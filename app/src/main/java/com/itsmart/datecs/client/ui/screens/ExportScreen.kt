package com.itsmart.datecs.client.ui.screens

import android.app.DatePickerDialog
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsmart.datecs.client.data.model.DeviceState
import com.itsmart.datecs.client.device.AnafDownloader
import com.itsmart.datecs.client.ui.components.NoticeCard
import com.itsmart.datecs.client.ui.components.OutlineButton
import com.itsmart.datecs.client.ui.components.PrimaryButton
import com.itsmart.datecs.client.ui.components.SoftCard
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

/** Perioada exportului XML: cele uzuale sunt un singur tap, intervalele se aleg doar la nevoie. */
private enum class Perioada(val label: String) { LunaTrecuta("Luna trecută"), LunaCurenta("Luna aceasta"), Date("Alte date"), Z("După nr. Z") }

@Composable
fun ExportScreen(viewModel: MainViewModel, deviceState: DeviceState) {
    val context = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("dd-MM-yy", Locale.getDefault()) }
    val showFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    val downloading by viewModel.anafDownloading.collectAsStateWithLifecycle()
    val progress by viewModel.anafDownloadProgress.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastAnafResult.collectAsStateWithLifecycle()
    val reportText by viewModel.reportPreviewText.collectAsStateWithLifecycle()
    val reportLoading by viewModel.reportPreviewLoading.collectAsStateWithLifecycle()
    val cloudResult by viewModel.cloudResult.collectAsStateWithLifecycle()
    val cloudUploading by viewModel.cloudUploading.collectAsStateWithLifecycle()
    val cloudLink by viewModel.cloudLink.collectAsStateWithLifecycle()

    val prevMonth = remember { Calendar.getInstance().apply { add(Calendar.MONTH, -1) } }
    val lunaTrecuta = remember { AnafDownloader.LUNI_RO[prevMonth.get(Calendar.MONTH)] }
    val lunaCurenta = remember { AnafDownloader.LUNI_RO[Calendar.getInstance().get(Calendar.MONTH)] }

    // "Alte date": implicit luna trecuta intreaga
    val calFrom = remember { (prevMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) } }
    val calTo = remember { (prevMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)) } }
    var dateFrom by remember { mutableStateOf(calFrom.time) }
    var dateTo by remember { mutableStateOf(calTo.time) }

    // "Dupa nr. Z": de la primul Z netrimis inca la ANAF pana la ultimul Z
    var zFrom by remember { mutableStateOf(deviceState.lastConnectionZrepNum.ifEmpty { "1" }) }
    var zTo by remember { mutableStateOf(deviceState.nZreport.ifEmpty { "1" }) }
    LaunchedEffect(deviceState.nZreport) { if (deviceState.nZreport.isNotEmpty()) zTo = deviceState.nZreport }
    LaunchedEffect(deviceState.lastConnectionZrepNum) { if (deviceState.lastConnectionZrepNum.isNotEmpty()) zFrom = deviceState.lastConnectionZrepNum }

    var perioada by remember { mutableStateOf(Perioada.LunaTrecuta) }
    var clientName by remember { mutableStateOf(viewModel.loadClientName().ifEmpty { viewModel.getClientNameFromHeader() }) }
    var basePath by remember { mutableStateOf(viewModel.loadBasePath()) }
    var showOptions by remember { mutableStateOf(false) }
    LaunchedEffect(deviceState.headers) {
        if (clientName.isEmpty()) { val h = deviceState.headers.firstOrNull { it.isNotEmpty() } ?: ""; if (h.isNotEmpty()) clientName = h }
    }
    val (headerClientName, _, _) = remember(deviceState.headers, deviceState.cifNumber) { viewModel.parseHeaderInfo() }
    val firma = clientName.ifEmpty { headerClientName }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            val docId = DocumentsContract.getTreeDocumentId(it)
            val path = if (docId.startsWith("primary:")) "/storage/emulated/0/" + docId.substringAfter("primary:") else docId
            basePath = path; viewModel.saveBasePath(path)
        }
    }

    fun pickDate(initial: Date, onPick: (Date) -> Unit) {
        val c = Calendar.getInstance().apply { time = initial }
        DatePickerDialog(context, { _, y, m, d -> onPick(Calendar.getInstance().apply { set(y, m, d) }.time) },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ce se exporta, in cuvinte (apare si pe buton)
    val descriere = when (perioada) {
        Perioada.LunaTrecuta -> "$lunaTrecuta ${prevMonth.get(Calendar.YEAR)}"
        Perioada.LunaCurenta -> "$lunaCurenta, pana azi"
        Perioada.Date -> "${showFmt.format(dateFrom)} – ${showFmt.format(dateTo)}"
        Perioada.Z -> if (zFrom == zTo) "raportul Z $zFrom" else "Z $zFrom – $zTo"
    }
    val intervalValid = when (perioada) {
        Perioada.Date -> !dateFrom.after(dateTo)
        Perioada.Z -> (zFrom.toIntOrNull() ?: 0) in 1..(zTo.toIntOrNull() ?: 0)
        else -> true
    }
    fun export() {
        when (perioada) {
            Perioada.LunaTrecuta -> viewModel.exportPreviousMonth(firma, basePath)
            Perioada.LunaCurenta -> {
                val first = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.time
                viewModel.downloadAnafFiles("${dateFmt.format(first)} 00:00:00", "${dateFmt.format(Date())} 23:59:59", firma, basePath)
            }
            Perioada.Date -> viewModel.downloadAnafFiles("${dateFmt.format(dateFrom)} 00:00:00", "${dateFmt.format(dateTo)} 23:59:59", firma, basePath)
            Perioada.Z -> viewModel.downloadAnafFiles(zFrom.trim(), zTo.trim(), firma, basePath)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ===== casa si unde ajung fisierele =====
        SoftCard(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.PrimaryLight), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PointOfSale, null, tint = AppColors.Primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(firma.ifEmpty { "Casa de marcat" }, fontSize = 15.sp, fontWeight = FontWeight.W800, color = AppColors.TextPrimary, maxLines = 1)
                    Text(listOf(deviceState.model, deviceState.serialNumber).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = AppColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Ultimul Z", deviceState.nZreport.ifEmpty { "—" }, Modifier.weight(1f))
                Stat("Trimis la ANAF", deviceState.lastConnectionZrepNum.ifEmpty { "—" }.let { if (it == "—") it else "Z $it" }, Modifier.weight(1f))
                Stat("Cloud", if (cloudLink.paired) "Asociat" else "Neasociat", Modifier.weight(1f),
                    tone = if (cloudLink.paired) AppColors.Success else AppColors.Warning)
            }
        }

        // ===== 1. perioada =====
        SoftCard(padding = 16.dp) {
            StepTitle("1", "Ce perioadă exporți?")
            Spacer(Modifier.height(12.dp))
            // 2 x 2: pe telefon textele raman intregi
            Perioada.values().toList().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { p ->
                        val sub = when (p) { Perioada.LunaTrecuta -> lunaTrecuta; Perioada.LunaCurenta -> lunaCurenta; Perioada.Date -> "interval"; Perioada.Z -> "rapoarte Z" }
                        Choice(p.label, sub, selected = perioada == p, modifier = Modifier.weight(1f)) { perioada = p }
                    }
                }
            }
            when (perioada) {
                Perioada.Date -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    DateField("De la", showFmt.format(dateFrom), Modifier.weight(1f)) { pickDate(dateFrom) { dateFrom = it } }
                    DateField("Până la", showFmt.format(dateTo), Modifier.weight(1f)) { pickDate(dateTo) { dateTo = it } }
                }
                Perioada.Z -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        ZField("De la Z", zFrom, Modifier.weight(1f)) { zFrom = it }
                        ZField("Până la Z", zTo, Modifier.weight(1f)) { zTo = it }
                    }
                    if (deviceState.nZreport.isNotEmpty())
                        Text("Pe casă există rapoartele Z 1 – ${deviceState.nZreport}.", fontSize = 12.sp, color = AppColors.TextSecondary,
                            modifier = Modifier.padding(top = 6.dp))
                }
                else -> {}
            }
            if (!intervalValid) Text("Intervalul nu este valid: începutul trebuie să fie înaintea sfârșitului.",
                fontSize = 12.sp, color = AppColors.Danger, fontWeight = FontWeight.W600, modifier = Modifier.padding(top = 6.dp))
        }

        // ===== 2. export =====
        SoftCard(padding = 16.dp) {
            StepTitle("2", "Exportă fișierele")
            Text(if (cloudLink.paired) "Fișierele XML se descarcă de pe casă și se trimit automat în cloud.itsmart.ro (și contabilului, dacă e setat)."
                 else "Fișierele XML se descarcă pe acest aparat. Ca să ajungă automat în cloud, asociați aplicația din Setări → Cloud.",
                fontSize = 13.sp, color = AppColors.TextSecondary, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            PrimaryButton(if (downloading) "Se descarcă…" else "Exportă $descriere", Icons.Filled.Download,
                enabled = !downloading && intervalValid) { export() }
            if (downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = AppColors.Primary, trackColor = AppColors.PrimaryLight)
                if (progress.isNotEmpty()) Text(progress, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            }

            lastResult?.let { result ->
                if (downloading) return@let
                Spacer(Modifier.height(12.dp))
                if (result.success && result.fileCount > 0) {
                    val cloudOk = cloudResult.startsWith("OK")
                    NoticeCard(Icons.Filled.CheckCircle, "${result.fileCount} fișiere exportate",
                        when {
                            cloudUploading -> "Se trimit în cloud.itsmart.ro…"
                            cloudOk -> "Trimise în cloud.itsmart.ro"
                            cloudResult.isNotEmpty() -> "Cloud: $cloudResult"
                            else -> "Salvate pe aparat"
                        }, AppColors.Success, AppColors.SuccessBg)
                    Spacer(Modifier.height(10.dp))
                    if (cloudResult.isNotEmpty() && !cloudOk && !cloudUploading && cloudLink.paired) {
                        OutlineButton("Trimite din nou în cloud", Icons.Filled.CloudUpload) { viewModel.uploadToCloud() }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlineButton("Trimite pe email (ZIP)", Icons.Filled.Email) {
                        val zipUri = viewModel.createZipFromAnafFiles()
                        if (zipUri != null) {
                            val intent = viewModel.createXmlEmailIntent(zipUri, firma, descriere, deviceState.serialNumber, deviceState.fiscalNumber, result.fileCount)
                            context.startActivity(Intent.createChooser(intent, "Trimite export XML..."))
                        }
                    }
                } else if (result.success) {
                    NoticeCard(Icons.Filled.Info, "Niciun fișier în perioada aleasă", "Alegeți altă perioadă sau verificați rapoartele Z de pe casă.",
                        AppColors.Info, AppColors.InfoBg)
                } else {
                    val gol = result.error.contains("nu s-au gasit", ignoreCase = true)
                    NoticeCard(Icons.Filled.ErrorOutline, if (gol) "Niciun fișier în perioada aleasă" else "Exportul nu a reușit",
                        if (gol) "Pentru $descriere casa nu are fișiere ANAF. Alegeți altă perioadă (de exemplu luna trecută)."
                        else result.error, if (gol) AppColors.Warning else AppColors.Danger, if (gol) AppColors.WarningBg else AppColors.DangerBg)
                }
            }

            // optiuni rar folosite: numele de pe fisiere si folderul de salvare
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showOptions = !showOptions }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tune, null, tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Opțiuni: nume firmă, folder salvare", fontSize = 13.sp, fontWeight = FontWeight.W600, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                Icon(if (showOptions) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = AppColors.TextSecondary)
            }
            if (showOptions) {
                OutlinedTextField(value = clientName, onValueChange = { clientName = it }, label = { Text("Numele firmei pe fișiere") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Folder salvare", fontSize = 12.sp, color = AppColors.TextSecondary)
                        Text(basePath.ifEmpty { "folderul aplicației (implicit)" }, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 2)
                    }
                    TextButton(onClick = { folderPicker.launch(null) }) { Text("Schimbă", fontWeight = FontWeight.W700) }
                }
            }
        }

        // ===== raport memorie fiscala (separat: se tipareste pe casa) =====
        FiscalReportCard(viewModel, deviceState, firma, reportText, reportLoading, dateFrom, dateTo, showFmt, dateFmt, ::pickDate)
    }
}

