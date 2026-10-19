package ru.tabatabata

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    SideEffect {
        val style =
            if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                )
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { TabataTheme { App() } }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val activity = requireNotNull(LocalActivity.current) as ComponentActivity
    val store = remember { Store(context) }
    val scope = rememberCoroutineScope()
    val lastPresetId by store.lastPresetId.collectAsStateWithLifecycle(null)
    val presets by store.presets.collectAsStateWithLifecycle(emptyList())
    val options by store.options.collectAsStateWithLifecycle(Options())
    val snapshot by TimerService.state.collectAsStateWithLifecycle()
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
    BackHandler(enabled = tab == 2 && snapshot == null) { tab = 0 }
    val editorSaver = remember {
        Saver<Preset?, String>(
            save = { preset ->
                preset?.let {
                    org.json
                        .JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("plan", it.plan.json())
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
                        )
                    }
                    .getOrNull()
            },
        )
    }
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
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingPlan?.let { TimerService.command(context, "start", it) }
            pendingPlan = null
        }
    var draftTarget by rememberSaveable { mutableStateOf<String?>(null) }
    fun launch(plan: Plan, draftKey: String? = null) {
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
            notificationPrompt = true
        } else TimerService.command(context, "start", plan)
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
    DisposableEffect(running, darkSurface) {
        val style =
            if (running || darkSurface) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                )
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
        onDispose {}
    }
    Scaffold(
        containerColor =
            if (running) workoutBackground(snapshot?.interval?.stage, darkSurface)
            else MaterialTheme.colorScheme.background,
        bottomBar = {
            if (
                (snapshot == null && !editorOpen && tab == 0) ||
                    snapshot?.status == Status.COMPLETED
            )
                AdBanner(ads)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (snapshot?.status == Status.STOPPED) {
                // Return to the menu without flashing a result screen.
            } else if (snapshot != null)
                Workout(
                    snapshot!!,
                    options,
                    onClose = {
                        val result = snapshot?.status ?: Status.STOPPED
                        tab = 0
                        editorOpen = false
                        TimerService.clearResult()
                        ads.maybeShow(result)
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
                        launch(p, draftTarget)
                    },
                )
            else if (editorOpen)
                Editor(
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
                    onStart = { launch(it, editor?.id ?: "new") },
                )
            else
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { tab = if (tab == 2) 0 else 2 }) {
                            Text(
                                if (tab == 2) "← " + stringResource(R.string.saved)
                                else stringResource(R.string.settings)
                            )
                        }
                    }
                    when (tab) {
                        0 ->
                            Saved(
                                presets,
                                lastPresetId,
                                options.showReadyModes,
                                onNew = {
                                    editor = null
                                    editorOpen = true
                                },
                                onStart = { p ->
                                    scope.launch { store.saveLastPreset(p.id) }
                                    launch(p.plan)
                                },
                                onEdit = {
                                    editor = it
                                    editorOpen = true
                                },
                                onCopy = { p ->
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
                                    scope.launch {
                                        val removed = store.remove(p.id)
                                        if (
                                            snackbar.showSnackbar(
                                                context.getString(R.string.deleted),
                                                context.getString(R.string.undo),
                                                duration = SnackbarDuration.Long,
                                            ) == SnackbarResult.ActionPerformed
                                        )
                                            removed?.let { showWriteError(store.restore(it)) }
                                    }
                                },
                                onMove = { from, to ->
                                    scope.launch {
                                        presets.getOrNull(from)?.let {
                                            showWriteError(store.move(it.id, to - from))
                                        }
                                    }
                                },
                            )
                        2 ->
                            Settings(options, presets, store, snackbar) { o ->
                                scope.launch { store.saveOptions(o) }
                            }
                    }
                }
        }
    }
    if (recovery)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recovery_title)) },
            text = { Text(stringResource(R.string.recovery_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        recovery = false
                        TimerService.command(context, "recover")
                    }
                ) {
                    Text(stringResource(R.string.recover))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        context
                            .getSharedPreferences("session", Context.MODE_PRIVATE)
                            .edit()
                            .remove("active")
                            .apply()
                        recovery = false
                    }
                ) {
                    Text(stringResource(R.string.discard))
                }
            },
        )
    if (notificationPrompt && Build.VERSION.SDK_INT >= 33)
        AlertDialog(
            onDismissRequest = {
                notificationPrompt = false
                pendingPlan?.let { TimerService.command(context, "start", it) }
                pendingPlan = null
            },
            title = { Text(stringResource(R.string.notification_title)) },
            text = { Text(stringResource(R.string.notification_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        notificationPrompt = false
                        context
                            .getSharedPreferences("ui", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("notificationAsked", true)
                            .apply()
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(stringResource(R.string.allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        notificationPrompt = false
                        context
                            .getSharedPreferences("ui", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("notificationAsked", true)
                            .apply()
                        pendingPlan?.let { TimerService.command(context, "start", it) }
                        pendingPlan = null
                    }
                ) {
                    Text(stringResource(R.string.later))
                }
            },
        )
}

@Composable
private fun Saved(
    presets: List<Preset>,
    lastPresetId: String?,
    showReadyModes: Boolean,
    onNew: () -> Unit,
    onStart: (Preset) -> Unit,
    onEdit: (Preset) -> Unit,
    onCopy: (Preset) -> Unit,
    onDelete: (Preset) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val readyModes = listOf(
        Preset("builtin-classic", stringResource(R.string.classic_tabata), Plan()),
        Preset("builtin-30-30", "30/30 × 10", Plan(work = 30, rest = 30, reps = 10)),
        Preset("builtin-40-20", "40/20 × 8", Plan(work = 40, rest = 20, reps = 8)),
        Preset("builtin-60-30", "60/30 × 6", Plan(work = 60, rest = 30, reps = 6)),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
    ) {
        item {
            Button(onClick = onNew, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("+ " + stringResource(R.string.new_timer))
            }
        }
        if (presets.isNotEmpty()) item {
            Text(stringResource(R.string.saved), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp))
        }
        itemsIndexed(presets, key = { _, p -> p.id }) { index, p ->
            var menu by remember { mutableStateOf(false) }
            val contextMenuLabel = stringResource(R.string.mode_actions, p.name)
            Card(onClick = { onEdit(p) }, modifier = Modifier.animateItem().fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            TextButton(
                                onClick = { menu = true },
                                modifier =
                                    Modifier.semantics { contentDescription = contextMenuLabel },
                            ) {
                                Text("⋮", fontSize = 24.sp)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                listOf(
                                        R.string.edit to { onEdit(p) },
                                        R.string.copy to { onCopy(p) },
                                        R.string.up to { onMove(index, index - 1) },
                                        R.string.down to { onMove(index, index + 1) },
                                        R.string.delete to { onDelete(p) },
                                    )
                                    .forEach { (r, f) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(r)) },
                                            enabled =
                                                (r != R.string.up || index > 0) &&
                                                    (r != R.string.down ||
                                                        index < presets.lastIndex),
                                            onClick = {
                                                menu = false
                                                f()
                                            },
                                        )
                                    }
                            }
                        }
                    }
                    if (p.id == lastPresetId)
                        Text(
                            stringResource(R.string.last_launch),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    Text(
                        stringResource(
                            R.string.mode_summary,
                            durationInput(p.plan.work),
                            durationInput(p.plan.rest),
                            p.plan.reps,
                        ) +
                            if (p.plan.sets > 1)
                                " · " + stringResource(R.string.set_count, p.plan.sets)
                            else "",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        formatTime(p.plan.durationMs()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        shape = MaterialTheme.shapes.small,
                        onClick = { onStart(p) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.start))
                    }
                }
            }
        }
        if (showReadyModes) {
            item {
                Text(stringResource(R.string.ready_mode), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            }
            itemsIndexed(readyModes, key = { _, p -> p.id }) { _, ready ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ready.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (ready.id == "builtin-classic")
                                Text(stringResource(R.string.mode_summary, durationInput(ready.plan.work),
                                    durationInput(ready.plan.rest), ready.plan.reps), style = MaterialTheme.typography.bodySmall)
                            Text(formatTime(ready.plan.durationMs()), style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(onClick = { onStart(ready) }, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.start))
                        }
                    }
                }
            }
        }
    }

}

@Composable
internal fun Toggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
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
    onChange: (Options) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        Toggle(R.string.voice, o.voice) { onChange(o.copy(voice = it)) }
        if (o.voice) {
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
                onClick = { cues.play(Stage.WORK, o, lastRound = true) },
                enabled = voiceState == "ready",
            ) {
                Text(stringResource(R.string.listen_voice))
            }
        }
        Toggle(R.string.vibrate, o.vibrate) { onChange(o.copy(vibrate = it)) }
        OutlinedButton(onClick = { cues.play(Stage.WORK, o.copy(volume = 0f, vibrate = true, voice = false)) },
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
                    Modifier.fillMaxWidth().clickable { onChange(o.copy(theme = value)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = o.theme == value,
                        onClick = { onChange(o.copy(theme = value)) },
                    )
                    Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
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
        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.about_body))
        if (BuildConfig.SUPPORT_EMAIL.isNotBlank())
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = {
                    runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_SENDTO,
                                    android.net.Uri.fromParts(
                                        "mailto",
                                        BuildConfig.SUPPORT_EMAIL,
                                        null,
                                    ),
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
                Text(stringResource(R.string.support))
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
            Text("▶", color = MaterialTheme.colorScheme.primary)
        }
    }
}
