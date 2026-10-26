package com.itsmart.datecs.client.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.itsmart.datecs.client.device.InternalFiscalBridge
import com.itsmart.datecs.client.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground Service care polezza CRM-ul la fiecare POLL_INTERVAL_MS.
 *
 * Pentru fiecare serial inregistrat in [seenSerials] (alimentat din MainViewModel la
 * conectare la aparat) interogheaza GET /api/agent/pending/{serial}. Daca apar comenzi
 * noi (id necunoscut anterior) → afiseaza notificare heads-up cu sunet si trimite un
 * broadcast pe care MainActivity il ridica si declanseaza executia automat.
 *
 * NB: nu deschide conexiunea la aparat din service — depinde de UI ca sa stie ce serial
 * e conectat si sa ruleze comenzile. Serviciul e doar "poll + alerteaza".
 */
class AgentPollingService : Service() {
    companion object {
        private const val TAG = "AgentPollingService"
        private const val POLL_INTERVAL_MS = 60_000L
        /** BlueCash: starea in CRM o data pe zi; daca modulul e ocupat, reincercare la 10 minute. */
        private const val STATUS_RETRY_MS = 10 * 60_000L

        const val ACTION_NEW_COMMAND = "com.itsmart.datecs.client.NEW_COMMAND"
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_CMD_ID = "cmd_id"
        const val EXTRA_CMD_TYPE = "cmd_type"
        const val EXTRA_PAYLOAD_JSON = "payload_json"

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SET_SERIAL = "set_serial"
        const val EXTRA_NEW_SERIAL = "new_serial"

        // Setat din MainViewModel cand user-ul se conecteaza la un aparat. Multi-instanta
        // (BC50 + extern) — toate serial-urile cunoscute sunt polezate periodic.
        @Volatile var activeSerial: String = ""

        // ID-uri de comenzi deja procesate (raportate sau in curs) — evitam re-notificare.
        private val seenCmdIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun markCommandHandled(commandId: String) { if (commandId.isNotBlank()) seenCmdIds.add(commandId) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_SERIAL -> {
                val s = intent.getStringExtra(EXTRA_NEW_SERIAL) ?: ""
                activeSerial = s
                Log.d(TAG, "activeSerial set to '$s'")
            }
        }

        NotificationHelper.ensureChannels(this)   // si la pornirea dupa boot, fara MainActivity
        val notification = NotificationHelper.foregroundNotification(
            this,
            if (activeSerial.isNotBlank()) "Monitorizez comenzi pentru $activeSerial"
            else "Astept conectare la aparat..."
        )
        try {
            startForeground(NotificationHelper.NOTIF_ID_FOREGROUND, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground EROARE: ${t.message}")
        }

        if (pollJob == null || pollJob?.isActive != true) startPollingLoop()
        return START_STICKY
    }

    private fun startPollingLoop() {
        pollJob = scope.launch {
            var nextStatusAt = System.currentTimeMillis() + 60_000L   // prima citire la un minut dupa pornire
            while (isActive) {
                try {
                    // BlueCash: starea casei in CRM o data pe zi, indiferent de aplicatia de vanzare deschisa
                    val today = java.time.LocalDate.now().toString()
                    val prefs = getSharedPreferences("tda_server", MODE_PRIVATE)
                    if (InternalFiscalBridge.isAvailable() && prefs.getString("monitor_day", "") != today &&
                        System.currentTimeMillis() >= nextStatusAt) {
                        nextStatusAt = System.currentTimeMillis() + when (val r = BlueCashMonitor.runOnce(this@AgentPollingService)) {
                            is BlueCashMonitor.Result.Sent -> {
                                if (activeSerial.isBlank()) activeSerial = r.serial   // comenzile CRM pentru casa interna
                                prefs.edit().putString("monitor_day", today).apply()
                                saveMonitorState("Stare trimisa in CRM (${r.serial})")
                                STATUS_RETRY_MS
                            }
                            is BlueCashMonitor.Result.Busy -> { saveMonitorState("Amanat: ${r.reason}"); STATUS_RETRY_MS }
                            is BlueCashMonitor.Result.Failed -> { saveMonitorState("Eroare: ${r.reason}"); STATUS_RETRY_MS * 3 }
                        }
                    }
                    val serial = activeSerial
                    if (serial.isNotBlank()) pollOnce(serial)
                } catch (t: Throwable) {
                    Log.w(TAG, "poll iter EROARE: ${t.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun saveMonitorState(text: String) {
        val t = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date())
        getSharedPreferences("tda_server", MODE_PRIVATE).edit().putString("monitor_state", "$t — $text").apply()
        Log.d(TAG, "Monitor: $text")
    }

    private fun pollOnce(serial: String) {
        try {
            val urlStr = "${MainViewModel.CRM_BASE_URL}/api/agent/pending/" +
                java.net.URLEncoder.encode(serial, "UTF-8")
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            if (code !in 200..299) {
                if (code != 404) Log.d(TAG, "poll $serial: HTTP $code")
                return
            }
            val body = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
            if (body.isBlank() || body.trim() == "[]") return

            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val cmd = arr.optJSONObject(i) ?: continue
                val id = cmd.opt("id")?.toString() ?: continue
                if (id.isBlank() || id == "0") continue
                if (!seenCmdIds.add(id)) continue // deja notificat
                val typeStr = cmd.optString("type", "").uppercase().trim()
                val payload = cmd.optJSONObject("payload") ?: JSONObject()

                Log.d(TAG, "Comanda noua: id=$id type=$typeStr serial=$serial")
                notifyAndDispatch(id, typeStr, serial, payload)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pollOnce EROARE: ${t.message}")
        }
    }

    private fun notifyAndDispatch(id: String, type: String, serial: String, payload: JSONObject) {
        val title = when (type) {
            "REVIZIE", "SET_SERVICEDATE" -> "Comanda noua: REVIZIE"
            "EXPORT_P7B" -> "Comanda noua: Export ANAF"
            "EXPORT_MF_SUMAR", "EXPORT_MF_DETALIAT", "EXPORT_MF_DET" -> "Comanda noua: Raport MF"
            "VERIFICARE_ANAF", "CHECK_ANAF", "CITIRE_DETALIATA", "READ_STATUS" -> "Comanda noua: Verificare ANAF"
            "TIPARIRE_Z", "PRINT_Z" -> "Comanda noua: Tiparire Z"
            "MODIFICARE_TVA", "MODIFY_TVA" -> "Comanda noua: Modificare TVA"
            "MODIFICARE_ANTET", "MODIFY_HEADER" -> "Comanda noua: Modificare antet"
            else -> "Comanda noua: $type"
        }
        val body = buildString {
            appendLine("Aparat: $serial")
            val obs = payload.optString("observatii", "")
            if (obs.isNotBlank()) appendLine(obs)
            val sd = payload.optString("service_date", "")
            if (sd.isNotBlank()) appendLine("Service date: $sd")
            appendLine("Apasati pentru a executa.")
        }.trim()
        NotificationHelper.showCommandAvailable(this, title, body)

        // Apel DIRECT la ViewModel (bypass broadcast care era pierdut intermittent).
        // Daca VM nu e activ (app fortat-stopped), fallback la broadcast pentru cazul
        // cand MainActivity totusi asculta.
        val vm = MainViewModel.activeInstance
        if (vm != null) {
            Log.d(TAG, "VM activ — apel direct onIncomingCrmCommand id=$id")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    vm.onIncomingCrmCommand(id, type, serial, payload.toString())
                } catch (t: Throwable) {
                    Log.w(TAG, "VM.onIncomingCrmCommand EROARE: ${t.message}")
                }
            }
        } else {
            Log.d(TAG, "VM inactiv — fallback broadcast")
            val intent = Intent(ACTION_NEW_COMMAND).apply {
                setPackage(packageName)
                putExtra(EXTRA_CMD_ID, id)
                putExtra(EXTRA_CMD_TYPE, type)
                putExtra(EXTRA_SERIAL, serial)
                putExtra(EXTRA_PAYLOAD_JSON, payload.toString())
            }
            sendBroadcast(intent)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        pollJob?.cancel(); pollJob = null
        scope.cancel()
        super.onDestroy()
    }
}
