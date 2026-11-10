package ru.tabatabata

import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class EngagementTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = compose.activity.getString(id)
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val dir=File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),"engagement-0100").apply { mkdirs() }
        val image=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun supportReportShowsOnlySelectedTechnicalDataAndCopiesDraft() {
        val store=Store(context)
        val before=runBlocking { store.presets.first() }
        try {
            runBlocking { store.updatePreset(Preset("private-support-test","PRIVATE_TIMER_NAME",Plan())) }
            compose.onNodeWithText(text(R.string.settings)).performClick()
            compose.onNodeWithText(text(R.string.report_problem)).performScrollTo().assertIsDisplayed()
            capture("settings-feedback")
            compose.onNodeWithText(text(R.string.report_problem)).performClick()
            compose.onNodeWithTag("support-technical").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
            compose.onNodeWithText("PRIVATE_TIMER_NAME").assertDoesNotExist()
            compose.onNodeWithTag("support-description").performTextInput("Test: sound stopped")
            compose.onNodeWithTag("support-description").performImeAction()
            capture("support-report")
            compose.onNodeWithText(text(R.string.report_copy)).performClick()
            val clip=context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(clip.contains("Test: sound stopped"))
            assertTrue(clip.contains(BuildConfig.VERSION_NAME))
            assertFalse(clip.contains("PRIVATE_TIMER_NAME"))
            assertFalse(clip.contains("session_id"))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("support-description").assertTextContains("Test: sound stopped")
            compose.onNodeWithText(text(R.string.preview_close)).performClick()
            compose.onNodeWithText(text(R.string.review_store_link)).assertIsDisplayed()
        } finally { runBlocking { store.savePresets(before) } }
    }

    @Test fun reportActionsRemainReachableWithLargeTextAndLandscape() {
        val inst=InstrumentationRegistry.getInstrumentation()
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        val font=shell("settings get system font_scale").trim().toFloatOrNull() ?: 1f
        try {
            shell("settings put system font_scale 1.5")
            compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale>=1.49f }
            compose.onNodeWithText(text(R.string.settings)).performClick()
            compose.onNodeWithText(text(R.string.report_problem)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.report_email)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.preview_close)).assertIsDisplayed()
            capture("support-large-text")
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText(text(R.string.preview_close)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.report_email)).assertIsDisplayed()
            capture("support-landscape-large-text")
            compose.onNodeWithText(text(R.string.preview_close)).performClick()
        } finally {
            shell("settings put system font_scale $font")
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun reviewBudgetIsDurableAtomicAndIndependentOfHistoryDeletion() = runBlocking {
        val prefs=context.getSharedPreferences("review-policy",0)
        val before=prefs.all
        val ads=context.getSharedPreferences("ad-policy",0)
        val lastAd=ads.getLong("last-shown",0)
        try {
            prefs.edit().clear().commit();ads.edit().remove("last-shown").commit()
            val now=System.currentTimeMillis()
            val results=listOf(async { ReviewPreferences(context).claim(now) },async { ReviewPreferences(context).claim(now) }).awaitAll()
            assertEquals(1,results.count { it })
            assertEquals(1,ReviewPreferences(context).read().attempts)
            HistoryRepository(context).clear()
            assertFalse(ReviewPreferences(context).claim(now+1000))
            assertFalse(ReviewPreferences(context).claim(now-1))
            assertTrue(ReviewPreferences(context).claim(now+ReviewPolicy.COOLDOWN))
            assertFalse(ReviewPreferences(context).claim(now+2*ReviewPolicy.COOLDOWN))
            val intent=supportMailIntent("test@example.invalid","Body & русский\nline","Subject & ?")
            assertEquals(android.content.Intent.ACTION_SENDTO,intent.action)
            assertEquals("mailto",intent.data!!.scheme)
            assertEquals("Body & русский\nline",intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
            assertFalse(RuStoreReviews(compose.activity).available) // Real review stays disabled in debug tests.
        } finally {
            val edit=prefs.edit().clear()
            before.forEach { (k,v) -> when(v) { is Int -> edit.putInt(k,v);is Long -> edit.putLong(k,v);is Boolean -> edit.putBoolean(k,v) } }
            edit.commit();ads.edit().putLong("last-shown",lastAd).commit()
        }
    }
}
