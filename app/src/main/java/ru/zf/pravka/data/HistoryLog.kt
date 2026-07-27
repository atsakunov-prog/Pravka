package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

// Full proofread history as JSONL on the device (owner's request - he feeds
// it to a bigger model for quality analysis). This intentionally overrides
// spec section 14 "do not persist fix texts": the owner asked for exactly
// that, and the file never leaves the device except via his own share action.
class HistoryLog(private val context: Context) {

    companion object {
        private const val FILE_NAME = "history.jsonl"
        private const val MAX_BYTES = 5L * 1024 * 1024
        private const val AUTHORITY = "ru.zf.pravka.files"
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private val file: File get() = File(context.filesDir, FILE_NAME)

    @Synchronized
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
    ) {
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(context.filesDir, "$FILE_NAME.1")
                backup.delete()
                file.renameTo(backup)
            }
            val entry = JSONObject().apply {
                put("ts", timestampFormat.format(Date()))
                put("mode", mode)
                put("provider", providerId)
                put("model", model)
                put("latency_ms", latencyMs)
                put("input_tokens", inputTokens)
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

    fun shareIntent(): Intent {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
