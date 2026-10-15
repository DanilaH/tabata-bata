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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
    fun launch(plan: Plan) {
        scope.launch { store.saveLastPlan(plan) }
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
            if (snapshot != null)
                Workout(
                    snapshot!!,
                    options,
                    onClose = {
                        val result = snapshot?.status ?: Status.STOPPED
                        tab = 0
                        TimerService.clearResult()
                        ads.maybeShow(result)
                    },
                    onRepeat = {
                        val p = snapshot!!.plan
                        TimerService.clearResult()
                        launch(p)
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
                                editorOpen = false
                                snackbar.showSnackbar(context.getString(R.string.saved_ok))
                            }
                        }
                    },
                    onStart = {
                        launch(it)
                        editorOpen = false
                    },
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
                                onNew = {
                                    editor = null
                                    editorOpen = true
                                },
                                onStart = { launch(it.plan) },
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
                                                tab = 0
                                            } else
                                                snackbar.showSnackbar(
                                                    context.getString(R.string.limit)
                                                )
                                        }
                                    },
                                    onStart = { launch(it) },
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
        if (presets.isEmpty())
            item {
                Text(
                    stringResource(R.string.ready_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    defaultName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatTime(ready.plan.durationMs()),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        style =
                            MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        stringResource(R.string.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                    listOf(R.string.work to "20", R.string.rest to "10", R.string.reps to "8")
                        .forEach { (label, value) ->
                            Column(Modifier.weight(1f)) {
                                Text(
                                    value +
                                        if (label == R.string.reps) ""
                                        else " " + stringResource(R.string.seconds_short),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    stringResource(label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }
                Button(
                    onClick = { onStart(ready) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.start_workout))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.saved),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNew) { Text("+ " + stringResource(R.string.new_timer)) }
                }
                if (presets.isEmpty())
                    Text(
                        stringResource(R.string.no_saved),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
            }
        itemsIndexed(presets, key = { _, p -> p.id }) { index, p ->
            var menu by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            TextButton(onClick = { menu = true }) { Text("•••") }
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
                                            onClick = {
                                                menu = false
                                                f()
                                            },
                                        )
                                    }
                            }
                        }
                    }
                    Text(
                        "${p.plan.work} ${stringResource(R.string.seconds_short)} ${stringResource(R.string.work).lowercase()} · ${p.plan.rest} ${stringResource(R.string.seconds_short)} ${stringResource(R.string.rest).lowercase()} · ${p.plan.reps} ${stringResource(R.string.reps).lowercase()}" +
                            if (p.plan.sets > 1)
                                " · ${p.plan.sets} ${stringResource(R.string.sets).lowercase()}"
                            else "",
                        color = MaterialTheme.colorScheme.primary,
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
        if (presets.isNotEmpty())
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.saved),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNew) { Text("+ " + stringResource(R.string.new_timer)) }
                }
                OutlinedButton(
                    onClick = { onStart(ready) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(defaultName + " · 20/10 × 8")
                }
            }
    }
}

