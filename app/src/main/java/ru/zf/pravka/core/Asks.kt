package ru.zf.pravka.core

import org.json.JSONObject
import ru.zf.pravka.core.Dela.str
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * «Подскажи» двумя заходами (15.10.2026, шаг 2 петли этапа 4, docs/dela-phone-23.md; сервер — `asks.py`, веб —
 * `asksQueue` в `static/app.js`, контракт — `server/contract/dela-asks.json`). Саша: «два захода одной очереди
 * „Подскажи“ на „Сегодня“: 15:04 — только сгорающее; 19:00 — остальное, по клиентам и людям» (вечерний заход тем же
 * вечером перенесён на 18:00 — `asks.rhythm.evening`, общий с «Сейчас на завтра» шага 1).
 *
 * Вопросы те же (ждущие предложения синка) — меняется только КОГДА блок «Подскажи» на «Сегодня» раскрыт и КАК
 * сгруппирован: до 15:00 — строка «Подскажи · к 15:00: N · к вечеру: M», с 15:00 — только сгорающее, с вечернего захода
 * до 05:00 — всё группами по клиентам и людям, с пятницы 15:00 до понедельника — «до понедельника тихо». Ничего не
 * прячется насовсем: тап по строке раскрывает всё до смены фазы, вкладка Дел «Новое» показывает всё всегда.
 *
 * Срочность (`payload.urgency`, `urgent_on`) и предмет (`payload.about`) ставит СЕРВЕР при записи вопроса — телефон их
 * только читает и ничего нового не шлёт. Фаза — по часам МОСКВЫ (не по поясу телефона: у сервера, веба и дайджеста
 * заходы московские), Свод `asks.rhythm` и `day.quiet` поверх заводского по полю. Вид `asks` сервера телефону не нужен:
 * всё считается из копии синка, как в вебе, — так блок живёт и без сети.
 *
 * Правило — точная копия `asks.py`; таблицы `phases.examples` и `queue.examples` контракта проверяет `AsksTest`.
 * Без флага [Dela.FEATURE_ASKS] у сервера — [Queue.on] = false: «Подскажи» как раньше (все ждущие, свежие сверху).
 */
object Asks {

    /** Часы очереди — московские, как у сервера (`meetings.MSK`), а не зона телефона. */
    val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    /** Фаза очереди: `key` — слово контракта и сервера. */
    enum class Phase(val key: String) { BEFORE("before"), TODAY("today"), EVENING("evening"), QUIET("quiet") }

    /**
     * Ритм — Свод `asks.rhythm`. Поля шага 1 (заход «Сейчас на завтра»): [evening] Пн–Чт, [fri], [friWinter] — пятница в
     * зимнем окне [winter] (ММ-ДД с какого по какое, через Новый год). Поля шага 2: [today] — заход «сгорающего» (вместе
     * со слотом сводки дайджеста 15:00), [nightEnd] — до скольки ночью длится вечер (Саша отвечает и в 23–00 ч),
     * [todayMax] и [eveningMax] — сколько вопросов раскрыто в заход.
     */
    data class Rhythm(
        val evening: String = "18:00",
        val fri: String = "14:00",
        val friWinter: String = "13:30",
        val winter: Pair<String, String> = "11-15" to "01-15",
        val today: String = "15:00",
        val nightEnd: String = "05:00",
        val todayMax: Int = 5,
        val eveningMax: Int = 7,
    )

    /** Заводской ритм — `svod.asks.rhythm.factory` контракта (сверяет `AsksTest`). */
    val FACTORY = Rhythm()

    /** Тишина выходных: с дня недели [dow] (ISO, 5 — пятница) с [from] до понедельника `night_end`. */
    data class Weekend(val dow: Int, val from: String)

    /**
     * Свод `day.quiet` — ключ общий с Засечкой: очереди нужна только часть `weekend` (`null` — тишины выходных нет;
     * тогда в Сб–Вс заходов всё равно нет). Дневные окна (`daily`, шаг 4) читает Засечка, не очередь.
     */
    data class Quiet(val weekend: Weekend?)

