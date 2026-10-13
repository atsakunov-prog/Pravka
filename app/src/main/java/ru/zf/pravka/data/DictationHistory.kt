package ru.zf.pravka.data

import android.content.Context
import java.io.File
import org.json.JSONObject
import ru.zf.pravka.core.Dictations

/**
 * Что показывает экран «Правка» (Правка 5.0, этап 5): диктовки телефона из его журналов и
 * диктовки компьютера, забранные с сервера (`PcDictations`), — одной лентой, свежие сверху.
 * Склейка тейка с чисткой и подписи — `core/Dictations.kt`, здесь только чтение файлов.
 *
 * Журналы телефона (`transcriptions.jsonl`, `history.jsonl`) ротируются по 5 МБ в «.1» —
 * читаем оба: иначе сразу после ротации история обрывалась бы на сегодняшнем утре. Это
 * диск, поэтому — только с IO-потока.
 */
internal object DictationHistory {

    /** Сколько тейков телефона брать с конца: тысячи — это месяцы диктовок. */
    const val PHONE_LIMIT = 4000

    fun load(context: Context, pc: PcDictations, limit: Int = PHONE_LIMIT): List<Dictations.Item> {
        val dir = DataRoot.dir(context)
        val takes = lines(dir, TranscriptionLog.FILE_NAME).mapNotNull { parse(it)?.let(Dictations::takeOf) }.takeLast(limit)
        val from = (takes.minOfOrNull { it.ms } ?: 0L) - 60_000L
        val cleans = lines(dir, HistoryLog.FILE_NAME).mapNotNull { parse(it)?.let(Dictations::cleanOf) }.filter { it.ms >= from }
        return Dictations.merge(Dictations.phone(takes, cleans), runCatching { pc.items() }.getOrDefault(emptyList()))
    }

    /** Строки журнала по порядку: сначала «.1» (старое), потом текущий. */
    private fun lines(dir: File, name: String): List<String> =
        listOf(File(dir, "$name.1"), File(dir, name)).flatMap { f ->
            if (f.isFile) runCatching { f.readLines() }.getOrDefault(emptyList()) else emptyList()
        }

    private fun parse(line: String): JSONObject? =
        if (line.isBlank()) null else runCatching { JSONObject(line) }.getOrNull()
}
