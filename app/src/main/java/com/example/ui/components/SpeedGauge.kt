package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.TrackingStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BentoDarkSurface
import com.example.ui.theme.BentoHeroLilac
import com.example.ui.theme.BentoHeroOnLilac
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SpeedFast
import com.example.ui.theme.SpeedLow
import com.example.ui.theme.SpeedMed
import com.example.ui.theme.SpeedSprint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
fun SpeedGauge(
    currentSpeedKmh: Float,
    maxSpeedKmh: Float,
    status: TrackingStatus,
    modifier: Modifier = Modifier
) {
    val maxGaugeSpeed = max(60f, (maxSpeedKmh * 1.15f).coerceAtMost(160f))
    val normalizedSpeed = (currentSpeedKmh / maxGaugeSpeed).coerceIn(0f, 1f)

    val animatedSpeedFraction by animateFloatAsState(
        targetValue = normalizedSpeed,
        animationSpec = tween(durationMillis = 350),
        label = "SpeedGaugeAnimation"
    )

    val speedColor = when {
        currentSpeedKmh < 15f -> SpeedLow
        currentSpeedKmh < 30f -> SpeedMed
        currentSpeedKmh < 50f -> SpeedFast
        else -> SpeedSprint
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("speed_gauge_surface"),
        shape = RoundedCornerShape(32.dp),
        color = BentoHeroLilac,
        contentColor = BentoHeroOnLilac,
        border = BorderStroke(1.dp, BentoHeroLilac.copy(alpha = 0.8f)),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Bento Header: Status badge & Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(BentoHeroOnLilac.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = BentoHeroOnLilac,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "BIEŻĄCA PRĘDKOŚĆ",
                        style = MaterialTheme.typography.labelMedium,
                        color = BentoHeroOnLilac.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                }

                // Status chip
                val (statusText, statusBg, statusFg) = when (status) {
                    TrackingStatus.TRACKING -> Triple("AKTYWNY", EmeraldAccent.copy(alpha = 0.25f), BentoHeroOnLilac)
                    TrackingStatus.PAUSED -> Triple("PAUZA", AmberWarning.copy(alpha = 0.3f), BentoHeroOnLilac)
                    TrackingStatus.STOPPED -> Triple("GOTOWY", BentoHeroOnLilac.copy(alpha = 0.12f), BentoHeroOnLilac.copy(alpha = 0.7f))
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(statusBg)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusFg,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Speed Arc Canvas
            Box(
                modifier = Modifier
                    .size(width = 240.dp, height = 140.dp)
                    .testTag("speed_gauge_canvas_box"),
                contentAlignment = Alignment.BottomCenter
            ) {
                val outlineColor = BentoHeroOnLilac.copy(alpha = 0.18f)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 14.dp.toPx()
                    val arcSize = Size(size.width - strokeWidth, (size.height * 2) - strokeWidth)
                    val arcTopLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                    val startAngle = 180f
                    val sweepAngle = 180f

                    // Background Track Arc
                    drawArc(
                        color = outlineColor,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Tick Marks
                    val numTicks = 9
                    val radius = (size.width - strokeWidth) / 2
                    val center = Offset(size.width / 2, size.height)

                    for (i in 0 until numTicks) {
                        val tickFraction = i.toFloat() / (numTicks - 1)
                        val tickAngleRad = (180f + tickFraction * 180f) * (PI / 180f)
                        val innerR = radius - 16.dp.toPx()
                        val outerR = radius - 8.dp.toPx()

                        val startX = center.x + (innerR * cos(tickAngleRad)).toFloat()
                        val startY = center.y + (innerR * sin(tickAngleRad)).toFloat()
                        val endX = center.x + (outerR * cos(tickAngleRad)).toFloat()
                        val endY = center.y + (outerR * sin(tickAngleRad)).toFloat()

                        drawLine(
                            color = outlineColor,
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    // Active Colored Speed Arc
                    if (animatedSpeedFraction > 0.005f) {
                        val activeSweep = sweepAngle * animatedSpeedFraction
                        drawArc(
                            brush = Brush.sweepGradient(
                                0.5f to BentoHeroOnLilac,
                                0.75f to BentoHeroOnLilac,
                                1.0f to BentoHeroOnLilac,
                                center = center
                            ),
                            startAngle = startAngle,
                            sweepAngle = activeSweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Glowing head dot on needle end
                        val currentAngleRad = (180f + activeSweep) * (PI / 180f)
                        val dotRadius = radius
                        val dotX = center.x + (dotRadius * cos(currentAngleRad)).toFloat()
                        val dotY = center.y + (dotRadius * sin(currentAngleRad)).toFloat()

                        drawCircle(
                            color = BentoHeroOnLilac,
                            radius = strokeWidth * 0.65f,
                            center = Offset(dotX, dotY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = strokeWidth * 0.3f,
                            center = Offset(dotX, dotY)
                        )
                    }
                }

                // Digital Large Number in Center of Gauge
                Column(
                    modifier = Modifier.padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = String.format("%.1f", currentSpeedKmh),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Black
                        ),
                        color = BentoHeroOnLilac
                    )
                    Text(
                        text = "km/h",
                        style = MaterialTheme.typography.labelLarge,
                        color = BentoHeroOnLilac.copy(alpha = 0.75f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Min / Max Gauge Reference Labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.labelSmall,
                    color = BentoHeroOnLilac.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${maxGaugeSpeed.toInt()} km/h",
                    style = MaterialTheme.typography.labelSmall,
                    color = BentoHeroOnLilac.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

