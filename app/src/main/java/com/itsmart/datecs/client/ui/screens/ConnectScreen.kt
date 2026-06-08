package com.itsmart.datecs.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsmart.datecs.client.data.model.ConnectionType
import com.itsmart.datecs.client.ui.components.ActionButton
import com.itsmart.datecs.client.ui.components.SectionCard
import com.itsmart.datecs.client.ui.components.StatusRow
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel

@Composable
fun ConnectScreen(viewModel: MainViewModel, connected: Boolean, onConnected: () -> Unit) {
    val scannedDevices by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val connectedModel by viewModel.connectedDeviceName.collectAsStateWithLifecycle()
    val connectedAddr by viewModel.connectedAddress.collectAsStateWithLifecycle()
    val connectedType by viewModel.connectedType.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()

    val lanHistory by viewModel.lanHistory.collectAsStateWithLifecycle()

    val usbDevices = scannedDevices.filter { it.type == ConnectionType.USB }
    val btDevices = scannedDevices.filter { it.type == ConnectionType.BLUETOOTH }
    val internalDevices = scannedDevices.filter { it.type == ConnectionType.INTERNAL }

    // LAN connection fields
    var lanIp by remember { mutableStateOf(viewModel.loadLanIp()) }
    var lanPort by remember { mutableStateOf(viewModel.loadLanPort()) }

    // Auto-scan on first load + repeat every 3 seconds
    LaunchedEffect(Unit) {
        while (true) {
            try { viewModel.scanDevices() } catch (_: Exception) {}
            kotlinx.coroutines.delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // Connecting indicator
        if (isConnecting) {
            SectionCard(title = "Conectare in curs...", accent = AppColors.Info) {
                Text(
                    "Detectare model... Va rugam asteptati...",
                    fontSize = 12.sp, color = AppColors.TextSecondary,
                    fontWeight = FontWeight.W600,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        // Connected device card
        if (connected && !isConnecting) {
            SectionCard(title = "Dispozitiv Conectat", accent = AppColors.Success) {
                StatusRow("Model", connectedModel)
                StatusRow("Serie", deviceState.serialNumber.ifEmpty { "Se citeste..." }, mono = true)
                StatusRow("Nr. Fiscal", deviceState.fiscalNumber.ifEmpty { "---" }, mono = true)
                StatusRow("Conexiune", when (connectedType) {
                    ConnectionType.USB -> "USB"
                    ConnectionType.BLUETOOTH -> "Bluetooth SPP"
                    ConnectionType.LAN -> "LAN (TCP/IP)"
                    ConnectionType.INTERNAL -> "Fiscal Intern"
                })
                if (connectedType == ConnectionType.LAN) {
                    StatusRow("Adresa", connectedAddr, mono = true)
                }
                Spacer(modifier = Modifier.height(10.dp))
                ActionButton(
                    text = "Deconectare",
                    onClick = { viewModel.disconnectDevice() },
                    outline = true, danger = true, full = true
                )
            }
        }

        // LAN section — IP/Port input at top, history below
        SectionCard(title = "LAN / WiFi (TCP/IP)", accent = AppColors.Warning) {
            // IP + Port input fields (always visible)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = lanIp,
                    onValueChange = { lanIp = it },
                    label = { Text("IP Aparat", fontSize = 10.sp) },
                    placeholder = { Text("192.168.1.100") },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
                OutlinedTextField(
                    value = lanPort,
                    onValueChange = { lanPort = it },
                    label = { Text("Port", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            ActionButton(
                text = "Conectare LAN",
                onClick = {
                    val address = "$lanIp:$lanPort"
                    viewModel.saveLanConfig(lanIp, lanPort)
                    viewModel.connectDevice(address, "LAN $lanIp", ConnectionType.LAN)
                },
                full = true,
                enabled = !connected && !isConnecting && lanIp.isNotEmpty(),
                color = AppColors.Warning
            )

            // LAN History
            if (lanHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "ISTORIC CONEXIUNI LAN",
                    fontSize = 10.sp, fontWeight = FontWeight.W700,
                    color = AppColors.TextSecondary, letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                lanHistory.forEach { entry ->
                    val isThis = connected && connectedAddr == entry.address
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(
                                if (isThis) AppColors.SuccessBg else AppColors.Background,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isThis) AppColors.Success.copy(alpha = 0.27f) else AppColors.Border,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !connected && !isConnecting) {
                                val parts = entry.address.split(":", limit = 2)
                                lanIp = parts[0]
                                lanPort = parts.getOrElse(1) { "3999" }
                                viewModel.saveLanConfig(lanIp, lanPort)
                                viewModel.connectDevice(entry.address, entry.name, ConnectionType.LAN)
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, fontWeight = FontWeight.W700, fontSize = 13.sp, color = AppColors.TextPrimary)
                            if (entry.info.isNotEmpty()) {
                                Text(entry.info, fontSize = 10.sp, color = AppColors.TextSecondary, maxLines = 1)
                            }
                            Text(entry.address, fontSize = 10.sp, color = AppColors.Warning, fontFamily = FontFamily.Monospace)
                        }
                        Badge("LAN", AppColors.Warning)
                    }
                }
            }
        }

        // Fiscal Intern section (BC50, DP-25X — apare doar pe device-uri cu fiscal onboard)
        if (internalDevices.isNotEmpty()) {
            SectionCard(title = "Fiscal Intern (memorie onboard)", accent = AppColors.Primary) {
                internalDevices.forEach { device ->
                    val isThis = connected && connectedAddr == device.address
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(
                                if (isThis) AppColors.SuccessBg else AppColors.Background,
                                RoundedCornerShape(10.dp)
                            )
                            .border(
                                1.dp,
                                if (isThis) AppColors.Success.copy(alpha = 0.27f) else AppColors.Border,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = !connected && !isConnecting) {
                                viewModel.connectDevice(device.address, device.name, ConnectionType.INTERNAL)
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.W700, fontSize = 13.sp, color = AppColors.TextPrimary)
                            Text(device.info, fontSize = 10.5.sp, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace)
                        }
                        Badge("INTERN", AppColors.Primary)
                    }
                }
            }
        }

        // USB section
        SectionCard(title = "USB Host", accent = AppColors.Success) {
            ActionButton(
                text = "Rescaneaza dispozitive",
                onClick = { viewModel.scanDevices() },
                small = true, enabled = !isConnecting
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (usbDevices.isEmpty()) {
                Text(
                    "Niciun dispozitiv USB Datecs detectat.\nConectati aparatul prin cablu USB.",
                    fontSize = 12.sp, color = AppColors.TextTertiary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            usbDevices.forEach { device ->
                val isThis = connected && connectedAddr == device.address
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            if (isThis) AppColors.SuccessBg else AppColors.Background,
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (isThis) AppColors.Success.copy(alpha = 0.27f) else AppColors.Border,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !connected && !isConnecting) {
                            viewModel.connectDevice(device.address, device.name, ConnectionType.USB)
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.W700, fontSize = 13.sp, color = AppColors.TextPrimary)
                        Text(device.info, fontSize = 10.5.sp, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                    Badge("USB", AppColors.Success)
                }
            }
        }

        // Bluetooth section
        SectionCard(title = "Bluetooth (impereche)", accent = AppColors.Primary) {
            if (btDevices.isEmpty()) {
                Text(
                    "Niciun dispozitiv Bluetooth impereche gasit.\nImperecheati aparatul din Settings > Bluetooth.",
                    fontSize = 12.sp, color = AppColors.TextTertiary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            btDevices.forEach { device ->
                val isThis = connected && connectedAddr == device.address
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            if (isThis) AppColors.SuccessBg else AppColors.Background,
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (isThis) AppColors.Success.copy(alpha = 0.27f) else AppColors.Border,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !connected && !isConnecting) {
                            viewModel.connectDevice(device.address, device.name, ConnectionType.BLUETOOTH)
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.W700, fontSize = 13.sp, color = AppColors.TextPrimary)
                        Text(device.address, fontSize = 10.5.sp, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(device.info, fontSize = 9.5.sp, color = AppColors.TextTertiary)
                        if (device.isPaired) {
                            Badge("PAIRED", AppColors.Primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 0.3.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}
