package ru.zf.pravka.core

import ru.zf.pravka.data.ZasechkaStore
import kotlin.math.roundToInt

/**
 * Числа для вкладки «Отчёт»: считаются здесь, чистым Kotlin, и проверяются
 * JVM-тестом; вкладка только рисует. Модель к этому экрану не подходит вовсе.
 *
 * Три правила, на которых всё стоит (подробно — docs/otchet.md):
 *
 * 1. **Обрезка по окну.** Минуты записи считаются только внутри [from, to):
 *    дело, которое идёт сейчас, не раздувает вчерашний день, а разрез полуночью
 *    ничего не двоит. Суммы копятся в миллисекундах и округляются один раз —
 *    иначе день из двадцати записей приезжал на 1436 минут (урок выгрузок).
 * 2. **Честное сравнение.** Сегодня в 15:20 сравнивается с тем же днём недели
 *    неделю назад ТОЖЕ до 15:20 ([Window.shifted]): половина дня против целого
 *    проигрывает всегда, и такой график врал бы каждое утро.
 * 3. **Порогов не выдумываем.** Базой служат его же дни: тот же день недели за
 *    четыре недели, медиана за 28 дней, место среди своих дней. Коэффициенты
 *    называют формулу словами прямо во вкладке.
 */
object DayReport {

    const val DAY_MS = 86_400_000L
    const val HOUR_MS = 3_600_000L
    private const val MIN_MS = 60_000L

    /** Глубокий блок работы — от 45 минут без смены дела. */
    const val DEEP_BLOCK_MS = 45 * MIN_MS

    /** Две записи одного дела, разорванные меньше чем на пять минут, — одно дело. */
    private const val MERGE_GAP_MS = 5 * MIN_MS

    /** Кусок сна короче получаса ночным блоком не считается (дрёма, ослышка). */
    private const val SLEEP_BLOCK_MIN_MS = 30 * MIN_MS

    /** Окно счёта — день или его прошедшая часть. */
    data class Window(val from: Long, val to: Long) {
        val elapsedMs: Long get() = (to - from).coerceAtLeast(0L)

        /**
         * То же окно на [days] дней раньше, ТОЙ ЖЕ длины: у идущего дня это
         * «до этого же часа», а не целые сутки.
         */
        fun shifted(days: Int): Window = Window(from - days * DAY_MS, to - days * DAY_MS)
    }

    /** Сутки [dayStart, dayStart + 24 ч), обрезанные «сейчас». */
    fun dayWindow(dayStart: Long, now: Long): Window =
        Window(dayStart, minOf(now, dayStart + DAY_MS).coerceAtLeast(dayStart))

    /** Миллисекунды записи внутри окна. */
    fun msIn(e: ZasechkaStore.Entry, w: Window, now: Long): Long = e.durationMsIn(w.from, w.to, now)

    /** Миллисекунды → минуты, округление к ближайшей, а не усечение. */
    fun msToMin(ms: Long): Long = (ms + 30_000L) / 60_000L

    /** Очки отрезка: ценность часа × часы, как в ленте Засечки. */
    fun pointsOf(worth: Int, ms: Long): Int = (worth * ms.toDouble() / HOUR_MS).roundToInt()

    fun isSleep(category: String): Boolean = category.trim().equals("Сон", ignoreCase = true)

    /** Потери и заполнитель «Не размечено» — то, что владелец зовёт дырами. */
    fun isHole(category: String): Boolean {
        val c = category.trim().lowercase()
        return c == "потери" || c == "не размечено" || c == "прокрастинация"
    }

    fun isWork(category: String): Boolean = category.trim().lowercase().startsWith("работа")

    fun isSport(category: String): Boolean = category.trim().lowercase().startsWith("спорт")

    /** Автоматика и заполнители — не дела владельца: их не считают во фрагментации. */
    private fun isOwnerEntry(e: ZasechkaStore.Entry): Boolean =
        e.source != "auto" && e.source != "gap" && !isSleep(e.category)

    // ---- Категории ----

    data class CatSlice(val category: String, val ms: Long, val worth: Int) {
        val minutes: Long get() = msToMin(ms)
        val points: Int get() = pointsOf(worth, ms)
    }

