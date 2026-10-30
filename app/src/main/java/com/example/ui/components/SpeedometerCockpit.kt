package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import com.example.data.model.GpsTelemetry
import com.example.data.model.SpeedUnit
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.RajdhaniFontFamily
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpeedometerCockpit(
    telemetry: GpsTelemetry,
    speedUnit: SpeedUnit,
    showGearRpm: Boolean = true,
    isLandscape: Boolean = true,
    modifier: Modifier = Modifier
) {
    val speed = (telemetry.speedKmh * speedUnit.multiplierFromKmh).coerceIn(0f, 220f)
    val animatedSpeed by animateFloatAsState(
        targetValue = speed,
        animationSpec = tween(durationMillis = 200),
        label = "gps_speed_needle"
    )

    val gaugeSize = if (isLandscape) 184.dp else 214.dp

    Column(
        modifier = modifier.testTag("speedometer_gauge"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Manual / Analog Speedometer Dial Gauge (0-200 Scale & Thick Yellow Needle)
        Box(
            modifier = Modifier.size(gaugeSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(gaugeSize)) {
                val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.44f
            val trackWidth = size.minDimension * 0.024f

            // Inner dark backdrop ring
            drawCircle(
                color = Color(0x73060C14),
                radius = r + trackWidth * 1.8f,
                center = Offset(cx, cy)
            )

            // Background arc track (270 degrees from 135 deg to 405 deg)
            drawArc(
                color = Color(0x401E2B40),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(trackWidth, cap = StrokeCap.Round)
            )

            // Redline Zone Arc (last 18%)
            drawArc(
                color = NeonRed.copy(alpha = 0.5f),
                startAngle = 135f + 270f * 0.82f,
                sweepAngle = 48.6f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(trackWidth * 1.3f, cap = StrokeCap.Round)
            )

            // Active Speed Arc Fill with Neon Cyan/Green glow
            val speedFraction = (animatedSpeed / 200f).coerceIn(0f, 1f)
            if (speedFraction > 0.01f) {
                val activeSweep = 270f * speedFraction
                val arcColor = when {
                    animatedSpeed > 160f -> NeonRed
                    animatedSpeed > 100f -> NeonAmber
                    else -> NeonCyan
                }
                drawArc(
                    color = arcColor,
                    startAngle = 135f,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(trackWidth * 1.5f, cap = StrokeCap.Round)
                )
            }

            // Dial Ticks and 0-200 Speed Scale Numbers
            for (i in 0..40) {
                val angleDeg = 135.0 + 270.0 * i / 40.0
                val angleRad = Math.toRadians(angleDeg)
                val isMajor = i % 4 == 0 // 0, 20, 40, 60, 80, 100, 120, 140, 160, 180, 200
                val isMid = i % 2 == 0 // every 10 km/h
                val speedAtTick = i * 5

                val innerLen = r - (if (isMajor) 18f else if (isMid) 11f else 6f)
                val outerLen = r - 2f
                val isRedline = speedAtTick >= 160

                val tickColor = when {
                    isRedline -> NeonRed
                    isMajor -> NeonCyan
                    else -> NeonCyan.copy(alpha = 0.45f)
                }

                val strokeW = if (isMajor) 3.2f else if (isMid) 1.8f else 1.0f

                val start = Offset(
                    cx + cos(angleRad).toFloat() * innerLen,
                    cy + sin(angleRad).toFloat() * innerLen
                )
                val end = Offset(
                    cx + cos(angleRad).toFloat() * outerLen,
                    cy + sin(angleRad).toFloat() * outerLen
                )

                drawLine(tickColor, start, end, strokeW, cap = StrokeCap.Round)

                // Scale number labels along the dial: 0, 20, 40, 60, 80, 100, 120, 140, 160, 180, 200
                if (isMajor) {
                    val numRadius = r - 32f
                    val numX = cx + cos(angleRad).toFloat() * numRadius
                    val numY = cy + sin(angleRad).toFloat() * numRadius
                    val numColor = when {
                        speedAtTick >= 160 -> android.graphics.Color.rgb(255, 42, 85)
                        speedAtTick >= 100 -> android.graphics.Color.rgb(255, 214, 0)
                        else -> android.graphics.Color.rgb(0, 240, 255)
                    }

                    val textPaint = AndroidPaint().apply {
                        isAntiAlias = true
                        color = numColor
                        textSize = size.minDimension * 0.052f
                        textAlign = AndroidPaint.Align.CENTER
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        setShadowLayer(4f, 0f, 0f, numColor)
                    }

                    drawContext.canvas.nativeCanvas.drawText(
                        speedAtTick.toString(),
                        numX,
                        numY + (textPaint.textSize * 0.35f),
                        textPaint
                    )
                }
            }

            // Thick Neon Yellow Needle (High-visibility rider cockpit styling)
            val needleRad = Math.toRadians(135.0 + 270.0 * animatedSpeed / 200.0)
            val needleLen = r * 0.76f
            val tip = Offset(
                cx + cos(needleRad).toFloat() * needleLen,
                cy + sin(needleRad).toFloat() * needleLen
            )
            val tail = Offset(
                cx - cos(needleRad).toFloat() * r * 0.16f,
                cy - sin(needleRad).toFloat() * r * 0.16f
            )

            // 1. Outer Yellow Needle Glow
            drawLine(
                color = NeonYellow.copy(alpha = 0.40f),
                start = tail,
                end = tip,
                strokeWidth = 22f,
                cap = StrokeCap.Round
            )
            // 2. Thick Yellow Needle Core
            drawLine(
                color = NeonYellow,
                start = tail,
                end = tip,
                strokeWidth = 9.5f,
                cap = StrokeCap.Round
            )
            // 3. Central bright high-contrast highlight
            drawLine(
                color = Color(0xFFFFFDE7),
                start = tail,
                end = tip,
                strokeWidth = 2.8f,
                cap = StrokeCap.Round
            )

            drawCircle(color = Color(0xFF070D16), radius = 17f, center = Offset(cx, cy))
            drawCircle(color = NeonYellow, radius = 17f, center = Offset(cx, cy), style = Stroke(3.8f))
            drawCircle(color = Color(0xFFFFFDE7), radius = 5f, center = Offset(cx, cy))
        }
        }

        // 2. Digital Speedometer positioned UNDER manual dial
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Speed Unit Pill Badge (Significantly enlarged legend)
            Box(
                modifier = Modifier
                    .background(
                        color = CockpitCard.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, NeonCyan.copy(alpha = 0.70f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = speedUnit.label.uppercase(),
                    color = NeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RajdhaniFontFamily,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Enormous Digital Speed Value - instantly legible for riders
            Text(
                text = animatedSpeed.toInt().toString(),
                color = HudWhite,
                fontSize = if (isLandscape) 48.sp else 54.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OrbitronFontFamily,
                letterSpacing = 1.sp
            )

            if (showGearRpm) {
                val gearText = if (telemetry.gear.isNotEmpty() && telemetry.gear != "--") {
                    "GEAR ${telemetry.gear} • ${telemetry.rpm} RPM"
                } else {
                    "GEAR / RPM --"
                }

                Box(
                    modifier = Modifier
                        .background(
                            color = NeonGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = gearText,
                        color = NeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}