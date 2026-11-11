package ru.tabatabata

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

private val LightPalette =
    lightColorScheme(
        primary = Color(0xFF3049C7),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFFF6F7FC),
        surface = Color(0xFFF6F7FC),
        surfaceVariant = Color(0xFFE5E9F5),
        onSurface = Color(0xFF151B30),
        onBackground = Color(0xFF151B30),
        onSurfaceVariant = Color(0xFF4C5772),
        outline = Color(0xFF65718D),
        outlineVariant = Color(0xFFBBC4DA),
        surfaceContainer = Color(0xFFEBEEF8),
        surfaceContainerLow = Color(0xFFF0F2FA),
        surfaceContainerHigh = Color(0xFFE5E9F5),
        surfaceContainerHighest = Color(0xFFDDE3F3),
        secondaryContainer = Color(0xFFE5E9F5),
        secondary = Color(0xFF4C5772),
    )
private val DarkPalette =
    darkColorScheme(
        primary = Color(0xFFB9C5FF),
        onPrimary = Color(0xFF10216C),
        background = Color(0xFF111522),
        surface = Color(0xFF111522),
        surfaceVariant = Color(0xFF252E44),
        onSurface = Color(0xFFEDF0FC),
        onBackground = Color(0xFFEDF0FC),
        onSurfaceVariant = Color(0xFFBBC5DE),
        outline = Color(0xFF8996B4),
        outlineVariant = Color(0xFF424E69),
        surfaceContainer = Color(0xFF1D2436),
        surfaceContainerLow = Color(0xFF181F30),
        surfaceContainerHigh = Color(0xFF252E44),
        surfaceContainerHighest = Color(0xFF303A53),
        secondaryContainer = Color(0xFF252E44),
        secondary = Color(0xFFBBC5DE),
    )

@Composable
private fun TabataTheme(content: @Composable () -> Unit) {
    val activity = requireNotNull(LocalActivity.current) as ComponentActivity
    val store = remember { Store(activity) }
    val options by store.options.collectAsStateWithLifecycle(Options())
    val dark =
        when (options.theme) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
    MaterialTheme(
        colorScheme = if (dark) DarkPalette else LightPalette,
        shapes =
            Shapes(
                androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ),
        content = content,
    )
}

