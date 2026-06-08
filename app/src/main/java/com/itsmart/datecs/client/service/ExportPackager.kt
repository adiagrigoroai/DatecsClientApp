package com.itsmart.datecs.client.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Utilitar pentru:
 *   - impachetare fisiere .p7b intr-un .zip
 *   - share via Intent.ACTION_SEND_MULTIPLE (deschide email/Gmail/Drive cu attachments)
 */
object ExportPackager {
    private const val TAG = "ExportPackager"

    /**
     * Creeaza un ZIP cu fisierele din [folder] (filtrate dupa .p7b/.xml).
     * @return file-ul ZIP creat, sau null la eroare.
     */
    fun createZip(folder: File, zipName: String): File? {
        if (!folder.exists() || !folder.isDirectory) {
            Log.w(TAG, "createZip: folder invalid ${folder.absolutePath}")
            return null
        }
        val files = folder.listFiles { f ->
            f.isFile && (f.name.endsWith(".p7b", true) || f.name.endsWith(".xml", true))
        }?.toList() ?: emptyList()
        if (files.isEmpty()) {
            Log.w(TAG, "createZip: niciun fisier .p7b/.xml in ${folder.absolutePath}")
            return null
        }
        val zipFile = File(folder, zipName)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val buffer = ByteArray(8192)
                for (file in files) {
                    zos.putNextEntry(ZipEntry(file.name))
                    BufferedInputStream(FileInputStream(file)).use { bis ->
                        while (true) {
                            val n = bis.read(buffer)
                            if (n == -1) break
                            zos.write(buffer, 0, n)
                        }
                    }
                    zos.closeEntry()
                }
            }
            Log.d(TAG, "ZIP creat: ${zipFile.absolutePath} (${zipFile.length()} bytes, ${files.size} fisiere)")
            return zipFile
        } catch (t: Throwable) {
            Log.e(TAG, "createZip EROARE: ${t.message}", t)
            return null
        }
    }

    /**
     * Lanseaza Intent.ACTION_SEND_MULTIPLE pentru share (Gmail/Outlook/Drive).
     * User-ul alege aplicatia (sau e direct Gmail daca e singura).
     */
    fun shareFiles(
        context: Context,
        files: List<File>,
        subject: String,
        body: String,
        recipientEmail: String? = null
    ): Boolean {
        if (files.isEmpty()) return false
        val authority = "${context.packageName}.provider"
        return try {
            val uris = ArrayList<Uri>()
            for (file in files) {
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(context, authority, file)
                    uris.add(uri)
                }
            }
            if (uris.isEmpty()) return false

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/octet-stream"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.putExtra(Intent.EXTRA_SUBJECT, subject)
            intent.putExtra(Intent.EXTRA_TEXT, body)
            if (!recipientEmail.isNullOrBlank()) {
                intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val chooser = Intent.createChooser(intent, "Trimite raport ANAF prin...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "shareFiles EROARE: ${t.message}", t)
            false
        }
    }

    /** Date range "luna anterioara" in formatul cerut de Datecs: "DD-MM-YY hh:mm:ss". */
    fun previousMonthRange(): Pair<String, String> {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, -1)
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) // 0-based
        val firstDay = 1
        val lastDay = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val yy = year % 100
        val mm = month + 1
        val from = String.format(Locale.US, "%02d-%02d-%02d 00:00:01", firstDay, mm, yy)
        val to = String.format(Locale.US, "%02d-%02d-%02d 23:59:59", lastDay, mm, yy)
        return Pair(from, to)
    }

    /** Returneaza (luna, anul) pentru luna anterioara. */
    fun previousMonthValues(): Pair<Int, Int> {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, -1)
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        return Pair(month, year)
    }
}
