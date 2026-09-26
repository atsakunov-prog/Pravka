package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Pace
import ru.zf.pravka.core.PaceSeed
import ru.zf.pravka.core.PaceTune

/**
 * История «сколько идёт запрос» по тройкам «дорога + модель + усилие»
 * (усилие — с 22.09.2026: на xhigh Опус думает в разы дольше, чем на medium,
 * и общая прямая врала бы обоим). Считает `core/Pace.kt`, калибрует ночью
 * `core/PaceTune.kt`, здесь хранение, ключи и журнал замеров.
 *
 * Ключ дороги — ещё и ВИД запроса ([kind]: вопрос тренеру отвечает абзацами,
 * разбор подходов — строкой) и снимок ([photo]: картинка идёт в разы дольше
 * текста). Модель у них одна, а время — нет (26.09.2026).
 *
 * Читают это с потока запроса в миг, когда он уходит, и ждать диска некогда.
 * Поэтому дороги живут в памяти, а на диск уезжают следом, через `DiskWriter`.
 * Каждый замер ещё и дописывается строкой в свой журнал ([LOG_FILE]) — по нему
 * ночная калибровка прогоняет историю заново, и его же выгружает CSV.
 *
 * Пустой дороги не бывает: пока своих замеров нет, работает заводская прикидка
 * (`core/PaceSeed.kt`), а дорога Правки при первом запуске после обновления
 * вычитывает своё прошлое из журнала правок — [seedFromHistory].
 *
 * Данные расходные: потерялись — дорога откатывается к прикидке и снова
 * учится. Поэтому ни `.prev`, ни карантина тут нет, в отличие от ленты.
 */
class PaceStore(private val context: Context) {

    private companion object {
        private const val FILE_NAME = "pace.json"

        /**
         * Журнал замеров: строка JSON на запрос. Дописывается в конец, а не
         * переписывается целиком: нужна долгая история, а переписывать
         * мегабайт на каждом ответе незачем.
         */
        private const val LOG_FILE = "pace-log.jsonl"

        /** Больше этого — остаётся вторая половина: полгода работы с запасом. */
        private const val LOG_MAX_BYTES = 4L * 1024 * 1024

        /**
         * Номер разбора журнала. Разбор прошёл — больше к журналу не ходим,
         * иначе те же полторы тысячи записей ложились бы в накопитель на
         * каждом запуске и он застыл бы на июле. Понадобится перечитать
         * журнал по-новому — номер растёт, и разбор проходит ещё раз.
         */
        private const val SEED_VERSION = 1

        /**
         * С этой версии дороги режимов меряют длину сказанного. Раньше они
         * слали транспорту пустой вход, и их замеры лежали на «0 знаков»:
         * среднее честное, длины за ним нет. Переезд ставит их на типичную
         * длину фразы ([LEGACY_CHARS]) малым весом ([LEGACY_WEIGHT]) — один
         * раз, по отметке в файле.
         */
        private const val CHARS_VERSION = 2

        /**
         * Медиана длины надиктованной фразы: 134 знака по журналу
         * распознавания, 1790 диктовок с июля по сентябрь 2026 (10% короче
         * 42 знаков, 10% длиннее 590).
         */
        private const val LEGACY_CHARS = 134.0
        private const val LEGACY_WEIGHT = 4.0

        /** Дороги, которые до CHARS_VERSION меряли «0 знаков». */
        private val LEGACY_ROUTES = setOf(
            "zasechka", "zasechka_fork", "raznoska", "money", "money_ask", "money_patterns",
            "food", "body", "body_light",
        )

        /** В настройках — дороги, по которым ходили за последний месяц; остальные числом. */
        private const val RECENT_MS = 30L * 24 * 3_600_000
    }

    /** Один замер журнала: что обещали и что вышло. */
    private data class Sample(
        /** Когда запрос УШЁЛ. */
        val at: Long,
        val key: String,
        val chars: Int,
        val expected: Long,
        val actual: Long,
        /** 1 — кэш прочитан, 0 — записан (холодный), -1 — кэша нет. */
        val cache: Int,
        val coldExpected: Boolean,
    ) {
        fun json(): String = JSONObject()
            .put("t", at).put("k", key).put("c", chars).put("e", expected)
            .put("a", actual).put("h", cache).put("p", coldExpected)
            .toString()

        companion object {
            fun of(o: JSONObject) = Sample(
                at = o.optLong("t"),
                key = o.optString("k"),
                chars = o.optInt("c"),
                expected = o.optLong("e"),
                actual = o.optLong("a"),
                cache = o.optInt("h", -1),
                coldExpected = o.optBoolean("p", false),
            )
        }
    }

