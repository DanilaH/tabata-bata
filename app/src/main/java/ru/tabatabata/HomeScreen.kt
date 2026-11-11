package ru.tabatabata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

@Composable
internal fun Saved(
    presets: List<Preset>, lastPresetId: String?, showReadyModes: Boolean,
    onNew: () -> Unit, onStart: (Preset) -> Unit, onEdit: (Preset) -> Unit,
    onPreview: (Preset) -> Unit, onCopy: (Preset) -> Unit, onDelete: (Preset) -> Unit,
    onMove: (Int, Int) -> Unit, onHistory: () -> Unit, onInteraction: () -> Unit,
) {
    val readyModes = listOf(
        Preset("builtin-classic", stringResource(R.string.classic_tabata), Plan(), "green", 2),
        Preset("builtin-30-30", "30/30 × 10", Plan(work = 30, rest = 30, reps = 10)),
        Preset("builtin-40-20", "40/20 × 8", Plan(work = 40, rest = 20, reps = 8), "orange"),
        Preset("builtin-60-30", "60/30 × 6", Plan(work = 60, rest = 30, reps = 6), "violet"),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (presets.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.new_mode_hint), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("+ " + stringResource(R.string.new_timer))
                    }
                }
            } else {
                ScreenHeading(stringResource(R.string.saved), "+ " + stringResource(R.string.new_timer),
                    onNew, prominent = true)
            }
        }
        item { TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.history_open) + " →")
        } }
        itemsIndexed(presets, key = { _, p -> p.id }) { index, p ->
            var menu by remember { mutableStateOf(false) }
            val dark = MaterialTheme.colorScheme.background.red < .3f
            val tint = presetTint(p.accent, dark)
            Card(onClick = { onPreview(p) }, modifier = Modifier.animateItem().fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StageMark(presetMark(p.mark), tint)
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Box {
                            val label = stringResource(R.string.mode_actions, p.name)
                            TextButton(onClick = { onInteraction(); menu = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = label }) { Text("⋮", fontSize = 24.sp) }
                            DropdownMenu(menu, { menu = false }) {
                                listOf(
                                    R.string.start to { onStart(p) },
                                    R.string.plan_preview to { onPreview(p) },
                                    R.string.edit to { onEdit(p) },
                                    R.string.copy to { onCopy(p) },
                                    R.string.up to { onMove(index, index - 1) },
                                    R.string.down to { onMove(index, index + 1) },
                                    R.string.delete to { onDelete(p) },
                                ).forEach { (r, action) ->
                                    DropdownMenuItem(text = { Text(stringResource(r)) },
                                        enabled = (r != R.string.up || index > 0) &&
                                            (r != R.string.down || index < presets.lastIndex),
                                        onClick = { menu = false; action() })
                                }
                            }
                        }
                    }
                    if (p.id == lastPresetId) Text(stringResource(R.string.last_launch),
                        style = MaterialTheme.typography.labelSmall, color = tint)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val summary = stringResource(R.string.mode_summary,
                                durationInput(p.plan.work), durationInput(p.plan.rest), p.plan.reps)
                            Text(stringResource(R.string.compact_summary, durationInput(p.plan.work),
                                durationInput(p.plan.rest), p.plan.reps),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { contentDescription = summary })
                            if (p.plan.sets > 1) Text(stringResource(R.string.set_count, p.plan.sets),
                                style = MaterialTheme.typography.labelMedium)
                            Text(formatTime(p.plan.durationMs()), style = MaterialTheme.typography.titleMedium,
                                color = tint, fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(onClick = { onStart(p) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.start))
                        }
                    }
                }
            }
        }
        if (showReadyModes) {
            item { Text(stringResource(R.string.ready_mode), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) }
            itemsIndexed(readyModes, key = { _, p -> p.id }) { _, ready ->
                OutlinedCard(onClick = { onPreview(ready) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ready.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(formatTime(ready.plan.durationMs()), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onStart(ready) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.start))
                        }
                    }
                }
            }
        }
    }
}
