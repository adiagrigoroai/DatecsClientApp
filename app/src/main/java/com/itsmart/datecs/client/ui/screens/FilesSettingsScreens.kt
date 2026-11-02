package com.itsmart.datecs.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsmart.datecs.client.BuildConfig
import com.itsmart.datecs.client.service.CloudUploadService
import com.itsmart.datecs.client.ui.components.*
import com.itsmart.datecs.client.ui.navigation.Screen
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Locale

private val PERIODS = listOf(7 to "Ultimele 7 zile", 30 to "Ultimele 30 zile", 90 to "Ultimele 90 zile", 0 to "Toate fisierele")

/** Fisierele XML ale firmei din cloud.itsmart.ro (dovada ca transferul a ajuns). */
@Composable
fun FilesScreen(viewModel: MainViewModel, wide: Boolean, onOpen: (Screen) -> Unit) {
    val files by viewModel.cloudFiles.collectAsStateWithLifecycle()
    val state by viewModel.cloudFilesState.collectAsStateWithLifecycle()
    val link by viewModel.cloudLink.collectAsStateWithLifecycle()
    val autoUpload by viewModel.autoUpload.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf(30) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(link.paired) { if (link.paired) viewModel.loadCloudFiles() }

    val fmtIn = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val fmtOut = remember { SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.US) }
    val since = if (period == 0) 0L else System.currentTimeMillis() - period * 86_400_000L
    val shown = files.mapNotNull { f ->
        val t = try { fmtIn.parse(f.uploadedAt.take(19))?.time ?: 0L } catch (_: Exception) { 0L }
        if (t >= since) f to t else null
    }

    LazyVerticalGrid(columns = GridCells.Fixed(if (wide) 2 else 1), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE8EEFC)).clickable { menu = true }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarMonth, null, tint = AppColors.Primary)
                        Spacer(Modifier.width(10.dp))
                        Text(PERIODS.first { it.first == period }.second, fontSize = 15.sp, fontWeight = FontWeight.W700, color = AppColors.Primary,
                            modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = AppColors.Primary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        PERIODS.forEach { (d, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { period = d; menu = false }) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { viewModel.loadCloudFiles() }, enabled = link.paired && state != "loading") {
                    Icon(Icons.Filled.Refresh, "Reincarca", tint = AppColors.Primary)
                }
            }
        }
        when {
            !link.paired -> item(span = { GridItemSpan(maxLineSpan) }) {
                NoticeCard(Icons.Filled.CloudOff, "Neasociat cu cloud.itsmart.ro", "Asociati aplicatia din Setari ca sa vedeti fisierele trimise.",
                    AppColors.Warning, AppColors.WarningBg, onClick = { onOpen(Screen.Settings) })
            }
            state == "loading" && files.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.isNotBlank() && state != "loading" -> item(span = { GridItemSpan(maxLineSpan) }) {
                NoticeCard(Icons.Filled.ErrorOutline, "Lista nu s-a incarcat", state, AppColors.Danger, AppColors.DangerBg)
            }
            shown.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Niciun fisier in perioada aleasa.", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(20.dp))
            }
        }
        items(shown, key = { it.first.id }) { (f, t) -> FileRow(f, if (t > 0) fmtOut.format(java.util.Date(t)) else f.uploadedAt) }
        if (link.paired) item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(4.dp))
            NoticeCard(Icons.Filled.Sync, if (autoUpload) "Transfer automat activ" else "Transfer automat oprit",
                "Fisierele XML descarcate de pe aparat se trimit automat in cloud.", AppColors.Primary, Color(0xFFE8EEFC)) {
                Switch(checked = autoUpload, onCheckedChange = { viewModel.setAutoUpload(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Success, checkedThumbColor = Color.White))
            }
        }
    }
}

@Composable
private fun FileRow(f: CloudUploadService.CloudFile, date: String) {
    SoftCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2563EB)), contentAlignment = Alignment.Center) {
                Text(f.type.uppercase().take(3).ifBlank { "XML" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.W800)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.W700, color = AppColors.TextPrimary, maxLines = 1)
                Text("$date • ${(f.size + 1023) / 1024} KB" + if (f.amef.isNotBlank()) " • ${f.amef}" else "",
                    fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1)
            }
            Icon(Icons.Filled.CheckCircle, "In cloud", tint = AppColors.Success, modifier = Modifier.size(26.dp))
        }
    }
}

