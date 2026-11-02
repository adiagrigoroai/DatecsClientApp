package com.itsmart.datecs.client

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AmefMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Pe BlueCash casa e modulul intern, iar pe USB sta terminalul BluePad (acelasi VID/PID ca o casa Datecs):
        // aplicatia nu trebuie sa apara / sa porneasca la conectarea lui.
        try {
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(this, "$packageName.UsbDatecsAlias"),
                if (com.itsmart.datecs.client.service.AppUpdateService.isBlueCash) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val deviceInfo = buildString {
                    appendLine("=== AMEF Monitor Crash Report ===")
                    appendLine("Timestamp: $timestamp")
                    appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine("=== Stack Trace ===")
                    appendLine(stackTrace)
                    appendLine()
                    appendLine("=== Cause Chain ===")
                    var cause = throwable.cause
                    var depth = 0
                    while (cause != null && depth < 10) {
                        appendLine("Caused by [${depth}]: ${cause.javaClass.name}: ${cause.message}")
                        cause = cause.cause
                        depth++
                    }
                }

                // Save to internal file
                val crashDir = File(filesDir, "crash_logs")
                crashDir.mkdirs()
                val crashFile = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
                crashFile.writeText(deviceInfo)

                // Save to external storage (visible in file manager)
                try {
                    val extDir = getExternalFilesDir(null)
                    if (extDir != null) {
                        val extCrashFile = File(extDir, "crash_log.txt")
                        extCrashFile.writeText(deviceInfo)
                        Log.e("AMEF_CRASH", "External crash log: ${extCrashFile.absolutePath}")
                    }
                } catch (_: Exception) {}

                // Save to SharedPreferences + CLEAR AUTH TOKEN to prevent crash loop
                val prefs = getSharedPreferences("datecs_client", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("last_crash", deviceInfo)
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .remove("auth_token")  // Clear token so app shows LoginScreen on restart
                    .apply()

                Log.e("AMEF_CRASH", "Crash saved to: ${crashFile.absolutePath}")
                Log.e("AMEF_CRASH", deviceInfo)

            } catch (e: Exception) {
                Log.e("AMEF_CRASH", "Failed to save crash: ${e.message}")
            }

            // Call default handler to let Android handle the crash normally
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /**
         * Check if there's a pending crash report and return it.
         * Call this from MainActivity on start.
         */
        fun getLastCrashReport(context: Context): String? {
            val prefs = context.getSharedPreferences("datecs_client", Context.MODE_PRIVATE)
            return prefs.getString("last_crash", null)
        }

        /**
         * Clear the crash report after it has been sent/dismissed.
         */
        fun clearCrashReport(context: Context) {
            val prefs = context.getSharedPreferences("datecs_client", Context.MODE_PRIVATE)
            prefs.edit().remove("last_crash").remove("last_crash_time").apply()
        }

        /**
         * Create an email intent with the crash report.
         */
        fun createCrashEmailIntent(crashReport: String): Intent {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("suport@itsmartretail.ro"))
                putExtra(Intent.EXTRA_SUBJECT, "AMEF Monitor Crash Report - ${
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
                }")
                putExtra(Intent.EXTRA_TEXT, crashReport)
            }
            return Intent.createChooser(intent, "Trimite raport eroare")
        }
    }
}
