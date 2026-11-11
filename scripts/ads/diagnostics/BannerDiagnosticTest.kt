package ru.tabatabata

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated, demo-only investigation. Never replaces the SDK listener or fakes impressions. */
class BannerDiagnosticTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        inst.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun events() = shell("logcat -d -s TabataEvents:D '*:S'")
    private fun has(name: String) = events().lineSequence().any {
        it.contains("$name ") && it.contains("\"format\":\"banner\"")
    }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun capture(name: String, dir: File) {
        compose.waitForIdle()
        inst.waitForIdleSync()
        val states = StringBuilder()
        val webViews = mutableListOf<WebView>()
        compose.runOnUiThread {
            views(compose.activity.window.decorView).forEach { v ->
                val rect = Rect()
                val visible = v.getGlobalVisibleRect(rect)
                states.appendLine("${v.javaClass.name} size=${v.width}x${v.height} shown=${v.isShown} " +
                    "visible=$visible rect=$rect alpha=${v.alpha} window=${v.windowVisibility} " +
                    "focus=${v.hasWindowFocus()} hardware=${v.isHardwareAccelerated}")
                if (v is WebView) webViews.add(v)
            }
            states.appendLine("webview=${WebView.getCurrentWebViewPackage()?.versionName}")
        }
        webViews.forEachIndexed { index, web ->
            val done = CountDownLatch(1)
            var result = "not-returned"
            compose.runOnUiThread {
                states.appendLine("web[$index] javascript=${web.settings.javaScriptEnabled}")
                // Read-only MRAID/DOM state. No synthetic events, clicks or tracker requests.
                web.evaluateJavascript("""(function(){try{return JSON.stringify({
                    ready:document.readyState, visibility:document.visibilityState,
                    focus:document.hasFocus(), mraid:typeof mraid,
                    state:typeof mraid==='object'?mraid.getState():null,
                    viewable:typeof mraid==='object'?mraid.isViewable():null,
                    frames:window.frames.length,
                    bridges:Object.keys(window).filter(function(k){return /mraid|yandex|impression|bridge|monetiz/i.test(k);}),
                    snippets:Array.from(document.scripts).filter(function(s){return !s.src;}).map(function(s){
                        return (s.textContent.match(/.{0,120}(?:impression|Impression|adlib-mock|fetch|mraid).{0,200}/g)||[]).slice(-20);
                    }),
                    resources:performance.getEntriesByType('resource').slice(-60).map(function(r){
                        var origin;try{origin=new URL(r.name,document.baseURI).origin;}catch(e){origin='invalid';}
                        return {origin:origin,type:r.initiatorType,duration:r.duration,status:r.responseStatus||null};
                    })
                });}catch(e){return String(e);}})()""") { result = it; done.countDown() }
            }
            done.await(2, TimeUnit.SECONDS)
            states.appendLine("web[$index] state=$result")
        }
        File(dir, "$name-views.txt").writeText(states.toString())
        File(dir, "$name-events.txt").writeText(events())
        inst.uiAutomation.takeScreenshot()?.let { image ->
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun bannerRecordsSdkImpression() {
        assertTrue(BuildConfig.BANNER_ID in listOf("demo-banner-yandex", "demo-banner-adfox-html"))
        assertTrue(BuildConfig.INTERSTITIAL_ID.isBlank())
        assertTrue(BuildConfig.APPMETRICA_KEY.isBlank())
        val dir = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
            "ad-diagnostic").apply { mkdirs() }
        File(dir, "identity.txt").writeText("${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}\n")
        shell("logcat -c")
        lateinit var ads: Ads
        compose.runOnUiThread {
            WebView.setWebContentsDebuggingEnabled(true)
            com.yandex.mobile.ads.common.YandexAds.enableLogging(true)
            ads = Ads(compose.activity)
            ads.setPlacementAllowed(true)
            compose.activity.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))) {
                            Spacer(Modifier.weight(1f))
                            AdBanner(ads)
                        }
                    }
                }
            }
        }
        try {
            val started = SystemClock.elapsedRealtime()
            var loaded = false
            while (SystemClock.elapsedRealtime() - started < 90000 && !has("ad_impression")) {
                compose.waitForIdle()
                if (!loaded && has("ad_loaded")) { loaded = true; capture("loaded", dir) }
                SystemClock.sleep(500)
            }
            capture("final", dir)
            assertTrue("Banner did not load\n" + events(), has("ad_loaded"))
            assertTrue("SDK impression missing\n" + events(), has("ad_impression"))
        } finally {
            File(dir, "logcat.txt").writeText(shell("logcat -d"))
            compose.runOnUiThread { ads.close() }
        }
    }
}
