package ru.tabatabata

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun workoutClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return if (seconds < 60) seconds.toString() else formatTime(ms)
}

internal fun workoutBackground(stage: Stage?, dark: Boolean): Color =
    when (stage) {
        Stage.WORK -> if (dark) Color(0xFF70251B) else Color(0xFFB9341C)
        Stage.REST, Stage.BETWEEN -> if (dark) Color(0xFF183C70) else Color(0xFF2457B8)
        Stage.COOL -> if (dark) Color(0xFF16483F) else Color(0xFF126451)
        else -> if (dark) Color(0xFF60400A) else Color(0xFF825700)
    }


@Composable
internal fun Workout(
    s: Snapshot,
    options: Options,
    onClose: () -> Unit,
    onRepeat: () -> Unit,
    onDraft: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var locked by rememberSaveable(s.sessionId) { mutableStateOf(false) }
    var unlockProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var exit by remember { mutableStateOf(false) }
    val active = s.status in listOf(Status.RUNNING, Status.PAUSED, Status.INTERRUPTED)
    val dark = MaterialTheme.colorScheme.background.red < .3f
    val background by animateColorAsState(
        if (active) workoutBackground(s.interval?.stage, dark) else MaterialTheme.colorScheme.background,
        animationSpec = tween(160), label = "stage color",
    )
    val foreground = if (active) Color(0xFFF7F8F1) else MaterialTheme.colorScheme.onBackground
    val stage =
        stringResource(
            if (s.status == Status.COMPLETED) R.string.completed
            else if (s.status == Status.STOPPED) R.string.stopped
            else stageString(s.interval?.stage)
        )
    BackHandler {
        if (active) {
            if (!locked) exit = true
        } else onClose()
    }
    Surface(color = background, contentColor = foreground, modifier = Modifier.fillMaxSize()) {
        if (!active) WorkoutResult(s, stage, onRepeat, onClose, onDraft)
        else BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
            // Choose from the window, before a wrapping title consumes any height.
            val landscape = maxWidth > maxHeight * 1.25f
            val largeText = LocalDensity.current.fontScale > 1.3f
            Column(Modifier.fillMaxSize()) {
                val lockAction: @Composable () -> Unit = {
                    if (locked)
                        Column(
                            Modifier.width(if (largeText) 160.dp else 80.dp)
                                .heightIn(min = if (largeText) 56.dp else 48.dp)
                                .pointerInput(locked) {
                                    detectTapGestures(
                                        onLongPress = { locked = false },
                                        onPress = {
                                            val progressJob =
                                                scope.launch {
                                                    val start =
                                                        android.os.SystemClock.uptimeMillis()
                                                    while (unlockProgress < 1f) {
                                                        unlockProgress =
                                                            ((android.os.SystemClock
                                                                    .uptimeMillis() - start) / 500f)
                                                                .coerceAtMost(1f)
                                                        delay(16)
                                                    }
                                                    locked = false
                                                }
                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                progressJob.cancel()
                                                unlockProgress = 0f
                                            }
                                        },
                                    )
                                }
                                .semantics {
                                    contentDescription = context.getString(R.string.unlock)
                                    onLongClick(label = context.getString(R.string.unlock)) {
                                        locked = false
                                        true
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                stringResource(R.string.unlock_short),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            LinearProgressIndicator(
                                progress = { unlockProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = foreground,
                            )
                        }
                    else
                        TextButton(
                            onClick = { locked = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = foreground),
                            modifier = Modifier.width(if (largeText) 160.dp else 80.dp).heightIn(min = if (largeText) 56.dp else 48.dp),
                        ) {
                            Icon(
                                painter =
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_lock),
                                contentDescription = stringResource(R.string.lock_short),
                            )
                        }
                }
                val stopAction: @Composable () -> Unit = {
                    TextButton(
                        onClick = { exit = true },
                        enabled = !locked,
                        modifier = Modifier.semantics { contentDescription = context.getString(R.string.stop) },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = foreground,
                                disabledContentColor = foreground.copy(alpha = .4f),
                            ),
                    ) {
                        Text(stringResource(if (largeText) R.string.stop_short else R.string.stop))
                    }
                }
                val stageTitles = Stage.entries.map { stringResource(stageString(it)) }
                if (largeText && !landscape) {
                    StableText(stage, stageTitles, Modifier.fillMaxWidth(), MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        lockAction()
                        Spacer(Modifier.weight(1f))
                        stopAction()
                    }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    lockAction()
                    StableText(stage, stageTitles, Modifier.weight(1f),
                        if (largeText) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                    stopAction()
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val compactControls = largeText || maxHeight < 500.dp
                    val detailsHeight = maxHeight * .4f
                    if (landscape)
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ClockFace(s, Modifier.weight(1f).fillMaxHeight())
                            Column(
                                Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                WorkoutPause(s, locked, foreground)
                                Spacer(Modifier.height(8.dp))
                                Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    WorkoutDetails(s, locked, foreground, compact = true)
                                    WorkoutUpcoming(s, options.showStages, compact = true)
                                }
                            }
                        }
                    else
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ClockFace(s, Modifier.weight(1f).fillMaxWidth())
                            WorkoutPause(s, locked, foreground)
                            Spacer(Modifier.height(8.dp))
                            if (compactControls) {
                                WorkoutDetails(s, locked, foreground, compact = true)
                                WorkoutUpcoming(s, options.showStages, compact = true)
                            } else Column(Modifier.fillMaxWidth().heightIn(max = detailsHeight)
                                .clipToBounds().verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                WorkoutUpcoming(s, options.showStages, compact = false)
                                WorkoutDetails(s, locked, foreground)
                            }
                        }
                }
            }
        }
    }
    if (exit) AppDialog(stringResource(R.string.exit_title), onDismiss = { exit = false }) {
        Text(stringResource(R.string.exit_body))
        DialogActions(
            confirm = { m -> TextButton(onClick = {
                exit = false; TimerService.command(context, "stop")
            }, modifier = m) { Text(stringResource(R.string.stop)) } },
            dismiss = { m -> TextButton(onClick = { exit = false }, modifier = m) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StableText(value: String, choices: List<String>, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium) {
    Box(modifier, contentAlignment = Alignment.Center) {
        (choices + value).distinct().forEach { label ->
            Text(label, style = style, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.hiddenWhen(label != value))
        }
    }
}

@Composable
private fun WorkoutResult(s: Snapshot, title: String, onRepeat: () -> Unit, onClose: () -> Unit,
    onDraft: (() -> Unit)?) {
    var appeared by remember(s.sessionId) { mutableStateOf(false) }
    LaunchedEffect(s.sessionId) { appeared = true }
    val mark by animateFloatAsState(if (appeared) 1f else 0f, tween(180), label = "completion mark")
    ContentFrame {
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
            val compact = maxWidth > maxHeight * 1.3f
            val summary: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(38.dp).alpha(mark).scale(.8f + .2f * mark), contentAlignment = Alignment.Center) {
                            StageMark(6, MaterialTheme.colorScheme.primary)
                        }
                        Text(title, style = MaterialTheme.typography.headlineLarge)
                    }
                    if (s.statsAvailable) {
                        Text(stringResource(R.string.result_time, formatTime(s.activeMs)),
                            style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.result_rounds, s.completedWork.size, s.plan.reps * s.plan.sets))
                    } else Text(stringResource(R.string.result_unknown))
                }
            }
            val actions: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRepeat, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.repeat)) }
                    OutlinedButton(onClick = onClose, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.home)) }
                    if (onDraft != null) TextButton(onClick = onDraft, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.return_draft))
                    }
                }
            }
            if (compact) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { summary() }
                Box(Modifier.weight(1f)) { actions() }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                summary()
                actions()
            }
        }
    }
}