    val QUIET_FACTORY = Quiet(Weekend(5, "15:00"))

    const val KIND_CLIENT = "client"
    const val KIND_PERSON = "person"
    const val KIND_MEETING = "meeting"
    const val KIND_SMALL = "small"
    const val KIND_PLAN = "plan"

    /** Предмет вопроса — порядок групп на экране вечером (`asks.KINDS`). */
    val KINDS = listOf(KIND_CLIENT, KIND_PERSON, KIND_MEETING, KIND_SMALL)

    /** Подпись группы мелочи (`asks.SMALL`). */
    const val SMALL = "Мелочь"

    /** Название вопроса без штампа и без пачки (`asks.ELSE_TITLE`). */
    const val ELSE = "Прочее"

    private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val HM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val MD = Regex("^(\\d{2})-(\\d{2})$")

    // ------------------------------------------------------------ Свод поверх заводского

    /** «ЧЧ:ММ» → «ЧЧ:ММ» с ведущим нулём; не время — null (`asks._hm`). */
    private fun hm(v: Any?): String? {
        val m = HM.matchEntire(text(v)) ?: return null
        return "%02d:%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** «ММ-ДД» — настоящий день (високосный год: «02-29» тоже день) → (месяц, день); иначе null (`asks._md`). */
    private fun md(v: Any?): Pair<Int, Int>? {
        val m = MD.matchEntire(text(v)) ?: return null
        val mo = m.groupValues[1].toInt()
        val d = m.groupValues[2].toInt()
        return runCatching { LocalDate.of(2028, mo, d) }.getOrNull()?.let { mo to d }
    }

    /** Строка значения JSON так, как её видит сервер (`str(v or "").strip()`): null и JSON null — пусто. */
    private fun text(v: Any?): String = if (v == null || v == JSONObject.NULL) "" else v.toString().trim()

    /** Целое (не дробное, не да/нет) в пределах — как `isinstance(v, int)` сервера; иначе null. */
    private fun whole(v: Any?, range: IntRange): Int? =
        (v as? Int ?: (v as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())?.takeIf { it in range }

    /**
     * Ритм владельца: Свод `asks.rhythm` поверх заводского по полю (`asks.norm`). Записи нет, не словарь или поле
     * кривое — заводское по полю, молча (сервер пишет строку журнала, телефону шуметь незачем). Чужие поля (их может
     * дописать следующий шаг) не мешают.
     */
    fun rhythm(raw: Any? = Svod.value(Svod.ASKS_RHYTHM)): Rhythm {
        val o = raw as? JSONObject ?: return FACTORY
        val f = FACTORY
        val w = o.optJSONArray("winter")?.takeIf { it.length() == 2 && md(it.opt(0)) != null && md(it.opt(1)) != null }
        return Rhythm(
            evening = hm(o.opt("evening")) ?: f.evening,
            fri = hm(o.opt("fri")) ?: f.fri,
            friWinter = hm(o.opt("fri_winter")) ?: f.friWinter,
            winter = w?.let { text(it.opt(0)) to text(it.opt(1)) } ?: f.winter,
            today = hm(o.opt("today")) ?: f.today,
            nightEnd = hm(o.opt("night_end")) ?: f.nightEnd,
            todayMax = whole(o.opt("today_max"), 1..50) ?: f.todayMax,
            eveningMax = whole(o.opt("evening_max"), 1..50) ?: f.eveningMax,
        )
    }

    /**
     * Тишина владельца: Свод `day.quiet` поверх заводского (`asks.norm_quiet`) — только часть `weekend`: `null` или
     * `false` — тишины нет; словарь — по полю (`dow` 1…7, `from` «ЧЧ:ММ»), кривое поле — заводское; не словарь —
     * заводское целиком.
     */
    fun quiet(raw: Any? = Svod.value(Svod.DAY_QUIET)): Quiet {
        val o = raw as? JSONObject ?: return QUIET_FACTORY
        if (!o.has("weekend")) return QUIET_FACTORY
        val v = o.opt("weekend")
        if (v == null || v == JSONObject.NULL || v == false) return Quiet(null)
        val w = v as? JSONObject ?: return QUIET_FACTORY
        val f = QUIET_FACTORY.weekend!!
        return Quiet(Weekend(dow = whole(w.opt("dow"), 1..7) ?: f.dow, from = hm(w.opt("from")) ?: f.from))
    }

    // ------------------------------------------------------------ фазы

    /** Минуты от полуночи нормального «ЧЧ:ММ». */
    private fun minutes(hm: String): Int = hm.substringBefore(':').toInt() * 60 + hm.substringAfter(':').toInt()

    /** Пятница в зимнем окне (с завода 15.11–15.01): свечи в Москве раньше 16:00 — заход на полчаса раньше. */
    fun winter(rh: Rhythm, day: LocalDate): Boolean {
        val lo = md(rh.winter.first) ?: md(FACTORY.winter.first)!!
        val hi = md(rh.winter.second) ?: md(FACTORY.winter.second)!!
        val d = day.monthValue to day.dayOfMonth
        fun le(a: Pair<Int, Int>, b: Pair<Int, Int>) = a.first < b.first || (a.first == b.first && a.second <= b.second)
        return if (le(lo, hi)) le(lo, d) && le(d, hi) else le(lo, d) || le(d, hi)
    }

    /**
     * Вечерний заход дня — минуты по Москве (`asks.evening_at` шага 1): Пн–Чт — `evening`, Пт — `fri` или в зимнем окне
     * `fri_winter`, Сб–Вс — null (заходов нет). По нему же сервер ставит кандидатов «Сейчас на завтра».
     */
    fun eveningMin(rh: Rhythm, day: LocalDate): Int? {
        val wd = day.dayOfWeek.value - 1 // 0 — пн
        if (wd > 4) return null
        return minutes(if (wd < 4) rh.evening else if (winter(rh, day)) rh.friWinter else rh.fri)
    }

    /** Тишина выходных: с `weekend.dow` `weekend.from` (Пт 15:00) до понедельника `night_end` (`asks.in_quiet`). */
    private fun inQuiet(t: ZonedDateTime, rh: Rhythm, q: Quiet): Boolean {
        val w = q.weekend ?: return false
        val pos = (t.dayOfWeek.value - 1) * 1440 + t.hour * 60 + t.minute
        return pos >= (w.dow - 1) * 1440 + minutes(w.from) || pos < minutes(rh.nightEnd)
    }

    private fun phaseAt(t: ZonedDateTime, rh: Rhythm, q: Quiet): Phase {
        if (inQuiet(t, rh, q)) return Phase.QUIET
        val day = t.toLocalDate()
        val min = t.hour * 60 + t.minute
        // Ночь — хвост вчерашнего вечера (Саша отвечает и в 23–00 ч).
        if (min < minutes(rh.nightEnd)) return if (eveningMin(rh, day.minusDays(1)) != null) Phase.EVENING else Phase.QUIET
        val ev = eveningMin(rh, day) ?: return Phase.QUIET
        if (min >= ev) return Phase.EVENING
        return if (min >= minutes(rh.today)) Phase.TODAY else Phase.BEFORE
    }

    /**
     * Фаза очереди в миг [nowMs] (`asks.phase`): тишина выходных → ночь до `night_end` (хвост вчерашнего вечера, если
     * вчера был заход) → день без захода — тихо → с вечернего захода — вечер → с `today` — «сгорающее» → иначе «до».
     * Пятница: «до» до `fri`, вечер с `fri` до тишины — «сгорающего» у неё нет, раз `fri` раньше `today`.
     */
    fun phase(nowMs: Long, rh: Rhythm = rhythm(), q: Quiet = quiet()): Phase = phaseAt(at(nowMs), rh, q)

    /** Ближайший заход: миг, какая фаза начнётся и словами — «15:00» сегодня, иначе «пн 15:00» (`asks.when_word`). */
    data class Next(val atMs: Long, val phase: Phase, val label: String)

    /** Ближайший заход после [nowMs] — начало фазы «сгорающего» или вечера не в тишине (`asks.next_start`). */
    fun next(nowMs: Long, rh: Rhythm = rhythm(), q: Quiet = quiet()): Next? {
        val t = at(nowMs)
        for (k in 0L..8L) {
            val day = t.toLocalDate().plusDays(k)
            val ev = eveningMin(rh, day) ?: continue
            val td = minutes(rh.today)
            for (m in if (td < ev) listOf(td, ev) else listOf(ev)) {
                val s = day.atTime(m / 60, m % 60).atZone(MSK)
                val ms = s.toInstant().toEpochMilli()
                if (ms <= nowMs) continue
                val p = phaseAt(s, rh, q)
                if (p == Phase.TODAY || p == Phase.EVENING) {
                    val hm = "%02d:%02d".format(s.hour, s.minute)
                    return Next(ms, p, if (k == 0L) hm else WEEKDAYS[day.dayOfWeek.value - 1] + " " + hm)
                }
            }
        }
        return null
    }

    private fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(MSK)

    /** День по Москве («ГГГГ-ММ-ДД») — `urgent_on` сравнивается с ним, а не с днём по поясу телефона. */
    fun day(nowMs: Long): String = at(nowMs).toLocalDate().toString()

    // ------------------------------------------------------------ срочность и предмет

    /** Предмет вопроса: `kind` — [KINDS] или [KIND_PLAN], `id` — карточка (проект, человек, встреча), если есть. */
    data class About(val kind: String, val id: String?, val title: String)

    /**
     * Штамп сервера из `payload` и миг вопроса — разобраны один раз на вопрос: «Сегодня» пересобирается каждую минуту,
     * а разбор JSON на каждой перерисовке — лишняя работа главного потока. Ключ — `id`; запись годна, пока у вопроса те
     * же `rev`, `payload` и `created_at` (строки сравниваются сначала по ссылке — у той же копии синка это один объект).
     */
    private class Stamp(
        val rev: Int,
        val payload: String,
        val createdAt: String,
        val urgency: String,
        val urgentOn: String,
        val about: About?,
        val ts: Long,
    )

    private val stamps = object : LinkedHashMap<String, Stamp>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stamp>?): Boolean = size > 512
    }

