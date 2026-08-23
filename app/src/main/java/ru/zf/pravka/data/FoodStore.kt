package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.MealItem

// Еда: дневник приёмов пищи с разобранным КБЖУ (`food.json`).
//
// Это НЕ кэш. Сказанное «омлет из трёх яиц и тост с авокадо» нельзя добыть
// заново ниоткуда - как и ленту Засечки. Поэтому дисциплина здесь ленточная:
//   - запись атомарна (tmp + fsync + переименование), рядом лежит `.prev`;
//   - пустой список поверх непустого файла ОТКЛОНЯЕТСЯ: дневник не умеет
//     очищаться сам, значит это баг кода (тот же заслон, что у ленты после
//     инцидента 22.08.2026);
//   - файл в часовых копиях (Backups).
//
// Приём пищи живёт в двух состояниях. Разобранный, но не подтверждённый -
// `pending`: он уже на диске (модель отвечала не бесплатно), но в итоги дня не
// идёт и наружу не уезжает. «ОК» на плашке делает его подтверждённым, и только
// тогда он попадает в сумму дня, в ленту и в intervals.icu.
class FoodStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "food.json"
        // Год с лишним: столько дневник имеет смысл держать под рукой, а
        // выгрузка CSV забирает его целиком, если понадобится дальше.
        private const val KEEP_DAYS = 420
        // Неподтверждённые приёмы старше суток - брошенные: владелец наговорил
        // и ушёл, плашка истекла. Держим сутки и убираем, иначе они копятся.
        private const val PENDING_TTL_MS = 36 * 3_600_000L
    }

    /** Приём пищи: что сказано, что из этого вышло и куда уже уехало. */
    data class Meal(
        val id: Long,
        val ts: Long,                  // когда съедено (не когда записано)
        val createdAt: Long,
        val kind: String,              // завтрак | обед | ужин | перекус
        val raw: String,               // что владелец сказал буква в букву
        val items: List<MealItem>,
        val note: String = "",         // замечание модели: чего не хватило
        val source: String = "voice",  // voice | text | photo | barcode
        val photo: String = "",        // имя файла в filesDir/food
        val confirmed: Boolean = false,
        val icuSynced: Boolean = false,
        val ribbonSynced: Boolean = false,
        val costUsd: Double = 0.0,
        val model: String = "",
    ) {
        val kcal: Int get() = items.sumOf { it.kcal }
        val protein: Int get() = items.sumOf { it.protein }
        val fat: Int get() = items.sumOf { it.fat }
        val carbs: Int get() = items.sumOf { it.carbs }
        val fiber: Int get() = items.sumOf { it.fiber }
        val grams: Int get() = items.sumOf { it.grams }

        /** «Омлет, тост, кофе» - строка для плашки и для ленты. */
        val shortList: String get() = items.joinToString(", ") { it.name }
    }

    /** Сумма дня: то, что видно во вкладке и что уезжает в wellness. */
    data class DayTotal(
        val date: String,
        val kcal: Int,
        val protein: Int,
        val fat: Int,
        val carbs: Int,
        val fiber: Int,
        val meals: Int,
    ) {
        val empty: Boolean get() = meals == 0
    }

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private val _mealsFlow = MutableStateFlow<List<Meal>>(emptyList())
    val mealsFlow: StateFlow<List<Meal>> = _mealsFlow

    var logger: ((String) -> Unit)? = null

    /** Куда складываем снимки тарелок. */
    fun photoDir(): File = File(context.filesDir, "food").also { it.mkdirs() }

    fun photoFile(name: String): File? =
        if (name.isBlank()) null else File(photoDir(), name).takeIf { it.exists() }

    suspend fun load(): List<Meal> = mutex.withLock { ensureLoaded(); _mealsFlow.value }

    /** Свежий разбор ложится на диск сразу — до того, как владелец его увидел. */
    suspend fun add(
        ts: Long,
        kind: String,
        raw: String,
        items: List<MealItem>,
        note: String,
        source: String,
        photo: String,
        costUsd: Double,
        model: String,
    ): Meal = mutex.withLock {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val meal = Meal(
            id = now,
            ts = if (ts > 0) ts else now,
            createdAt = now,
            kind = kind,
            raw = raw,
            items = items,
            note = note,
            source = source,
            photo = photo,
            costUsd = costUsd,
            model = model,
        )
        write(listOf(meal) + _mealsFlow.value)
        meal
    }

    /** «ОК» на плашке: приём становится частью дня. */
    suspend fun confirm(id: Long): Meal? = mutex.withLock {
        ensureLoaded()
        val updated = _mealsFlow.value.map { if (it.id == id) it.copy(confirmed = true) else it }
        write(updated)
        updated.firstOrNull { it.id == id }
    }

    suspend fun replaceItems(id: Long, items: List<MealItem>) = mutex.withLock {
        ensureLoaded()
        write(
            _mealsFlow.value.map {
                // Правка руками сбрасывает отметки об отправке: суммы дня
                // изменились, значит их надо донести заново.
                if (it.id == id) it.copy(items = items, icuSynced = false, ribbonSynced = false) else it
            }
        )
    }

    suspend fun setKind(id: Long, kind: String) = mutex.withLock {
        ensureLoaded()
        write(_mealsFlow.value.map { if (it.id == id) it.copy(kind = kind) else it })
    }

    suspend fun setTime(id: Long, ts: Long) = mutex.withLock {
        ensureLoaded()
        write(
            _mealsFlow.value.map {
                if (it.id == id) it.copy(ts = ts, icuSynced = false, ribbonSynced = false) else it
            }
        )
    }

    suspend fun markIcuSynced(ids: Collection<Long>) = mutex.withLock {
        ensureLoaded()
        if (ids.isEmpty()) return@withLock
        write(_mealsFlow.value.map { if (it.id in ids) it.copy(icuSynced = true) else it })
    }

    suspend fun markRibbonSynced(id: Long) = mutex.withLock {
        ensureLoaded()
        write(_mealsFlow.value.map { if (it.id == id) it.copy(ribbonSynced = true) else it })
    }

    suspend fun delete(id: Long) = mutex.withLock {
        ensureLoaded()
        val gone = _mealsFlow.value.firstOrNull { it.id == id }
        // allowEmpty: удалить единственный приём — законное действие владельца,
        // и заслон против пустой записи не должен его отменять.
        write(_mealsFlow.value.filterNot { it.id == id }, allowEmpty = true)
        // Снимок удаляем вместе с записью: он больше ни к чему не относится.
        gone?.photo?.takeIf { it.isNotBlank() }?.let { name ->
            DiskWriter.post { runCatching { File(photoDir(), name).delete() } }
        }
    }

    fun byId(id: Long): Meal? = _mealsFlow.value.firstOrNull { it.id == id }

    /** Неподтверждённые разборы, свежие сверху: их показывает плашка и вкладка. */
    fun pending(): List<Meal> = _mealsFlow.value.filter { !it.confirmed }

    fun mealsOn(date: String): List<Meal> =
        _mealsFlow.value.filter { it.confirmed && dayKey(it.ts) == date }.sortedBy { it.ts }

    /** Итог дня по подтверждённым приёмам. */
    fun dayTotal(date: String): DayTotal {
        val meals = mealsOn(date)
        return DayTotal(
            date = date,
            kcal = meals.sumOf { it.kcal },
            protein = meals.sumOf { it.protein },
            fat = meals.sumOf { it.fat },
            carbs = meals.sumOf { it.carbs },
            fiber = meals.sumOf { it.fiber },
            meals = meals.size,
        )
    }

    /** Итоги за последние [days] дней, свежие сверху; пустые дни пропускаются. */
    fun recentDays(days: Int): List<DayTotal> {
        val now = System.currentTimeMillis()
        return (0 until days)
            .map { dayTotal(dayKey(now - it * 86_400_000L)) }
            .filterNot { it.empty }
    }

    /** Дни, чей итог ещё не уехал в intervals.icu (подтверждённые приёмы). */
    fun daysNeedingIcu(): List<String> = _mealsFlow.value
        .filter { it.confirmed && !it.icuSynced }
        .map { dayKey(it.ts) }
        .distinct()

    fun mealIdsOn(date: String): List<Long> =
        _mealsFlow.value.filter { it.confirmed && dayKey(it.ts) == date }.map { it.id }

    // ---- Диск ----

    private suspend fun ensureLoaded() {
        if (loaded) return
        val parsed = withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { text -> parse(JSONArray(text)) }
        }
        loaded = true
        if (parsed != null) _mealsFlow.value = parsed
    }

    private fun write(list: List<Meal>, allowEmpty: Boolean = false) {
        val now = System.currentTimeMillis()
        val cutoff = now - KEEP_DAYS * 86_400_000L
        val kept = list
            .filterNot { !it.confirmed && now - it.createdAt > PENDING_TTL_MS }
            .filter { it.confirmed || it.createdAt >= cutoff }
            .filter { it.ts >= cutoff || !it.confirmed }
            .sortedByDescending { it.ts }
        // Заслон против потери дневника: непустой файл нельзя заменить пустым
        // списком. Дневник сам собой не очищается — если он вдруг пуст, это баг
        // кода, и цена ошибки здесь та же, что у ленты. Исключение одно:
        // владелец удалил последний приём руками, и это [allowEmpty].
        if (kept.isEmpty() && !allowEmpty && _mealsFlow.value.isNotEmpty()) {
            logger?.invoke("еда: запись пустого дневника поверх ${_mealsFlow.value.size} приёмов отклонена")
            return
        }
        _mealsFlow.value = kept
        val json = serialize(kept).toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun serialize(meals: List<Meal>): JSONArray = JSONArray().apply {
        for (m in meals) put(
            JSONObject().apply {
                put("id", m.id)
                put("ts", m.ts)
                put("created", m.createdAt)
                put("kind", m.kind)
                put("raw", m.raw)
                put("note", m.note)
                put("source", m.source)
                put("photo", m.photo)
                put("confirmed", m.confirmed)
                put("icu", m.icuSynced)
                put("ribbon", m.ribbonSynced)
                put("cost", m.costUsd)
                put("model", m.model)
                put(
                    "items",
                    JSONArray().apply {
                        for (it in m.items) put(
                            JSONObject().apply {
                                put("name", it.name)
                                put("g", it.grams)
                                put("kcal", it.kcal)
                                put("p", it.protein)
                                put("f", it.fat)
                                put("c", it.carbs)
                                put("fiber", it.fiber)
                                put("sure", it.sureness)
                            }
                        )
                    }
                )
            }
        )
    }

    private fun parse(array: JSONArray): List<Meal> {
        val out = mutableListOf<Meal>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val items = mutableListOf<MealItem>()
            o.optJSONArray("items")?.let { a ->
                for (j in 0 until a.length()) {
                    val it = a.optJSONObject(j) ?: continue
                    items.add(
                        MealItem(
                            name = it.optString("name"),
                            grams = it.optInt("g"),
                            kcal = it.optInt("kcal"),
                            protein = it.optInt("p"),
                            fat = it.optInt("f"),
                            carbs = it.optInt("c"),
                            fiber = it.optInt("fiber"),
                            sureness = it.optString("sure"),
                        )
                    )
                }
            }
            val ts = o.optLong("ts").takeIf { it > 0 } ?: o.optLong("created")
            out.add(
                Meal(
                    id = o.optLong("id", ts),
                    ts = ts,
                    createdAt = o.optLong("created", ts),
                    kind = o.optString("kind"),
                    raw = o.optString("raw"),
                    items = items,
                    note = o.optString("note"),
                    source = o.optString("source").ifBlank { "voice" },
                    photo = o.optString("photo"),
                    confirmed = o.optBoolean("confirmed", false),
                    icuSynced = o.optBoolean("icu", false),
                    ribbonSynced = o.optBoolean("ribbon", false),
                    costUsd = o.optDouble("cost", 0.0),
                    model = o.optString("model"),
                )
            )
        }
        return out.sortedByDescending { it.ts }
    }

    /** Выгрузка дневника: тот же путь наружу, что у CSV Засечки. */
    suspend fun shareCsvIntent(): android.content.Intent = withContext(Dispatchers.IO) {
        val sb = StringBuilder("date,time,kind,item,grams,kcal,protein,fat,carbs,fiber,source,raw\n")
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        for (m in _mealsFlow.value.filter { it.confirmed }.sortedBy { it.ts }) {
            for (item in m.items) {
                sb.append(dayKey(m.ts)).append(',')
                    .append(timeFormat.format(Date(m.ts))).append(',')
                    .append(cell(m.kind)).append(',')
                    .append(cell(item.name)).append(',')
                    .append(item.grams).append(',')
                    .append(item.kcal).append(',')
                    .append(item.protein).append(',')
                    .append(item.fat).append(',')
                    .append(item.carbs).append(',')
                    .append(item.fiber).append(',')
                    .append(cell(m.source)).append(',')
                    .append(cell(m.raw)).append('\n')
            }
        }
        val out = File(context.cacheDir, "pravka-eda.csv")
        out.writeText(sb.toString())
        shareFileIntent(context, out, "text/csv")
    }
}

/** yyyy-MM-dd в зоне телефона: ключ дня и в дневнике, и в wellness. */
internal fun dayKey(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
