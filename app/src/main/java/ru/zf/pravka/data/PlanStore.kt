package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// План: что владелец делает сегодня и по каким правилам (`plan.json`).
//
// Кэш из двух источников, и разделение между ними ровно такое, как владелец
// его описал: «Notion — оперативка и знания, intervals.icu — календарь и факт».
//
//   СКЕЛЕТ ДНЯ — из календаря intervals. Он там уже полный: название сессии,
//   тип, длительность, нагрузка и нумерованный список упражнений со схемами
//   прямо в описании. Структура, а не проза, и ключ у приложения уже есть.
//
//   ПРАВИЛА — из страницы блока в Notion. Их владелец правит руками, и они
//   прозой: потолок пульса, каденс, серая зона, сколько пробежек в неделю,
//   светофор колена, правило отмены. Раз в сутки страница читается, Сонет
//   вынимает из неё числа, они ложатся сюда.
//
// Всё это КЭШ: потерять не страшно, отрастёт. Но пустой ответ сюда не пишется -
// на даче интернета нет, и вкладка должна показывать вчерашнее знание, а не
// пустоту.
class PlanStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "plan.json"
    }

    /** Один день плана — как он лежит в календаре intervals. */
    data class PlanDay(
        val eventId: String,
        val date: String,            // yyyy-MM-dd
        val name: String,
        val type: String,            // Run | Ride | WeightTraining | Note...
        val minutes: Int,
        val load: Int,
        val description: String,     // включая нумерованный список упражнений
        val carbsPerHour: Int = 0,
    ) {
        val strength: Boolean get() = type.equals("WeightTraining", ignoreCase = true)

        /**
         * Блок силовой по названию сессии: «Силовая A — ноги…» → «A · дом».
         * Полевая C — дачная, поэтому у неё свой блок в справочнике.
         */
        val block: String
            get() {
                if (!strength) return ""
                val n = name.lowercase()
                return when {
                    n.contains("силовая a") || n.contains("силовая а") -> "A · дом"
                    n.contains("силовая b") || n.contains("силовая в") -> "B · дом"
                    n.contains("силовая c") || n.contains("силовая с") -> "C · полевой"
                    n.contains("зарядка") -> "Зарядка"
                    n.contains("турник") -> "Турник"
                    else -> ""
                }
            }

        /**
         * Упражнения, выписанные в описании нумерованным списком:
         * «1. Гоблет-присед 3х8 (легко)» → «Гоблет-присед 3х8 (легко)».
         * Это план на сегодня буква в букву — по нему и строится карточка.
         */
        fun plannedLines(): List<String> =
            description.lines()
                .map { it.trim() }
                .filter { Regex("^\\d+[.)]\\s+\\S").containsMatchIn(it) }
                .map { it.replace(Regex("^\\d+[.)]\\s+"), "") }
    }

    /**
     * Железные правила блока, вынутые из страницы Notion. Ноль значит «в
     * тексте не нашлось» — тогда светофор про этот показатель молчит, а не
     * выдумывает порог.
     */
    data class Rules(
        val blockTitle: String = "",
        val runHrCeiling: Int = 0,          // потолок лёгкого бега
        val greyZoneLow: Int = 0,           // серая зона: не легко и не быстро
        val greyZoneHigh: Int = 0,
        val cadenceMin: Int = 0,
        val runsPerWeekMax: Int = 0,
        val hoursBetweenRuns: Int = 0,
        val rampNeedsPositiveTsb: Boolean = false,
        val cancelOrder: String = "",       // что выпадает первым, когда день ломается
        val kneeGreen: String = "",
        val kneeYellow: String = "",
        val kneeRed: String = "",
        val weekPlan: List<Pair<String, String>> = emptyList(),  // день недели -> сессия
        val extra: List<String> = emptyList(),                   // прочие правила строками
        val sourceText: String = "",        // страница блока целиком (для промпта)
        val fetchedAt: Long = 0L,
        val pageId: String = "",
    ) {
        val known: Boolean get() = fetchedAt > 0L
    }

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private val _daysFlow = MutableStateFlow<List<PlanDay>>(emptyList())
    val daysFlow: StateFlow<List<PlanDay>> = _daysFlow

    private val _rulesFlow = MutableStateFlow(Rules())
    val rulesFlow: StateFlow<Rules> = _rulesFlow

    @Volatile private var eventsAt = 0L
    fun eventsFetchedAt(): Long = eventsAt

    suspend fun load() = mutex.withLock { ensureLoaded() }

    /**
     * Свежий календарь. Пустой список НЕ принимается: на даче без интернета
     * вкладка должна показать вчерашний план, а не «ничего не запланировано».
     * Приехавшее сливается по id события, поэтому короткое окно не съедает
     * длинное.
     */
    suspend fun mergeDays(days: List<PlanDay>?) = mutex.withLock {
        ensureLoaded()
        if (days.isNullOrEmpty()) return@withLock
        val byId = LinkedHashMap<String, PlanDay>()
        for (d in _daysFlow.value) byId[d.eventId] = d
        for (d in days) byId[d.eventId] = d
        // Держим месяц назад и месяц вперёд: прошлое нужно для «план против
        // факта», будущее — чтобы видеть, что готовится.
        val from = dayKey(System.currentTimeMillis() - 40 * 86_400_000L)
        val to = dayKey(System.currentTimeMillis() + 40 * 86_400_000L)
        _daysFlow.value = byId.values.filter { it.date in from..to }.sortedBy { it.date }
        eventsAt = System.currentTimeMillis()
        persist()
    }

    suspend fun setRules(rules: Rules) = mutex.withLock {
        ensureLoaded()
        if (!rules.known) return@withLock
        _rulesFlow.value = rules
        persist()
    }

    // ---- Выборки ----

    fun dayOf(date: String): List<PlanDay> = _daysFlow.value.filter { it.date == date }

    /** Главная сессия дня: силовая важнее прокрутки — она «не двигается никогда». */
    fun mainOf(date: String): PlanDay? {
        val list = dayOf(date)
        return list.firstOrNull { it.strength }
            ?: list.maxByOrNull { it.load }
            ?: list.firstOrNull()
    }

    fun upcoming(days: Int): List<PlanDay> {
        val today = dayKey(System.currentTimeMillis())
        val to = dayKey(System.currentTimeMillis() + days * 86_400_000L)
        return _daysFlow.value.filter { it.date in today..to }.sortedBy { it.date }
    }

    /** Сессия по названию: «когда последний раз была Силовая B». */
    fun lastMatching(match: (PlanDay) -> Boolean): PlanDay? {
        val today = dayKey(System.currentTimeMillis())
        return _daysFlow.value.filter { it.date <= today && match(it) }.maxByOrNull { it.date }
    }

    // ---- Диск ----

    private suspend fun ensureLoaded() {
        if (loaded) return
        val parsed = withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { text -> parse(JSONObject(text)) }
        }
        loaded = true
        if (parsed != null) {
            _daysFlow.value = parsed.first
            _rulesFlow.value = parsed.second
            eventsAt = parsed.third
        }
    }

    private fun persist() {
        val json = serialize().toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun serialize(): JSONObject = JSONObject().apply {
        put("eventsAt", eventsAt)
        put(
            "days",
            JSONArray().apply {
                for (d in _daysFlow.value) put(
                    JSONObject().apply {
                        put("id", d.eventId)
                        put("date", d.date)
                        put("name", d.name)
                        put("type", d.type)
                        put("minutes", d.minutes)
                        put("load", d.load)
                        put("desc", d.description)
                        put("carbs", d.carbsPerHour)
                    }
                )
            }
        )
        val r = _rulesFlow.value
        put(
            "rules",
            JSONObject().apply {
                put("title", r.blockTitle)
                put("hrCeiling", r.runHrCeiling)
                put("greyLow", r.greyZoneLow)
                put("greyHigh", r.greyZoneHigh)
                put("cadence", r.cadenceMin)
                put("runsMax", r.runsPerWeekMax)
                put("hoursBetween", r.hoursBetweenRuns)
                put("rampTsb", r.rampNeedsPositiveTsb)
                put("cancel", r.cancelOrder)
                put("kneeGreen", r.kneeGreen)
                put("kneeYellow", r.kneeYellow)
                put("kneeRed", r.kneeRed)
                put("text", r.sourceText)
                put("fetchedAt", r.fetchedAt)
                put("pageId", r.pageId)
                put(
                    "week",
                    JSONArray().apply {
                        for ((day, session) in r.weekPlan) put(
                            JSONObject().apply {
                                put("d", day)
                                put("s", session)
                            }
                        )
                    }
                )
                put("extra", JSONArray().apply { r.extra.forEach { put(it) } })
            }
        )
    }

    private fun parse(o: JSONObject): Triple<List<PlanDay>, Rules, Long> {
        val days = mutableListOf<PlanDay>()
        o.optJSONArray("days")?.let { a ->
            for (i in 0 until a.length()) {
                val d = a.optJSONObject(i) ?: continue
                days.add(
                    PlanDay(
                        eventId = d.optString("id"),
                        date = d.optString("date"),
                        name = d.optString("name"),
                        type = d.optString("type"),
                        minutes = d.optInt("minutes"),
                        load = d.optInt("load"),
                        description = d.optString("desc"),
                        carbsPerHour = d.optInt("carbs"),
                    )
                )
            }
        }
        val r = o.optJSONObject("rules")
        val week = mutableListOf<Pair<String, String>>()
        r?.optJSONArray("week")?.let { a ->
            for (i in 0 until a.length()) {
                val w = a.optJSONObject(i) ?: continue
                week.add(w.optString("d") to w.optString("s"))
            }
        }
        val extra = mutableListOf<String>()
        r?.optJSONArray("extra")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { extra.add(it) }
        }
        val rules = if (r == null) Rules() else Rules(
            blockTitle = r.optString("title"),
            runHrCeiling = r.optInt("hrCeiling"),
            greyZoneLow = r.optInt("greyLow"),
            greyZoneHigh = r.optInt("greyHigh"),
            cadenceMin = r.optInt("cadence"),
            runsPerWeekMax = r.optInt("runsMax"),
            hoursBetweenRuns = r.optInt("hoursBetween"),
            rampNeedsPositiveTsb = r.optBoolean("rampTsb", false),
            cancelOrder = r.optString("cancel"),
            kneeGreen = r.optString("kneeGreen"),
            kneeYellow = r.optString("kneeYellow"),
            kneeRed = r.optString("kneeRed"),
            weekPlan = week,
            extra = extra,
            sourceText = r.optString("text"),
            fetchedAt = r.optLong("fetchedAt"),
            pageId = r.optString("pageId"),
        )
        return Triple(days.sortedBy { it.date }, rules, o.optLong("eventsAt"))
    }
}