@Composable
private fun ClockFace(s: Snapshot, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val clock = workoutClock(s.remaining)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val status = stringResource(R.string.paused)
        val statusStyle = MaterialTheme.typography.bodyMedium
        val statusHeight = with(density) { measurer.measure(AnnotatedString(status), statusStyle).size.height.toDp() }
        val pattern = remember(s.plan, clock.length) {
            val longest = (s.plan.stages().map { workoutClock(it.duration) } + clock).maxBy { it.length }
            longest.map { if (it.isDigit()) '8' else it }.joinToString("")
        }
        // Measure actual glyphs, including Android's nonlinear accessibility font scaling.
        val availableWidth = with(density) { maxWidth.toPx() }
        val availableHeight = with(density) { (maxHeight - statusHeight - 20.dp).coerceAtLeast(1.dp).toPx() }
        val size = remember(measurer, pattern, availableWidth, availableHeight, density.fontScale) {
            var lower = 1f
            var upper = 240f
            repeat(10) {
                val candidate = (lower + upper) / 2f
                val measured = measurer.measure(AnnotatedString(pattern),
                    TextStyle(fontSize = candidate.sp, lineHeight = (candidate * 1.08f).sp,
                        fontFeatureSettings = "tnum"), softWrap = false, maxLines = 1).size
                if (measured.width <= availableWidth && measured.height <= availableHeight) lower = candidate
                else upper = candidate
            }
            lower
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(statusHeight), contentAlignment = Alignment.Center) {
                Text(status, style = statusStyle, modifier = Modifier.hiddenWhen(s.status == Status.RUNNING))
            }
            Text(
                clock,
                modifier = Modifier.testTag("workout-clock"),
                fontSize = size.sp,
                lineHeight = (size * 1.08f).sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.height(8.dp))
            val duration = s.interval?.duration ?: 1L
            val progress = (1f - s.remaining.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
            val progressLabel = stringResource(R.string.stage_progress)
            LinearProgressIndicator(
                progress = { progress },
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(6.dp).semantics {
                        contentDescription = progressLabel
                    },
                color = LocalContentColor.current,
                trackColor = LocalContentColor.current.copy(alpha = .2f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun WorkoutPause(s: Snapshot, locked: Boolean, foreground: Color) {
    val context = LocalContext.current
    Button(
        onClick = {
            TimerService.command(context, if (s.status == Status.RUNNING) "pause" else "resume")
        },
        enabled = !locked,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = foreground,
                contentColor = Color(0xFF1B2922),
                disabledContainerColor = foreground.copy(alpha = .18f),
                disabledContentColor = foreground.copy(alpha = .5f),
            ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(stringResource(if (s.status == Status.RUNNING) R.string.pause else R.string.resume))
    }
}

@Composable
private fun WorkoutDetails(s: Snapshot, locked: Boolean, foreground: Color, compact: Boolean = false) {
    if (compact) { CompactWorkoutDetails(s, locked, foreground); return }
    val context = LocalContext.current
    var restAdded by remember(s.sessionId, s.index) { mutableStateOf(false) }
    LaunchedEffect(restAdded) { if (restAdded) { delay(700); restAdded = false } }
    val canExtendRest = s.interval?.stage in listOf(Stage.REST, Stage.BETWEEN)
    // Measure the same control on every stage, but place/expose it only during rest.
    // This keeps the weighted clock area identical without a hidden touch target.
    run {
        TextButton(
            onClick = { TimerService.command(context, "extendRest"); restAdded = true },
            enabled = canExtendRest && !locked && s.status != Status.INTERRUPTED,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground),
            modifier = Modifier.fillMaxWidth().then(
                if (canExtendRest) Modifier else Modifier
                    .clearAndSetSemantics {}
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {}
                    }
            ),
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (restAdded) Text("✓", modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = context.getString(R.string.rest_added)
                })
            }
            Text(stringResource(R.string.add_rest))
            Spacer(Modifier.width(24.dp))
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val ahead = stringResource(R.string.rounds_ahead, s.plan.reps * s.plan.sets)
        val position = stringResource(R.string.round_position, s.interval?.rep ?: 0, s.plan.reps)
        val cooling = stringResource(R.string.cool)
        StableText(when (s.interval?.stage) { Stage.PREPARE -> ahead; Stage.COOL -> cooling; else -> position },
            listOf(ahead, position, cooling), Modifier.fillMaxWidth())
        if (s.plan.sets > 1) Text("${stringResource(R.string.sets)} ${s.interval?.set ?: 0} / ${s.plan.sets}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.hiddenWhen((s.interval?.set ?: 0) == 0))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val previous = stringResource(R.string.previous)
        TextButton(
            onClick = { TimerService.command(context, "previous") },
            enabled = !locked,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .4f)),
            modifier = Modifier.weight(1f).semantics { contentDescription = previous },
        ) {
            Text("‹", fontSize = 28.sp)
            Text(stringResource(R.string.stage_short), style = MaterialTheme.typography.labelSmall)
        }

        val next = stringResource(R.string.next)
        TextButton(
            onClick = { TimerService.command(context, "next") },
            enabled = !locked,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .4f)),
            modifier = Modifier.weight(1f).semantics { contentDescription = next },
        ) {
            Text(stringResource(R.string.stage_short), style = MaterialTheme.typography.labelSmall)
            Text("›", fontSize = 28.sp)
        }
    }
    Text(
        "${stringResource(R.string.remaining)} ${formatTime(s.totalRemaining)}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun CompactWorkoutDetails(s: Snapshot, locked: Boolean, foreground: Color) {
    val context = LocalContext.current
    val stage = s.interval?.stage
    val canExtend = stage in listOf(Stage.REST, Stage.BETWEEN)
    val round = when (stage) {
        Stage.PREPARE -> stringResource(R.string.rounds_ahead, s.plan.reps * s.plan.sets)
        Stage.COOL -> stringResource(R.string.cool)
        else -> stringResource(R.string.workout_round_short, s.interval?.rep ?: 0, s.plan.reps)
    }
    val set = if (s.plan.sets > 1 && stage !in listOf(Stage.PREPARE, Stage.COOL))
        " · " + stringResource(R.string.workout_set_short, s.interval?.set ?: 0, s.plan.sets) else ""
    // Keep the counter and navigation in fixed slots across all stages, without a scroll viewport.
    val samples = listOf(stringResource(R.string.rounds_ahead, s.plan.reps * s.plan.sets),
        stringResource(R.string.cool), stringResource(R.string.workout_round_short, s.plan.reps, s.plan.reps) +
            if (s.plan.sets > 1) " · " + stringResource(R.string.workout_set_short, s.plan.sets, s.plan.sets) else "")
    StableText(round + set, samples, Modifier.fillMaxWidth(), MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { TimerService.command(context, "previous") }, enabled = !locked,
            modifier = Modifier.semantics { contentDescription = context.getString(R.string.previous) }) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_previous), null, tint = foreground.copy(alpha = if (locked) .4f else 1f))
        }
        TextButton(onClick = { TimerService.command(context, "extendRest") },
            enabled = canExtend && !locked && s.status != Status.INTERRUPTED,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = foreground),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).hiddenWhen(!canExtend)) {
            Text(stringResource(R.string.add_rest))
        }
        IconButton(onClick = { TimerService.command(context, "next") }, enabled = !locked,
            modifier = Modifier.semantics { contentDescription = context.getString(R.string.next) }) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_next), null, tint = foreground.copy(alpha = if (locked) .4f else 1f))
        }
    }
    Text("${stringResource(R.string.remaining)} ${formatTime(s.totalRemaining)}",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun WorkoutUpcoming(s: Snapshot, queue: Boolean, compact: Boolean) {
    if (queue && compact) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val upcoming = s.plan.stages().drop(s.index + 1).take(3)
            repeat(3) { index ->
                val item = upcoming.getOrNull(index)
                Column(Modifier.hiddenWhen(item == null)) {
                    Text(stringResource(stageString(item?.stage ?: Stage.WORK)), style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(item?.duration ?: 0), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } else if (queue) StageQueue(s, Modifier.height(72.dp))
    else Text(stringResource(R.string.next_stage, stringResource(stageString(s.next?.stage ?: Stage.WORK)),
        formatTime(s.next?.duration ?: 0)), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().hiddenWhen(s.next == null),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun StageQueue(s: Snapshot, modifier: Modifier) {
    val stages = remember(s.plan) { s.plan.stages() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        val next = stages.drop(s.index + 1).take(3)
        repeat(3) { position ->
            val interval = next.getOrNull(position)
            val it = interval ?: Interval(Stage.WORK, 0, 0, 0)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).hiddenWhen(interval == null),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(stageString(it.stage)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(formatTime(it.duration), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Reserve layout space without drawing or exposing a hidden accessibility target. */
private fun Modifier.hiddenWhen(hidden: Boolean): Modifier = if (!hidden) this else
    clearAndSetSemantics {}.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {}
    }
