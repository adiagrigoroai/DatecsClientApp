package com.itsmart.datecs.client.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.itsmart.datecs.client.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifica si descarca update APK din GitHub Releases.
 *
 * Flow:
 *   1. GET https://api.github.com/repos/{OWNER}/{REPO}/releases/latest
 *   2. Parse tag_name (ex: "v1.0.2") si assets[0].browser_download_url
 *   3. Daca versiunea > BuildConfig.VERSION_NAME → ofera download
 *   4. Download APK in cacheDir/update.apk
 *   5. Lanseaza PackageInstaller Intent pentru install
 */
object AppUpdateService {
    private const val TAG = "AppUpdateService"
    private const val OWNER = "adiagrigoroai"
    private const val REPO = "DatecsClientApp"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class ReleaseInfo(
        val tagName: String,           // "v1.0.2"
        val versionName: String,        // "1.0.2"
        val downloadUrl: String,        // URL APK
        val releaseNotes: String,       // body
        val publishedAt: String,        // ISO timestamp
        val sizeBytes: Long
    )

    data class CheckResult(
        val hasUpdate: Boolean,
        val current: String,
        val latest: ReleaseInfo? = null,
        val error: String = ""
    )

    /** Compara semantic two version strings: returns negative/0/positive */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.trimStart('v').split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.trimStart('v').split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i) ?: 0
            val y = pb.getOrNull(i) ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    /** Cheama API-ul GitHub, returneaza info despre ultimul release. */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_NAME
        try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DatecsClientApp/$current")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GitHub API HTTP $code")
                return@withContext CheckResult(false, current, error = "HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val versionName = tagName.trimStart('v')
            val publishedAt = json.optString("published_at", "")
            val releaseNotes = json.optString("body", "")

            // Caut primul .apk in assets
            val assets = json.optJSONArray("assets") ?: return@withContext CheckResult(false, current, error = "no assets")
            var downloadUrl = ""
            var sizeBytes = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    sizeBytes = asset.optLong("size", 0L)
                    break
                }
            }
            if (downloadUrl.isEmpty()) return@withContext CheckResult(false, current, error = "no APK asset")

            val info = ReleaseInfo(tagName, versionName, downloadUrl, releaseNotes, publishedAt, sizeBytes)
            val hasUpdate = compareVersions(versionName, current) > 0
            Log.d(TAG, "Curent: $current | Latest: $versionName | hasUpdate=$hasUpdate")
            CheckResult(hasUpdate, current, info)
        } catch (t: Throwable) {
            Log.w(TAG, "checkForUpdate EROARE: ${t.message}")
            CheckResult(false, current, error = t.message ?: "eroare necunoscuta")
        }
    }

    /**
     * Descarca APK-ul in cacheDir si returneaza File-ul.
     * onProgress: (downloadedBytes, totalBytes)
     */
    suspend fun downloadApk(
        context: Context,
        info: ReleaseInfo,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(context.cacheDir, "update_${info.versionName}.apk")
            if (outFile.exists()) outFile.delete()

            val conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DatecsClientApp/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "downloadApk HTTP ${conn.responseCode}")
                return@withContext null
            }
            val total = if (info.sizeBytes > 0) info.sizeBytes else conn.contentLengthLong

            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            Log.d(TAG, "Descarcat: ${outFile.absolutePath} (${outFile.length()} bytes)")
            outFile
        } catch (t: Throwable) {
            Log.w(TAG, "downloadApk EROARE: ${t.message}")
            null
        }
    }

    /**
     * Lanseaza intent-ul de install (Android 7+ foloseste FileProvider).
     * User-ul va vedea pop-up Android cerand confirmare + (la prima dată) "Install unknown apps".
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "installApk EROARE: ${t.message}", t)
            false
        }
    }

    /** True daca Android >= O si user-ul nu a dat permisiunea "Install unknown apps" pentru aceasta app. */
    fun needsInstallPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            !context.packageManager.canRequestPackageInstalls()
        } else false
    }

    /** Lanseaza setarea Android pentru "Install unknown apps". */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) } catch (_: Throwable) {}
        }
    }
}
