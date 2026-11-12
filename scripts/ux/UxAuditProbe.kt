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
    private val output get() = File(context.filesDir,"ux-audit").apply {
        check(isDirectory || mkdirs()) { "Cannot create private audit output" }
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun captureSystem(name: String) {
        // Keep the app frame clock moving until the external Activity transition draws.
        observeFor(800)
        inst.waitForIdleSync()
        inst.uiAutomation.waitForIdle(300,5000)
        SystemClock.sleep(300)
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        try {
            File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { bitmap.recycle() }
        File(output,"$name-window.txt").writeText(inst.uiAutomation.rootInActiveWindow.toString())
    }
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
        val serviceInfo = inst.uiAutomation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        inst.uiAutomation.serviceInfo = serviceInfo
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
        compose.waitUntil(10000) { compose.onAllNodesWithText("+ "+text(R.string.new_timer)).fetchSemanticsNodes().isNotEmpty() }
        hideKeyboard()
    }
    @After fun finish() { stop() }
    private fun capture(name: String) {
        compose.waitForIdle()
        inst.waitForIdleSync()
        observeFor(400) // Advance test frames too: retained LazyColumn exit layers must finish drawing.
        // Advancing the test clock can schedule a relocation/layout after the first idle.
        // Flush it, then allow SurfaceFlinger to present that frame before taking pixels.
        compose.waitForIdle()
        inst.waitForIdleSync()
        SystemClock.sleep(150)
        val launcherAnr = inst.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText("Quickstep isn't responding")?.isNotEmpty() == true
        val image = requireNotNull(inst.uiAutomation.takeScreenshot())
        try {
            val pending = File(output,"$name.png.tmp")
            pending.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            check(pending.renameTo(File(output,"$name.png")))
        } finally { image.recycle() }
        assertFalse("Emulator SystemUI: Quickstep ANR obscures the app; screenshot is not valid UI evidence", launcherAnr)
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
                        .put("paragraphWidth",layout.multiParagraph.width).put("paragraphHeight",layout.multiParagraph.height)
                        .put("maxLineContentWidth",(0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) })
                        .put("bounds", "$b").put("overflow",layout.hasVisualOverflow)
                        .put("widthOverflow",layout.didOverflowWidth).put("heightOverflow",layout.didOverflowHeight)
                        .put("ellipsized",ellipsis).put("editable",node.config.contains(SemanticsProperties.EditableText)))
                }
            }
        }
        val c=compose.activity.resources.configuration
        File(output,"$name-layout.json").writeText(JSONObject().put("texts",all).put("fontScale",c.fontScale)
            .put("widthDp",c.screenWidthDp).put("heightDp",c.screenHeightDp)
            .put("language",c.locales[0].toLanguageTag())
            .put("lightStatusIcons", !androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
            .put("lightNavigationIcons", !androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightNavigationBars)
            .put("navigationContrastEnforced", compose.activity.window.isNavigationBarContrastEnforced).toString(2))
    }
    private fun at(id: Int) = compose.onNodeWithText(text(id))
    private fun firstSaved() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(presets.first().name))
    }
    private fun finalCapture(name: String) {
        runCatching { capture(name) }.onFailure { android.util.Log.e("UxAudit", "Final capture failed",it) }
    }
    private fun back() { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle() }
    private fun hideKeyboard() {
        compose.runOnUiThread { compose.activity.window.decorView.windowInsetsController?.hide(android.view.WindowInsets.Type.ime()) }
        observeFor(650)
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction = apply {
        // Scroll even a partially visible action fully into view; fixed controls have no scroll ancestor.
        val parent = fetchSemanticsNode().parent
        val scrollable = generateSequence(parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) }
        if (scrollable) performScrollTo() else assertIsDisplayed()
    }

    private fun openEditor() {
        firstSaved()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions, presets.first().name)).reveal().performClick()
        at(R.string.edit).reveal().performClick()
    }

    @Test fun a_homeMenuAndLongSavedNames() {
        try {
            capture("01-home-saved")
            firstSaved()
            compose.onNodeWithText(presets.first().name).performClick()
            at(R.string.preview_close).assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions,presets.first().name)).reveal().performClick()
            capture("02-mode-menu")
            at(R.string.edit).assertIsDisplayed()
            at(R.string.copy).assertIsDisplayed()
            at(R.string.delete).reveal().assertIsDisplayed()
            capture("02-mode-menu-bottom")
            back()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions, presets.first().name)).reveal().performClick()
            at(R.string.delete).reveal().performClick()
            compose.waitUntil(10000) { runBlocking { store.presets.first().size } == 2 }
            capture("02-deleted-snackbar")
            at(R.string.undo).assertIsDisplayed().performTouchInput { click(center) }
            compose.waitUntil(10000) { runBlocking { store.presets.first().size } == 3 }
            capture("02-restored-snackbar")
            runBlocking { store.savePresets(emptyList()) }
            compose.waitUntil(10000) { compose.onAllNodesWithText(presets.first().name).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("+ "+text(R.string.new_timer)).assertIsDisplayed()
            capture("03-home-empty")
            runBlocking { store.saveOptions(store.options.first().copy(showReadyModes=false)) }
            compose.waitForIdle()
            capture("04-home-no-ready")
            compose.onNodeWithText("+ "+text(R.string.new_timer)).assertIsDisplayed().performClick()
            at(R.string.start).reveal().assertIsDisplayed()
        } finally { finalCapture("a-final") }
    }
    @Test fun b_editorValidationAppearanceAndLeaveDialog() {
        try {
            firstSaved()
            openEditor()
            capture("05-editor-top")
            val work=compose.onNodeWithContentDescription(text(R.string.work))
            work.reveal().performTouchInput { click(center) }.performTextReplacement("60:00")
            compose.waitUntil(10000) { inst.uiAutomation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            observeFor(600)
            work.assertIsDisplayed()
            val inputBounds = work.getUnclippedBoundsInRoot()
            val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
            assertTrue("Focused input extends outside the resized window: $inputBounds / $rootBounds",
                inputBounds.top >= rootBounds.top && inputBounds.bottom <= rootBounds.bottom)
            capture("06-editor-input-keyboard")
            val start = compose.onNodeWithText(text(R.string.start)).reveal()
            try {
                start.assertIsDisplayed()
            } catch (failure: AssertionError) {
                File(output,"06-editor-start-failure-semantics.txt").writeText(compose.onRoot().printToString())
                capture("06-editor-start-failure")
                throw failure
            }
            compose.onNodeWithText(text(R.string.save)).reveal().assertIsDisplayed()
            capture("06-editor-keyboard")
            hideKeyboard()
            work.performTextReplacement("99:99")
            compose.onNodeWithText(text(R.string.start)).assertIsNotEnabled()
            capture("07-editor-invalid")
            work.performTextReplacement("0:20")
            hideKeyboard()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.card_mark_label,text(R.string.mark_sets)))
                .reveal().assertIsDisplayed().performClick()
            capture("08-editor-appearance")
            compose.onNodeWithContentDescription(text(R.string.plan_preview)).reveal().performClick()
            capture("09-editor-preview")
            at(R.string.preview_close).assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("← "+text(R.string.saved)).reveal().performClick()
            at(R.string.unsaved).assertIsDisplayed()
            capture("10-unsaved-dialog")
            at(R.string.stay).reveal().assertIsDisplayed()
            at(R.string.reset).reveal().assertIsDisplayed()
            compose.onAllNodesWithText(text(R.string.save)).onLast().reveal().assertIsDisplayed()
        } finally { finalCapture("b-final") }
    }
    @Test fun c_historyLongNamesAndExpandedPlan() {
        try {
            compose.onNodeWithContentDescription(text(R.string.history_open)).reveal().performClick()
            try {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("history-week-counts").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                File(output,"11-history-load-failure-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString(50))
                File(output,"11-history-load-failure-state.txt").writeText(
                    "lifecycle=${compose.activity.lifecycle.currentState}\n" +
                        runBlocking { runCatching { HistoryRepository(context).load().toString() }.toString() })
                throw failure
            }
            compose.onNodeWithTag("history-list").performScrollToNode(hasText(text(R.string.history_empty_title)))
            capture("11-history-empty")
            val repo=HistoryRepository(context)
            repo.enqueue(WorkoutRecord("ux-record",presets[1].plan,longName,"violet",1,
                finishedAt=System.currentTimeMillis(),completed=true,activeMs=presets[1].plan.durationMs(),completedRounds=4))
            runBlocking { repo.load() }
            compose.onNodeWithTag("history-list").performScrollToIndex(0)
            compose.waitUntil(10000) { compose.onAllNodesWithText(compose.activity.getString(R.string.history_week_counts,1,0)).fetchSemanticsNodes().isNotEmpty() }
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
            compose.onNodeWithTag("history-list").performScrollToNode(hasText(text(R.string.history_clear)))
            at(R.string.history_clear).reveal().assertIsDisplayed()
            compose.waitForIdle();SystemClock.sleep(350)
            capture("16-clear-before-tap")
            at(R.string.history_clear).performClick()
            var physicalOpened=true
            try {
                compose.waitUntil(4000) { compose.onAllNodesWithText(text(R.string.history_clear_title)).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                physicalOpened=false
                capture("16-clear-touch-did-not-open")
                at(R.string.history_clear).performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.waitUntil(4000) { compose.onAllNodesWithText(text(R.string.history_clear_title)).fetchSemanticsNodes().isNotEmpty() }
            }
            capture("16-history-clear")
            at(R.string.cancel).reveal().assertIsDisplayed()
            assertTrue("Physical clear-history tap did not open dialog; semantic diagnostic used",physicalOpened)
        } finally { finalCapture("c-final") }
    }
    @Test fun d_settingsSoundsSupportAndKeyboard() {
        try {
            compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
            capture("17-settings-sounds")
            at(R.string.finish_cue).reveal().assertIsDisplayed()
            capture("18-settings-finish")
            at(R.string.vibration_preview).reveal().assertIsDisplayed()
            capture("19-settings-vibration")
            at(R.string.halfway).reveal().performClick()
            at(R.string.halfway_preview).reveal().assertIsDisplayed()
            capture("19-settings-halfway")
            at(R.string.show_ready_modes).reveal().assertIsDisplayed()
            capture("20-settings-options")
            at(R.string.voice).reveal().performClick()
            compose.onNodeWithText(text(R.string.voice_stages_rounds),useUnmergedTree=true).reveal().assertIsDisplayed()
            capture("20-settings-voice")
            at(R.string.voice).reveal().performClick()
            at(R.string.theme_dark).reveal()
            capture("20-settings-appearance")
            at(R.string.export).reveal()
            capture("20-settings-data")
            at(R.string.review_store_link).reveal()
            capture("20-settings-about")
            at(R.string.report_problem).reveal().performClick()
            capture("21-support")
            compose.onNodeWithTag("support-description").reveal().performTouchInput { click(center) }.performTextInput("UX audit: button and keyboard check")
            observeFor(600)
            capture("22-native-input-before-check")
            compose.waitUntil(5000) { inst.uiAutomation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            capture("22-support-keyboard")
            at(R.string.report_copy).reveal().assertIsDisplayed().performTouchInput { click(center) }
            compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.report_copied)).fetchSemanticsNodes().isNotEmpty() }
            // System clipboard preview can temporarily take input focus after a real touch.
            compose.waitUntil(10000) {
                var copied = false
                compose.runOnUiThread {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    copied = clip?.getItemAt(0)?.text?.contains("UX audit:") == true
                }
                copied
            }
            capture("23-support-copied")
            compose.onNodeWithTag("support-description").reveal().performImeAction()
            observeFor(400)
            compose.waitUntil(5000) { inst.uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            capture("23-support-done")
            at(R.string.preview_close).reveal().assertIsDisplayed().performTouchInput { click(center) }
        } finally { finalCapture("d-final") }
    }
    @Test fun e_workoutAllStagesClockGridAndLock() {
        try {
            compose.runOnUiThread { TimerService.command(context,"start",presets[1].plan) }
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.RUNNING }
            at(R.string.pause).performClick()
            compose.waitUntil(5000) { TimerService.state.value?.status==Status.PAUSED }
            val frames=JSONArray()
            val failures=mutableListOf<String>()
            var firstClock: androidx.compose.ui.geometry.Rect? = null
            repeat(9) { index ->
                val s=requireNotNull(TimerService.state.value)
                capture("24-stage-$index-${s.interval?.stage}")
                val clock=compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInWindow
                if (firstClock == null) firstClock = clock
                assertEquals("Clock top shifts at stage $index", firstClock!!.top, clock.top, 1f)
                assertEquals("Clock size shifts at stage $index", firstClock!!.height, clock.height, 1f)
                if (args.getString("uxOrientation") != "landscape") {
                    val density = compose.activity.resources.displayMetrics.density
                    assertTrue("Clock too small: $clock", clock.height >= 48f * density)
                    val content = compose.onNodeWithTag("app-content").fetchSemanticsNode().boundsInWindow
                    assertTrue("Clock loses visual priority: $clock", clock.width >= minOf(content.width * .5f, 160f * density))
                }
                val clockVisible=runCatching { compose.onNodeWithTag("workout-clock").assertIsDisplayed() }.isSuccess
                val controlsVisible=runCatching {
                    at(R.string.resume).assertIsDisplayed()
                    compose.onNodeWithContentDescription(text(R.string.next)).reveal().assertIsDisplayed()
                    at(R.string.resume).assertIsDisplayed() // Primary action stays fixed while details scroll.
                }.isSuccess
                if (s.interval?.stage in listOf(Stage.REST, Stage.BETWEEN) && args.getString("uxOrientation") != "landscape") {
                    at(R.string.add_rest).reveal()
                    val rest = at(R.string.add_rest).fetchSemanticsNode().boundsInWindow
                    val primary = at(R.string.resume).fetchSemanticsNode().boundsInWindow
                    assertTrue("Rest control cropped under resume: $rest / $primary", rest.top >= primary.bottom)
                }
                if (index in listOf(0,4,8)) capture("24-controls-$index")
                s.next?.let { next ->
                    val caption = compose.activity.getString(R.string.next_stage,
                        text(stageString(next.stage)), formatTime(next.duration))
                    val upcoming = compose.onNodeWithText(caption).reveal()
                    upcoming.assertIsDisplayed()
                    val layouts = mutableListOf<TextLayoutResult>()
                    upcoming.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    assertTrue("Next-stage caption clips or truncates: $caption", layouts.isNotEmpty() && layouts.all { layout ->
                        (0 until layout.lineCount).all { line ->
                            !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -1f &&
                                layout.getLineRight(line) <= layout.size.width + 1f &&
                                layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f
                        }
                    })
                    val bounds = upcoming.getUnclippedBoundsInRoot()
                    val root = compose.onRoot().getUnclippedBoundsInRoot()
                    assertTrue("Next-stage caption is outside the viewport: $bounds / $root",
                        bounds.left >= root.left && bounds.right <= root.right &&
                            bounds.top >= root.top && bounds.bottom <= root.bottom)
                    capture("24-upcoming-$index")
                }
                frames.put(JSONObject().put("stage",s.interval?.stage?.name).put("clock",clock.toString())
                    .put("clockVisible",clockVisible).put("controlsVisible",controlsVisible))
                File(output,"clock-positions.json").writeText(frames.toString(2))
                if(!clockVisible || !controlsVisible) failures.add("${s.interval?.stage}: clock=$clockVisible controls=$controlsVisible")
                if(index<8) {
                    if(controlsVisible) compose.onNodeWithContentDescription(text(R.string.next)).performClick()
                    else compose.runOnUiThread { TimerService.command(context,"next") } // Diagnostic rendering only; recorded as unreachable.
                    compose.waitUntil(5000) { TimerService.state.value?.index==index+1 }
                }
            }
            File(output,"clock-positions.json").writeText(frames.toString(2))
            compose.onNodeWithContentDescription(text(R.string.lock_short)).performClick()
            capture("25-workout-locked")
            compose.onNodeWithContentDescription(text(R.string.unlock)).performTouchInput { longClick(durationMillis=650) }
            compose.onNodeWithContentDescription(text(R.string.stop)).performClick()
            capture("26-stop-dialog")
            at(R.string.cancel).reveal().assertIsDisplayed().performClick()
            stop()
            compose.runOnUiThread { TimerService.command(context,"start",Plan(0,1,0,1)) }
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.COMPLETED }
            capture("27-result-initial")
            at(R.string.home).reveal().assertIsDisplayed()
            at(R.string.repeat).reveal().assertIsDisplayed()
            capture("27-result")
            assertTrue("Unreachable workout layout: $failures",failures.isEmpty())
        } finally { finalCapture("e-final") }
    }
    @Test fun k_queueAndLargeSupportContent() {
        try {
            runBlocking { store.saveOptions(store.options.first().copy(showStages = true)) }
            compose.runOnUiThread { TimerService.command(context, "start", presets[1].plan) }
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
            at(R.string.pause).performClick()
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
            capture("36-queue-prepare")
            repeat(4) {
                compose.onNodeWithContentDescription(text(R.string.next)).reveal().performClick()
                compose.waitUntil(5000) { TimerService.state.value?.index == it + 1 }
            }
            capture("36-queue-between")
            at(R.string.add_rest).reveal().performClick()
            capture("36-queue-extended")
            stop()
            compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
            at(R.string.report_problem).reveal().performClick()
            compose.onNodeWithTag("support-description").reveal().performTouchInput { click(center) }.performTextInput("Long description. ".repeat(100))
            capture("37-support-long-input")
            at(R.string.report_copy).reveal()
            capture("37-support-long-actions")
        } finally { finalCapture("k-final") }
    }

    @Test fun l_bannerLoadingSequence() {
        if (args.getString("uxFeel") != "true") return
        capture("38-banner-initial")
        for (index in 1..4) { observeFor(2000); capture("38-banner-$index") }
        runBlocking { store.savePresets(listOf(
            presets[0],
            Preset("daily-strength", "Силовая тренировка", Plan(work=45, rest=15, reps=12), "violet", 1),
            Preset("daily-warmup", "Разминка", Plan(work=30, rest=10, reps=6), "orange", 0))) }
        observeFor(700)
        capture("40-home-everyday")
    }

    @Test fun m_hourDigitsAndLargePlan() {
        try {
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(
                compose.activity.getString(R.string.mode_actions, presets[2].name)))
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions, presets[2].name)).performClick()
            at(R.string.plan_preview).performClick()
            capture("39-twenty-hour-plan")
            at(R.string.preview_close).performClick()
            val plan = Plan(prepare = 3600, work = 10, rest = 0, reps = 200, sets = 20, between = 0, cool = 3600)
            compose.runOnUiThread { TimerService.command(context, "start", plan) }
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
            at(R.string.pause).performClick()
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
            capture("39-hour-clock")
            val node = compose.onNodeWithTag("workout-clock")
            val results = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            // hasVisualOverflow also compares paragraph constraints with the final tight size;
            // it can flag unused paragraph width while every glyph is inside the actual text node.
            assertTrue("Long clock wraps or clips: " + results.map { "${it.size}, line=${it.getLineLeft(0)}..${it.getLineRight(0)}" },
                results.isNotEmpty() && results.all { it.lineCount == 1 && !it.isLineEllipsized(0) &&
                    it.getLineLeft(0) >= -1f && it.getLineRight(0) <= it.size.width + 1f &&
                    it.getLineTop(0) >= -1f && it.getLineBottom(0) <= it.size.height + 1f })
            val bounds = node.fetchSemanticsNode().boundsInWindow
            val parent = compose.onNodeWithTag("app-content").fetchSemanticsNode().boundsInWindow
            assertTrue("Long clock exceeds content", bounds.left >= parent.left && bounds.right <= parent.right &&
                bounds.top >= parent.top && bounds.bottom <= parent.bottom)
            compose.onNodeWithContentDescription(text(R.string.next)).reveal().performClick()
            compose.waitUntil(5000) { TimerService.state.value?.index == 1 }
            capture("39-large-round-count")
        } finally { finalCapture("m-final") }
    }

    private fun observeFor(durationMs: Long) {
        val started = SystemClock.uptimeMillis()
        // Keep Compose's test frame clock advancing while wall-clock service ticks.
        compose.waitUntil(durationMs + 5000) { SystemClock.uptimeMillis() - started >= durationMs }
    }

    @Test fun f_feelRealTransitionsAndPressResponse() {
        if(args.getString("uxFeel")!="true") return
        try {
            firstSaved()
            openEditor()
            val plus=compose.onNodeWithContentDescription("+ "+text(R.string.work))
            plus.performTouchInput { down(center) }
            observeFor(1500)
            plus.performTouchInput { up() }
            capture("28-feel-plus-release")
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("0:04")
            compose.onNodeWithContentDescription(text(R.string.rest)).reveal().performTextReplacement("0:04")
            compose.onNodeWithContentDescription(text(R.string.prepare)).reveal().performTextReplacement("0:04")
            hideKeyboard()
            at(R.string.start).reveal().performClick()
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.RUNNING }
            observeFor(13000)
            at(R.string.pause).performClick()
            compose.onNodeWithContentDescription(text(R.string.lock_short)).performClick()
            observeFor(700)
            compose.onNodeWithContentDescription(text(R.string.unlock)).performTouchInput { longClick(durationMillis=650) }
            at(R.string.resume).performClick()
            observeFor(1500)
            compose.onNodeWithContentDescription(text(R.string.stop)).performClick()
            at(R.string.cancel).performClick()
            capture("29-feel-running")
        } finally { finalCapture("f-final") }
    }

    @Test fun g_newTimerNamingAndKeyboard() {
        try {
            compose.onNodeWithText("+ "+text(R.string.new_timer)).performClick()
            at(R.string.save).reveal().performClick()
            at(R.string.save_mode).assertIsDisplayed()
            capture("30-new-name-dialog")
            compose.onAllNodes(hasSetTextAction()).onLast().reveal().performTouchInput { click(center) }.performTextInput(longName)
            observeFor(600)
            capture("31-native-input-before-check")
            compose.waitUntil(5000) { inst.uiAutomation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            capture("31-new-name-keyboard")
            compose.onAllNodesWithText(text(R.string.save)).onLast().reveal().assertIsDisplayed()
            capture("31-new-name-actions-keyboard")
            at(R.string.cancel).reveal().assertIsDisplayed()
            compose.onAllNodesWithText(text(R.string.save)).onLast().reveal().performTouchInput { click(center) }
            compose.waitUntil(5000) { runBlocking { store.presets.first().size } == 4 }
            assertEquals(longName, runBlocking { store.presets.first().last().name })
        } finally { finalCapture("g-final") }
    }
    @Test fun h_notificationExplanation() {
        try {
            context.getSharedPreferences("ui",0).edit().putBoolean("notificationAsked",false).commit()
            firstSaved()
            compose.onAllNodesWithText(text(R.string.start)).onFirst().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.notification_title)).fetchSemanticsNodes().isNotEmpty() }
            capture("32-notification-explanation")
            at(R.string.later).reveal().assertIsDisplayed()
            at(R.string.allow).reveal().assertIsDisplayed()
        } finally { finalCapture("h-final") }
    }
    @Test fun j_rotationKeepsPausedClockAndSession() {
        try {
            compose.runOnUiThread { TimerService.command(context,"start",presets[1].plan) }
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.RUNNING }
            at(R.string.pause).performClick()
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
            val before = requireNotNull(TimerService.state.value)
            val original = compose.activity.resources.configuration.orientation
            compose.runOnUiThread { compose.activity.requestedOrientation =
                if (original == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation != original }
            compose.onNodeWithTag("workout-clock").assertIsDisplayed()
            at(R.string.resume).assertIsDisplayed()
            val after = requireNotNull(TimerService.state.value)
            assertEquals(before.sessionId, after.sessionId)
            assertEquals(before.index, after.index)
            assertEquals(before.remaining, after.remaining)
            capture("35-rotated-paused")
        } finally { finalCapture("j-final") }
    }

    @Test fun i_recoveryAndInterruptedLayout() {
        try {
            context.getSharedPreferences("session",0).edit().putString("active",JSONObject()
                .put("plan",presets[1].plan.json()).put("index",1).put("remaining",30000)
                .put("sessionId","ux-recovery").put("activeMs",0).put("statsAvailable",true).toString()).commit()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.recovery_title)).fetchSemanticsNodes().isNotEmpty() }
            capture("33-recovery-dialog")
            at(R.string.discard).reveal().assertIsDisplayed()
            at(R.string.recover).reveal().assertIsDisplayed().performClick()
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.INTERRUPTED }
            capture("34-interrupted-workout")
            val restoredImage = requireNotNull(inst.uiAutomation.takeScreenshot())
            val topColor = restoredImage.getPixel(restoredImage.width / 2, 5)
            val bodyColor = restoredImage.getPixel(5, restoredImage.height / 3)
            fun channelDifference(a: Int, b: Int): Int = kotlin.math.abs(a - b)
            val mismatch = channelDifference(android.graphics.Color.red(topColor), android.graphics.Color.red(bodyColor)) +
                channelDifference(android.graphics.Color.green(topColor), android.graphics.Color.green(bodyColor)) +
                channelDifference(android.graphics.Color.blue(topColor), android.graphics.Color.blue(bodyColor))
            assertTrue("Restored workout has a mismatched system bar: $mismatch", mismatch < 80)
            at(R.string.resume).assertIsDisplayed()
        } finally { finalCapture("i-final") }
    }

    @Test fun n_filePickerCancellationAndThemePersistence() {
        try {
            compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
            for ((theme,label) in listOf("dark" to R.string.theme_dark,"light" to R.string.theme_light)) {
                at(label).reveal().performClick()
                compose.waitUntil(5000) { runBlocking { store.options.first().theme } == theme }
                capture("43-settings-$theme-selected")
            }
            at(R.string.export).reveal().performClick()
            compose.waitUntil(10000) {
                inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
            }
            captureSystem("44-system-export-picker")
            back()
            at(R.string.import_timers).reveal().assertIsDisplayed()
            capture("45-export-cancelled")
            at(R.string.import_timers).performClick()
            compose.waitUntil(10000) {
                inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
            }
            captureSystem("46-system-import-picker")
            back()
            at(R.string.import_timers).reveal().assertIsDisplayed()
            val restored = if (args.getString("uxTheme") == "dark") R.string.theme_dark else R.string.theme_light
            at(restored).reveal().performClick()
            compose.activityRule.scenario.recreate()
            at(restored).reveal().assertIsSelected()
            assertEquals(3,runBlocking { store.presets.first().size })
            capture("47-settings-theme-restored")
            if (BuildConfig.PRIVACY_URL.isNotBlank()) {
                at(R.string.privacy).reveal().assertIsDisplayed()
                capture("48-settings-privacy")
            }
        } finally { finalCapture("n-final") }
    }

    @Test fun o_historyLegacyStoppedAndPagination() {
        try {
            val repository = HistoryRepository(context)
            val now = System.currentTimeMillis()
            repeat(51) { index ->
                repository.enqueue(WorkoutRecord("ux-page-$index",presets[0].plan,
                    if (index == 0) longName else "Workout ${index + 1}","blue",0,
                    finishedAt=now-index*1000L,completed=index > 0 && index % 2 == 0,
                    activeMs=if(index == 0) 0 else 20000,completedRounds=0,statsAvailable=index != 0))
            }
            runBlocking { repository.load() }
            compose.onNodeWithContentDescription(text(R.string.history_open)).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("history-week-counts").fetchSemanticsNodes().isNotEmpty() }
            at(R.string.history_partial_week).reveal().assertIsDisplayed()
            capture("52-history-partial-week")
            val list = compose.onNodeWithTag("history-list")
            list.performScrollToNode(hasTestTag("history-ux-page-0"))
            at(R.string.history_partial_record).reveal().assertIsDisplayed()
            capture("53-history-legacy-stopped")
            list.performScrollToNode(hasText(text(R.string.history_more)))
            at(R.string.history_more).assertIsDisplayed()
            capture("54-history-more")
            at(R.string.history_more).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(text(R.string.history_more)).fetchSemanticsNodes().isEmpty() }
            list.performScrollToNode(hasTestTag("history-ux-page-50"))
            compose.onNodeWithTag("history-ux-page-50").assertIsDisplayed()
            capture("55-history-oldest-page")
            list.performScrollToNode(hasText(text(R.string.history_clear)))
            at(R.string.history_clear).assertIsDisplayed()
        } finally { finalCapture("o-final") }
    }

    @Test fun p_completedDraftActionsAndReturn() {
        try {
            compose.onNodeWithText("+ "+text(R.string.new_timer)).performClick()
            for ((label,value) in listOf(R.string.work to "0:01",R.string.rest to "0:00",
                R.string.reps to "1",R.string.prepare to "0:00")) {
                compose.onNodeWithContentDescription(text(label)).reveal().performTextReplacement(value)
            }
            hideKeyboard()
            at(R.string.start).reveal().performClick()
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.COMPLETED }
            capture("56-completed-draft-initial")
            at(R.string.repeat).reveal().assertIsDisplayed()
            at(R.string.home).reveal().assertIsDisplayed()
            at(R.string.return_draft).reveal().assertIsDisplayed()
            capture("57-completed-draft-actions")
            at(R.string.return_draft).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).reveal().assertTextContains("0:01")
            capture("58-returned-draft")
            assertEquals(3,runBlocking { store.presets.first().size })
        } finally { finalCapture("p-final") }
    }

    @Test fun q_completedLegacySessionWithoutStatistics() {
        try {
            // A checkpoint from before session statistics existed must not invent a result.
            context.getSharedPreferences("session",0).edit().putString("active",JSONObject()
                .put("plan",Plan(0,1,0,1).json()).put("index",0).put("remaining",1000)
                .put("sessionId","ux-legacy-result").toString()).commit()
            compose.activityRule.scenario.recreate()
            at(R.string.recover).reveal().performClick()
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.INTERRUPTED }
            assertFalse(requireNotNull(TimerService.state.value).statsAvailable)
            at(R.string.resume).performClick()
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.COMPLETED }
            at(R.string.result_unknown).reveal().assertIsDisplayed()
            capture("59-completed-legacy-result")
            at(R.string.repeat).reveal().assertIsDisplayed()
            at(R.string.home).reveal().assertIsDisplayed().performClick()
            compose.onNodeWithText("+ "+text(R.string.new_timer)).assertIsDisplayed()
            capture("60-legacy-result-return-home")
        } finally { finalCapture("q-final") }
    }

    @Test fun r_historyRepeatedNavigationAndWorkoutReturn() {
        if (args.getString("uxOrientation") == "landscape" || context.resources.configuration.fontScale < 1.99f) return
        val repository = HistoryRepository(context)
        val originalOrientation = compose.activity.requestedOrientation
        try {
            repository.enqueue(WorkoutRecord("ux-history-reopen",Plan(0,1,0,1),longName,"blue",0,
                finishedAt=System.currentTimeMillis(),completed=true,activeMs=1000,completedRounds=1))
            runBlocking { repository.load() }
            repeat(5) { attempt ->
                compose.onNodeWithContentDescription(text(R.string.history_open)).reveal().performClick()
                fun awaitHistory() {
                    compose.onNodeWithTag("history-list").performScrollToIndex(0)
                    compose.waitUntil(10000) {
                        compose.onAllNodesWithTag("history-week-counts").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-ux-history-reopen"))
                    compose.onNodeWithTag("history-ux-history-reopen").assertIsDisplayed()
                }
                awaitHistory()
                if (attempt < 2) {
                    compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    awaitHistory()
                    capture("61-history-reopen-$attempt-landscape")
                    compose.runOnUiThread { compose.activity.requestedOrientation = originalOrientation }
                    compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    awaitHistory()
                }
                capture("61-history-reopen-$attempt")
                if (attempt >= 2) {
                    compose.onNode(hasText(text(R.string.repeat)) and hasAnyAncestor(hasTestTag("history-ux-history-reopen")))
                        .reveal().performClick()
                    compose.waitUntil(10000) { TimerService.state.value?.status == Status.COMPLETED }
                    at(R.string.home).reveal().performClick()
                } else compose.onNodeWithContentDescription("← " + text(R.string.saved)).performClick()
                compose.onNodeWithText("+ " + text(R.string.new_timer)).assertIsDisplayed()
            }
        } catch (failure: Throwable) {
            File(output,"61-history-reopen-failure-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString(50))
            File(output,"61-history-reopen-failure-state.txt").writeText(
                "lifecycle=${compose.activity.lifecycle.currentState}\n" +
                    runBlocking { runCatching { repository.load().toString() }.toString() })
            throw failure
        } finally { finalCapture("r-final") }
    }
}
