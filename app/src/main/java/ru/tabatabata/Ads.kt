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

/** Loading never navigates. Only an explicit result-to-menu action may show. */
class Ads(private val activity: Activity) {
    private var loader: InterstitialAdLoader? = null
    private var ad: InterstitialAd? = null
    private var loadedAt = 0L
    private var showing: InterstitialAd? = null
    private var dead = false
    private var placementAllowed = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("ad-policy", Activity.MODE_PRIVATE)
    private val lifecycle = (activity as androidx.lifecycle.LifecycleOwner).lifecycle
    var foreground by mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    private val retry = AdLoadRetry(
        schedule = { delay, action -> handler.postDelayed(Runnable { action() }, delay); Unit },
        cancel = { handler.removeCallbacksAndMessages(null) },
        reload = { load() },
        now = { android.os.SystemClock.elapsedRealtime() },
    )
    private val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
        foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        updateEligibility()
    }

    init {
        lifecycle.addObserver(observer)
        if (BuildConfig.BANNER_ID.isNotBlank() || BuildConfig.INTERSTITIAL_ID.isNotBlank())
            YandexAds.initialize(activity) {
                if (!dead) {
                    ready = true
                    updateEligibility()
                }
            }
    }

    fun setPlacementAllowed(allowed: Boolean) {
        if (placementAllowed == allowed) return
        placementAllowed = allowed
        updateEligibility()
    }

    private fun updateEligibility() {
        if (dead) return
        retry.setEnabled(ready && foreground && placementAllowed && !fullscreen &&
            BuildConfig.INTERSTITIAL_ID.isNotBlank() && AdPolicy.mayPrefetch(
                prefs.getInt("completed", 0), prefs.getLong("last-shown", 0), System.currentTimeMillis()))
    }

    private fun discardExpired() {
        if (ad != null && android.os.SystemClock.elapsedRealtime() - loadedAt >= 45 * 60_000L) {
            ad?.setAdEventListener(null)
            ad = null
        }
    }

    private fun load() {
        discardExpired()
        if (dead || ad != null || fullscreen) return
        val token = retry.begin() ?: return
        val attempt = retry.attempt
        Telemetry.event("ad_request", mapOf("format" to "interstitial", "attempt" to attempt))
        if (loader == null) loader = InterstitialAdLoader(activity)
        loader?.loadAd(
            AdRequest.Builder(BuildConfig.INTERSTITIAL_ID).build(),
            object : InterstitialAdLoadListener {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    if (!retry.loaded(token)) return
                    ad = interstitialAd
                    loadedAt = android.os.SystemClock.elapsedRealtime()
                    Telemetry.event("ad_loaded", mapOf("format" to "interstitial", "attempt" to attempt))
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    if (!retry.failed(token)) return
                    Telemetry.event("ad_error", mapOf("format" to "interstitial", "stage" to "load",
                        "code" to error.code, "attempt" to attempt))
                }
            },
        )
    }

    fun completed(session: String) {
        if (prefs.getString("counted-session", null) != session) {
            prefs.edit().putString("counted-session", session)
                .putInt("completed", prefs.getInt("completed", 0) + 1).apply()
            updateEligibility()
        }
    }

    fun maybeShow(result: Status): Boolean {
        discardExpired()
        val reason = when {
            dead || activity.isFinishing || activity.isDestroyed -> "closed"
            !foreground -> "background"
            fullscreen -> "in_progress"
            result != Status.COMPLETED -> "not_completed"
            prefs.getInt("completed", 0) <= 1 -> "first_completion"
            !AdPolicy.mayShow(result, prefs.getInt("completed", 0),
                prefs.getLong("last-shown", 0), System.currentTimeMillis()) -> "daily_limit"
            !placementAllowed -> "screen"
            !ready -> "sdk_not_ready"
            ad == null -> "not_loaded"
            else -> null
        }
        if (reason != null) {
            Telemetry.event("ad_skipped", mapOf("format" to "interstitial", "reason" to reason))
            updateEligibility() // May prefetch for a later action; never a delayed show.
            return false
        }
        val loaded = ad ?: return false
        ad = null
        showing = loaded // Keep a strong reference until the final callback.
        fullscreen = true
        updateEligibility()
        Telemetry.event("ad_show_attempt", mapOf("format" to "interstitial"))
        loaded.setAdEventListener(object : InterstitialAdEventListener {
            private var shown = false
            override fun onAdShown() {
                if (dead || showing !== loaded || shown) return
                shown = true
                prefs.edit().putLong("last-shown", System.currentTimeMillis()).apply()
                Telemetry.event("ad_shown", mapOf("format" to "interstitial"))
            }
            override fun onAdFailedToShow(adError: AdError) {
                if (dead || showing !== loaded) return
                finish()
                Telemetry.event("ad_error", mapOf("format" to "interstitial", "stage" to "show"))
                // No immediate show/load loop. A later eligible action may prefetch.
            }
            override fun onAdDismissed() {
                if (dead || showing !== loaded) return
                finish()
                Telemetry.event("ad_closed", mapOf("format" to "interstitial"))
            }
            private fun finish() {
                loaded.setAdEventListener(null)
                showing = null
                fullscreen = false
            }
            override fun onAdClicked() {}
            override fun onAdImpression(impressionData: ImpressionData?) {
                if (!dead && showing === loaded)
                    Telemetry.event("ad_impression", mapOf("format" to "interstitial"))
            }
        })
        loaded.show(activity)
        return true
    }

    fun close() {
        dead = true
        lifecycle.removeObserver(observer)
        retry.close()
        ready = false
        ad?.setAdEventListener(null)
        showing?.setAdEventListener(null)
        ad = null
        showing = null
        loader = null
    }
}

