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
class EventLog(
    private val context: Context,
    private val fileName: String = "dictation-events.log",
    /** Потолок файла до ротации. У лога запросов к Claude — больше: один запрос это десятки килобайт. */
    private val maxBytes: Long = MAX_BYTES,
) {

    companion object {
        private const val MAX_BYTES = 512L * 1024
    }

    // Only touched on the DiskWriter thread.
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var written = -1L   // -1 = not yet measured

    private val file: File by lazy { File(DataRoot.dir(context), fileName) }

    fun add(line: String) {
        // Timestamp on the caller's thread so the ordering the owner reads is
        // the real event ordering, not the drain ordering.
        val at = System.currentTimeMillis()
        DiskWriter.post { append(at, line) }
    }

    private fun append(at: Long, line: String) {
        if (written < 0) written = if (file.exists()) file.length() else 0L
        if (written > maxBytes) rotate()
        val text = "${stamp.format(Date(at))}  $line\n"
        val w = writer ?: BufferedWriter(FileWriter(file, true)).also { writer = it }
        w.write(text)
        w.flush()
        // Bytes, not chars: Cyrillic is ~2 bytes/char in UTF-8, and counting
        // chars let the file grow to double the stated cap before rotating.
        written += text.toByteArray(Charsets.UTF_8).size
    }

    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        val backup = File(DataRoot.dir(context), "$fileName.1")
        backup.delete()
        file.renameTo(backup)
        written = 0L
    }

    fun exists(): Boolean = file.exists() && file.length() > 0

    /** Стереть лог целиком (кнопка «Очистить» у лога запросов). */
    fun clear() {
        DiskWriter.post {
            runCatching { writer?.close() }
            writer = null
            file.delete()
            File(DataRoot.dir(context), "$fileName.1").delete()
            written = 0L
        }
    }

    /** Newest [n] lines for the on-screen log viewer (call off the main thread). */
    fun readLast(n: Int): List<String> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().takeLast(n)
    }.getOrDefault(emptyList())

    fun shareIntent(): Intent = shareFileIntent(context, file, "text/plain")

    /**
     * Лог за период [fromMs, toMs). У строк нет года — только «MM-dd HH:mm:ss»;
     * год берётся текущий, а дата из будущего читается как прошлогодняя (лог
     * живёт неделями, а не годами, так что двусмысленности нет).
     */
    fun shareRangeIntent(fromMs: Long, toMs: Long): Intent {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val now = System.currentTimeMillis()
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun lineMs(line: String): Long {
            if (line.length < 17) return -1L
            val stamp = line.substring(0, 14)   // "MM-dd HH:mm:ss"
            var t = runCatching { parser.parse("$year-$stamp")?.time ?: -1L }.getOrDefault(-1L)
            if (t > now + 86_400_000L) {
                t = runCatching { parser.parse("${year - 1}-$stamp")?.time ?: -1L }.getOrDefault(-1L)
            }
            return t
        }
        val lines = runCatching { if (file.exists()) file.readLines() else emptyList() }.getOrDefault(emptyList())
        val out = File(context.cacheDir, "pravka-events.log")
        out.bufferedWriter().use { w ->
            for (line in lines) {
                val t = lineMs(line)
                if (t in fromMs until toMs) { w.write(line); w.write("\n") }
            }
        }
        return shareFileIntent(context, out, "text/plain")
    }
}
