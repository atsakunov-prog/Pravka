package ru.zf.pravka.core

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONObject
import ru.zf.pravka.core.Dela.bool
import ru.zf.pravka.core.Dela.int
import ru.zf.pravka.core.Dela.intOrNull
import ru.zf.pravka.core.Dela.longOrNull
import ru.zf.pravka.core.Dela.str
import ru.zf.pravka.core.Dela.strings

/**
 * CRM внутри Дел на телефоне (docs/dela-phone-2.md, этап 2; контракт —
 * server/contract/dela-crm.json, `views_crm`). Клиент = проект `kind = client`,
 * внутри — сделки со стадиями, оплаты и хронология контактов.
 *
 * Почему CRM-виды — с сервера, а дела телефон считает сам: логика воронки
 * большая (застывшие, тишина, следующее дело, взвешенная воронка, доступ
 * команды по ответственности, деньги только кому открыты) и общая с вебом.
 * Вторая её копия на телефоне разошлась бы с первой в первую же неделю.
 * Телефон хранит последний ответ каждого вида на диске — экран открывается
 * без сети, — и накладывает поверх свою неотправленную правку (стадия,
 * оплата, запись хронологии), чтобы касание было видно сразу.
 */
object DelaCrm {

    val STAGE = mapOf(
        "lead" to "лид", "proposal" to "КП", "mandate" to "мандат", "active" to "в работе",
        "closing" to "закрытие", "archive" to "архив",
    )
    val OUTCOME = mapOf("won" to "выиграли", "lost" to "проиграли", "paused" to "заморожено")
    val PAY_KIND = mapOf(
        "advance" to "аванс", "stage" to "этап", "final" to "финал", "success" to "success fee",
        "retainer" to "ретейнер", "extra" to "допработы",
    )
    /** Виды записи хронологии — ровно те, что принимает сервер. */
    val IKIND = linkedMapOf(
        "call" to "звонок", "meeting" to "встреча", "zoom" to "Zoom", "telegram" to "Telegram",
        "email" to "почта", "whatsapp" to "WhatsApp", "note" to "заметка", "other" to "другое",
    )
    val CADENCE = linkedMapOf("month" to "раз в месяц", "quarter" to "раз в квартал", "year" to "раз в год", "none" to "не видимся")

    // ------------------------------------------------------------ строки видов

    /**
     * Следующее дело сделки или клиента — открытое дело с ближайшим сроком. [viaClient] (15.10.2026, аудит №2,
     * `deal_steps`): дело клиента без сделки, а сделка у клиента одна живая — её шаг; строка подписывает «дело клиента».
     */
    data class NextTask(
        val id: String,
        val num: Int,
        val title: String,
        val dueDate: String,
        val ownerId: String,
        val ball: String,
        val viaClient: Boolean = false,
    )

    /**
     * Сделка в виде — строка `v_deals` плюс посчитанное сервером. Деньги —
     * `null`, когда закрыты человеку (а не ноль): их не показываем совсем.
     */
    data class Deal(
        val id: String,
        val projectId: String,
        val projectName: String,
        val name: String,
        val stage: String,
        val outcome: String = "",
        val lostReason: String = "",
        val closedOn: String = "",
        val dealType: String = "",
        val leadPersonId: String = "",
        val teamIds: List<String> = emptyList(),
        val personIds: List<String> = emptyList(),
        val stageSince: String = "",
        val openTasks: Int = 0,
        val nextTask: NextTask? = null,
        val lastTouch: String = "",
        val quietDays: Int = 0,
        val stale: Boolean = false,
        val feeKop: Long? = null,
        val pEff: Int? = null,
        val paidKop: Long? = null,
        val invoicedKop: Long? = null,
        val plannedKop: Long? = null,
        val toGetKop: Long? = null,
        val weightedKop: Long? = null,
        val minutes30: Int? = null,
        val minutesAll: Int? = null,
        val myView: String = "",
        val local: Boolean = false,
        // «Что за сделка» (06.10.2026): кто привёл, вероятность (null — по стадии), сроки.
        val sourcePersonId: String = "",
        val probability: Int? = null,
        val expectedOn: String = "",
        val deadline: String = "",
        /** «Идеи» — старые заметки владельца (Notion): видны, только если в них что-то есть. */
        val ideas: String = "",
        /**
         * Сделка у клиента одна живая (`crm.only_deal`, 15.10.2026, аудит №2): тогда её шаги — и открытые дела клиента
         * без сделки, хронология — и его записи без сделки. Вторая живая сделка — правило молчит.
         */
        val solo: Boolean = false,
    ) {
        val closed: Boolean get() = stage == "archive"
        /** «в работе», а у закрытой — итог: «выиграли», «проиграли». */
        val stageWord: String get() = if (closed && outcome.isNotBlank()) OUTCOME[outcome] ?: outcome else STAGE[stage] ?: stage
        /** «Тишина N дн.» — как у веба: живая сделка, месяц без движения. */
        val quiet: Boolean get() = !closed && quietDays > 30
    }

    data class StageSum(val stage: String, val count: Int, val feeKop: Long?, val weightedKop: Long?)

    data class Pipeline(
        val deals: List<Deal>,
        val stages: List<StageSum>,
        val money: Boolean,
        val live: Int,
        val pipelineKop: Long?,
        val toGetKop: Long?,
        val stale: Int,
    ) {
        /** Живые сделки по стадиям в порядке воронки (пустые стадии — не показываем). */
        fun byStage(onlyStale: Boolean = false): List<Pair<String, List<Deal>>> =
            Dela.OPEN_STAGES.map { st ->
                st to deals.filter { it.stage == st && (!onlyStale || it.stale) }.sortedByDescending { it.feeKop ?: 0L }
            }.filter { it.second.isNotEmpty() }

        /** Закрытые за 90 дней — отдельно, свежие сверху. */
        val closed: List<Deal> get() = deals.filter { it.closed }.sortedByDescending { it.closedOn }
    }

    data class Client(
        val id: String,
        val name: String,
        val aliases: List<String>,
        val org: String,
        val archivedAt: String,
        val liveDeals: Int,
        val allDeals: Int,
        val stages: List<String>,
        val lastTouch: String,
        val openTasks: Int,
        val nextTask: NextTask?,
        val paidYearKop: Long?,
        val invoicedKop: Long?,
        val minutes90: Int?,
        /** `relations` — «поддержание отношений» (09.10.2026); пусто — по сделкам. */
        val status: String = "",
        /** Когда последнее дело заведено или закрыто — по нему сервер ставит свежих сверху. */
        val lastTaskAt: String = "",
    ) {
        val live: Boolean get() = archivedAt.isBlank()
        val relations: Boolean get() = status == RELATIONS
    }

    data class Clients(val clients: List<Client>, val money: Boolean) {
        /**
         * Поиск по имени, алиасам и организации — без регистра и «ё»; архивные — только по
         * просьбе; [relationsOnly] — фильтр «Поддержание отношений». Порядок — сервера
         * (с 09.10.2026 — по свежести дел и контактов), телефон его не пересортировывает.
         */
        fun find(q: String, withArchive: Boolean = false, relationsOnly: Boolean = false): List<Client> {
            val n = Dela.norm(q)
            return clients.filter {
                (withArchive || it.live) && (!relationsOnly || it.relations) &&
                    (n == null || Dela.norm(it.name + " " + it.aliases.joinToString(" ") + " " + it.org)?.contains(n) == true)
            }
        }
    }

    /** Статус клиента «поддержание отношений»: уже не лид, но и не сделка — работали, держим связь. */
    const val RELATIONS = "relations"