@Composable
fun AdBanner(ads: Ads) {
    if (BuildConfig.BANNER_ID.isBlank()) return
    val context = LocalContext.current
    // Scaffold delegates bottom-bar insets to this component. Measure the SDK banner
    // only inside the safe width, including side navigation/cutouts in landscape.
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(top = 8.dp)
    ) {
        val width = maxWidth.value.toInt()
        if (width <= 0) return@BoxWithConstraints
        // Initialization can refine sticky sizing. Never reuse the preliminary
        // size object for the first SDK view created after initialization.
        val size = remember(context, width, ads.ready) { BannerAdSize.sticky(context, width) }
        // Reserve the SDK-calculated height before init/load and after a failed load.
        // A late creative must not move controls underneath the user's finger.
        Box(Modifier.fillMaxWidth().height(size.height.dp)) {
            if (ads.ready) key(width) {
                AndroidView(
                    factory = {
                        BannerAdView(context).apply {
                            val lease = BannerLease()
                            // Leave the SDK view's ordinary tag available to the SDK.
                            setTag(R.id.tabata_banner_lease, lease)
                            setAdSize(size)
                            setBannerAdEventListener(
                                object : BannerAdEventListener {
                                    override fun onAdLoaded() {
                                        if (!lease.alive) return
                                        Telemetry.event("ad_loaded", mapOf("format" to "banner"))
                                    }
    
                                    override fun onAdFailedToLoad(error: AdRequestError) {
                                        if (!lease.alive) return
                                        Telemetry.event(
                                            "ad_error",
                                            mapOf("format" to "banner", "stage" to "load", "code" to error.code),
                                        )
                                    }
    
                                    override fun onAdClicked() {}
    
                                    override fun onImpression(impressionData: ImpressionData?) {
                                        if (!lease.alive) return
                                        Telemetry.event("ad_impression", mapOf("format" to "banner"))
                                    }
                                }
                            )
                            Telemetry.event("ad_request", mapOf("format" to "banner", "scope" to "initial_load"))
                            loadAd(AdRequest.Builder(BuildConfig.BANNER_ID).build())
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        (it.getTag(R.id.tabata_banner_lease) as? BannerLease)?.alive = false
                        it.setTag(R.id.tabata_banner_lease, null)
                        it.setBannerAdEventListener(null)
                        it.destroy()
                    },
                )
            }
        }
    }
}

private class BannerLease(var alive: Boolean = true)