@Composable
private fun FiscalReportCard(viewModel: MainViewModel, deviceState: DeviceState, firma: String, reportText: String, reportLoading: Boolean,
                             initFrom: Date, initTo: Date, showFmt: SimpleDateFormat, dateFmt: SimpleDateFormat,
                             pickDate: (Date, (Date) -> Unit) -> Unit) {
    val busy by viewModel.fmBusy.collectAsStateWithLifecycle()
    val result by viewModel.fmResult.collectAsStateWithLifecycle()
    val accEmail by viewModel.accountantEmail.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadAccountant() }
    var open by remember { mutableStateOf(false) }
    var byZ by remember { mutableStateOf(true) }
    var detailed by remember { mutableStateOf(false) }
    var zFrom by remember { mutableStateOf("1") }
    var zTo by remember { mutableStateOf(deviceState.nZreport.ifEmpty { "1" }) }
    var from by remember { mutableStateOf(initFrom) }
    var to by remember { mutableStateOf(initTo) }
    LaunchedEffect(deviceState.nZreport) { if (deviceState.nZreport.isNotEmpty()) zTo = deviceState.nZreport }

    SoftCard(padding = 16.dp) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Print, null, tint = AppColors.Warning, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Raport memorie fiscală", fontSize = 16.sp, fontWeight = FontWeight.W800, color = AppColors.TextPrimary)
                Text("Se tipărește pe casă sau se vizualizează aici", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = AppColors.TextSecondary)
        }
        if (!open) return@SoftCard
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("După nr. Z", null, byZ, Modifier.weight(1f)) { byZ = true }
            Choice("După dată", null, !byZ, Modifier.weight(1f)) { byZ = false }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (byZ) {
                ZField("De la Z", zFrom, Modifier.weight(1f)) { zFrom = it }
                ZField("Până la Z", zTo, Modifier.weight(1f)) { zTo = it }
            } else {
                DateField("De la", showFmt.format(from), Modifier.weight(1f)) { pickDate(from) { from = it } }
                DateField("Până la", showFmt.format(to), Modifier.weight(1f)) { pickDate(to) { to = it } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Sumar", null, !detailed, Modifier.weight(1f)) { detailed = false }
            Choice("Detaliat", null, detailed, Modifier.weight(1f)) { detailed = true }
        }
        Spacer(Modifier.height(12.dp))
        val idle = busy.isEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Tipărește", Icons.Filled.Print, Modifier.weight(1f), enabled = idle, color = AppColors.Warning) {
                if (byZ) viewModel.executeFiscalReportByZ(zFrom, zTo, detailed)
                else viewModel.executeFiscalReportByDate(dateFmt.format(from), dateFmt.format(to), detailed)
            }
            OutlineButton("Vizualizează", Icons.Filled.Visibility, Modifier.weight(1f),
                enabled = byZ && idle) { viewModel.previewFiscalReportByZ(zFrom, zTo, detailed) }
        }
        Spacer(Modifier.height(8.dp))
        OutlineButton("Trimite pe email", Icons.Filled.Email, enabled = byZ && idle) { viewModel.sendFmReportByEmail(zFrom, zTo, detailed) }
        Text(if (!byZ) "Vizualizarea și emailul sunt disponibile doar după nr. Z."
             else if (accEmail.isNotBlank()) "Emailul pleacă la $accEmail, cu raportul atașat (PDF și TXT)."
             else "Setați adresa de email în Setări → Contabilitate ca să primiți raportul.",
            fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))

        if (!idle) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppColors.WarningBg).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = AppColors.Warning, strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(busy, fontSize = 15.sp, fontWeight = FontWeight.W800, color = AppColors.TextPrimary)
                    Text("Nu deconectați casa până la final.", fontSize = 12.5.sp, color = AppColors.TextSecondary)
                }
            }
        } else result?.let { (ok, msg) ->
            Spacer(Modifier.height(12.dp))
            NoticeCard(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, if (ok) "Gata" else "Nu a reușit", msg,
                if (ok) AppColors.Success else AppColors.Danger, if (ok) AppColors.SuccessBg else AppColors.DangerBg)
        }
        if (reportText.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(reportText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary, lineHeight = 13.sp,
                modifier = Modifier.fillMaxWidth().background(AppColors.Background, RoundedCornerShape(10.dp)).padding(10.dp))
        }
    }
}

