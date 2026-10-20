package ru.tabatabata

import android.app.Activity
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import android.view.ViewGroup
import com.yandex.mobile.ads.banner.BannerAdView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real demo SDK requests only; never compiled into production or ordinary CI. */
class AdsDemoTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun logs() = shell("logcat -d -s TabataEvents:D '*:S'")
    private fun event(name: String, format: String): Boolean = logs().lineSequence().any {
        it.contains(name + " ") && it.contains("\"format\":\"$format\"")
    }
    private fun await(message: String, timeout: Long = 90000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            compose.waitForIdle()
            if (predicate()) return
            if (SystemClock.elapsedRealtime() >= deadline) fail(message + "\n" + logs())
            SystemClock.sleep(500)
        }
    }
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
    private fun tree(node: AccessibilityNodeInfo?, indent: String = ""): String {
        if (node == null) return ""
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return buildString {
            appendLine("$indent${node.className} id=${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} enabled=${node.isEnabled} visible=${node.isVisibleToUser} bounds=$bounds")
            for (i in 0 until node.childCount) append(tree(node.getChild(i), indent + "  "))
        }
    }
    private fun capture(name: String) {
        val dir = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "demo-ads").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "events.txt").writeText(logs())
        File(dir, "$name-tree.txt").writeText(tree(instrumentation.uiAutomation.rootInActiveWindow))
    }
    private fun banner(view: View): BannerAdView? {
        if (view is BannerAdView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            banner(view.getChildAt(i))?.let { return it }
        }
        return null
    }
    private fun assertSafeBanner(name: String) {
        val beforeLoad = compose.onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot
        await("Banner did not become visible: $name") {
            var visible = false
            compose.runOnUiThread {
                visible = banner(compose.activity.window.decorView)?.let { it.isShown && it.height > 0 } == true
            }
            visible && event("ad_impression", "banner")
        }
        val content = compose.onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot
        assertEquals("Loading must not change content height: $name", beforeLoad.bottom, content.bottom, 1f)
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val view = requireNotNull(banner(decor))
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val origin = IntArray(2); decor.getLocationOnScreen(origin)
            val point = IntArray(2); view.getLocationOnScreen(point)
            val x = point[0] - origin[0]; val y = point[1] - origin[1]
            assertTrue("Left safe area: $name", x >= insets.left)
            assertTrue("Right safe area: $name", x + view.width <= decor.width - insets.right)
            assertTrue("Bottom safe area: $name", y + view.height <= decor.height - insets.bottom)
            assertTrue("Content overlap: $name", content.bottom <= y - 7 * view.resources.displayMetrics.density)
        }
        capture(name)
    }
    private fun assertNoBanner() {
        compose.waitForIdle()
        compose.runOnUiThread { assertNull(banner(compose.activity.window.decorView)) }
    }
    @Test fun realScreensRespectSafeAreasAndRemoveAdsDuringWorkout() {
        assertEquals("demo-banner-yandex", BuildConfig.BANNER_ID)
        assertTrue(BuildConfig.APPMETRICA_KEY.isBlank())
        val originalNav = shell("cmd overlay list").lineSequence().firstOrNull {
            it.startsWith("[x]") && it.contains("com.android.internal.systemui.navbar.")
        }?.substringAfter("] ")?.trim()
        try {
            for (nav in listOf("gestural", "threebutton")) {
                shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.$nav")
                for (landscape in listOf(false, true)) {
                    shell("logcat -c")
                    compose.runOnUiThread {
                        compose.activity.requestedOrientation = if (landscape)
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    SystemClock.sleep(1500)
                    assertSafeBanner("home-$nav-$landscape")
                }
            }
            // Narrow portrait: actual app, actual SDK view, no replacement setContent.
            shell("wm size 720x1280")
            shell("wm density 360")
            shell("logcat -c")
            compose.runOnUiThread {
                compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            SystemClock.sleep(1500)
            assertSafeBanner("home-narrow")
            compose.onNodeWithText(compose.activity.getString(R.string.settings)).performClick()
            assertNoBanner()
            instrumentation.runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(compose.activity.getString(R.string.new_timer), substring = true).performClick()
            assertNoBanner()
            instrumentation.runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.runOnUiThread { TimerService.command(compose.activity, "start", Plan(0, 30, 10, 2)) }
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.RUNNING }
            assertNoBanner()
            compose.runOnUiThread { TimerService.command(compose.activity, "pause") }
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
            assertNoBanner()
            compose.runOnUiThread { TimerService.command(compose.activity, "stop") }
            compose.waitUntil(5000) { TimerService.state.value == null }
            shell("logcat -c")
            compose.runOnUiThread { TimerService.command(compose.activity, "start", Plan(0, 1, 0, 1)) }
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.COMPLETED }
            assertSafeBanner("result-narrow")
            compose.onNodeWithText(compose.activity.getString(R.string.repeat)).assertIsDisplayed().performClick()
            assertNoBanner()
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.COMPLETED }
            compose.onNodeWithText(compose.activity.getString(R.string.home)).assertIsDisplayed()
            compose.runOnUiThread { TimerService.clearResult() }
            shell("svc wifi disable"); shell("svc data disable"); shell("logcat -c")
            compose.activityRule.scenario.recreate()
            await("Offline banner failure callback missing", 120000) { event("ad_error", "banner") }
            capture("home-offline")
            compose.onNodeWithText(compose.activity.getString(R.string.new_timer), substring = true).assertIsDisplayed().performClick()
            assertNoBanner()
            compose.runOnUiThread { TimerService.command(compose.activity, "start", Plan(0, 30, 0, 1)) }
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.RUNNING }
            assertNoBanner()
        } finally {
            capture("real-screens-final")
            compose.runOnUiThread { TimerService.command(compose.activity, "stop") }
            shell("svc wifi enable"); shell("svc data enable")
            shell("wm size reset"); shell("wm density reset")
            if (originalNav != null) shell("cmd overlay enable-exclusive --category $originalNav")
        }
    }

    @Test fun actualDemoBannerAndInterstitialRespectFrequency() {
        assertEquals("demo-banner-yandex", BuildConfig.BANNER_ID)
        assertEquals("demo-interstitial-yandex", BuildConfig.INTERSTITIAL_ID)
        assertTrue("No production analytics in probe", BuildConfig.APPMETRICA_KEY.isBlank())
        val serviceInfo = instrumentation.uiAutomation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = serviceInfo
        compose.waitForIdle()
        val prefs = compose.activity.getSharedPreferences("ad-policy", Activity.MODE_PRIVATE)
        lateinit var ads: Ads
        shell("logcat -c")
        compose.runOnUiThread {
            prefs.edit().clear().commit()
            ads = Ads(compose.activity)
            compose.activity.setContent { MaterialTheme { AdBanner(ads) } }
        }
        try {
            await("Demo banner did not load") { event("ad_loaded", "banner") }
            await("Demo banner impression missing") { event("ad_impression", "banner") }
            capture("banner")
            await("Demo interstitial did not load") { event("ad_loaded", "interstitial") }
            compose.runOnUiThread {
                ads.completed("first-demo")
                assertFalse("First completion must not show an ad", ads.maybeShow(Status.COMPLETED))
                ads.completed("second-demo")
                assertFalse("Stop must not show an ad", ads.maybeShow(Status.STOPPED))
                assertTrue("Loaded second completion should show demo", ads.maybeShow(Status.COMPLETED))
            }
            await("Interstitial onAdShown missing") { prefs.getLong("last-shown", 0) > 0 }
            await("Interstitial impression missing") { event("ad_impression", "interstitial") }
            capture("interstitial")
            SystemClock.sleep(10000)
            if (!dismissControl(instrumentation.uiAutomation.rootInActiveWindow)) dismissObservedStory()
            capture("after-skip")
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (!event("ad_closed", "interstitial") && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(1000)
                if (!dismissControl(instrumentation.uiAutomation.rootInActiveWindow)) dismissObservedStory()
            }
            await("Demo close callback missing", 5000) { event("ad_closed", "interstitial") }
            compose.runOnUiThread {
                ads.completed("third-demo")
                assertFalse("24h cap must prevent another ad", ads.maybeShow(Status.COMPLETED))
            }
            capture("after-close")
        } finally {
            capture("final-state")
            compose.runOnUiThread { ads.close(); prefs.edit().clear().commit() }
        }
    }
}