class MainActivity : ComponentActivity() {
    internal var onUserAction: (() -> Unit)? = null
    override fun onUserInteraction() {
        super.onUserInteraction()
        onUserAction?.invoke()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        updateSystemBars(true, android.graphics.Color.parseColor("#F6F7FC"))
        setContent { TabataTheme { App() } }
    }
    private var barsDark: Boolean? = null
    private var barsBackground: Int? = null
    internal fun updateSystemBars(dark: Boolean, background: Int, force: Boolean = false) {
        if (force || barsDark != dark) {
            barsDark = dark
            val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            // Keep platform protection for three-button navigation over stage colours.
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = true
        }
        if (barsBackground != background) {
            barsBackground = background
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android can restore decor flags after onCreate; reapply after recreation/dialog dismissal.
        if (hasFocus && Build.VERSION.SDK_INT < 36)
            updateSystemBars(barsDark ?: false, barsBackground ?: android.graphics.Color.TRANSPARENT, force = true)
    }

}

@Composable
private fun App() {
    val context = LocalContext.current
    val activity = requireNotNull(LocalActivity.current) as MainActivity
    val store = remember { Store(context) }
    val scope = rememberCoroutineScope()
    val lastPresetId by store.lastPresetId.collectAsStateWithLifecycle(null)
    val presets by store.presets.collectAsStateWithLifecycle(emptyList())
    val options by store.options.collectAsStateWithLifecycle(Options())
    val snapshot by TimerService.state.collectAsStateWithLifecycle()
    val sessionPreset by TimerService.sessionPreset.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    suspend fun showWriteError(result: PresetWrite) {
        if (result != PresetWrite.SAVED) snackbar.showSnackbar(context.getString(
            if (result == PresetWrite.LIMIT) R.string.limit else R.string.storage_damaged))
    }
    val damaged by store.damagedPresets.collectAsStateWithLifecycle(false)
    LaunchedEffect(damaged) { if (damaged) showWriteError(PresetWrite.DAMAGED) }
    val ads = remember(activity) { Ads(activity) }
    DisposableEffect(ads) { onDispose { ads.close() } }
    LaunchedEffect(snapshot?.sessionId, snapshot?.status) {
        if (snapshot?.status == Status.COMPLETED) ads.completed(snapshot!!.sessionId)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0 && snapshot == null) { tab = 0 }
    val editorSaver = remember {
        Saver<Preset?, String>(
            save = { preset ->
                preset?.let {
                    org.json
                        .JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("plan", it.plan.json()).put("accent", it.accent).put("mark", it.mark)
                        .toString()
                }
            },
            restore = { raw ->
                runCatching {
                        val obj = org.json.JSONObject(raw)
                        Preset(
                            obj.getString("id"),
                            obj.getString("name"),
                            obj.getJSONObject("plan").plan(),
                            obj.optString("accent", "blue"), obj.optInt("mark", 0),
                        )
                    }
                    .getOrNull()
            },
        )
    }
    var previewPreset by rememberSaveable(stateSaver = editorSaver) { mutableStateOf<Preset?>(null) }
    var editor by rememberSaveable(stateSaver = editorSaver) { mutableStateOf<Preset?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var recovery by remember {
        mutableStateOf(
            snapshot == null &&
                context.getSharedPreferences("session", Context.MODE_PRIVATE).contains("active")
        )
    }
    var notificationPrompt by rememberSaveable { mutableStateOf(false) }
    var pendingPlan by rememberSaveable(stateSaver = Saver<Plan?, String>(
        save = { it?.json()?.toString() },
        restore = { runCatching { org.json.JSONObject(it).plan() }.getOrNull() },
    )) { mutableStateOf<Plan?>(null) }
    var pendingPreset by rememberSaveable(stateSaver = editorSaver) { mutableStateOf<Preset?>(null) }
    val reviewLedger = remember { ReviewPreferences(context) }
    val reviewHistory = remember { HistoryRepository(context) }
    val reviews = remember(activity) {
        ReviewCoordinator(scope, reviewLedger, RuStoreReviews(activity),
            experience = { reviewHistory.reviewExperience(it) },
            allowed = {
                TimerService.state.value == null && tab == 0 && !editorOpen &&
                    previewPreset == null && !recovery && !notificationPrompt && pendingPlan == null &&
                    !ads.fullscreen && !activity.isFinishing && !activity.isDestroyed &&
                    activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            },
            event = { Telemetry.event("review_flow", mapOf("outcome" to it)) })
    }
    val reviewPhase by reviews.phase.collectAsStateWithLifecycle()
    DisposableEffect(reviews, activity) {
        val interaction: () -> Unit = { reviews.cancelPreparation() }
        activity.onUserAction = interaction
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                reviews.cancelPreparation()
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            if (activity.onUserAction === interaction) activity.onUserAction = null
            activity.lifecycle.removeObserver(observer)
            reviews.close()
        }
    }
    LaunchedEffect(snapshot?.sessionId, snapshot?.status) {
        reviews.inspect(snapshot?.takeIf { it.status == Status.COMPLETED }?.sessionId)
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingPlan?.let { TimerService.command(context, "start", it, pendingPreset) }
            pendingPlan = null
            pendingPreset = null
        }
    var draftTarget by rememberSaveable { mutableStateOf<String?>(null) }
    fun launch(plan: Plan, draftKey: String? = null, preset: Preset? = null) {
        reviews.cancelPreparation()
        draftTarget = draftKey
        scope.launch {
            store.saveLastPlan(plan)
            if (presets.any { it.id == draftKey }) store.saveLastPreset(draftKey!!)
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED &&
                !context
                    .getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .getBoolean("notificationAsked", false)
        ) {
            pendingPlan = plan
            pendingPreset = preset
            reviewLedger.externalWindow()
            notificationPrompt = true
        } else TimerService.command(context, "start", plan, preset)
    }
    LaunchedEffect(snapshot?.sessionId, snapshot?.status) {
        if (snapshot?.status == Status.STOPPED) {
            tab = 0
            editorOpen = false
            TimerService.clearResult()
        }
    }
    val running = snapshot?.status in listOf(Status.RUNNING, Status.PAUSED, Status.INTERRUPTED)
    DisposableEffect(running, options.keepScreen) {
        if (running && options.keepScreen)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val darkSurface = MaterialTheme.colorScheme.background.red < .3f
    val systemBackground = if (running) workoutBackground(snapshot?.interval?.stage, darkSurface)
        else MaterialTheme.colorScheme.background
    SideEffect {
        activity.updateSystemBars(running || darkSurface,
            systemBackground.toArgb())
    }
    val screen = when {
        running -> "workout"
        snapshot?.status == Status.COMPLETED -> "result"
        editorOpen -> "editor"
        tab == 2 -> "settings"
        tab == 3 -> "history"
        else -> "home"
    }
    val screenAlpha = remember { Animatable(1f) }
    LaunchedEffect(screen) {
        // The service starts immediately; fade only non-workout screens.
        if (screen == "workout") screenAlpha.snapTo(1f)
        else { screenAlpha.snapTo(.7f); screenAlpha.animateTo(1f, tween(140)) }
    }
    val adPlacementAllowed = reviewPhase == ReviewPhase.IDLE && previewPreset == null && !recovery && !notificationPrompt && pendingPlan == null &&
        ((snapshot == null && !editorOpen && tab == 0) || snapshot?.status == Status.COMPLETED)
    SideEffect { ads.setPlacementAllowed(adPlacementAllowed) }
    Scaffold(
        containerColor =
            if (running) workoutBackground(snapshot?.interval?.stage, darkSurface)
            else MaterialTheme.colorScheme.background,
        bottomBar = {
            if (adPlacementAllowed && ads.foreground && !ads.fullscreen)
                AdBanner(ads)
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("app-content").graphicsLayer { alpha = screenAlpha.value }) {
            if (snapshot?.status == Status.STOPPED) {
                // Return to the menu without flashing a result screen.
            } else if (snapshot != null)
                Workout(
                    snapshot!!,
                    options,
                    onClose = {
                        val result = snapshot?.status ?: Status.STOPPED
                        val reviewReserved = result == Status.COMPLETED && snapshot?.sessionId?.let { reviews.requestOnReturn(it) } == true
                        tab = 0
                        editorOpen = false
                        TimerService.clearResult()
                        if (!reviewReserved) ads.maybeShow(result)
                    },
                    onDraft =
                        if (draftTarget != null && EditorDrafts(context).has(draftTarget!!))
                            ({
                                TimerService.clearResult()
                                editor = presets.find { it.id == draftTarget }
                                editorOpen = true
                            })
                        else null,
                    onRepeat = {
                        val p = snapshot!!.plan
                        TimerService.clearResult()
                        launch(p, draftTarget, sessionPreset)
                    },
                )
            else if (editorOpen)
                ContentFrame { Editor(
                    editor,
                    onCancel = { editorOpen = false },
                    onSave = { p ->
                        scope.launch {
                            val result = store.updatePreset(p)
                            if (result != PresetWrite.SAVED) showWriteError(result)
                            else {
                                EditorDrafts(context).clear(editor?.id ?: "new")
                                editorOpen = false
                                snackbar.showSnackbar(context.getString(R.string.saved_ok))
                            }
                        }
                    },
                    onStart = { launch(it.plan, editor?.id ?: "new", it) },
                ) }
            else
                ContentFrame { Column(Modifier.fillMaxSize()) {
                    AppToolbar(
                        title = stringResource(when (tab) { 3 -> R.string.history; 2 -> R.string.settings; else -> R.string.saved }),
                        onBack = if (tab == 0) null else ({ reviews.cancelPreparation(); tab = 0 }),
                    ) {
                        if (tab == 0) {
                            IconButton(onClick = { reviews.cancelPreparation(); tab = 3 }) {
                                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_history),
                                    contentDescription = stringResource(R.string.history_open))
                            }
                            IconButton(onClick = { reviews.cancelPreparation(); tab = 2 }) {
                                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_settings),
                                    contentDescription = stringResource(R.string.settings))
                            }
                        }
                    }
                    when (tab) {
                        0 ->
                            Saved(
                                presets,
                                lastPresetId,
                                options.showReadyModes,
                                onNew = {
                                    reviews.cancelPreparation()
                                    editor = null
                                    editorOpen = true
                                },
                                onStart = { p ->
                                    scope.launch { store.saveLastPreset(p.id) }
                                    launch(p.plan, preset = p)
                                },
                                onEdit = {
                                    reviews.cancelPreparation()
                                    editor = it
                                    editorOpen = true
                                },
                                onPreview = { p ->
                                    reviews.cancelPreparation()
                                    previewPreset = p
                                    Telemetry.event("plan_preview", mapOf("source" to "saved"))
                                },
                                onCopy = { p ->
                                    reviews.cancelPreparation()
                                    scope.launch {
                                        showWriteError(store.updatePreset(
                                                p.copy(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    name =
                                                        (p.name +
                                                                context.getString(
                                                                    R.string.copy_suffix
                                                                ))
                                                            .take(100),
                                                )
                                            ))
                                    }
                                },
                                onDelete = { p ->
                                    reviews.cancelPreparation()
                                    scope.launch {
                                        val removed = store.remove(p.id)
                                        if (removed == null) { showWriteError(PresetWrite.DAMAGED); return@launch }
                                        if (
                                            snackbar.showSnackbar(
                                                context.getString(R.string.deleted),
                                                context.getString(R.string.undo),
                                                duration = SnackbarDuration.Long,
                                            ) == SnackbarResult.ActionPerformed
                                        )
                                            showWriteError(store.restore(removed))
                                    }
                                },
                                onInteraction = { reviews.cancelPreparation() },

                                onMove = { from, to ->
                                    reviews.cancelPreparation()
                                    scope.launch {
                                        presets.getOrNull(from)?.let {
                                            showWriteError(store.move(it.id, to - from))
                                        }
                                    }
                                },
                            )
                        3 -> HistoryScreen(
                            onRepeat = { p ->
                                Telemetry.event("history_repeat")
                                launch(p.plan, preset = p)
                            },
                            onPreview = { p -> previewPreset = p
                                Telemetry.event("plan_preview", mapOf("source" to "history")) },
                            snackbar = snackbar,
                        )
                        2 ->
                            Settings(options, presets, store, snackbar, onExternal = {
                                reviews.cancelPreparation(); reviewLedger.externalWindow()
                            }) { o ->
                                scope.launch { store.saveOptions(o) }
                            }
                    }
                } }
        }
    }
    previewPreset?.let { p -> PlanPreviewDialog(p.plan, p.name) { previewPreset = null } }
    if (recovery) AppDialog(stringResource(R.string.recovery_title), onDismiss = {}) {
        Text(stringResource(R.string.recovery_body))
        DialogActions(
            confirm = { m -> TextButton(onClick = {
                recovery = false
                TimerService.command(context, "recover")
            }, modifier = m) { Text(stringResource(R.string.recover)) } },
            dismiss = { m -> TextButton(onClick = {
                context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().remove("active").apply()
                recovery = false
            }, modifier = m) { Text(stringResource(R.string.discard)) } },
        )
    }
    if (notificationPrompt && Build.VERSION.SDK_INT >= 33) {
        fun startPending() {
            notificationPrompt = false
            pendingPlan?.let { TimerService.command(context, "start", it, pendingPreset) }
            pendingPlan = null
            pendingPreset = null
        }
        AppDialog(stringResource(R.string.notification_title), onDismiss = { startPending() }) {
            Text(stringResource(R.string.notification_body))
            DialogActions(
                confirm = { m -> TextButton(onClick = {
                    notificationPrompt = false
                    context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
                    reviewLedger.externalWindow()
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, modifier = m) { Text(stringResource(R.string.allow)) } },
                dismiss = { m -> TextButton(onClick = {
                    context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
                    startPending()
                }, modifier = m) { Text(stringResource(R.string.later)) } },
            )
        }
    }
}

