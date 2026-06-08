package com.itsmart.datecs.client.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Echivalent Android al StatusAMEF.Core.Services.CloudUploadService (Win).
 *
 * Endpoint-uri pe cloud.itsmart.ro:
 *   GET  /api/get_token?cui={CUI}   → { api_token, company_name, cui, upload_link }
 *   POST /api/upload_xml             → multipart/form-data cu files[]
 *                                      Header: Authorization: Bearer {api_token}
 */
object CloudUploadService {
    private const val TAG = "CloudUploadService"
    private const val BASE_URL = "https://cloud.itsmart.ro"

    data class TokenResult(
        val success: Boolean,
        val apiToken: String = "",
        val companyName: String = "",
        val cui: String = "",
        val uploadLink: String = "",
        val error: String = ""
    )

    data class UploadResult(
        val success: Boolean,
        val uploaded: Int = 0,
        val skipped: Int = 0,
        val sessionId: String = "",
        val error: String = ""
    )

    /** Normalizeaza CUI: scoate prefix "CIF:" si "RO". */
    private fun normalizeCui(cui: String): String {
        var c = cui.trim()
        if (c.startsWith("CIF:", ignoreCase = true)) c = c.substring(4).trim()
        if (c.length > 2 && c.startsWith("RO", ignoreCase = true) && c.substring(2).toLongOrNull() != null) {
            c = c.substring(2).trim()
        }
        return c
    }

    /** Obtine API token pentru un CUI. */
    suspend fun getToken(cuiInput: String): TokenResult = withContext(Dispatchers.IO) {
        if (cuiInput.isBlank()) return@withContext TokenResult(false, error = "CUI lipsa")
        val cui = normalizeCui(cuiInput)
        try {
            val url = "$BASE_URL/api/get_token?cui=" + URLEncoder.encode(cui, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                Log.w(TAG, "get_token HTTP $code: $body")
                return@withContext TokenResult(false, error = "HTTP $code: $body")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            TokenResult(
                success = true,
                apiToken = json.optString("api_token", ""),
                companyName = json.optString("company_name", ""),
                cui = json.optString("cui", cui),
                uploadLink = json.optString("upload_link", "")
            )
        } catch (t: Throwable) {
            Log.w(TAG, "getToken EROARE: ${t.message}")
            TokenResult(false, error = t.message ?: "eroare necunoscuta")
        }
    }

    /**
     * Upload multipart/form-data la /api/upload_xml.
     *
     * @param apiToken obtinut din getToken()
     * @param cui CUI normalizat
     * @param serieAparat serie fiscala AMEF
     * @param files lista de fisiere de uploadat (zip, pdf, p7b, etc.)
     * @param luna 1-12
     * @param anul anul (4 cifre)
     */
    suspend fun upload(
        apiToken: String,
        cui: String,
        serieAparat: String,
        files: List<File>,
        luna: Int,
        anul: Int
    ): UploadResult = withContext(Dispatchers.IO) {
        if (apiToken.isBlank()) return@withContext UploadResult(false, error = "API token lipsa")
        val realFiles = files.filter { it.exists() && it.length() > 0 }
        if (realFiles.isEmpty()) return@withContext UploadResult(false, error = "Niciun fisier de uploadat")

        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_android"
        val boundary = "----DatecsClientBoundary" + UUID.randomUUID().toString().replace("-", "")
        val lineEnd = "\r\n"

        try {
            val conn = (URL("$BASE_URL/api/upload_xml").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60_000
                readTimeout = 5 * 60_000
                doOutput = true
                useCaches = false
                setRequestProperty("Authorization", "Bearer $apiToken")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "DatecsClientApp")
            }

            DataOutputStream(conn.outputStream).use { dos ->
                fun writeField(name: String, value: String) {
                    dos.writeBytes("--$boundary$lineEnd")
                    dos.writeBytes("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd")
                    dos.write(value.toByteArray(Charsets.UTF_8))
                    dos.writeBytes(lineEnd)
                }
                fun writeFile(field: String, file: File) {
                    dos.writeBytes("--$boundary$lineEnd")
                    dos.writeBytes("Content-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"$lineEnd")
                    dos.writeBytes("Content-Type: application/octet-stream$lineEnd$lineEnd")
                    FileInputStream(file).use { fis ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = fis.read(buffer)
                            if (n == -1) break
                            dos.write(buffer, 0, n)
                        }
                    }
                    dos.writeBytes(lineEnd)
                }

                writeField("cui", cui)
                writeField("serie_aparat", serieAparat)
                writeField("sursa", "android")
                writeField("luna", luna.toString())
                writeField("anul", anul.toString())
                writeField("session_id", sessionId)

                for (f in realFiles) writeFile("files[]", f)

                dos.writeBytes("--$boundary--$lineEnd")
                dos.flush()
            }

            val code = conn.responseCode
            val respBody = try {
                if (code in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }

            if (code !in 200..299) {
                var detail = respBody
                try { detail = JSONObject(respBody).optString("detail", respBody) } catch (_: Exception) {}
                Log.w(TAG, "upload HTTP $code: $detail")
                return@withContext UploadResult(false, sessionId = sessionId, error = "HTTP $code: $detail")
            }

            // parseaza raspuns
            var uploaded = realFiles.size
            var skipped = 0
            try {
                val json = JSONObject(respBody)
                uploaded = json.optInt("uploaded", uploaded)
                skipped = json.optInt("skipped", 0)
            } catch (_: Exception) {}

            Log.d(TAG, "upload OK: $uploaded uploaded, $skipped skipped, session=$sessionId")
            UploadResult(true, uploaded, skipped, sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "upload EROARE: ${t.message}")
            UploadResult(false, error = t.message ?: "eroare necunoscuta")
        }
    }

    /** Convenience: getToken + upload intr-o singura chemare. */
    suspend fun getTokenAndUpload(
        cui: String,
        serieAparat: String,
        files: List<File>,
        luna: Int,
        anul: Int
    ): UploadResult {
        val tok = getToken(cui)
        if (!tok.success) return UploadResult(false, error = "Token: ${tok.error}")
        return upload(tok.apiToken, tok.cui, serieAparat, files, luna, anul)
    }
}
