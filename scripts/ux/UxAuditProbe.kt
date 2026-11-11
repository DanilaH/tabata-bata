package ru.tabatabata

import android.app.LocaleManager
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.*
import android.view.KeyEvent
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Observational audit of real App navigation. No production ads, mail or review submission. */
class UxAuditProbe {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val store get() = Store(context)
    private val output get() = File(requireNotNull(args.getString("additionalTestOutputDir"))).apply { mkdirs() }
    private fun text(id: Int) = compose.activity.getString(id)
    private val longName = "Силовая тренировка — ноги, спина, плечи, три серии с длинным отдыхом и разминкой перед занятием".take(100)
    private val presets get() = listOf(
        Preset("ux-short", "Табата 20/10", Plan(), "green", 2),
        Preset("ux-long", longName, Plan(prepare=61,work=61,rest=61,reps=2,sets=2,between=61,cool=61), "violet", 1),
        Preset("ux-wide", "W".repeat(100), Plan(0,3600,0,20), "orange", 3))
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun stop() {
        if (TimerService.state.value != null) {
            compose.runOnUiThread {
                if (TimerService.state.value?.status==Status.COMPLETED) TimerService.clearResult()
                else TimerService.command(context,"stop")
            }
            compose.waitUntil(10000) { TimerService.state.value == null }
        }
    }
    @Before fun prepare() {
        assertEquals("demo-banner-adfox-html",BuildConfig.BANNER_ID)
        assertTrue(BuildConfig.INTERSTITIAL_ID.isBlank() && BuildConfig.APPMETRICA_KEY.isBlank())
        stop()
        context.getSharedPreferences("session",0).edit().clear().commit()
        context.getSharedPreferences("ui",0).edit().putBoolean("notificationAsked",true).commit()
        EditorDrafts(context).clear("new")
        presets.forEach { EditorDrafts(context).clear(it.id) }
        val language = args.getString("uxLocale") ?: "ru"
        val locales = context.getSystemService(LocaleManager::class.java)
        if (locales.applicationLocales.toLanguageTags() != language)
            locales.applicationLocales = LocaleList.forLanguageTags(language)
        compose.waitUntil(15000) { compose.activity.resources.configuration.locales[0].language == language }
        compose.runOnUiThread { compose.activity.requestedOrientation =
            if (args.getString("uxOrientation")=="landscape") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10000) { (compose.activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) == (args.getString("uxOrientation")=="landscape") }
        runBlocking {
            store.saveOptions(Options().copy(theme=args.getString("uxTheme")?:"light",volume=0f,voice=false,vibrate=false))
            store.savePresets(presets)
            HistoryRepository(context).clear()
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText(presets.first().name).fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun finish() { stop() }
    private fun capture(name: String) {
        compose.waitForIdle()
        inst.waitForIdleSync()
        SystemClock.sleep(220)
        val image = requireNotNull(inst.uiAutomation.takeScreenshot())
        File(output,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        val nodes = compose.onAllNodes(SemanticsMatcher("Text layout") {
            it.config.contains(SemanticsActions.GetTextLayoutResult)
        },useUnmergedTree=true).fetchSemanticsNodes()
        val all = JSONArray()
        compose.runOnUiThread {
            for (node in nodes) {
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                for (layout in results) {
                    val b=node.boundsInWindow
                    val ellipsis=(0 until layout.lineCount).any { layout.isLineEllipsized(it) }
                    all.put(JSONObject().put("text",layout.layoutInput.text.text)
                        .put("font",layout.layoutInput.style.fontSize.value)
                        .put("width",layout.size.width).put("height",layout.size.height)
                        .put("bounds", "$b").put("overflow",layout.hasVisualOverflow)
                        .put("widthOverflow",layout.didOverflowWidth).put("heightOverflow",layout.didOverflowHeight)
                        .put("ellipsized",ellipsis).put("editable",node.config.contains(SemanticsProperties.EditableText)))
                }
            }
        }
        val c=compose.activity.resources.configuration
        File(output,"$name-layout.json").writeText(JSONObject().put("texts",all).put("fontScale",c.fontScale)
            .put("widthDp",c.screenWidthDp).put("heightDp",c.screenHeightDp)
            .put("language",c.locales[0].toLanguageTag()).toString(2))
    }
    private fun at(id: Int) = compose.onNodeWithText(text(id))
    private fun back() { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle() }
    private fun hideKeyboard() {
        compose.runOnUiThread { compose.activity.window.decorView.windowInsetsController?.hide(android.view.WindowInsets.Type.ime()) }
        SystemClock.sleep(400)
        compose.waitForIdle()
    }

    @Test fun a_homeMenuAndLongSavedNames() {
        try {
            capture("01-home-saved")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions,presets.first().name)).performClick()
            capture("02-mode-menu")
            at(R.string.edit).assertIsDisplayed()
            at(R.string.copy).assertIsDisplayed()
            at(R.string.delete).performScrollTo().assertIsDisplayed()
            capture("02-mode-menu-bottom")
            back()
            runBlocking { store.savePresets(emptyList()) }
            compose.waitUntil(10000) { compose.onAllNodesWithText(presets.first().name).fetchSemanticsNodes().isEmpty() }
            capture("03-home-empty")
            runBlocking { store.saveOptions(store.options.first().copy(showReadyModes=false)) }
            compose.waitForIdle()
            capture("04-home-no-ready")
            compose.onNodeWithText("+ "+text(R.string.new_timer)).assertIsDisplayed().performClick()
            at(R.string.start).assertIsDisplayed()
        } finally { capture("a-final") }
    }
    @Test fun b_editorValidationAppearanceAndLeaveDialog() {
        try {
            compose.onNodeWithText(presets.first().name).performClick()
            capture("05-editor-top")
            val work=compose.onNodeWithContentDescription(text(R.string.work))
            work.performScrollTo().performTextReplacement("60:00")
            compose.onNodeWithText(text(R.string.start)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.save)).assertIsDisplayed()
            capture("06-editor-keyboard")
            hideKeyboard()
            work.performTextReplacement("99:99")
            compose.onNodeWithText(text(R.string.start)).assertIsNotEnabled()
            capture("07-editor-invalid")
            work.performTextReplacement("0:20")
            hideKeyboard()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.card_mark_label,text(R.string.mark_sets)))
                .performScrollTo().assertIsDisplayed()
            capture("08-editor-appearance")
            compose.onNodeWithContentDescription(text(R.string.plan_preview)).performClick()
            capture("09-editor-preview")
            at(R.string.preview_close).assertIsDisplayed().performClick()
            compose.onNodeWithText("← "+text(R.string.saved)).performScrollTo().performClick()
            at(R.string.unsaved).assertIsDisplayed()
            capture("10-unsaved-dialog")
            at(R.string.stay).assertIsDisplayed()
            at(R.string.reset).assertIsDisplayed()
            compose.onAllNodesWithText(text(R.string.save)).onLast().assertIsDisplayed()
        } finally { capture("b-final") }
    }
    @Test fun c_historyLongNamesAndExpandedPlan() {
        try {
            compose.onNodeWithText(text(R.string.history_open)+" →").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(text(R.string.history_empty_title)).fetchSemanticsNodes().isNotEmpty() }
            capture("11-history-empty")
            val repo=HistoryRepository(context)
            repo.enqueue(WorkoutRecord("ux-record",presets[1].plan,longName,"violet",1,
                finishedAt=System.currentTimeMillis(),completed=true,activeMs=presets[1].plan.durationMs(),completedRounds=4))
            compose.waitUntil(10000) { compose.onAllNodesWithTag("history-ux-record").fetchSemanticsNodes().isNotEmpty() }
            capture("12-history-week")
            compose.onNodeWithTag("history-list").performScrollToNode(hasText(text(R.string.history_saved_plan)))
            at(R.string.history_saved_plan).assertIsDisplayed()
            at(R.string.repeat).assertIsDisplayed()
            capture("13-history-card")
            at(R.string.history_saved_plan).performClick()
            capture("14-history-plan")
            val setTitle=compose.activity.getString(R.string.preview_set,1,2)
            compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("plan-preview")))
                .performScrollToNode(hasText(setTitle))
            compose.onNodeWithText(setTitle).performClick()
            capture("15-plan-expanded")
            at(R.string.preview_close).assertIsDisplayed().performClick()
            at(R.string.history_clear).performScrollTo().performClick()
            capture("16-history-clear")
            at(R.string.cancel).assertIsDisplayed()
        } finally { capture("c-final") }
    }
    @Test fun d_settingsSoundsSupportAndKeyboard() {
        try {
            at(R.string.settings).performClick()
            capture("17-settings-sounds")
            at(R.string.finish_cue).performScrollTo().assertIsDisplayed()
            capture("18-settings-finish")
            at(R.string.vibration_preview).performScrollTo().assertIsDisplayed()
            capture("19-settings-vibration")
            at(R.string.show_ready_modes).performScrollTo().assertIsDisplayed()
            capture("20-settings-options")
            at(R.string.report_problem).performScrollTo().performClick()
            capture("21-support")
            compose.onNodeWithTag("support-description").performScrollTo().performTextInput("UX audit: button and keyboard check")
            capture("22-support-keyboard")
            compose.onNodeWithTag("support-description").performImeAction()
            compose.waitForIdle();SystemClock.sleep(600)
            at(R.string.report_copy).assertIsDisplayed().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.report_copied)).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread {
                val clip=context.getSystemService(ClipboardManager::class.java).primaryClip
                assertNotNull("Clipboard must contain copied report",clip)
                assertTrue(clip!!.getItemAt(0).text.contains("UX audit:"))
            }
            capture("23-support-copied")
            at(R.string.preview_close).assertIsDisplayed()
        } finally { capture("d-final") }
    }
    @Test fun e_workoutAllStagesClockGridAndLock() {
        try {
            compose.runOnUiThread { TimerService.command(context,"start",presets[1].plan) }
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.RUNNING }
            at(R.string.pause).performClick()
            compose.waitUntil(5000) { TimerService.state.value?.status==Status.PAUSED }
            val frames=JSONArray()
            repeat(9) { index ->
                val s=requireNotNull(TimerService.state.value)
                capture("24-stage-$index-${s.interval?.stage}")
                val clock=compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInWindow
                frames.put(JSONObject().put("stage",s.interval?.stage?.name).put("clock",clock.toString()))
                at(R.string.resume).assertIsDisplayed()
                compose.onNodeWithContentDescription(text(R.string.next)).assertIsDisplayed()
                if(index<8) {
                    compose.onNodeWithContentDescription(text(R.string.next)).performClick()
                    compose.waitUntil(5000) { TimerService.state.value?.index==index+1 }
                }
            }
            File(output,"clock-positions.json").writeText(frames.toString(2))
            compose.onNodeWithContentDescription(text(R.string.lock_short)).performClick()
            capture("25-workout-locked")
            compose.onNodeWithContentDescription(text(R.string.unlock)).performTouchInput { longClick(durationMillis=650) }
            at(R.string.stop).performClick()
            capture("26-stop-dialog")
            at(R.string.cancel).assertIsDisplayed().performClick()
            stop()
            compose.runOnUiThread { TimerService.command(context,"start",Plan(0,1,0,1)) }
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.COMPLETED }
            at(R.string.home).performScrollTo().assertIsDisplayed()
            at(R.string.repeat).performScrollTo().assertIsDisplayed()
            capture("27-result")
        } finally { capture("e-final") }
    }
    @Test fun f_feelRealTransitionsAndPressResponse() {
        if(args.getString("uxFeel")!="true") return
        shell("screenrecord --time-limit 60 /sdcard/ui-audit-feel.mp4 >/dev/null 2>&1 &")
        try {
            compose.onNodeWithText(presets.first().name).performClick()
            val plus=compose.onNodeWithContentDescription("+ "+text(R.string.work))
            plus.performTouchInput { down(center) }
            SystemClock.sleep(1500)
            plus.performTouchInput { up() }
            capture("28-feel-plus-release")
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("0:04")
            compose.onNodeWithContentDescription(text(R.string.rest)).performScrollTo().performTextReplacement("0:04")
            compose.onNodeWithContentDescription(text(R.string.prepare)).performScrollTo().performTextReplacement("0:04")
            hideKeyboard()
            at(R.string.start).performClick()
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.RUNNING }
            SystemClock.sleep(13000)
            at(R.string.pause).performClick()
            compose.onNodeWithContentDescription(text(R.string.lock_short)).performClick()
            SystemClock.sleep(700)
            compose.onNodeWithContentDescription(text(R.string.unlock)).performTouchInput { longClick(durationMillis=650) }
            at(R.string.resume).performClick()
            SystemClock.sleep(1500)
            at(R.string.stop).performClick()
            at(R.string.cancel).performClick()
            capture("29-feel-running")
        } finally { shell("pkill -2 screenrecord");capture("f-final") }
    }
}
