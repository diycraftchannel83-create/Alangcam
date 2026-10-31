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
    onBuyPremium: () -> Unit,
    onBuyCredit: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val isId = language == "id"
    val creditHours = state.creditSeconds / 3600L
    val creditMinutes = (state.creditSeconds % 3600L) / 60L

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CockpitSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitBorder)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = uiText(language, "UPGRADE & KREDIT REKAM", "UPGRADE & RECORDING CREDITS"),
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
                    text = String.format("%02d:%02d", creditHours, creditMinutes),
                    color = NeonGreen,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = uiText(language, "Sisa waktu rekam berbayar", "Remaining paid recording time"),
                    color = HudMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = CockpitBorder)
                Spacer(modifier = Modifier.height(14.dp))

                Text("PREMIUM LIFETIME", color = HudWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (state.isPremium) {
                        uiText(language, "Aktif — watermark dihapus permanen. Batas durasi tetap berlaku.", "Active — watermark permanently removed. Recording limits still apply.")
                    } else {
                        uiText(language, "Hapus watermark Created by CamRiderPro secara permanen. Tidak membuka durasi tanpa batas.", "Permanently remove the Created by CamRiderPro watermark. Does not unlock unlimited recording.")
                    },
                    color = HudMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onBuyPremium,
                    enabled = state.isConnected && !state.isPremium && state.premiumPrice != "—" && state.premiumPrice != "Belum tersedia",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)
                ) {
                    Text(
                        text = if (state.isPremium) {
                            uiText(language, "PREMIUM AKTIF", "PREMIUM ACTIVE")
                        } else {
                            "${uiText(language, "BELI PREMIUM", "BUY PREMIUM")} • ${state.premiumPrice}"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = CockpitBorder)
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = uiText(language, "KREDIT REKAM • 4 JAM", "RECORDING CREDIT • 4 HOURS"),
                    color = HudWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = uiText(language, "Tambahkan 240 menit waktu rekam. Kredit dapat ditumpuk dan dipakai sampai habis. Watermark tetap ada jika belum membeli Premium.", "Add 240 minutes of recording time. Credits accumulate and remain until used. Watermark stays unless Premium is purchased."),
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
                        text = "${uiText(language, "TOP UP 4 JAM", "TOP UP 4 HOURS")} • ${state.creditPrice}",
                        fontWeight = FontWeight.Bold
                    )
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
                        Text(uiText(language, "PULIHKAN PREMIUM", "RESTORE PREMIUM"), color = NeonGreen)
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