    private fun stamp(sg: Dela.Suggestion): Stamp = synchronized(stamps) {
        stamps[sg.id]?.takeIf { it.rev == sg.rev && it.payload == sg.payload && it.createdAt == sg.createdAt }
            ?: parse(sg).also { stamps[sg.id] = it }
    }

    private fun parse(sg: Dela.Suggestion): Stamp {
        val p = sg.payloadObj()
        val a = p.optJSONObject("about")
        val about = a?.str("kind")?.takeIf { it in KINDS }?.let { k -> About(k, a.str("id").ifBlank { null }, a.str("title")) }
        return Stamp(sg.rev, sg.payload, sg.createdAt, p.str("urgency"), p.str("urgent_on"), about, seconds(sg.createdAt))
    }

    /**
     * Миг вопроса с точностью до секунды (`asks._ts`) — так `created_at` отдаёт синк: порядок «кто дольше ждёт» у
     * сервера, веба и телефона один; время без пояса — московское (так пишет сервер); не время — 0.
     */
    private fun seconds(s: String): Long {
        val v = s.trim().ifBlank { return 0L }
        return runCatching { OffsetDateTime.parse(v).toEpochSecond() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(v).atZone(MSK).toEpochSecond() }.getOrNull()
            ?: 0L
    }

    /** Сгорает в этот день (`asks.is_urgent`): `urgency` «today» и `urgent_on` — этот день. Завтра тот же вопрос — вечерний. */
    fun urgent(sg: Dela.Suggestion, today: String): Boolean = stamp(sg).let { it.urgency == "today" && it.urgentOn == today }

