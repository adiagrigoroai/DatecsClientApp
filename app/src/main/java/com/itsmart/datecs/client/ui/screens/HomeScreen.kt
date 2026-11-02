package com.itsmart.datecs.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsmart.datecs.client.R
import com.itsmart.datecs.client.ui.components.HeaderBrush
import com.itsmart.datecs.client.ui.components.NoticeCard
import com.itsmart.datecs.client.ui.components.TileCard
import com.itsmart.datecs.client.ui.navigation.Screen
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel

/** Ecranul de start: imaginea principala, cele 4 placi, starea aplicatiei (transfer automat, asociere cloud). */
@Composable
fun HomeScreen(viewModel: MainViewModel, connected: Boolean, wide: Boolean, onOpen: (Screen) -> Unit) {
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val autoUpload by viewModel.autoUpload.collectAsStateWithLifecycle()
    val link by viewModel.cloudLink.collectAsStateWithLifecycle()
    val pendingRevizie by viewModel.pendingRevizie.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // imaginea principala, pe albastru (continua antetul)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(HeaderBrush).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.casa_datecs), null, modifier = Modifier.width(if (wide) 220.dp else 150.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Transfer automat XML si mentenanta pentru aparatele tale fiscale.", color = Color.White,
                    fontSize = if (wide) 22.sp else 17.sp, fontWeight = FontWeight.W700, lineHeight = if (wide) 28.sp else 22.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (connected) Color(0xFF34D399) else Color(0xFFFCA5A5)))
                    Spacer(Modifier.width(6.dp))
                    Text(if (connected) "${deviceState.devName.ifBlank { "Aparat" }} · ${deviceState.serialNumber}" else "Niciun aparat conectat",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.W600, maxLines = 1)
                }
            }
        }

        if (pendingRevizie != null) {
            NoticeCard(Icons.Filled.Build, "Revizie programata", "Din CRM a venit o cerere de revizie. Deschideti Mentenanta AMEF.",
                AppColors.Warning, AppColors.WarningBg, onClick = { onOpen(if (connected) Screen.Maintenance else Screen.Devices) })
        }

        // cele 4 placi: un rand pe ecran lat (tableta / POS), 2 x 2 pe telefon
        val tiles = listOf(
            Triple(Icons.Filled.Wifi, "Conecteaza\naparatul", Screen.Devices),
            Triple(Icons.Filled.CloudUpload, "Fisiere\nXML", Screen.Files),
            Triple(Icons.Filled.Handyman, "Mentenanta\nAMEF", Screen.Maintenance),
            Triple(Icons.Filled.BarChart, "Rapoarte\n& Istoric", Screen.Export),
        )
        val open = { s: Screen -> onOpen(if (!connected && (s == Screen.Maintenance || s == Screen.Export)) Screen.Devices else s) }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                tiles.forEach { (icon, title, screen) -> TileCard(icon, title, Modifier.weight(1f)) { open(screen) } }
            }
        } else {
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { (icon, title, screen) -> TileCard(icon, title, Modifier.weight(1f)) { open(screen) } }
                }
            }
        }

        if (link.paired) {
            NoticeCard(Icons.Filled.CheckCircle, "Aplicatia ruleaza",
                if (autoUpload) "Transfer automat activ · ${link.companyName}" else "Transfer automat oprit · ${link.companyName}",
                if (autoUpload) AppColors.Success else AppColors.TextTertiary, if (autoUpload) AppColors.SuccessBg else Color(0xFFF1F3F7)) {
                Switch(checked = autoUpload, onCheckedChange = { viewModel.setAutoUpload(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Success, checkedThumbColor = Color.White))
            }
        } else {
            NoticeCard(Icons.Filled.CloudOff, "Neasociat cu cloud.itsmart.ro",
                "Fisierele XML nu se trimit in cloud. Asociati aplicatia din Setari.", AppColors.Warning, AppColors.WarningBg,
                onClick = { onOpen(Screen.Settings) })
        }
        Text("cloud.itsmart.ro", fontSize = 11.sp, color = AppColors.TextTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
