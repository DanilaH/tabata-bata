package ru.tabatabata

import android.app.Activity
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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
    private fun capture(name: String) {
        val dir = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "demo-ads").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "events.txt").writeText(logs())
    }
    @Test fun actualDemoBannerAndInterstitialRespectFrequency() {
        assertEquals("demo-banner-yandex", BuildConfig.BANNER_ID)
        assertEquals("demo-interstitial-yandex", BuildConfig.INTERSTITIAL_ID)
        assertTrue("No production analytics in probe", BuildConfig.APPMETRICA_KEY.isBlank())
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
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Demo close callback missing", 20000) { event("ad_closed", "interstitial") }
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
