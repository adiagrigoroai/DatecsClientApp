package com.itsmart.datecs.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsmart.datecs.client.R
import com.itsmart.datecs.client.data.model.ConnectionType
import com.itsmart.datecs.client.device.ScannedDevice
import com.itsmart.datecs.client.ui.components.*
import com.itsmart.datecs.client.ui.navigation.Screen
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel

/** Aparate: fara aparat — conectare (LAN / USB / Bluetooth); cu aparat — detaliile lui si actiunile principale. */
@Composable
fun DevicesScreen(viewModel: MainViewModel, connected: Boolean, wide: Boolean, onOpen: (Screen) -> Unit) {
    val scanned by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val internal = scanned.filter { it.type == ConnectionType.INTERNAL }

    // Pe BlueCash 50/500: conectare automata (o data) la modulul fiscal intern
    var autoInternalTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(internal.isNotEmpty(), connected, isConnecting) {
        val dev = internal.firstOrNull()
        if (dev != null && !connected && !isConnecting && !autoInternalTried) {
            autoInternalTried = true
            viewModel.connectDevice(dev.address, dev.name, ConnectionType.INTERNAL)
        }
    }
    // scanare USB / Bluetooth la 3 secunde cat timp ecranul e deschis
    LaunchedEffect(Unit) {
        while (true) {
            try { viewModel.scanDevices() } catch (_: Exception) {}
            kotlinx.coroutines.delay(3000)
        }
    }

    val scroll = rememberScrollState()
    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (connected) ConnectedPanel(viewModel, onOpen) else ConnectPanel(viewModel, connected, isConnecting, scanned)
            }
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LanHistory(viewModel, connected, isConnecting)
                MonitorCard()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (connected) ConnectedPanel(viewModel, onOpen) else {
                ConnectPanel(viewModel, connected, isConnecting, scanned)
                LanHistory(viewModel, connected, isConnecting)
            }
            MonitorCard()
        }
    }
}

private enum class Way(val label: String, val icon: ImageVector) {
    LAN("LAN / Wi-Fi", Icons.Filled.Wifi), USB("USB", Icons.Filled.Usb), BT("Bluetooth", Icons.Filled.Bluetooth)
}

