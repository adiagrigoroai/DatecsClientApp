package com.itsmart.datecs.client.service

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.itsmart.datecs.client.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Asocierea aplicatiei cu cloud.itsmart.ro (token propriu per instalare) si urcarea fisierelor ANAF.
 *
 *   POST /api/v2/pair     code + install_id + app  -> token (cod de asociere generat in portal, valabil 24h)
 *   POST /api/v2/migrate  cui + serie + install_id -> token (o singura data per aparat, pana la 31.01.2027)
 *   GET  /api/v2/whoami   Bearer token -> firma
 *   POST /api/v2/enroll   cui + email + telefon -> token (firma noua din ANAF sau existenta) + email de confirmare
 *   GET  /api/v2/enroll/status  Bearer -> email / payment / active / expired / rejected
 *   POST /api/upload_xml  multipart files[], Bearer token
 *
 * Tokenul se pastreaza criptat cu o cheie din Android Keystore (nu iese de pe aparat, nu intra in backup).
 */
class CloudUploadService(context: Context) {
    companion object {
        private const val TAG = "CloudUploadService"
        private const val BASE_URL = "https://cloud.itsmart.ro"
        private const val APP = "itsmartclient"
        private const val PREFS = "cloud_link"
        private const val KEY_ALIAS = "itsmart_cloud_token"
    }

    data class Link(val paired: Boolean, val companyName: String = "", val cui: String = "", val method: String = "")

    data class Result(val ok: Boolean, val message: String = "", val unauthorized: Boolean = false)

    /** Starea contului pentru o instalare inrolata din aplicatie; aplicatia se foloseste doar la "active". */
    data class Account(val status: String, val companyName: String = "", val cui: String = "", val email: String = "",
                       val paidUntil: String = "", val payLink: String = "", val invoiceUrl: String = "", val error: String = "") {
        val active get() = status == "active"
    }

    /** Un fisier din cloud (lista /api/list_files a firmei). */
    data class CloudFile(val id: Long, val name: String, val size: Long, val type: String, val amef: String, val uploadedAt: String)

