package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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
        private const val AUTHORITY = "ru.zf.pravka.files"
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** One record per transcription attempt (success or failure). */
    @Synchronized
    fun append(
        engine: String,
        audioMs: Long,
        transcribeMs: Long,
        text: String,
        error: String?,
    ) {
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(context.filesDir, "$FILE_NAME.1")
                backup.delete()
                file.renameTo(backup)
            }
            val entry = JSONObject().apply {
                put("ts", timestampFormat.format(Date()))
                put("engine", engine)
                put("audio_ms", audioMs)
                put("transcribe_ms", transcribeMs)
                put("chars", text.length)
                put("words", if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size)
                put("ok", error == null)
                put("text", text)
                if (error != null) put("error", error)
            }
            file.appendText(entry.toString() + "\n")
        }
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

    /** Shares the raw JSONL (transcripts + metrics) for prompt tuning. */
    fun shareJsonIntent(): Intent {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Writes a metrics-only CSV (no transcript text) to the cache and returns a
     * share intent for it. Columns: timestamp, engine, audio seconds,
     * transcription seconds, chars, chars/sec, realtime factor, ok.
     */
    fun shareMetricsCsvIntent(): Intent {
        val csv = buildString {
            append("ts,engine,audio_sec,transcribe_sec,chars,words,chars_per_sec,realtime_factor,ok\n")
            // Oldest-first in the export so a spreadsheet reads chronologically.
            for (e in readLast(10_000).asReversed()) {
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
        val uri = FileProvider.getUriForFile(context, AUTHORITY, out)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
