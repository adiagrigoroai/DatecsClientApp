package com.itsmart.datecs.client.ui.screens

import android.app.DatePickerDialog
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsmart.datecs.client.data.model.DeviceState
import com.itsmart.datecs.client.device.AnafDownloader
import com.itsmart.datecs.client.ui.components.ActionButton
import com.itsmart.datecs.client.ui.components.SectionCard
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(viewModel: MainViewModel, deviceState: DeviceState) {
    val context = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("dd-MM-yy", Locale.getDefault()) }

    val downloading by viewModel.anafDownloading.collectAsStateWithLifecycle()
    val progress by viewModel.anafDownloadProgress.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastAnafResult.collectAsStateWithLifecycle()
    val reportText by viewModel.reportPreviewText.collectAsStateWithLifecycle()
    val reportLoading by viewModel.reportPreviewLoading.collectAsStateWithLifecycle()
    val cloudResult by viewModel.cloudResult.collectAsStateWithLifecycle()
    val cloudUploading by viewModel.cloudUploading.collectAsStateWithLifecycle()
    val cloudLink by viewModel.cloudLink.collectAsStateWithLifecycle()
    val cloudPairing by viewModel.cloudPairing.collectAsStateWithLifecycle()
    val cloudPairMessage by viewModel.cloudPairMessage.collectAsStateWithLifecycle()
    var pairCode by remember { mutableStateOf("") }
    var confirmUnpair by remember { mutableStateOf(false) }

    // Date range defaults: prima zi luna trecuta -> ultima zi luna trecuta
    val calFrom = remember {
        Calendar.getInstance().apply { add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 1) }
    }
    val calTo = remember {
        Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.DAY_OF_MONTH, -1) }
    }
    var dateFrom by remember { mutableStateOf(dateFmt.format(calFrom.time)) }
    var dateTo by remember { mutableStateOf(dateFmt.format(calTo.time)) }

    // Z range defaults
    val lastAnafZ = deviceState.lastConnectionZrepNum.ifEmpty { "1" }
    var zFrom by remember { mutableStateOf(lastAnafZ) }
    var zTo by remember { mutableStateOf(deviceState.nZreport.ifEmpty { "1" }) }

    var clientName by remember { mutableStateOf(viewModel.loadClientName().ifEmpty { viewModel.getClientNameFromHeader() }) }
    var basePath by remember { mutableStateOf(viewModel.loadBasePath()) }
    var reportDetailed by remember { mutableStateOf(false) }
    var fmRangeByZ by remember { mutableStateOf(true) }
    var fmZFrom by remember { mutableStateOf("1") }
    var fmZTo by remember { mutableStateOf(deviceState.nZreport.ifEmpty { "1" }) }

    LaunchedEffect(deviceState.nZreport) {
        if (deviceState.nZreport.isNotEmpty()) { zTo = deviceState.nZreport; fmZTo = deviceState.nZreport }
    }
    LaunchedEffect(deviceState.lastConnectionZrepNum) {
        if (deviceState.lastConnectionZrepNum.isNotEmpty()) zFrom = deviceState.lastConnectionZrepNum
    }
    LaunchedEffect(deviceState.headers) {
        if (clientName.isEmpty()) { val h = deviceState.headers.firstOrNull { it.isNotEmpty() } ?: ""; if (h.isNotEmpty()) clientName = h }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            val docId = DocumentsContract.getTreeDocumentId(it)
            val path = if (docId.startsWith("primary:")) "/storage/emulated/0/" + docId.substringAfter("primary:") else docId
            basePath = path; viewModel.saveBasePath(path)
        }
    }

    val (headerClientName, _, _) = remember(deviceState.headers, deviceState.cifNumber) { viewModel.parseHeaderInfo() }
    val lunaText = remember { AnafDownloader.LUNI_RO[Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.get(Calendar.MONTH)] }

    if (confirmUnpair) {
        AlertDialog(onDismissRequest = { confirmUnpair = false },
            title = { Text("Stergeti asocierea?") },
            text = { Text("Fisierele XML nu se vor mai trimite in cloud.itsmart.ro pana nu introduceti un cod nou de asociere.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; viewModel.unpairCloud() }) { Text("Sterge asocierea", color = AppColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Renunta") } })
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {

        // === CARD 0: ASOCIERE CLOUD.ITSMART.RO ===
        SectionCard(title = "Asociere cloud.itsmart.ro", accent = if (cloudLink.paired) AppColors.Success else AppColors.Warning) {
            if (cloudLink.paired) {
                Text("Asociat cu ${cloudLink.companyName.ifBlank { "firma" }}" + if (cloudLink.cui.isNotBlank()) " (CUI ${cloudLink.cui})" else "",
                    fontSize = 12.sp, fontWeight = FontWeight.W700, color = AppColors.Success,
                    modifier = Modifier.fillMaxWidth().background(AppColors.SuccessBg, RoundedCornerShape(8.dp)).padding(10.dp))
                Text("Fisierele XML descarcate se trimit automat in cloud.", fontSize = 10.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                ActionButton("Sterge asocierea", onClick = { confirmUnpair = true }, small = true, outline = true)
            } else {
                Text("Pentru trimiterea fisierelor XML, generati un cod de asociere in cloud.itsmart.ro " +
                    "(Aparate > Aplicatii asociate > Genereaza cod de asociere) si introduceti-l aici. Codul este valabil 24 de ore.",
                    fontSize = 10.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pairCode, onValueChange = { pairCode = it.uppercase().take(9) },
                        label = { Text("Cod asociere (ex. ABCD-2345)", fontSize = 10.sp) }, modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp))
                    ActionButton(if (cloudPairing) "Asociere..." else "Asociaza", onClick = { viewModel.pairCloud(pairCode) },
                        enabled = !cloudPairing && pairCode.count { it.isLetterOrDigit() } == 8, color = AppColors.Success)
                }
            }
            if (cloudPairMessage.isNotEmpty() && !cloudLink.paired) {
                Text(cloudPairMessage, fontSize = 11.sp, fontWeight = FontWeight.W600, color = AppColors.Danger, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // === CARD 1: EXPORT XML DUPA NR. Z ===
        SectionCard(title = "Export XML dupa Nr. Z", accent = AppColors.Primary) {
            OutlinedTextField(value = clientName, onValueChange = { clientName = it },
                label = { Text("Nume Client", fontSize = 10.sp) }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(value = basePath, onValueChange = { basePath = it }, label = { Text("Cale salvare", fontSize = 9.sp) },
                    modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                ActionButton("Browse", onClick = { folderPicker.launch(null) }, small = true, outline = true)
            }
            Text("INTERVAL Z:", fontSize = 10.sp, fontWeight = FontWeight.W700, color = AppColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(value = zFrom, onValueChange = { zFrom = it }, label = { Text("De la Z", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
                OutlinedTextField(value = zTo, onValueChange = { zTo = it }, label = { Text("Pana la Z", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
            }

            Spacer(modifier = Modifier.height(6.dp))
            ActionButton(
                text = if (downloading) "Descarcare..." else "Download XML ANAF",
                onClick = { viewModel.downloadAnafFiles(zFrom, zTo, clientName, basePath) },
                full = true, enabled = !downloading
            )
            if (downloading && progress.isNotEmpty()) { Spacer(modifier = Modifier.height(6.dp)); Text(progress, fontSize = 11.sp, fontWeight = FontWeight.W600, color = AppColors.Warning, fontFamily = FontFamily.Monospace) }

            lastResult?.let { result ->
                if (result.success && result.fileCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${result.fileCount} fisiere descarcate", fontSize = 11.sp, fontWeight = FontWeight.W700, color = AppColors.Success,
                        modifier = Modifier.fillMaxWidth().background(AppColors.SuccessBg, RoundedCornerShape(8.dp)).padding(10.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionButton(
                        text = "Trimite pe Email (ZIP)",
                        onClick = {
                            val zipUri = viewModel.createZipFromAnafFiles()
                            if (zipUri != null) {
                                val intent = viewModel.createXmlEmailIntent(zipUri, clientName.ifEmpty { headerClientName },
                                    lunaText, deviceState.serialNumber, deviceState.fiscalNumber, result.fileCount)
                                context.startActivity(Intent.createChooser(intent, "Trimite export XML..."))
                            }
                        },
                        full = true, color = AppColors.Success
                    )
                } else if (!result.success) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("EROARE: ${result.error}", fontSize = 11.sp, fontWeight = FontWeight.W700, color = AppColors.Danger,
                        modifier = Modifier.fillMaxWidth().background(AppColors.DangerBg, RoundedCornerShape(8.dp)).padding(10.dp))
                }
            }

            if (cloudUploading) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Se trimit fisierele in cloud.itsmart.ro...", fontSize = 11.sp, fontWeight = FontWeight.W600, color = AppColors.Warning)
            }
            if (cloudResult.isNotEmpty() && !cloudResult.startsWith("OK") && !cloudUploading && cloudLink.paired && lastResult?.success == true) {
                Spacer(modifier = Modifier.height(6.dp))
                ActionButton("Trimite din nou in cloud", onClick = { viewModel.uploadToCloud() }, full = true, outline = true)
            }
            if (cloudResult.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val isOk = cloudResult.startsWith("OK")
                Text("Cloud: $cloudResult", fontSize = 10.sp, fontWeight = FontWeight.W600,
                    color = if (isOk) AppColors.Success else AppColors.Danger,
                    modifier = Modifier.fillMaxWidth().background((if (isOk) AppColors.Success else AppColors.Danger).copy(alpha = 0.08f), RoundedCornerShape(6.dp)).padding(8.dp))
            }
        }

        // === CARD: EXPORT RAPID LUNA ANTERIOARA ===
        SectionCard(title = "Export rapid: luna anterioara ($lunaText)", accent = AppColors.Success) {
            Text(
                "Descarca automat XML/p7b pentru toata luna anterioara, fara sa selectezi interval.\n" +
                "In iunie → mai, in iulie → iunie, etc. Dupa download, fisierele se urca automat in cloud.itsmart.ro.",
                fontSize = 10.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp)
            )
            ActionButton(
                text = if (downloading) "Descarcare..." else "Export $lunaText",
                onClick = { viewModel.exportPreviousMonth(clientName.ifEmpty { headerClientName }, basePath) },
                full = true, enabled = !downloading, color = AppColors.Success
            )
            if (downloading && progress.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(progress, fontSize = 11.sp, fontWeight = FontWeight.W600, color = AppColors.Warning, fontFamily = FontFamily.Monospace)
            }
        }

        // === CARD 2: EXPORT XML DUPA DATA ===
        SectionCard(title = "Export XML dupa Data", accent = AppColors.Accent) {
            Text("INTERVAL DATE:", fontSize = 10.sp, fontWeight = FontWeight.W700, color = AppColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(value = dateFrom, onValueChange = { dateFrom = it }, label = { Text("De la", fontSize = 9.sp) },
                    modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                ActionButton("...", onClick = {
                    DatePickerDialog(context, { _, y, m, d -> calFrom.set(y, m, d); dateFrom = dateFmt.format(calFrom.time) },
                        calFrom.get(Calendar.YEAR), calFrom.get(Calendar.MONTH), calFrom.get(Calendar.DAY_OF_MONTH)).show()
                }, small = true, outline = true)
                OutlinedTextField(value = dateTo, onValueChange = { dateTo = it }, label = { Text("Pana la", fontSize = 9.sp) },
                    modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                ActionButton("...", onClick = {
                    DatePickerDialog(context, { _, y, m, d -> calTo.set(y, m, d); dateTo = dateFmt.format(calTo.time) },
                        calTo.get(Calendar.YEAR), calTo.get(Calendar.MONTH), calTo.get(Calendar.DAY_OF_MONTH)).show()
                }, small = true, outline = true)
            }
            Spacer(modifier = Modifier.height(6.dp))
            ActionButton(
                text = if (downloading) "Descarcare..." else "Download XML ANAF",
                onClick = { viewModel.downloadAnafFiles("$dateFrom 00:00:00", "$dateTo 23:59:59", clientName, basePath) },
                full = true, enabled = !downloading, color = AppColors.Accent
            )
            lastResult?.let { result ->
                if (result.success && result.fileCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionButton(
                        text = "Trimite pe Email (ZIP)",
                        onClick = {
                            val zipUri = viewModel.createZipFromAnafFiles()
                            if (zipUri != null) {
                                val intent = viewModel.createXmlEmailIntent(zipUri, clientName.ifEmpty { headerClientName },
                                    lunaText, deviceState.serialNumber, deviceState.fiscalNumber, result.fileCount)
                                context.startActivity(Intent.createChooser(intent, "Trimite export XML..."))
                            }
                        },
                        full = true, color = AppColors.Success
                    )
                }
            }
        }

        // === CARD 3: RAPORT MEMORIE FISCALA ===
        SectionCard(title = "Raport Memorie Fiscala", accent = AppColors.Warning) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                FilterChip(selected = fmRangeByZ, onClick = { fmRangeByZ = true }, label = { Text("Dupa nr. Z", fontSize = 12.sp) })
                FilterChip(selected = !fmRangeByZ, onClick = { fmRangeByZ = false }, label = { Text("Dupa data", fontSize = 12.sp) })
            }
            if (fmRangeByZ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    OutlinedTextField(value = fmZFrom, onValueChange = { fmZFrom = it }, label = { Text("De la Z", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
                    OutlinedTextField(value = fmZTo, onValueChange = { fmZTo = it }, label = { Text("Pana la Z", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    OutlinedTextField(value = dateFrom, onValueChange = { dateFrom = it }, label = { Text("De la", fontSize = 9.sp) },
                        modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                    OutlinedTextField(value = dateTo, onValueChange = { dateTo = it }, label = { Text("Pana la", fontSize = 9.sp) },
                        modifier = Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                FilterChip(selected = !reportDetailed, onClick = { reportDetailed = false }, label = { Text("Sumar", fontSize = 12.sp) })
                FilterChip(selected = reportDetailed, onClick = { reportDetailed = true }, label = { Text("Detaliat", fontSize = 12.sp) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Printeaza", onClick = {
                    if (fmRangeByZ) viewModel.executeFiscalReportByZ(fmZFrom, fmZTo, reportDetailed)
                    else viewModel.executeFiscalReportByDate(dateFrom, dateTo, reportDetailed)
                }, color = AppColors.Warning, modifier = Modifier.weight(1f))
                ActionButton(text = if (reportLoading) "Citire..." else "Vizualizeaza", onClick = {
                    if (fmRangeByZ) viewModel.previewFiscalReportByZ(fmZFrom, fmZTo, reportDetailed)
                }, outline = true, modifier = Modifier.weight(1f), enabled = fmRangeByZ && !reportLoading)
            }
            if (reportText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(reportText, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary, lineHeight = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(AppColors.Background, RoundedCornerShape(8.dp)).padding(10.dp))
                Spacer(modifier = Modifier.height(8.dp))
                ActionButton("Trimite pe Email (TXT)", onClick = {
                    val intent = viewModel.createFmReportEmailIntent(reportText, clientName.ifEmpty { headerClientName }, deviceState.serialNumber)
                    context.startActivity(Intent.createChooser(intent, "Trimite raport MF..."))
                }, full = true, color = AppColors.Success)
            }
        }
    }
}