    /** Запись хронологии (`crm.interactions`). Строки не удаляются: убранная — `deleted_at`. */
    data class Interaction(
        val id: String,
        val at: String,
        val kind: String,
        val summary: String,
        val nextStep: String = "",
        val projectId: String = "",
        val dealId: String = "",
        val personIds: List<String> = emptyList(),
        val source: String = "",
        val sourceRef: String = "",
        val durationMin: Int? = null,
        val deletedAt: String = "",
        val dealName: String = "",
        val projectName: String = "",
        val local: Boolean = false,
        /**
         * Сводка дайджеста Telegram, из которой запись (`digest_ref` видов client, deal, dossier; 14.10.2026,
         * dela_0018): пункт сводки о рабочем человеке или клиенте. Такую запись видит только владелец (сервер
         * команде строки не отдаёт); тап «сводка →» — сама сводка видом `digest?ref=`. Пусто — обычная запись.
         */
        val digestRef: String = "",
        /**
         * Упоминание, не контакт (15.10.2026, аудит №28): клиента или человека назвали в теме сводки Telegram, а его
         * чата в окне не было. Запись — правда сводки, видна; «последний контакт» она не двигает (считает сервер).
         */
        val mention: Boolean = false,
        /** Приватная запись (флаг `privacy`, аудит №9): видит только её хозяин; «скрыть от команды» — вернуть нельзя. */
        val private: Boolean = false,
        /**
         * Что из записи видит команда (`crm.v_interactions`): `none` — приватная, `fact` — запись встречи, где команда видит
         * только название, а хозяин — итог и договорённости с карточки встречи, `all`. "" — старый сервер.
         */
        val teamSees: String = "",
        /** Хозяин записи: только он скрывает её от команды и убирает запись встречи и пункт дайджеста. */
        val ownerId: String = "",
    ) {
        val day: String get() = at.take(10)
        val kindWord: String get() = IKIND[kind] ?: kind
        val fromDigest: Boolean get() = digestRef.isNotBlank()
    }

    /**
     * «Только тебе» (флаг `privacy`, `crm.insights`, место этапа 6): наблюдение владельца — заметка рукой, несостыковка,
     * неуверенность, «кто за столом», что уточнить. Видит только владелец Дел; команде поле вида — `[]`. Названо не
     * `Insight`: так называются вещи «Денег».
     */
    data class PrivateNote(
        val id: String,
        val kind: String = "note",
        val text: String,
        val quote: String = "",
        val personIds: List<String> = emptyList(),
        val projectId: String = "",
        val dealId: String = "",
        val meetingId: String = "",
        val at: String = "",
        val source: String = "",
        val sourceRef: String = "",
        val state: String = "new",
        val people: List<String> = emptyList(),
        val meetingTitle: String = "",
        val local: Boolean = false,
    ) {
        /** «Мимо» и «Неверно» — свёрнуты строкой «ещё N». */
        val off: Boolean get() = state in NOTE_OFF
        val kindWord: String get() = NOTE_KIND[kind] ?: kind
    }

    /** Вид «Только тебе» словом (`INSIGHT_KIND` веба); незнакомый ключ этапа 6 — как есть. */
    val NOTE_KIND = mapOf(
        "note" to "заметка", "conflict" to "несостыковка", "numbers" to "цифры", "hedge" to "неуверенность", "self" to "о себе",
        "objection" to "возражение", "table" to "кто за столом", "ask" to "уточнить", "bypass" to "в обход", "signal" to "сигнал",
        "risk" to "риск", "mandate" to "мандат", "position" to "позиция", "personal" to "личное", "occasion" to "повод",
    )
    val NOTE_OFF = setOf("dismissed", "wrong")

    data class PersonRow(
        val id: String,
        val name: String,
        val short: String,
        val role: String,
        val cadence: String,
        val hub: Boolean,
    )

    /**
     * Встреча карточки сделки или клиента (15.10.2026, аудит №3; поле `meetings` видов `deal` и `client`): строка вида
     * «Встречи» сервера — когда, название Дел, сколько шла, ссылка на неё в вебе встреч. Видит только владелец встреч.
     */
    data class Meeting(
        val id: String,
        val day: String,
        val startedAt: String,
        val durationMin: Int?,
        val title: String,
        val url: String = "",
        val client: String = "",
        val deal: String = "",
        val people: List<String> = emptyList(),
        val summary: String = "",
    )

    data class ClientView(
        val project: Dela.Project?,
        val timeline: List<Interaction>,
        val deals: List<Deal>,
        val people: List<PersonRow>,
        val money: Boolean,
        /** Оплаты всех проектов клиента (09.10.2026) — тем, кому открыты деньги. */
        val payments: List<Dela.Payment> = emptyList(),
        /** Встречи клиента (свежие сверху, до 50) и сколько их всего; команде — пусто (аудит №3). */
        val meetings: List<Meeting> = emptyList(),
        val meetingsN: Int = 0,
        /** «Только тебе» клиента; null — старый сервер (поля нет): блока нет. */
        val insights: List<PrivateNote>? = null,
    )

    data class DealView(
        val deal: Deal?,
        /** Открытые шаги — её дела и, при [Deal.solo], дела клиента без сделки (у них `dealId` пуст: «дело клиента»). */
        val open: List<Dela.Task>,
        val done: List<Dela.Task>,
        val payments: List<Dela.Payment>,
        val timeline: List<Interaction>,
        val history: List<String>,
        val money: Boolean,
        val minutesAll: Int?,
        /** Встречи сделки по карточке встречи (свежие сверху, до 50) и сколько их всего; команде — пусто (аудит №3). */
        val meetings: List<Meeting> = emptyList(),
        val meetingsN: Int = 0,
        /** «Только тебе» сделки; null — старый сервер. */
        val insights: List<PrivateNote>? = null,
    )

    data class Tie(
        val id: String,
        val name: String,
        val short: String,
        val org: String,
        val role: String,
        val cadence: String,
        val hub: Boolean,
        val lastTouch: String,
        val brought: Int,
        val agenda: Int,
        val sinceDays: Int?,
        val due: Boolean,
        val overdueDays: Int?,
        /** Дней до дня рождения; есть, только если день рождения записан. */
        val birthdayIn: Int?,
    )

    data class Ties(val people: List<Tie>, val today: String) {
        /** Кому пора напомнить о себе — первыми (порядок сервера). */
        val due: List<Tie> get() = people.filter { it.due }
        val rest: List<Tie> get() = people.filter { !it.due }
        /** Дни рождения в ближайшие две недели — ближние сверху. */
        val birthdays: List<Tie> get() = people.filter { (it.birthdayIn ?: 99) <= 14 }.sortedBy { it.birthdayIn }
    }

    /** [insights] — «Только тебе» о человеке; null — старый сервер. */
    data class Dossier(val timeline: List<Interaction>, val deals: List<Deal>, val insights: List<PrivateNote>? = null)

    // ------------------------------------------------------------ разбор

    private fun objs(o: JSONObject, key: String): List<JSONObject> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun next(o: JSONObject?): NextTask? {
        o ?: return null
        val id = o.str("id").ifBlank { return null }
        return NextTask(id, o.int("num"), o.str("title"), o.str("due_date"), o.str("owner_id"), o.str("ball"), o.bool("via_client"))
    }

