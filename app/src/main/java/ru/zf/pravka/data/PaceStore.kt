package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject
import ru.zf.pravka.core.Pace

/**
 * История «сколько идёт запрос» по парам «дорога + модель». Считает
 * `core/Pace.kt`, здесь только хранение.
 *
 * Читают это с ГЛАВНОГО потока службы: дуга прогресса на стекле спрашивает
 * «сколько ждать» ровно в тот миг, когда запрос уходит, и ждать диска ей
 * некогда. Поэтому весь накопитель живёт в памяти, а на диск уезжает следом,
 * через `DiskWriter` — тем же порядком, что и журналы.
 *
 * Данные расходные: потерялись — дуга первые несколько запросов идёт вслепую и
 * снова учится. Поэтому ни `.prev`, ни карантина тут нет, в отличие от ленты.
 */
class PaceStore(private val context: Context) {

    private companion object {
        private const val FILE_NAME = "pace.json"
    }

    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    /** Ключ — «дорога|модель»; пустая дорога тоже ключ, просто общий. */
    private val acc = HashMap<String, Pace.Acc>()
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return@runCatching
            val root = JSONObject(file.readText())
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue
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

    /** Сколько ждать запроса на [chars] символах по дороге [route] моделью [model]. */
    @Synchronized
    fun expect(route: String, model: String, chars: Int): Long {
        load()
        return Pace.estimate(acc[key(route, model)], chars)
    }

    /** Ответ пришёл: запомнить, сколько он шёл. */
    @Synchronized
    fun record(route: String, model: String, chars: Int, ms: Long) {
        load()
        val k = key(route, model)
        acc[k] = Pace.add(acc[k] ?: Pace.Acc(), chars, ms)
        val snapshot = HashMap(acc)
        DiskWriter.post { persist(snapshot) }
    }

    /**
     * Что дуга знает о каждой дороге — строками для настроек. Владелец
     * (20.09.2026): «ты точно рассчитал средние? И ты точно учитываешь модель
     * и количество знаков? Короче, посмотри». Единственный честный ответ на
     * такой вопрос — показать числа, а не пересказать их: видно и дорогу, и
     * модель, и основание, и цену знака, и сколько замеров за этим стоит.
     */
    @Synchronized
    fun summary(): List<String> {
        load()
        return acc.entries
            .sortedByDescending { it.value.n }
            .map { (key, a) ->
                val road = key.substringBefore('|')
                val model = key.substringAfter('|').substringAfterLast('-').ifBlank { key.substringAfter('|') }
                val l = Pace.line(a)
                val n = l?.n?.roundToInt() ?: 0
                val body = when {
                    l == null -> "замеров нет"
                    l.straight -> "%.1f с + %.1f мс на знак".format(Locale.US, l.baseMs / 1000.0, l.msPerChar)
                    else -> "среднее %.1f с (длина ещё не учтена)".format(Locale.US, l.meanMs / 1000.0)
                }
                "$road · $model: $body · замеров $n"
            }
    }

    private fun key(route: String, model: String): String =
        (route.ifBlank { "общая" }) + "|" + model

    private fun persist(snapshot: Map<String, Pace.Acc>) {
        runCatching {
            val root = JSONObject()
            for ((k, a) in snapshot) {
                root.put(
                    k,
                    JSONObject()
                        .put("n", a.n)
                        .put("x", a.sumX)
                        .put("y", a.sumY)
                        .put("xx", a.sumXX)
                        .put("xy", a.sumXY),
                )
            }
            StoreFiles.writeAtomic(file, root.toString())
        }
    }
}