@Composable
private fun Editor(
    preset: Preset?,
    initialPlan: Plan? = null,
    onCancel: (() -> Unit)?,
    onSave: (Preset) -> Unit,
    onStart: (Plan) -> Unit,
) {
    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }
    var preview by rememberSaveable(preset?.id) { mutableStateOf(false) }
    val p = preset?.plan ?: initialPlan ?: Plan()
    var name by rememberSaveable(preset?.id) { mutableStateOf(preset?.name ?: "") }
    var prepare by rememberSaveable(preset?.id) { mutableStateOf(p.prepare.toString()) }
    var work by rememberSaveable(preset?.id) { mutableStateOf(p.work.toString()) }
    var rest by rememberSaveable(preset?.id) { mutableStateOf(p.rest.toString()) }
    var reps by rememberSaveable(preset?.id) { mutableStateOf(p.reps.toString()) }
    var sets by rememberSaveable(preset?.id) { mutableStateOf(p.sets.toString()) }
    var between by rememberSaveable(preset?.id) { mutableStateOf(p.between.toString()) }
    var cool by rememberSaveable(preset?.id) { mutableStateOf(p.cool.toString()) }
    var last by rememberSaveable(preset?.id) { mutableStateOf(p.lastRest) }
    var advanced by
        rememberSaveable(preset?.id) { mutableStateOf(p.sets > 1 || p.cool > 0 || p.lastRest) }
    val plan =
        runCatching {
                Plan(
                        prepare.toInt(),
                        work.toInt(),
                        rest.toInt(),
                        reps.toInt(),
                        sets.toInt(),
                        between.toInt(),
                        cool.toInt(),
                        last,
                    )
                    .also { it.validate() }
            }
            .getOrNull()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            onCancel?.let {
                TextButton(onClick = it) { Text("← " + stringResource(R.string.saved)) }
            }
            OutlinedTextField(
                name,
                { if (it.length <= 100) name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.intervals),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            NumberField(R.string.prepare, prepare) { prepare = it }
            NumberField(R.string.work, work) { work = it }
            NumberField(R.string.rest, rest) { rest = it }
            NumberField(R.string.reps, reps) { reps = it }
            TextButton(onClick = { advanced = !advanced }) {
                Text(stringResource(R.string.advanced) + if (advanced) " −" else " +")
            }
            if (advanced) {
                NumberField(R.string.sets, sets) { sets = it }
                NumberField(R.string.between, between) { between = it }
                NumberField(R.string.cool, cool) { cool = it }
                Toggle(R.string.last_rest, last) { last = it }
            }
            if (plan == null)
                Text(stringResource(R.string.invalid), color = MaterialTheme.colorScheme.error)
            else {
                Card(onClick = { preview = !preview }, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.total), modifier = Modifier.weight(1f))
                            Text(
                                formatTime(plan.durationMs()),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            stringResource(R.string.plan_preview) + if (preview) " ▴" else " ▾",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (preview)
                            Text(
                                plan.stages().take(8).joinToString(" → ") {
                                    "${context.getString(stageString(it.stage))} ${it.duration/1000}"
                                } + if (plan.stages().size > 8) " …" else ""
                            )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                shape = MaterialTheme.shapes.small,
                enabled = plan != null,
                onClick = { plan?.let(onStart) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.start))
            }
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                enabled = plan != null,
                onClick = {
                    plan?.let {
                        onSave(
                            Preset(
                                preset?.id ?: java.util.UUID.randomUUID().toString(),
                                name.trim().ifEmpty { context.getString(R.string.default_name) },
                                it,
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun NumberField(
    label: Int,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val seconds =
        label in
            listOf(R.string.prepare, R.string.work, R.string.rest, R.string.between, R.string.cool)
    val max =
        when (label) {
            R.string.reps -> 200
            R.string.sets -> 20
            else -> 3600
        }
    val min = if (label in listOf(R.string.work, R.string.reps, R.string.sets)) 1 else 0
    val title = stringResource(label)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title + if (seconds) " · " + stringResource(R.string.seconds_short) else "",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(
            onClick = {
                onChange(((value.toIntOrNull() ?: min) - 1).coerceIn(min, max).toString())
            },
            modifier = Modifier.semantics { contentDescription = "− $title" },
        ) {
            Text("−")
        }
        OutlinedTextField(
            value,
            { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(76.dp).semantics { contentDescription = title },
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ),
        )
        IconButton(
            onClick = {
                onChange(((value.toIntOrNull() ?: min) + 1).coerceIn(min, max).toString())
            },
            modifier = Modifier.semantics { contentDescription = "+ $title" },
        ) {
            Text("+")
        }
    }
}

@Composable
private fun Toggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked, onChange)
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
        Toggle(R.string.keep_screen, o.keepScreen) { onChange(o.copy(keepScreen = it)) }
        HorizontalDivider()
        Text(stringResource(R.string.signals), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.volume), style = MaterialTheme.typography.titleSmall)
        Slider(o.volume, onValueChange = { onChange(o.copy(volume = it)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                    Stage.WORK to R.string.work,
                    Stage.REST to R.string.rest,
                    null to R.string.completed,
                )
                .forEach { (stage, label) ->
                    OutlinedButton(
                        onClick = { cues.play(stage, o) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(label), maxLines = 2)
                    }
                }
        }
        Text(stringResource(R.string.sound_hint), style = MaterialTheme.typography.bodySmall)
        Toggle(R.string.vibrate, o.vibrate) { onChange(o.copy(vibrate = it)) }
        Toggle(R.string.countdown, o.countdown) { onChange(o.copy(countdown = it)) }
        Text(stringResource(R.string.countdown_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.behavior), style = MaterialTheme.typography.titleMedium)
        Toggle(R.string.duck_music, o.duckMusic) { onChange(o.copy(duckMusic = it)) }
        Text(stringResource(R.string.focus_hint), style = MaterialTheme.typography.bodySmall)
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
