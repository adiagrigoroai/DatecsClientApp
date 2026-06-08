package com.itsmart.datecs.client.device

import android.os.Environment
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

data class AnafFileInfo(
    val name: String,      // path on device: "XML/0369/Z0102_3130411777.xml"
    val saveAs: String     // local filename: "1234445551_Z0102.p7b"
)

data class AnafDownloadResult(
    val success: Boolean,
    val fileCount: Int,
    val savedPath: String,
    val files: List<String>,
    val error: String = ""
)

class AnafDownloader(private val connection: DudeDeviceConnection) {

    companion object {
        private const val TAG = "AnafDownloader"

        val LUNI_RO = arrayOf(
            "Ianuarie", "Februarie", "Martie", "Aprilie", "Mai", "Iunie",
            "Iulie", "August", "Septembrie", "Octombrie", "Noiembrie", "Decembrie"
        )

        /**
         * Build structured folder name: {NumeClient}_{SerieAparat}_{Luna}_{An}
         * Example: "McDonaldsSRL_DT123456_Februarie_2026"
         */
        fun buildFolderName(clientName: String, serialNumber: String): String {
            val cal = Calendar.getInstance()
            val luna = LUNI_RO[cal.get(Calendar.MONTH)]
            val an = cal.get(Calendar.YEAR)
            val safeName = sanitizePath(clientName.ifEmpty { "Client" })
            val safeSerial = sanitizePath(serialNumber.ifEmpty { "UNKNOWN" })
            return "${safeName}_${safeSerial}_${luna}_$an"
        }

        fun sanitizePath(input: String): String {
            return input.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifEmpty { "UNKNOWN" }
        }
    }

    /**
     * Full download workflow:
     * 1. Get file list from device for the given range
     * 2. Download each file
     * 3. Save to local storage with structured folder
     *
     * @param from "DD-MM-YY hh:mm:ss" or Z number string
     * @param to   "DD-MM-YY hh:mm:ss" or Z number string
     * @param clientName Client name for folder structure
     * @param serialNumber Device serial number
     * @param basePath Base path for saving (null = Documents/DatecsANAF)
     * @param onProgress callback with (current, total, fileName)
     * @return download result
     */
    suspend fun downloadAnafFiles(
        from: String,
        to: String,
        clientName: String = "",
        serialNumber: String = "",
        basePath: String? = null,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): AnafDownloadResult {
        try {
            Log.d(TAG, "Starting ANAF download: from=$from to=$to client=$clientName serial=$serialNumber")

            // Step 1: Get file list
            onProgress(0, 0, "Citire lista fisiere de pe aparat...")
            Log.d(TAG, "Step 1: Requesting file list (cmd128 Variant0+Variant1)...")
            val (base64Xml, xmlLen) = try {
                connection.readAnafFileListBase64(from, to)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Step 1 FAILED - IO error reading file list: ${e.message}", e)
                return AnafDownloadResult(false, 0, "", emptyList(),
                    "Eroare comunicare la citire lista fisiere: ${e.message}\nVerificati conexiunea la aparat.")
            } catch (e: Exception) {
                Log.e(TAG, "Step 1 FAILED - Error reading file list: ${e.javaClass.simpleName}: ${e.message}", e)
                return AnafDownloadResult(false, 0, "", emptyList(),
                    "Eroare citire lista fisiere: ${e.javaClass.simpleName}: ${e.message}")
            }
            Log.d(TAG, "Step 1 OK: Got file list XML, base64 length=${base64Xml.length}, expected decoded=$xmlLen")
            onProgress(0, 0, "Decodare lista fisiere...")

            // Decode BASE64 to XML
            val xmlBytes = android.util.Base64.decode(base64Xml, android.util.Base64.DEFAULT)
            Log.d(TAG, "Decoded XML: ${xmlBytes.size} bytes (expected $xmlLen)")

            // Parse XML to get file names
            val fileList = parseAnafFileList(xmlBytes)
            Log.d(TAG, "Parsed ${fileList.size} ANAF files from XML")

            if (fileList.isEmpty()) {
                return AnafDownloadResult(false, 0, "", emptyList(), "Nu s-au gasit fisiere ANAF in intervalul selectat")
            }

            onProgress(0, fileList.size, "Gasite ${fileList.size} fisiere. Pregatire download...")

            // Step 2: Create structured output directory
            val baseDir = if (!basePath.isNullOrEmpty()) {
                File(basePath)
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DatecsANAF")
            }
            val folderName = buildFolderName(clientName, serialNumber)
            val outputDir = File(baseDir, folderName)
            if (!outputDir.exists()) {
                val created = outputDir.mkdirs()
                Log.d(TAG, "Step 2: Output dir created=$created: ${outputDir.absolutePath}")
            } else {
                Log.d(TAG, "Step 2: Output dir exists: ${outputDir.absolutePath}")
            }

            // Step 3: Download and save each file
            val savedFiles = mutableListOf<String>()
            val errors = mutableListOf<String>()
            for ((index, fileInfo) in fileList.withIndex()) {
                onProgress(index + 1, fileList.size, "Descarcare: ${fileInfo.saveAs}")
                Log.d(TAG, "Step 3: Downloading [${index + 1}/${fileList.size}]: ${fileInfo.name} -> ${fileInfo.saveAs}")

                try {
                    val base64Content = connection.readAnafFileBase64(fileInfo.name)
                    val fileBytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)

                    val outFile = File(outputDir, fileInfo.saveAs)
                    BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                        bos.write(fileBytes)
                    }
                    savedFiles.add(fileInfo.saveAs)
                    Log.d(TAG, "Saved OK: ${outFile.absolutePath} (${fileBytes.size} bytes)")
                } catch (e: java.io.IOException) {
                    val errMsg = "IO eroare la ${fileInfo.saveAs}: ${e.message}"
                    Log.e(TAG, "Download FAILED [${index + 1}]: $errMsg", e)
                    errors.add(errMsg)
                } catch (e: Exception) {
                    val errMsg = "${e.javaClass.simpleName} la ${fileInfo.saveAs}: ${e.message}"
                    Log.e(TAG, "Download FAILED [${index + 1}]: $errMsg", e)
                    errors.add(errMsg)
                }
            }

            val errorSummary = if (errors.isNotEmpty()) "\nErori (${errors.size}): ${errors.joinToString("; ")}" else ""
            return AnafDownloadResult(
                success = savedFiles.isNotEmpty(),
                fileCount = savedFiles.size,
                savedPath = outputDir.absolutePath,
                files = savedFiles,
                error = errorSummary
            )
        } catch (e: java.io.IOException) {
            Log.e(TAG, "ANAF download IO FAILED: ${e.message}", e)
            return AnafDownloadResult(false, 0, "", emptyList(),
                "Eroare comunicare: ${e.message}\nVerificati conexiunea la aparat.")
        } catch (e: Exception) {
            Log.e(TAG, "ANAF download FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            return AnafDownloadResult(false, 0, "", emptyList(),
                "Eroare ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseAnafFileList(xmlBytes: ByteArray): List<AnafFileInfo> {
        val files = mutableListOf<AnafFileInfo>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), null)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "file") {
                    val name = parser.getAttributeValue(null, "name") ?: ""
                    val saveAs = parser.getAttributeValue(null, "saveas") ?: ""
                    if (name.isNotEmpty() && saveAs.isNotEmpty()) {
                        files.add(AnafFileInfo(name, saveAs))
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "XML parse error: ${e.message}", e)
        }
        return files
    }
}
