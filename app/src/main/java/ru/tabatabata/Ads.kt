package ru.tabatabata

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mobile.ads.banner.*
import com.yandex.mobile.ads.common.*
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.*

/** No loading callback ever blocks or navigates the workout UI. */
class Ads(private val activity: Activity) {
    private var loader: InterstitialAdLoader? = null
    private var ad: InterstitialAd? = null
    private var dead = false
    private val handler = Handler(Looper.getMainLooper())
    private val retry = AdLoadRetry(
        schedule = { delay, action -> handler.postDelayed(Runnable { action() }, delay); Unit },
        cancel = { handler.removeCallbacksAndMessages(null) },
        reload = { load() },
    )
    var ready by mutableStateOf(false)
        private set

    private val prefs = activity.getSharedPreferences("ad-policy", Activity.MODE_PRIVATE)

    init {
        if (BuildConfig.BANNER_ID.isNotBlank() || BuildConfig.INTERSTITIAL_ID.isNotBlank())
            YandexAds.initialize(activity) {
                if (!dead) {
                    ready = true
                    load()
                }
            }
    }

    private fun load() {
        if (BuildConfig.INTERSTITIAL_ID.isBlank() || dead || ad != null || !retry.begin()) return
        Telemetry.event("ad_request", mapOf("format" to "interstitial"))
        if (loader == null) loader = InterstitialAdLoader(activity)
        loader?.loadAd(
            AdRequest.Builder(BuildConfig.INTERSTITIAL_ID).build(),
            object : InterstitialAdLoadListener {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    if (!dead) {
                        retry.loaded()
                        this@Ads.ad = interstitialAd
                        Telemetry.event("ad_loaded", mapOf("format" to "interstitial"))
                    }
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    if (dead) return
                    retry.failed()
                    Telemetry.event(
                        "ad_error",
                        mapOf("format" to "interstitial", "stage" to "load"),
                    )
                }
            },
        )
    }

    fun completed(session: String) {
        if (prefs.getString("counted-session", null) != session)
            prefs
                .edit()
                .putString("counted-session", session)
                .putInt("completed", prefs.getInt("completed", 0) + 1)
                .apply()
    }

    fun maybeShow(result: Status): Boolean {
        val now = System.currentTimeMillis()
        if (
            !ready ||
                dead ||
                activity.isFinishing ||
                activity.isDestroyed ||
                !AdPolicy.mayShow(
                    result,
                    prefs.getInt("completed", 0),
                    prefs.getLong("last-shown", 0),
                    now,
                )
        )
            return false
        val loaded = ad ?: return false
        ad = null
        loaded.setAdEventListener(
            object : InterstitialAdEventListener {
                override fun onAdShown() {
                    prefs.edit().putLong("last-shown", System.currentTimeMillis()).apply()
                }

                override fun onAdFailedToShow(adError: AdError) {
                    loaded.setAdEventListener(null)
                    Telemetry.event(
                        "ad_error",
                        mapOf("format" to "interstitial", "stage" to "show"),
                    )
                    load()
                }

                override fun onAdDismissed() {
                    loaded.setAdEventListener(null)
                    Telemetry.event("ad_closed", mapOf("format" to "interstitial"))
                    load()
                }

                override fun onAdClicked() {}

                override fun onAdImpression(impressionData: ImpressionData?) {
                    Telemetry.event("ad_impression", mapOf("format" to "interstitial"))
                }
            }
        )
        loaded.show(activity)
        return true
    }

    fun close() {
        dead = true
        retry.close()
        ready = false
        ad?.setAdEventListener(null)
        ad = null
        loader = null
    }
}

@Composable
fun AdBanner(ads: Ads) {
    if (!ads.ready || BuildConfig.BANNER_ID.isBlank()) return
    val context = LocalContext.current
    // Scaffold delegates bottom-bar insets to this component. Measure the SDK banner
    // only inside the safe width, including side navigation/cutouts in landscape.
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(top = 8.dp)
    ) {
        val width = maxWidth.value.toInt()
        key(width) {
            AndroidView(
                factory = {
                    BannerAdView(context).apply {
                        setAdSize(BannerAdSize.sticky(context, width))
                        setBannerAdEventListener(
                            object : BannerAdEventListener {
                                override fun onAdLoaded() {
                                    Telemetry.event("ad_loaded", mapOf("format" to "banner"))
                                }

                                override fun onAdFailedToLoad(error: AdRequestError) {
                                    Telemetry.event(
                                        "ad_error",
                                        mapOf("format" to "banner", "stage" to "load"),
                                    )
                                }

                                override fun onAdClicked() {}

                                override fun onImpression(impressionData: ImpressionData?) {
                                    Telemetry.event("ad_impression", mapOf("format" to "banner"))
                                }
                            }
                        )
                        Telemetry.event("ad_request", mapOf("format" to "banner"))
                        loadAd(AdRequest.Builder(BuildConfig.BANNER_ID).build())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onRelease = { it.destroy() },
            )
        }
    }
}