    /**
     * Предмет вопроса (`asks.about_of`): кандидат «Сейчас на завтра» — [KIND_PLAN]; штамп сервера — как есть; вопрос без
     * штампа (записан до выката шага 2) — мелочь с названием пачки.
     */
    fun about(sg: Dela.Suggestion): About {
        if (sg.source == Dela.PLAN_SOURCE) return About(KIND_PLAN, null, sg.batchTitle)
        return stamp(sg).about ?: About(KIND_SMALL, null, sg.batchTitle.ifBlank { ELSE })
    }

    // ------------------------------------------------------------ очередь

    /** Группа вопросов одного предмета: [items] — сгорающее, потом старые сверху; [urgent] — сколько из них сгорает. */
    data class Group(val kind: String, val id: String?, val title: String, val items: List<Dela.Suggestion>, val urgent: Int) {
        val n: Int get() = items.size
    }

    /** Очередь «Подскажи» на миг — что рисует «Сегодня». */
    data class Queue(
        /** Сервер знает штамп (флаг `asks`); нет — «Подскажи» как раньше: [all] свежими сверху. */
        val on: Boolean,
        val phase: Phase,
        val next: Next?,
        /** День по Москве. */
        val day: String,
        /** Группы в порядке экрана вечером: со сгорающим — первыми, дальше клиенты, люди, встречи, мелочь. */
        val groups: List<Group>,
        /** Сгорающее в порядке групп — так раскрыто с 15:00. */
        val urgent: List<Dela.Suggestion>,
        /** Ждущих вопросов без кандидатов плана. */
        val total: Int,
        /** Не сгорающее — «к вечеру». */
        val rest: Int,
        /** Ждущих кандидатов «Сейчас на завтра» — только числом (их блок — строка плана шага 1). */
        val plan: Int,
        val todayMax: Int,
        val eveningMax: Int,
        /** Без флага — прежний список «Подскажи» (свежие сверху); с флагом — все вопросы в порядке групп. */
        val all: List<Dela.Suggestion>,
    ) {
        /**
         * Ключ раскрытия тапом — «до смены фазы»: фаза и её ближайший заход (у «до» сегодня — 15:00, у тишины — пн 15:00),
         * чтобы раскрытое вчера утром не оказалось раскрытым сегодня утром (выбор переживает пересоздание экрана — складывание,
         * выгрузку приложения системой).
         */
        val openKey: String get() = phase.key + "@" + (next?.atMs ?: 0L)
    }

