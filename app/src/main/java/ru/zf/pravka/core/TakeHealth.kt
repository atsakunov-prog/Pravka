package ru.zf.pravka.core

import java.util.Locale

/**
 * Сколько тейк был глух и кто его слушал — счёт без Android, под JVM-тестом.
 *
 * Владелец (28.09.2026): «много пропускает слов у меня внезапно, не знаю
 * почему. Хотя вроде бы говорил нормально». Слово теряется там, где тейк
 * идёт, а распознаватель не слушает: после любой его ошибки сессия
 * поднимается заново — пауза перед подъёмом плюс его собственное «готов», — и
 * всё сказанное в это окно не слышит никто. До сих пор такие окна были
 * невидимы: кнопка красная, пилюля пишет, в «Расшифровках» — только текст, и
 * отличить «плохо расслышал» от «вовсе не слушал» было нечем.
 *
 * Теперь каждое окно считается: когда перестал слушать посреди тейка, когда
 * снова «готов», чем кончилось (код ошибки), и кто слушал — телефон или
 * гарнитура. Итог — строкой в журнал и полями записи «Расшифровок».
 *
 * Время — любые миллисекунды одной шкалы (`SystemClock.elapsedRealtime`).
 */
class TakeHealth(private val startedAtMs: Long) {

    /** Тап → первое «готов», мс; −1 — движок так ни разу и не отозвался. */
    var startupMs = -1L
        private set

    /** Сколько миллисекунд посреди тейка распознаватель не слушал. */
    var deafMs = 0L
        private set

    /** Сколько раз он переставал слушать посреди тейка. */
    var gaps = 0
        private set

    /** Кто слушал: «телефон», «гарнитура «OpenComm2»», при смене — «телефон → …». */
    var mic: String? = null
        private set

    private var deafSince = 0L
    private var closed = false
    private val errors = sortedMapOf<Int, Int>()

    /** Слышит прямо сейчас: уже отозвался и не в глухом окне. */
    val hearing: Boolean get() = startupMs >= 0 && deafSince == 0L && !closed

    /** Распознаватель сказал «готов» — на старте или после подъёма. */
    fun ready(nowMs: Long) {
        if (closed) return
        if (startupMs < 0) {
            startupMs = (nowMs - startedAtMs).coerceAtLeast(0)
            return
        }
        closeGap(nowMs)
    }

    /**
     * Перестал слушать посреди тейка: ошибка, конец сессии, перезапуск. До
     * первого «готов» — не в счёт: то окно и есть [startupMs].
     */
    fun deaf(nowMs: Long) {
        if (closed || startupMs < 0 || deafSince != 0L) return
        deafSince = nowMs
    }

    fun error(code: Int) {
        if (closed) return
        errors[code] = (errors[code] ?: 0) + 1
    }

    /** Кто слушает: первая строка — как есть, следующая другая — через стрелку. */
    fun heard(label: String) {
        val now = mic
        mic = when {
            now == null -> label
            now.substringAfterLast(" → ") == label -> now
            else -> "$now → $label"
        }
    }

    /**
     * Тейк кончился (стоп, отмена, сторож тишины): глухое окно, если шло,
     * закрывается этим мигом, дальше счёт не ведётся.
     */
    fun close(nowMs: Long) {
        if (closed) return
        closeGap(nowMs)
        closed = true
    }

    private fun closeGap(nowMs: Long) {
        if (deafSince == 0L) return
        deafMs += (nowMs - deafSince).coerceAtLeast(0)
        gaps++
        deafSince = 0L
    }

    /** Ошибки строкой: «7×3, 2×1»; пусто — ошибок не было. */
    fun errorsLine(): String = errors.entries.joinToString(", ") { "${it.key}×${it.value}" }

    /** Одна строка в журнал на конец тейка. */
    fun summary(): String = buildString {
        append(if (startupMs < 0) "старт: движок не отозвался" else "старт ${startupMs} мс")
        append(" · глухо ").append(deafMs).append(" мс")
        if (gaps > 0) append(" (").append(gaps).append(" ").append(windows(gaps)).append(")")
        errorsLine().takeIf { it.isNotEmpty() }?.let { append(" · ошибки ").append(it) }
        mic?.let { append(" · слушал ").append(it) }
    }

    companion object {

        /** Глухота короче этого — шум, в карточке её не показываем. */
        const val SHOW_DEAF_MS = 300L

        /** Старт дольше этого — первые слова под угрозой, показываем. */
        const val SHOW_STARTUP_MS = 1_500L

        /**
         * Строка здоровья для карточки «Расшифровок»: только то, что стоило
         * слов, — глухие окна, долгий старт, гарнитура. Всё в порядке — null,
         * карточка остаётся прежней.
         */
        fun cardLine(startupMs: Long, deafMs: Long, gaps: Int, mic: String?, locale: Locale): String? {
            val parts = mutableListOf<String>()
            if (deafMs >= SHOW_DEAF_MS) {
                val sec = String.format(locale, "%.1f", deafMs / 1000.0)
                parts += "глухо $sec с" + (if (gaps > 1) " ($gaps ${windows(gaps)})" else "")
            }
            if (startupMs >= SHOW_STARTUP_MS) {
                parts += "старт " + String.format(locale, "%.1f", startupMs / 1000.0) + " с"
            }
            if (mic != null && (mic.contains("гарнитура") || mic.contains("→") || mic.contains("заглуш"))) {
                parts += mic
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        private fun windows(n: Int): String {
            val mod100 = n % 100
            val mod10 = n % 10
            return when {
                mod100 in 11..14 -> "окон"
                mod10 == 1 -> "окно"
                mod10 in 2..4 -> "окна"
                else -> "окон"
            }
        }
    }
}