@Composable
private fun ConnectPanel(viewModel: MainViewModel, connected: Boolean, isConnecting: Boolean, scanned: List<ScannedDevice>) {
    var way by rememberSaveable { mutableStateOf(Way.LAN) }
    var lanIp by remember { mutableStateOf(viewModel.loadLanIp()) }
    var lanPort by remember { mutableStateOf(viewModel.loadLanPort()) }

    SoftCard(padding = 14.dp) {
        // selectorul de conexiune
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFEFF2FA)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Way.entries.forEach { w ->
                val sel = w == way
                Column(Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                    .background(if (sel) Brush.verticalGradient(listOf(Color(0xFF2563EB), Color(0xFF1240D8))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { way = w }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(w.icon, null, tint = if (sel) Color.White else AppColors.TextPrimary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(w.label, fontSize = 12.sp, fontWeight = FontWeight.W600, color = if (sel) Color.White else AppColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (way) {
            Way.LAN -> {
                Label("IP aparat (din reteaua locala)")
                Field(lanIp, { lanIp = it.trim() }, Icons.Filled.Lan, "192.168.1.100", KeyboardType.Uri)
                Spacer(Modifier.height(12.dp))
                Label("Port")
                Field(lanPort, { lanPort = it.filter(Char::isDigit).take(5) }, Icons.Filled.Layers, "3999", KeyboardType.Number)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(if (isConnecting) "Se conecteaza..." else "Conecteaza", Icons.Filled.Link,
                    enabled = !connected && !isConnecting && lanIp.isNotBlank()) {
                    viewModel.saveLanConfig(lanIp, lanPort)
                    viewModel.connectDevice("$lanIp:$lanPort", "LAN $lanIp", ConnectionType.LAN)
                }
                Spacer(Modifier.height(14.dp))
                NoticeCard(Icons.Filled.Info, "Unde gasesc IP-ul?",
                    "Casa de marcat: Setari → Comunicatii → LAN.\n" +
                        "Imprimanta fiscala FP-700 / FP-950: apasati Back, apoi Enter; se tipareste un Diagnostic cu adresa IP.",
                    AppColors.Info, AppColors.InfoBg)
            }
            Way.USB -> DeviceList(scanned.filter { it.type == ConnectionType.USB || it.type == ConnectionType.INTERNAL },
                "Niciun aparat USB detectat. Conectati casa cu cablul USB (OTG) si apasati Cauta din nou.",
                connected, isConnecting, viewModel) {
                OutlineButton("Cauta din nou", Icons.Filled.Refresh, enabled = !isConnecting) { viewModel.scanDevices() }
            }
            Way.BT -> DeviceList(scanned.filter { it.type == ConnectionType.BLUETOOTH },
                "Niciun aparat Bluetooth imperecheat. Imperecheati casa din Setarile telefonului → Bluetooth.",
                connected, isConnecting, viewModel) {}
        }
    }
}

@Composable
private fun DeviceList(devices: List<ScannedDevice>, empty: String, connected: Boolean, isConnecting: Boolean,
                       viewModel: MainViewModel, footer: @Composable () -> Unit) {
    if (devices.isEmpty()) Text(empty, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
    devices.forEach { d ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, AppColors.Border, RoundedCornerShape(14.dp))
            .clickable(enabled = !connected && !isConnecting) { viewModel.connectDevice(d.address, d.name, d.type) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(when (d.type) { ConnectionType.BLUETOOTH -> Icons.Filled.Bluetooth; ConnectionType.INTERNAL -> Icons.Filled.Memory; else -> Icons.Filled.Usb },
                null, tint = AppColors.Primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.W700, color = AppColors.TextPrimary)
                Text(if (d.type == ConnectionType.BLUETOOTH) d.address else d.info, fontSize = 11.5.sp, color = AppColors.TextSecondary, maxLines = 1)
            }
            Text("Conecteaza", fontSize = 13.sp, fontWeight = FontWeight.W700, color = AppColors.Primary)
        }
    }
    footer()
}

@Composable
private fun LanHistory(viewModel: MainViewModel, connected: Boolean, isConnecting: Boolean) {
    val history by viewModel.lanHistory.collectAsStateWithLifecycle()
    val connectedAddr by viewModel.connectedAddress.collectAsStateWithLifecycle()
    if (history.isEmpty()) return
    SoftCard(padding = 14.dp) {
        Text("Conexiuni recente", fontSize = 15.sp, fontWeight = FontWeight.W800, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 8.dp))
        history.forEach { e ->
            val here = connected && connectedAddr == e.address
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(if (here) AppColors.SuccessBg else Color(0xFFF6F7FB))
                .clickable(enabled = !connected && !isConnecting) {
                    val parts = e.address.split(":", limit = 2)
                    viewModel.saveLanConfig(parts[0], parts.getOrElse(1) { "3999" })
                    viewModel.connectDevice(e.address, e.name, ConnectionType.LAN)
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PointOfSale, null, tint = if (here) AppColors.Success else AppColors.Primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontSize = 13.5.sp, fontWeight = FontWeight.W700, color = AppColors.TextPrimary, maxLines = 1)
                    if (e.info.isNotBlank()) Text(e.info, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1)
                }
                Text(e.address, fontSize = 11.sp, color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun ConnectedPanel(viewModel: MainViewModel, onOpen: (Screen) -> Unit) {
    val ds by viewModel.deviceState.collectAsStateWithLifecycle()
    val type by viewModel.connectedType.collectAsStateWithLifecycle()
    val addr by viewModel.connectedAddress.collectAsStateWithLifecycle()
    val link by viewModel.cloudLink.collectAsStateWithLifecycle()
    val cloudResult by viewModel.cloudResult.collectAsStateWithLifecycle()
    val autoUpload by viewModel.autoUpload.collectAsStateWithLifecycle()
    val (client, _, _) = remember(ds.headers, ds.cifNumber) { viewModel.parseHeaderInfo() }

    SoftCard(padding = 18.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(170.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFD9F7E6), Color.White))),
                contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.hero_cloud), null, modifier = Modifier.size(120.dp))
            }
            Box(Modifier.align(Alignment.BottomCenter).offset(x = 48.dp).size(40.dp).clip(CircleShape).background(Color.White).padding(3.dp)
                .clip(CircleShape).background(AppColors.Success), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Text("Aparat conectat", fontSize = 24.sp, fontWeight = FontWeight.W800, color = AppColors.TextPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp))
        InfoLine(Icons.Filled.Person, "Client:", client.ifBlank { "—" })
        InfoLine(Icons.Filled.PointOfSale, "Model:", ds.devName.ifBlank { ds.model.ifBlank { "—" } })
        InfoLine(Icons.Filled.Tag, "Serie (NUI):", ds.serialNumber.ifBlank { "se citeste..." })
        InfoLine(Icons.Filled.Wifi, "Conexiune:", when (type) {
            ConnectionType.LAN -> "LAN ($addr)"; ConnectionType.USB -> "USB"; ConnectionType.BLUETOOTH -> "Bluetooth"
            ConnectionType.INTERNAL -> "Modul fiscal intern"
        })
    }
    when {
        !link.paired -> NoticeCard(Icons.Filled.CloudOff, "Neasociat cu cloud.itsmart.ro", "Fisierele XML nu se trimit in cloud. Asociati din Setari.",
            AppColors.Warning, AppColors.WarningBg, onClick = { onOpen(Screen.Settings) })
        cloudResult.startsWith("EROARE") -> NoticeCard(Icons.Filled.CloudOff, "Transfer XML esuat", cloudResult.removePrefix("EROARE: "),
            AppColors.Danger, AppColors.DangerBg)
        else -> NoticeCard(Icons.Filled.CloudUpload, if (autoUpload) "Transfer XML activ" else "Transfer XML oprit",
            cloudResult.ifBlank { link.companyName }, AppColors.Success, AppColors.SuccessBg) {
            if (autoUpload) Icon(Icons.Filled.CheckCircle, null, tint = AppColors.Success, modifier = Modifier.size(26.dp))
        }
    }
    OutlineButton("Exporta acum XML", Icons.Filled.Upload) { onOpen(Screen.Export) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = { viewModel.refreshStatus() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Filled.MonitorHeart, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test conexiune", fontWeight = FontWeight.W700, color = AppColors.Primary)
        }
        FilledTonalButton(onClick = { onOpen(Screen.Maintenance) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Filled.Handyman, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mentenanta", fontWeight = FontWeight.W700, color = AppColors.Primary)
        }
    }
    TextButton(onClick = { viewModel.disconnectDevice() }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.LinkOff, null, tint = AppColors.Danger, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text("Deconecteaza aparatul", color = AppColors.Danger, fontWeight = FontWeight.W700)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.W700, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, icon: ImageVector, placeholder: String, kb: KeyboardType) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(icon, null, tint = AppColors.TextSecondary) }, placeholder = { Text(placeholder) },
        shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = kb),
        textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color(0xFFF6F7FB), focusedContainerColor = Color.White,
            unfocusedBorderColor = AppColors.Border))
}