    /**
     * Ждущие вопросы → группы (`asks.queue`; [title] — имя группы по предмету, у телефона — свежее из копии). Группа —
     * предмет: клиент и человек — по id (без id — по названию), встреча — по id или названию, мелочь — одна. Внутри вида
     * — у кого самый старый вопрос (дольше ждёт); «старше» — по `created_at` до секунды, равные — по id.
     */
    fun groups(
        sugs: Collection<Dela.Suggestion>,
        day: String,
        title: (About, Dela.Suggestion) -> String = { a, sg -> if (a.kind == KIND_MEETING) sg.batchTitle.ifBlank { a.title } else a.title },
    ): Triple<List<Group>, List<Dela.Suggestion>, Int> {
        class G(val kind: String, val id: String?, val title: String, val old: Long) {
            val hot = ArrayList<Dela.Suggestion>()
            val cold = ArrayList<Dela.Suggestion>()
        }
        var plan = 0
        val gs = LinkedHashMap<String, G>()
        val sorted = sugs.map { it to stamp(it).ts }.sortedWith(compareBy({ it.second }, { it.first.id })).map { it.first }
        for (sg in sorted) {
            val a = about(sg)
            if (a.kind == KIND_PLAN) {
                plan++
                continue
            }
            val small = a.kind == KIND_SMALL
            val key = if (small) KIND_SMALL else a.kind + "\u0000" + (a.id ?: a.title)
            val g = gs.getOrPut(key) { G(a.kind, if (small) null else a.id, if (small) SMALL else title(a, sg), stamp(sg).ts) }
            (if (urgent(sg, day)) g.hot else g.cold) += sg
        }
        val order = gs.values.sortedWith(compareBy({ it.hot.isEmpty() }, { KINDS.indexOf(it.kind) }, { it.old }))
        val out = order.map { Group(it.kind, it.id, it.title, it.hot + it.cold, it.hot.size) }
        return Triple(out, order.flatMap { it.hot }, plan)
    }