    /**
     * Минуты и очки по категориям внутри окна, по убыванию времени. Имя
     * категории берётся в написании первой встреченной записи; пустая — «».
     */
    fun byCategory(
        entries: List<ZasechkaStore.Entry>,
        w: Window,
        now: Long,
        worthOf: (String) -> Int,
    ): List<CatSlice> {
        val ms = LinkedHashMap<String, Long>()
        val names = HashMap<String, String>()
        for (e in entries) {
            val part = msIn(e, w, now)
            if (part <= 0L) continue
            val shown = e.category.trim()
            val key = shown.lowercase()
            ms[key] = (ms[key] ?: 0L) + part
            names.putIfAbsent(key, shown)
        }
        return ms.entries
            .map { (key, v) -> CatSlice(names[key] ?: key, v, worthOf(key)) }
            .sortedByDescending { it.ms }
    }

    /** Минуты категории (по имени, без учёта регистра) внутри окна. */
    fun minutesOf(entries: List<ZasechkaStore.Entry>, w: Window, now: Long, category: String): Long {
        val key = category.trim().lowercase()
        return msToMin(entries.filter { it.category.trim().lowercase() == key }.sumOf { msIn(it, w, now) })
    }

    /** Минуты всех категорий, прошедших фильтр (работа, спорт, дыры…). */
    fun minutesWhere(
        entries: List<ZasechkaStore.Entry>,
        w: Window,
        now: Long,
        filter: (String) -> Boolean,
    ): Long = msToMin(entries.filter { filter(it.category) }.sumOf { msIn(it, w, now) })

    // ---- Балл ----

    data class Balance(val plus: Double, val minus: Double) {
        val net: Int get() = (plus + minus).roundToInt()

        /**
         * КПД дня: доля плюсовых очков в обороте (плюс к сумме модулей). Ноль
         * очков — null, а не 0 %: пустой день не «плохой», он просто пустой.
         */
        val efficiency: Double?
            get() {
                val turnover = plus - minus
                return if (turnover <= 0.0) null else plus / turnover
            }
    }

    /** Балл окна: плюс и минус порознь, как в строке «Баланс» Засечки. */
    fun balance(
        entries: List<ZasechkaStore.Entry>,
        w: Window,
        now: Long,
        worthOf: (String) -> Int,
    ): Balance {
        var plus = 0.0
        var minus = 0.0
        for (e in entries) {
            val v = worthOf(e.category) * msIn(e, w, now) / HOUR_MS.toDouble()
            if (v >= 0) plus += v else minus += v
        }
        return Balance(plus, minus)
    }

    // ---- Сон и ритм ----

    /** Ночь перед сутками [dayStart]: с 18:00 накануне до 14:00 этого дня. */
    fun nightWindow(dayStart: Long): Window = Window(dayStart - 6 * HOUR_MS, dayStart + 14 * HOUR_MS)

    data class Block(val start: Long, val end: Long) {
        val ms: Long get() = end - start
    }

    /**
     * Куски «Сна» внутри окна, склеенные в блоки: лента режет ночь полуночью
     * (и тренировкой с часов), а спать владелец от этого не переставал.
     */
    fun sleepBlocks(entries: List<ZasechkaStore.Entry>, w: Window, now: Long): List<Block> {
        val parts = entries
            .filter { isSleep(it.category) }
            .mapNotNull { e ->
                val s = maxOf(e.start, w.from)
                val t = minOf(if (e.open) now else e.end, w.to)
                if (t > s) Block(s, t) else null
            }
            .sortedBy { it.start }
        val out = ArrayList<Block>()
        for (p in parts) {
            val last = out.lastOrNull()
            if (last != null && p.start <= last.end + MERGE_GAP_MS) {
                out[out.size - 1] = Block(last.start, maxOf(last.end, p.end))
            } else {
                out.add(p)
            }
        }
        return out.filter { it.ms >= SLEEP_BLOCK_MIN_MS }
    }

    /** Ночной сон перед днём — сумма всех блоков ночи. Ноль — в ленте сна нет. */
    fun nightSleepMs(entries: List<ZasechkaStore.Entry>, dayStart: Long, now: Long): Long =
        sleepBlocks(entries, nightWindow(dayStart), now).sumOf { it.ms }

    data class Rhythm(val wakeMs: Long?, val bedMs: Long?)

    /**
     * Подъём — конец самого длинного блока ночи перед днём; отбой — начало
     * самого длинного блока СЛЕДУЮЩЕЙ ночи. У идущего дня отбоя ещё нет.
     */
    fun rhythm(entries: List<ZasechkaStore.Entry>, dayStart: Long, now: Long): Rhythm {
        val wake = sleepBlocks(entries, nightWindow(dayStart), now).maxByOrNull { it.ms }
            ?.end?.takeIf { it < now }
        val bed = sleepBlocks(entries, nightWindow(dayStart + DAY_MS), now).maxByOrNull { it.ms }?.start
        return Rhythm(wake, bed)
    }

