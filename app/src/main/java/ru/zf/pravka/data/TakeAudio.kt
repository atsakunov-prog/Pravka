package ru.zf.pravka.data

import android.content.Context
import java.io.File

/**
 * Звук тейков Google — чтобы любую фразу можно было разобрать заново
 * (владелец, 30.09.2026: «давай, конечно же, писать… последние 300 МБ… на
 * каждый будет значок, WAV-файл, и если на него нажимаешь, то просто ещё раз
 * разбирается эта фраза»). Поводом стал тейк, где распознаватель после паузы
 * оглох на полминуты: звук жил только в памяти своей записи и ушёл вместе с
 * тейком.
 *
 * Пишет своя запись (`provider/MicFeed.kt`) на своём потоке, кусками по 50 мс,
 * тот же 16 кГц моно, что уходит распознавателю, — около 1,9 МБ на минуту
 * записи вместе с паузами: 300 МБ — это примерно 2,5 часа. Лежит в приватной
 * памяти, а не в папке базы: WAV пишется в реальном времени, и запись звука не
 * должна ждать FUSE общей памяти (та же причина, что у `Recordings`). Это не
 * база, а расходный архив: старое уходит, когда не хватает места.
 *
 * Файл связан со своей расшифровкой по имени — поле `audio` в
 * `TranscriptionLog`. Файл ушёл уборкой — значок у расшифровки пропадает.
 */
class TakeAudio(private val context: Context) {

    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** Файл для нового тейка. Сам файл создаёт своя запись, когда пойдёт звук. */
    fun newFile(): File = File(dir, "take_${System.currentTimeMillis()}.wav")

    /** Звук тейка по имени из расшифровки; null — его нет или в нём нет звука. */
    fun file(name: String?): File? {
        if (name.isNullOrBlank() || name.contains('/')) return null
        return File(dir, name).takeIf { it.isFile && it.length() > WAV_HEADER }
    }

    fun delete(name: String?) {
        if (name.isNullOrBlank() || name.contains('/')) return
        File(dir, name).delete()
    }

    /** Сколько места занято, байт. */
    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Убрать старое сверх [maxBytes] и пустые файлы. Зовётся с потока диска перед новым тейком. */
    fun prune(maxBytes: Long = MAX_BYTES) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return
        val drop = Policy.drop(files.map { Policy.Item(it.name, it.length(), it.lastModified()) }, maxBytes)
        for (name in drop) File(dir, name).delete()
    }

    /** Правило уборки — без Android, под тестом. */
    object Policy {
        data class Item(val name: String, val bytes: Long, val modifiedMs: Long)

        /**
         * Какие файлы убрать: пустые (упал до первого звука) — всегда, дальше
         * старые, пока остальное не влезет в [maxBytes]. Свежие не трогаем
         * никогда: самый новый тейк остаётся, даже если один он больше лимита.
         */
        fun drop(items: List<Item>, maxBytes: Long): List<String> {
            val out = ArrayList<String>()
            val alive = ArrayList<Item>()
            for (i in items) if (i.bytes <= WAV_HEADER) out += i.name else alive += i
            var total = alive.sumOf { it.bytes }
            val oldestFirst = alive.sortedBy { it.modifiedMs }
            for ((index, i) in oldestFirst.withIndex()) {
                if (total <= maxBytes || index == oldestFirst.lastIndex) break
                out += i.name
                total -= i.bytes
            }
            return out
        }
    }

    companion object {
        private const val DIR = "take-audio"

        /** Заголовок WAV: файл не длиннее — звука в нём нет. */
        const val WAV_HEADER = 44L

        /** Сколько хранить: 300 МБ — около 2,5 часа записи 16 кГц моно. */
        const val MAX_BYTES = 300L * 1024 * 1024

        /** Байт на минуту записи: 16 кГц × 2 байта × 60 с. */
        const val BYTES_PER_MINUTE = 16_000L * 2 * 60
    }
}