    /**
     * Очередь «Подскажи» человека [me] на миг [nowMs] — как `asksQueue` веба: фаза, ближайший заход, группы, сгорающее,
     * число кандидатов плана. Имя группы клиента и человека — свежее по id из копии (переименовали после вопроса), имя
     * штампа — запас. Без флага `asks` у сервера — прежний список «Подскажи» (`DelaViews.asks`).
     */
    fun queue(s: Dela.Snapshot, me: String, nowMs: Long, rh: Rhythm = rhythm(), q: Quiet = quiet()): Queue {
        val day = day(nowMs)
        val ph = phase(nowMs, rh, q)
        val nx = next(nowMs, rh, q)
        if (!s.asksOn) {
            val list = DelaViews.asks(s, me)
            return Queue(false, ph, nx, day, emptyList(), emptyList(), list.size, list.size, 0, rh.todayMax, rh.eveningMax, list)
        }
        val mine = s.suggestions.values.filter { it.pending && (me.isBlank() || it.forUser == me) }
        val (gs, hot, plan) = groups(mine, day) { a, sg -> fresh(s, a, sg) }
        val total = gs.sumOf { it.n }
        return Queue(true, ph, nx, day, gs, hot, total, total - hot.size, plan, rh.todayMax, rh.eveningMax, gs.flatMap { it.items })
    }

    /**
     * Имя группы (`asksTitle` веба, `asks._title` сервера): клиент и человек — свежее из копии; встреча — текущее название
     * пачки вопроса (сервер переименовывает её вслед за встречей); нет — имя штампа, пустое — «Встреча» / «Прочее».
     */
    private fun fresh(s: Dela.Snapshot, a: About, sg: Dela.Suggestion): String {
        val id = a.id
        val got = when {
            a.kind == KIND_MEETING -> sg.batchTitle
            id == null -> null
            a.kind == KIND_CLIENT -> s.projects[id]?.name
            a.kind == KIND_PERSON -> s.person(id)?.label
            else -> null
        }
        return got?.takeIf { it.isNotBlank() } ?: a.title.ifBlank { if (a.kind == KIND_MEETING) "Встреча" else ELSE }
    }

    /**
     * Свёрнутая строка очереди (`asksFoldText` веба): «Подскажи · к 15:00: 1 · к вечеру: 6»; в пятницу до захода —
     * «Подскажи · к 14:00: 7» (ближайший заход — вечерний, сразу всё); после захода «сгорающего» — «Подскажи · к вечеру:
     * M»; в тишине — «Подскажи: N — до понедельника тихо». Нулевые части не пишутся.
     */
    fun foldText(q: Queue): String {
        val nx = q.next
        if (q.phase == Phase.QUIET) {
            val till = if (nx != null && !nx.label.startsWith("пн ")) "до " + nx.label else "до понедельника"
            return "Подскажи: ${q.total} — $till тихо"
        }
        val parts = if (q.phase == Phase.BEFORE && nx?.phase == Phase.EVENING) {
            listOf("к ${nx.label}: ${q.total}")
        } else {
            listOfNotNull(
                "к ${nx?.label}: ${q.urgent.size}".takeIf { q.phase == Phase.BEFORE && nx != null && q.urgent.isNotEmpty() },
                "к вечеру: ${q.rest}".takeIf { q.rest > 0 },
            )
        }
        return (listOf("Подскажи") + parts).joinToString(" · ")
    }

