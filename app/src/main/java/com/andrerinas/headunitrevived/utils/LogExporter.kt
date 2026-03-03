package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    enum class Verbosity(val filter: String, val logLevel: Int) {
        VERBOSE("*:V", Log.VERBOSE),
        DEBUG("*:D", Log.DEBUG),
        INFO("*:I", Log.INFO),
        WARNING("*:W", Log.WARN),
        ERROR("*:E", Log.ERROR)
    }

    private const val MAX_LOG_FILES = 5
    private const val MAX_TOTAL_SIZE = 50L * 1024 * 1024 // 50 MB

    private var captureProcess: Process? = null
    private var captureFile: File? = null

    val isCapturing: Boolean get() = captureProcess != null

    /**
     * Deletes the oldest HUR_Log_* files until the count is below [MAX_LOG_FILES]
     * and the total size is below [MAX_TOTAL_SIZE], preserving the most recent files.
     */
    private fun rotateLogs(logDir: File) {
        val files = logDir.listFiles { _, name -> name.startsWith("HUR_Log_") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return

        while (files.size >= MAX_LOG_FILES) {
            files.removeAt(0).delete()
        }

        var totalSize = files.sumOf { it.length() }
        while (totalSize > MAX_TOTAL_SIZE && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            totalSize -= oldest.length()
            oldest.delete()
        }
    }

    /**
     * Starts a continuous logcat process writing to a timestamped file.
     * Unlike [saveLogToPublicFile], this captures everything from the moment it is called,
     * bypassing the small shared ring buffer.
     */
    fun startCapture(context: Context, verbosity: Verbosity) {
        stopCapture()
        val logDir = context.getExternalFilesDir(null) ?: return
        logDir.mkdirs()
        rotateLogs(logDir)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(logDir, "HUR_Log_$timeStamp.txt")
        captureFile = file

        try {
            captureProcess = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "brief", verbosity.filter, "-f", file.absolutePath)
            )
        } catch (e: IOException) {
            AppLog.e("Failed to start log capture", e)
            captureFile = null
        }
    }

    /** Stops the continuous capture process. */
    fun stopCapture() {
        captureProcess?.destroy()
        captureProcess = null
    }

    /**
     * Writes logs to a timestamped file and returns it.
     * - If a capture file is available (capture was started, active or already stopped):
     *   copies its content into a fresh export file so the original capture file is preserved.
     * - Otherwise: dumps the current logcat ring buffer.
     */
    fun saveLogToPublicFile(context: Context, verbosity: Verbosity): File? {
        val logDir = context.getExternalFilesDir(null) ?: return null
        if (!logDir.exists()) logDir.mkdirs()

        val source = captureFile
        if (source != null && source.exists() && source.length() > 0) {
            captureFile = null
            return source
        }

        return try {
            rotateLogs(logDir)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(logDir, "HUR_Log_$timeStamp.txt")
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", verbosity.filter, "-f", logFile.absolutePath)
            ).waitFor()
            logFile
        } catch (e: Exception) {
            AppLog.e("Failed to save logs", e)
            null
        }
    }

    fun shareLogFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share Log File")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}