    private val file: File by lazy { File(DataRoot.dir(context), FILE_NAME) }
    private val logFile: File by lazy { File(DataRoot.dir(context), LOG_FILE) }

    /** Ключ — «дорога[·вид][·фото]|модель|усилие». */
    private val roads = HashMap<String, Pace.Road>()
    private var seed = 0
    private var charsVersion = 0
    private var tuneAt = 0L
    private var tuneLines: List<String> = emptyList()
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        var legacyLog: List<Sample> = emptyList()
        runCatching {
            if (!file.exists()) return@runCatching
            val root = JSONObject(file.readText())
            seed = root.optInt("seed", 0)
            charsVersion = root.optInt("chars", 0)
            tuneAt = root.optLong("tuneAt", 0L)
            tuneLines = root.optJSONArray("tuneLines")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            // До 20.09.2026 в корне лежали сразу дороги — читаем и так.
            val stored = root.optJSONObject("roads") ?: root
            for (name in stored.keys()) {
                val o = stored.optJSONObject(name) ?: continue
                // До 22.09.2026 ключ был «дорога|модель», без усилия: те замеры
                // сняты на усилии по умолчанию, туда и ложатся.
                val key = if (name.count { it == '|' } == 1) {
                    name + "|" + Models.effectiveEffort(name.substringAfter('|'), "")
                } else name
                roads[key] = Pace.Road(
                    acc = Pace.Acc(
                        n = o.optDouble("n", 0.0),
                        sumX = o.optDouble("x", 0.0),
                        sumY = o.optDouble("y", 0.0),
                        sumXX = o.optDouble("xx", 0.0),
                        sumXY = o.optDouble("xy", 0.0),
                    ),
                    coldN = o.optDouble("cn", 0.0),
                    coldSum = o.optDouble("cs", 0.0),
                    warmN = o.optDouble("wn", 0.0),
                    errN = o.optDouble("en", 0.0),
                    errAbs = o.optDouble("ea", 0.0),
                    errBias = o.optDouble("eb", 0.0),
                    lastAt = o.optLong("at", 0L),
                    tune = Pace.Tune(
                        decay = o.optDouble("td", Pace.DECAY),
                        clip = o.optBoolean("tc", true),
                        cold = o.optBoolean("tk", true),
                        shift = o.optDouble("ts", 0.0),
                    ),
                )
            }
            // Сборка 26.09 (утро) держала журнал прямо в pace.json — он
            // переезжает в свой файл один раз.
            root.optJSONArray("log")?.let { arr ->
                legacyLog = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(Sample::of) }
            }
        }
        var dirty = legacyLog.isNotEmpty()
        if (charsVersion < CHARS_VERSION) {
            for ((k, r) in roads.entries.toList()) {
                val route = k.substringBefore('|')
                if (route in LEGACY_ROUTES && r.acc.n > 0.0) {
                    roads[k] = r.copy(acc = Pace.relocate(r.acc, LEGACY_CHARS, LEGACY_WEIGHT))
                }
            }
            charsVersion = CHARS_VERSION
            dirty = true
        }
        if (dirty) {
            val snapshot = HashMap(roads)
            val at = seed
            val lines = legacyLog.map { it.json() }
            val tAt = tuneAt
            val tLines = tuneLines
            DiskWriter.post {
                if (lines.isNotEmpty()) appendLog(lines)
                persist(snapshot, at, tAt, tLines)
            }
        }
    }

    /**
     * Прошлое дуги — из журнала правок, один раз после обновления. Идёт с
     * фонового потока: журнал это мегабайты, и разбирать их под замком, пока
     * служба ждёт ответа на «сколько ждать», нельзя.
     */
    fun seedFromHistory(history: HistoryLog) {
        if (seeded()) return
        // Диск — ВНЕ замка, в накопитель кладём уже разобранное.
        val rows = runCatching { history.readTimings(PaceSeed.HISTORY_TAIL) }.getOrNull().orEmpty()
        absorb(rows)
    }

    @Synchronized
    private fun seeded(): Boolean {
        load()
        return seed >= SEED_VERSION
    }

    @Synchronized
    private fun absorb(rows: List<HistoryLog.Timing>) {
        load()
        if (seed >= SEED_VERSION) return
        for (t in rows) {
            if (!PaceSeed.counts(t.model)) continue
            val route = PaceSeed.route(t.mode) ?: continue
            // В журнале усилия нет: его записи сняты на «по умолчанию».
            val k = key(route, "", false, t.model, "")
            val r = roads[k] ?: Pace.Road()
            roads[k] = r.copy(acc = Pace.add(r.acc, t.chars, t.ms))
        }
        seed = SEED_VERSION
        persistLater()
    }

    /**
     * Сколько ждать запроса на [chars] знаках по дороге [route] (вида [kind],
     * со снимком — [photo]) моделью [model] с усилием [effort].
     */
    @Synchronized
    fun expect(
        route: String,
        kind: String,
        photo: Boolean,
        model: String,
        effort: String,
        chars: Int,
        now: Long = System.currentTimeMillis(),
    ): Long {
        load()
        val k = key(route, kind, photo, model, effort)
        return Pace.expect(roads[k], priorOf(k), chars, now)
    }

    /**
     * Сколько займёт следующий шаг по дороге [route], если он пойдёт той же
     * моделью, что в прошлый раз (а не бывало — заводской). Развилке «З»:
     * следом за ней почти всегда идёт разбор Засечки, и отсчёт обещает оба.
     */
    @Synchronized
    fun expectNext(route: String, chars: Int, now: Long = System.currentTimeMillis()): Long {
        load()
        val last = roads.entries
            .filter { it.key.substringBefore('|') == route }
            .maxByOrNull { it.value.lastAt }
        if (last != null) return Pace.expect(last.value, priorOf(last.key), chars, now)
        val def = ModelRoute.entries.firstOrNull { it.key == route }?.let { ModelChoice.defaultOf(it) }
            ?: return 0L
        return expect(route, "", false, def.model, def.effort, chars, now)
    }

    /**
     * Ответ пришёл: запомнить, сколько он шёл. [cache] — как прошёл кэш
     * промпта (`ClaudeProvider.workDone`).
     */
    @Synchronized
    fun record(
        route: String,
        kind: String,
        photo: Boolean,
        model: String,
        effort: String,
        chars: Int,
        ms: Long,
        cache: Boolean?,
        now: Long = System.currentTimeMillis(),
    ) {
        load()
        val k = key(route, kind, photo, model, effort)
        val prior = priorOf(k)
        val before = roads[k]
        val startedAt = now - ms
        val promised = Pace.expect(before, prior, chars, startedAt)
        // В накопителе только свои замеры: прикидка подмешивается на оценке
        // и тает, а не оседает в сумме навсегда.
        roads[k] = Pace.learn(before, prior, chars, ms, cache, now)
        val line = Sample(
            at = startedAt,
            key = k,
            chars = chars,
            expected = promised,
            actual = ms,
            cache = when (cache) { true -> 1; false -> 0; null -> -1 },
            coldExpected = Pace.coldLikely(before, startedAt),
        ).json()
        val snapshot = HashMap(roads)
        val at = seed
        val tAt = tuneAt
        val tLines = tuneLines
        DiskWriter.post {
            appendLog(listOf(line))
            persist(snapshot, at, tAt, tLines)
        }
    }

    // ---- Ночная калибровка ----

    /** Раз в сутки ночью (`PaceTune.due`) — из пятиминутного тика службы, НЕ с главного потока. */
    fun tuneIfDue(history: HistoryLog, log: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val last = synchronized(this) {
            load()
            tuneAt
        }
        if (!PaceTune.due(now, last, hour)) return
        val lines = tuneNow(history)
        lines.forEach(log)
    }

    /**
     * Прогнать ВСЮ историю каждой дороги заново всеми способами счёта и
     * оставить лучший (`core/PaceTune.kt`). История — журнал замеров плюс,
     * для Правки, журнал правок с июля (раньше журнала замеров; позже —
     * замеры уже в журнале, дважды не считаем). Диск и счёт — ВНЕ замка: у
     * кнопки в это время может уйти запрос. Звать не на главном потоке.
     * Возвращает итог строками — для настроек и журнала автоматов.
     */
    fun tuneNow(history: HistoryLog): List<String> {
        val started = System.currentTimeMillis()
        // Что было у дорог ДО чтения журнала: пришёл замер, пока считали, —
        // дорогу не трогаем (его в прогоне нет), её возьмёт следующая ночь.
        val before: Map<String, Long> = synchronized(this) {
            load()
            roads.mapValues { it.value.lastAt }
        }
        val logged = readLog()
        val historyRows = runCatching { history.readPaceRows() }.getOrDefault(emptyList())
        val byKey = HashMap<String, MutableList<PaceTune.Sample>>()
        // С какого мига дорога пишет свой журнал: журнал правок раньше этого —
        // история, позже — те же запросы второй раз. По ДОРОГЕ, а не по
        // ключу: усилия журнал правок не знает и мог бы лечь не в тот ключ.
        val firstLogged = HashMap<String, Long>()
        for (s in logged) {
            val done = s.at + s.actual
            byKey.getOrPut(s.key) { ArrayList() } += PaceTune.Sample(done, s.chars, s.actual, cacheOf(s.cache))
            val route = s.key.substringBefore('|').substringBefore('·')
            firstLogged[route] = minOf(firstLogged[route] ?: Long.MAX_VALUE, done)
        }
        var fromHistory = 0
        for (r in historyRows) {
            if (!PaceSeed.counts(r.model)) continue
            val route = PaceSeed.route(r.mode) ?: continue
            if (r.at >= (firstLogged[route] ?: Long.MAX_VALUE)) continue
            val kind = if (r.mode.startsWith("ASSIST_")) "помощник" else ""
            val k = key(route, kind, false, r.model, "")
            byKey.getOrPut(k) { ArrayList() } += PaceTune.Sample(r.at, r.chars, r.ms, r.cache)
            fromHistory++
        }
        val priors = synchronized(this) { byKey.keys.associateWith { priorOf(it) } }
        val results = HashMap<String, PaceTune.Result>()
        for ((k, samples) in byKey) {
            runCatching { PaceTune.tune(samples, priors.getValue(k)) }.getOrNull()?.let { results[k] = it }
        }
        val took = System.currentTimeMillis() - started
        val total = byKey.values.sumOf { it.size }
        val lines = ArrayList<String>()
        lines += "секунды: калибровка · замеров %d (из журнала правок %d) · дорог %d · %.1f с".format(
            Locale.US, total, fromHistory, results.size, took / 1000.0,
        )
        synchronized(this) {
            for ((k, res) in results) {
                val current = roads[k]
                if (current != null && current.lastAt != (before[k] ?: 0L)) continue
                roads[k] = res.road
                lines += "  %s: %s · мимо ±%.1f с (по-заводскому ±%.1f) · замеров %d".format(
                    Locale.US,
                    label(k),
                    PaceTune.describe(res.tune),
                    res.miss / 1000.0,
                    res.factoryMiss / 1000.0,
                    res.n,
                )
            }
            val few = byKey.count { (k, v) -> k !in results && v.isNotEmpty() }
            if (few > 0) lines += "  ещё дорог: $few — замеров меньше ${PaceTune.MIN_SAMPLES}, считают по-заводскому"
            tuneAt = started
            tuneLines = lines.toList()
            persistLater()
        }
        return lines
    }

    /** Когда была калибровка и что вышло — для настроек. */
    @Synchronized
    fun lastTune(): Pair<Long, List<String>> {
        load()
        return tuneAt to tuneLines
    }

    // ---- Показать ----

    /**
     * Что прогноз знает о каждой дороге — строками для настроек. Владелец
     * (20.09.2026): «ты точно рассчитал средние? И ты точно учитываешь модель
     * и количество знаков? Короче, посмотри». Единственный честный ответ на
     * такой вопрос — показать числа: прямую, по которой считает кнопка,
     * добавку холодного кэша, способ, выбранный калибровкой, и то, НАСКОЛЬКО
     * отсчёт промахивается (с 26.09.2026: «по секундам посмотри, обучается ли
     * он сам»). Промах меряется до того, как замер лёг в прямую, — это ошибка
     * прогноза, а не то, как прямая помнит сама себя.
     */
    @Synchronized
    fun summary(now: Long = System.currentTimeMillis()): List<String> {
        load()
        val (recent, old) = roads.entries.partition { it.value.lastAt <= 0L || now - it.value.lastAt < RECENT_MS }
        val lines = recent
            .sortedByDescending { it.value.acc.n }
            .map { (key, road) ->
                // Показываем ту самую прямую, по которой считает кнопка, —
                // со всем, что в неё сейчас подмешано.
                val l = Pace.line(Pace.mix(road.acc, priorOf(key)))
                val body = when {
                    l == null -> "замеров нет"
                    l.straight -> "%.1f с + %.1f мс на знак".format(Locale.US, l.baseMs / 1000.0, l.msPerChar)
                    else -> "среднее %.1f с (длина ещё не учтена)".format(Locale.US, l.meanMs / 1000.0)
                }
                val cold = Pace.coldExtra(road).takeIf { it >= 100.0 }
                    ?.let { " · холодный кэш +%.1f с".format(Locale.US, it / 1000.0) }.orEmpty()
                val miss = road.miss?.let { m ->
                    val b = road.bias ?: 0.0
                    val side = when {
                        abs(b) < 100.0 -> ""
                        b > 0 -> ", ответ позже на %.1f".format(Locale.US, b / 1000.0)
                        else -> ", ответ раньше на %.1f".format(Locale.US, -b / 1000.0)
                    }
                    " · мимо ±%.1f с%s".format(Locale.US, m / 1000.0, side)
                }.orEmpty()
                val tuned = if (road.tune.factory) "" else " · калибровка: " + PaceTune.describe(road.tune)
                val n = road.acc.n.roundToInt()
                val tail = when {
                    road.acc.n >= Pace.PRIOR_FADE_AT -> ""
                    sibling(key) != null -> " + прямая соседки через коэффициенты моделей"
                    else -> " + прикидка"
                }
                "${label(key)}: $body$cold$miss$tuned · замеров $n$tail"
            }
        val oldLine = if (old.isEmpty()) emptyList()
        else listOf("без дела больше месяца: ${old.size} (модели и дороги, которыми больше не ходят)")
        return lines + oldLine + "с завода, пока замеров нет: ${PaceSeed.factoryLine()}"
    }

    /** Сколько замеров лежит для выгрузки. Читает файл — звать не на главном потоке. */
    fun logSize(): Int = runCatching {
        if (!logFile.exists()) 0 else logFile.useLines { it.count { l -> l.isNotBlank() } }
    }.getOrDefault(0)

    /**
     * Замеры CSV-файлом — поделиться. Колонки: когда ушёл, дорога, вид,
     * снимок, модель, усилие, знаков, обещано и вышло (с), промах (с, плюс —
     * позже обещанного), кэш (warm/cold/none), ждали ли холодного. Этого
     * хватает, чтобы пересчитать прямые где угодно и проверить, что кнопка
     * считает то, что показывает.
     */
    fun shareCsvIntent(): Intent {
        val rows = readLog()
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val csv = buildString {
            append("ts,route,kind,photo,model,effort,chars,expected_sec,actual_sec,miss_sec,cache,cold_expected\n")
            for (s in rows) {
                val parts = s.key.split('|')
                val head = parts[0].split('·')
                val tags = head.drop(1)
                append(stamp.format(Date(s.at))).append(',')
                append(head[0]).append(',')
                append(tags.filter { it != "фото" }.joinToString("·")).append(',')
                append(tags.contains("фото")).append(',')
                append(parts.getOrElse(1) { "" }).append(',')
                append(parts.getOrElse(2) { "" }).append(',')
                append(s.chars).append(',')
                append(String.format(Locale.US, "%.2f", s.expected / 1000.0)).append(',')
                append(String.format(Locale.US, "%.2f", s.actual / 1000.0)).append(',')
                append(String.format(Locale.US, "%.2f", (s.actual - s.expected) / 1000.0)).append(',')
                append(when (s.cache) { 1 -> "warm"; 0 -> "cold"; else -> "none" }).append(',')
                append(s.coldExpected).append('\n')
            }
        }
        val out = File(context.cacheDir, "pravka-claude-timings.csv")
        out.writeText(csv)
        return shareFileIntent(context, out, "text/csv")
    }

    // ---- Внутреннее ----

    /**
     * С чего дорога начинает, пока своих замеров мало. Есть у этой же дороги
     * (того же вида, со снимком или без) обученная соседка на ДРУГОЙ модели
     * или усилии — её прямая, пересчитанная взаимными коэффициентами моделей
     * (`PaceSeed.transfer`): форма дороги своя, модель — новая. Нет —
     * заводская прикидка. Владелец (26.09.2026) менял и модели, и усилие;
     * после каждой смены дорога, по которой ходят раз-два в день, неделю
     * жила бы заводским, хотя сама же всё знала на прошлой модели.
     * Под замком.
     */
    private fun priorOf(key: String): Pace.Acc {
        val parts = key.split('|')
        val model = parts.getOrElse(1) { "" }
        val effort = parts.getOrElse(2) { "" }
        val sib = sibling(key) ?: return PaceSeed.prior(model, effort)
        val sp = sib.key.split('|')
        val known = Pace.line(sib.value.acc) ?: return PaceSeed.prior(model, effort)
        return PaceSeed.transfer(known, sp.getOrElse(1) { "" }, sp.getOrElse(2) { "" }, model, effort)
    }

    /** Самая свежая обученная соседка ключа: та же дорога·вид·фото, другая модель или усилие. */
    private fun sibling(key: String): Map.Entry<String, Pace.Road>? {
        val head = key.substringBefore('|')
        return roads.entries
            .filter { it.key != key && it.key.substringBefore('|') == head && it.value.acc.n >= Pace.PRIOR_FADE_AT }
            .maxByOrNull { it.value.lastAt }
    }

    /** «pravka · opus-5-5 medium», «body·вопрос · sonnet-5 high». */
    private fun label(key: String): String {
        val parts = key.split('|')
        val effort = parts.getOrElse(2) { "" }
        return parts[0] + " · " + PaceSeed.shortModel(parts.getOrElse(1) { "" }) +
            if (effort.isNotBlank()) " $effort" else ""
    }

    private fun cacheOf(code: Int): Boolean? = when (code) { 1 -> true; 0 -> false; else -> null }

    private fun key(route: String, kind: String, photo: Boolean, model: String, effort: String): String {
        val head = buildString {
            append(route.ifBlank { "общая" })
            if (kind.isNotBlank()) append('·').append(kind)
            if (photo) append("·фото")
        }
        return head + "|" + model + "|" + Models.effectiveEffort(model, effort)
    }

    /** Весь журнал замеров по порядку. Звать не на главном потоке. */
    private fun readLog(): List<Sample> {
        // Дописи ещё в очереди писателя — дождаться их, чтобы выгрузка и
        // калибровка видели последний ответ, а не позапрошлый.
        DiskWriter.drain(5_000L)
        return runCatching {
            if (!logFile.exists()) emptyList()
            else logFile.useLines { seq ->
                seq.mapNotNull { l -> runCatching { Sample.of(JSONObject(l)) }.getOrNull() }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** Только на writer-потоке. */
    private fun appendLog(lines: List<String>) {
        runCatching {
            if (logFile.exists() && logFile.length() > LOG_MAX_BYTES) {
                val keep = logFile.readLines().let { it.subList(it.size / 2, it.size) }
                StoreFiles.writeAtomic(logFile, keep.joinToString("\n", postfix = "\n"))
            }
            logFile.appendText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    /** Под замком: снимок и запись на writer-потоке. */
    private fun persistLater() {
        val snapshot = HashMap(roads)
        val at = seed
        val tAt = tuneAt
        val tLines = tuneLines
        DiskWriter.post { persist(snapshot, at, tAt, tLines) }
    }

    private fun persist(snapshot: Map<String, Pace.Road>, seedAt: Int, tAt: Long, tLines: List<String>) {
        runCatching {
            val out = JSONObject()
            for ((k, r) in snapshot) {
                out.put(
                    k,
                    JSONObject()
                        .put("n", r.acc.n)
                        .put("x", r.acc.sumX)
                        .put("y", r.acc.sumY)
                        .put("xx", r.acc.sumXX)
                        .put("xy", r.acc.sumXY)
                        .put("cn", r.coldN)
                        .put("cs", r.coldSum)
                        .put("wn", r.warmN)
                        .put("en", r.errN)
                        .put("ea", r.errAbs)
                        .put("eb", r.errBias)
                        .put("at", r.lastAt)
                        .put("td", r.tune.decay)
                        .put("tc", r.tune.clip)
                        .put("tk", r.tune.cold)
                        .put("ts", r.tune.shift),
                )
            }
            StoreFiles.writeAtomic(
                file,
                JSONObject()
                    .put("seed", seedAt)
                    .put("chars", CHARS_VERSION)
                    .put("tuneAt", tAt)
                    .put("tuneLines", JSONArray(tLines))
                    .put("roads", out)
                    .toString(),
            )
        }
    }
}
