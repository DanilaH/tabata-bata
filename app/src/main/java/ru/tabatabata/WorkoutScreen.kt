package ru.tabatabata

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
        Stage.WORK -> if (dark) Color(0xFF193D2B) else Color(0xFF176B44)
        Stage.REST,
        Stage.BETWEEN -> if (dark) Color(0xFF16364A) else Color(0xFF245ED0)
        else -> if (dark) Color(0xFF49350A) else Color(0xFF895C00)
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
        if (!active)
            Column(
                Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                Text(stage, style = MaterialTheme.typography.headlineLarge)
                if (s.statsAvailable) {
                    Text(
                        stringResource(R.string.result_time, formatTime(s.activeMs)),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(
                            R.string.result_rounds,
                            s.completedWork.size,
                            s.plan.reps * s.plan.sets,
                        )
                    )
                } else Text(stringResource(R.string.result_unknown))
                Button(
                    onClick = onRepeat,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.repeat))
                }
                if (onDraft != null)
                    TextButton(onClick = onDraft) { Text(stringResource(R.string.return_draft)) }
                OutlinedButton(
                    onClick = onClose,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home))
                }
            }
        else
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (locked)
                        Column(
                            Modifier.width(88.dp)
                                .heightIn(min = 48.dp)
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
                        ) {
                            Icon(
                                painter =
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_lock),
                                contentDescription = stringResource(R.string.lock_short),
                            )
                        }
                    Text(
                        stage,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(
                        onClick = { exit = true },
                        enabled = !locked,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = foreground,
                                disabledContentColor = foreground.copy(alpha = .4f),
                            ),
                    ) {
                        Text(stringResource(R.string.stop))
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth > maxHeight * 1.25f)
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ClockFace(s, Modifier.weight(1.35f).fillMaxHeight())
                            Column(
                                Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                WorkoutControls(s, locked, foreground)
                            }
                        }
                    else
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ClockFace(s, Modifier.weight(1f).fillMaxWidth())
                            if (options.showStages) StageQueue(s, Modifier.heightIn(max = 100.dp))
                            else
                                s.next?.let {
                                    Text(
                                        stringResource(
                                            R.string.next_stage,
                                            stringResource(stageString(it.stage)),
                                            formatTime(it.duration),
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            WorkoutControls(s, locked, foreground)
                        }
                }
            }
    }
    if (exit)
        AlertDialog(
            onDismissRequest = { exit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        exit = false
                        TimerService.command(context, "stop")
                    }
                ) {
                    Text(stringResource(R.string.stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { exit = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
}

@Composable
private fun ClockFace(s: Snapshot, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val clock = workoutClock(s.remaining)
        val scale = LocalDensity.current.fontScale
        val digits = workoutClock(s.interval?.duration ?: s.remaining).length.coerceAtLeast(2)
        val size =
            minOf(
                    240f,
                    maxWidth.value / (digits * .62f * scale),
                    (maxHeight.value - 24f - 44f * scale) /
                        (1.3f * scale),
                )
                .coerceAtLeast(20f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(44.dp * scale), contentAlignment = Alignment.Center) {
            if (s.status != Status.RUNNING)
                Text(
                    stringResource(
                        if (s.status == Status.INTERRUPTED) R.string.interrupted
                        else R.string.paused
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
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
            Spacer(Modifier.height(12.dp))
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
private fun WorkoutControls(s: Snapshot, locked: Boolean, foreground: Color) {
    val context = LocalContext.current
    Button(
        onClick = {
            TimerService.command(context, if (s.status == Status.RUNNING) "pause" else "resume")
        },
        enabled = !locked,
        shape = MaterialTheme.shapes.small,
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val previous = stringResource(R.string.previous)
        TextButton(
            onClick = { TimerService.command(context, "previous") },
            enabled = !locked,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .4f)),
            modifier = Modifier.semantics { contentDescription = previous },
        ) {
            Text("‹", fontSize = 28.sp)
            Text(stringResource(R.string.stage_short), style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (s.interval?.stage == Stage.PREPARE)
                    stringResource(R.string.rounds_ahead, s.plan.reps * s.plan.sets)
                else if (s.interval?.stage == Stage.COOL) stringResource(R.string.cool)
                else stringResource(R.string.round_position, s.interval?.rep ?: 0, s.plan.reps),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            if (s.plan.sets > 1 && (s.interval?.set ?: 0) > 0)
                Text(
                    "${stringResource(R.string.sets)} ${s.interval?.set ?: 0} / ${s.plan.sets}",
                    style = MaterialTheme.typography.labelMedium,
                )
        }
        val next = stringResource(R.string.next)
        TextButton(
            onClick = { TimerService.command(context, "next") },
            enabled = !locked,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .4f)),
            modifier = Modifier.semantics { contentDescription = next },
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
private fun StageQueue(s: Snapshot, modifier: Modifier) {
    val stages = remember(s.plan) { s.plan.stages() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        stages.drop(s.index + 1).take(3).forEach {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
