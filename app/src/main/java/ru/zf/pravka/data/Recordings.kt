package ru.zf.pravka.data

import android.content.Context
import java.io.File

// Saved dictation audio. Every recording is written to disk BEFORE it is
// transcribed and deleted only after the text successfully lands in a field
// (owner's request, Wispr-style): if transcription fails - no network to the
// home Whisper, Nano stumbled, the app was killed - the .wav stays here and
// the app can retry it later from the "Записи" screen.
class Recordings(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newFile(): File = File(dir, "rec_${System.currentTimeMillis()}.wav")

    data class Item(val file: File, val startedAt: Long, val sizeBytes: Long, val durationMs: Long) {
        val id: String get() = file.name
    }

    /** Pending recordings, newest first. */
    fun list(): List<Item> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                Item(
                    file = f,
                    startedAt = runCatching { f.name.removePrefix("rec_").removeSuffix(".wav").toLong() }
                        .getOrDefault(f.lastModified()),
                    sizeBytes = f.length(),
                    durationMs = WavFile.durationMs(f),
                )
            }
            .orEmpty()

    fun hasPending(): Boolean = list().isNotEmpty()

    fun delete(id: String) {
        File(dir, id).takeIf { it.exists() }?.delete()
    }
}
