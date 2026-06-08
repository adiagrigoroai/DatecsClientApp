package com.itsmart.datecs.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmart.datecs.client.data.model.DeviceState
import com.itsmart.datecs.client.ui.components.ActionButton
import com.itsmart.datecs.client.ui.components.SectionCard
import com.itsmart.datecs.client.ui.components.StatusRow
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel

@Composable
fun StatusScreen(viewModel: MainViewModel, deviceState: DeviceState, isLoading: Boolean) {
    val anafOk = deviceState.lastConnectionState == "1"
    val nz = deviceState.nZreport.toIntOrNull() ?: 0
    val rapoarteRamase = if (nz > 0) (2500 - nz) else 0
    val tipAparat = when (deviceState.devType) {
        "1" -> "Casa de marcat"
        "2" -> "Imprimanta fiscala"
        else -> deviceState.devType.ifEmpty { "---" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // === BIG ANAF STATUS ===
        val anafColor = if (anafOk) AppColors.Success else AppColors.Danger
        val anafBg = if (anafOk) AppColors.SuccessBg else AppColors.DangerBg
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .background(anafBg, RoundedCornerShape(16.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(anafColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (anafOk) "\u2713" else "!",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.W800
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    if (anafOk) "ANAF CONECTAT" else "EROARE ANAF",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W800,
                    color = anafColor
                )
                if (deviceState.lastConnectionDate.isNotEmpty()) {
                    Text(
                        "Ultima conexiune: ${deviceState.lastConnectionDate}",
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary
                    )
                }
                if (!anafOk && deviceState.lastConnectionErrorCode.isNotEmpty()) {
                    Text(
                        "Cod eroare: ${deviceState.lastConnectionErrorCode}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W700,
                        color = AppColors.Danger,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // === DEVICE INFO (simplificat — fara parola, fara retea) ===
        SectionCard(title = "Informatii Aparat", accent = AppColors.Primary) {
            StatusRow("Model", deviceState.model.ifEmpty { "---" })
            StatusRow("Tip", tipAparat)
            StatusRow("Serie", deviceState.serialNumber.ifEmpty { "---" }, mono = true)
            StatusRow("Nr. Fiscal", deviceState.fiscalNumber.ifEmpty { "---" }, mono = true)
            StatusRow("CIF/CUI", deviceState.cifNumber.ifEmpty { "---" }, mono = true)
            StatusRow("Z Curent", deviceState.nZreport.ifEmpty { "---" })
            StatusRow("Rapoarte Ramase", "$rapoarteRamase / 2500",
                accent = if (rapoarteRamase < 100) AppColors.Danger
                    else if (rapoarteRamase < 500) AppColors.Warning else AppColors.Success)
            StatusRow("Data Service", deviceState.serviceDate.ifEmpty { "---" }, mono = true)
        }

        // === REFRESH ===
        Spacer(modifier = Modifier.height(8.dp))
        ActionButton(
            text = if (isLoading) "Se citesc datele..." else "Refresh Status",
            onClick = { viewModel.refreshStatus() },
            full = true, outline = true, enabled = !isLoading
        )
    }
}
