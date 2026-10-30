package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.LockOpen

@Composable
fun HandlebarControlBar(
    isRecording: Boolean,
    isAudioEnabled: Boolean,
    isNightBoostActive: Boolean,
    isTorchActive: Boolean,
    isSpeedometerLocked: Boolean = true,
    onRecordToggle: () -> Unit,
    onCameraSwitch: () -> Unit,
    onAudioToggle: () -> Unit,
    onNightToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onLockScreen: () -> Unit,
    onToggleSpeedometerDrag: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = CockpitBlack.copy(alpha = 0.65f),
                shape = RoundedCornerShape(22.dp)
            )
            .border(1.2.dp, NeonCyan.copy(alpha = 0.50f), RoundedCornerShape(22.dp))
            .padding(vertical = 8.dp, horizontal = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Record Action Big Button with active halo pulse
        RecordActionButton(
            isRecording = isRecording,
            onClick = onRecordToggle
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Dual-column ergonomic keypad for easy right-thumb access without vertical stretching
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Switch Camera
                HandlebarIconButton(
                    icon = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera",
                    testTag = "btn_switch_camera",
                    onClick = onCameraSwitch
                )

                // Night Mode Boost
                HandlebarIconButton(
                    icon = Icons.Default.Bedtime,
                    contentDescription = "Night Mode Boost",
                    testTag = "btn_toggle_night_mode",
                    tint = if (isNightBoostActive) NeonCyan else HudMuted,
                    onClick = onNightToggle
                )

                // Screen Lock for motorcycle mount protection
                HandlebarIconButton(
                    icon = Icons.Default.Lock,
                    contentDescription = "Lock Screen Guard",
                    testTag = "btn_lock_screen",
                    tint = NeonYellow,
                    onClick = onLockScreen
                )

                // Ride History / Gallery
                HandlebarIconButton(
                    icon = Icons.Default.History,
                    contentDescription = "Ride History",
                    testTag = "btn_open_history",
                    tint = NeonCyan,
                    onClick = onOpenHistory
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Audio / Mic toggle
                HandlebarIconButton(
                    icon = if (isAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Toggle Audio",
                    testTag = "btn_toggle_audio",
                    tint = if (isAudioEnabled) NeonGreen else HudMuted,
                    onClick = onAudioToggle
                )

                // Flash / Torch toggle
                HandlebarIconButton(
                    icon = Icons.Default.FlashOn,
                    contentDescription = "Torch Light",
                    testTag = "btn_toggle_torch",
                    tint = if (isTorchActive) NeonAmber else HudMuted,
                    onClick = onTorchToggle
                )

                // Speedometer Position / Drag Toggle
                HandlebarIconButton(
                    icon = if (isSpeedometerLocked) Icons.Default.OpenWith else Icons.Default.LockOpen,
                    contentDescription = "Posisi Spedometer",
                    testTag = "btn_speedo_position",
                    tint = if (isSpeedometerLocked) HudWhite else NeonAmber,
                    onClick = onToggleSpeedometerDrag
                )

                // Settings
                HandlebarIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    testTag = "btn_open_settings",
                    tint = HudWhite,
                    onClick = onOpenSettings
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Exit application
        HandlebarIconButton(
            icon = Icons.Default.PowerSettingsNew,
            contentDescription = "Power off / Exit app",
            testTag = "btn_exit_app",
            tint = NeonRed,
            onClick = onExitApp
        )
    }
}

@Composable
private fun RecordActionButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isRecording) NeonRed else NeonGreen,
        label = "rec_bg"
    )

    val pulseTransition = rememberInfiniteTransition(label = "btn_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.52f,
        targetValue = 0.68f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "btn_pulse_scale"
    )

    Box(
        modifier = Modifier
            .size(54.dp)
            .drawBehind {
                if (isRecording) {
                    drawCircle(bgColor.copy(alpha = 0.20f), radius = size.minDimension * pulseScale)
                    drawCircle(bgColor.copy(alpha = 0.35f), radius = size.minDimension * 0.52f, style = Stroke(width = 2.dp.toPx()))
                } else {
                    drawCircle(bgColor.copy(alpha = 0.15f), radius = size.minDimension * 0.56f)
                }
            }
            .background(CockpitBlack.copy(alpha = 0.55f), CircleShape)
            .border(2.2.dp, if (isRecording) NeonRed else NeonGreen, CircleShape)
            .clickable { onClick() }
            .testTag("btn_record_toggle"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
            tint = if (isRecording) NeonRed else NeonGreen,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun HandlebarIconButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    tint: Color = HudWhite,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(46.dp)
            .drawBehind {
                drawCircle(tint.copy(alpha = 0.12f), radius = size.minDimension * 0.52f)
            }
            .background(CockpitBlack.copy(alpha = 0.45f), CircleShape)
            .border(1.2.dp, tint.copy(alpha = 0.45f), CircleShape)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
