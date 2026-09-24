package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

// Dedicated on-device log for dictation transcriptions, kept separate from
// the CLEAN proofread history (history.jsonl). Two reasons the owner wanted
// it split out:
//   1. the JSONL keeps every raw transcript so the CLEAN prompt can be tuned
//      against what the recognizer actually produced;
//   2. a metrics view (engine, chars, audio length, transcription time,
//      realtime factor) can be exported on its own to compare small vs base
//      and see how long things really take.
// The file never leaves the device except via the owner's own share action.
class TranscriptionLog(private val context: Context) {

    companion object {
        private const val FILE_NAME = "transcriptions.jsonl"
        private const val MAX_BYTES = 5L * 1024 * 1024
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private val file: File by lazy { File(DataRoot.dir(context), FILE_NAME) }

    /** One record per transcription attempt (success or failure). */
    fun append(
        engine: String,
        audioMs: Long,
        transcribeMs: Long,
        text: String,
        error: String?,
    ) {
        // Queued off the main thread: this runs on the stop tap, right before the
        // text has to land in the field.
        val at = System.currentTimeMillis()
        DiskWriter.post {
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(DataRoot.dir(context), "$FILE_NAME.1")
                backup.delete()
                file.renameTo(backup)
            }
            val entry = JSONObject().apply {
                put("ts", timestampFormat.format(Date(at)))
                put("engine", engine)
                put("audio_ms", audioMs)
                put("transcribe_ms", transcribeMs)
                put("chars", text.length)
                put("words", countWords(text))
                put("ok", error == null)
                put("text", text)
                if (error != null) put("error", error)
            }
            file.appendText(entry.toString() + "\n")
        }
    }

    // Counts whitespace-separated runs without compiling a Regex or allocating
    // the split list (the transcript can be thousands of chars).
    private fun countWords(s: String): Int {
        var n = 0
        var inWord = false
        for (c in s) {
            if (c.isWhitespace()) inWord = false
            else if (!inWord) { inWord = true; n++ }
        }
        return n
    }

    fun exists(): Boolean = file.exists() && file.length() > 0

    data class Entry(
        val ts: String,
        val engine: String,
        val audioMs: Long,
        val transcribeMs: Long,
        val chars: Int,
        val words: Int,
        val ok: Boolean,
        val text: String,
        val error: String?,
    ) {
        // >1 means slower than realtime, <1 faster. 0 when audio length unknown.
        val realtimeFactor: Double get() = if (audioMs > 0) transcribeMs.toDouble() / audioMs else 0.0
    }

    /** Last [limit] entries, newest first. */
    @Synchronized
    fun readLast(limit: Int): List<Entry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .takeLast(limit)
                .mapNotNull { line ->
                    runCatching {
                        val o = JSONObject(line)
                        Entry(
                            ts = o.optString("ts"),
                            engine = o.optString("engine"),
                            audioMs = o.optLong("audio_ms"),
                            transcribeMs = o.optLong("transcribe_ms"),
                            chars = o.optInt("chars"),
                            words = o.optInt("words"),
                            ok = o.optBoolean("ok", true),
                            text = o.optString("text"),
                            error = if (o.has("error")) o.optString("error") else null,
                        )
                    }.getOrNull()
                }
                .asReversed()
        }.getOrElse { emptyList() }
    }

    /** Время записи в миллисекундах; строка не разобралась — 0. */
    fun tsMillis(entry: Entry): Long =
        runCatching { timestampFormat.parse(entry.ts)?.time ?: 0L }.getOrDefault(0L)

    /**
     * Записи за период [fromMs, toMs), свежие сверху. Владелец (15.09): у
     * выгрузки должен быть выбор периода — день, неделя, месяц, свой, — а не
     * «весь лог целиком».
     */
    @Synchronized
    fun readRange(fromMs: Long, toMs: Long): List<Entry> =
        readLast(100_000).filter { val t = tsMillis(it); t in fromMs until toMs }

    /** Shares the raw JSONL (transcripts + metrics) for prompt tuning. */
    fun shareJsonIntent(): Intent = shareFileIntent(context, file, "application/json")

    /** Тот же JSONL, но только за период: файл собирается в кэше из отфильтрованных строк. */
    fun shareJsonIntent(fromMs: Long, toMs: Long): Intent {
        val out = File(context.cacheDir, "pravka-transcriptions.jsonl")
        out.bufferedWriter().use { w ->
            for (e in readRange(fromMs, toMs).asReversed()) {
                val o = JSONObject().apply {
                    put("ts", e.ts); put("engine", e.engine); put("audio_ms", e.audioMs)
                    put("transcribe_ms", e.transcribeMs); put("chars", e.chars); put("words", e.words)
                    put("ok", e.ok); put("text", e.text)
                    if (e.error != null) put("error", e.error)
                }
                w.write(o.toString()); w.write("\n")
            }
        }
        return shareFileIntent(context, out, "application/json")
    }

    /**
     * Writes a metrics-only CSV (no transcript text) to the cache and returns a
     * share intent for it. Columns: timestamp, engine, audio seconds,
     * transcription seconds, chars, chars/sec, realtime factor, ok.
     */
    fun shareMetricsCsvIntent(fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): Intent {
        val csv = buildString {
            append("ts,engine,audio_sec,transcribe_sec,chars,words,chars_per_sec,realtime_factor,ok\n")
            // Oldest-first in the export so a spreadsheet reads chronologically.
            val rows = if (fromMs == 0L && toMs == Long.MAX_VALUE) readLast(10_000) else readRange(fromMs, toMs)
            for (e in rows.asReversed()) {
                val audioSec = e.audioMs / 1000.0
                val transcribeSec = e.transcribeMs / 1000.0
                val charsPerSec = if (transcribeSec > 0) e.chars / transcribeSec else 0.0
                append(e.ts).append(',')
                append(e.engine).append(',')
                append(String.format(Locale.US, "%.2f", audioSec)).append(',')
                append(String.format(Locale.US, "%.2f", transcribeSec)).append(',')
                append(e.chars).append(',')
                append(e.words).append(',')
                append(String.format(Locale.US, "%.1f", charsPerSec)).append(',')
                append(String.format(Locale.US, "%.2f", e.realtimeFactor)).append(',')
                append(e.ok).append('\n')
            }
        }
        val out = File(context.cacheDir, "pravka-transcription-metrics.csv")
        out.writeText(csv)
        return shareFileIntent(context, out, "text/csv")
    }
}
