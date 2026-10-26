package com.itsmart.datecs.client.service

import android.content.Context
import android.util.Log
import com.datecsro.fiscalprinter.SDK.FiscalDevice
import com.datecsro.fiscalprinter.SDK.FiscalException
import com.datecsro.fiscalprinter.SDK.model.DatecsFiscalDevice
import com.datecsro.fiscalprinter.SDK.model.rou.FMP_P6X_ROU
import com.itsmart.datecs.client.BuildConfig
import com.itsmart.datecs.client.device.InternalFiscalBridge
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monitorizarea BlueCash in fundal, indiferent de aplicatia de vanzare (Freya, TDA Pos...):
 * deschide modulul fiscal intern cateva secunde, DOAR daca e liber si fara bon deschis, citeste starea
 * (numai comenzi de citire), il elibereaza si trimite starea in CRM prin serverul TDA Pos.
 */
object BlueCashMonitor {
    private const val TAG = "BlueCashMonitor"

    sealed class Result {
        data class Sent(val serial: String) : Result()
        /** Modulul e folosit de alta aplicatie sau are bon deschis — se reincearca mai tarziu. */
        data class Busy(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val CIF_RE = Regex("(?i)(?:CIF|CUI|C\\.?I\\.?F\\.?)[:\\s]*([A-Z]{0,2}\\d{5,13})")

    fun runOnce(ctx: Context): Result {
        if (!InternalFiscalBridge.isAvailable()) return Result.Failed("Aparatul nu are modul fiscal intern")
        val session = InternalFiscalBridge.open(ctx) ?: return Result.Busy("modulul fiscal este folosit de alta aplicatie")
        val status: JSONObject
        var dude: FMP_P6X_ROU? = null
        try {
            val p = FMP_P6X_ROU(session.inputStream, session.outputStream).also { dude = it }
            if (!(try { p.command74Variant0Version0(); true } catch (_: Exception) { false }))
                return Result.Busy("modulul fiscal nu raspunde")
            DatecsFiscalDevice(FiscalException.FPTR_CC_ROMANIA)
            // nu intervenim in timpul unei vanzari
            if (p.command76Variant0Version0().getInt("IsOpen", 0) > 0) return Result.Busy("bon fiscal deschis")
            fun v(name: String, idx: String = "") = try {
                val r = p.Command255Read(FiscalDevice.cmd255Name.valueOf(name), idx)
                (r.get("VarValue") ?: r.keys.firstOrNull()?.let { r.get(it) } ?: "").trim()
            } catch (_: Exception) { "" }
            val headers = (0..7).map { v("Header", it.toString()).replace("^", "").trim() }
            val dev = try { val r = p.command90Variant0Version1(); r.keys.associate { it.trim() to (r.get(it) ?: "").trim() } } catch (_: Exception) { emptyMap() }
            val rates = try {
                val r = p.command50Variant0Version0()
                r.keys.filter { it.startsWith("Tax") && it.length == 4 }.mapNotNull { k ->
                    (r.get(k) ?: "").replace("%", "").trim().toDoubleOrNull()?.takeIf { it < 100 }?.let { "${k.substring(3)}=${"%.2f".format(Locale.US, it)}%" }
                }.sorted().joinToString(", ")
            } catch (_: Exception) { "" }
            val idNumber = v("IDnumber")
            val serial = listOf(idNumber, dev["HardwareVer"].orEmpty()).firstOrNull { it.isNotBlank() && !it.matches(Regex("X*0+")) }
                ?: return Result.Failed("casa nefiscalizata (serie sablon)")
            val tax = v("TAXnumber").replace(Regex("(?i)^\\s*(CIF|CUI)[:\\s]*"), "").trim()
            val cif = tax.takeIf { it.length >= 5 && it != "0" } ?: CIF_RE.find(headers.joinToString(" "))?.groupValues?.get(1).orEmpty()
            val client = headers.firstOrNull { it.isNotBlank() && !CIF_RE.containsMatchIn(it) }.orEmpty()
            val z = v("nZreport")
            status = JSONObject()
                .put("app_build", "ITSMARTCLIENT_${BuildConfig.VERSION_NAME}")
                .put("client", client).put("cif", cif)
                .put("adresa", headers.filter { it.isNotBlank() && it != client && !CIF_RE.containsMatchIn(it) }.joinToString(", "))
                .put("model_aparat", if (Regex("(?i)500").containsMatchIn(android.os.Build.MODEL ?: "")) "BC-500" else "BC-50")
                .put("tip_aparat", "Casa de marcat")
                .put("serie_aparat", serial).put("serie_fiscala", v("FMnumber")).put("fm_number", v("FMnumber"))
                .put("data_fiscalizare", v("FmWriteDateTime"))
                .put("lan_ip", v("LAN_IP")).put("lan_netmask", v("LAN_NetMask")).put("lan_gateway", v("LAN_Gateway"))
                .put("stare", "preluat").put("status", "OK").put("mesaj", "IT Smart Client (monitorizare automata)")
                .put("data_executie", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .put("data_urmatoarei_revizii", v("ServiceDate")).put("parola_service", v("ServPasw"))
                .put("ultimul_z", z).put("nzreport", z).put("rapoarte_ramase", z.toIntOrNull()?.let { (2500 - it).toString() } ?: "")
                .put("tip_conexiune", when (v("NetInterfaceToUse")) { "0" -> "LAN"; "1" -> "GPRS"; "2" -> "WiFi"; else -> "INTERN" })
                .put("raport_anaf", v("LastConnectionZrepNum")).put("status_anaf", v("LastConnectionState"))
                .put("eroare_anaf", v("LastConnectionErrorCode"))
                .put("antet_complet", headers.filter { it.isNotBlank() }.joinToString(" | "))
                .put("tva", rates).put("observatii", "TVA: $rates").put("utilizator", "itsmart_client")
        } catch (e: Exception) {
            Log.w(TAG, "citire stare: ${e.message}")
            return Result.Failed("citire stare: ${e.message}")
        } finally {
            try { dude?.close() } catch (_: Exception) {}
            session.close()   // modulul se elibereaza imediat pentru aplicatia de vanzare
        }
        return try {
            TdaServerClient.sendStatus(ctx, status, "IT Smart Client (automat)")
            Result.Sent(status.optString("serie_aparat"))
        } catch (e: Exception) {
            Result.Failed("CRM: ${e.message}")
        }
    }
}
