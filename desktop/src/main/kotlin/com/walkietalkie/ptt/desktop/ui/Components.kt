package com.walkietalkie.ptt.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Sfondo sfumato scuro di tutte le schermate. */
@Composable
fun WalkieBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(WalkieColors.background), content = content)
}

/** Contenitore "vetro smerigliato". */
fun Modifier.glass(radius: Dp = 20.dp, strong: Boolean = false) = this
    .clip(RoundedCornerShape(radius))
    .background(if (strong) WalkieColors.GlassStrong else WalkieColors.Glass)
    .border(1.dp, WalkieColors.Stroke, RoundedCornerShape(radius))

/** Chip di stato con pallino colorato (es. "● Internet"). */
@Composable
fun StatusChip(label: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(radius = 50.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(label, color = WalkieColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Cerchio con l'iniziale e il colore della persona; [badge] è un piccolo simbolo (📡/🌐). */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, speaking: Boolean = false, badge: String? = null) {
    val color = WalkieColors.forName(name)
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f))))
                .border(if (speaking) 3.dp else 0.dp, WalkieColors.Teal, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
        if (badge != null) Text(badge, fontSize = 11.sp)
    }
}

/** Barre animate tipo equalizzatore, accanto a chi sta parlando. */
@Composable
fun EqualizerBars(color: Color = WalkieColors.Teal, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0, 180, 90, 270).forEach { offset ->
            val h by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420, delayMillis = offset, easing = LinearEasing), RepeatMode.Reverse),
                label = "bar",
            )
            Box(Modifier.width(4.dp).height(18.dp * h).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

enum class PttState { IDLE, DISABLED, TRANSMITTING, RECEIVING }

/** Il grande tasto push-to-talk, con anelli che si allargano quando si parla o si ascolta. */
@Composable
fun PttButton(state: PttState, onPress: () -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val ringColor = when (state) {
        PttState.TRANSMITTING -> WalkieColors.Red
        PttState.RECEIVING -> WalkieColors.Teal
        else -> Color.Transparent
    }
    val fill = when (state) {
        PttState.TRANSMITTING -> Brush.radialGradient(listOf(Color(0xFFFF6B7A), WalkieColors.Red, WalkieColors.RedDark))
        PttState.DISABLED -> Brush.radialGradient(listOf(Color(0xFF3A4058), Color(0xFF262B3F)))
        else -> Brush.radialGradient(listOf(WalkieColors.Amber, WalkieColors.Orange, Color(0xFFC2410C)))
    }
    val pressScale by animateFloatAsState(if (state == PttState.TRANSMITTING) 1.08f else 1f, label = "press")

    Box(modifier.size(196.dp), contentAlignment = Alignment.Center) {
        if (state == PttState.TRANSMITTING || state == PttState.RECEIVING) {
            val transition = rememberInfiniteTransition(label = "rings")
            repeat(3) { i ->
                val t by transition.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1800, delayMillis = i * 600, easing = LinearEasing)),
                    label = "ring$i",
                )
                Box(
                    Modifier
                        .size(128.dp)
                        .graphicsLayer {
                            scaleX = 1f + t * 0.55f
                            scaleY = 1f + t * 0.55f
                            alpha = (1f - t) * 0.6f
                        }
                        .border(3.dp, ringColor, CircleShape),
                )
            }
        }
        Box(
            Modifier
                .size(128.dp)
                .scale(pressScale)
                .shadow(if (state == PttState.DISABLED) 0.dp else 24.dp, CircleShape, spotColor = WalkieColors.Orange)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            onRelease()
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = "Tieni premuto per parlare", tint = Color.White, modifier = Modifier.size(54.dp))
        }
    }
}

/**
 * Pulsante SOS: si attiva con [TAPS] tocchi entro [WINDOW_MS] (evita gli invii per sbaglio).
 * I pallini sotto mostrano quanti tocchi mancano.
 */
@Composable
fun SosButton(onTrigger: () -> Unit, modifier: Modifier = Modifier) {
    val taps = remember { mutableStateListOf<Long>() }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(taps.size) {
        if (taps.isNotEmpty()) {
            delay(SOS_WINDOW_MS)
            val now = System.currentTimeMillis()
            taps.removeAll { now - it >= SOS_WINDOW_MS }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .shadow(12.dp, CircleShape, spotColor = WalkieColors.Red)
                .clip(CircleShape)
                .background(if (taps.isEmpty()) Brush.linearGradient(listOf(Color(0xFF3A1520), Color(0xFF2A1018))) else WalkieColors.sos)
                .border(2.dp, WalkieColors.Red, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures {
                        val now = System.currentTimeMillis()
                        taps.removeAll { now - it >= SOS_WINDOW_MS }
                        taps.add(now)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (taps.size >= SOS_TAPS) {
                            taps.clear()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTrigger()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("SOS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(SOS_TAPS) { i ->
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(if (i < taps.size) WalkieColors.Red else WalkieColors.Stroke),
                )
            }
        }
        Text(
            if (taps.isEmpty()) "5 tocchi" else "ancora ${SOS_TAPS - taps.size}",
            color = WalkieColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

const val SOS_TAPS = 5
const val SOS_WINDOW_MS = 3_000L
