package com.example.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.billing.BillingUiState
import com.example.ui.uiText
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonYellow

@Composable
fun MonetizationDialog(
    state: BillingUiState,
    language: String,
    onBuyCredit: () -> Unit,
    onBuyCredit2Hours: () -> Unit,
    onBuyCredit5Hours: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val isId = language == "id"
    val creditHours = state.creditSeconds / 3600L
    val creditMinutes = (state.creditSeconds % 3600L) / 60L
    val creditUnits = (state.creditSeconds * 1000L) / 3600L

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CockpitSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = uiText(language, "KREDIT REKAM", "RECORDING CREDITS"),
                    color = NeonYellow,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = uiText(language, "SALDO KREDIT REKAM", "RECORDING CREDIT BALANCE"),
                    color = HudMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format("%,d", creditUnits),
                    color = NeonGreen,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = String.format("%02d:%02d %s", creditHours, creditMinutes, uiText(language, "jam tersisa", "hours remaining")),
                    color = HudMuted,
                    fontSize = 11.sp
                )
                Text(
                    text = uiText(language, "Sisa waktu rekam berbayar", "Remaining paid recording time"),
                    color = HudMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = CockpitBorder)
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = uiText(language, "KREDIT REKAM • 1.000 KREDIT", "RECORDING CREDIT • 1,000 CREDITS"),
                    color = HudWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = uiText(language, "Beli kredit untuk menambah durasi rekaman. Setiap pembelian kredit menghapus watermark pada HUD dan video secara permanen.", "Buy credits to add recording time. Any credit purchase permanently removes the watermark from the HUD and recorded videos."),
                    color = HudMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onBuyCredit,
                    enabled = state.isConnected && state.creditPrice != "—" && state.creditPrice != "Belum tersedia",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonYellow, contentColor = Color.Black)
                ) {
                    Text(
                        text = "${uiText(language, "TOP UP 1.000 KREDIT", "TOP UP 1,000 CREDITS")} • ${state.creditPrice}",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onBuyCredit2Hours,
                    enabled = state.isConnected && state.credit2Price != "—" && state.credit2Price != "Belum tersedia",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)
                ) {
                    Text("${uiText(language, "TOP UP 2.000 KREDIT • 2 JAM", "TOP UP 2,000 CREDITS • 2 HOURS")} • ${state.credit2Price}", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onBuyCredit5Hours,
                    enabled = state.isConnected && state.credit5Price != "—" && state.credit5Price != "Belum tersedia",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonYellow, contentColor = Color.Black)
                ) {
                    Text("${uiText(language, "TOP UP 5.000 KREDIT • 5 JAM", "TOP UP 5,000 CREDITS • 5 HOURS")} • ${state.credit5Price}", fontWeight = FontWeight.Bold)
                }
                state.message?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(it, color = NeonYellow, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onRefresh) {
                        Text(uiText(language, "PERBARUI STATUS PEMBELIAN", "REFRESH PURCHASE STATUS"), color = NeonGreen)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(uiText(language, "TUTUP", "CLOSE"), color = HudMuted)
                    }
                }
                if (!state.isConnected) {
                    Text(
                        text = uiText(language, "Menghubungkan ke Google Play...", "Connecting to Google Play..."),
                        color = HudMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
