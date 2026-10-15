package ru.tabatabata

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

internal fun workoutClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return if (seconds < 60) seconds.toString() else formatTime(ms)
}

@Composable
internal fun Workout(s: Snapshot, options: Options, onClose: () -> Unit, onRepeat: () -> Unit) {
    val context = LocalContext.current
    var locked by rememberSaveable(s.sessionId) { mutableStateOf(false) }
    var exit by remember { mutableStateOf(false) }
    val active = s.status in listOf(Status.RUNNING, Status.PAUSED, Status.INTERRUPTED)
    val dark = MaterialTheme.colorScheme.background.red < .3f
    val background =
        if (!active) MaterialTheme.colorScheme.background
        else
            when (s.interval?.stage) {
                Stage.WORK -> if (dark) Color(0xFF193D2B) else Color(0xFF286043)
                Stage.REST,
                Stage.BETWEEN -> if (dark) Color(0xFF16364A) else Color(0xFF245B79)
                else -> if (dark) Color(0xFF44361D) else Color(0xFF75551F)
            }
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
                Text(stringResource(R.string.done_body))
                Button(
                    onClick = onRepeat,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.repeat))
                }
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
                        Box(
                            Modifier.widthIn(min = 80.dp)
                                .heightIn(min = 48.dp)
                                .combinedClickable(onClick = {}, onLongClick = { locked = false })
                                .semantics {
                                    contentDescription = context.getString(R.string.unlock)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.unlock),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    else
                        TextButton(
                            onClick = { locked = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = foreground),
                        ) {
                            Text(stringResource(R.string.lock_short))
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
        val size =
            minOf(
                    240f,
                    maxWidth.value / (clock.length * .62f * scale),
                    maxHeight.value / (1.3f * scale),
                )
                .coerceAtLeast(20f)
        Text(
            clock,
            fontSize = size.sp,
            lineHeight = (size * 1.08f).sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun WorkoutControls(s: Snapshot, locked: Boolean, foreground: Color) {
    val context = LocalContext.current
    if (s.status != Status.RUNNING)
        Text(
            stringResource(
                if (s.status == Status.INTERRUPTED) R.string.interrupted else R.string.paused
            ),
            style = MaterialTheme.typography.titleMedium,
        )
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val previous = stringResource(R.string.previous)
        IconButton(
            onClick = { TimerService.command(context, "previous") },
            enabled = !locked,
            modifier = Modifier.semantics { contentDescription = previous },
        ) {
            Text("‹", fontSize = 32.sp)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${s.interval?.rep ?: 0} / ${s.plan.reps}",
                style = MaterialTheme.typography.headlineMedium,
            )
            if (s.plan.sets > 1)
                Text(
                    "${stringResource(R.string.sets)} ${s.interval?.set ?: 0} / ${s.plan.sets}",
                    style = MaterialTheme.typography.labelMedium,
                )
        }
        val next = stringResource(R.string.next)
        IconButton(
            onClick = { TimerService.command(context, "next") },
            enabled = !locked,
            modifier = Modifier.semantics { contentDescription = next },
        ) {
            Text("›", fontSize = 32.sp)
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
