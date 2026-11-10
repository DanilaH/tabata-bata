package ru.tabatabata

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal fun presetTint(accent: String, dark: Boolean): Color = when (accent) {
    "green" -> if (dark) Color(0xFF74D8B0) else Color(0xFF126647)
    "orange" -> if (dark) Color(0xFFFFBD82) else Color(0xFFA04612)
    "violet" -> if (dark) Color(0xFFCFB4FF) else Color(0xFF7040B3)
    else -> if (dark) Color(0xFFB9C5FF) else Color(0xFF3049C7)
}

internal fun presetMark(mark: Int): Int = when (mark) { 1 -> 1; 2 -> 3; 3 -> 4; else -> 0 }

@Composable
internal fun PresetAppearance(accent: String, mark: Int, onChange: (String, Int) -> Unit) {
    Text(stringResource(R.string.card_appearance), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.card_appearance_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val dark = MaterialTheme.colorScheme.background.red < .3f
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presetAccents.forEachIndexed { i, value ->
            val label = stringResource(listOf(R.string.accent_blue, R.string.accent_green,
                R.string.accent_orange, R.string.accent_violet)[i])
            val description = stringResource(R.string.card_accent_label, label)
            FilledIconButton(onClick = { onChange(value, mark) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = presetTint(value, dark), contentColor = if (dark) Color(0xFF111522) else Color.White),
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = description; selected = value == accent
                }) { if (value == accent) Text("✓") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in 0..3) {
            val label = stringResource(listOf(R.string.mark_timer, R.string.mark_strength,
                R.string.mark_rounds, R.string.mark_sets)[value])
            val description = stringResource(R.string.card_mark_label, label)
            OutlinedIconButton(onClick = { onChange(accent, value) },
                border = androidx.compose.foundation.BorderStroke(if (value == mark) 2.dp else 1.dp,
                    if (value == mark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = description; selected = value == mark
                }) { StageMark(presetMark(value), MaterialTheme.colorScheme.primary) }
        }
    }
}
