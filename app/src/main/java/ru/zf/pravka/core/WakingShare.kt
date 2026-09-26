package ru.zf.pravka.core

/**
 * Доля категории от бодрствования — для итогов дня Засечки.
 *
 * Владелец (08.09.2026): «там обычно сон на 7 часов и всё остальное очень
 * коротко — давай сон уберём, будем считать чистое время и сколько процентов
 * от бодрствования каждая категория». Сон узнаётся так же, как в Отчёте
 * ([DayReport.isSleep]), чтобы две вкладки не разошлись в том, что такое сон.
 * Вкладка только рисует; числа — здесь, под JVM-тестами.
 */
object WakingShare {

    fun isSleep(category: String): Boolean = DayReport.isSleep(category)

    /** Минуты бодрствования: всё, что не сон. Ключи — категории в любом регистре. */
    fun awakeMinutes(minutesByCategory: Map<String, Long>): Long =
        minutesByCategory.filterKeys { !isSleep(it) }.values.sum()

    /** Целый процент от бодрствования; 0, когда считать нечего. */
    fun percent(minutes: Long, awake: Long): Int =
        if (awake <= 0L || minutes <= 0L) 0
        else Math.round(minutes * 100.0 / awake).toInt()

    /**
     * Подпись для строки итогов: «12%»; у крошки, которая округлилась бы в
     * ноль, — «<1%», чтобы не путать с пустой строкой; у пустой — ничего.
     */
    fun label(minutes: Long, awake: Long): String = when {
        minutes <= 0L || awake <= 0L -> ""
        percent(minutes, awake) == 0 -> "<1%"
        else -> "${percent(minutes, awake)}%"
    }
}
