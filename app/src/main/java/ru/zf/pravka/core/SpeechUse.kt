package ru.zf.pravka.core

/**
 * Сколько Правка занимала службу речи Google — по суткам, числами, под тестом.
 *
 * Владелец (01.10.2026), со снимком батареи: «Распознавание и синтез речи»
 * 57 %, в фоне 6 ч 52 мин, Правка 2 % — «проверь, что правка не тратит
 * батарею сильно». Своё Правка видит в батарее своей строкой: служба
 * доступности, своя запись микрофона, звуки, проверка связи — всё там. А
 * работа распознавателя на её тейках и синтезатора на её фразах ложится на
 * счёт службы Google, и чья это доля, батарея не говорит. Этот счёт отвечает
 * числом: сколько минут за сутки служба слушала Правку, разбирала её записи
 * заново, держалась её прогревом и голосом.
 *
 * Слушать и разбирать — это работа (процессор, сеть). Прогрев и голос между
 * фразами — только привязка: процесс службы жив и в батарее идёт «фоном», но
 * не считает. Поэтому и строки отдельные.
 */
object SpeechUse {

    enum class Kind(val key: String) {
        /** Тейк: служба слушала — облаком или офлайн-пакетом. */
        TAKE("take"),

        /** Переразбор записанного тейка (значок волны у расшифровки). */
        REPLAY("replay"),

        /** Прогрев: клиент привязан заранее, чтобы не терять первые слова, — не слушает. */
        WARM("warm"),

        /** Голос Правки: синтезатор заведён ради фразы в наушники. */
        VOICE("voice"),
        ;

        companion object {
            fun fromKey(key: String): Kind? = entries.firstOrNull { it.key == key }
        }
    }

    /** Сутки [day] («2026-10-01»): сколько миллисекунд и сколько раз — по видам. */
    data class Day(val day: String, val ms: Map<Kind, Long> = emptyMap(), val n: Map<Kind, Int> = emptyMap()) {
        fun ms(k: Kind): Long = ms[k] ?: 0L
        fun n(k: Kind): Int = n[k] ?: 0
    }

    /** Сколько суток держим: сравнить с экраном батареи за неделю — хватит с запасом. */
    const val KEEP_DAYS = 14

    /**
     * Одна привязка дольше суток — сбой часов или забытый клиент, а не работа:
     * в счёт идёт не больше суток, чтобы одно число не съело картину.
     */
    private const val ONE_MAX_MS = 24 * 60 * 60_000L

    /**
     * Учесть [ms] вида [kind] за сутки [day]. Список — по возрастанию дат,
     * старше [KEEP_DAYS] суток уходят. Отрицательное (часы перевели) — ноль.
     */
    fun add(days: List<Day>, day: String, kind: Kind, ms: Long, count: Int = 1): List<Day> {
        val add = ms.coerceIn(0L, ONE_MAX_MS)
        val i = days.indexOfFirst { it.day == day }
        val was = if (i >= 0) days[i] else Day(day)
        val now = was.copy(
            ms = was.ms + (kind to was.ms(kind) + add),
            n = was.n + (kind to was.n(kind) + count.coerceAtLeast(0)),
        )
        val out = days.toMutableList()
        if (i >= 0) out[i] = now else out += now
        return out.sortedBy { it.day }.takeLast(KEEP_DAYS)
    }

    /**
     * Сутки строками для экрана: что служба делала для Правки. Пустые сутки —
     * одна строка «служба речи Правке не понадобилась».
     */
    fun lines(d: Day?): List<String> {
        if (d == null || Kind.entries.all { d.ms(it) == 0L && d.n(it) == 0 }) {
            return listOf("служба речи Правке не понадобилась")
        }
        val out = ArrayList<String>()
        out += "слушала ${dur(d.ms(Kind.TAKE))} — ${d.n(Kind.TAKE)} ${plural(d.n(Kind.TAKE), "тейк", "тейка", "тейков")}"
        if (d.n(Kind.REPLAY) > 0) {
            out += "разбирала заново ${dur(d.ms(Kind.REPLAY))} — ${d.n(Kind.REPLAY)} ${plural(d.n(Kind.REPLAY), "запись", "записи", "записей")}"
        }
        out += "держалась прогревом ${dur(d.ms(Kind.WARM))} — ${d.n(Kind.WARM)} ${plural(d.n(Kind.WARM), "раз", "раза", "раз")}"
        if (d.n(Kind.VOICE) > 0) {
            out += "держала голос ${dur(d.ms(Kind.VOICE))} — ${d.n(Kind.VOICE)} ${plural(d.n(Kind.VOICE), "фраза", "фразы", "фраз")}"
        }
        return out
    }

    /** Длительность словами: «0», «меньше минуты», «43 мин», «1 ч 52 мин». */
    fun dur(ms: Long): String {
        if (ms <= 0L) return "0 мин"
        val min = ms / 60_000L
        if (min == 0L) return "меньше минуты"
        val h = min / 60
        val m = min % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    private fun plural(n: Int, one: String, few: String, many: String): String = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
}
