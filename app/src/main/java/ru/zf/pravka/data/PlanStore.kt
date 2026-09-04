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
        /** «07:30» из start_date_local; пусто — событие без времени. */
        val time: String = "",
        /** Теги события в intervals («v3», «зарядка», «гиря», «турник»). */
        val tags: List<String> = emptyList(),
    ) {
        val strength: Boolean get() = type.equals("WeightTraining", ignoreCase = true)

        /**
         * Зарядка, стоящая в календаре своим событием. Она тоже WeightTraining
         * (так владелец её пушит), но главной сессией дня быть не может: днём
         * правят Zwift, бег и силовые, а зарядка — ежедневный фон со своей
         * карточкой.
         *
         * Узнаётся по тегу «зарядка» или по слову в названии. Раньше сюда же
         * попадало всё со словом «турник» — и «Турник + пресс №1» плана v3
         * считался зарядкой: пропадал из карточки дня, из плана недели и из
         * чек-листа, а владелец видел половину своих сессий. Турник — это
         * силовая со своим блоком в справочнике, а не утренний фон.
         */
        val charger: Boolean
            get() = strength && (
                tags.any { it.trim().equals("зарядка", ignoreCase = true) } ||
                    CHARGER_NAME.containsMatchIn(name.lowercase())
                )

        /**
         * Короткое имя сессии для подписей: «Гиря №0 — знакомство: гоблет…» →
         * «Гиря №0», «Зарядка · 6 пунктов» → «Зарядка».
         */
        val shortName: String
            get() = name.split(" — ", ": ", " · ").first().trim().take(40).ifBlank { name.take(40) }

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
         *
         * Список бывает и В ОДНУ СТРОКУ — «Дача. 1. Суставы. 2. Осанка: …
         * 5. Скручивания ×15 с паузой. Потом прогулка.» — так владелец пишет
         * дачные зарядки. Раньше такая строка не считалась списком вовсе, и
         * вместо его пяти пунктов вкладка показывала пятнадцать из старого
         * справочника. Пункты в одной строке принимаются, когда их номера идут
         * подряд: «сравни с пятницей 4.09» списком не становится.
         */
        fun plannedLines(): List<String> = parsed.items.map { it.second }

        /** Текст описания ДО списка: комментарий владельца к сессии. */
        fun noteBefore(): String = parsed.before

        /**
         * Текст ПОСЛЕ списка: «Минимум на плохое утро: 1 + 3 + шесть
         * отжиманий», «Отдых минута. Задача дня — не устать…». Раньше он не
         * показывался вообще — а это ровно то, что стоит прочитать перед
         * началом. Структура для Garmin (Warmup, intensity=) сюда не попадает.
         */
        fun noteAfter(): String = parsed.after

        private class Parsed(val before: String, val items: List<Pair<Int, String>>, val after: String)

        private val parsed: Parsed by lazy { parseDescription() }

        private fun parseDescription(): Parsed {
            val before = StringBuilder()
            val after = StringBuilder()
            val items = mutableListOf<Pair<Int, String>>()
            fun noteTo(text: String) {
                if (text.isBlank()) return
                (if (items.isEmpty()) before else after).append(text.trim()).append('\n')
            }
            for (raw in description.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || isStructure(line)) continue
                val ms = ITEM.findAll(line).toList()
                val nums = ms.map { it.groupValues[1].toInt() }
                val startsLine = ms.isNotEmpty() && ms.first().range.first == 0
                val consecutive = ms.size >= 2 && nums.zipWithNext().all { (a, b) -> b == a + 1 }
                val expected = (items.lastOrNull()?.first ?: 0) + 1
                when {
                    consecutive -> {
                        // Список в одну строку. Текст до первого номера —
                        // заметка («Дача.»), хвост абзаца после последнего
                        // пункта — тоже заметка, не доза.
                        noteTo(line.substring(0, ms.first().range.first))
                        for ((i, m) in ms.withIndex()) {
                            val end = if (i + 1 < ms.size) ms[i + 1].range.first else line.length
                            var text = line.substring(m.range.last + 1, end).trim()
                            if (i == ms.size - 1) {
                                val cut = lastSentenceCut(text)
                                if (cut > 0) {
                                    items.add(nums[i] to tidy(text.substring(0, cut)))
                                    noteTo(text.substring(cut))
                                    text = ""
                                }
                            }
                            if (text.isNotEmpty()) items.add(nums[i] to tidy(text))
                        }
                    }
                    startsLine -> items.add(nums[0] to tidy(line.substring(ms[0].range.last + 1)))
                    // «Дача. 1. Суставы» и дальше пункты по строкам: единичный
                    // номер не в начале строки принимается, только если он
                    // продолжает счёт — иначе это дата или число в тексте.
                    ms.size == 1 && nums[0] == expected -> {
                        noteTo(line.substring(0, ms[0].range.first))
                        items.add(nums[0] to tidy(line.substring(ms[0].range.last + 1)))
                    }
                    else -> noteTo(line)
                }
            }
            return Parsed(before.toString().trim(), items, after.toString().trim())
        }

        /** Где обрезать хвост-заметку у последнего пункта строки-списка. */
        private fun lastSentenceCut(text: String): Int {
            val idx = text.lastIndexOf(". ")
            if (idx <= 0) return 0
            val rest = text.substring(idx + 2).trim()
            if (rest.isBlank() || DOSE_LIKE.containsMatchIn(rest)) return 0
            return idx + 1
        }

        private fun tidy(text: String): String {
            val t = text.trim().trimEnd(';', ',')
            return (if (t.contains(". ")) t else t.removeSuffix(".")).trim()
        }

        companion object {
            /** Слово «зарядка» в названии — с любого окончания, но целым словом. */
            private val CHARGER_NAME = Regex("(?:^|[^\\p{L}])зарядк")
            /** Номер пункта: «1. », «2) » — в начале строки или после пробела. */
            private val ITEM = Regex("(?:^|(?<=\\s))(\\d{1,2})[.)]\\s+(?=[\\p{L}\\d~×])")
            /** Машинная структура тренировки для Garmin — не текст плана. */
            private val STRUCTURE = Regex("^(?:Warmup|Main Set|Cooldown|Rest|Recovery)\\b|intensity=")
            private val DOSE_LIKE = Regex("[×x]\\s*\\d|\\d\\s*[×xх]\\s*\\d|\\d\\s*(?:сек|мин)(?![\\p{L}])")

            fun isStructure(line: String): Boolean = STRUCTURE.containsMatchIn(line)
        }
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
        val testPrep: String = "",          // что сделать перед тестом, его словами
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

    /**
     * Главная сессия дня: силовая важнее прокрутки — она «не двигается
     * никогда». Зарядка-события в главные не идут: с тех пор как владелец
     * ставит их в календарь каждый день, «первая WeightTraining дня» — это
     * почти всегда зарядка, и без этого фильтра карточка дня показывала бы её
     * вместо Zwift, а журнал силовой писался бы в блок «Зарядка».
     */
    fun mainOf(date: String): PlanDay? {
        val list = dayOf(date).filterNot { it.charger }
        return list.filter { it.strength }.maxByOrNull { it.minutes }
            ?: list.maxByOrNull { it.load }
            ?: list.firstOrNull()
            ?: dayOf(date).firstOrNull()
    }

    /** Зарядка-событие дня, если владелец поставил его в календарь. */
    fun chargerOf(date: String): PlanDay? =
        dayOf(date).filter { it.charger }.minByOrNull { it.time.ifBlank { "99:99" } }

    /**
     * ВСЕ силовые сессии дня кроме зарядки, главная первой, дальше по часам:
     * в плане v3 утро — это гиря, а сразу за ней турник и пресс, и у каждой
     * свой список упражнений. Карточка дня раньше показывала список только
     * главной, а вторая жила строкой «Ещё сегодня» без единой галочки.
     */
    fun strengthOf(date: String): List<PlanDay> {
        val main = mainOf(date)?.takeIf { it.strength && !it.charger }
        val rest = dayOf(date)
            .filter { it.strength && !it.charger && it.eventId != main?.eventId }
            .sortedBy { it.time.ifBlank { "99:99" } }
        return listOfNotNull(main) + rest
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
                        put("time", d.time)
                        put("tags", JSONArray().apply { d.tags.forEach { put(it) } })
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
                put("testPrep", r.testPrep)
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
                        time = d.optString("time"),
                        tags = d.optJSONArray("tags")?.let { a ->
                            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
                        }.orEmpty(),
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
            testPrep = r.optString("testPrep"),
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
