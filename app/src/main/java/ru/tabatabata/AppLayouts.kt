package ru.tabatabata

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Keep forms and navigation within a comfortable reading distance on tablets. */
@Composable
internal fun ContentFrame(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(), content = content)
    }
}

/** Move actions below a heading before either can squeeze the other into fragments. */
@Composable
internal fun ScreenHeading(title: String, action: String, onAction: () -> Unit,
    modifier: Modifier = Modifier, prominent: Boolean = false) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        val heading: @Composable (Modifier) -> Unit = { m ->
            Text(title, modifier = m,
                style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        if (stacked) Column(Modifier.fillMaxWidth()) {
            heading(Modifier)
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
        } else Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            heading(Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** One inset owner, one scrollable surface: neither title nor actions can consume the body. */
@Composable
internal fun AppDialog(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    content()
                }
            }
        }
    }
}

@Composable
internal fun DialogActions(confirm: @Composable (Modifier) -> Unit,
    dismiss: @Composable (Modifier) -> Unit, alternative: (@Composable (Modifier) -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            confirm(Modifier.fillMaxWidth().heightIn(min = 48.dp))
            alternative?.invoke(Modifier.fillMaxWidth().heightIn(min = 48.dp))
            dismiss(Modifier.fillMaxWidth().heightIn(min = 48.dp))
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            dismiss(Modifier.heightIn(min = 48.dp))
            alternative?.invoke(Modifier.heightIn(min = 48.dp))
            confirm(Modifier.heightIn(min = 48.dp))
        }
    }
}
