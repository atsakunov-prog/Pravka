package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject
import ru.zf.pravka.core.Pace
import ru.zf.pravka.core.PaceSeed

/**
 * История «сколько идёт запрос» по тройкам «дорога + модель + усилие»
 * (усилие — с 22.09.2026: на xhigh Опус думает в разы дольше, чем на medium,
 * и общая прямая врала бы обоим). Считает
 * `core/Pace.kt`, здесь только хранение.
 *
 * Читают это с ГЛАВНОГО потока службы: дуга прогресса на стекле спрашивает
 * «сколько ждать» ровно в тот миг, когда запрос уходит, и ждать диска ей
 * некогда. Поэтому весь накопитель живёт в памяти, а на диск уезжает следом,
 * через `DiskWriter` — тем же порядком, что и журналы.
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
    }

    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    /** Ключ — «дорога|модель|усилие»; пустая дорога тоже ключ, просто общий. */
    private val acc = HashMap<String, Pace.Acc>()
    private var seed = 0
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return@runCatching
            val root = JSONObject(file.readText())
            seed = root.optInt("seed", 0)
            // До 20.09.2026 в корне лежали сразу дороги — читаем и так.
            val roads = root.optJSONObject("roads") ?: root
            for (stored in roads.keys()) {
                val o = roads.optJSONObject(stored) ?: continue
                // До 22.09.2026 ключ был «дорога|модель», без усилия: те замеры
                // сняты на усилии по умолчанию, туда и ложатся.
                val key = if (stored.count { it == '|' } == 1) {
                    stored + "|" + Models.effectiveEffort(stored.substringAfter('|'), "")
                } else stored
                acc[key] = Pace.Acc(
                    n = o.optDouble("n", 0.0),
                    sumX = o.optDouble("x", 0.0),
                    sumY = o.optDouble("y", 0.0),
                    sumXX = o.optDouble("xx", 0.0),
                    sumXY = o.optDouble("xy", 0.0),
                )
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
            val k = key(route, t.model, "")
            acc[k] = Pace.add(acc[k] ?: Pace.Acc(), t.chars, t.ms)
        }
        seed = SEED_VERSION
        val snapshot = HashMap(acc)
        DiskWriter.post { persist(snapshot, SEED_VERSION) }
    }

    /** Сколько ждать запроса на [chars] символах по дороге [route] моделью [model] с усилием [effort]. */
    @Synchronized
    fun expect(route: String, model: String, effort: String, chars: Int): Long {
        load()
        return Pace.estimate(Pace.mix(acc[key(route, model, effort)], PaceSeed.prior(model, effort)), chars)
    }

    /** Ответ пришёл: запомнить, сколько он шёл. */
    @Synchronized
    fun record(route: String, model: String, effort: String, chars: Int, ms: Long) {
        load()
        val k = key(route, model, effort)
        // В накопителе только свои замеры: прикидка подмешивается на оценке
        // и тает, а не оседает в сумме навсегда.
        acc[k] = Pace.add(acc[k] ?: Pace.Acc(), chars, ms)
        val snapshot = HashMap(acc)
        val at = seed
        DiskWriter.post { persist(snapshot, at) }
    }

    /**
     * Что дуга знает о каждой дороге — строками для настроек. Владелец
     * (20.09.2026): «ты точно рассчитал средние? И ты точно учитываешь модель
     * и количество знаков? Короче, посмотри». Единственный честный ответ на
     * такой вопрос — показать числа, а не пересказать их: видно и дорогу, и
     * модель, и основание, и цену знака, и сколько СВОИХ замеров за этим
     * стоит — заводская прикидка числом замеров не притворяется.
     */
    @Synchronized
    fun summary(): List<String> {
        load()
        val roads = acc.entries
            .sortedByDescending { it.value.n }
            .map { (key, a) ->
                val (road, modelId, effort) = key.split('|').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
                val model = PaceSeed.shortModel(modelId) + if (effort.isNotBlank()) " $effort" else ""
                // Показываем ту самую прямую, по которой дуга и считает, —
                // со всем, что в неё сейчас подмешано.
                val l = Pace.line(Pace.mix(a, PaceSeed.prior(modelId, effort)))
                val body = when {
                    l == null -> "замеров нет"
                    l.straight -> "%.1f с + %.1f мс на знак".format(Locale.US, l.baseMs / 1000.0, l.msPerChar)
                    else -> "среднее %.1f с (длина ещё не учтена)".format(Locale.US, l.meanMs / 1000.0)
                }
                val n = a.n.roundToInt()
                val tail = if (a.n < Pace.PRIOR_FADE_AT) " + прикидка" else ""
                "$road · $model: $body · замеров $n$tail"
            }
        return roads + "с завода, пока замеров нет: ${PaceSeed.factoryLine()}"
    }

    private fun key(route: String, model: String, effort: String): String =
        (route.ifBlank { "общая" }) + "|" + model + "|" + Models.effectiveEffort(model, effort)

    private fun persist(snapshot: Map<String, Pace.Acc>, seedAt: Int) {
        runCatching {
            val roads = JSONObject()
            for ((k, a) in snapshot) {
                roads.put(
                    k,
                    JSONObject()
                        .put("n", a.n)
                        .put("x", a.sumX)
                        .put("y", a.sumY)
                        .put("xx", a.sumXX)
                        .put("xy", a.sumXY),
                )
            }
            StoreFiles.writeAtomic(file, JSONObject().put("seed", seedAt).put("roads", roads).toString())
        }
    }
}
