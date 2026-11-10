package ru.tabatabata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HistoryScreen(onRepeat: (Preset) -> Unit, onPreview: (Preset) -> Unit,
    snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val repository = remember { HistoryRepository(context) }
    var limit by rememberSaveable { mutableIntStateOf(50) }
    val stream = remember(repository, limit) { repository.observe(limit) }
    val result by stream.collectAsStateWithLifecycle(initialValue = null)
    val page = result?.getOrNull()
    val scope = rememberCoroutineScope()
    var clearing by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) repository.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repository) {
        while (isActive) { delay(60_000); repository.refresh() }
    }
    if (clearing) AlertDialog(onDismissRequest = { if (!busy) clearing = false },
        title = { Text(stringResource(R.string.history_clear_title)) },
        text = { Text(stringResource(R.string.history_clear_body)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val ok = runCatching { repository.clear() }.isSuccess
                busy = false
                clearing = false
                if (!ok) snackbar.showSnackbar(context.getString(R.string.history_error))
            }
        }) { Text(stringResource(R.string.history_clear)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { clearing = false }) {
            Text(stringResource(R.string.cancel))
        } })

    LazyColumn(Modifier.fillMaxSize().testTag("history-list"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (result == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (result?.isFailure == true) item {
            Text(stringResource(R.string.history_error))
            TextButton(onClick = { repository.refresh() }) { Text(stringResource(R.string.history_retry)) }
        }
        if (page != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.history_this_week), style = MaterialTheme.typography.titleMedium)
                        val locale = context.resources.configuration.locales[0]
                        val zone = ZoneId.systemDefault()
                        val dates = DateTimeFormatter.ofPattern("d MMM", locale).withZone(zone)
                        Text("${dates.format(Instant.ofEpochMilli(page.window.start))} — ${dates.format(Instant.ofEpochMilli(page.window.end - 1))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTime(page.week.activeMs / 1000 * 1000), Modifier.testTag("history-week-time"),
                            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.history_active_time), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.history_week_counts, page.week.completed, page.week.stopped),
                            Modifier.testTag("history-week-counts"), style = MaterialTheme.typography.bodyMedium)
                        if (page.week.incompleteStats > 0) Text(stringResource(R.string.history_partial_week),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text(stringResource(R.string.history_local_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (page.total == 0) item {
                Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.history_empty_body), style = MaterialTheme.typography.bodyMedium)
            }
            items(page.records, key = { it.sessionId }) { record ->
                HistoryCard(record, { onRepeat(record.repeatPreset()) }, { onPreview(record.repeatPreset()) })
            }
            if (page.records.size < page.total) item {
                OutlinedButton(onClick = { limit += 50 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.history_more))
                }
            }
            if (page.total > 0) item {
                TextButton(onClick = { clearing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(record: WorkoutRecord, onRepeat: () -> Unit, onPreview: () -> Unit) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.red < .3f
    val tint = presetTint(record.accent, dark)
    val date = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", context.resources.configuration.locales[0])
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(record.finishedAt))
    Card(Modifier.fillMaxWidth().testTag("history-${record.sessionId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StageMark(presetMark(record.mark), tint)
                Text(record.name.ifBlank { stringResource(R.string.default_name) }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(if (record.completed) R.string.history_completed else R.string.history_stopped),
                style = MaterialTheme.typography.labelLarge, color = if (record.completed) tint else MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.statsAvailable) {
                Text(stringResource(R.string.history_session_time, formatTime(record.activeMs / 1000 * 1000)),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.history_rounds, record.completedRounds, record.plan.reps * record.plan.sets),
                    style = MaterialTheme.typography.bodyMedium)
            } else Text(stringResource(R.string.history_partial_record), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.compact_summary, durationInput(record.plan.work),
                durationInput(record.plan.rest), record.plan.reps) +
                if (record.plan.sets > 1) " · " + stringResource(R.string.set_count, record.plan.sets) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // FlowRow keeps both actions reachable with large text and on narrow displays.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onRepeat) { Text(stringResource(R.string.repeat)) }
                TextButton(onClick = onPreview) { Text(stringResource(R.string.history_saved_plan)) }
            }
        }
    }
}
