package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.OverlayConfig
import com.example.data.model.RecordingSettings
import com.example.data.model.SpeedUnit
import com.example.data.model.VideoResolution
import com.example.ui.uiText
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonYellow

@Composable
fun SettingsDialog(
    settings: RecordingSettings,
    overlayConfig: OverlayConfig,
    isSimulating: Boolean,
    onSettingsChange: (RecordingSettings) -> Unit,
    onOverlayConfigChange: (OverlayConfig) -> Unit,
    onToggleSimulation: () -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    onOpenMonetization: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("settings_dialog"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CockpitSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiText(language, "PENGATURAN RIDERCAM", "CAMRIDER SETTINGS"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = HudWhite
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("btn_close_settings")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = HudMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                SettingsSectionHeader(
                    uiText(language, "MONETISASI CAMRIDERPRO", "CAMRIDERPRO MONETIZATION"),
                    Icons.Default.Videocam
                )
                SettingsCard {
                    Text(
                        text = uiText(language, "Premium Lifetime hanya menghapus watermark. Kredit rekam menambah durasi hingga 4 jam.", "Premium Lifetime removes only the watermark. Recording credits add up to 4 hours."),
                        fontSize = 11.sp,
                        color = HudMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NeonGreen, RoundedCornerShape(9.dp))
                            .clickable(onClick = onOpenMonetization)
                            .padding(vertical = 11.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiText(language, "UPGRADE & TOP UP KREDIT", "UPGRADE & TOP UP CREDITS"),
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Language preference
                SettingsSectionHeader(uiText(language, "BAHASA APLIKASI", "APP LANGUAGE"), Icons.Default.Settings)
                SettingsCard {
                    Text(uiText(language, "Pilih Bahasa", "Choose Language"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HudWhite)
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf(
                            "id" to "Indonesia", "en" to "English", "ms" to "Malaysia",
                            "ja" to "日本語", "ru" to "Русский", "th" to "ไทย",
                            "vi" to "Tiếng Việt", "zh" to "中文", "ko" to "한국어", "ar" to "العربية"
                        ).chunked(2).forEach { languageRow ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                languageRow.forEach { (code, label) ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(if (language == code) NeonGreen else CockpitBlack, RoundedCornerShape(8.dp))
                                            .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                                            .clickable { onLanguageChange(code) }
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (language == code) Color.Black else HudWhite)
                                    }
                                }
                                if (languageRow.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 1: Simulasi / Demo Mode
                SettingsSectionHeader(uiText(language, "SIMULASI PENGUJIAN DESK/EMULATOR", "DESK/EMULATOR TEST SIMULATION"), Icons.Default.DirectionsBike)
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiText(language, "Simulasi Perjalanan Motor", "Motorcycle Ride Simulation"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = HudWhite
                            )
                            Text(
                                text = uiText(language, "Simulasikan kecepatan motor, akselerasi, gigi & GPS tanpa harus mengendarai motor nyata", "Simulate motorcycle speed, acceleration, gear and GPS without riding a real motorcycle"),
                                fontSize = 11.sp,
                                color = HudMuted
                            )
                        }
                        Switch(
                            checked = isSimulating,
                            onCheckedChange = { onToggleSimulation() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = NeonGreen
                            ),
                            modifier = Modifier.testTag("switch_simulation")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: Video & Manual Record
                SettingsSectionHeader(uiText(language, "PENGATURAN REKAMAN", "RECORDING SETTINGS"), Icons.Default.Videocam)
                SettingsCard {
                    Text(
                        text = uiText(language, "Resolusi Video", "Video Resolution"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    VideoResolution.values().forEach { res ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSettingsChange(settings.copy(resolution = res)) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = settings.resolution == res,
                                onClick = { onSettingsChange(settings.copy(resolution = res)) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = NeonGreen,
                                    unselectedColor = HudMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = res.label,
                                fontSize = 12.sp,
                                color = if (settings.resolution == res) HudWhite else HudMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Frame rate
                    Text(
                        text = uiText(language, "Frame Rate Target", "Target Frame Rate"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        listOf(60, 30).forEach { fps ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (settings.targetFps == fps) NeonGreen else CockpitBlack,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                                    .clickable { onSettingsChange(settings.copy(targetFps = fps)) }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "$fps FPS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (settings.targetFps == fps) Color.Black else HudWhite
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = uiText(language, "Satuan Kecepatan", "Speed Unit"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudWhite
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        SpeedUnit.values().forEach { unit ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (settings.speedUnit == unit) NeonGreen else CockpitBlack,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                                    .clickable { onSettingsChange(settings.copy(speedUnit = unit)) }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                                    .testTag("speed_unit_${unit.name.lowercase()}")
                            ) {
                                Text(
                                    text = unit.label.uppercase(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (settings.speedUnit == unit) Color.Black else HudWhite
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = uiText(language, "VOICE ASSISTANT", "VOICE ASSISTANT"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonGreen
                    )
                    OverlayToggleItem(uiText(language, "Asisten Suara", "Voice Assistant"), settings.voiceAssistantEnabled) {
                        onSettingsChange(settings.copy(voiceAssistantEnabled = it))
                    }
                    OverlayToggleItem(uiText(language, "Peringatan Suara Batas Kecepatan", "Speed Limit Voice Alert"), settings.speedLimitAlertEnabled) {
                        onSettingsChange(settings.copy(speedLimitAlertEnabled = it))
                    }
                    OverlayToggleItem(uiText(language, "Suara Sambutan saat Mulai Rekam", "Welcome Voice on Recording Start"), settings.welcomeVoiceEnabled) {
                        onSettingsChange(settings.copy(welcomeVoiceEnabled = it))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(uiText(language, "Batas Kecepatan", "Speed Limit"), fontSize = 12.sp, color = HudWhite)
                        Text("${settings.speedLimitKmh} km/h", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                    }
                    Slider(
                        value = settings.speedLimitKmh.toFloat(),
                        onValueChange = { onSettingsChange(settings.copy(speedLimitKmh = it.toInt())) },
                        valueRange = 30f..160f,
                        steps = 25,
                        enabled = settings.voiceAssistantEnabled && settings.speedLimitAlertEnabled,
                        colors = SliderDefaults.colors(thumbColor = NeonGreen, activeTrackColor = NeonGreen)
                    )
                    Text(
                        text = uiText(language, "Jeda pengulangan peringatan: ${settings.alertRepeatSeconds} detik", "Alert repeat interval: ${settings.alertRepeatSeconds} seconds"),
                        fontSize = 10.sp,
                        color = HudMuted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { seconds ->
                            Box(
                                modifier = Modifier
                                    .background(if (settings.alertRepeatSeconds == seconds) NeonGreen else CockpitBlack, RoundedCornerShape(8.dp))
                                    .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                                    .clickable { onSettingsChange(settings.copy(alertRepeatSeconds = seconds)) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text("${seconds}s", fontSize = 11.sp, color = if (settings.alertRepeatSeconds == seconds) Color.Black else HudWhite)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = uiText(language, "Lokasi Penyimpanan Video:", "Video Storage Location:"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudMuted
                    )
                    Text(
                        text = "0://Movies/Camriderpro/ (Internal Storage)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 3: Mode Malam
                SettingsSectionHeader(uiText(language, "MODE MALAM (PENINGKATAN CAHAYA RENDAH)", "NIGHT MODE (LOW LIGHT BOOST)"), Icons.Default.Nightlight)
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiText(language, "Tingkatkan Kualitas Malam", "Enhance Night Quality"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = HudWhite
                            )
                            Text(
                                text = uiText(language, "Kompensasi eksposur tinggi untuk rekaman malam lebih terang dan jernih", "Increases exposure compensation for brighter, clearer night recordings"),
                                fontSize = 10.sp,
                                color = HudMuted
                            )
                        }
                        Switch(
                            checked = settings.nightModeEnabled,
                            onCheckedChange = { onSettingsChange(settings.copy(nightModeEnabled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = NeonCyan
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 4: Overlay & Tampilan
                SettingsSectionHeader(uiText(language, "OVERLAY & TAMPILAN", "OVERLAY & DISPLAY"), Icons.Default.Visibility)
                SettingsCard {
                    OverlayToggleItem(uiText(language, "Kecepatan Digital (Speedometer)", "Digital Speedometer"), overlayConfig.showSpeedometer) {
                        onOverlayConfigChange(overlayConfig.copy(showSpeedometer = it))
                    }
                    OverlayToggleItem(uiText(language, "Peta GPS & Rute Radar", "GPS Map & Route Radar"), overlayConfig.showGpsMap) {
                        onOverlayConfigChange(overlayConfig.copy(showGpsMap = it))
                    }
                    OverlayToggleItem(uiText(language, "Koordinat GPS & Ketinggian", "GPS Coordinates & Altitude"), overlayConfig.showCoordinates) {
                        onOverlayConfigChange(overlayConfig.copy(showCoordinates = it))
                    }
                    OverlayToggleItem(uiText(language, "Data Gear / RPM (OBD-II)", "Gear / RPM Data (OBD-II)"), overlayConfig.showGearRpm) {
                        onOverlayConfigChange(overlayConfig.copy(showGearRpm = it))
                    }
                    OverlayToggleItem(uiText(language, "Tanggal & Waktu Real-Time", "Real-Time Date & Time"), overlayConfig.showDateTime) {
                        onOverlayConfigChange(overlayConfig.copy(showDateTime = it))
                    }
                    Text(
                        text = uiText(
                            language,
                            "Watermark otomatis tampil pada akun Free dan hanya dihapus setelah Premium Lifetime dibeli.",
                            "Watermark is automatically shown on Free accounts and is removed only after Premium Lifetime is purchased."
                        ),
                        fontSize = 11.sp,
                        color = HudMuted
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = uiText(language, "POSISI SPEDOMETER & REKAMAN", "SPEEDOMETER & RECORDING POSITION"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonGreen
                    )
                    Text(
                        text = uiText(language, "Pilih preset atau geser langsung di layar. Posisi pada video rekaman otomatis sama persis.", "Choose a preset or drag it directly on screen. The recording position will match exactly."),
                        fontSize = 11.sp,
                        color = HudMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val presets = listOf(
                            Triple(uiText(language, "KANAN", "RIGHT"), 0.65f, 0.38f),
                            Triple(uiText(language, "TENGAH", "CENTER"), 0.50f, 0.38f),
                            Triple(uiText(language, "KANAN ATAS", "TOP RIGHT"), 0.68f, 0.26f)
                        )
                        presets.forEach { (label, x, y) ->
                            val isSelected = kotlin.math.abs(overlayConfig.speedometerXRatio - x) < 0.06f &&
                                    kotlin.math.abs(overlayConfig.speedometerYRatio - y) < 0.06f
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (isSelected) NeonGreen else CockpitBlack, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (isSelected) NeonGreen else CockpitBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        onOverlayConfigChange(
                                            overlayConfig.copy(speedometerXRatio = x, speedometerYRatio = y)
                                        )
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else HudWhite
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OverlayToggleItem(
                        uiText(language, "Kunci Posisi Spedometer", "Lock Speedometer Position"),
                        overlayConfig.isSpeedometerLocked
                    ) {
                        onOverlayConfigChange(overlayConfig.copy(isSpeedometerLocked = it))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NeonYellow,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = NeonYellow,
            fontFamily = com.example.ui.theme.RajdhaniFontFamily,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CockpitCard, RoundedCornerShape(14.dp))
            .border(1.dp, CockpitBorder, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun OverlayToggleItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = com.example.ui.theme.RajdhaniFontFamily,
            color = HudWhite
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = NeonGreen
            )
        )
    }
}
