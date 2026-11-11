package ru.tabatabata

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal fun repeatDelay(repeats: Int): Long = when {
    repeats < 5 -> 180L
    repeats < 15 -> 90L
    else -> 40L
}

@Composable
internal fun RepeatButton(symbol: String, label: String, enabled: Boolean, tint: Color, onStep: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            if (interaction is PressInteraction.Cancel || interaction is PressInteraction.Press) repeated = false
        }
    }
    val scale by animateFloatAsState(if (pressed) .92f else 1f, label = "button press")
    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) {
            repeated = false
            delay(400)
            var repeats = 0
            while (true) {
                repeated = true
                step()
                delay(repeatDelay(repeats++))
            }
        }
    }
    FilledIconButton(
        onClick = { if (!repeated) step(); repeated = false },
        enabled = enabled,
        interactionSource = source,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = tint, contentColor = Color.White),
        modifier = Modifier.size(52.dp).scale(scale).semantics { contentDescription = label },
    ) { Icon(androidx.compose.ui.res.painterResource(if (symbol == "+") R.drawable.ic_add else R.drawable.ic_remove),
        null, Modifier.size(24.dp)) }
}

@Composable
internal fun StageMark(index: Int, tint: Color) {
    val drawable = when (index) {
        0 -> R.drawable.ic_stage_timer
        1 -> R.drawable.ic_stage_work
        2 -> R.drawable.ic_stage_rest
        3 -> R.drawable.ic_stage_rounds
        4 -> R.drawable.ic_stage_sets
        5 -> R.drawable.ic_stage_between
        else -> R.drawable.ic_check
    }
    Icon(androidx.compose.ui.res.painterResource(drawable), null, Modifier.size(28.dp), tint = tint)
}
