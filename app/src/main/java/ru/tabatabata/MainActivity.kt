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
        primary = Color(0xFF244D3B),
        onPrimary = Color(0xFFFAFBF6),
        background = Color(0xFFF0F0E9),
        surface = Color(0xFFF0F0E9),
        surfaceVariant = Color(0xFFE3E6DD),
        onSurface = Color(0xFF202624),
        onBackground = Color(0xFF202624),
        onSurfaceVariant = Color(0xFF58625B),
        outline = Color(0xFF747C73),
        outlineVariant = Color(0xFFC9CBC4),
        surfaceContainer = Color(0xFFE8EBE2),
        surfaceContainerLow = Color(0xFFECEEE6),
        surfaceContainerHigh = Color(0xFFE3E6DD),
        surfaceContainerHighest = Color(0xFFDEE2D8),
        secondaryContainer = Color(0xFFE3E6DD),
        secondary = Color(0xFF58625B),
    )
private val DarkPalette =
    darkColorScheme(
        primary = Color(0xFFA8CCB2),
        onPrimary = Color(0xFF163526),
        background = Color(0xFF1B201D),
        surface = Color(0xFF1B201D),
        surfaceVariant = Color(0xFF303932),
        onSurface = Color(0xFFE3E7DF),
        onBackground = Color(0xFFE3E7DF),
        onSurfaceVariant = Color(0xFFB5BFB4),
        outline = Color(0xFF849083),
        outlineVariant = Color(0xFF454F46),
        surfaceContainer = Color(0xFF262D28),
        surfaceContainerLow = Color(0xFF222824),
        surfaceContainerHigh = Color(0xFF303932),
        surfaceContainerHighest = Color(0xFF38423A),
        secondaryContainer = Color(0xFF303932),
        secondary = Color(0xFFB5BFB4),
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
    val lastPlan by store.lastPlan.collectAsStateWithLifecycle<Plan?>(null)
    val lastPresetId by store.lastPresetId.collectAsStateWithLifecycle(null)
    val presets by store.presets.collectAsStateWithLifecycle(emptyList())
    val options by store.options.collectAsStateWithLifecycle(Options())
    val snapshot by TimerService.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
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
    var notificationPrompt by remember { mutableStateOf(false) }
    var pendingPlan by remember { mutableStateOf<Plan?>(null) }
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
                                if (draftTarget == "quick") {
                                    editorOpen = false
                                    tab = 1
                                } else {
                                    editor = presets.find { it.id == draftTarget }
                                    editorOpen = true
                                }
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
                            if (presets.size >= 500 && presets.none { it.id == p.id })
                                snackbar.showSnackbar(context.getString(R.string.limit))
                            else {
                                store.updatePreset(p)
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
                    if (tab != 2)
                        PrimaryTabRow(tab) {
                            listOf(R.string.saved, R.string.quick).forEachIndexed { i, r ->
                                Tab(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    text = { Text(stringResource(r)) },
                                )
                            }
                        }
                    when (tab) {
                        0 ->
                            Saved(
                                presets,
                                lastPresetId,
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
                                        if (presets.size < 500)
                                            store.updatePreset(
                                                p.copy(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    name =
                                                        (p.name +
                                                                context.getString(
                                                                    R.string.copy_suffix
                                                                ))
                                                            .take(100),
                                                )
                                            )
                                    }
                                },
                                onDelete = { p ->
                                    scope.launch {
                                        store.remove(p.id)
                                        if (
                                            snackbar.showSnackbar(
                                                context.getString(R.string.deleted),
                                                context.getString(R.string.undo),
                                                duration = SnackbarDuration.Long,
                                            ) == SnackbarResult.ActionPerformed
                                        )
                                            store.updatePreset(p)
                                    }
                                },
                                onMove = { from, to ->
                                    scope.launch {
                                        val list = presets.toMutableList()
                                        if (to in list.indices) {
                                            list.add(to, list.removeAt(from))
                                            store.savePresets(list)
                                        }
                                    }
                                },
                            )
                        1 ->
                            if (lastPlan == null) CircularProgressIndicator()
                            else
                                Editor(
                                    null,
                                    initialPlan = lastPlan,
                                    onCancel = null,
                                    onSave = { p ->
                                        scope.launch {
                                            if (presets.size < 500) {
                                                store.updatePreset(p)
                                                EditorDrafts(context).clear("quick")
                                                tab = 0
                                            } else
                                                snackbar.showSnackbar(
                                                    context.getString(R.string.limit)
                                                )
                                        }
                                    },
                                    onStart = { launch(it, "quick") },
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
    onNew: () -> Unit,
    onStart: (Preset) -> Unit,
    onEdit: (Preset) -> Unit,
    onCopy: (Preset) -> Unit,
    onDelete: (Preset) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val defaultName = stringResource(R.string.classic_tabata)
    val ready = Preset(id = "builtin-classic", name = defaultName, plan = Plan())
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
            Card(onClick = { onEdit(p) }, modifier = Modifier.fillMaxWidth()) {
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
                                Text("•••")
                            }
                            DropdownMenu(menu, { menu = false }) {
                                listOf(
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
        item {
            Text(stringResource(R.string.ready_mode), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(defaultName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.mode_summary, "0:20", "0:10", 8),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(formatTime(ready.plan.durationMs()), style = MaterialTheme.typography.labelMedium)
                    if (lastPresetId == ready.id) Text(stringResource(R.string.last_launch), style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { onStart(ready) }, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.start))
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
                    if (imported != null && presets.size + imported.size <= 500) {
                        store.savePresets(presets + imported)
                        snackbar.showSnackbar(context.getString(R.string.import_ok))
                    } else snackbar.showSnackbar(context.getString(R.string.import_error))
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Stage.WORK to R.string.work, Stage.REST to R.string.rest, null to R.string.finish_cue)
                .forEach { (stage, label) ->
                    OutlinedButton(onClick = { cues.play(stage, o.copy(voice = false)) },
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("▶ " + stringResource(label), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            OutlinedButton(onClick = { cues.play(Stage.WORK, o.copy(voice = false), countdown = true) },
                shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text("▶ " + stringResource(R.string.countdown_preview))
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
