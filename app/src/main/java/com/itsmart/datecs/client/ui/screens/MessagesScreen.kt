package com.itsmart.datecs.client.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsmart.datecs.client.data.model.DeviceState
import com.itsmart.datecs.client.ui.components.ActionButton
import com.itsmart.datecs.client.ui.components.SectionCard
import com.itsmart.datecs.client.ui.components.StatusRow
import com.itsmart.datecs.client.ui.theme.AppColors
import com.itsmart.datecs.client.viewmodel.MainViewModel

@Composable
fun MessagesScreen(viewModel: MainViewModel, deviceState: DeviceState) {
    val context = LocalContext.current
    val crmMessages by viewModel.crmMessages.collectAsStateWithLifecycle()
    val crmInvoices by viewModel.crmInvoices.collectAsStateWithLifecycle()
    val pendingRevizie by viewModel.pendingRevizie.collectAsStateWithLifecycle()
    val revizieRunning by viewModel.revizieRunning.collectAsStateWithLifecycle()
    val revizieResult by viewModel.revizieResult.collectAsStateWithLifecycle()

    val casaConectata by viewModel.connected.collectAsStateWithLifecycle()
    var showRevizieConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // === REVIZIE ANUALA ===
        if (pendingRevizie != null) {
            SectionCard(title = "Revizie Anuala", accent = AppColors.Warning) {
                Text(
                    "Aveti o revizie anuala programata!",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W700,
                    color = AppColors.Warning,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (pendingRevizie!!.serviceDate.isNotEmpty()) {
                    Text(
                        "Data service noua: ${pendingRevizie!!.serviceDate}",
                        fontSize = 12.sp, color = AppColors.TextSecondary
                    )
                }
                if (pendingRevizie!!.observatii.isNotEmpty()) {
                    Text(
                        pendingRevizie!!.observatii,
                        fontSize = 11.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!showRevizieConfirm) {
                    ActionButton(
                        text = "Vreau sa fac revizia",
                        onClick = { showRevizieConfirm = true },
                        full = true, color = AppColors.Warning
                    )
                } else {
                    // Avertizari
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.WarningBg, RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = AppColors.Warning, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Inainte de a confirma revizia asigurati-va ca:",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.W700,
                                    color = AppColors.Warning
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            WarningItem("Ati executat raportul Z")
                            WarningItem("Aveti rola de hartie in aparat")
                            WarningItem("Aveti conexiune la internet")
                            if (!casaConectata) WarningItem("Casa de marcat nu este conectata: conectati-o din Aparate, apoi reveniti aici")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            text = "Anuleaza",
                            onClick = { showRevizieConfirm = false },
                            outline = true, color = AppColors.TextTertiary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            text = when { revizieRunning -> "Revizie in curs..."; !casaConectata -> "Conectati casa de marcat"
                                else -> "Confirm si execut revizia" },
                            onClick = { viewModel.executeCrmRevizie() },
                            color = AppColors.Warning,
                            enabled = !revizieRunning && casaConectata,
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }
        }

        // === REVIZIE RESULT ===
        revizieResult?.let { rev ->
            val revOk = rev.error.isEmpty()
            SectionCard(
                title = if (revOk) "Revizie Finalizata" else "Revizie - Eroare",
                accent = if (revOk) AppColors.Success else AppColors.Danger
            ) {
                if (rev.serviceDateSet) StatusRow("Data Service", rev.newServiceDate, mono = true, accent = AppColors.Success)
                StatusRow("ANAF Check", if (rev.anafCheckOk) "OK" else "ESUAT",
                    accent = if (rev.anafCheckOk) AppColors.Success else AppColors.Danger)
                StatusRow("SIM", if (rev.simOk) "OK" else "ESUAT",
                    accent = if (rev.simOk) AppColors.Success else AppColors.Danger)
                StatusRow("Crypto", if (rev.cryptoOk) "OK" else "ESUAT",
                    accent = if (rev.cryptoOk) AppColors.Success else AppColors.Danger)
                StatusRow("Modem", if (rev.modemOk) "OK" else "ESUAT",
                    accent = if (rev.modemOk) AppColors.Success else AppColors.Danger)
                if (rev.zPrinted) StatusRow("Raport Z", "Tiparit", accent = AppColors.Success)
                if (rev.error.isNotEmpty()) {
                    Text(rev.error, fontSize = 11.sp, color = AppColors.Danger,
                        modifier = Modifier.padding(top = 4.dp))
                }

                // Butoane PV
                if (rev.pvText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            text = "Email PV + PDF",
                            onClick = {
                                val intent = viewModel.createPvEmailIntent(rev.pvText, rev.pdfFileName)
                                context.startActivity(android.content.Intent.createChooser(intent, "Trimite PV prin..."))
                            },
                            small = true, color = AppColors.Success,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Log detaliat
                if (rev.log.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    var showLog by remember { mutableStateOf(false) }
                    ActionButton(
                        text = if (showLog) "Ascunde Log" else "Arata Log",
                        onClick = { showLog = !showLog },
                        small = true, outline = true
                    )
                    if (showLog) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            rev.log, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            color = AppColors.TextSecondary, lineHeight = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.Background, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        // === NOTIFICARI CRM ===
        if (crmMessages.isNotEmpty()) {
            SectionCard(title = "Notificari", accent = AppColors.Primary) {
                crmMessages.forEach { msg ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(
                                if (!msg.read) AppColors.InfoBg else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val icon = when (msg.type) {
                                    "revizie" -> Icons.Filled.Build
                                    "factura" -> Icons.Filled.Receipt
                                    else -> Icons.Filled.Notifications
                                }
                                Icon(icon, null, tint = AppColors.Primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    msg.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.W700,
                                    color = AppColors.TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (msg.date.isNotEmpty()) {
                                    Text(msg.date, fontSize = 10.sp, color = AppColors.TextTertiary)
                                }
                            }
                            if (msg.body.isNotEmpty()) {
                                Text(
                                    msg.body,
                                    fontSize = 11.sp,
                                    color = AppColors.TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp, start = 26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // === FACTURI ===
        if (crmInvoices.isNotEmpty()) {
            SectionCard(title = "Facturi", accent = AppColors.Accent) {
                crmInvoices.forEach { inv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(AppColors.Background, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Receipt, null, tint = AppColors.Accent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Factura ${inv.number}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.W600,
                                color = AppColors.TextPrimary
                            )
                            Text(
                                "${inv.date} — ${inv.amount}",
                                fontSize = 11.sp,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // === EMPTY STATE ===
        if (crmMessages.isEmpty() && crmInvoices.isEmpty() && pendingRevizie == null && revizieResult == null) {
            Spacer(modifier = Modifier.height(40.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Inbox, null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Niciun mesaj",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W600,
                    color = AppColors.TextTertiary
                )
                Text(
                    "Mesajele din CRM vor aparea aici",
                    fontSize = 12.sp,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // === REFRESH MESAJE ===
        Spacer(modifier = Modifier.height(16.dp))
        ActionButton(
            text = "Actualizeaza Mesaje",
            onClick = { viewModel.fetchCrmMessages(); viewModel.checkCrmCommands() },
            full = true, outline = true
        )
    }
}

@Composable
private fun WarningItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(AppColors.Warning, androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, color = AppColors.TextPrimary)
    }
}