@Composable
internal fun Toggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun Settings(
    o: Options,
    presets: List<Preset>,
    store: Store,
    snackbar: SnackbarHostState,
    onExternal: () -> Unit,
    onChange: (Options) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    if (reportOpen) SupportDialog(onClose = { reportOpen = false }, onExternal = onExternal)
    val cues = remember { Cues(context) {} }
    DisposableEffect(Unit) { onDispose { cues.close() } }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                scope.launch {
                    val ok =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                    context.contentResolver.openOutputStream(uri)?.use {
                                        it.write(store.export(presets).toByteArray())
                                    } ?: error("No stream")
                                }
                                .isSuccess
                        }
                    snackbar.showSnackbar(
                        context.getString(if (ok) R.string.export_ok else R.string.file_error)
                    )
                }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    val imported =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                    context.contentResolver
                                        .openInputStream(uri)
                                        ?.bufferedReader()
                                        ?.use { reader ->
                                            val chars = CharArray(1_000_001)
                                            var n = 0
                                            while (n < chars.size) {
                                                val r = reader.read(chars, n, chars.size - n)
                                                if (r < 0) break
                                                n += r
                                            }
                                            require(n <= 1_000_000)
                                            store.import(String(chars, 0, n))
                                        } ?: error("No stream")
                                }
                                .getOrNull()
                        }
                    val result = imported?.let { store.addPresets(it) }
                    snackbar.showSnackbar(context.getString(when (result) {
                        PresetWrite.SAVED -> R.string.import_ok
                        PresetWrite.LIMIT -> R.string.limit
                        PresetWrite.DAMAGED -> R.string.storage_damaged
                        null -> R.string.import_error
                    }))
                }
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.signals), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.volume) + " · ${(o.volume * 100).toInt()}%",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(o.volume, onValueChange = { onChange(o.copy(volume = it)) })
        Text(stringResource(R.string.sound_profile), style = MaterialTheme.typography.titleSmall)
        soundProfiles.forEachIndexed { index, profile ->
            val title = stringResource(listOf(R.string.profile_electronic, R.string.profile_beep, R.string.profile_bell)[index])
            val previewLabel = stringResource(R.string.profile_preview, title)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(o.soundProfile == profile,
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                    onClick = { onChange(o.copy(soundProfile = profile))
                        Telemetry.event("sound_profile_selected", mapOf("profile" to profile)) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = o.soundProfile == profile, onClick = null)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { cues.play(Stage.WORK, o.copy(soundProfile = profile, voice = false)) }) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_play), contentDescription = previewLabel,
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(stringResource(R.string.listen_signals), style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth()) {
            listOf(Stage.WORK to R.string.work, Stage.REST to R.string.rest, null to R.string.finish_cue)
                .forEach { (stage, label) ->
                    SoundPreviewRow(stringResource(label)) { cues.play(stage, o.copy(voice = false)) }
                }
            SoundPreviewRow(stringResource(R.string.countdown_preview)) {
                cues.play(Stage.WORK, o.copy(voice = false), countdown = true)
            }
        }
        Text(stringResource(R.string.sound_hint), style = MaterialTheme.typography.bodySmall)
        Toggle(R.string.halfway, o.halfway) { onChange(o.copy(halfway = it)) }
        Text(stringResource(R.string.halfway_hint), style = MaterialTheme.typography.bodySmall)
        if (o.halfway) SoundPreviewRow(stringResource(R.string.halfway_preview)) {
            cues.play(Stage.WORK, o.copy(voice = false), halfway = true)
        }
        HorizontalDivider()
        Text(stringResource(R.string.voice_section), style = MaterialTheme.typography.titleMedium)
        Toggle(R.string.voice, o.voice) { onChange(o.copy(voice = it)) }
        if (o.voice) {
            for (rounds in listOf(false, true)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(o.voiceRounds == rounds,
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                    onValueChange = { onChange(o.copy(voiceRounds = rounds)) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(o.voiceRounds == rounds, onClick = null)
                    Text(stringResource(if (rounds) R.string.voice_stages_rounds else R.string.voice_stages), Modifier.weight(1f))
                }
            }
            val voiceState by cues.voiceState.collectAsStateWithLifecycle()
            Text(
                stringResource(
                    when (voiceState) {
                        "ready" -> R.string.voice_hint
                        "loading" -> R.string.voice_loading
                        else -> R.string.voice_unavailable
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { cues.play(Stage.WORK, o, lastRound = true, round = 3, totalRounds = 8) },
                enabled = voiceState == "ready",
            ) {
                Text(stringResource(R.string.listen_voice))
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.vibration_section), style = MaterialTheme.typography.titleMedium)
        Toggle(R.string.vibrate, o.vibrate) { onChange(o.copy(vibrate = it)) }
        OutlinedButton(onClick = { cues.play(Stage.WORK, o.copy(volume = 0f, vibrate = true, voice = false)) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vibration_preview)) }
        Toggle(R.string.countdown, o.countdown) { onChange(o.copy(countdown = it)) }
        Text(stringResource(R.string.countdown_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.behavior), style = MaterialTheme.typography.titleMedium)
        Toggle(R.string.keep_screen, o.keepScreen) { onChange(o.copy(keepScreen = it)) }
        Toggle(R.string.duck_music, o.duckMusic) { onChange(o.copy(duckMusic = it)) }
        Text(stringResource(R.string.focus_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Toggle(R.string.show_ready_modes, o.showReadyModes) { onChange(o.copy(showReadyModes = it)) }
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
        listOf(
                "system" to R.string.theme_system,
                "light" to R.string.theme_light,
                "dark" to R.string.theme_dark,
            )
            .forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = o.theme == value, role = androidx.compose.ui.semantics.Role.RadioButton,
                        onClick = { onChange(o.copy(theme = value)) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = o.theme == value,
                        onClick = null,
                    )
                    Text(stringResource(label), modifier = Modifier.weight(1f))
                }
            }
        HorizontalDivider()
        Toggle(R.string.show_stages, o.showStages) { onChange(o.copy(showStages = it)) }
        HorizontalDivider()
        Text(stringResource(R.string.data_section), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            shape = MaterialTheme.shapes.small,
            onClick = { export.launch("tabata-bata-timers.json") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.export))
        }
        OutlinedButton(
            shape = MaterialTheme.shapes.small,
            onClick = {
                importLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_timers))
        }
        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_body))
        Text(stringResource(R.string.feedback_section), style = MaterialTheme.typography.titleMedium)
        if (BuildConfig.SUPPORT_EMAIL.isNotBlank()) OutlinedButton(
            onClick = { reportOpen = true }, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) { Text(stringResource(R.string.report_problem)) }
        OutlinedButton(onClick = {
            onExternal()
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(STORE_PAGE))) }.onFailure {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.link_error)) }
            }
            Telemetry.event("store_review_open_attempt")
        }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.review_store_link))
        }
        if (BuildConfig.PRIVACY_URL.isNotBlank())
            TextButton(
                onClick = {
                    runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(BuildConfig.PRIVACY_URL),
                                )
                            )
                        }
                        .onFailure {
                            scope.launch {
                                snackbar.showSnackbar(context.getString(R.string.link_error))
                            }
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.privacy))
            }
    }
}

@Composable
private fun SoundPreviewRow(label: String, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = label }) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_play), null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
