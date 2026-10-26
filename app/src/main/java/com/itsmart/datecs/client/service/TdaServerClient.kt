package com.itsmart.datecs.client.service

import android.content.Context
import android.util.Log
import com.itsmart.datecs.client.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Starea aparatului in CRM (Monitorizare AMEF v2) prin serverul TDA Pos.
 * CRM-ul cere autentificare la /api/preluare_amef; contul CRM sta doar pe server, nu in aplicatie.
 * Aparatul primeste un token propriu la prima trimitere (inregistrare pe serie).
 */
object TdaServerClient {
    private const val TAG = "TdaServerClient"
    private const val BASE = "https://tdapos.itsmartretail.ro"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tda_server", Context.MODE_PRIVATE)

    private fun post(path: String, body: JSONObject, token: String? = null): Pair<Int, JSONObject> {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
            if (token != null) setRequestProperty("X-Client-Token", token)
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return code to (try { JSONObject(text) } catch (_: Exception) { JSONObject() })
    }

    private fun register(ctx: Context, status: JSONObject): String {
        val serial = status.optString("serie_aparat")
        val user = ctx.getSharedPreferences("datecs_client", 0).getString("auth_user", "") ?: ""
        val (_, r) = post("/api/client/register", JSONObject().put("serial", serial).put("model", status.optString("model_aparat"))
            .put("cif", status.optString("cif")).put("client", status.optString("client")).put("user", user))
        val token = r.optString("token")
        if (!r.optBoolean("ok") || token.isBlank()) throw IOException(r.optString("error").ifBlank { "Inregistrare esuata" })
        prefs(ctx).edit().putString("token", token).putString("serial", serial).apply()
        return token
    }

    /** Trimite starea (payload preluare_amef). Arunca exceptie cu motivul daca nu ajunge in CRM. */
    fun sendStatus(ctx: Context, status: JSONObject, message: String) {
        val serial = status.optString("serie_aparat")
        if (serial.isBlank()) throw IOException("Seria aparatului nu a fost citita")
        var token = prefs(ctx).getString("token", "").takeIf { prefs(ctx).getString("serial", "") == serial && !it.isNullOrBlank() }
            ?: register(ctx, status)
        val body = JSONObject().put("status", status).put("message", message)
        var (code, r) = post("/api/client/crm-status", body, token)
        if (code == 401) {   // token revocat / reinstalare pe alt aparat: o singura reinregistrare
            token = register(ctx, status)
            val again = post("/api/client/crm-status", body, token); code = again.first; r = again.second
        }
        if (!r.optBoolean("ok")) throw IOException(r.optString("error").ifBlank { "HTTP $code" })
        Log.d(TAG, "Stare trimisa in CRM pentru $serial")
    }
}
