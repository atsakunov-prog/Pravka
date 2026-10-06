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

    /** Следующее дело сделки или клиента — открытое дело с ближайшим сроком. */
    data class NextTask(val id: String, val num: Int, val title: String, val dueDate: String, val ownerId: String, val ball: String)

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
    ) {
        val live: Boolean get() = archivedAt.isBlank()
    }

    data class Clients(val clients: List<Client>, val money: Boolean) {
        /** Поиск по имени, алиасам и организации — без регистра и «ё»; архивные — только по просьбе. */
        fun find(q: String, withArchive: Boolean = false): List<Client> {
            val n = Dela.norm(q)
            return clients.filter { (withArchive || it.live) && (n == null || Dela.norm(it.name + " " + it.aliases.joinToString(" ") + " " + it.org)?.contains(n) == true) }
        }
    }

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
    ) {
        val day: String get() = at.take(10)
        val kindWord: String get() = IKIND[kind] ?: kind
    }

    data class PersonRow(
        val id: String,
        val name: String,
        val short: String,
        val role: String,
        val cadence: String,
        val hub: Boolean,
    )

    data class ClientView(
        val project: Dela.Project?,
        val timeline: List<Interaction>,
        val deals: List<Deal>,
        val people: List<PersonRow>,
        val money: Boolean,
    )

    data class DealView(
        val deal: Deal?,
        val open: List<Dela.Task>,
        val done: List<Dela.Task>,
        val payments: List<Dela.Payment>,
        val timeline: List<Interaction>,
        val history: List<String>,
        val money: Boolean,
        val minutesAll: Int?,
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

    data class Dossier(val timeline: List<Interaction>, val deals: List<Deal>)

    // ------------------------------------------------------------ разбор

    private fun objs(o: JSONObject, key: String): List<JSONObject> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun next(o: JSONObject?): NextTask? {
        o ?: return null
        val id = o.str("id").ifBlank { return null }
        return NextTask(id, o.int("num"), o.str("title"), o.str("due_date"), o.str("owner_id"), o.str("ball"))
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
        )
    }

    fun interaction(o: JSONObject): Interaction? {
        val id = o.str("id").ifBlank { return null }
        return Interaction(
            id = id, at = o.str("at"), kind = o.str("kind"), summary = o.str("summary"), nextStep = o.str("next_step"),
            projectId = o.str("project_id"), dealId = o.str("deal_id"), personIds = o.strings("person_ids"),
            source = o.str("source"), sourceRef = o.str("source_ref"), durationMin = o.intOrNull("duration_min"),
            deletedAt = o.str("deleted_at"), dealName = o.str("deal_name"), projectName = o.str("project_name"),
        )
    }

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
    )

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

    fun dossier(o: JSONObject): Dossier = Dossier(timeline(o), objs(o, "deals").mapNotNull(::deal))

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

    // ------------------------------------------------------------ пути видов

    const val PIPELINE = "pipeline"
    const val CLIENTS = "clients"
    const val TIES = "ties"
    fun clientPath(projectId: String) = "client?project_id=$projectId"
    fun dealPath(dealId: String) = "deal?deal_id=$dealId"
    fun dossierPath(personId: String) = "dossier?person_id=$personId"

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
                lostReason = out.lostReason, closedOn = out.closedOn)
            val n = Dela.applyDeal(base, set, today)
            out = out.copy(stage = n.stage, outcome = n.outcome, lostReason = n.lostReason, closedOn = n.closedOn, local = true)
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
        val have = list.map { it.id }.toSet()
        val mine = ops.filter { it.str("op") == "interaction.add" }.mapNotNull { o ->
            val d = o.optJSONObject("data") ?: return@mapNotNull null
            val i = interaction(d)?.copy(local = true) ?: return@mapNotNull null
            i.copy(dealName = s.deals[i.dealId]?.name.orEmpty(), projectName = s.projects[i.projectId]?.name.orEmpty())
        }.filter { it.id !in have && match(it) }
        return (list + mine).filter { it.id !in gone }.sortedByDescending { it.at }
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
