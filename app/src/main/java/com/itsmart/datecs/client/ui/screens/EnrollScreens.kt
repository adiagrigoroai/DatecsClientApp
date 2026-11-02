package com.itsmart.datecs.client.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmart.datecs.client.service.CloudUploadService
import com.itsmart.datecs.client.ui.components.SafeCircularProgress
import com.itsmart.datecs.client.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Inregistrarea firmei din aplicatie: cod fiscal, email, telefon (contul se confirma pe email, apoi se plateste). */
@Composable
fun EnrollScreen(isLoading: Boolean, errorMessage: String, onEnroll: (String, String, String) -> Unit, onBack: () -> Unit) {
    var cui by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val valid = cui.count { it.isDigit() } in 2..10 && email.contains('@') && email.contains('.') && phone.count { it.isDigit() } >= 9

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp).align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.itsmart.datecs.client.R.drawable.hero_cloud), null,
                modifier = Modifier.size(72.dp).padding(bottom = 10.dp))
            Text("Inregistrare firma", color = AppColors.PrimaryDark, fontSize = 24.sp, fontWeight = FontWeight.W800)
            Text("Contul se creeaza in cloud.itsmart.ro cu datele firmei de la ANAF", color = AppColors.TextSecondary, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    OutlinedTextField(value = cui, onValueChange = { cui = it.uppercase().take(14) }, label = { Text("Cod fiscal (CUI)") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true, shape = RoundedCornerShape(10.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it.trim().take(150) }, label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true, shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(value = phone, onValueChange = { phone = it.take(20) }, label = { Text("Telefon") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true, shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Text("Primiti un email de confirmare. Dupa confirmare va trimitem proforma pentru primele 3 luni si linkul de plata; " +
                        "dupa plata aplicatia se activeaza automat.", fontSize = 12.sp, color = AppColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp))
                    if (errorMessage.isNotEmpty()) {
                        Text(errorMessage, color = AppColors.Danger, fontSize = 12.sp, fontWeight = FontWeight.W600, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().background(AppColors.DangerBg, RoundedCornerShape(8.dp)).padding(10.dp))
                        Spacer(Modifier.height(10.dp))
                    }
                    Button(onClick = { onEnroll(cui, email, phone) }, enabled = !isLoading && valid,
                        modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)) {
                        if (isLoading) SafeCircularProgress(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Inregistreaza firma", fontWeight = FontWeight.W700, fontSize = 15.sp, color = Color.White)
                    }
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("Am deja cont — autentificare", color = AppColors.Primary)
                    }
                }
            }
        }
    }
}

/** Contul inrolat inca nu e activ: confirmare email, plata, expirat sau refuzat. Se reverifica singur la 30 s. */
@Composable
fun AccountStatusScreen(account: CloudUploadService.Account, notice: String, onRefresh: () -> Unit, onResend: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(account.status) {
        while (true) { delay(30_000); onRefresh() }
    }
    data class Info(val icon: ImageVector, val color: Color, val bg: Color, val title: String, val text: String)
    val i = when (account.status) {
        "email" -> Info(Icons.Filled.Email, AppColors.Info, AppColors.InfoBg, "Confirmati adresa de email",
            "Am trimis un email la ${account.email.ifBlank { "adresa introdusa" }}. Deschideti linkul din email ca sa continuam. " +
                "Verificati si folderul Spam.")
        "payment" -> Info(Icons.Filled.Payment, AppColors.Warning, AppColors.WarningBg, "Contul asteapta plata",
            if (account.payLink.isNotBlank()) "Ati primit pe email proforma pentru primele 3 luni si linkul de plata. Puteti plati cu cardul. " +
                "Dupa confirmarea platii, aplicatia se activeaza automat."
            else "Emailul a fost confirmat. In scurt timp primiti pe email proforma pentru primele 3 luni si linkul de plata.")
        "expired" -> Info(Icons.Filled.Payment, AppColors.Warning, AppColors.WarningBg, "Abonamentul a expirat",
            "Abonamentul a fost valabil pana la ${account.paidUntil}. Pentru prelungire contactati IT Smart: 031.433.66.85, dev@itsmartretail.ro.")
        "rejected" -> Info(Icons.Filled.ErrorOutline, AppColors.Danger, AppColors.DangerBg, "Inregistrare refuzata",
            "Pentru detalii contactati IT Smart: 031.433.66.85, dev@itsmartretail.ro.")
        else -> Info(Icons.Filled.CheckCircle, AppColors.Success, AppColors.SuccessBg, "Cont activ", "")
    }

    if (confirmLeave) {
        AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("Renuntati la inregistrare?") },
            text = { Text("Pe acest aparat se poate face apoi o inregistrare noua sau autentificarea cu un cont existent.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Renunta", color = AppColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Inapoi") } })
    }

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp).align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).background(i.bg, RoundedCornerShape(36.dp)), contentAlignment = Alignment.Center) {
                        Icon(i.icon, null, tint = i.color, modifier = Modifier.size(40.dp))
                    }
                    Text(i.title, fontSize = 22.sp, fontWeight = FontWeight.W800, color = i.color, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    if (account.companyName.isNotBlank()) Text("${account.companyName}  ·  CUI ${account.cui}", fontSize = 13.sp,
                        fontWeight = FontWeight.W600, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
                    Text(i.text, fontSize = 14.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center, lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
                    if (account.status == "payment" && account.payLink.isNotBlank()) {
                        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.payLink))) },
                            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Success)) {
                            Text("Plateste online", fontWeight = FontWeight.W700, fontSize = 15.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (account.status == "payment" && account.invoiceUrl.isNotBlank()) {
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.invoiceUrl))) },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Vezi proforma") }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (account.status == "email") {
                        OutlinedButton(onClick = onResend, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                            Text("Retrimite emailul de confirmare")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary, contentColor = Color.White)) { Text("Verifica din nou", fontWeight = FontWeight.W700) }
                    if (account.error.isNotBlank()) Text(account.error, fontSize = 12.sp, color = AppColors.Danger,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                    if (notice.isNotBlank()) Text(notice, fontSize = 12.sp, color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                    TextButton(onClick = { confirmLeave = true }, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Renunta la inregistrare", color = AppColors.TextTertiary, fontSize = 12.sp)
                    }
                }
            }
            Text("cloud.itsmart.ro", color = AppColors.TextTertiary, fontSize = 10.sp, modifier = Modifier.padding(top = 20.dp))
        }
    }
}
