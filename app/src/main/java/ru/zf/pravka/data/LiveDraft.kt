package ru.zf.pravka.data

import android.content.Context
import java.io.File

// Crash-safe autosave of the live Google dictation. That engine keeps no WAV,
// so if the phone dies or the app is killed mid-take the audio is gone - but
// the recognized words are checkpointed here as they stream in, so nothing
// spoken is lost. Written atomically (temp + rename) to survive a kill during
// the write itself. Cleared once a take is delivered into a field; a draft that
// survives to the next app launch is an interrupted take, offered for recovery.
class LiveDraft(private val context: Context) {

    private val file: File get() = File(context.filesDir, "live_draft.txt")
    private val tmp: File get() = File(context.filesDir, "live_draft.txt.tmp")

    @Synchronized
    fun save(text: String) {
        if (text.isBlank()) return
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun read(): String? = runCatching {
        if (file.exists() && file.length() > 0) file.readText().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
        runCatching { tmp.delete() }
    }

    fun exists(): Boolean = file.exists() && file.length() > 0
}
