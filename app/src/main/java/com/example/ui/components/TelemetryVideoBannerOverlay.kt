package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.OverlayConfig
import com.example.data.model.SpeedUnit
import com.example.ui.uiText
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonYellow
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.RajdhaniFontFamily
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelemetryVideoBannerOverlay(
    telemetry: GpsTelemetry,
    speedUnit: SpeedUnit,
    overlayConfig: OverlayConfig,
    modifier: Modifier = Modifier,
    language: String = "id"
) {
    var timePart by remember {
        mutableStateOf(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
    }
    var datePart by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            timePart = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
            datePart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
            delay(1000)
        }
    }

    val max = (telemetry.maxSpeedKmh * speedUnit.multiplierFromKmh).toInt()
    val avg = (telemetry.avgSpeedKmh * speedUnit.multiplierFromKmh).toInt()

    // Narrow vertical telemetry dock to preserve more camera view.
    Box(
        modifier = modifier
            .width(160.dp)
            .padding(horizontal = 5.dp, vertical = 5.dp)
            .testTag("telemetry_video_banner")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            VerticalStatItem(
                icon = Icons.Default.CalendarToday,
                label = uiText(language, "WAKTU", "TIME"),
                value = timePart,
                subText = datePart,
                accentColor = HudWhite
            )

            HorizontalDivider()

            if (overlayConfig.showCoordinates) {
                VerticalStatItem(
                    icon = Icons.Default.LocationOn,
                    label = uiText(language, "KOORDINAT", "COORDINATES"),
                    value = String.format(Locale.US, "%.4f°", telemetry.latitude),
                    subText = String.format(Locale.US, "%.4f°", telemetry.longitude),
                    accentColor = NeonCyan
                )
                HorizontalDivider()
            }

            VerticalStatItem(
                icon = Icons.Default.Height,
                label = uiText(language, "ELEVASI", "ALTITUDE"),
                value = "${telemetry.altitudeMeters.toInt()} M",
                subText = if (telemetry.isGpsFixed) "GPS LOCK" else "NO FIX",
                accentColor = HudWhite
            )

            HorizontalDivider()

            VerticalStatItem(
                icon = Icons.Default.Straighten,
                label = uiText(language, "JARAK", "DISTANCE"),
                value = String.format(Locale.US, "%.2f KM", telemetry.distanceKm),
                subText = uiText(language, "TOTAL PERJALANAN", "TOTAL TRIP"),
                accentColor = NeonYellow
            )

            HorizontalDivider()

            VerticalStatItem(
                icon = Icons.Default.Speed,
                label = uiText(language, "KECEPATAN MAKS", "MAX SPEED"),
                value = "$max ${speedUnit.label.uppercase()}",
                subText = uiText(language, "REKOR TERTINGGI", "PEAK RECORD"),
                accentColor = NeonAmber
            )

            HorizontalDivider()

            VerticalStatItem(
                icon = Icons.Default.TrendingUp,
                label = uiText(language, "RATA-RATA", "AVERAGE"),
                value = "$avg ${speedUnit.label.uppercase()}",
                subText = "AVG SPEED",
                accentColor = NeonGreen
            )
        }
    }
}

@Composable
private fun VerticalStatItem(
    icon: ImageVector,
    label: String,
    value: String,
    subText: String,
    accentColor: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = HudMuted,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = RajdhaniFontFamily,
                letterSpacing = 0.2.sp,
                maxLines = 1
            )
        }
        Text(
            text = value,
            color = accentColor,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OrbitronFontFamily,
            letterSpacing = 0.sp,
            maxLines = 1
        )
        if (subText.isNotEmpty()) {
            Text(
                text = subText,
                color = HudWhite.copy(alpha = 0.55f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HorizontalDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        NeonCyan.copy(alpha = 0.35f),
                        Color.Transparent
                    )
                )
            )
    )
}