package ru.tabatabata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun PlanPreviewDialog(plan: Plan, name: String? = null, onClose: () -> Unit) {
    val model = remember(plan) { overview(plan) }
    var expandedBlock by rememberSaveable(plan) { mutableIntStateOf(-1) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight * .94f),
                shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(Modifier.testTag("plan-preview")) {
                    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.plan_preview), style = MaterialTheme.typography.titleLarge)
                                if (!name.isNullOrBlank()) Text(name, style = MaterialTheme.typography.bodyMedium)
                                Text(formatTime(model.duration), style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("preview-total"))
                                for ((stage, duration) in model.byStage) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(stageString(stage)), Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(formatTime(duration), fontWeight = FontWeight.Medium,
                                            modifier = Modifier.testTag("preview-stage-${stage.name}"))
                                    }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            }
                        }
                        for ((index, block) in model.blocks.withIndex()) {
                            if (block.set == 0) item(key = "single-$index") { PreviewStepRow(block.steps.single()) }
                            else {
                                item(key = "set-$index") {
                                    val expanded = expandedBlock == index
                                    val state = stringResource(if (expanded) R.string.preview_expanded else R.string.preview_collapsed)
                                    Surface(shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Row(Modifier.fillMaxWidth().clickable { expandedBlock = if (expanded) -1 else index }
                                            .semantics { stateDescription = state }
                                            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(stringResource(R.string.preview_set, block.set, plan.reps), fontWeight = FontWeight.Bold)
                                                Text(stringResource(R.string.starts_at, formatTime(block.steps.first().startsAt)),
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text(formatTime(block.duration))
                                            Text(if (expanded) " −" else " +", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                if (expandedBlock == index) items(block.steps) { step -> PreviewStepRow(step) }
                            }
                        }
                    }
                    HorizontalDivider()
                    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(8.dp)) {
                        Text(stringResource(R.string.preview_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewStepRow(step: PreviewStep) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(stageString(step.interval.stage)))
            Text(stringResource(R.string.starts_at, formatTime(step.startsAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatTime(step.interval.duration))
    }
}