    // ------------------------------------------------------------ карточка на «Сегодня»

    /** Как разложена карточка «Подскажи»: по-старому, одной строкой, только сгорающее, всё группами. */
    enum class Mode { LEGACY, FOLD, URGENT, GROUPED }

    /**
     * Что рисует карточка на «Сегодня» — без Compose, чтобы раскладку по фазам проверяли JVM-тесты.
     * [rows] — строки вопросов (по-старому и сгорающее), [groups] — группы с показанными строками (вечер или раскрыто),
     * [more] — сколько не поместилось (дальше — «в «Новом»»), [fold] — строка-раскрывашка.
     */
    data class Card(
        val mode: Mode,
        val count: Int,
        val sub: String?,
        val fold: String?,
        val rows: List<Dela.Suggestion> = emptyList(),
        val groups: List<Pair<Group, List<Dela.Suggestion>>> = emptyList(),
        val more: Int = 0,
    ) {
        /** Вопросы на экране в порядке показа — по ним П-номера (их же видит Claude: «П1 поставь»); свёрнуто — пусто. */
        val shown: List<Dela.Suggestion> get() = rows + groups.flatMap { it.second }
    }

    /**
     * Карточка «Подскажи» по фазе ([opened] — [Queue.openKey], в котором раскрыли тапом; сменилась фаза — снова
     * свёрнуто): вечер или раскрыто — всё группами до `evening_max`; с 15:00 — только сгорающее до `today_max` и строка
     * «к вечеру: M»; иначе — одна строка [foldText]. Без флага — прежние [legacyMax] строк. Вопросов нет — null.
     */
    fun card(q: Queue, opened: String?, legacyMax: Int): Card? {
        if (!q.on) {
            if (q.all.isEmpty()) return null
            return Card(Mode.LEGACY, q.all.size, "ждёт твоего да", null, rows = q.all.take(legacyMax), more = (q.all.size - legacyMax).coerceAtLeast(0))
        }
        if (q.total == 0) return null
        if (q.phase == Phase.EVENING || opened == q.openKey) {
            var left = q.eveningMax
            val shown = ArrayList<Pair<Group, List<Dela.Suggestion>>>()
            for (g in q.groups) {
                if (left <= 0) break
                val items = g.items.take(left)
                left -= items.size
                shown += g to items
            }
            val sub = if (q.phase == Phase.EVENING) "по клиентам и людям" else "раскрыто до " + (q.next?.label ?: "захода")
            return Card(Mode.GROUPED, q.total, sub, null, groups = shown, more = q.total - shown.sumOf { it.second.size })
        }
        if (q.phase == Phase.TODAY && q.urgent.isNotEmpty()) {
            val rows = q.urgent.take(q.todayMax)
            return Card(Mode.URGENT, q.total, "сгорает сегодня", "к вечеру: ${q.rest}".takeIf { q.rest > 0 }, rows = rows, more = q.urgent.size - rows.size)
        }
        return Card(Mode.FOLD, q.total, null, foldText(q))
    }

    /**
     * Блок «Подскажи» главной «Сейчас» вкладки Дел (`newBox` веба; правило 15 — вкладка устроена как веб): та же
     * раскладка по фазе, что на «Сегодня», а без флага — все ждущие вопросы, как раньше (предела строк у вкладки нет).
     */
    fun homeCard(q: Queue, opened: String?): Card? = card(q, opened, Int.MAX_VALUE)

    /** Подзаголовок группы: «ООО Ромашка · 2», сгорающее — отдельной частью (её карточка красит). */
    fun groupHead(g: Group): String = "${g.title} · ${g.n}"

    fun groupUrgent(g: Group): String? = if (g.urgent > 0) "сгорает ${g.urgent}" else null
}