    fun deal(o: JSONObject): Deal? {
        val id = o.str("id").ifBlank { return null }
        return Deal(
            id = id, projectId = o.str("project_id"), projectName = o.str("project_name"), name = o.str("name"),
            stage = o.str("stage"), outcome = o.str("outcome"), lostReason = o.str("lost_reason"), closedOn = o.str("closed_on"),
            dealType = o.str("deal_type"), leadPersonId = o.str("lead_person_id"), teamIds = o.strings("team_ids"),
            personIds = o.strings("person_ids"), stageSince = o.str("stage_since"), openTasks = o.int("open_tasks"),
            nextTask = next(o.optJSONObject("next_task")), lastTouch = o.str("last_touch"), quietDays = o.int("quiet_days"),
            stale = o.bool("stale"), feeKop = o.longOrNull("fee_kop"), pEff = o.intOrNull("p_eff"),
            paidKop = o.longOrNull("paid_kop"), invoicedKop = o.longOrNull("invoiced_kop"), plannedKop = o.longOrNull("planned_kop"),
            toGetKop = o.longOrNull("to_get_kop"), weightedKop = o.longOrNull("weighted_kop"),
            minutes30 = o.intOrNull("minutes_30"), minutesAll = o.intOrNull("minutes_all"), myView = o.str("my_view"),
            sourcePersonId = o.str("source_person_id"), probability = o.intOrNull("probability"),
            expectedOn = o.str("expected_on"), deadline = o.str("deadline"), ideas = o.str("ideas"), solo = o.bool("solo"),
        )
    }

    fun interaction(o: JSONObject): Interaction? {
        val id = o.str("id").ifBlank { return null }
        return Interaction(
            id = id, at = o.str("at"), kind = o.str("kind"), summary = o.str("summary"), nextStep = o.str("next_step"),
            projectId = o.str("project_id"), dealId = o.str("deal_id"), personIds = o.strings("person_ids"),
            source = o.str("source"), sourceRef = o.str("source_ref"), durationMin = o.intOrNull("duration_min"),
            deletedAt = o.str("deleted_at"), dealName = o.str("deal_name"), projectName = o.str("project_name"),
            // Старый сервер поля не шлёт — ссылка на сводку та же: всё до «#» у записи из дайджеста.
            digestRef = o.str("digest_ref").ifBlank { if (o.str("source") == "digest") o.str("source_ref").substringBefore('#') else "" },
            mention = o.bool("mention"), private = o.bool("private"), teamSees = o.str("team_sees"), ownerId = o.str("owner_id"),
        )
    }

    /** Строка `insights` видов client, deal, dossier, meeting. */
    fun privateNote(o: JSONObject): PrivateNote? {
        val id = o.str("id").ifBlank { return null }
        val text = o.str("text").ifBlank { return null }
        return PrivateNote(
            id = id, kind = o.str("kind").ifBlank { "note" }, text = text, quote = o.str("quote"), personIds = o.strings("person_ids"),
            projectId = o.str("project_id"), dealId = o.str("deal_id"), meetingId = o.str("meeting_id"), at = o.str("at"),
            source = o.str("source"), sourceRef = o.str("source_ref"), state = o.str("state").ifBlank { "new" },
            people = o.strings("people"), meetingTitle = o.str("meeting_title"),
        )
    }

    /** Поле `insights` вида: нет поля — null (старый сервер, блока нет), пустое — пустой список. */
    private fun insights(o: JSONObject): List<PrivateNote>? =
        if (!o.has("insights") || o.isNull("insights")) null else objs(o, "insights").mapNotNull(::privateNote)

    /** Строка `meetings` вида сделки или клиента (`meetings._item` сервера). */
    fun meeting(o: JSONObject): Meeting? {
        val id = o.str("id").ifBlank { return null }
        return Meeting(
            id = id, day = o.str("day").ifBlank { o.str("started_at").take(10) }, startedAt = o.str("started_at"),
            durationMin = o.intOrNull("duration_min"), title = o.str("title").ifBlank { o.str("source_title") },
            url = o.str("url").takeIf { it.startsWith("https://") }.orEmpty(), client = o.str("client"), deal = o.str("deal"),
            people = o.strings("people"), summary = o.str("summary"),
        )
    }

    private fun meetings(o: JSONObject): List<Meeting> = objs(o, "meetings").mapNotNull(::meeting)

    private fun timeline(o: JSONObject): List<Interaction> =
        objs(o, "timeline").mapNotNull(::interaction).filter { it.deletedAt.isBlank() }

    /** Флаг денег ответа: сервер сам знает, кому они открыты; нет флага — смотрим на сами суммы. */
    private fun money(o: JSONObject): Boolean = o.optBoolean("money", false)

    fun pipeline(o: JSONObject): Pipeline {
        val t = o.optJSONObject("totals") ?: JSONObject()
        return Pipeline(
            deals = objs(o, "deals").mapNotNull(::deal),
            stages = objs(o, "stages").map { StageSum(it.str("stage"), it.int("count"), it.longOrNull("fee_kop"), it.longOrNull("weighted_kop")) },
            money = money(o),
            live = t.int("live"),
            pipelineKop = t.longOrNull("pipeline_kop"),
            toGetKop = t.longOrNull("to_get_kop"),
            stale = t.int("stale"),
        )
    }

    fun clients(o: JSONObject): Clients = Clients(
        clients = objs(o, "clients").mapNotNull { c ->
            val id = c.str("id").ifBlank { return@mapNotNull null }
            Client(
                id = id, name = c.str("name"), aliases = c.strings("aliases"), org = c.str("org"), archivedAt = c.str("archived_at"),
                liveDeals = c.int("live_deals"), allDeals = c.int("all_deals"), stages = c.strings("stages"),
                lastTouch = c.str("last_touch"), openTasks = c.int("open_tasks"), nextTask = next(c.optJSONObject("next_task")),
                paidYearKop = c.longOrNull("paid_year_kop"), invoicedKop = c.longOrNull("invoiced_kop"), minutes90 = c.intOrNull("minutes_90"),
                status = c.str("status"), lastTaskAt = c.str("last_task_at"),
            )
        },
        money = money(o),
    )

    private fun personRow(o: JSONObject): PersonRow? {
        val id = o.str("id").ifBlank { return null }
        return PersonRow(id, o.str("name"), o.str("short"), o.str("role"), o.str("cadence"), o.bool("hub"))
    }

    fun client(o: JSONObject): ClientView = ClientView(
        project = o.optJSONObject("project")?.let { Dela.project(it) },
        timeline = timeline(o),
        deals = objs(o, "deals").mapNotNull(::deal),
        people = objs(o, "people").mapNotNull(::personRow),
        money = money(o),
        payments = objs(o, "payments").mapNotNull { Dela.payment(it) },
        meetings = meetings(o),
        meetingsN = o.int("meetings_n"),
        insights = insights(o),
    )

    fun dealView(o: JSONObject): DealView = DealView(
        deal = o.optJSONObject("deal")?.let(::deal),
        open = objs(o, "open").mapNotNull { Dela.task(it) },
        done = objs(o, "done").mapNotNull { Dela.task(it) },
        payments = objs(o, "payments").mapNotNull { Dela.payment(it) },
        timeline = timeline(o),
        history = objs(o, "history").map(::historyLine),
        money = money(o),
        minutesAll = o.intOrNull("minutes_all"),
        meetings = meetings(o),
        meetingsN = o.int("meetings_n"),
        insights = insights(o),
    )

    /**
     * Склеивает людей («Это тот же, что…», `person.merge`) только владелец Дел (аудит 15.10.2026, №23): сервер остальным
     * отказывает, кнопка члену команды ни к чему. Роль — из `users[]` синка.
     */
    fun canMerge(s: Dela.Snapshot, me: String): Boolean = s.users[me]?.owner == true

    /**
     * Видит ли смотрящий карточку человека целиком (`personWhole` веба, аудит №10): ярус `owner` — владелец Дел или автор
     * карточки. Старый сервер яруса не шлёт — по роли и автору. Только тогда — пометки «Только тебе», «Теплота» и «хаб»
     * (у остальных сервер поля и не шлёт, а правку отвергнет).
     */
    fun personWhole(p: Dela.Person, s: Dela.Snapshot, me: String): Boolean =
        if (p.tier.isNotBlank()) p.tier == "owner" else s.users[me]?.owner == true || (p.ownerId.isNotBlank() && p.ownerId == me)