/** Setari: contul, asocierea cu cloud.itsmart.ro, transferul automat, mesaje, versiunea. */
@Composable
fun SettingsScreen(viewModel: MainViewModel, wide: Boolean, onOpen: (Screen) -> Unit) {
    val username by viewModel.username.collectAsStateWithLifecycle()
    val link by viewModel.cloudLink.collectAsStateWithLifecycle()
    val pairing by viewModel.cloudPairing.collectAsStateWithLifecycle()
    val pairMessage by viewModel.cloudPairMessage.collectAsStateWithLifecycle()
    val autoUpload by viewModel.autoUpload.collectAsStateWithLifecycle()
    val unread by viewModel.unreadMessageCount.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var confirmUnpair by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    if (confirmUnpair) AlertDialog(onDismissRequest = { confirmUnpair = false }, title = { Text("Stergeti asocierea?") },
        text = { Text("Fisierele XML nu se vor mai trimite in cloud.itsmart.ro pana nu introduceti un cod nou de asociere.") },
        confirmButton = { TextButton(onClick = { confirmUnpair = false; viewModel.unpairCloud() }) { Text("Sterge asocierea", color = AppColors.Danger) } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Renunta") } })
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Iesiti din cont?") },
        text = { Text("Va trebui sa va autentificati din nou pe acest aparat.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout() }) { Text("Iesire", color = AppColors.Danger) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Renunta") } })

    val account: @Composable ColumnScope.() -> Unit = {
        Section("Cont")
        SoftCard {
            InfoLine(Icons.Filled.Person, "Utilizator:", username.ifBlank { "—" })
            InfoLine(Icons.Filled.Info, "Versiune:", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Spacer(Modifier.height(8.dp))
            OutlineButton("Mesaje" + if (unread > 0) " ($unread noi)" else "", Icons.Filled.Notifications) { onOpen(Screen.Messages) }
            Spacer(Modifier.height(8.dp))
            OutlineButton("Iesire din cont", Icons.Filled.Logout, color = AppColors.Danger) { confirmLogout = true }
        }
    }
    val cloud: @Composable ColumnScope.() -> Unit = {
        Section("Cloud itsmart.ro")
        SoftCard {
            if (link.paired) {
                NoticeCard(Icons.Filled.CloudDone, "Asociat", "${link.companyName}" + if (link.cui.isNotBlank()) " · CUI ${link.cui}" else "",
                    AppColors.Success, AppColors.SuccessBg)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Transfer automat", fontSize = 15.sp, fontWeight = FontWeight.W700, color = AppColors.TextPrimary)
                        Text("Fisierele XML descarcate se trimit singure in cloud", fontSize = 12.sp, color = AppColors.TextSecondary)
                    }
                    Switch(checked = autoUpload, onCheckedChange = { viewModel.setAutoUpload(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Success, checkedThumbColor = Color.White))
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { confirmUnpair = true }) { Text("Sterge asocierea", color = AppColors.Danger, fontWeight = FontWeight.W700) }
            } else {
                Text("Generati un cod in cloud.itsmart.ro (Aparate → Aplicatii asociate → Genereaza cod de asociere) si introduceti-l aici. " +
                    "Codul este valabil 24 de ore.", fontSize = 13.sp, color = AppColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase().take(9) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cod asociere (ex. ABCD-2345)") }, shape = RoundedCornerShape(14.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 18.sp))
                Spacer(Modifier.height(10.dp))
                PrimaryButton(if (pairing) "Asociere..." else "Asociaza", Icons.Filled.Link,
                    enabled = !pairing && code.count { it.isLetterOrDigit() } == 8) { viewModel.pairCloud(code) }
                if (pairMessage.isNotBlank()) Text(pairMessage, fontSize = 12.sp, color = AppColors.Danger, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = cloud)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = account)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cloud(); account()
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.W800, color = AppColors.TextSecondary, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}
