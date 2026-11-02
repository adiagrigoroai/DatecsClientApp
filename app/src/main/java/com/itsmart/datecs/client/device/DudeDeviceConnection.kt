package com.itsmart.datecs.client.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.datecsro.fiscalprinter.SDK.FiscalDevice
import com.datecsro.fiscalprinter.SDK.FiscalException
import com.datecsro.fiscalprinter.SDK.FiscalResponse
import com.datecsro.fiscalprinter.SDK.model.DatecsFiscalDevice
import com.datecsro.fiscalprinter.SDK.model.rou.*
import com.itsmart.datecs.client.data.model.CommandResult
import com.itsmart.datecs.client.data.model.ConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class DudeDeviceConnection(private val context: Context) : IDeviceConnection {

    companion object {
        private const val TAG = "DudeDevice"
        /** BlueCash: modulul intern se elibereaza dupa atatea ms fara comenzi. */
        private const val INTERNAL_IDLE_MS = 4_000L
        /** BlueCash: aplicatiile de vanzare care tin modulul deschis (ex. Sedona POS) blocheaza accesul. */
        const val BUSY_MSG = "Modulul fiscal este folosit de alta aplicatie de vanzare (ex. Sedona, Freya). Inchideti-o si reincercati."
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val DATECS_USB_VID = 65520
        private const val FTDI_USB_VID = 1027
    }

    private var fiscalDevice: FiscalDevice? = null
    private var btSocket: BluetoothSocket? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var lanSocket: Socket? = null
    private var internalSession: InternalFiscalBridge.InternalSession? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    var modelName: String = ""
        private set

    override val isConnected: Boolean get() = fiscalDevice != null || internalMode

    // ---- BlueCash: modulul fiscal intern poate fi folosit de o singura aplicatie odata ----
    // Pe BC50 nu tinem modulul ocupat: il deschidem pe durata unei operatii si il eliberam dupa
    // INTERNAL_IDLE_MS fara comenzi, ca aplicatia de vanzare (TDA Pos, Freya etc.) sa poata emite bonuri.
    private var internalMode = false
    private val activeOps = java.util.concurrent.atomic.AtomicInteger(0)
    private val openLock = kotlinx.coroutines.sync.Mutex()
    private val releaseScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var releaseJob: kotlinx.coroutines.Job? = null
    /** Ultima eroare de acces la modulul intern (ex. ocupat de alta aplicatie). */
    var lastAccessError: String = ""
        private set

    private fun openInternalDevice(): FiscalDevice? {
        openInternalConnection()
        val inp = inputStream ?: return null
        val out = outputStream ?: return null
        for (i in 0 until 4) {
            val candidate = FMP_P6X_ROU(inp, out)
            if (try { candidate.command74Variant0Version0(); true } catch (_: Exception) { false }) {
                DatecsFiscalDevice(FiscalException.FPTR_CC_ROMANIA)
                DatecsFiscalDevice.setConnectedModel(candidate)
                return DatecsFiscalDevice.getConnectedModel()
            }
            Thread.sleep(400)
        }
        closeRawConnection()
        return null
    }

    /** Dispozitivul pentru o operatie; pe BlueCash redeschide modulul intern daca a fost eliberat. */
    private suspend fun acquire(): FiscalDevice? {
        if (!internalMode) return fiscalDevice
        activeOps.incrementAndGet()
        releaseJob?.cancel()
        val dev = openLock.withLock {
            fiscalDevice ?: withContext(Dispatchers.IO) {
                try { openInternalDevice() } catch (e: Exception) { Log.w(TAG, "reopen intern: ${e.message}"); null }
            }.also { fiscalDevice = it }
        }
        if (dev == null) {
            lastAccessError = BUSY_MSG
            activeOps.decrementAndGet()
        } else lastAccessError = ""
        return dev
    }

    /** Operatie terminata: pe BlueCash, modulul se elibereaza dupa o pauza fara comenzi. */
    private fun released() {
        if (!internalMode) return
        if (activeOps.decrementAndGet() > 0) return
        scheduleInternalRelease()
    }

    private fun scheduleInternalRelease() {
        releaseJob?.cancel()
        releaseJob = releaseScope.launch {
            kotlinx.coroutines.delay(INTERNAL_IDLE_MS)
            openLock.withLock {
                if (activeOps.get() == 0 && fiscalDevice != null) {
                    try { fiscalDevice?.close() } catch (_: Exception) {}
                    fiscalDevice = null
                    closeRawConnection()
                    Log.d(TAG, "Modul fiscal intern eliberat (inactiv)")
                }
            }
        }
    }

    private suspend fun <T> useFd(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        try { withContext(Dispatchers.IO, block) } finally { released() }

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String, type: ConnectionType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "connect() start: address=$address type=$type")
                disconnect()

                // Step 1: Open raw connection to get streams
                when (type) {
                    ConnectionType.USB -> openUsbConnection(address)
                    ConnectionType.BLUETOOTH -> openBluetoothConnection(address)
                    ConnectionType.LAN -> openLanConnection(address)
                    ConnectionType.INTERNAL -> openInternalConnection()
                }
                Log.d(TAG, "Raw connection opened. inputStream=$inputStream outputStream=$outputStream")

                val inp = inputStream ?: throw IOException("No input stream")
                val out = outputStream ?: throw IOException("No output stream")

                // BlueCash 50 / 500: modulul fiscal intern raspunde direct pe protocolul FMP-P6X (ca in exemplul
                // oficial Datecs BC50). Detectarea automata a modelului nu e necesara si poate esua pe socketul intern.
                if (type == ConnectionType.INTERNAL) {
                    var dev: FiscalDevice? = null
                    for (i in 0 until 6) {
                        val candidate = FMP_P6X_ROU(inp, out)
                        val ok = try { candidate.command74Variant0Version0(); true } catch (e: Exception) {
                            Log.w(TAG, "BC50 ping ${i + 1}/6: ${e.message}"); false
                        }
                        if (ok) { dev = candidate; break }
                        Thread.sleep(500)
                    }
                    if (dev == null) {
                        Log.e(TAG, "Modulul fiscal intern nu raspunde")
                        closeRawConnection()
                        return@withContext false
                    }
                    DatecsFiscalDevice(FiscalException.FPTR_CC_ROMANIA)
                    DatecsFiscalDevice.setConnectedModel(dev)
                    fiscalDevice = DatecsFiscalDevice.getConnectedModel()
                    modelName = if (Regex("(?i)500").containsMatchIn(android.os.Build.MODEL ?: "")) "BC-500" else "BC-50"
                    internalMode = true
                    scheduleInternalRelease()
                    Log.d(TAG, "connect() INTERNAL SUCCESS model=$modelName")
                    return@withContext true
                }

                // Step 2: Detect device model
                Log.d(TAG, "Starting model detection...")
                val detector = FDModelDetector(inp, out)
                var detected = ""
                for (i in 0 until 10) {
                    try {
                        Log.d(TAG, "detectConnectedModel() attempt ${i + 1}/10")
                        detected = detector.detectConnectedModel() ?: ""
                        Log.d(TAG, "Detection result: '$detected'")
                        if (detected.isNotEmpty()) break
                    } catch (e: Exception) {
                        Log.e(TAG, "Detection attempt ${i + 1} failed: ${e.message}")
                    }
                    Thread.sleep(300)
                }

                if (detected.isEmpty()) {
                    Log.e(TAG, "Model detection FAILED after 10 attempts")
                    closeRawConnection()
                    return@withContext false
                }

                modelName = detected
                Log.d(TAG, "Model detected: $detected")

                // Step 3: Create fiscal device with detected model
                val tp = detector.transportProtocol
                Log.d(TAG, "TransportProtocol: $tp (class: ${tp?.javaClass?.simpleName})")
                val model = createModel(detected, tp) ?: run {
                    Log.e(TAG, "createModel() returned null for '$detected'")
                    closeRawConnection()
                    return@withContext false
                }
                Log.d(TAG, "Model created: ${model.javaClass.simpleName}")

                val dfd = DatecsFiscalDevice(FiscalException.FPTR_CC_ROMANIA)
                DatecsFiscalDevice.setConnectedModel(model)
                fiscalDevice = DatecsFiscalDevice.getConnectedModel()
                Log.d(TAG, "connect() SUCCESS. fiscalDevice=${fiscalDevice?.javaClass?.simpleName}")

                true
            } catch (e: Exception) {
                Log.e(TAG, "connect() EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                closeRawConnection()
                false
            }
        }
    }

    /** Fara pornirea automata la conectare (filtrul USB), permisiunea se cere aici; asteapta raspunsul utilizatorului. */
    private fun requestUsbPermission(usbManager: UsbManager, device: android.hardware.usb.UsbDevice): Boolean {
        val action = context.packageName + ".USB_PERMISSION"
        val latch = java.util.concurrent.CountDownLatch(1)
        var granted = false
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter(action),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val pi = android.app.PendingIntent.getBroadcast(context, 0,
                android.content.Intent(action).setPackage(context.packageName), android.app.PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pi)
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
        return granted || usbManager.hasPermission(device)
    }

    private fun openUsbConnection(deviceName: String) {
        Log.d(TAG, "openUsbConnection: deviceName=$deviceName")
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        Log.d(TAG, "USB device list: ${usbManager.deviceList.entries.joinToString { "${it.key} -> VID=${it.value.vendorId} PID=${it.value.productId} name=${it.value.deviceName}" }}")
        val device = usbManager.deviceList.values.firstOrNull {
            it.deviceName == deviceName
        } ?: throw IOException("USB device not found: $deviceName")

        Log.d(TAG, "Found USB device: VID=${device.vendorId} PID=${device.productId} interfaces=${device.interfaceCount}")
        if (!usbManager.hasPermission(device) && !requestUsbPermission(usbManager, device))
            throw IOException("Permisiunea USB pentru casa de marcat a fost refuzata")
        val conn = usbManager.openDevice(device)
            ?: throw IOException("Cannot open USB device (permission denied?)")
        Log.d(TAG, "USB device opened successfully")

        var epIn: android.hardware.usb.UsbEndpoint? = null
        var epOut: android.hardware.usb.UsbEndpoint? = null
        var skipBytes = 0

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
            }

            if (epIn != null && epOut != null) {
                if (!conn.claimInterface(iface, true)) {
                    conn.close()
                    throw IOException("Cannot claim USB interface")
                }

                // FTDI devices need special handling
                if (device.vendorId == FTDI_USB_VID) {
                    skipBytes = 2
                    conn.controlTransfer(0x40, 0, 0, 0, null, 0, 0) // Reset
                    conn.controlTransfer(0x40, 0, 1, 0, null, 0, 0) // Clear Rx
                    conn.controlTransfer(0x40, 0, 2, 0, null, 0, 0) // Clear Tx
                    conn.controlTransfer(0x40, 0x03, 0x001A, 0, null, 0, 0) // Baud 115200
                }

                usbConnection = conn
                inputStream = UsbInputStream(conn, epIn, skipBytes)
                outputStream = UsbOutputStream(conn, epOut)
                return
            }
        }

        conn.close()
        throw IOException("No bulk endpoints found on USB device")
    }

    @SuppressLint("MissingPermission")
    private fun openBluetoothConnection(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IOException("Bluetooth not available")
        val device = adapter.bondedDevices?.firstOrNull { it.address == address }
            ?: throw IOException("BT device not found: $address")

        val socket = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            throw IOException("BLE not supported yet — use USB or classic BT")
        } else {
            try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: Exception) {
                // Fallback: use reflection for insecure connection
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }
        }

        adapter.cancelDiscovery()
        socket.connect()

        btSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
    }

    /**
     * Deschide fiscal-ul intern al device-ului (BC50 etc). Foloseste libraria de sistem
     * com.android.fiscal (in /system/framework/) prin reflexie din InternalFiscalBridge.
     */
    private fun openInternalConnection() {
        Log.d(TAG, "openInternalConnection: opening internal fiscal via FiscalManager")
        if (!InternalFiscalBridge.isAvailable()) {
            throw IOException("Device-ul nu are fiscal intern (com.android.fiscal lipseste)")
        }
        val session = InternalFiscalBridge.open(context)
            ?: throw IOException(BUSY_MSG)
        internalSession = session
        inputStream = session.inputStream
        outputStream = session.outputStream
        Log.d(TAG, "Internal fiscal connection established")
    }

    private fun openLanConnection(address: String) {
        // address format: "host:port" or just "host" (default port 3999)
        val parts = address.split(":", limit = 2)
        val host = parts[0].trim()
        val port = parts.getOrElse(1) { "3999" }.trim().toIntOrNull() ?: 3999

        Log.d(TAG, "openLanConnection: host=$host port=$port")
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 10000) // 10 second connect timeout
        socket.soTimeout = 0 // Unlimited read timeout (matches SDK NetworkConnector)
        socket.tcpNoDelay = true // Low latency

        lanSocket = socket
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
        Log.d(TAG, "LAN connection established to $host:$port")
    }

    @Suppress("UNCHECKED_CAST")
    private fun createModel(name: String, tp: com.datecsro.fiscalprinter.SDK.AbstractTransportProtocol): FiscalDevice? {
        return try {
            when (name) {
                "WP-65MX" -> WP_65MX_ROU(tp)
                "FMP-350", "FMP-350MX" -> FMP350_ROU(tp)
                "DP-05", "DP-05L" -> DP05_ROU(tp)
                "DP-25" -> DP25_ROU(tp)
                "DP-25MX" -> DP25MX_ROU(tp)
                "DP-35" -> DP35_ROU(tp)
                "DP-150" -> DP150_ROU(tp)
                "DP-150MX" -> DP150MX_ROU(tp)
                "WP-50" -> WP50_ROU(tp)
                "WP-50MX" -> WP50MX_ROU(tp)
                "WP-500" -> WP500_ROU(tp)
                "BC-50", "BC-50MX", "BC-500", "BC-500MX" -> FMP_P6X_ROU(tp)
                "FP-700", "FP-700X", "FP-700XR", "FP-700MX" -> FP700_ROU(tp)
                "FP-950", "FP-950MX" -> FP950_ROU(tp)
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun disconnect() {
        internalMode = false
        releaseJob?.cancel()
        withContext(Dispatchers.IO) {
            try { fiscalDevice?.close() } catch (_: Exception) {}
            fiscalDevice = null
            modelName = ""
            closeRawConnection()
        }
    }

    private fun closeRawConnection() {
        // Close streams first to stop background reader thread
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        try { lanSocket?.close() } catch (_: Exception) {}
        try { internalSession?.close() } catch (_: Exception) {}
        btSocket = null
        usbConnection = null
        lanSocket = null
        internalSession = null
        inputStream = null
        outputStream = null
    }

    override suspend fun executeCommand(cmd: Int, params: String): CommandResult {
        val fd = acquire() ?: return CommandResult(false, -1, lastAccessError.ifEmpty { "Not connected" }, emptyMap())
        return useFd {
            try {
                val response: FiscalResponse? = when (cmd) {
                    38 -> fd.command38Variant0Version0(params.ifEmpty { "" })
                    43 -> {
                        // Header write: "SAVE" = save to FM, otherwise "lineNum\ttext"
                        if (params == "SAVE") {
                            fd.command43Variant2Version0()
                        } else {
                            val p = params.split("\t", limit = 2)
                            fd.command43Variant0Version0(
                                p.getOrElse(0) { "1" },
                                p.getOrElse(1) { "" }
                            )
                        }
                    }
                    44 -> fd.command44Variant0Version0(params.ifEmpty { "1" })
                    45 -> fd.command45Variant0Version0()
                    61 -> fd.command61Variant0Version0(params)
                    62 -> fd.command62Variant0Version0()
                    69 -> fd.command69Variant0Version0(params)
                    71 -> fd.command71Variant0Version0(params.ifEmpty { "" })
                    76 -> fd.command76Variant0Version0()
                    83 -> {
                        val p = params.split(",")
                        fd.command83Variant0Version0(
                            p.getOrElse(0) { "19.00" },
                            p.getOrElse(1) { "9.00" },
                            p.getOrElse(2) { "5.00" },
                            p.getOrElse(3) { "0.00" },
                            p.getOrElse(4) { "0.00" }
                        )
                    }
                    84 -> fd.command84Variant0Version0(params)
                    123 -> try {
                        when (params) {
                            "1" -> fd.command123Variant1Version0()
                            else -> fd.command123Variant0Version0()
                        }
                    } catch (_: Throwable) { null }
                    89 -> fd.command89Variant0Version0(params)
                    90 -> {
                        // cmd90: "v0" or "v0,param" for Version0, default=Version1
                        if (params.startsWith("v0")) {
                            val arg = params.removePrefix("v0").trimStart(',').trim()
                            fd.command90Variant0Version0(arg)
                        } else {
                            fd.command90Variant0Version1()
                        }
                    }
                    94 -> {
                        // Fiscal report by DATE: reportType, startDate DD-MM-YY, endDate DD-MM-YY
                        val p = params.split(",")
                        fd.command94Variant0Version0(
                            p.getOrElse(0) { "0" },
                            p.getOrElse(1) { "" },
                            p.getOrElse(2) { "" }
                        )
                    }
                    95 -> {
                        // Fiscal report by Z number: reportType, startZ, endZ
                        val p = params.split(",")
                        fd.command95Variant0Version0(
                            p.getOrElse(0) { "0" },
                            p.getOrElse(1) { "" },
                            p.getOrElse(2) { "" }
                        )
                    }
                    149 -> fd.command149Variant0Version0(params)
                    165 -> {
                        val p = params.split(",", limit = 2)
                        fd.command165Variant0Version0(
                            p.getOrElse(0) { "1" },
                            p.getOrElse(1) { "" }
                        )
                    }
                    202 -> fd.command202Variant1Version0(params)
                    253 -> when {
                        params.startsWith("v0") -> fd.command253Variant0Version0(params.removePrefix("v0").trim())
                        params.startsWith("v1") -> {
                            val p = params.removePrefix("v1").trim().split(",")
                            fd.command253Variant1Version0(p.getOrElse(0) { "" }, p.getOrElse(1) { "" })
                        }
                        params.startsWith("v2") -> fd.command253Variant2Version0()
                        params.startsWith("v3") -> fd.command253Variant3Version0()
                        else -> fd.command253Variant0Version0(params)
                    }
                    else -> null
                }

                if (response != null) {
                    val fields = mutableMapOf<String, String>()
                    for (key in response.keys) {
                        fields[key] = response.get(key) ?: ""
                    }
                    CommandResult(true, 0, fields.entries.joinToString(", ") { "${it.key}=${it.value}" }, fields)
                } else {
                    val raw = fd.customCommand(cmd, params) ?: "OK"
                    CommandResult(true, 0, raw, emptyMap())
                }
            } catch (e: FiscalException) {
                CommandResult(false, e.errorCode, "Eroare: ${e.errorMessage}", emptyMap())
            } catch (e: IOException) {
                CommandResult(false, -1, "Eroare comunicare: ${e.message}", emptyMap())
            } catch (e: Exception) {
                CommandResult(false, -1, "Eroare: ${e.message}", emptyMap())
            }
        }
    }

    override suspend fun cmd255Read(name: String, index: String): String {
        val fd = acquire() ?: return ""
        return useFd {
            try {
                Log.d(TAG, "cmd255Read: name=$name index=$index")
                val enumName = FiscalDevice.cmd255Name.valueOf(name)
                val response = fd.Command255Read(enumName, index)
                // Extract the value from FiscalResponse
                if (response != null && !response.isEmpty) {
                    val keys = response.keys
                    if (keys.isNotEmpty()) {
                        val value = response.get(keys.first()) ?: ""
                        Log.d(TAG, "cmd255Read($name) = '$value'")
                        value
                    } else ""
                } else ""
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "cmd255Read: enum '$name' not found, trying customCommand")
                // Enum name not found — try customCommand as fallback
                try {
                    val params = if (index.isEmpty()) name else "$name\t$index"
                    fd.customCommand(255, params) ?: ""
                } catch (e2: Exception) {
                    Log.e(TAG, "cmd255Read customCommand fallback failed: ${e2.message}")
                    ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "cmd255Read($name) EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                ""
            }
        }
    }

    override suspend fun cmd255Write(name: String, index: String, value: String): Boolean {
        val fd = acquire() ?: return false
        return useFd {
            try {
                val enumName = FiscalDevice.cmd255Name.valueOf(name)
                fd.Command255Write(enumName, index, value)
                true
            } catch (e: IllegalArgumentException) {
                // Enum name not found — try customCommand as fallback
                try {
                    val params = if (index.isEmpty()) "$name\t$value" else "$name\t$index\t$value"
                    fd.customCommand(255, params)
                    true
                } catch (_: Exception) { false }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    // ==================== FISCAL REPORT TEXT READING (Command 95 Version2) ====================

    /**
     * Read fiscal memory report by Z number as text lines.
     * Uses command95Variant0Version0 with type "10" (file) then reads lines with Variant0Version2.
     * @return List of text lines forming the report
     */
    suspend fun readFiscalReportByZText(startZ: String, endZ: String, detailed: Boolean): List<String> {
        val fd = acquire() ?: throw IOException(lastAccessError.ifEmpty { "Not connected" })
        return useFd {
            val type = if (detailed) "11" else "10"
            fd.command95Variant0Version0(type, startZ, endZ)
            val lines = mutableListOf<String>()
            while (true) {
                try {
                    val r = fd.command95Variant0Version2()
                    val line = r.get("TextData") ?: ""
                    lines.add(line)
                } catch (e: FiscalException) {
                    if (e.errorCode == FiscalException.ERR_END_OF_DATA ||
                        e.errorCode == FiscalException.ERR_EJ_NO_RECORDS) break
                    else throw e
                }
            }
            lines
        }
    }

    // ==================== ANAF DOWNLOAD (Command 128) ====================

    /**
     * Read ANAF file list from device (BASE64 encoded XML).
     * @param from  "DD-MM-YY hh:mm:ss" or Z number (1-2500)
     * @param to    "DD-MM-YY hh:mm:ss" or Z number (1-2500)
     * @return BASE64-encoded XML string with file list
     */
    suspend fun readAnafFileListBase64(from: String, to: String): Pair<String, Int> {
        val fd = acquire() ?: throw IOException(lastAccessError.ifEmpty { "Not connected" })
        return useFd {
            // Set range
            Log.d(TAG, "ANAF cmd128v0: Setting range from=$from to=$to")
            val r0 = fd.command128Variant0Version0(from, to)
            Log.d(TAG, "ANAF cmd128v0 OK")

            // First call to get DecodeBase64Len
            Log.d(TAG, "ANAF cmd128v1: Reading first chunk (getting file size)...")
            val r1 = fd.command128Variant1Version0()
            val xmlLen = r1.getInt("DecodeBase64Len")
            Log.d(TAG, "ANAF cmd128v1: DecodeBase64Len=$xmlLen")
            if (xmlLen <= 0) throw IOException("No ANAF data in range ($from - $to)")

            // Read all chunks
            val sb = StringBuilder()
            var chunkCount = 0
            while (true) {
                try {
                    val rN = fd.command128Variant1Version0()
                    val chunk = rN.get("Base64Data") ?: ""
                    sb.append(chunk)
                    chunkCount++
                } catch (e: FiscalException) {
                    if (e.errorCode == FiscalException.ERR_END_OF_DATA) {
                        Log.d(TAG, "ANAF file list: Read $chunkCount chunks, total base64=${sb.length}")
                        break
                    } else throw e
                }
            }
            Pair(sb.toString(), xmlLen)
        }
    }

    /**
     * Read a specific ANAF file from device (BASE64 encoded).
     * @param fileName The file path on the device (e.g. "XML/0369/Z0102_3130411777.xml")
     * @return BASE64-encoded file content
     */
    suspend fun readAnafFileBase64(fileName: String): String {
        val fd = acquire() ?: throw IOException(lastAccessError.ifEmpty { "Not connected" })
        return useFd {
            // Select file
            Log.d(TAG, "ANAF cmd128v2: Selecting file: $fileName")
            fd.command128Variant2Version0(fileName)

            // First call to get DecodeBase64Len
            val r1 = fd.command128Variant1Version0()
            val fileLen = r1.getInt("DecodeBase64Len")
            Log.d(TAG, "ANAF file $fileName: size=$fileLen bytes")
            if (fileLen <= 0) throw IOException("Empty ANAF file: $fileName")

            // Read all chunks
            val sb = StringBuilder()
            var chunkCount = 0
            while (true) {
                try {
                    val rN = fd.command128Variant1Version0()
                    val chunk = rN.get("Base64Data") ?: ""
                    sb.append(chunk)
                    chunkCount++
                } catch (e: FiscalException) {
                    if (e.errorCode == FiscalException.ERR_END_OF_DATA) {
                        Log.d(TAG, "ANAF file $fileName: $chunkCount chunks, base64=${sb.length}")
                        break
                    } else throw e
                }
            }
            sb.toString()
        }
    }

}
