package com.itsmart.datecs.client.viewmodel

import android.app.Application
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.itsmart.datecs.client.data.model.*
import com.itsmart.datecs.client.data.model.ConnectionType
import com.itsmart.datecs.client.device.AnafDownloadResult
import com.itsmart.datecs.client.device.AnafDownloader
import com.itsmart.datecs.client.device.DudeDeviceConnection
import com.itsmart.datecs.client.device.DeviceScanner
import com.itsmart.datecs.client.device.ScannedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Endpoint CRM unificat cu StatusAMEF (Windows) — API V2.
        // Polling: GET  {CRM_BASE_URL}/api/agent/pending/{serie_aparat}
        // ACK:     POST {CRM_BASE_URL}/api/agent/ack/{command_id}  body {"status":"in_progress|done|failed","result":"..."}
        const val CRM_BASE_URL = "https://crm.itsmartretail.ro"

        /** Referinta la VM-ul curent — folosita de AgentPollingService pentru auto-execute. */
        @Volatile var activeInstance: MainViewModel? = null
    }

    private val connection: DudeDeviceConnection = DudeDeviceConnection(application)
    private val scanner: DeviceScanner = DeviceScanner(application)

    /** ACK V2 — confirma starea executiei comenzii catre CRM. */
    private suspend fun ackCommandV2(commandId: String, status: String, resultMsg: String? = null): Boolean {
        if (commandId.isEmpty() || commandId == "0") return false
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val url = java.net.URL("$CRM_BASE_URL/api/agent/ack/${java.net.URLEncoder.encode(commandId, "UTF-8")}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true; conn.connectTimeout = 10000; conn.readTimeout = 10000
                val json = org.json.JSONObject().apply {
                    put("status", status)
                    if (!resultMsg.isNullOrBlank()) put("result", resultMsg)
                }
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode in 200..299
            }
        } catch (e: Exception) {
            addLog("CRM", "ACK V2 [$commandId/$status] EROARE", e.message, false)
            false
        }
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()
    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()
    private val _logs = MutableStateFlow<List<CommandLog>>(emptyList())
    val logs: StateFlow<List<CommandLog>> = _logs.asStateFlow()
    private val _toastMessage = MutableStateFlow<Pair<String, String>?>(null)
    val toastMessage: StateFlow<Pair<String, String>?> = _toastMessage.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()
    private val _connectedDeviceName = MutableStateFlow("")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()
    private val _connectedAddress = MutableStateFlow("")
    val connectedAddress: StateFlow<String> = _connectedAddress.asStateFlow()
    private val _connectedType = MutableStateFlow(ConnectionType.USB)
    val connectedType: StateFlow<ConnectionType> = _connectedType.asStateFlow()

    // Auth
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    private val _loginLoading = MutableStateFlow(false)
    val loginLoading: StateFlow<Boolean> = _loginLoading.asStateFlow()
    private val _loginError = MutableStateFlow("")
    val loginError: StateFlow<String> = _loginError.asStateFlow()
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    // CRM (silent)
    private val _crmSending = MutableStateFlow(false)
    val crmSending: StateFlow<Boolean> = _crmSending.asStateFlow()
    private val _crmResult = MutableStateFlow("")
    val crmResult: StateFlow<String> = _crmResult.asStateFlow()

    // Pending CRM commands / Messages
    data class PendingRevizie(val id: Int = 0, val serviceDate: String = "", val observatii: String = "")
    private val _pendingRevizie = MutableStateFlow<PendingRevizie?>(null)
    val pendingRevizie: StateFlow<PendingRevizie?> = _pendingRevizie.asStateFlow()
    private val _revizieResult = MutableStateFlow<RevizieResult?>(null)
    val revizieResult: StateFlow<RevizieResult?> = _revizieResult.asStateFlow()
    private val _revizieRunning = MutableStateFlow(false)
    val revizieRunning: StateFlow<Boolean> = _revizieRunning.asStateFlow()

    // CRM Messages
    private val _crmMessages = MutableStateFlow<List<CrmMessage>>(emptyList())
    val crmMessages: StateFlow<List<CrmMessage>> = _crmMessages.asStateFlow()
    private val _crmInvoices = MutableStateFlow<List<CrmInvoice>>(emptyList())
    val crmInvoices: StateFlow<List<CrmInvoice>> = _crmInvoices.asStateFlow()
    private val _unreadMessageCount = MutableStateFlow(0)
    val unreadMessageCount: StateFlow<Int> = _unreadMessageCount.asStateFlow()

    // ANAF download
    private val _anafDownloadProgress = MutableStateFlow("")
    val anafDownloadProgress: StateFlow<String> = _anafDownloadProgress.asStateFlow()
    private val _anafDownloading = MutableStateFlow(false)
    val anafDownloading: StateFlow<Boolean> = _anafDownloading.asStateFlow()
    private val _lastAnafResult = MutableStateFlow<AnafDownloadResult?>(null)
    val lastAnafResult: StateFlow<AnafDownloadResult?> = _lastAnafResult.asStateFlow()

    // Cloud upload
    private val _cloudUploading = MutableStateFlow(false)
    val cloudUploading: StateFlow<Boolean> = _cloudUploading.asStateFlow()
    private val _cloudResult = MutableStateFlow("")
    val cloudResult: StateFlow<String> = _cloudResult.asStateFlow()
    // Asocierea cu cloud.itsmart.ro (token propriu al acestei instalari)
    private val cloud = com.itsmart.datecs.client.service.CloudUploadService(application)
    private val _cloudLink = MutableStateFlow(cloud.link())
    val cloudLink: StateFlow<com.itsmart.datecs.client.service.CloudUploadService.Link> = _cloudLink.asStateFlow()
    private val _cloudPairing = MutableStateFlow(false)
    val cloudPairing: StateFlow<Boolean> = _cloudPairing.asStateFlow()
    private val _cloudPairMessage = MutableStateFlow("")
    val cloudPairMessage: StateFlow<String> = _cloudPairMessage.asStateFlow()

    // Inrolare din aplicatie (cod fiscal + email + telefon): aplicatia se foloseste doar cu contul activ (platit)
    private val _account = MutableStateFlow(if (cloud.enrolled) cloud.cachedAccount() else null)
    val account: StateFlow<com.itsmart.datecs.client.service.CloudUploadService.Account?> = _account.asStateFlow()
    private val _enrollLoading = MutableStateFlow(false)
    val enrollLoading: StateFlow<Boolean> = _enrollLoading.asStateFlow()
    /** Transfer automat: fisierele XML descarcate de pe aparat se trimit singure in cloud (Acasa / Fisiere). */
    private val _autoUpload = MutableStateFlow(getApplication<Application>().getSharedPreferences("datecs_client", 0).getBoolean("auto_upload", true))
    val autoUpload: StateFlow<Boolean> = _autoUpload.asStateFlow()
    fun setAutoUpload(on: Boolean) {
        getApplication<Application>().getSharedPreferences("datecs_client", 0).edit().putBoolean("auto_upload", on).apply()
        _autoUpload.value = on
    }

    /** Emailul contabilului (Setari): cloud-ul ii trimite automat fisierele XML urcate. */
    private val _accountantEmail = MutableStateFlow("")
    val accountantEmail: StateFlow<String> = _accountantEmail.asStateFlow()
    private val _accountantMsg = MutableStateFlow("")
    val accountantMsg: StateFlow<String> = _accountantMsg.asStateFlow()

    fun loadAccountant() {
        viewModelScope.launch {
            val (e, err) = cloud.accountant()
            if (e != null) _accountantEmail.value = e
            _accountantMsg.value = err
        }
    }

    fun saveAccountant(email: String) {
        viewModelScope.launch {
            _accountantMsg.value = "Se salveaza..."
            val (e, err) = cloud.accountant(email.trim())
            if (e != null) { _accountantEmail.value = e; _accountantMsg.value = if (e.isBlank()) "Trimiterea la contabil este oprita" else "Salvat" }
            else _accountantMsg.value = err
            addLog("CLOUD", "Email contabil", e ?: err, e != null)
        }
    }

    // Fisierele firmei din cloud (ecranul Fisiere)
    private val _cloudFiles = MutableStateFlow<List<com.itsmart.datecs.client.service.CloudUploadService.CloudFile>>(emptyList())
    val cloudFiles: StateFlow<List<com.itsmart.datecs.client.service.CloudUploadService.CloudFile>> = _cloudFiles.asStateFlow()
    private val _cloudFilesState = MutableStateFlow("")   // "" = gata, "loading", altfel mesajul de eroare
    val cloudFilesState: StateFlow<String> = _cloudFilesState.asStateFlow()

    fun loadCloudFiles() {
        if (_cloudFilesState.value == "loading") return
        viewModelScope.launch {
            _cloudFilesState.value = "loading"
            val (files, err) = cloud.listFiles()
            if (files != null) _cloudFiles.value = files
            _cloudFilesState.value = err
        }
    }

    private val _accountNotice = MutableStateFlow("")
    val accountNotice: StateFlow<String> = _accountNotice.asStateFlow()
    private val _enrollError = MutableStateFlow("")
    val enrollError: StateFlow<String> = _enrollError.asStateFlow()

    // Report preview
    private val _reportPreviewText = MutableStateFlow("")
    val reportPreviewText: StateFlow<String> = _reportPreviewText.asStateFlow()
    private val _reportPreviewLoading = MutableStateFlow(false)
    val reportPreviewLoading: StateFlow<Boolean> = _reportPreviewLoading.asStateFlow()

    // LAN History
    data class LanHistoryEntry(val address: String, val name: String, val info: String, val lastUsed: Long)
    private val _lanHistory = MutableStateFlow<List<LanHistoryEntry>>(emptyList())
    val lanHistory: StateFlow<List<LanHistoryEntry>> = _lanHistory.asStateFlow()

    init {
        _lanHistory.value = loadLanHistory()
        val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
        val savedToken = prefs.getString("auth_token", "") ?: ""
        val savedUser = prefs.getString("auth_user", "") ?: ""
        if (savedToken.isNotEmpty()) {
            _isLoggedIn.value = true
            _username.value = savedUser
        }
        // Inregistrez aceasta instanta ca VM "activ" pentru AgentPollingService.
        // Cand service-ul detecteaza o comanda noua, va apela direct VM.onIncomingCrmCommand
        // (bypass broadcast — broadcast-ul era pierdut intermittent).
        activeInstance = this
        // asocierea poate fi revocata din portal: se verifica la pornire (fara internet ramane cum era)
        // (instalarile inrolate din aplicatie au firma inactiva pana la plata: starea lor vine din refreshAccount)
        if (cloud.enrolled) { _isLoggedIn.value = true; _username.value = cloud.cachedAccount().email; refreshAccount() }
        else if (pairLogin && _cloudLink.value.paired) { _isLoggedIn.value = true; _username.value = _cloudLink.value.companyName
            viewModelScope.launch { cloud.whoami(); _cloudLink.value = cloud.link(); if (!_cloudLink.value.paired) logout() } }
        else if (_cloudLink.value.paired) viewModelScope.launch { cloud.whoami(); _cloudLink.value = cloud.link() }
    }

    override fun onCleared() {
        if (activeInstance === this) activeInstance = null
        super.onCleared()
    }

    fun addLog(tag: String, cmd: String, res: String?, ok: Boolean) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _logs.value = (_logs.value + CommandLog(time, tag, cmd, res, ok)).takeLast(200)
    }

    fun showToast(message: String, type: String = "success") {
        _toastMessage.value = Pair(message, type)
        viewModelScope.launch { kotlinx.coroutines.delay(2500); _toastMessage.value = null }
    }

    // ==================== AUTH ====================

    fun login(user: String, password: String) {
        viewModelScope.launch {
            _loginLoading.value = true
            _loginError.value = ""
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val url = java.net.URL("https://cloud.itsmart.ro/login")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        conn.instanceFollowRedirects = false
                        conn.doOutput = true
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        val formData = "email=${java.net.URLEncoder.encode(user, "UTF-8")}&password=${java.net.URLEncoder.encode(password, "UTF-8")}"
                        conn.outputStream.use { it.write(formData.toByteArray(Charsets.UTF_8)) }
                        val code = conn.responseCode
                        val location = conn.getHeaderField("Location") ?: ""
                        val cookies = conn.headerFields["Set-Cookie"] ?: emptyList()
                        val sessionCookie = cookies.firstOrNull { it.contains("session") || it.contains("token") }
                            ?.substringBefore(";") ?: ""
                        Triple(code, location, sessionCookie)
                    } catch (_: java.net.UnknownHostException) { Triple(-1, "offline", "") }
                    catch (_: java.net.ConnectException) { Triple(-1, "offline", "") }
                    catch (_: java.net.SocketTimeoutException) { Triple(-1, "offline", "") }
                    catch (_: javax.net.ssl.SSLException) { Triple(-1, "offline", "") }
                    catch (_: java.io.IOException) { Triple(-1, "offline", "") }
                }
                val (code, location, sessionCookie) = result
                if (code == -1) {
                    // fara internet: doar un cont care s-a autentificat online pe acest aparat (hash local)
                    if (offlineCredentialsMatch(user, password)) {
                        val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
                        prefs.edit().putString("auth_token", "offline").putString("auth_user", user).apply()
                        _username.value = user; _isLoggedIn.value = true
                        addLog("AUTH", "Login OFFLINE", "Server indisponibil, cont verificat local", true)
                        showToast("Mod offline — server indisponibil", "warning")
                    } else {
                        _loginError.value = "Fara conexiune la cloud.itsmart.ro. Prima autentificare pe acest aparat necesita internet."
                        addLog("AUTH", "Login OFFLINE refuzat", "cont neverificat online pe acest aparat", false)
                    }
                } else if (code == 302 && location.contains("error")) {
                    val errorMsg = try { java.net.URLDecoder.decode(location.substringAfter("error=", "").substringBefore("&"), "UTF-8") } catch (_: Exception) { "" }
                    _loginError.value = errorMsg.ifEmpty { "Email sau parola incorecta" }
                    addLog("AUTH", "Login ESUAT", "Redirect: $location", false)
                } else if (code == 302 && !location.contains("error")) {
                    val token = sessionCookie.ifEmpty { "session_ok" }
                    val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
                    prefs.edit().putString("auth_token", token).putString("auth_user", user).apply()
                    rememberOfflineCredentials(user, password)
                    _username.value = user; _isLoggedIn.value = true
                    addLog("AUTH", "Login OK", "user=$user", true)
                } else {
                    // cloud.itsmart.ro raspunde cu redirect si la succes, si la eroare: orice altceva nu e autentificare
                    _loginError.value = "Eroare server (HTTP $code)"
                    addLog("AUTH", "Login ESUAT", "HTTP $code loc=$location", false)
                }
            } catch (e: Exception) {
                _loginError.value = "Autentificare esuata: ${e.message?.take(80)}"
                addLog("AUTH", "Login ESUAT", e.message, false)
            }
            _loginLoading.value = false
        }
    }

    /** Dupa un login online reusit: hash PBKDF2 (cu sare) pentru autentificarea fara internet. Parola nu se salveaza. */
    private fun rememberOfflineCredentials(user: String, password: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        getApplication<Application>().getSharedPreferences("datecs_client", 0).edit()
            .putString("offline_user", user.trim().lowercase())
            .putString("offline_salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString("offline_hash", android.util.Base64.encodeToString(pbkdf2(password, salt), android.util.Base64.NO_WRAP))
            .apply()
    }

    private fun offlineCredentialsMatch(user: String, password: String): Boolean {
        val p = getApplication<Application>().getSharedPreferences("datecs_client", 0)
        if (p.getString("offline_user", "") != user.trim().lowercase()) return false
        val salt = android.util.Base64.decode(p.getString("offline_salt", "") ?: "", android.util.Base64.NO_WRAP)
        val hash = android.util.Base64.decode(p.getString("offline_hash", "") ?: "", android.util.Base64.NO_WRAP)
        return salt.isNotEmpty() && hash.isNotEmpty() && java.security.MessageDigest.isEqual(hash, pbkdf2(password, salt))
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 20_000, 256)).encoded

    // ==================== INROLARE ====================

    fun enroll(cui: String, email: String, phone: String) {
        if (_enrollLoading.value) return
        viewModelScope.launch {
            _enrollLoading.value = true; _enrollError.value = ""
            val r = cloud.enroll(cui, email, phone)
            _enrollLoading.value = false
            if (!r.ok) { _enrollError.value = r.message; addLog("CLOUD", "Inrolare esuata", r.message, false); return@launch }
            addLog("CLOUD", "Inrolare", r.message, true)
            _cloudLink.value = cloud.link()
            _account.value = cloud.cachedAccount()
            _username.value = email.trim().lowercase()
            _isLoggedIn.value = true
        }
    }

    /** Starea contului (confirmare email / plata / activ); se apeleaza la pornire si din ecranul de stare. */
    fun refreshAccount() {
        if (!cloud.enrolled) return
        viewModelScope.launch {
            val a = cloud.account()
            _accountNotice.value = ""
            _cloudLink.value = cloud.link()
            if (a.status == "none") { _account.value = null; _isLoggedIn.value = false; _loginError.value = a.error; return@launch }
            _account.value = a
        }
    }

    fun resendConfirmation() {
        viewModelScope.launch {
            _accountNotice.value = cloud.resendConfirmation().message
        }
    }

    /** Renunta la inrolarea de pe acest aparat (alta firma / alt email). */
    fun leaveEnrollment() {
        cloud.forget()
        _cloudLink.value = cloud.link()
        _account.value = null
        logout()
    }

    fun logout() {
        val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
        prefs.edit().remove("auth_token").remove("auth_user").apply()
        // aparatul inrolat din aplicatie nu are parola: iesirea inseamna renuntarea la inrolare (se poate relua)
        if (cloud.enrolled || pairLogin) { cloud.forget(); _cloudLink.value = cloud.link(); _account.value = null; pairLogin = false }
        _isLoggedIn.value = false; _username.value = ""
    }

    private fun getAuthToken(): String {
        val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
        return prefs.getString("auth_token", "") ?: ""
    }

    // ==================== SCAN & CONNECT ====================

    fun scanDevices() {
        try { _scannedDevices.value = scanner.scanAll() }
        catch (e: Exception) { Log.e("MainVM", "scanDevices error: ${e.message}", e) }
    }

    fun connectDevice(address: String, name: String, type: ConnectionType) {
        viewModelScope.launch {
            _isConnecting.value = true
            _deviceState.value = DeviceState()
            addLog(type.name, "Conectare la $name...", address, true)
            try {
                if (type == ConnectionType.LAN) saveLanHistory(address, name)
                val ok = connection.connect(address, type)
                if (ok) {
                    val model = connection.modelName
                    _connectedDeviceName.value = model
                    _connectedAddress.value = address
                    _connectedType.value = type
                    _connected.value = true
                    _deviceState.value = _deviceState.value.copy(devName = model, model = model)
                    showToast("Conectat la $model!", "success")
                    _isLoading.value = true
                    refreshStatusInternal()
                    _isLoading.value = false
                    if (type == ConnectionType.LAN) {
                        val hdrs = _deviceState.value.headers
                        val clientName = hdrs.firstOrNull { it.isNotEmpty() } ?: ""
                        val addressLine = hdrs.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
                        if (clientName.isNotEmpty()) saveLanHistory(address, clientName, addressLine)
                    }
                    // Auto CRM + auto check comenzi (silent)
                    reportStatusToCrmSilent()
                    checkCrmCommands()
                    fetchCrmMessages()
                    // Notifica AgentPollingService despre aparatul activ — va polezza la 60s
                    // chiar daca aplicatia trece in background.
                    startAgentPollingForSerial(_deviceState.value.serialNumber)
                } else {
                    showToast(if (type == ConnectionType.INTERNAL) DudeDeviceConnection.BUSY_MSG else "Conectare esuata! Verificati aparatul.", "error")
                }
            } catch (e: Exception) {
                showToast("Eroare: ${e.message}", "error")
            }
            _isConnecting.value = false
        }
    }

    fun disconnectDevice() {
        viewModelScope.launch {
            connection.disconnect()
            _connected.value = false
            _connectedDeviceName.value = ""
            _connectedAddress.value = ""
            _deviceState.value = DeviceState()
            showToast("Deconectat", "warning")
            // Opresc polling-ul cand nu mai am aparat conectat — service-ul ramane
            // pornit dar nu mai face GET-uri (activeSerial = "").
            startAgentPollingForSerial("")
        }
    }

    /**
     * Porneste sau actualizeaza AgentPollingService cu noul serial activ.
     * Numit pe connect/disconnect. Service-ul ramane in background si reincepe automat
     * polling-ul dupa REBOOT (cu START_STICKY).
     */
    private fun startAgentPollingForSerial(serial: String) {
        try {
            val ctx = getApplication<Application>()
            val intent = android.content.Intent(ctx,
                com.itsmart.datecs.client.service.AgentPollingService::class.java
            ).apply {
                action = com.itsmart.datecs.client.service.AgentPollingService.ACTION_SET_SERIAL
                putExtra(com.itsmart.datecs.client.service.AgentPollingService.EXTRA_NEW_SERIAL, serial)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            addLog("AGENT", "startAgentPolling EROARE", e.message, false)
        }
    }

    /**
     * Apelat de MainActivity la primirea broadcast-ului ACTION_NEW_COMMAND.
     * Aplica payload-ul ca pending si declanseaza executia automat daca tipul e REVIZIE.
     */
    fun onIncomingCrmCommand(commandId: String, type: String, serial: String, payloadJson: String) {
        android.util.Log.d("MainVM", "onIncomingCrmCommand: id=$commandId type=$type serial=$serial connected=${_connected.value} running=${_revizieRunning.value} deviceSerial='${_deviceState.value.serialNumber}'")
        if (isCommandRecentlyTried(commandId)) {
            android.util.Log.d("MainVM", "Comanda $commandId deja procesata recent (24h) — skip")
            addLog("AGENT", "Comanda $commandId deja procesata recent (24h) — skip", null, true)
            return
        }
        if (serial.isNotBlank() && _deviceState.value.serialNumber.isNotEmpty() &&
            !serial.equals(_deviceState.value.serialNumber, ignoreCase = true)) {
            android.util.Log.d("MainVM", "Comanda $commandId pt alt serial — skip")
            addLog("AGENT", "Comanda primita pt alt serial ($serial vs ${_deviceState.value.serialNumber}) — skip",
                null, false)
            return
        }
        val payload = try { org.json.JSONObject(payloadJson) } catch (_: Exception) { org.json.JSONObject() }
        val typeUp = type.uppercase().trim()
        if (typeUp == "REVIZIE" || typeUp == "SET_SERVICEDATE") {
            val rawSd = payload.optString("service_date", "")
            val effectiveSd = if (rawSd.isNotBlank()) rawSd else {
                val cal = Calendar.getInstance().apply {
                    add(Calendar.YEAR, 1); set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            }
            _pendingRevizie.value = PendingRevizie(
                id = commandId.toIntOrNull() ?: 0,
                serviceDate = effectiveSd,
                observatii = payload.optString("observatii", "")
            )
            if (_connected.value && !_revizieRunning.value) {
                android.util.Log.d("MainVM", "AUTO-EXECUTE REVIZIE id=$commandId")
                addLog("AGENT", "Comanda REVIZIE primita — auto-execute", "id=$commandId", true)
                executeCrmRevizie()
            } else {
                android.util.Log.d("MainVM", "REVIZIE NU se executa: connected=${_connected.value} running=${_revizieRunning.value}")
                addLog("AGENT", "Comanda REVIZIE primita — astept conectare", "id=$commandId", true)
            }
        } else {
            addLog("AGENT", "Comanda $typeUp primita (handler ne-implementat in app)", "id=$commandId", false)
        }
        fetchCrmMessages()
    }

    // ==================== STATUS ====================

    fun refreshStatus() {
        viewModelScope.launch { _isLoading.value = true; refreshStatusInternal(); _isLoading.value = false }
    }

    private suspend fun refreshStatusInternal() {
        try {
            val r90 = connection.executeCommand(90)
            addLog("CMD90", "command90V0V1()", r90.rawOutput, r90.success)
            val r90name = connection.executeCommand(90, "v0")
            val r90fm = try { connection.executeCommand(90, "v0,0") } catch (_: Exception) { CommandResult(false, -1, "", emptyMap()) }
            val r62 = connection.executeCommand(62)

            val varNames = listOf("nZreport", "nBon", "EcrMode", "Fiscalized", "IDnumber", "FMnumber", "ServPasw",
                "LastConnectionDate", "LastConnectionState", "LastConnectionErrorCode", "LastConnectionZrepNum",
                "ServiceDate", "LAN_IP", "LAN_NetMask", "LAN_Gateway", "NetInterfaceToUse", "FmWriteDateTime")
            val varValues = mutableMapOf<String, String>()
            for (v in varNames) {
                try { varValues[v] = connection.cmd255Read(v, "") } catch (_: Exception) {}
            }

            val vatRates = mutableListOf<String>()
            for (i in 0..4) {
                try {
                    val v = connection.cmd255Read("valVat", i.toString())
                    val rawInt = v.trim().toIntOrNull() ?: 0
                    vatRates.add(if (rawInt == 0 || rawInt > 10000) "0.00" else "%.2f".format(rawInt / 100.0))
                } catch (_: Exception) { vatRates.add("0.00") }
            }

            val headers = mutableListOf<String>()
            // "^" = litera urmatoare cu dubla latime (antet DP-05L etc.): doar formatare de tiparire, nu text
            for (i in 0..5) { try { headers.add(connection.cmd255Read("Header", i.toString()).replace("^", "").trim()) } catch (_: Exception) { headers.add("") } }

            // === CIF ROBUST (port din ServiceUtility) ===
            val cifNumber = readCifRobust(headers)

            val allFields90 = r90.fields
            val serialNum = varValues["IDnumber"].orEmpty().ifEmpty { allFields90["DevNumber"] ?: "" }
            val fiscalNum = varValues["FMnumber"].orEmpty().ifEmpty { r90fm.fields["fMNumber"] ?: allFields90["fMNumber"] ?: "" }
            val fmNum = r90fm.fields["fMNumber"] ?: allFields90["fMNumber"] ?: ""
            val devName90 = r90name.fields["Name"] ?: allFields90["DevName"] ?: ""
            val fwVersion = buildString {
                val major = allFields90["Major"] ?: ""; val minor = allFields90["Minor"] ?: ""; val build = allFields90["Build"] ?: ""
                if (major.isNotEmpty()) { append(major); if (minor.isNotEmpty()) append(".$minor"); if (build.isNotEmpty()) append(".$build") }
            }
            val dateTimeValue = r62.fields.values.firstOrNull { it.isNotEmpty() } ?: r62.rawOutput.takeIf { it.isNotEmpty() && !it.contains("=") } ?: ""

            _deviceState.value = _deviceState.value.copy(
                devName = connection.modelName.ifEmpty { devName90 },
                model = connection.modelName.ifEmpty { _deviceState.value.model },
                // BlueCash 50/500 raporteaza DevType=2, dar sunt case de marcat
                devType = if (connection.modelName.startsWith("BC-")) "1" else allFields90["DevType"] ?: _deviceState.value.devType,
                serialNumber = serialNum.ifEmpty { _deviceState.value.serialNumber },
                fiscalNumber = fiscalNum.ifEmpty { _deviceState.value.fiscalNumber },
                fmNumber = fmNum.ifEmpty { fiscalNum },
                fwRev = fwVersion.ifEmpty { _deviceState.value.fwRev },
                country = allFields90["Country"] ?: "",
                deviceDateTime = dateTimeValue,
                lastConnectionDate = varValues["LastConnectionDate"].orEmpty(),
                lastConnectionState = varValues["LastConnectionState"].orEmpty(),
                lastConnectionErrorCode = varValues["LastConnectionErrorCode"].orEmpty(),
                lastConnectionZrepNum = varValues["LastConnectionZrepNum"].orEmpty(),
                nZreport = varValues["nZreport"].orEmpty(),
                nBon = varValues["nBon"].orEmpty(),
                ecrMode = varValues["EcrMode"].orEmpty().ifEmpty { "0" },
                fiscalized = varValues["Fiscalized"].orEmpty(),
                servicePassword = varValues["ServPasw"].orEmpty(),
                serviceDate = varValues["ServiceDate"].orEmpty(),
                vatRates = vatRates,
                headers = headers,
                lanIp = varValues["LAN_IP"].orEmpty(),
                lanNetmask = varValues["LAN_NetMask"].orEmpty(),
                lanGateway = varValues["LAN_Gateway"].orEmpty(),
                netInterface = varValues["NetInterfaceToUse"].orEmpty(),
                fiscalizationDate = varValues["FmWriteDateTime"].orEmpty(),
                cifNumber = cifNumber.ifEmpty { _deviceState.value.cifNumber },
            )
        } catch (e: Exception) {
            addLog("ERROR", "refreshStatus()", e.message, false)
            showToast("Eroare citire: ${e.message}", "error")
        }
    }

    // === CIF/CUI ROBUST — multi-strategy ===
    private suspend fun readCifRobust(headers: List<String>): String {
        var cifNumber = ""
        val cifRegex = Regex("[A-Z]{0,2}\\d{5,13}")

        // Strategy 1: CMD 123 Variant 1 + Variant 0
        try {
            val r123v1 = connection.executeCommand(123, "1")
            addLog("CMD123", "Variant1 fields", r123v1.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }, r123v1.success)
            val r123v0 = connection.executeCommand(123, "")
            addLog("CMD123", "Variant0 fields", r123v0.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }, r123v0.success)

            val allCmd123Fields = r123v1.fields + r123v0.fields

            // Cautare dupa nume camp
            for ((key, value) in allCmd123Fields) {
                val k = key.lowercase()
                if (k.contains("cif") || k.contains("cui") || k.contains("eik") ||
                    k.contains("bulstat") || k.contains("taxid") || k.contains("taxnumber")) {
                    cifNumber = value.trim()
                    addLog("CMD123", "CIF gasit in camp '$key'", cifNumber, true)
                    break
                }
            }

            // Cautare pattern in valori
            if (cifNumber.isEmpty()) {
                for ((_, value) in allCmd123Fields) {
                    val cifMatch = Regex("(?:CIF|CUI)[:\\s]*([A-Z]{0,2}\\d{5,13})", RegexOption.IGNORE_CASE).find(value)
                    if (cifMatch != null) { cifNumber = cifMatch.groupValues[1]; addLog("CMD123", "CIF gasit in valoare", cifNumber, true); break }
                }
            }

            // Cautare format generic
            if (cifNumber.isEmpty()) {
                for ((_, value) in allCmd123Fields) {
                    if (value.isNotEmpty() && cifRegex.matches(value.trim()) && value.trim().length in 5..13) {
                        cifNumber = value.trim(); addLog("CMD123", "CIF din valoare generica", cifNumber, true); break
                    }
                }
            }
        } catch (e: Exception) { addLog("CMD123", "Read CIF", "Eroare: ${e.message}", false) }

        // Strategy 2: Fallback CMD 255
        if (cifNumber.isEmpty()) {
            for (varName in listOf("CIF", "CUI", "ClientCIF", "EIK", "TaxNumber")) {
                try {
                    val v = connection.cmd255Read(varName, "")
                    if (v.isNotEmpty() && v != "0" && v.length >= 5) { cifNumber = v.trim(); addLog("CMD255", "CIF gasit in '$varName'", cifNumber, true); break }
                } catch (_: Exception) {}
            }
        }

        // Strategy 3: Cautare in antet
        if (cifNumber.isEmpty()) {
            val allHeaderText = headers.joinToString(" ")
            val headerMatch = Regex("(?:CIF|CUI|C\\.?I\\.?F\\.?|C\\.?U\\.?I\\.?)[:\\s]*([A-Z]{0,2}\\d{5,13})", RegexOption.IGNORE_CASE).find(allHeaderText)
            if (headerMatch != null) { cifNumber = headerMatch.groupValues[1]; addLog("HEADER", "CIF gasit in antet", cifNumber, true) }
        }

        // Cleanup prefixe
        cifNumber = cifNumber.replace(Regex("^\\s*(?:CIF|CUI|C\\.?I\\.?F\\.?|C\\.?U\\.?I\\.?)\\s*:?\\s*", RegexOption.IGNORE_CASE), "").trim()
        return cifNumber
    }

    // ==================== CRM REPORT (silent, auto) ====================

    private fun reportStatusToCrmSilent() {
        viewModelScope.launch {
            try {
                val ds = _deviceState.value
                if (ds.serialNumber.isEmpty()) return@launch
                val tipAparat = when (ds.devType) { "1" -> "Casa de marcat"; "2" -> "Imprimanta fiscala"; else -> ds.devType }
                val tipConexiune = when (_connectedType.value) { ConnectionType.USB -> "USB"; ConnectionType.BLUETOOTH -> "Serial"; ConnectionType.LAN -> "LAN"; ConnectionType.INTERNAL -> "INTERN" }
                val rapoarteRamase = try { (2500 - (ds.nZreport.toIntOrNull() ?: 0)).toString() } catch (_: Exception) { "" }
                val antetComplet = ds.headers.filter { it.isNotEmpty() }.joinToString(" | ")
                val tvaLinie = ds.vatRates.mapIndexed { i, v -> val rate = v.toDoubleOrNull() ?: 0.0; if (rate > 0) "${('A' + i)}=${"%.2f".format(rate)}%" else "${('A' + i)}=scutit" }.joinToString(", ")
                val (clientName, cif, adresa) = parseHeaderInfo()

                val json = org.json.JSONObject()
                json.put("app_build", "ANDROID_ITSMARTCLIENT_V2")
                json.put("client", clientName); json.put("cif", cif); json.put("adresa", adresa)
                json.put("model_aparat", ds.model); json.put("tip_aparat", tipAparat)
                json.put("serie_aparat", ds.serialNumber); json.put("serie_fiscala", ds.fiscalNumber)
                json.put("data_fiscalizare", ds.fiscalizationDate); json.put("fm_number", ds.fmNumber)
                json.put("lan_ip", ds.lanIp.ifEmpty { if (_connectedType.value == ConnectionType.LAN) _connectedAddress.value.split(":")[0] else "" })
                json.put("lan_netmask", ds.lanNetmask); json.put("lan_gateway", ds.lanGateway)
                json.put("stare", "preluat"); json.put("status", "OK"); json.put("mesaj", "Import IT Smart Client")
                json.put("data_executie", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                json.put("data_urmatoarei_revizii", ds.serviceDate); json.put("parola_service", ds.servicePassword)
                json.put("ultimul_z", ds.nZreport); json.put("rapoarte_ramase", rapoarteRamase); json.put("nzreport", ds.nZreport)
                json.put("tip_conexiune", tipConexiune); json.put("raport_anaf", ds.lastConnectionDate)
                json.put("status_anaf", ds.lastConnectionState); json.put("eroare_anaf", ds.lastConnectionErrorCode)
                json.put("antet_complet", antetComplet); json.put("tva", tvaLinie)
                json.put("observatii", "TVA: $tvaLinie"); json.put("utilizator", _username.value.ifEmpty { "android_client" })

                // CRM-ul cere autentificare: starea pleaca prin serverul TDA Pos (contul CRM e doar pe server)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.itsmart.datecs.client.service.TdaServerClient.sendStatus(getApplication(), json, "IT Smart Client")
                }
                addLog("CRM", "Stare trimisa in CRM", ds.serialNumber, true)
            } catch (e: Exception) {
                addLog("CRM", "Auto-raport EROARE", e.message, false)
            }
        }
    }

    // ==================== CRM COMMANDS ====================

    fun checkCrmCommands() {
        viewModelScope.launch {
            try {
                val serial = _deviceState.value.serialNumber
                if (serial.isEmpty()) return@launch
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("$CRM_BASE_URL/api/agent/pending/${java.net.URLEncoder.encode(serial, "UTF-8")}")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
                    val code = conn.responseCode
                    val body = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                    Pair(code, body)
                }
                val (code, body) = result
                if (code in 200..299 && body.isNotBlank() && body.trim() != "[]") {
                    val arr = org.json.JSONArray(body)
                    // Primul REVIZIE/SET_SERVICEDATE devine pendingRevizie pentru UI banner+buton.
                    for (i in 0 until arr.length()) {
                        val cmd = arr.getJSONObject(i)
                        val typeStr = cmd.optString("type", "").uppercase().trim()
                        if (typeStr == "REVIZIE" || typeStr == "SET_SERVICEDATE") {
                            val cmdId = cmd.optInt("id", 0).toString()
                            // Daca am incercat deja in ultimele 24h (succes/esec), o sarim.
                            if (isCommandRecentlyTried(cmdId)) continue
                            val payload = cmd.optJSONObject("payload") ?: org.json.JSONObject()
                            val rawSd = payload.optString("service_date", "")
                            val effectiveSd = if (rawSd.isNotBlank()) rawSd else {
                                val cal = Calendar.getInstance().apply {
                                    add(Calendar.YEAR, 1)
                                    set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                                }
                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                            }
                            _pendingRevizie.value = PendingRevizie(
                                id = cmd.optInt("id", 0),
                                serviceDate = effectiveSd,
                                observatii = payload.optString("observatii", "")
                            )
                            break
                        }
                    }
                    addLog("CRM", "Check comenzi V2", "${arr.length()} comenzi", true)
                } else if (code == 404) {
                    // 404 = niciun aparat înregistrat in CRM cu acest serial — nu e eroare, doar info.
                    addLog("CRM", "Check comenzi V2", "404 (aparat nu e in CRM)", true)
                }
            } catch (e: Exception) {
                addLog("CRM", "Check comenzi V2 EROARE", e.message, false)
            }
        }
    }

    // ==================== CRM MESSAGES ====================

    fun fetchCrmMessages() {
        viewModelScope.launch {
            try {
                val serial = _deviceState.value.serialNumber
                if (serial.isEmpty()) return@launch
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("$CRM_BASE_URL/api/agent/pending/${java.net.URLEncoder.encode(serial, "UTF-8")}")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
                    val code = conn.responseCode
                    val body = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                    Pair(code, body)
                }
                val (code, body) = result
                if (code in 200..299 && body.isNotBlank() && body.trim() != "[]") {
                    val arr = org.json.JSONArray(body)
                    val messages = mutableListOf<CrmMessage>()
                    for (i in 0 until arr.length()) {
                        val cmd = arr.getJSONObject(i)
                        val typeStr = cmd.optString("type", "").uppercase().trim()
                        val payload = cmd.optJSONObject("payload") ?: org.json.JSONObject()
                        val title = when (typeStr) {
                            "REVIZIE", "SET_SERVICEDATE" -> "Revizie Anuala Programata"
                            "EXPORT_P7B" -> "Export ANAF (.p7b)"
                            "EXPORT_MF_SUMAR" -> "Export MF — Sumar"
                            "EXPORT_MF_DETALIAT", "EXPORT_MF_DET" -> "Export MF — Detaliat"
                            "TIPARIRE_Z", "PRINT_Z" -> "Tiparire Raport Z"
                            "MODIFICARE_TVA", "MODIFY_TVA" -> "Modificare cote TVA"
                            "MODIFICARE_ANTET", "MODIFY_HEADER" -> "Modificare antet"
                            "VERIFICARE_ANAF", "CHECK_ANAF", "CITIRE_DETALIATA", "READ_STATUS" -> "Verificare ANAF"
                            else -> "Notificare ($typeStr)"
                        }
                        val bodyText = buildString {
                            val obs = payload.optString("observatii", "")
                            if (obs.isNotBlank()) appendLine(obs)
                            val sd = payload.optString("service_date", "")
                            if (sd.isNotBlank()) appendLine("Data service: $sd")
                            val zs = if (payload.has("z_start")) payload.optString("z_start") else ""
                            val ze = if (payload.has("z_end")) payload.optString("z_end") else ""
                            if (zs.isNotBlank() || ze.isNotBlank()) appendLine("Z: $zs - $ze")
                            val ds = payload.optString("start_date", "")
                            val de = payload.optString("end_date", "")
                            if (ds.isNotBlank() || de.isNotBlank()) appendLine("Perioada: $ds - $de")
                        }.trim()
                        messages.add(CrmMessage(
                            id = cmd.optInt("id", 0).toString(),
                            type = typeStr.lowercase(),
                            title = title,
                            body = bodyText,
                            date = cmd.optString("created_at", payload.optString("service_date", "")),
                            read = false
                        ))
                    }
                    _crmMessages.value = messages
                    _crmInvoices.value = emptyList() // facturile vin separat din cloud.itsmart.ro
                    _unreadMessageCount.value = messages.count { !it.read }
                } else {
                    _crmMessages.value = emptyList()
                    _crmInvoices.value = emptyList()
                    _unreadMessageCount.value = 0
                }
            } catch (e: Exception) {
                addLog("CRM", "Fetch mesaje V2 EROARE", e.message, false)
            }
        }
    }

    // ==================== REVIZIE COMPLETA ====================

    fun executeCrmRevizie() {
        val revizie = _pendingRevizie.value ?: return
        viewModelScope.launch {
            _revizieRunning.value = true
            _revizieResult.value = null
            val logSb = StringBuilder()
            fun rlog(msg: String, ok: Boolean = true) {
                logSb.appendLine((if (ok) "OK" else "ERR") + " | $msg")
                addLog("REVIZIE", msg, null, ok)
            }

            try {
                rlog("Revizie inceputa")
                ackCommandV2(revizie.id.toString(), "in_progress", "Revizie pornita pe dispozitiv")
                val pass = _deviceState.value.servicePassword
                if (pass.isNotEmpty() && pass != "0" && pass != "-" && pass != "---") {
                    val rLogin = connection.executeCommand(253, "v0$pass")
                    rlog("Service mode: ${if (rLogin.success) "OK" else "ESUAT"}", rLogin.success)
                }

                // Tip raport Z AUTOMAT inainte de revizie (comportament DatecsServiceUtility).
                // CMD 69 "Z" inchide ziua daca exista sold neraportat sau bon deschis;
                // daca nu e nimic de raportat, CMD esueaza silent si continuam.
                // Dupa Z, aparatul iese din mod service => reauth obligatoriu.
                var zPrinted = false
                try {
                    rlog("Tip raport Z (automat, daca este necesar)...")
                    val rZ = connection.executeCommand(69, "Z")
                    zPrinted = rZ.success
                    rlog(if (rZ.success) "Raport Z tiparit cu succes" else "Z omis (nimic de raportat sau cod=${rZ.returnCode})",
                        rZ.success || rZ.returnCode == -100013 /*ZerosOnly*/ || rZ.returnCode == -100008 /*NoData*/)
                    if (rZ.success) {
                        kotlinx.coroutines.delay(2500) // asteapta finalizarea imprimarii
                        // Reauth in mod service (dupa Z, modul service e resetat).
                        if (pass.isNotEmpty() && pass != "0" && pass != "-" && pass != "---") {
                            val rRe = connection.executeCommand(253, "v0$pass")
                            rlog("Reauth service mode dupa Z: ${if (rRe.success) "OK" else "ESUAT"}", rRe.success)
                        }
                    }
                } catch (e: Exception) { rlog("Z auto eroare: ${e.message}", false) }

                val writeOk = connection.cmd255Write("ServiceDate", "", revizie.serviceDate)
                val newDate = connection.cmd255Read("ServiceDate", "")
                rlog("ServiceDate setat: ${revizie.serviceDate} -> OK=$writeOk, verificare=$newDate", writeOk)

                val r69 = connection.executeCommand(69, "O")
                val anafOk = r69.success
                rlog("CMD 69 'O' (ANAF check): ${if (anafOk) "OK" else "ESUAT"}", anafOk)

                val anafState = try { connection.cmd255Read("LastConnectionState", "") } catch (_: Exception) { "" }
                val anafError = try { connection.cmd255Read("LastConnectionErrorCode", "") } catch (_: Exception) { "" }
                rlog("ANAF: State=$anafState, Error=$anafError", anafState == "1" && anafError == "0")

                val r71sim = connection.executeCommand(71, "1"); val simOk = r71sim.success
                rlog("CMD 71 '1' (SIM): ${interpret71(1, r71sim.returnCode)}", simOk)

                val r71crypto = connection.executeCommand(71, "5"); val cryptoOk = r71crypto.success
                rlog("CMD 71 '5' (Crypto): ${interpret71(5, r71crypto.returnCode)}", cryptoOk)

                val r71modem = connection.executeCommand(71, "10"); val modemOk = r71modem.success || r71modem.returnCode == -1
                rlog("CMD 71 '10' (Modem): ${if (modemOk) "Modem OK" else interpret71(10, r71modem.returnCode)}", modemOk)

                var ejText = ""
                try {
                    val currentZ = _deviceState.value.nZreport
                    if (currentZ.isNotEmpty()) {
                        rlog("Citire EJ pentru Z=$currentZ...")
                        val lines = connection.readFiscalReportByZText(currentZ, currentZ, false)
                        ejText = lines.joinToString("\n"); rlog("EJ citit: ${lines.size} linii")
                    }
                } catch (e: Exception) { rlog("EJ citire esuata: ${e.message}", false) }

                rlog("Revizie finalizata!")
                refreshStatusInternal()

                val pvText = buildPVText(_deviceState.value, writeOk, anafOk, simOk, cryptoOk, modemOk, newDate, zPrinted, revizie.serviceDate)
                val pdfFileName = "PV_Revizie_${_deviceState.value.serialNumber}.pdf"
                generatePvPdf(pvText, pdfFileName)

                val revResult = RevizieResult(
                    serviceDateSet = writeOk, anafCheckOk = anafOk, simOk = simOk,
                    cryptoOk = cryptoOk, modemOk = modemOk, zPrinted = zPrinted,
                    newServiceDate = newDate, ejText = ejText, log = logSb.toString(),
                    pvText = pvText, pdfFileName = pdfFileName
                )
                _revizieResult.value = revResult
                _pendingRevizie.value = null
                reportRevizieResult(revizie.id, true)
                com.itsmart.datecs.client.service.AgentPollingService.markCommandHandled(revizie.id.toString())
                markCommandTriedPersistent(revizie.id.toString())

                // Auto-upload PV PDF la CRM (CRM trimite email mai departe).
                // Nu blocheaza UI — ruleaza in background si raporteaza in logs.
                viewModelScope.launch { uploadPvToCrm(revResult, revizie.id.toString()) }

                val allOk = writeOk && anafOk && simOk && cryptoOk && modemOk
                showToast(
                    if (allOk) "Revizie finalizata cu succes!" else "Revizie cu erori",
                    if (allOk) "success" else "warning"
                )
            } catch (e: Exception) {
                rlog("EROARE: ${e.message}", false)
                val revResult = RevizieResult(error = e.message ?: "Eroare necunoscuta", log = logSb.toString())
                _revizieResult.value = revResult
                reportRevizieResult(revizie.id, false)
                com.itsmart.datecs.client.service.AgentPollingService.markCommandHandled(revizie.id.toString())
                markCommandTriedPersistent(revizie.id.toString())
                _pendingRevizie.value = null
                showToast("Eroare revizie: ${e.message}", "error")
            }
            _revizieRunning.value = false
        }
    }

    /**
     * Verifica strict daca aparatul are bon deschis sau incasari neraportate.
     * Returneaza descrierea problemei daca exista (string non-null) sau null daca e OK.
     *
     * CMD 76 returneaza mai multe field-uri (S0, S1, S2, ...). Primul = status receipt
     * (1=deschis). Restul includ flag-uri precum "fiscal mode active" care sunt MEREU
     * "1" pe un aparat operational — verificarea "any == 1" da fals-pozitive (asta era
     * bug-ul anterior care intra in bucla). Verificam DOAR primul field.
     */
    private suspend fun preCheckRequireZ(): String? {
        return try {
            val r76 = connection.executeCommand(76)
            if (!r76.success) return null
            val firstFieldValue = r76.fields.entries.firstOrNull()?.value?.trim() ?: ""
            addLog("REVIZIE", "CMD 76 fields: ${r76.fields}", null, true)
            if (firstFieldValue == "1") "Bon deschis pe aparat" else null
        } catch (_: Exception) { null }
    }

    // Cache persistent (SharedPreferences) cu ID-uri de comenzi deja procesate (succes
    // sau esec) — TTL 24h. Previne bucla: cand un ack "failed" e trimis dar CRM continua
    // sa raporteze comanda ca pending, app-ul nu o re-executa imediat.
    private fun markCommandTriedPersistent(commandId: String) {
        if (commandId.isBlank() || commandId == "0") return
        val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
        prefs.edit().putLong("tried_cmd_$commandId", System.currentTimeMillis()).apply()
    }

    private fun isCommandRecentlyTried(commandId: String): Boolean {
        if (commandId.isBlank() || commandId == "0") return false
        val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
        val ts = prefs.getLong("tried_cmd_$commandId", 0L)
        if (ts == 0L) return false
        val ageMs = System.currentTimeMillis() - ts
        return ageMs < 24L * 60 * 60 * 1000  // 24h cooldown
    }

    /**
     * Upload PV PDF la CRM. Acolo se face email-ul automat (suport@itsmart.ro + clientul).
     * Endpoint propus: POST /api/agent/pv_upload/{commandId} multipart (pdf + meta JSON).
     * Daca endpoint-ul intoarce 404, atunci am inclus deja textul PV in ACK V2 result.
     */
    private suspend fun uploadPvToCrm(result: RevizieResult, commandId: String) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val context = getApplication<Application>()
                val pdfFile = File(context.cacheDir, result.pdfFileName)
                if (!pdfFile.exists() || pdfFile.length() == 0L) {
                    addLog("EMAIL", "PV PDF nu exista local — skip upload", null, false)
                    return@withContext
                }
                val boundary = "----ITSmartBoundary${System.currentTimeMillis()}"
                val url = java.net.URL("$CRM_BASE_URL/api/agent/pv_upload/${java.net.URLEncoder.encode(commandId, "UTF-8")}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.outputStream.use { os ->
                    val w = os.bufferedWriter(Charsets.UTF_8)
                    // Meta JSON
                    w.write("--$boundary\r\n")
                    w.write("Content-Disposition: form-data; name=\"meta\"\r\n")
                    w.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    val meta = org.json.JSONObject().apply {
                        put("serie_aparat", _deviceState.value.serialNumber)
                        put("cif", _deviceState.value.cifNumber)
                        put("model", _deviceState.value.model)
                        put("data_executie", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                        put("service_date", result.newServiceDate)
                        put("anaf_ok", result.anafCheckOk)
                        put("sim_ok", result.simOk)
                        put("crypto_ok", result.cryptoOk)
                        put("modem_ok", result.modemOk)
                        put("z_printed", result.zPrinted)
                        put("send_email", true)
                        put("pv_text", result.pvText)
                    }
                    w.write(meta.toString()); w.write("\r\n")
                    w.flush()
                    // PDF binary
                    os.write("--$boundary\r\n".toByteArray())
                    os.write("Content-Disposition: form-data; name=\"pdf\"; filename=\"${result.pdfFileName}\"\r\n".toByteArray())
                    os.write("Content-Type: application/pdf\r\n\r\n".toByteArray())
                    pdfFile.inputStream().use { it.copyTo(os) }
                    os.write("\r\n".toByteArray())
                    os.write("--$boundary--\r\n".toByteArray())
                }
                val code = conn.responseCode
                val resp = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                if (code in 200..299) {
                    addLog("EMAIL", "PV uploadat la CRM (auto-email)", "HTTP $code", true)
                } else if (code == 404) {
                    addLog("EMAIL", "Endpoint pv_upload lipseste pe CRM — PV doar in ACK", "HTTP 404", false)
                } else {
                    addLog("EMAIL", "Upload PV esuat", "HTTP $code: $resp", false)
                }
            }
        } catch (e: Exception) {
            addLog("EMAIL", "Upload PV exception", e.message, false)
        }
    }

    private fun interpret71(param: Int, returnCode: Int): String = when {
        param == 5 -> if (returnCode == 0) "Criptomodul OK" else "Criptomodul FAIL ($returnCode)"
        param == 1 || param == 10 -> when (returnCode) {
            0 -> "SIM/Date OK"; -110500 -> "SIM lipsa"; -110504 -> "SIM inactiv"; else -> "SIM/Date FAIL ($returnCode)"
        }
        else -> if (returnCode == 0) "OK" else "FAIL ($returnCode)"
    }

    private fun reportRevizieResult(commandId: Int, success: Boolean) {
        viewModelScope.launch {
            val status = if (success) "done" else "failed"
            val resultMsg = _revizieResult.value?.let {
                val parts = listOf(
                    "ServiceDate=${it.newServiceDate}",
                    "ANAF=${if (it.anafCheckOk) "OK" else "ESUAT"}",
                    "SIM=${if (it.simOk) "OK" else "ESUAT"}",
                    "Crypto=${if (it.cryptoOk) "OK" else "ESUAT"}",
                    "Modem=${if (it.modemOk) "OK" else "ESUAT"}",
                    if (it.zPrinted) "Z=tiparit" else "Z=neschimbat"
                )
                parts.joinToString(" | ")
            } ?: ""
            ackCommandV2(commandId.toString(), status, resultMsg)
        }
    }

    // ==================== PV / PDF ====================

    fun buildPVText(ds: DeviceState, serviceDateSet: Boolean, anafOk: Boolean, simOk: Boolean,
                     cryptoOk: Boolean, modemOk: Boolean, newServiceDate: String, zPrinted: Boolean,
                     targetDate: String): String {
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val today = dateFmt.format(Date())
        val clientName = ds.headers.firstOrNull { it.isNotEmpty() } ?: "---"
        var cif = ds.cifNumber
        if (cif.isEmpty()) {
            val cifMatch = Regex("(?:CIF|CUI|C\\.?I\\.?F\\.?)[:\\s]*([A-Z]?\\d{5,13})", RegexOption.IGNORE_CASE).find(ds.headers.joinToString(" "))
            cif = cifMatch?.groupValues?.get(1) ?: "---"
        }
        val adresa = ds.headers.drop(1).filter { it.isNotEmpty() }.joinToString(", ")
        val tipAparat = when (ds.devType) { "1" -> "Casa de marcat"; "2" -> "Imprimanta fiscala"; else -> ds.devType }

        return buildString {
            appendLine("PROCES VERBAL DE REVIZIE TEHNICA ANUALA")
            appendLine("========================================")
            appendLine(); appendLine("Data: $today"); appendLine()
            appendLine("Client: $clientName"); appendLine("CIF/CUI: $cif"); appendLine("Adresa: $adresa"); appendLine()
            appendLine("Aparat: ${ds.model}"); appendLine("Tip: $tipAparat")
            appendLine("Serie aparat: ${ds.serialNumber}"); appendLine("Serie fiscala: ${ds.fiscalNumber}")
            appendLine("Nr. FM: ${ds.fmNumber}"); appendLine("Data fiscalizare: ${ds.fiscalizationDate}"); appendLine()
            appendLine("REZULTATE REVIZIE:"); appendLine("------------------")
            appendLine("Data service setata: $newServiceDate ${if (serviceDateSet) "[OK]" else "[ESUAT]"}")
            appendLine("Verificare ANAF: ${if (anafOk) "OK" else "ESUAT"}")
            appendLine("SIM / Date mobile: ${if (simOk) "OK" else "ESUAT"}")
            appendLine("Criptomodul: ${if (cryptoOk) "OK" else "ESUAT"}")
            appendLine("Modem: ${if (modemOk) "OK" else "ESUAT"}")
            if (zPrinted) appendLine("Raport Z tiparit: DA")
            appendLine(); appendLine("CONCLUZII:")
            val allOk = serviceDateSet && anafOk && simOk && cryptoOk && modemOk
            appendLine(if (allOk) "Aparatul functioneaza in parametri normali. Revizia anuala a fost efectuata cu succes."
                else "Revizia a fost efectuata cu observatii. Verificati elementele marcate ca esuate.")
            appendLine(); appendLine("Data urmatoarei revizii: $targetDate"); appendLine()
            appendLine("Tehnician: ____________________"); appendLine()
            appendLine("Semnatura client: ____________________"); appendLine()
            appendLine("--- Generat cu IT Smart Client ---")
        }
    }

    fun generatePvPdf(pvText: String, fileName: String): Uri? {
        return try {
            val context = getApplication<Application>()
            val pdfDocument = PdfDocument()
            val lines = pvText.split("\n")
            val pageWidth = 595; val pageHeight = 842
            val leftMargin = 50f; val topMargin = 50f; val lineHeight = 16f

            val titlePaint = Paint().apply { textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
            val headerPaint = Paint().apply { textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
            val textPaint = Paint().apply { textSize = 11f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); isAntiAlias = true }
            val separatorPaint = Paint().apply { textSize = 10f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); isAntiAlias = true; color = android.graphics.Color.GRAY }

            var pageNum = 1; var lineIdx = 0
            while (lineIdx < lines.size) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas; var y = topMargin
                while (lineIdx < lines.size && y < (pageHeight - topMargin)) {
                    val line = lines[lineIdx]
                    val paint: Paint; val lh: Float
                    when {
                        line.startsWith("PROCES VERBAL") -> { paint = titlePaint; lh = 24f }
                        line.startsWith("REZULTATE") || line.startsWith("CONCLUZII:") -> { paint = headerPaint; lh = 20f }
                        line.startsWith("====") || line.startsWith("----") -> { paint = separatorPaint; lh = lineHeight }
                        else -> { paint = textPaint; lh = lineHeight }
                    }
                    canvas.drawText(line, leftMargin, y, paint); y += lh; lineIdx++
                }
                pdfDocument.finishPage(page); pageNum++
            }
            val file = File(context.cacheDir, fileName)
            file.outputStream().use { pdfDocument.writeTo(it) }; pdfDocument.close()
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) { Log.e("MainVM", "PDF error: ${e.message}", e); null }
    }

    // ==================== EMAIL HELPERS ====================

    private fun sendRevizieErrorEmailAuto(result: RevizieResult) {
        try {
            val ds = _deviceState.value; val context = getApplication<Application>()
            val subject = "Eroare Revizie - ${ds.serialNumber} - ${ds.model}"
            val body = buildString {
                appendLine("Raport eroare revizie anuala"); appendLine("===========================")
                appendLine("Data: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())}")
                appendLine("Client: ${ds.headers.firstOrNull { it.isNotEmpty() } ?: "---"}"); appendLine("CIF: ${ds.cifNumber}")
                appendLine("Aparat: ${ds.model}"); appendLine("Serie: ${ds.serialNumber}"); appendLine("Serie fiscala: ${ds.fiscalNumber}"); appendLine()
                if (result.error.isNotEmpty()) appendLine("EROARE: ${result.error}")
                else {
                    appendLine("ServiceDate: ${if (result.serviceDateSet) "OK" else "ESUAT"}")
                    appendLine("ANAF: ${if (result.anafCheckOk) "OK" else "ESUAT"}")
                    appendLine("SIM: ${if (result.simOk) "OK" else "ESUAT"}")
                    appendLine("Crypto: ${if (result.cryptoOk) "OK" else "ESUAT"}")
                    appendLine("Modem: ${if (result.modemOk) "OK" else "ESUAT"}")
                }
                appendLine(); appendLine("Log detaliat:"); appendLine(result.log)
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("suport@itsmartretail.ro"))
                putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                putExtra(android.content.Intent.EXTRA_TEXT, body)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) { addLog("EMAIL", "Eroare email suport", e.message, false) }
    }

    fun createXmlEmailIntent(zipUri: Uri, clientName: String, luna: String, serialNumber: String, fiscalNumber: String, fileCount: Int): android.content.Intent {
        val subject = "Export XML ANAF - $clientName - $serialNumber"
        val body = "Buna ziua,\n\nAtasat regasiti exportul XML pentru clientul $clientName " +
                "pentru luna $luna din aparatul cu seria $serialNumber si seria fiscala $fiscalNumber.\n\n" +
                "Arhiva contine $fileCount fisiere .p7b.\n\nCu stima,\nIT Smart Client"
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject); putExtra(android.content.Intent.EXTRA_TEXT, body)
            putExtra(android.content.Intent.EXTRA_STREAM, zipUri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createFmReportEmailIntent(reportText: String, clientName: String, serialNumber: String): android.content.Intent {
        val context = getApplication<Application>()
        val subject = "Raport Memorie Fiscala - $clientName - $serialNumber"
        val body = "Buna ziua,\n\nAtasat regasiti raportul de memorie fiscala pentru clientul $clientName " +
                "din aparatul cu seria $serialNumber.\n\nCu stima,\nIT Smart Client"
        val txtFile = File(context.cacheDir, "Raport_MF_${serialNumber}.txt"); txtFile.writeText(reportText)
        val txtUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", txtFile)
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject); putExtra(android.content.Intent.EXTRA_TEXT, body)
            putExtra(android.content.Intent.EXTRA_STREAM, txtUri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createPvEmailIntent(pvText: String, pdfFileName: String): android.content.Intent {
        val context = getApplication<Application>(); val ds = _deviceState.value
        val pdfUri = generatePvPdf(pvText, pdfFileName)
        val subject = "Proces Verbal Revizie Tehnica - ${ds.serialNumber} - ${ds.model}"
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject); putExtra(android.content.Intent.EXTRA_TEXT, pvText)
            if (pdfUri != null) { putExtra(android.content.Intent.EXTRA_STREAM, pdfUri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun createZipFromAnafFiles(): Uri? {
        val result = _lastAnafResult.value ?: return null
        if (!result.success || result.savedPath.isEmpty()) return null
        val context = getApplication<Application>()
        val folder = File(result.savedPath)
        val p7bFiles = folder.listFiles()?.filter { it.extension.lowercase() == "p7b" } ?: return null
        if (p7bFiles.isEmpty()) return null
        val cal = Calendar.getInstance(); val luna = AnafDownloader.LUNI_RO[cal.get(Calendar.MONTH)]; val anul = cal.get(Calendar.YEAR)
        val zipFile = File(context.cacheDir, "${luna}_${anul}.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            for (file in p7bFiles) { zos.putNextEntry(java.util.zip.ZipEntry(file.name)); file.inputStream().use { it.copyTo(zos) }; zos.closeEntry() }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", zipFile)
    }

    // ==================== REPORTS ====================

    fun executeFiscalReportByDate(startDate: String, endDate: String, detailed: Boolean) {
        viewModelScope.launch {
            val tip = if (detailed) "1" else "0"
            val r = connection.executeCommand(94, "$tip,$startDate,$endDate")
            addLog("CMD94", "Raport MF ($tip, $startDate, $endDate)", r.rawOutput, r.success)
            if (r.success) showToast("Raport MF generat", "success") else showToast("Eroare: ${r.rawOutput}", "error")
        }
    }

    fun executeFiscalReportByZ(zStart: String, zEnd: String, detailed: Boolean) {
        viewModelScope.launch {
            val tip = if (detailed) "1" else "0"
            val r = connection.executeCommand(95, "$tip,$zStart,$zEnd")
            addLog("CMD95", "Raport MF Z ($tip, $zStart, $zEnd)", r.rawOutput, r.success)
            if (r.success) showToast("Raport MF generat", "success") else showToast("Eroare: ${r.rawOutput}", "error")
        }
    }

    fun previewFiscalReportByZ(zStart: String, zEnd: String, detailed: Boolean) {
        viewModelScope.launch {
            _reportPreviewLoading.value = true; _reportPreviewText.value = "Citire raport..."
            try {
                val lines = connection.readFiscalReportByZText(zStart, zEnd, detailed)
                _reportPreviewText.value = if (lines.isEmpty()) "Nicio inregistrare." else lines.joinToString("\n")
            } catch (e: Exception) { _reportPreviewText.value = "Eroare: ${e.message}" }
            _reportPreviewLoading.value = false
        }
    }

    // ==================== ANAF DOWNLOAD ====================

    /**
     * Export automat ANAF pentru LUNA ANTERIOARA (in iunie → luna mai etc).
     * Computeaza intervalul DD-MM-YY in formatul Datecs si lanseaza downloadAnafFiles.
     * uploadToCloud() se declanseaza automat la succes (vezi downloadAnafFiles).
     */
    fun exportPreviousMonth(clientName: String, basePath: String) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        val yy = cal.get(Calendar.YEAR) % 100
        val mm = cal.get(Calendar.MONTH) + 1
        val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val from = String.format(java.util.Locale.US, "%02d-%02d-%02d 00:00:01", 1, mm, yy)
        val to = String.format(java.util.Locale.US, "%02d-%02d-%02d 23:59:59", lastDay, mm, yy)
        addLog("EXPORT", "Export luna anterioara", "$from -> $to", true)
        downloadAnafFiles(from, to, clientName, basePath)
    }

    /**
     * Folderul de salvare: cel ales de utilizator daca se poate scrie in el, altfel folderul aplicatiei
     * (Android 10+ nu permite scrierea directa in Documents fara permisiuni speciale; folderul aplicatiei nu cere permisiuni).
     */
    private fun writableBasePath(chosen: String): String {
        if (chosen.isNotBlank()) {
            val f = File(chosen)
            if ((f.isDirectory || f.mkdirs()) && f.canWrite()) return chosen
            addLog("ANAF", "Folderul ales nu permite scrierea", chosen, false)
        }
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: app.filesDir, "DatecsANAF")
        dir.mkdirs()
        return dir.absolutePath
    }

    fun downloadAnafFiles(from: String, to: String, clientName: String, basePath: String) {
        viewModelScope.launch {
            _anafDownloading.value = true; _anafDownloadProgress.value = "Initializare..."; _lastAnafResult.value = null
            if (clientName.isNotEmpty()) saveClientName(clientName)
            if (basePath.isNotEmpty()) saveBasePath(basePath)
            try {
                val downloader = AnafDownloader(connection)
                val result = downloader.downloadAnafFiles(from, to, clientName = clientName,
                    serialNumber = _deviceState.value.serialNumber, basePath = writableBasePath(basePath)
                ) { current, total, fileName -> _anafDownloadProgress.value = "Descarcare $current/$total: $fileName" }
                _lastAnafResult.value = result
                if (result.success) { showToast("${result.fileCount} fisiere descarcate!", "success"); if (_autoUpload.value) uploadToCloud() }
                else showToast("Eroare: ${result.error}", "error")
            } catch (e: Exception) {
                _lastAnafResult.value = AnafDownloadResult(false, 0, "", emptyList(), "Eroare: ${e.message}")
                showToast("Eroare: ${e.message}", "error")
            }
            _anafDownloading.value = false; _anafDownloadProgress.value = ""
        }
    }

    // ==================== CLOUD (asociere + upload) ====================

    /** Asociere cu codul generat in cloud.itsmart.ro; daca exista fisiere descarcate netrimise, se trimit imediat. */
    /** Intrare in aplicatie doar cu codul de asociere (fara email / parola): firma din cloud e contul aparatului. */
    private var pairLogin: Boolean
        get() = getApplication<Application>().getSharedPreferences("datecs_client", 0).getBoolean("pair_login", false)
        set(v) { getApplication<Application>().getSharedPreferences("datecs_client", 0).edit().putBoolean("pair_login", v).apply() }

    fun loginWithPairCode(code: String) {
        if (code.isBlank() || _cloudPairing.value) return
        viewModelScope.launch {
            _cloudPairing.value = true; _loginError.value = ""
            val r = cloud.pair(code)
            _cloudLink.value = cloud.link()
            _cloudPairing.value = false
            if (r.ok) {
                pairLogin = true
                addLog("CLOUD", "Intrare cu cod de asociere", r.message, true)
                _username.value = r.message
                _isLoggedIn.value = true
            } else {
                addLog("CLOUD", "Cod de asociere respins", r.message, false)
                _loginError.value = r.message
            }
        }
    }

    fun pairCloud(code: String) {
        if (code.isBlank() || _cloudPairing.value) return
        viewModelScope.launch {
            _cloudPairing.value = true; _cloudPairMessage.value = ""
            val r = cloud.pair(code)
            _cloudLink.value = cloud.link()
            _cloudPairing.value = false
            if (r.ok) {
                addLog("CLOUD", "Asociere cu cod", r.message, true)
                _cloudPairMessage.value = "Asociat cu ${r.message}"
                if (pendingCloudUpload) uploadToCloud()
            } else {
                addLog("CLOUD", "Asociere esuata", r.message, false)
                _cloudPairMessage.value = r.message
            }
        }
    }

    fun unpairCloud() {
        cloud.forget()
        _cloudLink.value = cloud.link()
        _cloudPairMessage.value = ""
        addLog("CLOUD", "Asociere stearsa", null, true)
    }

    /** Fisierele descarcate asteapta asocierea (se trimit dupa ce se introduce codul). */
    private var pendingCloudUpload = false

    /**
     * Fara asociere, se incearca o data asocierea automata (aparatul inregistrat la firma: CUI + serie / nr. fiscal),
     * permisa de cloud pana la 31.01.2027. Altfel e nevoie de codul de asociere din cloud.itsmart.ro.
     */
    private suspend fun ensureCloudLink(): Boolean {
        if (cloud.link().paired) return true
        val (_, cif, _) = parseHeaderInfo()
        val ds = _deviceState.value
        if (cif.length >= 5) for (serie in listOf(ds.serialNumber, ds.fiscalNumber).filter { it.isNotBlank() }.distinct()) {
            val r = cloud.migrate(cif, serie)
            addLog("CLOUD", "Asociere automata ($serie)", r.message, r.ok)
            if (r.ok) { _cloudLink.value = cloud.link(); return true }
        }
        return false
    }

    fun uploadToCloud() {
        if (_cloudUploading.value) return
        viewModelScope.launch {
            val result = _lastAnafResult.value
            if (result == null || !result.success || result.savedPath.isEmpty()) return@launch
            _cloudUploading.value = true; _cloudResult.value = ""
            try {
                if (!ensureCloudLink()) {
                    pendingCloudUpload = true
                    _cloudResult.value = "EROARE: Aplicatia nu este asociata cu cloud.itsmart.ro. Introduceti codul de asociere (sus, pe aceasta pagina)."
                    return@launch
                }
                val (_, cif, _) = parseHeaderInfo()
                val folder = File(result.savedPath)
                if (!folder.exists() || !folder.isDirectory) { _cloudResult.value = "EROARE: Folder inexistent"; return@launch }
                val allFiles = folder.listFiles()?.filter { it.isFile } ?: emptyList()
                if (allFiles.isEmpty()) { _cloudResult.value = "EROARE: Niciun fisier"; return@launch }

                val folderInfo = parseFolderInfo(folder.name)
                val p7bFiles = allFiles.filter { it.extension.lowercase() == "p7b" }
                val firstP7b = p7bFiles.firstOrNull { it.name.contains("_Z") }
                val serieAmef = firstP7b?.name?.split("_Z")?.firstOrNull() ?: _deviceState.value.serialNumber
                val pdfFiles = allFiles.filter { it.extension.lowercase() == "pdf" }

                val zipFile = File(folder.parent, "${folderInfo.lunaText}_${folderInfo.anul}.zip")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                        for (file in p7bFiles) { zos.putNextEntry(java.util.zip.ZipEntry(file.name)); file.inputStream().use { it.copyTo(zos) }; zos.closeEntry() }
                    }
                }
                val r = cloud.upload(listOf(zipFile) + pdfFiles, cif, serieAmef, folderInfo.luna, folderInfo.anul)
                _cloudLink.value = cloud.link()
                if (r.ok) {
                    pendingCloudUpload = false
                    _cloudResult.value = "OK — ${r.uploaded} fisiere incarcate" + if (r.skipped > 0) ", ${r.skipped} ignorate" else ""
                    addLog("CLOUD", "Upload OK", _cloudResult.value, true)
                } else {
                    if (r.unauthorized) pendingCloudUpload = true
                    _cloudResult.value = "EROARE: ${r.error}"
                    addLog("CLOUD", "Upload ESUAT", r.error, false)
                }
            } catch (e: Exception) { _cloudResult.value = "EROARE: ${e.message}"; addLog("CLOUD", "Upload EXCEPTIE", e.message, false) }
            finally { _cloudUploading.value = false }
        }
    }

    private val luniMap = mapOf(
        "ianuarie" to 1, "februarie" to 2, "martie" to 3, "aprilie" to 4, "mai" to 5, "iunie" to 6,
        "iulie" to 7, "august" to 8, "septembrie" to 9, "octombrie" to 10, "noiembrie" to 11, "decembrie" to 12
    )
    data class FolderInfo(val client: String, val luna: Int, val anul: Int, val lunaText: String)
    private fun parseFolderInfo(folderName: String): FolderInfo {
        val parts = folderName.split("_")
        val anul = parts.lastOrNull()?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
        val lunaTextRaw = parts.getOrNull(parts.size - 2) ?: ""
        val luna = luniMap[lunaTextRaw.lowercase()] ?: (Calendar.getInstance().get(Calendar.MONTH) + 1)
        val dbIdx = folderName.indexOf("_DB")
        val clientFromFolder = if (dbIdx > 0) folderName.substring(0, dbIdx).replace("_", " ") else ""
        return FolderInfo(clientFromFolder, luna, anul, lunaTextRaw)
    }

    // ==================== HELPERS ====================

    fun parseHeaderInfo(): Triple<String, String, String> {
        val headers = _deviceState.value.headers; val allText = headers.joinToString("\n")
        val clientName = headers.firstOrNull { it.isNotEmpty() } ?: ""
        var cif = _deviceState.value.cifNumber
        if (cif.isEmpty()) { val m = Regex("(?:CIF|CUI|C\\.?I\\.?F\\.?|C\\.?U\\.?I\\.?)[:\\s]*([A-Z]?\\d{5,13})", RegexOption.IGNORE_CASE).find(allText); cif = m?.groupValues?.get(1) ?: "" }
        val adresaLines = headers.drop(1).filter { it.isNotEmpty() && !it.contains(Regex("(?:CIF|CUI)", RegexOption.IGNORE_CASE)) }
        return Triple(clientName, cif, adresaLines.joinToString(", "))
    }

    fun getLogText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== IT Smart Client — Log ===")
        sb.appendLine("Data: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("Model: ${_deviceState.value.model}"); sb.appendLine("Serie: ${_deviceState.value.serialNumber}")
        sb.appendLine("==========================")
        for (log in _logs.value) { sb.append("[${log.time}] ${if (log.ok) "OK" else "ERR"} [${log.tag}] ${log.cmd}"); if (log.res != null) sb.append(" <- ${log.res}"); sb.appendLine() }
        return sb.toString()
    }

    fun getClientNameFromHeader(): String = _deviceState.value.headers.firstOrNull { it.isNotEmpty() } ?: ""

    // ==================== PREFS ====================

    fun saveLanConfig(ip: String, port: String) { getApplication<Application>().getSharedPreferences("datecs_client", 0).edit().putString("lan_ip", ip).putString("lan_port", port).apply() }
    fun loadLanIp(): String = getApplication<Application>().getSharedPreferences("datecs_client", 0).getString("lan_ip", "") ?: ""
    fun loadLanPort(): String = getApplication<Application>().getSharedPreferences("datecs_client", 0).getString("lan_port", "3999") ?: "3999"
    fun saveClientName(name: String) { getApplication<Application>().getSharedPreferences("datecs_client", 0).edit().putString("client_name", name).apply() }
    fun loadClientName(): String = getApplication<Application>().getSharedPreferences("datecs_client", 0).getString("client_name", "") ?: ""
    fun saveBasePath(path: String) { getApplication<Application>().getSharedPreferences("datecs_client", 0).edit().putString("base_path", path).apply() }
    fun loadBasePath(): String = getApplication<Application>().getSharedPreferences("datecs_client", 0).getString("base_path", "") ?: ""

    // ==================== LAN HISTORY ====================

    fun saveLanHistory(address: String, name: String, info: String = "") {
        val prefs = getApplication<Application>().getSharedPreferences("datecs_client", 0)
        val existing = loadLanHistory().toMutableList()
        existing.removeAll { it.address == address }
        existing.add(0, LanHistoryEntry(address, name, info, System.currentTimeMillis()))
        if (existing.size > 10) existing.subList(10, existing.size).clear()
        prefs.edit().putString("lan_history", existing.joinToString(";") { "${it.address}|${it.name}|${it.lastUsed}|${it.info}" }).apply()
        _lanHistory.value = existing
    }

    private fun loadLanHistory(): List<LanHistoryEntry> {
        val raw = getApplication<Application>().getSharedPreferences("datecs_client", 0).getString("lan_history", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("|", limit = 4)
            if (parts.size >= 2) LanHistoryEntry(parts[0], parts[1], parts.getOrElse(3) { "" }, parts.getOrNull(2)?.toLongOrNull() ?: 0) else null
        }
    }
}
