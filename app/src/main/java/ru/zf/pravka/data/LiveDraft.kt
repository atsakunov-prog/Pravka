package ru.zf.pravka.data

import android.content.Context
import java.io.File

// Crash-safe autosave of the live dictation. The streaming engine keeps no WAV,
// so if the phone dies or the app is killed mid-take the audio is gone - but the
// recognized words are checkpointed here as they stream in, so nothing spoken is
// lost. Written atomically (temp + rename) to survive a kill during the write
// itself. Cleared once a take is delivered into a field; a draft that survives
// to the next app launch is an interrupted take, offered for recovery.
//
// Saves are queued on DiskWriter: they used to run on the main thread, ~50 write
// +rename pairs a minute, in the same callbacks that carry recognized audio.
class LiveDraft(private val context: Context) {

    private val file: File by lazy { File(context.filesDir, "live_draft.txt") }
    private val tmp: File by lazy { File(context.filesDir, "live_draft.txt.tmp") }

    // The partial and the finalized checkpoint are frequently the same string;
    // remembering the last one collapses those into a single write.
    @Volatile private var lastSaved: String? = null

    fun save(text: String) {
        if (text.isBlank() || text == lastSaved) return
        lastSaved = text
        DiskWriter.post {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun read(): String? = runCatching {
        if (file.exists() && file.length() > 0) file.readText().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    fun clear() {
        lastSaved = null
        DiskWriter.post {
            file.delete()
            tmp.delete()
        }
    }
}