@Composable
private fun StepTitle(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(AppColors.Primary), contentAlignment = Alignment.Center) {
            Text(n, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W800)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.W800, color = AppColors.TextPrimary)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, tone: Color = AppColors.TextPrimary) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(AppColors.Background).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.W800, color = tone, maxLines = 1)
    }
}

/** Optiune selectabila (card mic): albastru plin cand e aleasa. */
@Composable
private fun Choice(title: String, sub: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(if (selected) AppColors.Primary else Color.White)
        .border(1.5.dp, if (selected) AppColors.Primary else AppColors.Border, shape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = if (sub != null) 10.dp else 12.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.W700, color = if (selected) Color.White else AppColors.TextPrimary, maxLines = 1)
        if (sub != null) Text(sub, fontSize = 12.sp, color = if (selected) Color.White.copy(alpha = 0.85f) else AppColors.TextSecondary, maxLines = 1)
    }
}

/** Camp de data care deschide calendarul (nu se tasteaza). */
@Composable
private fun DateField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.clip(shape).border(1.dp, AppColors.Border, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = AppColors.TextSecondary)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.W700, color = AppColors.TextPrimary)
        }
        Icon(Icons.Filled.CalendarMonth, null, tint = AppColors.Primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ZField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(5)) }, label = { Text(label) },
        modifier = modifier, singleLine = true, shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, fontWeight = FontWeight.W700))
}
