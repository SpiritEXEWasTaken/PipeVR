package org.schabi.newpipe.pipevr

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PipeVR test-day safety net, so problems are never lost even when nobody started the log
 * recorder:
 * - every crash (Java/Kotlin) is written to files/crashes/crash-<time>.txt together with the
 *   last log lines of the app;
 * - the app's own log (including native "Fatal signal" lines from the VR code) is recorded
 *   continuously to files/logs/app-<n>.log, keeping the last few files.
 * Files live in Android/data/<app id>/files/, readable over adb without root.
 */
object PipeVrCrashLog {
    private const val TAG = "PipeVRCrashLog"
    private const val MAX_LOG_BYTES = 4L * 1024 * 1024
    private const val KEEP_LOGS = 4
    private const val KEEP_CRASHES = 30

    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        val crashes = File(base, "crashes").apply { mkdirs() }
        val logs = File(base, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrash(crashes, thread, error)
            } catch (e: Throwable) {
                // never get in the way of the normal crash handling
            }
            previous?.uncaughtException(thread, error)
        }
        startRecorder(logs)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    private fun writeCrash(dir: File, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val recent = try {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "400",
                "--pid=${Process.myPid()}").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            "(log unavailable: $e)"
        }
        File(dir, "crash-${stamp()}.txt").writeText(buildString {
            append("PipeVR crash at ${Date()}\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
            append("Thread: ${thread.name}\n\n")
            append(trace)
            append("\n---- last log lines ----\n")
            append(recent)
        })
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_CRASHES)
            ?.forEach { it.delete() }
    }

    /** Streams this process' logcat into rotating files on a background thread. */
    private fun startRecorder(dir: File) {
        Thread({
            try {
                val pid = Process.myPid()
                val p = ProcessBuilder("logcat", "-v", "time", "--pid=$pid")
                    .redirectErrorStream(true).start()
                val reader = p.inputStream.bufferedReader()
                var file = File(dir, "app-${stamp()}.log")
                var out = file.bufferedWriter()
                prune(dir)
                var written = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    out.write(line)
                    out.newLine()
                    written += line.length + 1
                    if (!reader.ready()) out.flush()
                    if (written > MAX_LOG_BYTES) {
                        out.close()
                        file = File(dir, "app-${stamp()}.log")
                        out = file.bufferedWriter()
                        written = 0
                        prune(dir)
                    }
                }
                out.close()
            } catch (e: Exception) {
                Log.w(TAG, "log recorder stopped", e)
            }
        }, "pipevr-log-recorder").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun prune(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_LOGS)
            ?.forEach { it.delete() }
    }
}
