package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Lightweight diagnostic log for the live dictation session (start, segments,
// error codes, restarts, stop reason). Plain timestamped lines, size-capped, so
// when a take mysteriously cuts off the owner can export it and the actual
// SpeechRecognizer error codes are visible. Stays on-device; shared only by
// the owner's own action.
class EventLog(private val context: Context) {

    companion object {
        private const val FILE_NAME = "dictation-events.log"
        private const val MAX_BYTES = 512L * 1024
        private const val AUTHORITY = "ru.zf.pravka.files"
    }

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val file: File get() = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun add(line: String) {
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(context.filesDir, "$FILE_NAME.1")
                backup.delete()
                file.renameTo(backup)
            }
            file.appendText("${ts.format(Date())}  $line\n")
        }
    }

    fun exists(): Boolean = file.exists() && file.length() > 0

    @Synchronized
    fun clear() = runCatching { file.delete() }.let {}

    fun shareIntent(): Intent {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
