package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AuroraAccent
import io.nekohasekai.sfa.compose.theme.AuroraCyan
import io.nekohasekai.sfa.compose.theme.AuroraFuchsia

enum class PowerState { Disconnected, Connecting, Connected }

/**
 * The connect control, matching the desktop client: a circle wrapped in a
 * conic-gradient ring that spins while connecting and drifts once connected,
 * with rings pulsing outward when the tunnel is up.
 */
@Composable
fun AuroraPowerButton(
    state: PowerState,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    enabled: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "aurora-power")

    // The ring races while connecting, then settles into a slow drift.
    val ringAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis = if (state == PowerState.Connecting) 1100 else 7000,
                        easing = LinearEasing,
                    ),
                repeatMode = RepeatMode.Restart,
            ),
        label = "ring-angle",
    )

    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "pulse",
    )

    val ringAlpha by animateFloatAsState(
        targetValue =
            when (state) {
                PowerState.Disconnected -> 0f
                PowerState.Connecting -> 1f
                PowerState.Connected -> 0.9f
            },
        animationSpec = tween(400),
        label = "ring-alpha",
    )

    val glow by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "glow",
    )

    val gradient = remember { listOf(AuroraCyan, AuroraAccent, AuroraFuchsia, AuroraCyan) }
    val surfaceIdle = MaterialTheme.colorScheme.surfaceContainerHigh
    val iconIdle = MaterialTheme.colorScheme.onSurfaceVariant
    val connected = state == PowerState.Connected

    Box(
        modifier = modifier.size(size * 1.6f),
        contentAlignment = Alignment.Center,
    ) {
        // Rings expanding outward while the tunnel is up.
        if (connected) {
            Box(
                Modifier
                    .size(size)
                    .drawBehind {
                        listOf(pulse, (pulse + 0.5f) % 1f).forEachIndexed { index, progress ->
                            val ringScale = 1f + progress * 0.55f
                            drawCircle(
                                color =
                                    if (index == 0) {
                                        AuroraCyan.copy(alpha = (1f - progress) * 0.5f)
                                    } else {
                                        AuroraFuchsia.copy(alpha = (1f - progress) * 0.4f)
                                    },
                                radius = this.size.minDimension / 2f * ringScale,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    },
            )
        }

        // Soft halo behind the button.
        if (connected) {
            Box(
                Modifier
                    .size(size * 1.45f)
                    .clip(CircleShape)
                    .drawBehind {
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            AuroraAccent.copy(alpha = glow * 0.35f),
                                            Color.Transparent,
                                        ),
                                    center = center,
                                    radius = this.size.minDimension / 2f,
                                ),
                        )
                    },
            )
        }

        // The conic gradient ring.
        Box(
            Modifier
                .size(size + 10.dp)
                .drawBehind {
                    if (ringAlpha <= 0.01f) return@drawBehind
                    rotate(ringAngle) {
                        drawCircle(
                            brush = Brush.sweepGradient(gradient),
                            radius = this.size.minDimension / 2f - 1.5.dp.toPx(),
                            alpha = ringAlpha,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                },
        )

        Box(
            modifier =
                Modifier
                    .size(size)
                    .scale(if (connected) 1f else 0.97f)
                    .clip(CircleShape)
                    .drawBehind {
                        if (connected) {
                            drawRect(
                                brush =
                                    Brush.linearGradient(
                                        colors = listOf(AuroraCyan, AuroraAccent, AuroraFuchsia),
                                        start = Offset.Zero,
                                        end = Offset(this.size.width, this.size.height),
                                    ),
                                size = Size(this.size.width, this.size.height),
                            )
                        } else {
                            drawRect(color = surfaceIdle)
                        }
                    }
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (connected) Color.White else iconIdle,
                modifier = Modifier.size(size / 2.6f),
            )
        }
    }
}
