package ru.zf.pravka.data

import android.content.Context
import java.io.File

// Saved dictation audio. Every recording is written to disk BEFORE it is
// transcribed and deleted only after the text successfully lands in a field
// (owner's request, Wispr-style): if transcription fails - no network to the
// transcription stumbled or the app was killed - the .wav stays here and
// the app can retry it later from the "Записи" screen.
class Recordings(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newFile(): File = File(dir, "rec_${System.currentTimeMillis()}.wav")

    data class Item(val file: File, val startedAt: Long, val durationMs: Long) {
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
                    durationMs = WavFile.durationMs(f),
                )
            }
            .orEmpty()


    fun delete(id: String) {
        File(dir, id).takeIf { it.exists() }?.delete()
    }

    /**
     * Keeps the retry stash bounded (16 kHz mono is ~2 MB per minute of
     * speech; a week of failed transcriptions used to accumulate forever):
     * - header-only files (a crash before any audio) can never transcribe -
     *   deleted right away;
     * - everything older than [maxAgeDays] goes: a retry the owner has not
     *   made in two weeks is not going to happen;
     * - beyond that, the oldest files go until the total fits [maxTotalBytes].
     */
    fun prune(maxAgeDays: Int = 14, maxTotalBytes: Long = 200L * 1024 * 1024) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600 * 1000
        val survivors = mutableListOf<File>()
        for (f in files) {
            if (f.length() <= 44L || f.lastModified() < cutoff) f.delete() else survivors.add(f)
        }
        var total = survivors.sumOf { it.length() }
        for (f in survivors.sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
