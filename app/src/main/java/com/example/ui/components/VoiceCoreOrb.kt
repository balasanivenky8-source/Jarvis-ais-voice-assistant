package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.JarvisConnectionState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.MidnightAbyss
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RoseMagenta
import com.example.ui.theme.SurfaceElevated
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceCoreOrb(
    connectionState: JarvisConnectionState,
    inputAmplitude: Float,
    outputAmplitude: Float,
    onPowerMicClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 280.dp
) {
    val activeAmplitude = when (connectionState) {
        JarvisConnectionState.LISTENING -> inputAmplitude
        JarvisConnectionState.SPEAKING -> outputAmplitude
        JarvisConnectionState.CONNECTING -> 0.35f
        JarvisConnectionState.DISCONNECTED -> 0.04f
    }

    val smoothAmp by animateFloatAsState(
        targetValue = activeAmplitude,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "orbAmplitude"
    )

    val primaryGlow by animateColorAsState(
        targetValue = when (connectionState) {
            JarvisConnectionState.DISCONNECTED -> Color(0xFF334155)
            JarvisConnectionState.CONNECTING -> ElectricViolet
            JarvisConnectionState.LISTENING -> ElectricCyan
            JarvisConnectionState.SPEAKING -> RoseMagenta
        },
        animationSpec = tween(durationMillis = 360),
        label = "primaryGlow"
    )

    val secondaryGlow by animateColorAsState(
        targetValue = when (connectionState) {
            JarvisConnectionState.DISCONNECTED -> Color(0xFF1E293B)
            JarvisConnectionState.CONNECTING -> ElectricCyan
            JarvisConnectionState.LISTENING -> ElectricViolet
            JarvisConnectionState.SPEAKING -> ElectricViolet
        },
        animationSpec = tween(durationMillis = 360),
        label = "secondaryGlow"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orbMotion")

    val rotationOuter by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (connectionState == JarvisConnectionState.CONNECTING) 2400 else 12000,
                easing = LinearEasing
            )
        ),
        label = "rotationOuter"
    )

    val rotationInner by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (connectionState == JarvisConnectionState.SPEAKING) 4500 else 9000,
                easing = LinearEasing
            )
        ),
        label = "rotationInner"
    )

    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathPhase"
    )

    val coreScale by animateFloatAsState(
        targetValue = when (connectionState) {
            JarvisConnectionState.DISCONNECTED -> 0.96f
            JarvisConnectionState.CONNECTING -> 1.02f
            JarvisConnectionState.LISTENING -> 1f + (smoothAmp * 0.14f)
            JarvisConnectionState.SPEAKING -> 1.04f + (smoothAmp * 0.22f)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "coreScale"
    )

    val contentDescription = if (connectionState == JarvisConnectionState.DISCONNECTED) {
        stringResource(R.string.mic_button_connect_cd)
    } else {
        stringResource(R.string.mic_button_disconnect_cd)
    }

    Box(
        modifier = modifier.size(orbSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minSide = size.minDimension
            val baseRadius = minSide * 0.30f

            // Ambient outer aura
            val auraRadius = minSide * (0.48f + smoothAmp * 0.12f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = if (connectionState == JarvisConnectionState.DISCONNECTED) 0.12f else 0.32f),
                        secondaryGlow.copy(alpha = if (connectionState == JarvisConnectionState.DISCONNECTED) 0.05f else 0.14f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = auraRadius
                ),
                radius = auraRadius,
                center = center
            )

            // Reactive radial waveform bars
            val barCount = 48
            val waveRingRadius = baseRadius * (1.22f + smoothAmp * 0.12f)
            for (i in 0 until barCount) {
                val angleRad = (i.toFloat() / barCount) * (2f * PI.toFloat())
                val harmonic = 0.5f + 0.5f * sin(angleRad * 4f + breathPhase * 2f)
                val idleRipple = if (connectionState == JarvisConnectionState.DISCONNECTED) {
                    2.dp.toPx() * harmonic
                } else {
                    0f
                }
                val dynamicLen = when (connectionState) {
                    JarvisConnectionState.DISCONNECTED -> 3.dp.toPx() + idleRipple
                    JarvisConnectionState.CONNECTING -> 8.dp.toPx() + 14.dp.toPx() * harmonic
                    JarvisConnectionState.LISTENING -> 6.dp.toPx() + (26.dp.toPx() * smoothAmp * (0.4f + 0.6f * harmonic))
                    JarvisConnectionState.SPEAKING -> 8.dp.toPx() + (34.dp.toPx() * smoothAmp * (0.35f + 0.65f * harmonic))
                }

                val start = Offset(
                    x = center.x + cos(angleRad) * waveRingRadius,
                    y = center.y + sin(angleRad) * waveRingRadius
                )
                val end = Offset(
                    x = center.x + cos(angleRad) * (waveRingRadius + dynamicLen),
                    y = center.y + sin(angleRad) * (waveRingRadius + dynamicLen)
                )
                val barColor = if (i % 2 == 0) primaryGlow else secondaryGlow
                drawLine(
                    color = barColor.copy(
                        alpha = if (connectionState == JarvisConnectionState.DISCONNECTED) 0.25f else 0.85f
                    ),
                    start = start,
                    end = end,
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Outer rotating orbital HUD arcs
            val outerArcRadius = baseRadius * 1.15f
            val arcStroke = 2.2.dp.toPx()
            drawArc(
                color = primaryGlow.copy(alpha = 0.65f),
                startAngle = rotationOuter,
                sweepAngle = 78f,
                useCenter = false,
                topLeft = Offset(center.x - outerArcRadius, center.y - outerArcRadius),
                size = Size(outerArcRadius * 2f, outerArcRadius * 2f),
                style = Stroke(width = arcStroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = secondaryGlow.copy(alpha = 0.55f),
                startAngle = rotationOuter + 180f,
                sweepAngle = 64f,
                useCenter = false,
                topLeft = Offset(center.x - outerArcRadius, center.y - outerArcRadius),
                size = Size(outerArcRadius * 2f, outerArcRadius * 2f),
                style = Stroke(width = arcStroke, cap = StrokeCap.Round)
            )

            // Inner counter-rotating ring
            val innerArcRadius = baseRadius * 0.92f
            drawArc(
                color = primaryGlow.copy(alpha = 0.45f),
                startAngle = rotationInner,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - innerArcRadius, center.y - innerArcRadius),
                size = Size(innerArcRadius * 2f, innerArcRadius * 2f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Central Interactive Power / Mic Core Button
        val interactionSource = remember { MutableInteractionSource() }
        val buttonGradient = Brush.radialGradient(
            colors = listOf(
                when (connectionState) {
                    JarvisConnectionState.DISCONNECTED -> SurfaceElevated
                    JarvisConnectionState.CONNECTING -> ElectricViolet.copy(alpha = 0.35f)
                    JarvisConnectionState.LISTENING -> ElectricCyan.copy(alpha = 0.28f)
                    JarvisConnectionState.SPEAKING -> RoseMagenta.copy(alpha = 0.34f)
                },
                MidnightAbyss,
                ObsidianVoid
            )
        )

        Box(
            modifier = Modifier
                .scale(coreScale)
                .size(96.dp)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(buttonGradient)
                .border(
                    width = 1.8.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.9f),
                            secondaryGlow.copy(alpha = 0.7f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = primaryGlow),
                    onClickLabel = contentDescription,
                    onClick = onPowerMicClick
                )
                .testTag("central_mic_button"),
            contentAlignment = Alignment.Center
        ) {
            val icon = when (connectionState) {
                JarvisConnectionState.DISCONNECTED -> Icons.Rounded.PowerSettingsNew
                JarvisConnectionState.CONNECTING -> Icons.Rounded.Sync
                JarvisConnectionState.LISTENING -> Icons.Rounded.Mic
                JarvisConnectionState.SPEAKING -> Icons.Rounded.GraphicEq
            }
            val iconTint = when (connectionState) {
                JarvisConnectionState.DISCONNECTED -> HudMuted
                JarvisConnectionState.CONNECTING -> HudWhite
                JarvisConnectionState.LISTENING -> ElectricCyan
                JarvisConnectionState.SPEAKING -> RoseMagenta
            }
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(38.dp)
            )
        }
    }
}