    data class UploadResult(val ok: Boolean, val uploaded: Int = 0, val skipped: Int = 0, val error: String = "",
                            val unauthorized: Boolean = false)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Identificatorul acestei instalari (o reinstalare primeste altul si trebuie asociata din nou). */
    private val installId: String
        get() = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).apply() }

    private val hostname get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun link(): Link = if (token().isEmpty()) Link(false)
        else Link(true, prefs.getString("company", "") ?: "", prefs.getString("cui", "") ?: "", prefs.getString("method", "") ?: "")

    fun forget() { prefs.edit().remove("token").remove("company").remove("cui").remove("method").remove("status")
        .remove("email").remove("paid_until").apply() }

    /** Instalarea a fost inrolata din aplicatie (cod fiscal + email): accesul depinde de starea contului. */
    val enrolled get() = link().method == "enroll"

    /** Inrolare: firma noua (date ANAF) sau existenta (emailul contului); serverul trimite emailul de confirmare. */
    suspend fun enroll(cui: String, email: String, phone: String): Result {
        val r = issue("/api/v2/enroll", mapOf("cui" to cui.trim(), "email" to email.trim(), "phone" to phone.trim()), "enroll")
        if (r.ok) prefs.edit().putString("status", "email").putString("email", email.trim().lowercase()).apply()
        return r
    }

    /** Ultima stare cunoscuta (fara internet aplicatia ramane cum era la ultima verificare). */
    fun cachedAccount() = Account(prefs.getString("status", "email") ?: "email", prefs.getString("company", "") ?: "",
        prefs.getString("cui", "") ?: "", prefs.getString("email", "") ?: "", prefs.getString("paid_until", "") ?: "")

    /** Starea contului de pe server; la eroare de retea intoarce ultima stare cunoscuta, cu mesajul erorii. */
    suspend fun account(): Account = withContext(Dispatchers.IO) {
        val t = token().ifEmpty { return@withContext Account("none") }
        try {
            val c = (URL("$BASE_URL/api/v2/enroll/status").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("User-Agent", "ITSmartClient/${BuildConfig.VERSION_NAME}")
            }
            val (code, body) = read(c)
            when (code) {
                in 200..299 -> {
                    val j = JSONObject(body)
                    val a = Account(j.optString("status"), j.optString("company_name"), j.optString("cui"), j.optString("email"),
                        j.optString("paid_until"), j.optString("pay_link"), j.optString("invoice_url"))
                    prefs.edit().putString("status", a.status).putString("company", a.companyName).putString("cui", a.cui)
                        .putString("email", a.email).putString("paid_until", a.paidUntil).apply()
                    a
                }
                401 -> { forget(); Account("none", error = "Inrolarea a fost anulata. Reluati inregistrarea.") }
                else -> cachedAccount().copy(error = "HTTP $code: ${detail(body)}")
            }
        } catch (e: Exception) { cachedAccount().copy(error = "Fara conexiune la cloud.itsmart.ro") }
    }

    /** Retrimite emailul de confirmare a inrolarii. */
    suspend fun resendConfirmation(): Result = withContext(Dispatchers.IO) {
        val t = token().ifEmpty { return@withContext Result(false, "Neasociat") }
        try {
            val c = (URL("$BASE_URL/api/v2/enroll/resend").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            c.outputStream.use { it.write(ByteArray(0)) }
            val (code, body) = read(c)
            val j = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (code in 200..299 && j.optBoolean("ok")) Result(true, "Emailul a fost retrimis")
            else Result(false, j.optString("detail").ifBlank { "HTTP $code" })
        } catch (e: Exception) { Result(false, e.message ?: "fara conexiune") }
    }

    /** Asociere cu codul din cloud.itsmart.ro (ex. "ABCD-2345"). */
    suspend fun pair(code: String): Result = issue("/api/v2/pair", mapOf("code" to code.trim()), "pair")

    /** Asociere automata pentru aparatele deja inregistrate la firma (CUI + seria aparatului). */
    suspend fun migrate(cui: String, serie: String): Result =
        issue("/api/v2/migrate", mapOf("cui" to normalizeCui(cui), "serie" to serie.trim()), "migrate")

    /** Verifica tokenul si actualizeaza numele firmei; 401 = token revocat din portal (asocierea se sterge). */
    suspend fun whoami(): Result = withContext(Dispatchers.IO) {
        val t = token().ifEmpty { return@withContext Result(false, "Neasociat") }
        try {
            val c = (URL("$BASE_URL/api/v2/whoami").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("User-Agent", "ITSmartClient/${BuildConfig.VERSION_NAME}")
            }
            val (code, body) = read(c)
            when (code) {
                in 200..299 -> {
                    val j = JSONObject(body)
                    prefs.edit().putString("company", j.optString("company_name")).putString("cui", j.optString("cui")).apply()
                    Result(true, j.optString("company_name"))
                }
                401 -> { forget(); Result(false, "Asocierea a fost revocata din cloud.itsmart.ro", unauthorized = true) }
                else -> Result(false, "HTTP $code: ${detail(body)}")
            }
        } catch (e: Exception) { Result(false, e.message ?: "fara conexiune") }
    }

    private suspend fun issue(path: String, fields: Map<String, String>, method: String): Result = withContext(Dispatchers.IO) {
        try {
            val form = (fields + mapOf("install_id" to installId, "app" to APP, "hostname" to hostname, "version" to BuildConfig.VERSION_NAME))
                .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
            val c = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "ITSmartClient/${BuildConfig.VERSION_NAME}")
            }
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val (code, body) = read(c)
            if (code !in 200..299) return@withContext Result(false, detail(body).ifBlank { "HTTP $code" })
            val j = JSONObject(body)
            val token = j.optString("token")
            if (token.isEmpty()) return@withContext Result(false, "Raspuns fara token")
            prefs.edit().putString("token", encrypt(token)).putString("company", j.optString("company_name"))
                .putString("cui", j.optString("cui")).putString("method", method).apply()
            Log.d(TAG, "asociat ($method) cu ${j.optString("company_name")}")
            Result(true, j.optString("company_name"))
        } catch (e: Exception) {
            Log.w(TAG, "asociere $method: ${e.message}")
            Result(false, e.message ?: "fara conexiune")
        }
    }

    /** Emailul contabilului firmei din cloud (primeste automat fisierele XML urcate); null = eroare. */
    suspend fun accountant(newEmail: String? = null): Pair<String?, String> = withContext(Dispatchers.IO) {
        val t = token().ifEmpty { return@withContext null to "Aplicatia nu este asociata cu cloud.itsmart.ro" }
        try {
            val c = (URL("$BASE_URL/api/v2/accountant").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("User-Agent", "ITSmartClient/${BuildConfig.VERSION_NAME}")
                if (newEmail != null) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (newEmail != null) c.outputStream.use { it.write(("email=" + URLEncoder.encode(newEmail, "UTF-8")).toByteArray()) }
            val (code, body) = read(c)
            if (code !in 200..299) return@withContext null to (if (code == 401) "Firma nu este activa in cloud" else detail(body))
            JSONObject(body).optString("email") to ""
        } catch (e: Exception) { null to (e.message ?: "fara conexiune") }
    }

    /** Raportul MF pe emailul setat in aplicatie (contabilul firmei), cu PDF + TXT atasate. Intoarce (ok, mesaj). */
    suspend fun sendFmReport(pdf: File, text: String, name: String, serie: String, title: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val t = token().ifEmpty { return@withContext false to "Aplicatia nu este asociata cu cloud.itsmart.ro (Setari → Cloud)" }
        val boundary = "----ITSmartClient" + UUID.randomUUID().toString().replace("-", "")
        try {
            val c = (URL("$BASE_URL/api/v2/fm-report").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; useCaches = false
                connectTimeout = 30_000; readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "ITSmartClient/${BuildConfig.VERSION_NAME}")
            }
            c.outputStream.buffered().use { out ->
                fun field(n: String, v: String) =
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray(Charsets.UTF_8))
                field("serie_aparat", serie); field("title", title); field("name", name); field("text", text)
                if (pdf.isFile) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"pdf\"; filename=\"$name.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".toByteArray())
                    pdf.inputStream().use { it.copyTo(out) }
                    out.write("\r\n".toByteArray())
                }
                out.write("--$boundary--\r\n".toByteArray())
            }
            val (code, body) = read(c)
            if (code !in 200..299) return@withContext false to (if (code == 401) "Firma nu este activa in cloud" else detail(body))
            true to "Raportul a fost trimis pe ${JSONObject(body).optString("to")}"
        } catch (e: Exception) { false to (e.message ?: "fara conexiune") }
    }

    /** Fisierele firmei din cloud (cele mai noi primele); null = eroare (vezi mesajul). */
    suspend fun listFiles(): Pair<List<CloudFile>?, String> = withContext(Dispatchers.IO) {
        val t = token().ifEmpty { return@withContext null to "Aplicatia nu este asociata cu cloud.itsmart.ro" }
        try {
            val c = (URL("$BASE_URL/api/list_files").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("User-Agent", "ITSmartClient/${BuildConfig.VERSION_NAME}")
            }
            val (code, body) = read(c)
            if (code !in 200..299) return@withContext null to (if (code == 401) "Firma nu este activa in cloud" else "HTTP $code: ${detail(body)}")
            val arr = JSONObject(body).optJSONArray("files") ?: return@withContext emptyList<CloudFile>() to ""
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                CloudFile(j.optLong("id"), j.optString("file_name"), j.optLong("file_size"), j.optString("file_type"),
                    j.optString("amef_number"), j.optString("uploaded_at"))
            } to ""
        } catch (e: Exception) { null to (e.message ?: "fara conexiune") }
    }

    /** Urca fisierele (ZIP cu p7b + PDF-uri) pentru luna/anul dat. */
    suspend fun upload(files: List<File>, cui: String, serieAparat: String, luna: Int, anul: Int): UploadResult = withContext(Dispatchers.IO) {
        val t = token().ifEmpty { return@withContext UploadResult(false, error = "Aplicatia nu este asociata cu cloud.itsmart.ro", unauthorized = true) }
        val real = files.filter { it.isFile && it.length() > 0 }
        if (real.isEmpty()) return@withContext UploadResult(false, error = "Niciun fisier de trimis")
        val boundary = "----ITSmartClient" + UUID.randomUUID().toString().replace("-", "")
        try {
            val c = (URL("$BASE_URL/api/upload_xml").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; useCaches = false
                connectTimeout = 60_000; readTimeout = 5 * 60_000
                setRequestProperty("Authorization", "Bearer $t")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "ITSmartClient/${BuildConfig.VERSION_NAME}")
            }
            c.outputStream.buffered().use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                }
                field("cui", normalizeCui(cui))
                field("serie_aparat", serieAparat)
                field("sursa", "android")
                field("luna", luna.toString())
                field("anul", anul.toString())
                field("session_id", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_android")
                for (f in real) {
                    val type = when (f.extension.lowercase()) { "zip" -> "application/zip"; "pdf" -> "application/pdf"; else -> "application/octet-stream" }
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"files[]\"; filename=\"${f.name}\"\r\nContent-Type: $type\r\n\r\n".toByteArray(Charsets.UTF_8))
                    f.inputStream().use { it.copyTo(out) }
                    out.write("\r\n".toByteArray())
                }
                out.write("--$boundary--\r\n".toByteArray())
            }
            val (code, body) = read(c)
            when (code) {
                in 200..299 -> {
                    val j = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    UploadResult(true, j.optInt("uploaded", real.size), j.optInt("skipped", 0))
                }
                // tokenul a fost revocat din portal: aplicatia trebuie asociata din nou
                401 -> { forget(); UploadResult(false, error = "Asocierea nu mai este valabila. Introduceti un cod nou de asociere.", unauthorized = true) }
                else -> UploadResult(false, error = "HTTP $code: ${detail(body)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload: ${e.message}")
            UploadResult(false, error = e.message ?: "fara conexiune")
        }
    }

    private fun read(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val body = try { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        c.disconnect()
        return code to body
    }

    private fun detail(body: String) = try { JSONObject(body).optString("detail", body) } catch (_: Exception) { body }.take(200)

    /** CUI fara "CIF:" / "RO". */
    private fun normalizeCui(cui: String): String {
        var c = cui.trim()
        if (c.startsWith("CIF:", ignoreCase = true)) c = c.substring(4).trim()
        if (c.length > 2 && c.startsWith("RO", ignoreCase = true) && c.substring(2).trim().toLongOrNull() != null) c = c.substring(2).trim()
        return c
    }

    // ---- token criptat cu cheie din Android Keystore ----

    private fun token(): String = prefs.getString("token", null)?.let {
        try { decrypt(it) } catch (e: Exception) { Log.w(TAG, "token ilizibil: ${e.message}"); forget(); "" }
    } ?: ""

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }
}