    /**
     * Ставит ли смотрящий коллег на сделку этого клиента (аудит №11, `crm.grants_project`): владелец Дел или хозяин
     * проекта. Старый сервер (без флага `privacy`) правила не знает — как раньше, всем.
     */
    fun grantsProject(s: Dela.Snapshot, me: String, projectId: String): Boolean =
        !s.privacyOn || s.users[me]?.owner == true || s.projects[projectId]?.ownerId?.let { it.isNotBlank() && it == me } == true

    /** Себя с команды сделки убрать можно всегда — по своей карточке пользователя Дел. */
    fun isMe(s: Dela.Snapshot, me: String, personId: String): Boolean =
        personId.isNotBlank() && (s.users[me]?.personId == personId || s.people[personId]?.userId == me)

    /** Замок строки хронологии словами (`lockMark` веба): приватная или из дайджеста — «видишь только ты»; встреча фактом — «команде — без итога». */
    fun lockWord(i: Interaction): String? = when {
        i.private || i.fromDigest -> "видишь только ты"
        i.teamSees == "fact" -> "команде — без итога"
        else -> null
    }

    /** «Скрыть от команды» — своя общая запись (хозяин), не пункт дайджеста; только при флаге `privacy`. */
    fun canHide(i: Interaction, me: String, privacyOn: Boolean): Boolean =
        privacyOn && !i.private && !i.fromDigest && !i.local && i.ownerId.isNotBlank() && i.ownerId == me

    /** Крестик «убрать»: запись встречи и пункт дайджеста — только их хозяину (сервер остальным откажет). */
    fun canDelete(i: Interaction, me: String): Boolean =
        !i.local && (!(i.source == "meeting" || i.fromDigest) || i.ownerId.isBlank() || i.ownerId == me)

    /** Встреч в карточке сделки и клиента — свежие; остальные — «все встречи · N» (`MEET_CARD` веба). */
    const val MEET_CARD = 8

