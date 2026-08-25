package ru.zf.pravka.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

// Full proofread history as JSONL on the device (owner's request - he feeds
// it to a bigger model for quality analysis). This intentionally overrides
// spec section 14 "do not persist fix texts": the owner asked for exactly
// that, and the file never leaves the device except via his own share action.
class HistoryLog(private val dir: File) {

    companion object {
        private const val FILE_NAME = "history.jsonl"
        private const val MAX_BYTES = 5L * 1024 * 1024
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    /** Публичный: экспорт «Поделиться» живёт на стороне телефона. */
    val file: File by lazy { File(dir, FILE_NAME) }

    fun append(
        mode: String,
        providerId: String,
        model: String,
        latencyMs: Long,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        changed: Boolean,
        input: String,
        output: String,
        error: String?,
        cacheWriteTokens: Int = 0,
        cacheReadTokens: Int = 0,
    ) {
        // Off the caller thread (this runs right after a proofread lands):
        // DiskWriter's single thread also provides the ordering @Synchronized
        // used to. Timestamp captured here so entries carry the real time.
        val at = Date()
        DiskWriter.post {
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(dir, "$FILE_NAME.1")
                backup.delete()
                file.renameTo(backup)
            }
            val entry = JSONObject().apply {
                put("ts", timestampFormat.format(at))
                put("mode", mode)
                put("provider", providerId)
                put("model", model)
                put("latency_ms", latencyMs)
                put("input_tokens", inputTokens)
                if (cacheWriteTokens > 0) put("cache_write_tokens", cacheWriteTokens)
                if (cacheReadTokens > 0) put("cache_read_tokens", cacheReadTokens)
                put("output_tokens", outputTokens)
                put("cost_usd", costUsd)
                put("changed", changed)
                put("input", input)
                put("output", output)
                if (error != null) put("error", error)
            }
            file.appendText(entry.toString() + "\n")
        }
    }

    fun exists(): Boolean = file.exists() && file.length() > 0

    /**
     * Last [limit] successful CHANGED fixes as (dictated input, final output) -
     * the raw material the dictionary miner looks for recurring ASR
     * misrecognitions in.
     */
    fun readPairs(limit: Int): List<Pair<String, String>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().asReversed().asSequence()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .filter { !it.has("error") && it.optBoolean("changed") }
                .map { it.optString("input") to it.optString("output") }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                .take(limit)
                .toList()
        }.getOrElse { emptyList() }
    }

}
