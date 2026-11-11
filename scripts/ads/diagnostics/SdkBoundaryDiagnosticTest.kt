package ru.tabatabata

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yandex.mobile.ads.banner.*
import com.yandex.mobile.ads.common.*
import com.yandex.mobile.ads.interstitial.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runners.MethodSorters

/** Native SDK control, no Compose, Ads wrapper, policy, production IDs or analytics. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SdkBoundaryDiagnosticTest {
    @get:Rule val rule = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ComponentActivity
    private lateinit var host: FrameLayout
    private var loader: InterstitialAdLoader? = null
    private var interstitial: InterstitialAd? = null
    private var banner: BannerAdView? = null
    private val events = CopyOnWriteArrayList<String>()
    private val dir get() = File(requireNotNull(InstrumentationRegistry.getArguments()
        .getString("additionalTestOutputDir")), "sdk-boundary").apply { mkdirs() }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun mark(name: String, code: Int? = null, message: String? = null) {
        val item = JSONObject().put("event", name).put("elapsed", SystemClock.elapsedRealtime())
        if (code != null) item.put("code", code)
        if (message != null) item.put("message", message.replace(Regex("https?://\\S+"), "[url]").take(400))
        events.add(item.toString())
        Log.d("BoundarySdk", item.toString())
    }
    private fun has(name: String) = events.any { JSONObject(it).getString("event") == name }
    private fun waitUntil(name: String, timeout: Long = 45000): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!has(name) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(250)
        return has(name)
    }
    @Before fun initialize() {
        assertTrue(BuildConfig.APPMETRICA_KEY.isBlank())
        assertTrue(BuildConfig.BANNER_ID.isBlank())
        assertTrue(BuildConfig.INTERSTITIAL_ID.isBlank())
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = info
        val ready = CountDownLatch(1)
        rule.scenario.onActivity {
            activity = it
            host = FrameLayout(it)
            host.setBackgroundColor(android.graphics.Color.WHITE)
            it.setContentView(host)
            WebView.setWebContentsDebuggingEnabled(true)
            YandexAds.enableLogging(true)
            YandexAds.initialize(it) { ready.countDown() }
        }
        assertTrue("SDK initialization timeout", ready.await(30, TimeUnit.SECONDS))
        mark("sdk.ready")
    }
    @After fun cleanup() {
        rule.scenario.onActivity {
            banner?.setBannerAdEventListener(null)
            banner?.destroy()
            interstitial?.setAdEventListener(null)
            loader = null
            interstitial = null
        }
    }
    private fun capture(name: String) {
        File(dir, "$name-events.jsonl").writeText(events.joinToString("\n"))
        File(dir, "$name-tree.txt").writeText(tree(instrumentation.uiAutomation.rootInActiveWindow))
        File(dir, "$name-scales.txt").writeText(
            listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
                .joinToString("\n") { "$it=${shell("settings get global $it").trim()}" })
        if (banner != null) {
            val result = StringBuilder()
            val webViews = mutableListOf<WebView>()
            fun collect(view: View) {
                val rect = Rect()
                val visible = view.getGlobalVisibleRect(rect)
                result.appendLine("${view.javaClass.name} size=${view.width}x${view.height} visible=$visible " +
                    "rect=$rect shown=${view.isShown} focus=${view.hasWindowFocus()} hardware=${view.isHardwareAccelerated}")
                if (view is WebView) webViews.add(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            rule.scenario.onActivity { collect(host) }
            for (web in webViews) {
                val done = CountDownLatch(1)
                rule.scenario.onActivity {
                    web.evaluateJavascript("""(function(){return JSON.stringify({
                        ready:document.readyState, visibility:document.visibilityState,
                        mraid:typeof mraid, state:typeof mraid==='object'?mraid.getState():null,
                        viewable:typeof mraid==='object'?mraid.isViewable():null
                    });})()""") { result.appendLine(it); done.countDown() }
                }
                done.await(2, TimeUnit.SECONDS)
            }
            File(dir, "$name-views.txt").writeText(result.toString())
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "$name-logcat.txt").writeText(shell("logcat -d -s BoundarySdk:D YandexAds:D 'Yandex Ads':D '*:S'"))
    }
    private fun loadInterstitial(id: String) {
        require(id in setOf("demo-banner-yandex", "demo-interstitial-yandex", "demo-interstitial-adfox-image"))
        rule.scenario.onActivity {
            loader = InterstitialAdLoader(it)
            mark("interstitial.request")
            loader!!.loadAd(AdRequest.Builder(id).build(), object : InterstitialAdLoadListener {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    mark("interstitial.loaded")
                }
                override fun onAdFailedToLoad(error: AdRequestError) {
                    mark("interstitial.load_error", error.code, error.description)
                }
            })
        }
    }
    @Test fun a_bannerIdInInterstitialLoaderIsRejected() {
        try {
            loadInterstitial("demo-banner-yandex")
            val until = SystemClock.elapsedRealtime() + 45000
            while (!has("interstitial.loaded") && !has("interstitial.load_error") &&
                SystemClock.elapsedRealtime() < until) SystemClock.sleep(250)
            assertFalse("Wrong-format block unexpectedly loaded", has("interstitial.loaded"))
            assertTrue("Wrong-format request did not produce error callback", has("interstitial.load_error"))
        } finally { capture("wrong-format") }
    }
    private fun runBanner(id: String, name: String) {
        require(id in setOf("demo-banner-yandex", "demo-banner-adfox-html"))
        try {
            val attached = CountDownLatch(1)
            rule.scenario.onActivity {
                host.post {
                    val density = it.resources.displayMetrics.density
                    val size = BannerAdSize.sticky(it, (host.width / density).toInt())
                    val view = BannerAdView(it)
                    banner = view
                    view.setAdSize(size)
                    view.setBannerAdEventListener(object : BannerAdEventListener {
                        override fun onAdLoaded() { mark("banner.loaded") }
                        override fun onAdFailedToLoad(error: AdRequestError) {
                            mark("banner.load_error", error.code, error.description)
                        }
                        override fun onAdClicked() { mark("banner.clicked") }
                        override fun onImpression(data: ImpressionData?) { mark("banner.impression") }
                    })
                    host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        (size.height * density).toInt(), Gravity.BOTTOM))
                    mark("banner.request")
                    view.loadAd(AdRequest.Builder(id).build())
                    attached.countDown()
                }
            }
            assertTrue("Native host not attached", attached.await(10, TimeUnit.SECONDS))
            assertTrue("Banner did not load", waitUntil("banner.loaded"))
            capture("$name-loaded")
            assertTrue("Native SDK banner impression missing", waitUntil("banner.impression", 75000))
        } finally { capture("$name-final") }
    }
    @Test fun b_nativeYandexBanner() = runBanner("demo-banner-yandex", "banner-yandex")
    @Test fun c_nativeHtmlBanner() = runBanner("demo-banner-adfox-html", "banner-html")
    private fun runInterstitial(id: String, name: String) {
        try {
            loadInterstitial(id)
            assertTrue("Correct-format interstitial did not load", waitUntil("interstitial.loaded"))
            rule.scenario.onActivity {
                interstitial!!.setAdEventListener(object : InterstitialAdEventListener {
                    override fun onAdShown() { mark("interstitial.shown") }
                    override fun onAdFailedToShow(error: AdError) { mark("interstitial.show_error") }
                    override fun onAdDismissed() { mark("interstitial.closed") }
                    override fun onAdClicked() { mark("interstitial.clicked") }
                    override fun onAdImpression(data: ImpressionData?) { mark("interstitial.impression") }
                })
                interstitial!!.show(it)
            }
            assertTrue("Interstitial did not show", waitUntil("interstitial.shown"))
            assertTrue("Interstitial impression missing", waitUntil("interstitial.impression"))
            capture("$name-shown")
            val until = SystemClock.elapsedRealtime() + 90000
            var snapshot = 0
            val start = SystemClock.elapsedRealtime()
            while (!has("interstitial.closed") && SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(1000)
                dismissDemoInterstitial()
                val elapsed = SystemClock.elapsedRealtime() - start
                if (snapshot == 0 && elapsed > 10000 || snapshot == 1 && elapsed > 35000) {
                    snapshot++
                    capture("$name-progress-$snapshot")
                }
            }
            assertTrue("Native SDK interstitial did not close", has("interstitial.closed"))
            assertFalse("Test must not click ad/CTA", has("interstitial.clicked"))
        } finally { capture("$name-final") }
    }
    @Test fun d_nativeYandexInterstitial() = runInterstitial("demo-interstitial-yandex", "interstitial-yandex")
    @Test fun e_nativeImageInterstitial() = runInterstitial("demo-interstitial-adfox-image", "interstitial-image")

    private fun dismissControl(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || node.packageName?.toString() != "ru.tabatabata") return false
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        val isControl = id in setOf("monetization_ads_close", "monetization_ads_instream_skip") ||
            description in setOf("close", "skip", "close ad", "skip ad", "закрыть", "пропустить", "закрыть рекламу", "пропустить рекламу")
        if (isControl && node.isVisibleToUser && node.isEnabled) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.uiAutomation.injectInputEvent(event, true)
                    event.recycle()
                }
                return true
            }
        }
        for (i in 0 until node.childCount) if (dismissControl(node.getChild(i))) return true
        return false
    }
    private fun demoLabel(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains("[demo ad]", ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) demoLabel(node.getChild(i))?.let { return it }
        return null
    }
    private fun storyControl(node: AccessibilityNodeInfo?, width: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        if (node.className?.toString() == "android.widget.ImageView" && node.isVisibleToUser &&
            node.isEnabled && bounds.left > width * .85 && bounds.width() in 20..(width / 8) &&
            bounds.height() in 20..(width / 8)) return node
        for (i in 0 until node.childCount) storyControl(node.getChild(i), width)?.let { return it }
        return null
    }
    private fun dismissObservedStory(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "ru.tabatabata") return false
        val screen = Rect(); root.getBoundsInScreen(screen)
        var row = demoLabel(root)?.parent
        repeat(8) {
            val current = row ?: return false
            val bounds = Rect(); current.getBoundsInScreen(bounds)
            // Grounded in captured SDK trees: small rightmost image inside the DEMO metadata row.
            // The metadata row moves between video and static cards; never tap the ad body/CTA.
            if (bounds.width() > screen.width() * .8 && bounds.height() < screen.height() * .2) {
                val control = storyControl(current, screen.width())
                if (control != null) {
                    val target = Rect(); control.getBoundsInScreen(target)
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        instrumentation.uiAutomation.injectInputEvent(event, true)
                        event.recycle()
                    }
                    return true
                }
            }
            row = current.parent
        }
        return false
    }
    private fun hasStoryVideo(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.viewIdResourceName?.substringAfterLast('/') in setOf("video_mute_control", "video_progress_control"))
            return true
        for (i in 0 until node.childCount) if (hasStoryVideo(node.getChild(i))) return true
        return false
    }
    private var lastStoryBack = 0L
    private var storyBackCount = 0
    private fun dismissDemoInterstitial(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "ru.tabatabata") return false
        if (dismissControl(root) || dismissObservedStory()) return true
        // Captured SDK story creatives can omit an accessible close control.
        // Exercise the visible Android Back action, while the SDK owns that window.
        val now = SystemClock.elapsedRealtime()
        if (hasStoryVideo(root) && storyBackCount < 3 && now - lastStoryBack >= 5000) {
            lastStoryBack = now
            storyBackCount++
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            return true
        }
        return false
    }
    private fun tree(node: AccessibilityNodeInfo?, indent: String = ""): String {
        if (node == null) return ""
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return buildString {
            appendLine("$indent${node.className} pkg=${node.packageName} id=${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} enabled=${node.isEnabled} visible=${node.isVisibleToUser} bounds=$bounds")
            for (i in 0 until node.childCount) append(tree(node.getChild(i), indent + "  "))
        }
    }

}