    /** «14.10 · Альфа: фонды · 45 мин» — строка встречи в карточке (дата, название Дел, длительность). */
    fun meetingLine(m: Meeting, today: String): String = listOfNotNull(
        m.day.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, today) },
        m.title.ifBlank { "встреча" },
        hours(m.durationMin).takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /**
     * Следующее дело сделки — тем же правилом, что сервер (`crm.STEP`, аудит №2): её открытое дело из своей копии (там и
     * своя только что поставленная галка); своих нет, а сделка у клиента одна живая ([solo]) — открытое дело клиента без
     * сделки (не цель), самое раннее по сроку, с [NextTask.viaClient]; копия дел сделки не знает — что сказал сервер.
     * Дело из ответа вида, уже закрытое в копии, — не следующее: иначе только что закрытое висело бы до обновления вида.
     */
    fun nextFor(s: Dela.Snapshot, dealId: String, projectId: String, solo: Boolean, fromView: NextTask?): NextTask? {
        fun row(t: Dela.Task, via: Boolean) = NextTask(t.id, t.num, t.title, t.dueDate, t.ownerId, t.ball, via)
        val own = s.tasks.values.filter { it.dealId == dealId && !it.goal }
        own.filter { it.open }.sortedWith(Dela.ORDER).firstOrNull()?.let { return row(it, false) }
        if (solo && projectId.isNotBlank()) {
            clientSteps(s, projectId).firstOrNull()?.let { return row(it, true) }
        }
        // Копия знает дело из ответа: закрыто или ушло из сделки (и не шаг клиента при одной сделке) — не следующее.
        val known = fromView?.let { s.tasks[it.id] }
        if (known != null && (!known.open || (known.dealId != dealId && !(solo && known.dealId.isBlank() && known.projectId == projectId)))) return null
        return fromView
    }

    /**
     * Запасной список дел из ответа вида (копия своих не знает) — без того, что копия уже знает закрытым или ушедшим
     * ([fits] — где дело должно стоять): только что закрытое в копии дело иначе снова показывалось открытым, пока вид не
     * обновится (проверка 15.10.2026; [nextFor] от этого уже защищён).
     */
    fun viewSteps(s: Dela.Snapshot, fromView: List<Dela.Task>, fits: (Dela.Task) -> Boolean): List<Dela.Task> =
        fromView.filter { v -> val k = s.tasks[v.id]; k == null || (k.open && fits(k)) }

    /** Открытые дела клиента без сделки (не цели) — шаги его единственной живой сделки, ранние сверху. */
    fun clientSteps(s: Dela.Snapshot, projectId: String): List<Dela.Task> =
        s.tasks.values.filter { it.open && !it.goal && it.projectId == projectId && it.dealId.isBlank() }.sortedWith(Dela.ORDER)

    /**
     * Не дописанные имена (`skipped` ответа `person.add` и `person.merge`, аудит №1): другим именем не пишется то, что
     * уже имя или короткое имя другой живой карточки — «Ваня» не дописал — так зовут Иван Петров». Пусто — null.
     */
    fun skippedWords(result: JSONObject?): String? {
        val a = result?.optJSONArray("skipped") ?: return null
        val parts = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
            val alias = x.str("alias").ifBlank { return@mapNotNull null }
            "«$alias» не дописал — так зовут " + x.str("name").ifBlank { "другого человека" }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    fun ties(o: JSONObject): Ties = Ties(
        people = objs(o, "people").mapNotNull { p ->
            val id = p.str("id").ifBlank { return@mapNotNull null }
            Tie(
                id = id, name = p.str("name"), short = p.str("short"), org = p.str("org"), role = p.str("role"),
                cadence = p.str("cadence"), hub = p.bool("hub"), lastTouch = p.str("last_touch"), brought = p.int("brought"),
                agenda = p.int("agenda"), sinceDays = p.intOrNull("since_days"), due = p.bool("due"),
                overdueDays = p.intOrNull("overdue_days"), birthdayIn = p.intOrNull("birthday_in"),
            )
        },
        today = o.str("today"),
    )

    fun dossier(o: JSONObject): Dossier = Dossier(timeline(o), objs(o, "deals").mapNotNull(::deal), insights(o))

    /** Строка журнала сделки: «05.10 14:20 · dev · стадия: мандат». */
    private fun historyLine(h: JSONObject): String {
        val a = h.optJSONObject("after") ?: JSONObject()
        val what = when {
            h.str("op") == "insert" -> "заведена"
            a.str("stage").isNotBlank() -> "стадия: " + (STAGE[a.str("stage")] ?: a.str("stage")) +
                a.str("outcome").takeIf { it.isNotBlank() }?.let { " — " + (OUTCOME[it] ?: it) }.orEmpty()
            else -> a.keys().asSequence().filter { it !in setOf("updated_at", "rev", "seq") }.joinToString(", ")
        }
        return h.str("at").take(16).replace('T', ' ') + " · " + h.str("actor").ifBlank { "?" } + " · " + what
    }

    // ------------------------------------------------------------ карточки клиента и проекта (09.10.2026)

    /**
     * Строка хронологии (`timelineBlock` веба): запись (звонок, встреча, заметка),
     * счёт ([paid] = false — `invoiced_on`) или пришедшая оплата (`paid_on`).
     */
    data class TlRow(val at: String, val item: Interaction? = null, val pay: Dela.Payment? = null, val paid: Boolean = false)

    /** Сколько строк хронологии сразу — дальше «Показать всё» (`TL_SHOWN` веба). */
    const val TL_SHOWN = 10

    /** Записи, счета и оплаты вперемешку, свежие сверху. Отменённая оплата не в счёт. */
    fun timelineRows(items: List<Interaction>, pays: List<Dela.Payment>): List<TlRow> =
        (items.map { TlRow(it.at, item = it) } + pays.filter { it.live }.flatMap { p ->
            listOfNotNull(
                p.invoicedOn.takeIf { it.isNotBlank() }?.let { TlRow(it.take(10) + "T12:00:00", pay = p) },
                p.paidOn.takeIf { it.isNotBlank() }?.let { TlRow(it.take(10) + "T12:00:01", pay = p, paid = true) },
            )
        }).sortedByDescending { it.at }

    /**
     * Итог встречи коротко (`tlLine` веба): «Тема: итог» — тема до последнего «: »
     * в начале (до первой точки, не дальше 110 знаков; у встреч в теме бывает своё
     * двоеточие — «Клиент: тема: итог»). null у темы — её нет.
     */
    fun meetingParts(summary: String): Pair<String?, String> {
        val sum = summary.trim()
        val dot = sum.indexOf(". ")
        val stop = minOf(110, if (dot < 0) sum.length else dot)
        val cut = sum.substring(0, stop).lastIndexOf(": ")
        return if (cut >= 3) sum.substring(0, cut) to sum.substring(cut + 2) else null to sum
    }

    /** Договорённости (`next_step`) списком: через «;» или с новой строки, без маркеров и точек в конце. */
    fun steps(nextStep: String): List<String> =
        nextStep.split(Regex("\\s*;\\s*|\\n+")).map { it.replace(Regex("^[→\\-–•\\s]+"), "").replace(Regex("[.;]\\s*$"), "").trim() }
            .filter { it.isNotBlank() }

    /** Как ушёл счёт (`SENT_VIA` веба). */
    val SENT_VIA = listOf("почтой", "в Telegram", "через ЭДО", "лично", "курьером")

    /** Вид документа (`FILE_KIND` веба). */
    val FILE_KIND = linkedMapOf("contract" to "Договор", "invoice" to "Счёт", "nda" to "NDA", "act" to "Акт", "other" to "Документ")

    /** Ссылка без схемы — https:// (сервер ссылки не http(s) отвергает). */
    fun withScheme(v: String): String = v.trim().let { if (it.isNotBlank() && !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it)) "https://$it" else it }

    /**
     * «+ человек» — поиск, а не список (`personSearchPop` веба): пусто — [base]
     * (свои: команда или люди этого клиента); набрал — среди всех живых по имени,
     * короткому, другим именам, должности и компании. Каждое слово должно найтись:
     * с начала поля — лучше, с начала слова — хуже, кусочком — ещё хуже.
     */
    fun searchPeople(s: Dela.Snapshot, q: String, base: List<Dela.Person>, exclude: Set<String>, max: Int = 14): List<Dela.Person> {
        val words = (Dela.norm(q) ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return base.filter { it.id !in exclude }.take(max)
        fun keys(x: Dela.Person) = (listOf(x.name, x.short, x.role, orgLabel(s, x.orgId)?.name.orEmpty()) + x.aliases)
            .filter { it.isNotBlank() }.mapNotNull { Dela.norm(it) }
        fun score(x: Dela.Person): Int {
            val ks = keys(x)
            var sum = 0
            for (w in words) {
                sum += when {
                    ks.any { it.startsWith(w) } -> 0
                    ks.any { k -> k.split(Regex("[\\s\\-–«»\"().,]+")).any { it.startsWith(w) } } -> 1
                    ks.any { it.contains(w) } -> 2
                    else -> return -1
                }
            }
            return sum
        }
        return s.people.values.filter { it.live && it.id !in exclude }.map { it to score(it) }.filter { it.second >= 0 }
            .sortedWith(compareBy<Pair<Dela.Person, Int>>({ it.second }, { it.first.label.lowercase() })).map { it.first }.take(max)
    }

    /** Команда ЗФ (`teamPeople` веба): пользователи Дел и все, кто ведёт проекты или в их командах. */
    fun teamPeople(s: Dela.Snapshot): List<Dela.Person> {
        val ids = s.people.values.filter { it.userId.isNotBlank() }.map { it.id }.toMutableSet()
        for (d in s.deals.values) { if (d.leadPersonId.isNotBlank()) ids += d.leadPersonId; ids += d.teamIds }
        return ids.mapNotNull { s.people[it] }.filter { it.live }.sortedBy { it.label.lowercase() }
    }

    /** Команда клиента — ведущие и команды его живых проектов (`clientTeam` веба). */
    fun clientTeam(deals: List<Dela.Deal>): List<String> =
        deals.filter { !it.closed }.flatMap { listOf(it.leadPersonId) + it.teamIds }.filter { it.isNotBlank() }.distinct()

    /** Команда проекта: ведущий — первым, потом команда. */
    fun dealTeam(d: Dela.Deal): List<String> = (listOf(d.leadPersonId) + d.teamIds).filter { it.isNotBlank() }.distinct()

    /**
     * Проекты человека со стороной (`personDeals` веба): ведёт, в команде, от клиента,
     * привёл — живые сверху. У человека и в его карточке — плашками-ссылками.
     */
    fun personDeals(s: Dela.Snapshot, personId: String): List<Pair<Dela.Deal, String>> =
        s.deals.values.mapNotNull { d ->
            val role = when (personId) {
                d.leadPersonId -> "ведёт"
                in d.teamIds -> "в команде"
                in d.personIds -> "от клиента"
                d.sourcePersonId -> "привёл"
                else -> return@mapNotNull null
            }
            d to role
        }.sortedWith(compareBy({ it.first.closed }, { it.first.name.lowercase() }))

    /** Правка сделки полями (`deal.set`): описание, ведущий, файлы — что угодно из `set`. */
    fun dealSetOp(dealId: String, set: JSONObject, opId: String = Dela.newId()): JSONObject =
        op("deal.set", opId).put("id", dealId).put("set", set)

    /** Правка клиента (`project.set`): описание (`note`), статус, папка, файлы. */
    fun projectSetOp(projectId: String, set: JSONObject, opId: String = Dela.newId()): JSONObject =
        op("project.set", opId).put("id", projectId).put("set", set)

    /** «Счёт выставлен» — когда, кому и как (`invoicePop` веба). */
    fun invoiceOp(paymentId: String, day: String, sentTo: String, sentVia: String, opId: String = Dela.newId()): JSONObject =
        op("payment.set", opId).put("id", paymentId).put(
            "set",
            JSONObject().put("invoiced_on", day).put("sent_to", Dela.nul(sentTo.trim())).put("sent_via", Dela.nul(sentVia.trim())),
        )

    // ------------------------------------------------------------ пути видов

    const val PIPELINE = "pipeline"
    const val CLIENTS = "clients"
    const val TIES = "ties"
    fun clientPath(projectId: String) = "client?project_id=$projectId"
    fun dealPath(dealId: String) = "deal?deal_id=$dealId"
    fun dossierPath(personId: String) = "dossier?person_id=$personId"
    /** Одна сводка дайджеста по ref (`digest:2026-10-13T2000`) — вид `digest` сервера, только владельцу. */
    fun digestPath(ref: String) = "digest?ref=" + java.net.URLEncoder.encode(ref.substringBefore('#'), "UTF-8")

    // ------------------------------------------------------------ операции

    private fun op(name: String, opId: String) = JSONObject().put("op", name).put("op_id", opId)

    /** Стадия — переключателем: `deal.set {stage}`. */
    fun stageOp(dealId: String, stage: String, opId: String = Dela.newId()): JSONObject =
        op("deal.set", opId).put("id", dealId).put("set", JSONObject().put("stage", stage))

    /** «Закрыть…» с итогом: won, lost, paused; причина — учит воронку, можно пусто. */
    fun closeOp(dealId: String, outcome: String, reason: String = "", opId: String = Dela.newId()): JSONObject =
        op("deal.set", opId).put("id", dealId)
            .put("set", JSONObject().put("outcome", outcome).put("lost_reason", Dela.nul(reason.trim())))

    /** «Вернуть в работу» — живая стадия; итог сервер сотрёт сам. */
    fun reopenOp(dealId: String, opId: String = Dela.newId()): JSONObject = stageOp(dealId, "active", opId)

    /** «Счёт выставлен» — `{invoiced_on}`, «Оплачено» — `{paid_on}`: дата — сутки владельца. */
    fun invoicedOp(paymentId: String, day: String, opId: String = Dela.newId()): JSONObject =
        op("payment.set", opId).put("id", paymentId).put("set", JSONObject().put("invoiced_on", day))

    fun paidOp(paymentId: String, day: String, opId: String = Dela.newId()): JSONObject =
        op("payment.set", opId).put("id", paymentId).put("set", JSONObject().put("paid_on", day))

    /**
     * Запись в хронологию с телефона: `source = phone` (у хронологии сервер это
     * примет, у дела — нет). id выдаёт телефон — запись видна до ответа сервера.
     */
    fun interactionOp(
        kind: String,
        summary: String,
        atIso: String,
        projectId: String = "",
        dealId: String = "",
        personIds: List<String> = emptyList(),
        id: String = Dela.newId(),
        opId: String = Dela.newId(),
    ): JSONObject {
        val data = JSONObject().put("id", id).put("at", atIso).put("kind", if (kind in IKIND) kind else "note")
            .put("summary", summary.trim()).put("source", "phone").put("person_ids", Dela.arr(personIds.distinct()))
        if (projectId.isNotBlank()) data.put("project_id", projectId)
        if (dealId.isNotBlank()) data.put("deal_id", dealId)
        return op("interaction.add", opId).put("data", data)
    }

    /** Убрать запись: строка не удаляется, сервер ставит `deleted_at`. */
    fun interactionDeleteOp(id: String, opId: String = Dela.newId()): JSONObject = op("interaction.delete", opId).put("id", id)

    /** «Скрыть от команды» (флаг `privacy`): запись становится приватной навсегда — снять сервер не даст. */
    fun interactionHideOp(id: String, opId: String = Dela.newId()): JSONObject =
        op("interaction.set", opId).put("id", id).put("set", JSONObject().put("private", true))

    /**
     * «+ только мне» (флаг `privacy`, `insight.add`): о ком — человек ([personIds]), клиент ([projectId]) или сделка
     * ([projectId] + [dealId]). id выдаёт телефон — запись видна до ответа сервера.
     */
    fun noteAddOp(
        text: String,
        personIds: List<String> = emptyList(),
        projectId: String = "",
        dealId: String = "",
        kind: String = "note",
        id: String = Dela.newId(),
        opId: String = Dela.newId(),
    ): JSONObject {
        val x = JSONObject().put("id", id).put("kind", kind).put("text", text.trim()).put("source", "phone")
        if (personIds.isNotEmpty()) x.put("person_ids", Dela.arr(personIds.distinct()))
        if (projectId.isNotBlank()) x.put("project_id", projectId)
        if (dealId.isNotBlank()) x.put("deal_id", dealId)
        return op("insight.add", opId).put("insight", x)
    }

    /** «Видел» (`seen`), «Мимо» (`dismissed`) — состояние «Только тебе». */
    fun noteStateOp(id: String, state: String, opId: String = Dela.newId()): JSONObject =
        op("insight.set", opId).put("id", id).put("set", JSONObject().put("state", state))

    /** «Убрать» — сервер ставит `deleted_at`. */
    fun noteDeleteOp(id: String, opId: String = Dela.newId()): JSONObject = op("insight.delete", opId).put("id", id)

    /**
     * «Только тебе» из кэша вида плюс своё из очереди: новая запись видна сразу (о ком — [match]), «Видел» и «Мимо»
     * — сразу, убранная — уходит. Свежие сверху.
     */
    fun overlayNotes(list: List<PrivateNote>, ops: List<JSONObject>, match: (JSONObject) -> Boolean): List<PrivateNote> {
        val gone = ops.filter { it.str("op") == "insight.delete" }.map { it.str("id") }.toSet()
        val have = list.map { it.id }.toSet()
        val mine = ops.filter { it.str("op") == "insight.add" }.mapNotNull { o ->
            val x = o.optJSONObject("insight") ?: return@mapNotNull null
            if (!match(x)) return@mapNotNull null
            privateNote(x)?.copy(local = true)?.takeIf { it.id !in have }
        }
        val states = ops.filter { it.str("op") == "insight.set" }.associate { it.str("id") to it.optJSONObject("set")?.str("state").orEmpty() }
        return (list + mine).filter { it.id !in gone }
            .map { n -> states[n.id]?.takeIf { it.isNotBlank() }?.let { n.copy(state = it, local = true) } ?: n }
            .sortedByDescending { it.at }
    }

    /** Теплота и «хаб» человека. */
    fun personOp(personId: String, cadence: String? = null, hub: Boolean? = null, opId: String = Dela.newId()): JSONObject {
        val set = JSONObject()
        if (cadence != null) set.put("cadence", Dela.nul(cadence))
        if (hub != null) set.put("hub", hub)
        return op("person.set", opId).put("id", personId).put("set", set)
    }

    /**
     * Дописать человеку другие имена или номера (`person.add`, контракт
     * svod.json): сервер не затирает и не дублирует. Пусто — null.
     */
    fun personAddOp(personId: String, aliases: List<String> = emptyList(), phones: List<String> = emptyList(), opId: String = Dela.newId()): JSONObject? {
        val a = aliases.map { it.trim() }.filter { it.isNotEmpty() }
        val ph = phones.map { it.trim() }.filter { it.isNotEmpty() }
        if (a.isEmpty() && ph.isEmpty()) return null
        val add = JSONObject()
        if (a.isNotEmpty()) add.put("aliases", org.json.JSONArray(a))
        if (ph.isNotEmpty()) add.put("phones", org.json.JSONArray(ph))
        return op("person.add", opId).put("id", personId).put("add", add)
    }

    /** «Это тот же, что…»: [dupId] уходит в архив с `merged_into`, ссылки — на [intoId]. */
    fun personMergeOp(dupId: String, intoId: String, opId: String = Dela.newId()): JSONObject =
        op("person.merge", opId).put("id", dupId).put("into", intoId)

    /**
     * «Следующее дело…» сделки — открытое дело с проектом и сделкой: так сервер
     * видит у сделки следующий шаг (правило 12). Источник — `manual`:
     * `phone` база у дела не примет.
     */
    fun nextTaskOp(title: String, projectId: String, dealId: String, me: String, opId: String = Dela.newId()): JSONObject =
        Dela.createOp(Dela.Task(id = Dela.newId(), title = title.trim(), projectId = projectId, dealId = dealId, ownerId = me, source = "manual"), opId)

    /**
     * Когда была запись: сегодня — сейчас с поясом телефона, другой день — полдень
     * по Москве (как веб): у дня без времени место в середине, а не на границе суток.
     */
    fun atIso(day: String, today: String, now: OffsetDateTime = OffsetDateTime.now()): String =
        if (day.isBlank() || day == today) now.truncatedTo(ChronoUnit.SECONDS).toString() else "${day}T12:00:00+03:00"

    // ------------------------------------------------------------ своё поверх кэша

    /**
     * Своя неотправленная правка — поверх сделки из кэша вида: стадия и итог
     * переключились сразу, а не после синка и нового запроса вида.
     */
    fun overlayDeal(d: Deal, ops: List<JSONObject>, today: String): Deal {
        var out = d
        for (o in ops) {
            if (o.str("op") != "deal.set" || o.str("id") != d.id) continue
            val set = o.optJSONObject("set") ?: continue
            val base = Dela.Deal(id = out.id, projectId = out.projectId, name = out.name, stage = out.stage, outcome = out.outcome,
                lostReason = out.lostReason, closedOn = out.closedOn, teamIds = out.teamIds, personIds = out.personIds)
            val n = Dela.applyDeal(base, set, today)
            out = out.copy(
                name = n.name, stage = n.stage, outcome = n.outcome, lostReason = n.lostReason, closedOn = n.closedOn,
                teamIds = n.teamIds, personIds = n.personIds, local = true,
            )
        }
        return out
    }

    fun overlayPayments(list: List<Dela.Payment>, ops: List<JSONObject>): List<Dela.Payment> = list.map { p ->
        var out = p
        for (o in ops) {
            if (o.str("op") != "payment.set" || o.str("id") != p.id) continue
            val set = o.optJSONObject("set") ?: continue
            out = out.copy(
                invoicedOn = if (set.has("invoiced_on")) set.str("invoiced_on") else out.invoicedOn,
                paidOn = if (set.has("paid_on")) set.str("paid_on") else out.paidOn,
                cancelledAt = if (set.has("cancelled_at")) set.str("cancelled_at") else out.cancelledAt,
                local = true,
            )
        }
        out
    }

    /**
     * Хронология из кэша плюс свои записи из очереди (только подходящие этому
     * экрану — [match]) минус убранные; свежие сверху. Ответ сервера, в котором
     * своя запись уже есть, не удваивает её: id записи выдаёт телефон.
     */
    fun overlayTimeline(list: List<Interaction>, ops: List<JSONObject>, s: Dela.Snapshot, match: (Interaction) -> Boolean): List<Interaction> {
        val gone = ops.filter { it.str("op") == "interaction.delete" }.map { it.str("id") }.toSet()
        // «Скрыть от команды» — замок сразу, до ответа сервера.
        val hidden = ops.filter { it.str("op") == "interaction.set" && it.optJSONObject("set")?.optBoolean("private") == true }
            .map { it.str("id") }.toSet()
        val have = list.map { it.id }.toSet()
        val mine = ops.filter { it.str("op") == "interaction.add" }.mapNotNull { o ->
            val d = o.optJSONObject("data") ?: return@mapNotNull null
            val i = interaction(d)?.copy(local = true) ?: return@mapNotNull null
            i.copy(dealName = s.deals[i.dealId]?.name.orEmpty(), projectName = s.projects[i.projectId]?.name.orEmpty())
        }.filter { it.id !in have && match(it) }
        return (list + mine).filter { it.id !in gone }
            .map { if (it.id in hidden && !it.private) it.copy(private = true, teamSees = "none") else it }
            .sortedByDescending { it.at }
    }

    // ------------------------------------------------------------ проекты клиента

    /**
     * Проекты клиента — его сделки CRM (06.10.2026, docs/dela-phone-4.md;
     * владелец: «проекты должны группироваться под клиента»): живые по ходу
     * воронки, закрытые — в конце (`clientDeals` веба).
     */
    fun clientDeals(s: Dela.Snapshot, projectId: String): List<Dela.Deal> = s.allDealsOf(projectId)

    /**
     * «Клиент: тема» под своим клиентом — просто «тема» (`dealShort` веба): имя
     * клиента и так видно строкой выше. Приставка срезается, только если она —
     * сам клиент (его имя или алиас, по первым четырём буквам).
     */
    fun dealShort(name: String, client: Dela.Project?): String {
        val m = Regex("^([^:]{1,60}):\\s*(.+)$").find(name) ?: return name
        if (client == null) return name
        val head = Dela.norm(m.groupValues[1]) ?: return name
        val same = (listOf(client.name) + client.aliases).any { n ->
            val x = Dela.norm(n) ?: return@any false
            head.startsWith(x.take(4)) || x.startsWith(head.take(4))
        }
        return if (same) m.groupValues[2] else name
    }

    /** Проект клиента строкой меню: короткое имя, открытые дела и есть ли просрочка. */
    data class NavDeal(val deal: Dela.Deal, val short: String, val open: Int, val late: Boolean)

    /** Живые проекты клиента для меню и «Клиентов» — с числом открытых дел (красное при просрочке). */
    fun navDeals(s: Dela.Snapshot, projectId: String, today: String): List<NavDeal> {
        val client = s.projects[projectId]
        return clientDeals(s, projectId).filter { !it.closed }.map { d ->
            val open = s.tasks.values.filter { it.dealId == d.id && it.open }
            NavDeal(d, dealShort(d.name, client), open.size, open.any { it.dueDate.isNotBlank() && it.dueDate < today })
        }
    }

    // ------------------------------------------------------------ люди плашками

    /**
     * Люди клиента — как `view_client` сервера, но из своей копии (видно и без
     * сети, и сразу после крестика): из организации клиента или из людей его
     * сделок. Слитые дубли и архив — нет.
     */
    fun clientPeople(s: Dela.Snapshot, projectId: String): List<Dela.Person> {
        val org = s.projects[projectId]?.orgId.orEmpty()
        val inDeals = clientDeals(s, projectId).flatMap { it.personIds }.toSet()
        return s.people.values.filter { it.live && ((org.isNotBlank() && it.orgId == org) || it.id in inDeals) }
            .sortedBy { it.name.lowercase() }
    }

    /** Откуда человек — словами: клиент (и его страница), иначе имя организации; null — не знаем. */
    data class OrgLabel(val name: String, val clientId: String = "")

    fun orgLabel(s: Dela.Snapshot, orgId: String): OrgLabel? {
        if (orgId.isBlank()) return null
        s.projects.values.firstOrNull { it.kind == "client" && it.orgId == orgId }?.let { return OrgLabel(it.name, it.id) }
        return s.orgs[orgId]?.let { OrgLabel(it.name) }
    }

    /** Организации для «откуда он» (`orgChoices` веба): клиенты по имени проекта, потом другие компании. */
    data class OrgChoice(val orgId: String, val name: String, val client: Boolean)

    fun orgChoices(s: Dela.Snapshot): List<OrgChoice> {
        val byOrg = LinkedHashMap<String, String>()
        for (p in s.projects.values) if (p.kind == "client" && p.orgId.isNotBlank() && p.live) byOrg[p.orgId] = p.name
        val clients = byOrg.map { (id, name) -> OrgChoice(id, name, true) }.sortedBy { it.name.lowercase() }
        val other = s.orgs.values.filter { it.archivedAt.isBlank() && it.id !in byOrg }
            .map { OrgChoice(it.id, it.name, false) }.sortedBy { it.name.lowercase() }
        return clients + other
    }

    /** Группа «Людей»: «Команда», организация (клиент — ссылкой на его страницу), «Без компании». */
    data class PeopleGroup(val key: String, val title: String, val clientId: String, val people: List<Dela.Person>)

    /**
     * «Люди» по компаниям (`renderPeople` веба; владелец, 06.10.2026: «в людях
     * обязательно нужна группировка по компаниям»): команда сверху, без компании
     * — внизу. Поиск — каждое слово где угодно: имя, короткое, алиасы, должность,
     * компания.
     */
    fun peopleByCompany(s: Dela.Snapshot, q: String): List<PeopleGroup> {
        val words = (Dela.norm(q) ?: "").split(' ').filter { it.isNotBlank() }
        val groups = LinkedHashMap<String, Triple<String, String, MutableList<Dela.Person>>>()
        for (p in s.people.values.filter { it.live }) {
            val ol = orgLabel(s, p.orgId)
            val hay = Dela.norm(listOf(p.name, p.short, p.aliases.joinToString(" "), p.role, ol?.name.orEmpty()).joinToString(" ")).orEmpty()
            if (words.any { !hay.contains(it) }) continue
            val key = when {
                p.userId.isNotBlank() -> "0"
                ol != null -> "1" + (Dela.norm(ol.name) ?: ol.name)
                else -> "9"
            }
            val title = when {
                p.userId.isNotBlank() -> "Команда"
                ol != null -> ol.name
                else -> "Без компании"
            }
            groups.getOrPut(key) { Triple(title, if (p.userId.isBlank()) ol?.clientId.orEmpty() else "", mutableListOf()) }.third += p
        }
        return groups.entries.sortedBy { it.key }.map { (k, g) -> PeopleGroup(k, g.first, g.second, g.third.sortedBy { it.name.lowercase() }) }
    }

    /** Правка человека: откуда он (`org_id`), должность и что ещё ([set] — поля сервера). */
    fun personSetOp(personId: String, set: JSONObject, opId: String = Dela.newId()): JSONObject =
        op("person.set", opId).put("id", personId).put("set", set)

    /** Новый человек — id даёт телефон: виден до ответа, повтор не удваивает. */
    fun personCreateOp(id: String, name: String, orgId: String = "", opId: String = Dela.newId()): JSONObject {
        val data = JSONObject().put("id", id).put("name", name.trim())
        if (orgId.isNotBlank()) data.put("org_id", orgId)
        return op("person.create", opId).put("data", data)
    }

    /** Люди сделки или её команда целиком (`person_ids`, `team_ids`) — крестик и «+ человек». */
    fun dealPeopleOp(dealId: String, field: String, ids: List<String>, opId: String = Dela.newId()): JSONObject =
        op("deal.set", opId).put("id", dealId).put("set", JSONObject().put(field, Dela.arr(ids.distinct())))

    /** Правка и её отмена — для «Вернуть» после крестика или «откуда он». */
    data class Undoable(val ops: List<JSONObject>, val back: List<JSONObject>, val said: String)

    /**
     * Переименовать клиента (13.10.2026, docs/dela-phone-13.md; кнопка «Переименовать»
     * в `renderClientCard` веба). Пустое или то же имя — null, ничего. Так уже зовут
     * другой живой проект — отказ словами без операции (`ops` пустой): два клиента с
     * одним именем сервер не различил бы ни в подписях встреч, ни в разборе. Иначе —
     * `project.set {name}` и «Вернуть» прежним именем. Встречи, старое имя в `aliases`
     * и пристёгивание подписанных встреч сервер делает сам (dela_0012); со старым
     * сервером — просто новое имя: `project.set name` был всегда.
     */
    fun renameClient(s: Dela.Snapshot, projectId: String, name: String): Undoable? {
        val p = s.projects[projectId] ?: return null
        val n = name.trim()
        if (n.isEmpty() || n == p.name) return null
        val twin = s.projects.values.firstOrNull { it.id != p.id && it.live && it.name.equals(n, ignoreCase = true) }
        if (twin != null) return Undoable(emptyList(), emptyList(), "«${twin.name}» — уже другой клиент")
        return Undoable(
            listOf(projectSetOp(p.id, JSONObject().put("name", n))),
            listOf(projectSetOp(p.id, JSONObject().put("name", p.name))),
            "Теперь «$n» — и во встречах",
        )
    }

    /**
     * Убрать человека из клиента (`leaveClient` веба): из организации клиента,
     * если он оттуда, и из людей его сделок. Строки не удаляются — «Вернуть»
     * ставит всё как было. null — связан с клиентом иначе (поправить в карточке).
     */
    fun leaveClient(s: Dela.Snapshot, person: Dela.Person, projectId: String): Undoable? {
        val p = s.projects[projectId] ?: return null
        val go = mutableListOf<JSONObject>()
        val back = mutableListOf<JSONObject>()
        if (person.orgId.isNotBlank() && person.orgId == p.orgId) {
            go += personSetOp(person.id, JSONObject().put("org_id", JSONObject.NULL))
            back += personSetOp(person.id, JSONObject().put("org_id", person.orgId))
        }
        for (d in clientDeals(s, projectId)) {
            if (person.id !in d.personIds) continue
            go += dealPeopleOp(d.id, "person_ids", d.personIds - person.id)
            back += dealPeopleOp(d.id, "person_ids", d.personIds)
        }
        if (go.isEmpty()) return null
        return Undoable(go, back, "${person.label} больше не в «${p.name}»")
    }

    /**
     * Человека — к клиенту (`joinClient` веба): в организацию клиента; у старого
     * клиента без неё — сперва заводим её с его именем ([newOrgId]) и ставим
     * проекту. Нового человека ([name]) — сразу с этой организацией ([newPersonId]).
     */
    fun joinClient(
        s: Dela.Snapshot,
        projectId: String,
        person: Dela.Person?,
        name: String = "",
        newOrgId: String = Dela.newId(),
        newPersonId: String = Dela.newId(),
    ): Undoable? {
        val p = s.projects[projectId] ?: return null
        val go = mutableListOf<JSONObject>()
        val org = p.orgId.ifBlank {
            go += op("org.create", Dela.newId()).put("data", JSONObject().put("id", newOrgId).put("name", p.name).put("kind", "client"))
            go += op("project.set", Dela.newId()).put("id", p.id).put("set", JSONObject().put("org_id", newOrgId))
            newOrgId
        }
        return if (person != null) {
            if (person.orgId == org) return null
            go += personSetOp(person.id, JSONObject().put("org_id", org))
            Undoable(go, listOf(personSetOp(person.id, JSONObject().put("org_id", Dela.nul(person.orgId)))), "${person.label} — теперь из «${p.name}»")
        } else {
            if (name.isBlank()) return null
            go += personCreateOp(newPersonId, name, org)
            Undoable(go, emptyList(), "Завёл: ${name.trim()} из «${p.name}»")
        }
    }

    // ------------------------------------------------------------ слова

    /** «250 000 ₽» — копейки целые, как у сервера. Разряды — своим пробелом, не по языку телефона. */
    fun rub(kop: Long?): String =
        if (kop == null || kop == 0L) "" else String.format(Locale.ROOT, "%,d ₽", kop / 100).replace(',', ' ')

    /** «2,5 млн» · «350 тыс» — для плиток воронки. */
    fun rubShort(kop: Long?): String {
        if (kop == null || kop == 0L) return "0"
        val r = kop / 100.0
        return if (r >= 1e6) String.format(Locale.ROOT, if (r >= 1e7) "%.0f" else "%.1f", r / 1e6).replace('.', ',') + " млн"
        else "${Math.round(r / 1000)} тыс"
    }

    /** «сегодня», «вчера», «12 дн. назад», дальше месяца — дата. */
    fun ago(iso: String, today: String): String {
        if (iso.isBlank()) return ""
        val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
        val t = runCatching { LocalDate.parse(today.take(10)) }.getOrNull() ?: return iso.take(10)
        val n = ChronoUnit.DAYS.between(d, t)
        return when {
            n <= 0 -> "сегодня"
            n == 1L -> "вчера"
            n < 30 -> "$n дн. назад"
            else -> DelaAsk.ddmm(iso, today)
        }
    }

    /** Дней с даты до сегодня; нет даты — null. */
    fun daysSince(iso: String, today: String): Long? {
        val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return null
        val t = runCatching { LocalDate.parse(today.take(10)) }.getOrNull() ?: return null
        return ChronoUnit.DAYS.between(d, t)
    }

    /** «1 ч 20 мин» из минут Засечки. */
    fun hours(min: Int?): String = when {
        min == null || min <= 0 -> ""
        min >= 60 -> "${min / 60} ч ${"%02d".format(min % 60)} мин"
        else -> "$min мин"
    }

}