    /** Бодрствование в окне: прошедшее время минус сон. */
    fun awakeMs(entries: List<ZasechkaStore.Entry>, w: Window, now: Long): Long =
        (w.elapsedMs - entries.filter { isSleep(it.category) }.sumOf { msIn(it, w, now) }).coerceAtLeast(0L)

    // ---- Полоса дня ----

    data class Segment(
        val startFrac: Float,
        val endFrac: Float,
        val category: String,
        val title: String,
        val open: Boolean,
    )

    /**
     * Сутки одной полосой: доли 24 часов по записям, обрезанным сутками; у
     * идущего дела конец — «сейчас», будущее остаётся пустым.
     */
    fun strip(entries: List<ZasechkaStore.Entry>, dayStart: Long, now: Long): List<Segment> {
        val from = dayStart
        val to = dayStart + DAY_MS
        return entries
            .asSequence()
            .filter { it.start < to && (it.open || it.end > from) }
            .sortedBy { it.start }
            .mapNotNull { e ->
                val s = maxOf(e.start, from)
                val t = minOf(if (e.open) now else e.end, to)
                if (t <= s) null
                else Segment(
                    startFrac = (s - from).toFloat() / DAY_MS,
                    endFrac = (t - from).toFloat() / DAY_MS,
                    category = e.category,
                    title = e.title,
                    open = e.open,
                )
            }
            .toList()
    }

    // ---- Треугольник ценности ----

    data class Bucket(val label: String, val lo: Int, val hi: Int, val ms: Long, val categories: List<String>) {
        val minutes: Long get() = msToMin(ms)
    }

    private val BUCKET_EDGES = listOf(
        Triple("+7…+10", 7, 10),
        Triple("+3…+6", 3, 6),
        Triple("0…+2", 0, 2),
        Triple("−1…−4", -4, -1),
        Triple("−5…−10", -10, -5),
    )

    /**
     * Бодрствование, разложенное по ценности часа: пять ступеней от «+7…+10»
     * до «−5…−10». Цель владельца — перевёрнутый треугольник: широко сверху,
     * узко внизу. Сон (ценность 0, восемь часов) в счёт не идёт — он раздавил
     * бы середину и не сказал бы ничего.
     */
    fun buckets(slices: List<CatSlice>): List<Bucket> = BUCKET_EDGES.map { (label, lo, hi) ->
        val inside = slices.filter { !isSleep(it.category) && it.worth in lo..hi }
        Bucket(
            label = label, lo = lo, hi = hi,
            ms = inside.sumOf { it.ms },
            categories = inside.sortedByDescending { it.ms }.map { it.category },
        )
    }

    /**
     * Верх против низа: минуты в категориях от +6 и минуты в категориях от −2.
     * Индекс — доля верха в их сумме; null, когда ни того ни другого нет.
     */
    data class Triangle(val topMs: Long, val bottomMs: Long) {
        val index: Double? get() {
            val sum = topMs + bottomMs
            return if (sum <= 0L) null else topMs.toDouble() / sum
        }
    }

    fun triangle(slices: List<CatSlice>): Triangle = Triangle(
        topMs = slices.filter { !isSleep(it.category) && it.worth >= 6 }.sumOf { it.ms },
        bottomMs = slices.filter { !isSleep(it.category) && it.worth <= -2 }.sumOf { it.ms },
    )

    // ---- Фокус и фрагментация ----

    data class Focus(
        /** Минуты работы в окне. */
        val workMin: Long,
        /** Минуты работы в блоках от 45 минут. */
        val deepMin: Long,
        val deepBlocks: Int,
        /** Дел владельца (без автоматики, заполнителей и сна), задевших окно. */
        val entries: Int,
        /** Средняя длина такого дела, минут. */
        val avgEntryMin: Long,
        /** Самый длинный блок без смены дела, минут. */
        val longestMin: Long,
    ) {
        /** Доля работы, сделанной глубокими блоками; null без работы. */
        val deepShare: Double? get() = if (workMin <= 0L) null else deepMin.toDouble() / workMin
    }

    /**
     * Соседние записи одного дела (то же название и категория, разрыв меньше
     * пяти минут) — один блок: полночь и тренировка с часов режут дело, а
     * фокус от этого не рвётся.
     */
    fun mergeSame(entries: List<ZasechkaStore.Entry>, w: Window, now: Long): List<Block> {
        val out = ArrayList<Pair<String, Block>>()
        for (e in entries.sortedBy { it.start }) {
            val s = maxOf(e.start, w.from)
            val t = minOf(if (e.open) now else e.end, w.to)
            if (t <= s) continue
            val sig = e.title.trim().lowercase() + "|" + e.category.trim().lowercase()
            val last = out.lastOrNull()
            if (last != null && last.first == sig && s <= last.second.end + MERGE_GAP_MS) {
                out[out.size - 1] = sig to Block(last.second.start, maxOf(last.second.end, t))
            } else {
                out.add(sig to Block(s, t))
            }
        }
        return out.map { it.second }
    }

