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

/**
 * История «сколько идёт запрос» по тройкам «дорога + модель + усилие»
 * (усилие — с 22.09.2026: на xhigh Опус думает в разы дольше, чем на medium,
 * и общая прямая врала бы обоим). Считает `core/Pace.kt`, здесь хранение,
 * ключи и журнал замеров.
 *
 * Ключ дороги — ещё и ВИД запроса ([kind]: вопрос тренеру отвечает абзацами,
 * разбор подходов — строкой) и снимок ([photo]: картинка идёт в разы дольше
 * текста). Модель у них одна, а время — нет (26.09.2026).
 *
 * Читают это с потока запроса в миг, когда он уходит, и ждать диска некогда.
 * Поэтому всё живёт в памяти, а на диск уезжает следом, через `DiskWriter` —
 * тем же порядком, что и журналы.
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

        /** Сколько последних замеров держать для выгрузки: неделя-две работы. */
        private const val LOG_KEEP = 1_500
    }

    /** Один замер для выгрузки: что обещали и что вышло. */
    private data class Sample(
        val at: Long,
        val key: String,
        val chars: Int,
        val expected: Long,
        val actual: Long,
        /** 1 — кэш прочитан, 0 — записан (холодный), -1 — кэша нет. */
        val cache: Int,
        val coldExpected: Boolean,
    )

    private val file: File by lazy { File(DataRoot.dir(context), FILE_NAME) }

    /** Ключ — «дорога[·вид][·фото]|модель|усилие». */
    private val roads = HashMap<String, Pace.Road>()
    private val log = ArrayDeque<Sample>()
    private var seed = 0
    private var charsVersion = 0
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return@runCatching
            val root = JSONObject(file.readText())
            seed = root.optInt("seed", 0)
            charsVersion = root.optInt("chars", 0)
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
                )
            }
            root.optJSONArray("log")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    log.addLast(
                        Sample(
                            at = s.optLong("t"),
                            key = s.optString("k"),
                            chars = s.optInt("c"),
                            expected = s.optLong("e"),
                            actual = s.optLong("a"),
                            cache = s.optInt("h", -1),
                            coldExpected = s.optBoolean("p", false),
                        )
                    )
                }
            }
        }
        if (charsVersion < CHARS_VERSION) {
            for ((k, r) in roads.entries.toList()) {
                val route = k.substringBefore('|')
                if (route in LEGACY_ROUTES && r.acc.n > 0.0) {
                    roads[k] = r.copy(acc = Pace.relocate(r.acc, LEGACY_CHARS, LEGACY_WEIGHT))
                }
            }
            charsVersion = CHARS_VERSION
            val snapshot = HashMap(roads)
            val logSnap = log.toList()
            val at = seed
            DiskWriter.post { persist(snapshot, logSnap, at) }
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
        val snapshot = HashMap(roads)
        val logSnap = log.toList()
        DiskWriter.post { persist(snapshot, logSnap, SEED_VERSION) }
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
        return Pace.expect(roads[key(route, kind, photo, model, effort)], PaceSeed.prior(model, effort), chars, now)
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
        if (last != null) {
            val (_, model, effort) = last.key.split('|').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            return Pace.expect(last.value, PaceSeed.prior(model, effort), chars, now)
        }
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
        val prior = PaceSeed.prior(model, effort)
        val before = roads[k]
        val startedAt = now - ms
        val promised = Pace.expect(before, prior, chars, startedAt)
        // В накопителе только свои замеры: прикидка подмешивается на оценке
        // и тает, а не оседает в сумме навсегда.
        roads[k] = Pace.learn(before, prior, chars, ms, cache, now)
        log.addLast(
            Sample(
                at = startedAt,
                key = k,
                chars = chars,
                expected = promised,
                actual = ms,
                cache = when (cache) { true -> 1; false -> 0; null -> -1 },
                coldExpected = Pace.coldLikely(before, startedAt),
            )
        )
        while (log.size > LOG_KEEP) log.removeFirst()
        val snapshot = HashMap(roads)
        val logSnap = log.toList()
        val at = seed
        DiskWriter.post { persist(snapshot, logSnap, at) }
    }

    /**
     * Что прогноз знает о каждой дороге — строками для настроек. Владелец
     * (20.09.2026): «ты точно рассчитал средние? И ты точно учитываешь модель
     * и количество знаков? Короче, посмотри». Единственный честный ответ на
     * такой вопрос — показать числа: прямую, по которой считает кнопка,
     * добавку холодного кэша и то, НАСКОЛЬКО отсчёт промахивается (с
     * 26.09.2026: «по секундам посмотри, обучается ли он сам»). Промах
     * меряется до того, как замер лёг в прямую, — это ошибка прогноза, а не
     * то, как прямая помнит сама себя.
     */
    @Synchronized
    fun summary(): List<String> {
        load()
        val lines = roads.entries
            .sortedByDescending { it.value.acc.n }
            .map { (key, road) ->
                val parts = key.split('|')
                val road0 = parts[0]
                val modelId = parts.getOrElse(1) { "" }
                val effort = parts.getOrElse(2) { "" }
                val model = PaceSeed.shortModel(modelId) + if (effort.isNotBlank()) " $effort" else ""
                // Показываем ту самую прямую, по которой считает кнопка, —
                // со всем, что в неё сейчас подмешано.
                val l = Pace.line(Pace.mix(road.acc, PaceSeed.prior(modelId, effort)))
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
                val n = road.acc.n.roundToInt()
                val tail = if (road.acc.n < Pace.PRIOR_FADE_AT) " + прикидка" else ""
                "$road0 · $model: $body$cold$miss · замеров $n$tail"
            }
        return lines + "с завода, пока замеров нет: ${PaceSeed.factoryLine()}"
    }

    /** Сколько замеров лежит для выгрузки. */
    @Synchronized
    fun logSize(): Int {
        load()
        return log.size
    }

    /**
     * Замеры CSV-файлом — поделиться. Колонки: когда ушёл, дорога, вид,
     * снимок, модель, усилие, знаков, обещано и вышло (с), промах (с, плюс —
     * позже обещанного), кэш (warm/cold/none), ждали ли холодного. Этого
     * хватает, чтобы пересчитать прямые где угодно и проверить, что кнопка
     * считает то, что показывает.
     */
    fun shareCsvIntent(): Intent {
        val rows = synchronized(this) {
            load()
            log.toList()
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val csv = buildString {
            append("ts,route,kind,photo,model,effort,chars,expected_sec,actual_sec,miss_sec,cache,cold_expected\n")
            for (s in rows) {
                val parts = s.key.split('|')
                val head = parts[0].split('·')
                val route = head[0]
                val photo = head.drop(1).contains("фото")
                val kind = head.drop(1).filter { it != "фото" }.joinToString("·")
                append(stamp.format(Date(s.at))).append(',')
                append(route).append(',')
                append(kind).append(',')
                append(photo).append(',')
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

    private fun key(route: String, kind: String, photo: Boolean, model: String, effort: String): String {
        val head = buildString {
            append(route.ifBlank { "общая" })
            if (kind.isNotBlank()) append('·').append(kind)
            if (photo) append("·фото")
        }
        return head + "|" + model + "|" + Models.effectiveEffort(model, effort)
    }

    private fun persist(snapshot: Map<String, Pace.Road>, logSnap: List<Sample>, seedAt: Int) {
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
                        .put("at", r.lastAt),
                )
            }
            val arr = JSONArray()
            for (s in logSnap) {
                arr.put(
                    JSONObject()
                        .put("t", s.at)
                        .put("k", s.key)
                        .put("c", s.chars)
                        .put("e", s.expected)
                        .put("a", s.actual)
                        .put("h", s.cache)
                        .put("p", s.coldExpected)
                )
            }
            StoreFiles.writeAtomic(
                file,
                JSONObject()
                    .put("seed", seedAt)
                    .put("chars", CHARS_VERSION)
                    .put("roads", out)
                    .put("log", arr)
                    .toString(),
            )
        }
    }
}
