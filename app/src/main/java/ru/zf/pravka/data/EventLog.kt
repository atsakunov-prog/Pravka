package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Lightweight diagnostic log for the live dictation session (start, segments,
// error codes, restarts, stop reason). Plain timestamped lines, size-capped, so
// when a take misbehaves the owner can export it and the actual
// SpeechRecognizer error codes are visible. Stays on-device; shared only by
// the owner's own action.
//
// Writes go through DiskWriter (a recognizer callback must never block on the
// filesystem) and keep one handle open instead of reopening per line. Each line
// is still flushed - the whole point of this log is to survive a crash.
class EventLog(private val context: Context) {

    companion object {
        private const val FILE_NAME = "dictation-events.log"
        private const val MAX_BYTES = 512L * 1024
    }

    // Only touched on the DiskWriter thread.
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var written = -1L   // -1 = not yet measured

    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    fun add(line: String) {
        // Timestamp on the caller's thread so the ordering the owner reads is
        // the real event ordering, not the drain ordering.
        val at = System.currentTimeMillis()
        DiskWriter.post { append(at, line) }
    }

    private fun append(at: Long, line: String) {
        if (written < 0) written = if (file.exists()) file.length() else 0L
        if (written > MAX_BYTES) rotate()
        val text = "${stamp.format(Date(at))}  $line\n"
        val w = writer ?: BufferedWriter(FileWriter(file, true)).also { writer = it }
        w.write(text)
        w.flush()
        written += text.length
    }

    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        val backup = File(context.filesDir, "$FILE_NAME.1")
        backup.delete()
        file.renameTo(backup)
        written = 0L
    }

    fun exists(): Boolean = file.exists() && file.length() > 0

    fun shareIntent(): Intent = shareFileIntent(context, file, "text/plain")
}
