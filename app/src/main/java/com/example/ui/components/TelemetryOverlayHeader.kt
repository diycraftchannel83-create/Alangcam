package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.RecordingSettings
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.RajdhaniFontFamily
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelemetryOverlayHeader(
    isRecording: Boolean,
    recordingDurationSeconds: Long,
    settings: RecordingSettings,
    telemetry: GpsTelemetry,
    isNightBoostActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTimeString by remember {
        mutableStateOf(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
    }
    var batteryPercentage by remember { mutableStateOf(100) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            try {
                val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (level >= 0 && scale > 0) {
                    batteryPercentage = (level * 100 / scale)
                }
            } catch (_: Exception) {}
            delay(1000)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = modifier
            .width(210.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Left: Recording Status Pill
        Row(
            modifier = Modifier
                .border(
                    1.5.dp,
                    if (isRecording) NeonRed.copy(alpha = 0.85f) else NeonGreen.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("recording_status_pill"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .alpha(pulseAlpha)
                        .background(NeonRed, CircleShape)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "REC",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonRed,
                    fontFamily = OrbitronFontFamily,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                val hours = recordingDurationSeconds / 3600
                val minutes = (recordingDurationSeconds % 3600) / 60
                val seconds = recordingDurationSeconds % 60
                val timeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                Text(
                    text = timeFormatted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = HudWhite,
                    fontFamily = OrbitronFontFamily
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = NeonGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "STANDBY • READY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen,
                    fontFamily = RajdhaniFontFamily,
                    letterSpacing = 0.8.sp
                )
            }
        }

        // Right Column: GPS & Battery on top, Keterangan Rekaman 1080p directly below with same width
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // GPS Status & Battery Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.2.dp, CockpitBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val gpsFixed = telemetry.isGpsFixed
                    val gpsTint = if (gpsFixed) NeonGreen else NeonYellow
                    Icon(
                        imageVector = if (gpsFixed) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        contentDescription = "GPS Status",
                        tint = gpsTint,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (gpsFixed) "GPS LOCK (${telemetry.satellitesCount})" else "SEARCHING GPS...",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = gpsTint,
                        fontFamily = RajdhaniFontFamily,
                        letterSpacing = 0.4.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = "Battery",
                        tint = if (batteryPercentage > 20) NeonGreen else NeonRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "$batteryPercentage%",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite,
                        fontFamily = RajdhaniFontFamily
                    )
                }
            }

            // Bottom: Keterangan Rekaman 1080p with matching column width
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 3.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "${settings.resolution.label.substringBefore(" ")}  ${settings.targetFps}FPS  H.265",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = HudWhite,
                    fontFamily = RajdhaniFontFamily,
                    letterSpacing = 0.5.sp
                )
                if (isNightBoostActive) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = "Night Boost",
                        tint = NeonCyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "HDR",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        fontFamily = RajdhaniFontFamily
                    )
                }
            }
        }
    }
}