    fun focus(entries: List<ZasechkaStore.Entry>, w: Window, now: Long): Focus {
        val work = entries.filter { isWork(it.category) }
        val workBlocks = mergeSame(work, w, now)
        val deep = workBlocks.filter { it.ms >= DEEP_BLOCK_MS }
        val own = entries.filter { isOwnerEntry(it) }
        val ownBlocks = mergeSame(own, w, now)
        val ownMs = ownBlocks.sumOf { it.ms }
        return Focus(
            workMin = msToMin(work.sumOf { msIn(it, w, now) }),
            deepMin = msToMin(deep.sumOf { it.ms }),
            deepBlocks = deep.size,
            entries = ownBlocks.size,
            avgEntryMin = if (ownBlocks.isEmpty()) 0L else msToMin(ownMs / ownBlocks.size),
            longestMin = msToMin(ownBlocks.maxOfOrNull { it.ms } ?: 0L),
        )
    }

    // ---- Дела и клиенты ----

    data class TitleMinutes(val title: String, val category: String, val ms: Long) {
        val minutes: Long get() = msToMin(ms)
    }

    /** Что съело день: дела по времени, без сна и заполнителей. */
    fun topTitles(entries: List<ZasechkaStore.Entry>, w: Window, now: Long, limit: Int): List<TitleMinutes> {
        val ms = LinkedHashMap<String, Long>()
        val shown = HashMap<String, Pair<String, String>>()
        for (e in entries) {
            if (isSleep(e.category) || e.source == "gap") continue
            val part = msIn(e, w, now)
            if (part <= 0L) continue
            val title = e.title.trim().ifBlank { e.category.trim().ifBlank { "—" } }
            val key = title.lowercase() + "|" + e.category.trim().lowercase()
            ms[key] = (ms[key] ?: 0L) + part
            shown.putIfAbsent(key, title to e.category.trim())
        }
        return ms.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (key, v) -> TitleMinutes(shown[key]!!.first, shown[key]!!.second, v) }
    }

    /** Минуты по клиентам, по убыванию; записи без клиента не считаются. */
    fun byClient(entries: List<ZasechkaStore.Entry>, w: Window, now: Long): List<Pair<String, Long>> {
        val ms = LinkedHashMap<String, Long>()
        for (e in entries) {
            val c = e.client.trim()
            if (c.isBlank()) continue
            val part = msIn(e, w, now)
            if (part <= 0L) continue
            ms[c] = (ms[c] ?: 0L) + part
        }
        return ms.entries.sortedByDescending { it.value }.map { it.key to msToMin(it.value) }
    }

    /** Дел, начатых из Todoist в окне. */
    fun todoistStarts(entries: List<ZasechkaStore.Entry>, w: Window): Int =
        entries.count { it.source == "todoist" && it.start >= w.from && it.start < w.to }

    // ---- База для сравнения ----

    /** Медиана; пусто — null. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Место значения среди остальных: 1 — лучше всех; равные не понижают. */
    fun rank(value: Double, others: List<Double>): Int = 1 + others.count { it > value }

    /**
     * Дельта «сейчас против базы» словами и знаком: «+12 м», «−3». Ноль — «=».
     * [unit] пишется после числа, пустой — не пишется.
     */
    fun delta(cur: Long, ref: Long, unit: String = ""): String {
        val d = cur - ref
        val body = when {
            d > 0 -> "+$d"
            d < 0 -> "−${-d}"
            else -> "="
        }
        return if (d != 0L && unit.isNotBlank()) "$body $unit" else body
    }

    /** «1 ч 10 м», «47 м», «0 м». */
    fun dur(min: Long): String {
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m м"
            h > 0 -> "$h ч"
            else -> "$m м"
        }
    }

    /** «+37» / «−4» / «0». */
    fun signed(v: Int): String = when {
        v > 0 -> "+$v"
        v < 0 -> "−${-v}"
        else -> "0"
    }

    /** Проценты из доли: 0.734 → «73 %»; null → «—». */
    fun pct(share: Double?): String = if (share == null) "—" else "${(share * 100).roundToInt()} %"
}
