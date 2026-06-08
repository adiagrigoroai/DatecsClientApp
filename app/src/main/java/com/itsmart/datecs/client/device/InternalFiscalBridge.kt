package com.itsmart.datecs.client.device

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Bridge catre fiscal-ul intern al device-urilor Datecs Android (ex: BC50, DP-25X).
 *
 * Foloseste reflexie peste libraria de sistem `com.android.fiscal` (in /system/framework/),
 * astfel incat APK-ul ramane compilabil si executabil pe orice device — pe device-uri
 * fara fiscal intern [isAvailable] returneaza false si nu schimba nimic.
 *
 * API folosit (intern, descoperit prin dexdump):
 *   FiscalManager.getInstance(context) -> FiscalManager
 *   fm.getStatus(): Int                 // STATUS_ON=1, STATUS_OFF=0
 *   fm.turnOn(): void
 *   fm.getInformation(): Information    // serialNumber, firmwareVersion, fiscalMemory, ...
 *   fm.openDevice(): FiscalSocket       // .inputStream / .outputStream
 *
 * Dupa openDevice() comunicarea cu fiscal-ul e identica cu USB/BT (DUDE binar).
 */
object InternalFiscalBridge {
    private const val TAG = "InternalFiscalBridge"
    private const val CLS_MANAGER = "com.android.fiscal.FiscalManager"
    private const val CLS_SOCKET = "com.android.fiscal.FiscalSocket"
    private const val CLS_INFO = "com.android.fiscal.Information"

    private fun mgrClass(): Class<*>? = try { Class.forName(CLS_MANAGER) } catch (_: Throwable) { null }

    /** True doar pe device-uri unde libraria `com.android.fiscal` exista in framework. */
    fun isAvailable(): Boolean = mgrClass() != null

    private fun getManager(context: Context): Any? {
        val cls = mgrClass() ?: return null
        return try {
            cls.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (t: Throwable) {
            Log.w(TAG, "FiscalManager.getInstance EROARE: ${t.message}")
            null
        }
    }

    /** STATUS_ON=1 / STATUS_OFF=0 / null daca libraria nu exista. */
    fun getStatus(context: Context): Int? {
        val fm = getManager(context) ?: return null
        return try {
            fm.javaClass.getMethod("getStatus").invoke(fm) as? Int
        } catch (t: Throwable) {
            Log.w(TAG, "getStatus EROARE: ${t.message}")
            null
        }
    }

    /** Porneste fiscal-ul daca e oprit. Idempotent. */
    fun turnOn(context: Context): Boolean {
        val fm = getManager(context) ?: return false
        return try {
            fm.javaClass.getMethod("turnOn").invoke(fm)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "turnOn EROARE: ${t.message}")
            false
        }
    }

    data class FiscalInfo(
        val serialNumber: String,
        val firmwareName: String,
        val firmwareVersion: String,
        val fiscalMemory: String,
        val cpuSerialNumber: String,
        val flashIdentifier: String
    )

    fun getInfo(context: Context): FiscalInfo? {
        val fm = getManager(context) ?: return null
        return try {
            val info = fm.javaClass.getMethod("getInformation").invoke(fm) ?: return null
            val cls = info.javaClass
            fun str(field: String): String =
                try { (cls.getField(field).get(info) as? String) ?: "" } catch (_: Throwable) { "" }
            FiscalInfo(
                serialNumber = str("serialNumber"),
                firmwareName = str("firmwareName"),
                firmwareVersion = str("firmwareVersion"),
                fiscalMemory = str("fiscalMemory"),
                cpuSerialNumber = str("cpuSerialNumber"),
                flashIdentifier = str("flashIdentifier")
            )
        } catch (t: Throwable) {
            Log.w(TAG, "getInfo EROARE: ${t.message}")
            null
        }
    }

    /** Holder pentru streamurile DUDE + closer-ul intern. */
    class InternalSession(
        val inputStream: InputStream,
        val outputStream: OutputStream,
        private val socket: Any
    ) {
        fun close() {
            try { socket.javaClass.getMethod("close").invoke(socket) } catch (_: Throwable) {}
        }
    }

    /**
     * Deschide o sesiune catre fiscal-ul intern.
     * Pe BC50 face turnOn() automat daca status == STATUS_OFF.
     */
    fun open(context: Context): InternalSession? {
        val fm = getManager(context) ?: return null
        return try {
            // Asigura ca fiscal-ul e pornit.
            val status = (fm.javaClass.getMethod("getStatus").invoke(fm) as? Int) ?: 0
            if (status != 1) {
                try { fm.javaClass.getMethod("turnOn").invoke(fm) } catch (_: Throwable) {}
                // Mic delay ca firmware-ul sa se ridice; in practica BC50 raspunde imediat.
                Thread.sleep(800)
            }
            val socket = fm.javaClass.getMethod("openDevice").invoke(fm) ?: return null
            val socketCls = socket.javaClass
            val inp = socketCls.getMethod("getInputStream").invoke(socket) as? InputStream
                ?: return null
            val out = socketCls.getMethod("getOutputStream").invoke(socket) as? OutputStream
                ?: run {
                    try { inp.close() } catch (_: Throwable) {}
                    return null
                }
            InternalSession(inp, out, socket)
        } catch (t: Throwable) {
            Log.w(TAG, "open EROARE: ${t.message}")
            null
        }
    }
}
