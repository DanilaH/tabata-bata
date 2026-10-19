package ru.tabatabata

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    ) { Text(symbol, fontSize = 28.sp) }
}

@Composable
internal fun StageMark(index: Int, tint: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w=size.width; val h=size.height; val line=Stroke(width=w*.085f)
        fun segment(x:Float,y:Float,x2:Float,y2:Float) = drawLine(tint,Offset(w*x,h*y),Offset(w*x2,h*y2),w*.085f)
        when(index) {
            0 -> { drawCircle(tint,w*.32f,Offset(w*.5f,h*.57f),style=line);segment(.38f,.08f,.62f,.08f);segment(.5f,.57f,.5f,.35f) }
            1 -> { segment(.15f,.5f,.85f,.5f);segment(.2f,.25f,.2f,.75f);segment(.8f,.25f,.8f,.75f);segment(.08f,.35f,.08f,.65f);segment(.92f,.35f,.92f,.65f) }
            2,5 -> { segment(.35f,.2f,.35f,.8f);segment(.65f,.2f,.65f,.8f);if(index==5) {segment(.1f,.1f,.9f,.1f);segment(.1f,.9f,.9f,.9f)} }
            3 -> { drawArc(tint,35f,285f,false,Offset(w*.15f,h*.15f),androidx.compose.ui.geometry.Size(w*.7f,h*.7f),style=line);segment(.8f,.1f,.8f,.38f);segment(.8f,.38f,.55f,.38f) }
            4 -> { for(y in listOf(.25f,.5f,.75f)) segment(.15f,y,.85f,y) }
            else -> { segment(.18f,.52f,.4f,.75f);segment(.4f,.75f,.85f,.2f) }
        }
    }
}
