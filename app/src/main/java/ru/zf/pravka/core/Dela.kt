package ru.zf.pravka.core

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Дела на домашнем сервере (docs/dela-server.md, контракт с телефоном —
 * server/contract/dela.json). Здесь — то, что не знает ни про сеть, ни про
 * диск, и поэтому живёт под JVM-тестами: строки синка, слияние «после seq»,
 * наложение неотправленной очереди, виды и операции.
 *
 * Почему виды считаются на телефоне, а не берутся готовыми с `/api/view`:
 * владелец открывает «Дела» в метро и в машине. Вкладка обязана открываться
 * мгновенно из кэша и работать без сети, а свою правку видеть сразу, до
 * ответа сервера. Логика видов повторяет `store.py` буква в букву — сервер
 * остаётся источником правды, телефон только смотрит на свою копию его
 * данных тем же взглядом.
 */
object Dela {

    const val MINE = "mine"
    const val WAITING = "waiting"
    const val AGENDA = "agenda"
    const val INBOX = "inbox"
    const val OPEN = "open"
    const val DONE = "done"
    const val CANCELLED = "cancelled"

    // ------------------------------------------------------------ строки

    /** Дело — строка `tasks.v_tasks`: своё плюс имена и наследованное из вида. */
    data class Task(
        val id: String,
        val num: Int = 0,
        val title: String,
        val notes: String = "",
        val projectId: String = "",
        val dealId: String = "",
        val ownerId: String = "",
        val ball: String = MINE,
        /**
         * «Отбить» (10.10.2026, `dela_0007`, docs/dela-phone-11.md): человек ждёт от
         * меня ответа или дела. Группа дела — из него и мяча ([DelaGroups.grpOf]).
         */
        val owed: Boolean = false,
        val personId: String = "",
        val waitingSince: String = "",
        val nudgeOn: String = "",
        val requestedBy: String = "",
        val dueDate: String = "",
        val dueTime: String = "",
        /**
         * Напоминание в Telegram (06.10.2026, контракт — server/contract/dela-remind.json):
         * момент ISO со смещением («2026-10-06T11:00:00+03:00»). У напоминания по
         * месту сюда ложится миг приезда — его ставит телефон, увидев место.
         */
        val remindAt: String = "",
        /** Напомнить по приезду: имя места автопилота Засечки («дом», «Летово»). */
        val remindPlace: String = "",
        /** Когда сервер отправил напоминание; ставит только сервер, правка напоминания его сбрасывает. */
        val remindedAt: String = "",
        val estimateMin: Int = 0,
        /** "" — как у проекта (money_default). */
        val money: String = "",
        val want: Boolean = false,
        val focusOn: String = "",
        val labels: List<String> = emptyList(),
        val status: String = OPEN,
        val source: String = "manual",
        val sourceRef: String = "",
        val createdBy: String = "",
        val createdAt: String = "",
        val updatedAt: String = "",
        val completedAt: String = "",
        val rev: Int = 0,
        val seq: Long = 0,
        // Из вида: телефон пересчитывает их сам из справочника (derive), а
        // присланное сервером — запас, если проекта в справочнике нет.
        val projectName: String = "",
        val projectKind: String = "",
        val dealName: String = "",
        val sphere: String = INBOX,
        val moneyEff: String = "none",
        val personShort: String = "",
        val personName: String = "",
        val requestedByShort: String = "",
        /** Есть правка телефона, которую сервер ещё не принял. */
        val local: Boolean = false,
    ) {
        val open: Boolean get() = status == OPEN
        /** Как человек называется в строке: короткое имя, иначе полное. */
        val who: String get() = personShort.ifBlank { personName }
        /** «#57», пока дело не дошло до сервера — «новое». */
        val numLabel: String get() = if (num > 0) "#$num" else "новое"
    }

    data class Project(
        val id: String,
        val name: String,
        val aliases: List<String> = emptyList(),
        val sphere: String = "work",
        val kind: String = "client",
        val orgId: String = "",
        val ownerId: String = "",
        val moneyDefault: String = "none",
        val note: String = "",
        val archivedAt: String = "",
        val rev: Int = 0,
        val seq: Long = 0,
        // Карточка клиента (09.10.2026, docs/dela-phone-9.md): `relations` — «поддержание
        // отношений» (не лид и не сделка), пусто — по сделкам; папка и документы ссылками.
        val status: String = "",
        val folderUrl: String = "",
        val files: List<FileRef> = emptyList(),
    ) {
        val live: Boolean get() = archivedAt.isBlank()
    }

    /** Документ ссылкой (`files` сделки и клиента): вид (contract, invoice, nda, act, other), название, ссылка, дата. */
    data class FileRef(val kind: String, val title: String, val url: String, val at: String = "")

    fun files(o: JSONObject, key: String = "files"): List<FileRef> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val f = a.optJSONObject(i) ?: return@mapNotNull null
            val url = f.str("url").ifBlank { return@mapNotNull null }
            FileRef(f.str("kind").ifBlank { "other" }, f.str("title"), url, f.str("at"))
        }
    }

    fun json(files: List<FileRef>): JSONArray = JSONArray().apply {
        for (f in files) put(JSONObject().put("kind", f.kind).put("title", nul(f.title)).put("url", f.url).put("at", nul(f.at)))
    }

    /**
     * Сделка — строка `crm.v_deals` (контракт, часть 2). Деньги (`fee_kop`)
     * приходят только тем, кому они открыты: у остальных null — и здесь null,
     * а не ноль: «гонорара нет» и «гонорар не твой» — разные вещи.
     */
    data class Deal(
        val id: String,
        val projectId: String,
        val name: String,
        val stage: String = "",
        val outcome: String = "",
        val lostReason: String = "",
        val closedOn: String = "",
        val dealType: String = "",
        val leadPersonId: String = "",
        val teamIds: List<String> = emptyList(),
        val personIds: List<String> = emptyList(),
        val feeKop: Long? = null,
        val rev: Int = 0,
        val seq: Long = 0,
        val local: Boolean = false,
        // «Что за сделка» (06.10.2026, docs/dela-phone-4.md): кто привёл, вероятность, сроки.
        val sourcePersonId: String = "",
        /** Вероятность выиграть, %; null — по стадии (сервер считает сам). */
        val probability: Int? = null,
        /** Когда ждём решения клиента или подписи. */
        val expectedOn: String = "",
        val deadline: String = "",
        // Карточка проекта (09.10.2026): что за проект (видят все, кто видит сделку), папка и документы.
        val description: String = "",
        val folderUrl: String = "",
        val files: List<FileRef> = emptyList(),
    ) {
        val closed: Boolean get() = stage == "archive"
    }

    /** Оплата сделки (`crm.payments`): план, счёт, деньги; «не будет» — cancelled_at. */
    data class Payment(
        val id: String,
        val dealId: String,
        val kind: String = "",
        val title: String = "",
        val amountKop: Long = 0,
        val dueOn: String = "",
        val invoicedOn: String = "",
        val paidOn: String = "",
        val cancelledAt: String = "",
        val note: String = "",
        val rev: Int = 0,
        val seq: Long = 0,
        val local: Boolean = false,
        /** Кому и как ушёл счёт (09.10.2026): «бухгалтерия, Иван», «почтой». */
        val sentTo: String = "",
        val sentVia: String = "",
    ) {
        val live: Boolean get() = cancelledAt.isBlank()
    }

    data class Person(
        val id: String,
        val name: String,
        val short: String = "",
        val aliases: List<String> = emptyList(),
        val orgId: String = "",
        val phones: List<String> = emptyList(),
        val archivedAt: String = "",
        val rev: Int = 0,
        val seq: Long = 0,
        val role: String = "",
        /** Теплота: month, quarter, year, none; "" — не задана. */
        val cadence: String = "",
        /** «Хаб» — через него идут темы. */
        val hub: Boolean = false,
        val birthDay: Int = 0,
        val birthMonth: Int = 0,
        /** Человек — пользователь Дел (команда), а не контакт. */
        val userId: String = "",
        val local: Boolean = false,
        // Карточка человека в вебе: почта, Telegram, год рождения, что ищет и
        // предлагает, характер, откуда, заметка — синк приносит строку целиком.
        val emails: List<String> = emptyList(),
        val telegram: String = "",
        val birthYear: Int = 0,
        val seeks: String = "",
        val offers: String = "",
        val traits: String = "",
        val source: String = "",
        val note: String = "",
        /**
         * Дубль, слитый в живую карточку (`person.merge`, 06.10.2026): такой
         * человек в архиве, и все ссылки телефона на него — на этот id.
         */
        val mergedInto: String = "",
    ) {
        val label: String get() = short.ifBlank { name }
        val live: Boolean get() = archivedAt.isBlank() && mergedInto.isBlank()
    }

    data class Org(
        val id: String,
        val name: String,
        val aliases: List<String> = emptyList(),
        val archivedAt: String = "",
        val rev: Int = 0,
        val seq: Long = 0,
    )

    data class Comment(
        val id: String,
        val taskId: String,
        val authorId: String = "",
        val text: String,
        val deletedAt: String = "",
        val createdAt: String = "",
        val rev: Int = 0,
        val seq: Long = 0,
        val local: Boolean = false,
    )

    /** «Новое»: предложение автоматики. Задачи нет, пока человек не принял. */
    data class Suggestion(
        val id: String,
        val forUser: String = "",
        val kind: String = "create",
        val taskId: String = "",
        /** JSON предложенного дела строкой: поля задачи или имена (project_name, person_name). */
        val payload: String = "{}",
        val source: String = "",
        val sourceRef: String = "",
        val quote: String = "",
        val batchRef: String = "",
        val batchTitle: String = "",
        val status: String = "pending",
        val reason: String = "",
        val expiresAt: String = "",
        val createdAt: String = "",
        val decidedAt: String = "",
        val rev: Int = 0,
        val seq: Long = 0,
        val local: Boolean = false,
        val decidedBy: String = "",
        val resultTaskId: String = "",
        /**
         * «Понятно» у решённого без человека («Закрыто само», 06.10.2026):
         * видел, согласен — из «Нового» уходит. Ставит операция `suggestion.seen`.
         */
        val seenAt: String = "",
        /**
         * Итог решения строкой JSON (`result` сервера): поля дела после него, у
         * сделанного само (08.10.2026) — ещё `was` (как было, только поменявшееся)
         * и `comment_id` (комментарий-основание). У старого сервера — без них.
         */
        val result: String = "{}",
    ) {
        val pending: Boolean get() = status == "pending"
        fun payloadObj(): JSONObject = runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
        fun resultObj(): JSONObject = runCatching { JSONObject(result) }.getOrElse { JSONObject() }

        /**
         * «Сделано само» (08.10.2026, docs/dela-phone-5.md, правило 5 сервера):
         * закрытие или уточнение по свежей встрече или переписке сервер принял
         * сам. Признак — причина решения (`store.AUTO_REASONS`), а не флаг
         * `payload.auto`: такое предложение, принятое руками (встреча старая),
         * — решение человека, сюда не идёт. Старый сервер закрывал с той же
         * причиной «закрыто само» — его строки видны как были.
         */
        val autoDone: Boolean
            get() = (kind == "close" || kind == "update") && status == "accepted" && reason in AUTO_REASONS

        /** Дата встречи или переписки, иначе — когда предложение появилось: по ней пачки идут свежими сверху. */
        val at: String get() = payloadObj().str("meeting_at").ifBlank { createdAt.take(10) }
        /** Что предложено, одной строкой. */
        val title: String
            get() = payloadObj().let { p -> p.str("title").ifBlank { p.str("text") } }.ifBlank {
                when (kind) {
                    "close" -> "закрыть дело"
                    "assign" -> "взять дело"
                    else -> "предложение"
                }
            }
    }

    /**
     * Пользователь Дел. `clients` — какие клиенты ему видны (own — только свои
     * проекты, team — по ответственности в сделках, all — все), `seesMoney` —
     * открыты ли деньги фирмы. Владельцу открыто всё по роли.
     */
    data class User(
        val id: String,
        val name: String,
        val personId: String = "",
        val role: String = "member",
        val seq: Long = 0,
        val clients: String = "own",
        val seesMoney: Boolean = false,
    ) {
        val owner: Boolean get() = role == "owner"
    }

    /** Копия сервера на телефоне: всё, что видит владелец, плюс номер последнего изменения. */
    data class Snapshot(
        val seq: Long = 0,
        val today: String = "",
        val tasks: Map<String, Task> = emptyMap(),
        val projects: Map<String, Project> = emptyMap(),
        val deals: Map<String, Deal> = emptyMap(),
        val people: Map<String, Person> = emptyMap(),
        val orgs: Map<String, Org> = emptyMap(),
        val comments: Map<String, Comment> = emptyMap(),
        val suggestions: Map<String, Suggestion> = emptyMap(),
        val users: Map<String, User> = emptyMap(),
        val labels: List<String> = emptyList(),
        val syncedAt: Long = 0,
        val payments: Map<String, Payment> = emptyMap(),
        /** `features` последнего синка: чего нет — сервер ещё не умеет, и телефон это не шлёт. */
        val features: Set<String> = emptySet(),
    ) {
        /** Сервер Дел знает напоминания (поля `remind_*`) и шлёт их в Telegram. */
        val remindOn: Boolean get() = FEATURE_REMIND in features

        /** Сервер Дел держит наговорки (`dictation.add`, вид `dictations`) — «Новое» по источнику. */
        val dictationsOn: Boolean get() = FEATURE_DICTATIONS in features

        /** Сервер Дел знает «Отбить» (`owed`): только тогда телефон его шлёт. */
        val groupsOn: Boolean get() = FEATURE_GROUPS in features

        val empty: Boolean
            get() = tasks.isEmpty() && projects.isEmpty() && people.isEmpty() && suggestions.isEmpty()

        fun task(ref: String): Task? {
            val s = ref.trim().removePrefix("#")
            tasks[s]?.let { return it }
            val n = s.toIntOrNull() ?: return null
            return tasks.values.firstOrNull { it.num == n }
        }

        fun liveProjects(): List<Project> = projects.values.filter { it.live }.sortedBy { it.name.lowercase() }
        fun livePeople(): List<Person> = people.values.filter { it.live }.sortedBy { it.label.lowercase() }

        /**
         * Человек по id с учётом слияний: ссылка на дубль ведёт к живой
         * карточке (`merged_into`), цепочка — до конца, круг не вешает.
         */
        fun person(id: String): Person? {
            var p = people[id] ?: return null
            val seen = HashSet<String>()
            while (p.mergedInto.isNotBlank() && seen.add(p.id)) p = people[p.mergedInto] ?: break
            return p
        }
        fun dealsOf(projectId: String): List<Deal> =
            deals.values.filter { it.projectId == projectId && it.stage != "archive" }.sortedBy { it.name.lowercase() }
        /** Все сделки клиента: живые по ходу воронки, закрытые — в конце. */
        fun allDealsOf(projectId: String): List<Deal> =
            deals.values.filter { it.projectId == projectId }
                .sortedWith(compareBy<Deal>({ it.closed }, { STAGES.indexOf(it.stage).let { i -> if (i < 0) 99 else i } }, { it.name.lowercase() }))
        fun paymentsOf(dealId: String): List<Payment> =
            payments.values.filter { it.dealId == dealId }.sortedWith(compareBy<Payment>({ it.dueOn.isBlank() }, { it.dueOn }))
        fun commentsOf(taskId: String): List<Comment> =
            comments.values.filter { it.taskId == taskId && it.deletedAt.isBlank() }.sortedBy { it.createdAt }
    }

    // ------------------------------------------------------------ JSON

    /** Строка поля: JSON null — пустая строка, а не «null» (`optString` отдал бы «null»). */
    fun JSONObject.str(key: String): String =
        if (!has(key) || isNull(key)) "" else opt(key)?.toString().orEmpty()

    internal fun JSONObject.int(key: String): Int = if (!has(key) || isNull(key)) 0 else optInt(key, 0)
    internal fun JSONObject.long(key: String): Long = if (!has(key) || isNull(key)) 0L else optLong(key, 0L)
    /** Число, которого может не быть: деньги, закрытые человеку, сервер отдаёт null — это не ноль. */
    internal fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    internal fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
    internal fun JSONObject.bool(key: String): Boolean = !isNull(key) && optBoolean(key, false)
    internal fun JSONObject.strings(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.optString(i).trim().takeIf { it.isNotEmpty() } }
    }

    internal fun nul(s: String): Any = s.ifBlank { null } ?: JSONObject.NULL
    internal fun arr(items: List<String>): JSONArray = JSONArray().apply { items.forEach { put(it) } }
    private fun nulLong(v: Long?): Any = v ?: JSONObject.NULL

    fun task(o: JSONObject): Task? {
        val id = o.str("id").ifBlank { return null }
        return Task(
            id = id, num = o.int("num"), title = o.str("title"), notes = o.str("notes"),
            projectId = o.str("project_id"), dealId = o.str("deal_id"), ownerId = o.str("owner_id"),
            ball = o.str("ball").ifBlank { MINE }.let { if (it == OWED) MINE else it },
            owed = o.bool("owed") || o.str("ball") == OWED, personId = o.str("person_id"),
            waitingSince = o.str("waiting_since"), nudgeOn = o.str("nudge_on"), requestedBy = o.str("requested_by"),
            dueDate = o.str("due_date"), dueTime = o.str("due_time"),
            remindAt = o.str("remind_at"), remindPlace = o.str("remind_place"), remindedAt = o.str("reminded_at"),
            estimateMin = o.int("estimate_min"),
            money = o.str("money"), want = o.bool("want"), focusOn = o.str("focus_on"), labels = o.strings("labels"),
            status = o.str("status").ifBlank { OPEN }, source = o.str("source").ifBlank { "manual" },
            sourceRef = o.str("source_ref"), createdBy = o.str("created_by"), createdAt = o.str("created_at"),
            updatedAt = o.str("updated_at"), completedAt = o.str("completed_at"), rev = o.int("rev"), seq = o.long("seq"),
            projectName = o.str("project_name"), projectKind = o.str("project_kind"), dealName = o.str("deal_name"),
            sphere = o.str("sphere").ifBlank { INBOX }, moneyEff = o.str("money_eff").ifBlank { "none" },
            personShort = o.str("person_short"), personName = o.str("person_name"),
            requestedByShort = o.str("requested_by_short"), local = o.bool("_local"),
        )
    }

    fun json(t: Task): JSONObject = JSONObject()
        .put("id", t.id).put("num", t.num).put("title", t.title).put("notes", nul(t.notes))
        .put("project_id", nul(t.projectId)).put("deal_id", nul(t.dealId)).put("owner_id", t.ownerId)
        .put("ball", t.ball).put("owed", t.owed).put("person_id", nul(t.personId)).put("waiting_since", nul(t.waitingSince))
        .put("nudge_on", nul(t.nudgeOn)).put("requested_by", nul(t.requestedBy)).put("due_date", nul(t.dueDate))
        .put("due_time", nul(t.dueTime)).put("remind_at", nul(t.remindAt)).put("remind_place", nul(t.remindPlace))
        .put("reminded_at", nul(t.remindedAt)).put("estimate_min", if (t.estimateMin > 0) t.estimateMin else JSONObject.NULL)
        .put("money", nul(t.money)).put("want", t.want).put("focus_on", nul(t.focusOn)).put("labels", arr(t.labels))
        .put("status", t.status).put("source", t.source).put("source_ref", nul(t.sourceRef))
        .put("created_by", t.createdBy).put("created_at", nul(t.createdAt)).put("updated_at", nul(t.updatedAt))
        .put("completed_at", nul(t.completedAt)).put("rev", t.rev).put("seq", t.seq)
        .put("project_name", nul(t.projectName)).put("project_kind", nul(t.projectKind)).put("deal_name", nul(t.dealName))
        .put("sphere", t.sphere).put("money_eff", t.moneyEff).put("person_short", nul(t.personShort))
        .put("person_name", nul(t.personName)).put("requested_by_short", nul(t.requestedByShort))
        .apply { if (t.local) put("_local", true) }

    fun project(o: JSONObject): Project? {
        val id = o.str("id").ifBlank { return null }
        return Project(
            id, o.str("name"), o.strings("aliases"), o.str("sphere").ifBlank { "work" }, o.str("kind").ifBlank { "client" },
            o.str("org_id"), o.str("owner_id"), o.str("money_default").ifBlank { "none" }, o.str("note"),
            o.str("archived_at"), o.int("rev"), o.long("seq"),
            status = o.str("status"), folderUrl = o.str("folder_url"), files = files(o),
        )
    }

    fun json(p: Project): JSONObject = JSONObject().put("id", p.id).put("name", p.name).put("aliases", arr(p.aliases))
        .put("sphere", p.sphere).put("kind", p.kind).put("org_id", nul(p.orgId)).put("owner_id", p.ownerId)
        .put("money_default", p.moneyDefault).put("note", nul(p.note)).put("archived_at", nul(p.archivedAt))
        .put("status", nul(p.status)).put("folder_url", nul(p.folderUrl)).put("files", json(p.files))
        .put("rev", p.rev).put("seq", p.seq)

    fun deal(o: JSONObject): Deal? {
        val id = o.str("id").ifBlank { return null }
        return Deal(
            id = id, projectId = o.str("project_id"), name = o.str("name"), stage = o.str("stage"),
            outcome = o.str("outcome"), lostReason = o.str("lost_reason"), closedOn = o.str("closed_on"),
            dealType = o.str("deal_type"), leadPersonId = o.str("lead_person_id"), teamIds = o.strings("team_ids"),
            personIds = o.strings("person_ids"), feeKop = o.longOrNull("fee_kop"), rev = o.int("rev"), seq = o.long("seq"),
            local = o.bool("_local"), sourcePersonId = o.str("source_person_id"), probability = o.intOrNull("probability"),
            expectedOn = o.str("expected_on"), deadline = o.str("deadline"),
            description = o.str("description"), folderUrl = o.str("folder_url"), files = files(o),
        )
    }

    fun json(d: Deal): JSONObject = JSONObject().put("id", d.id).put("project_id", d.projectId).put("name", d.name)
        .put("stage", nul(d.stage)).put("outcome", nul(d.outcome)).put("lost_reason", nul(d.lostReason))
        .put("closed_on", nul(d.closedOn)).put("deal_type", nul(d.dealType)).put("lead_person_id", nul(d.leadPersonId))
        .put("team_ids", arr(d.teamIds)).put("person_ids", arr(d.personIds)).put("fee_kop", nulLong(d.feeKop))
        .put("source_person_id", nul(d.sourcePersonId)).put("probability", d.probability ?: JSONObject.NULL)
        .put("expected_on", nul(d.expectedOn)).put("deadline", nul(d.deadline))
        .put("description", nul(d.description)).put("folder_url", nul(d.folderUrl)).put("files", json(d.files))
        .put("rev", d.rev).put("seq", d.seq).apply { if (d.local) put("_local", true) }

    fun payment(o: JSONObject): Payment? {
        val id = o.str("id").ifBlank { return null }
        return Payment(
            id = id, dealId = o.str("deal_id"), kind = o.str("kind"), title = o.str("title"), amountKop = o.long("amount_kop"),
            dueOn = o.str("due_on"), invoicedOn = o.str("invoiced_on"), paidOn = o.str("paid_on"),
            cancelledAt = o.str("cancelled_at"), note = o.str("note"), rev = o.int("rev"), seq = o.long("seq"),
            local = o.bool("_local"), sentTo = o.str("sent_to"), sentVia = o.str("sent_via"),
        )
    }

    fun json(p: Payment): JSONObject = JSONObject().put("id", p.id).put("deal_id", p.dealId).put("kind", p.kind)
        .put("title", nul(p.title)).put("amount_kop", p.amountKop).put("due_on", nul(p.dueOn))
        .put("invoiced_on", nul(p.invoicedOn)).put("paid_on", nul(p.paidOn)).put("cancelled_at", nul(p.cancelledAt))
        .put("note", nul(p.note)).put("sent_to", nul(p.sentTo)).put("sent_via", nul(p.sentVia))
        .put("rev", p.rev).put("seq", p.seq).apply { if (p.local) put("_local", true) }

    fun person(o: JSONObject): Person? {
        val id = o.str("id").ifBlank { return null }
        return Person(
            id, o.str("name"), o.str("short"), o.strings("aliases"), o.str("org_id"), o.strings("phones"),
            o.str("archived_at"), o.int("rev"), o.long("seq"),
            role = o.str("role"), cadence = o.str("cadence"), hub = o.bool("hub"),
            birthDay = o.int("birth_day"), birthMonth = o.int("birth_month"), userId = o.str("user_id"), local = o.bool("_local"),
            emails = o.strings("emails"), telegram = o.str("telegram_username"), birthYear = o.int("birth_year"),
            seeks = o.str("seeks"), offers = o.str("offers"), traits = o.str("traits"), source = o.str("source"), note = o.str("note"),
            mergedInto = o.str("merged_into"),
        )
    }

    fun json(p: Person): JSONObject = JSONObject().put("id", p.id).put("name", p.name).put("short", nul(p.short))
        .put("aliases", arr(p.aliases)).put("org_id", nul(p.orgId)).put("phones", arr(p.phones))
        .put("archived_at", nul(p.archivedAt)).put("rev", p.rev).put("seq", p.seq)
        .put("role", nul(p.role)).put("cadence", nul(p.cadence)).put("hub", p.hub)
        .put("birth_day", if (p.birthDay > 0) p.birthDay else JSONObject.NULL)
        .put("birth_month", if (p.birthMonth > 0) p.birthMonth else JSONObject.NULL)
        .put("user_id", nul(p.userId)).put("emails", arr(p.emails)).put("telegram_username", nul(p.telegram))
        .put("birth_year", if (p.birthYear > 0) p.birthYear else JSONObject.NULL).put("seeks", nul(p.seeks))
        .put("offers", nul(p.offers)).put("traits", nul(p.traits)).put("source", nul(p.source)).put("note", nul(p.note))
        .put("merged_into", nul(p.mergedInto))
        .apply { if (p.local) put("_local", true) }

    fun org(o: JSONObject): Org? {
        val id = o.str("id").ifBlank { return null }
        return Org(id, o.str("name"), o.strings("aliases"), o.str("archived_at"), o.int("rev"), o.long("seq"))
    }

    fun json(o: Org): JSONObject = JSONObject().put("id", o.id).put("name", o.name).put("aliases", arr(o.aliases))
        .put("archived_at", nul(o.archivedAt)).put("rev", o.rev).put("seq", o.seq)

    fun comment(o: JSONObject): Comment? {
        val id = o.str("id").ifBlank { return null }
        return Comment(
            id, o.str("task_id"), o.str("author_id"), o.str("text"), o.str("deleted_at"), o.str("created_at"),
            o.int("rev"), o.long("seq"), o.bool("_local"),
        )
    }

    fun json(c: Comment): JSONObject = JSONObject().put("id", c.id).put("task_id", c.taskId).put("author_id", c.authorId)
        .put("text", c.text).put("deleted_at", nul(c.deletedAt)).put("created_at", nul(c.createdAt))
        .put("rev", c.rev).put("seq", c.seq).apply { if (c.local) put("_local", true) }

    fun suggestion(o: JSONObject): Suggestion? {
        val id = o.str("id").ifBlank { return null }
        val payload = o.optJSONObject("payload")?.toString() ?: o.str("payload").ifBlank { "{}" }
        val result = o.optJSONObject("result")?.toString() ?: "{}"
        return Suggestion(
            id, o.str("for_user"), o.str("kind").ifBlank { "create" }, o.str("task_id"), payload, o.str("source"),
            o.str("source_ref"), o.str("quote"), o.str("batch_ref"), o.str("batch_title"),
            o.str("status").ifBlank { "pending" }, o.str("reason"), o.str("expires_at"), o.str("created_at"),
            o.str("decided_at"), o.int("rev"), o.long("seq"), o.bool("_local"),
            decidedBy = o.str("decided_by"), resultTaskId = o.str("result_task_id"), seenAt = o.str("seen_at"),
            result = result,
        )
    }

    fun json(s: Suggestion): JSONObject = JSONObject().put("id", s.id).put("for_user", s.forUser).put("kind", s.kind)
        .put("task_id", nul(s.taskId)).put("payload", runCatching { JSONObject(s.payload) }.getOrElse { JSONObject() })
        .put("source", s.source).put("source_ref", nul(s.sourceRef)).put("quote", nul(s.quote))
        .put("batch_ref", nul(s.batchRef)).put("batch_title", nul(s.batchTitle)).put("status", s.status)
        .put("reason", nul(s.reason)).put("expires_at", nul(s.expiresAt)).put("created_at", nul(s.createdAt))
        .put("decided_at", nul(s.decidedAt)).put("rev", s.rev).put("seq", s.seq)
        .put("decided_by", nul(s.decidedBy)).put("result_task_id", nul(s.resultTaskId)).put("seen_at", nul(s.seenAt))
        .put("result", runCatching { JSONObject(s.result) }.getOrElse { JSONObject() })
        .apply { if (s.local) put("_local", true) }

    fun user(o: JSONObject): User? {
        val id = o.str("id").ifBlank { return null }
        return User(
            id, o.str("name").ifBlank { id }, o.str("person_id"), o.str("role").ifBlank { "member" }, o.long("seq"),
            clients = o.str("clients").ifBlank { "own" }, seesMoney = o.bool("sees_money"),
        )
    }

    fun json(u: User): JSONObject =
        JSONObject().put("id", u.id).put("name", u.name).put("person_id", nul(u.personId)).put("role", u.role).put("seq", u.seq)
            .put("clients", u.clients).put("sees_money", u.seesMoney)

    private fun <T> rows(o: JSONObject, key: String, parse: (JSONObject) -> T?): List<T> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(parse) }
    }

    private fun labels(o: JSONObject): List<String>? {
        val a = o.optJSONArray("labels") ?: return null
        return (0 until a.length()).mapNotNull { i ->
            when (val v = a.opt(i)) {
                is JSONObject -> v.str("name")
                is String -> v
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().sorted()
    }

    /** Кэш на диск: та же форма, что у ответа синка, плюс когда он пришёл. */
    fun toJson(s: Snapshot): JSONObject = JSONObject()
        .put("v", 1).put("seq", s.seq).put("today", s.today).put("syncedAt", s.syncedAt)
        .put("tasks", JSONArray().apply { s.tasks.values.forEach { put(json(it)) } })
        .put("projects", JSONArray().apply { s.projects.values.forEach { put(json(it)) } })
        .put("deals", JSONArray().apply { s.deals.values.forEach { put(json(it)) } })
        .put("people", JSONArray().apply { s.people.values.forEach { put(json(it)) } })
        .put("orgs", JSONArray().apply { s.orgs.values.forEach { put(json(it)) } })
        .put("comments", JSONArray().apply { s.comments.values.forEach { put(json(it)) } })
        .put("suggestions", JSONArray().apply { s.suggestions.values.forEach { put(json(it)) } })
        .put("users", JSONArray().apply { s.users.values.forEach { put(json(it)) } })
        .put("payments", JSONArray().apply { s.payments.values.forEach { put(json(it)) } })
        .put("labels", arr(s.labels))
        .put("features", arr(s.features.sorted()))

    fun fromJson(o: JSONObject): Snapshot = Snapshot(
        seq = o.long("seq"),
        today = o.str("today"),
        tasks = rows(o, "tasks", ::task).associateBy { it.id },
        projects = rows(o, "projects", ::project).associateBy { it.id },
        deals = rows(o, "deals", ::deal).associateBy { it.id },
        people = rows(o, "people", ::person).associateBy { it.id },
        orgs = rows(o, "orgs", ::org).associateBy { it.id },
        comments = rows(o, "comments", ::comment).associateBy { it.id },
        suggestions = rows(o, "suggestions", ::suggestion).associateBy { it.id },
        users = rows(o, "users", ::user).associateBy { it.id },
        labels = labels(o).orEmpty(),
        syncedAt = o.long("syncedAt"),
        payments = rows(o, "payments", ::payment).associateBy { it.id },
        features = o.strings("features").toSet(),
    )

    // ------------------------------------------------------------ синк

    /**
     * Ответ `/api/sync` поверх копии. `full` — выбросить всё и взять ответ
     * целиком (первый синк, новый или снятый доступ). Иначе — строки поверх
     * своих по id: окно перекрытия сервера присылает и уже виденное, поэтому
     * строка не моложе своей (`rev`) просто переписывается — повтор безвреден.
     * Удалений нет: строки на сервере не удаляются (дело — done/cancelled).
     */
    fun merge(old: Snapshot, resp: JSONObject, now: Long): Snapshot {
        val fresh = fromJson(resp)
        if (resp.optBoolean("full", false)) {
            return derive(fresh.copy(syncedAt = now, labels = labels(resp) ?: old.labels))
        }
        fun <T> up(have: Map<String, T>, got: Map<String, T>, rev: (T) -> Long): Map<String, T> {
            if (got.isEmpty()) return have
            val out = LinkedHashMap(have)
            for ((id, row) in got) {
                val was = out[id]
                if (was == null || rev(row) >= rev(was)) out[id] = row
            }
            return out
        }
        return derive(
            Snapshot(
                seq = maxOf(old.seq, fresh.seq),
                today = fresh.today.ifBlank { old.today },
                tasks = up(old.tasks, fresh.tasks) { it.rev.toLong() },
                projects = up(old.projects, fresh.projects) { it.rev.toLong() },
                deals = up(old.deals, fresh.deals) { it.rev.toLong() },
                people = up(old.people, fresh.people) { it.rev.toLong() },
                orgs = up(old.orgs, fresh.orgs) { it.rev.toLong() },
                comments = up(old.comments, fresh.comments) { it.rev.toLong() },
                suggestions = up(old.suggestions, fresh.suggestions) { it.rev.toLong() },
                users = up(old.users, fresh.users) { it.seq },
                labels = labels(resp) ?: old.labels,
                syncedAt = now,
                payments = up(old.payments, fresh.payments) { it.rev.toLong() },
                // Умение — состояние сервера сейчас, а не накопленное: откатили
                // сервер — флага нет, и телефон перестаёт слать то, чего тот не знает.
                features = fresh.features,
            )
        )
    }

    /**
     * Имена и наследованное у дел — из справочника, как в виде `v_tasks`:
     * переименованный проект или человек переименовывается и в делах сразу,
     * а дело, созданное офлайн, получает имя проекта до ответа сервера.
     */
    fun derive(s: Snapshot): Snapshot {
        if (s.tasks.isEmpty()) return s
        val tasks = s.tasks.mapValues { (_, t) -> deriveTask(t, s) }
        return s.copy(tasks = tasks)
    }

    private fun deriveTask(t: Task, s: Snapshot): Task {
        val p = s.projects[t.projectId]
        val d = s.deals[t.dealId]
        val pe = s.people[t.personId]
        val rq = s.people[t.requestedBy]
        return t.copy(
            projectName = if (t.projectId.isBlank()) "" else p?.name ?: t.projectName,
            projectKind = if (t.projectId.isBlank()) "" else p?.kind ?: t.projectKind,
            sphere = if (t.projectId.isBlank()) INBOX else p?.sphere ?: t.sphere,
            moneyEff = t.money.ifBlank { if (t.projectId.isBlank()) "none" else p?.moneyDefault ?: t.moneyEff.ifBlank { "none" } },
            dealName = if (t.dealId.isBlank()) "" else d?.name ?: t.dealName,
            personShort = if (t.personId.isBlank()) "" else pe?.short ?: t.personShort,
            personName = if (t.personId.isBlank()) "" else pe?.name ?: t.personName,
            requestedByShort = if (t.requestedBy.isBlank()) "" else rq?.label ?: t.requestedByShort,
        )
    }

    // ------------------------------------------------------------ очередь поверх копии

    /**
     * Неотправленные операции — поверх копии сервера, по порядку: телефон
     * видит свою правку сразу, а когда сервер её примет, следующий синк
     * привезёт ту же строку с новым `rev`. Принятое предложение просто
     * исчезает из «Нового»: дело с ним создаст сервер, и id его знает только он.
     */
    fun overlay(s: Snapshot, ops: List<JSONObject>, me: String, today: String, nowIso: String): Snapshot {
        if (ops.isEmpty()) return s
        val tasks = LinkedHashMap(s.tasks)
        val comments = LinkedHashMap(s.comments)
        val suggestions = LinkedHashMap(s.suggestions)
        val deals = LinkedHashMap(s.deals)
        val payments = LinkedHashMap(s.payments)
        val people = LinkedHashMap(s.people)
        val orgs = LinkedHashMap(s.orgs)
        val projects = LinkedHashMap(s.projects)
        fun find(ref: String): Task? {
            tasks[ref]?.let { return it }
            val n = ref.trim().removePrefix("#").toIntOrNull() ?: return null
            return tasks.values.firstOrNull { it.num == n }
        }
        for (op in ops) {
            when (op.str("op")) {
                "task.create" -> {
                    val t = op.optJSONObject("task") ?: continue
                    val id = t.str("id").takeIf { it.isNotBlank() } ?: continue
                    if (tasks.containsKey(id)) continue
                    val base = Task(id = id, title = t.str("title"), ownerId = me, createdBy = me, createdAt = nowIso, updatedAt = nowIso, local = true)
                    tasks[id] = applyFields(base, t, today, nowIso)
                }
                "task.set" -> {
                    val t = find(op.str("id")) ?: continue
                    val set = op.optJSONObject("set") ?: continue
                    tasks[t.id] = applyFields(t, set, today, nowIso).copy(local = true)
                }
                "task.done", "task.reopen", "task.cancel" -> {
                    val t = find(op.str("id")) ?: continue
                    val status = when (op.str("op")) { "task.done" -> DONE; "task.cancel" -> CANCELLED; else -> OPEN }
                    tasks[t.id] = t.copy(status = status, completedAt = if (status == OPEN) "" else nowIso, local = true)
                }
                "comment.add" -> {
                    val c = op.optJSONObject("comment") ?: continue
                    val task = find(c.str("task_id")) ?: continue
                    val id = c.str("id").ifBlank { op.str("op_id") }
                    if (comments.containsKey(id)) continue
                    comments[id] = Comment(id, task.id, me, c.str("text"), createdAt = nowIso, local = true)
                }
                "comment.delete" -> {
                    val c = comments[op.str("id")] ?: continue
                    comments[c.id] = c.copy(deletedAt = nowIso, local = true)
                }
                "suggestion.decide" -> {
                    val sg = suggestions[op.str("id")] ?: continue
                    if (!sg.pending) continue
                    val status = if (op.str("decision") == "reject") "rejected" else "accepted"
                    suggestions[sg.id] = sg.copy(status = status, reason = op.str("reason"), decidedAt = nowIso, local = true)
                }
                // «Понятно» у закрытого само — уходит из «Нового» сразу, до ответа.
                "suggestion.seen" -> for (id in op.strings("ids")) {
                    val sg = suggestions[id] ?: continue
                    if (sg.pending || sg.seenAt.isNotBlank()) continue
                    suggestions[id] = sg.copy(seenAt = nowIso, local = true)
                }
                "deal.set" -> {
                    val d = deals[op.str("id")] ?: continue
                    deals[d.id] = applyDeal(d, op.optJSONObject("set") ?: continue, today)
                }
                "payment.set" -> {
                    val p = payments[op.str("id")] ?: continue
                    val set = op.optJSONObject("set") ?: continue
                    payments[p.id] = p.copy(
                        invoicedOn = if (set.has("invoiced_on")) set.str("invoiced_on") else p.invoicedOn,
                        paidOn = if (set.has("paid_on")) set.str("paid_on") else p.paidOn,
                        cancelledAt = if (set.has("cancelled_at")) set.str("cancelled_at") else p.cancelledAt,
                        sentTo = if (set.has("sent_to")) set.str("sent_to") else p.sentTo,
                        sentVia = if (set.has("sent_via")) set.str("sent_via") else p.sentVia,
                        local = true,
                    )
                }
                "person.set" -> {
                    val p = people[op.str("id")] ?: continue
                    val set = op.optJSONObject("set") ?: continue
                    people[p.id] = applyPerson(p, set)
                }
                // Человек, заведённый с телефона («+ человек» у клиента и сделки), — виден до ответа: id даёт телефон.
                "person.create" -> {
                    val d = op.optJSONObject("data") ?: continue
                    val id = d.str("id").takeIf { it.isNotBlank() } ?: continue
                    if (people.containsKey(id)) continue
                    people[id] = applyPerson(Person(id = id, name = d.str("name")), d)
                }
                "org.create" -> {
                    val d = op.optJSONObject("data") ?: continue
                    val id = d.str("id").takeIf { it.isNotBlank() } ?: continue
                    if (!orgs.containsKey(id)) orgs[id] = Org(id, d.str("name"), d.strings("aliases"))
                }
                // У старого клиента без организации её заводит телефон — проект узнаёт её сразу.
                "project.set" -> {
                    val p = projects[op.str("id")] ?: continue
                    val set = op.optJSONObject("set") ?: continue
                    projects[p.id] = p.copy(
                        orgId = if (set.has("org_id")) set.str("org_id") else p.orgId,
                        name = if (set.has("name")) set.str("name").ifBlank { p.name } else p.name,
                        archivedAt = if (set.has("archived_at")) set.str("archived_at") else p.archivedAt,
                        note = if (set.has("note")) set.str("note") else p.note,
                        status = if (set.has("status")) set.str("status") else p.status,
                        folderUrl = if (set.has("folder_url")) set.str("folder_url") else p.folderUrl,
                        files = if (set.has("files")) files(set) else p.files,
                    )
                }
                // Дописать имена и номера — как сервер: что уже есть, не дублируется.
                "person.add" -> {
                    val p = people[op.str("id")] ?: continue
                    val add = op.optJSONObject("add") ?: continue
                    people[p.id] = p.copy(
                        aliases = p.aliases + addNew(p.aliases + p.name + p.short, add.strings("aliases"), ::normName),
                        phones = p.phones + addNew(p.phones, add.strings("phones"), CallRules::digits),
                        emails = p.emails + addNew(p.emails, add.strings("emails")) { it.trim().lowercase() },
                        telegram = p.telegram.ifBlank { add.str("telegram_username").removePrefix("@") },
                        local = true,
                    )
                }
                "person.merge" -> {
                    val dup = people[op.str("id")] ?: continue
                    val into = people[op.str("into")] ?: continue
                    people[dup.id] = dup.copy(archivedAt = nowIso, mergedInto = into.id, local = true)
                    people[into.id] = into.copy(
                        aliases = into.aliases + (listOf(dup.name) + dup.aliases)
                            .filter { a -> a.isNotBlank() && normName(a) != normName(into.name) && into.aliases.none { normName(it) == normName(a) } }
                            .distinctBy(::normName),
                        phones = into.phones + dup.phones.filter { d -> into.phones.none { CallRules.digits(it) == CallRules.digits(d) } },
                        local = true,
                    )
                }
            }
        }
        return derive(
            s.copy(
                tasks = tasks, comments = comments, suggestions = suggestions, deals = deals, payments = payments, people = people,
                orgs = orgs, projects = projects,
            )
        )
    }

    /**
     * Правка человека поверх строки — те поля, что меняет телефон: откуда он,
     * должность, теплота, хаб, имя, архив. Остальное — как было.
     */
    fun applyPerson(p: Person, set: JSONObject): Person = p.copy(
        name = if (set.has("name")) set.str("name").ifBlank { p.name } else p.name,
        short = if (set.has("short")) set.str("short") else p.short,
        orgId = if (set.has("org_id")) set.str("org_id") else p.orgId,
        role = if (set.has("role")) set.str("role") else p.role,
        cadence = if (set.has("cadence")) set.str("cadence") else p.cadence,
        hub = if (set.has("hub")) set.bool("hub") else p.hub,
        archivedAt = if (set.has("archived_at")) set.str("archived_at") else p.archivedAt,
        local = true,
    )

    /**
     * Правка сделки поверх строки — как у триггера сервера: итог (`outcome`)
     * сам уводит сделку в архив с датой закрытия, а возврат в живую стадию
     * итог снимает. Нужна телефону, чтобы стадия переключалась сразу, до ответа.
     */
    fun applyDeal(d: Deal, set: JSONObject, today: String): Deal {
        var out = d
        // Люди сделки и команда — плашками на её странице (06.10.2026): крестик и «+ человек» видны сразу.
        if (set.has("person_ids")) out = out.copy(personIds = set.strings("person_ids"))
        if (set.has("team_ids")) out = out.copy(teamIds = set.strings("team_ids"))
        if (set.has("name")) out = out.copy(name = set.str("name").ifBlank { out.name })
        if (set.has("stage")) out = out.copy(stage = set.str("stage"))
        if (set.has("outcome")) out = out.copy(outcome = set.str("outcome"))
        if (set.has("lost_reason")) out = out.copy(lostReason = set.str("lost_reason"))
        // Карточка проекта (09.10.2026): описание, ведущий, факты, папка и документы — видны сразу.
        if (set.has("description")) out = out.copy(description = set.str("description"))
        if (set.has("lead_person_id")) out = out.copy(leadPersonId = set.str("lead_person_id"))
        if (set.has("deal_type")) out = out.copy(dealType = set.str("deal_type"))
        if (set.has("source_person_id")) out = out.copy(sourcePersonId = set.str("source_person_id"))
        if (set.has("probability")) out = out.copy(probability = set.intOrNull("probability"))
        if (set.has("expected_on")) out = out.copy(expectedOn = set.str("expected_on"))
        if (set.has("deadline")) out = out.copy(deadline = set.str("deadline"))
        if (set.has("folder_url")) out = out.copy(folderUrl = set.str("folder_url"))
        if (set.has("files")) out = out.copy(files = files(set))
        // Тот же порядок, что у crm.deal_rules: итог уводит в архив, живая стадия итог стирает.
        if (out.outcome.isNotBlank() && set.has("outcome")) out = out.copy(stage = "archive")
        out = if (out.stage == "archive") {
            out.copy(closedOn = out.closedOn.ifBlank { if (d.stage != "archive") today else "" })
        } else {
            out.copy(outcome = "", lostReason = "", closedOn = "")
        }
        return out.copy(local = true)
    }

    /** Поля операции — в дело. Та же механика, что у триггера сервера: «жду с» ставится само. */
    fun applyFields(t: Task, f: JSONObject, today: String, nowIso: String): Task {
        var out = t
        for (k in f.keys()) {
            out = when (k) {
                "title" -> out.copy(title = f.str(k))
                "notes" -> out.copy(notes = f.str(k))
                "project_id" -> out.copy(projectId = f.str(k), dealId = if (f.str(k) != out.projectId && !f.has("deal_id")) "" else out.dealId)
                "deal_id" -> out.copy(dealId = f.str(k))
                "owner_id" -> out.copy(ownerId = f.str(k).ifBlank { out.ownerId })
                // «owed» в мяче — так группу называет автоматика: сервер пишет mine + флаг (`store._ball_norm`).
                "ball" -> f.str(k).ifBlank { MINE }.let { b -> if (b == OWED) out.copy(ball = MINE, owed = true) else out.copy(ball = b) }
                "owed" -> out.copy(owed = f.bool(k))
                "person_id" -> out.copy(personId = f.str(k))
                "nudge_on" -> out.copy(nudgeOn = f.str(k))
                "requested_by" -> out.copy(requestedBy = f.str(k))
                "due_date" -> out.copy(dueDate = f.str(k), dueTime = if (f.str(k).isBlank()) "" else out.dueTime)
                "due_time" -> out.copy(dueTime = f.str(k))
                // Новое напоминание — ещё не отправлено: сервер сбрасывает reminded_at так же.
                "remind_at" -> out.copy(remindAt = f.str(k), remindedAt = if (f.str(k) != out.remindAt) "" else out.remindedAt)
                "remind_place" -> out.copy(remindPlace = f.str(k), remindedAt = if (f.str(k) != out.remindPlace) "" else out.remindedAt)
                "estimate_min" -> out.copy(estimateMin = if (f.isNull(k)) 0 else f.optInt(k, 0))
                "money" -> out.copy(money = f.str(k))
                "want" -> out.copy(want = f.bool(k))
                "focus_on" -> out.copy(focusOn = f.str(k))
                "labels" -> out.copy(labels = f.strings(k))
                "status" -> {
                    val st = f.str(k).ifBlank { OPEN }
                    out.copy(status = st, completedAt = if (st == OPEN) "" else out.completedAt.ifBlank { nowIso })
                }
                "source" -> out.copy(source = f.str(k).ifBlank { out.source })
                "source_ref" -> out.copy(sourceRef = f.str(k))
                else -> out
            }
        }
        if (out.ball == WAITING && t.ball != WAITING && out.waitingSince.isBlank()) out = out.copy(waitingSince = today)
        if (out.ball == WAITING && out.waitingSince.isBlank()) out = out.copy(waitingSince = today)
        if (out.ball != WAITING) out = out.copy(waitingSince = "")
        // Мяч ушёл к человеку — «отбить» гаснет сам (триггер `owed` сервера).
        if (out.ball == WAITING) out = out.copy(owed = false)
        return out.copy(updatedAt = nowIso)
    }

    // ------------------------------------------------------------ операции

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Постоянный uuid из семени: тот же вход — тот же id. Для операций, у
     * которых ключ выводится, а не хранится (отмена дела Разноски). На
     * сервере `op_id` — uuid: строка другой формы роняет всю пачку (500), и
     * очередь встаёт навсегда.
     */
    fun stableId(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    /** Похоже ли на uuid — то, что сервер примет в op_id и id. */
    fun isUuid(s: String): Boolean = runCatching { UUID.fromString(s); s.length == 36 }.getOrDefault(false)

    /**
     * Поля дела, которые правит карточка телефона: остальное — имена и служебное.
     * Денег дела (`money`) здесь нет с 05.10.2026: владелец убрал их из
     * интерфейса («тяжело смотреть, нагружает»). Поле в данных осталось, но
     * карточка его не показывает — значит, и `task.set` не должен уметь его
     * тронуть: невидимая правка денег читалась бы как поломка.
     *
     * Напоминание (`remind_at`, `remind_place`, 06.10.2026) правится, только
     * когда сервер его знает (`Snapshot.remindOn`): старый сервер отвергает
     * операцию с незнакомым полем целиком. Карточка без этого флага блока
     * напоминания не показывает, и поля в `task.set` не попадают.
     */
    val EDITABLE = listOf(
        "title", "notes", "project_id", "deal_id", "ball", "person_id", "nudge_on", "requested_by",
        "due_date", "due_time", "remind_at", "remind_place", "estimate_min", "want", "focus_on", "labels", "owed",
    )

    /** Что сервер умеет сверх контракта части 1 — `features` ответа синка. */
    const val FEATURE_REMIND = "remind"

    /**
     * Три группы (10.10.2026): сервер знает поле `owed`. Без этой фичи телефон
     * `owed` не шлёт — старый сервер отверг бы операцию целиком, как с `remind_*`.
     */
    const val FEATURE_GROUPS = "groups"

    /** Мяч «owed» — группа «Отбить» одним полем: так её называет автоматика, сервер пишет mine + `owed`. */
    const val OWED = "owed"

    /** Что из [add] ещё нет в [have] (по [norm]: регистр, ё, форма номера не важны) — без повторов. */
    fun addNew(have: List<String>, add: List<String>, norm: (String) -> String): List<String> {
        val seen = have.filter { it.isNotBlank() }.map(norm).toHashSet()
        return add.map { it.trim() }.filter { it.isNotEmpty() && seen.add(norm(it)) }
    }

    fun normName(s: String): String = s.trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

    /** Наговорки: `dictation.add` и вид `dictations` (06.10.2026, docs/dela-phone-3.md). */
    const val FEATURE_DICTATIONS = "dictations"

    /**
     * Поля нового дела: карточкины плюс деньги. Деньги ставит только Разноска —
     * тот же промпт, что у разбора сервера (`TASKS_DELA`), который их по-прежнему
     * пишет: данные не трогаем, только не показываем.
     */
    private val CREATE_FIELDS = EDITABLE + "money"

    /**
     * Время срока «9:30», «930», «18.00» — в «09:30»; не время — null. Сервер
     * хранит его как `time` и отдаёт «09:30:00»: сравниваем первые пять знаков.
     */
    fun normTime(s: String): String? {
        val m = Regex("^\\s*(\\d{1,2})[:.\\s]?(\\d{2})\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        return String.format(java.util.Locale.ROOT, "%02d:%02d", h, mi)
    }

    /** Значение поля дела так, как его ждёт сервер: пусто — null. */
    fun field(t: Task, key: String): Any = when (key) {
        "title" -> t.title
        "notes" -> nul(t.notes)
        "project_id" -> nul(t.projectId)
        "deal_id" -> nul(t.dealId)
        "ball" -> t.ball
        "person_id" -> nul(t.personId)
        "nudge_on" -> nul(t.nudgeOn)
        "requested_by" -> nul(t.requestedBy)
        "due_date" -> nul(t.dueDate)
        "due_time" -> nul(t.dueTime)
        "remind_at" -> nul(t.remindAt)
        "remind_place" -> nul(t.remindPlace)
        "estimate_min" -> if (t.estimateMin > 0) t.estimateMin else JSONObject.NULL
        "money" -> nul(t.money)
        "want" -> t.want
        "focus_on" -> nul(t.focusOn)
        "labels" -> arr(t.labels)
        "owed" -> t.owed
        else -> JSONObject.NULL
    }

    private fun same(a: Any, b: Any): Boolean = a.toString() == b.toString()

    /** Что поменялось между тем, что телефон видел, и тем, что сохранил: set и was. */
    fun diff(before: Task, after: Task): Pair<JSONObject, JSONObject> {
        val set = JSONObject()
        val was = JSONObject()
        for (k in EDITABLE) {
            val a = field(before, k)
            val b = field(after, k)
            if (!same(a, b)) {
                set.put(k, b)
                was.put(k, a)
            }
        }
        return set to was
    }

    /**
     * Новое дело с телефона: id создаёт сам телефон (офлайн-создание, номер
     * придёт с ответом), op_id — его постоянный ключ: повтор пачки не плодит
     * дублей ни в очереди, ни на сервере.
     */
    fun createOp(t: Task, opId: String = newId()): JSONObject {
        val task = JSONObject().put("id", t.id).put("title", t.title.trim())
        for (k in CREATE_FIELDS) {
            if (k == "title") continue
            val v = field(t, k)
            if (v == JSONObject.NULL || (k == "want" && v == false) || (k == "owed" && v == false) || (k == "labels" && t.labels.isEmpty())) continue
            if (k == "ball" && v == MINE) continue
            task.put(k, v)
        }
        if (t.source.isNotBlank()) task.put("source", t.source)
        if (t.sourceRef.isNotBlank()) task.put("source_ref", t.sourceRef)
        return JSONObject().put("op", "task.create").put("op_id", opId).put("task", task)
    }

    fun setOp(id: String, set: JSONObject, was: JSONObject, opId: String = newId()): JSONObject =
        JSONObject().put("op", "task.set").put("op_id", opId).put("id", id).put("set", set).put("was", was)

    fun statusOp(op: String, id: String, opId: String = newId()): JSONObject =
        JSONObject().put("op", op).put("op_id", opId).put("id", id)

    fun commentOp(taskId: String, text: String, commentId: String = newId(), opId: String = newId()): JSONObject =
        JSONObject().put("op", "comment.add").put("op_id", opId)
            .put("comment", JSONObject().put("id", commentId).put("task_id", taskId).put("text", text.trim()))

    fun decideOp(id: String, accept: Boolean, reason: String = "", set: JSONObject? = null, opId: String = newId()): JSONObject =
        JSONObject().put("op", "suggestion.decide").put("op_id", opId).put("id", id)
            .put("decision", if (accept) "accept" else "reject")
            .apply {
                if (reason.isNotBlank()) put("reason", reason.trim())
                if (set != null && set.length() > 0) put("set", set)
            }

    /**
     * Наговорка, из которой вышли дела (06.10.2026, docs/dela-phone-3.md): текст
     * дословно — то, что ушло в разбор, не чистка. [ref] — ровно `source_ref` её
     * дел (`raznoska:<черновик>`): по нему «Новое» показывает, что сказано и куда
     * что попало. Повтор того же id сервер не переписывает.
     */
    fun dictationOp(ref: String, text: String, atIso: String, opId: String): JSONObject =
        JSONObject().put("op", "dictation.add").put("op_id", opId).put(
            "dictation",
            JSONObject().put("id", ref).put("text", text.trim()).put("at", atIso).put("source", "phone"),
        )

    /** «Понятно» у закрытого само: видел, согласен — из «Нового» уходит (`seen_at`). */
    fun seenOp(ids: List<String>, opId: String = newId()): JSONObject =
        JSONObject().put("op", "suggestion.seen").put("op_id", opId).put("ids", arr(ids))

    /**
     * «Не дела» из Разноски — в хронологию CRM заметкой (`interaction.add`,
     * kind = note), а не в поле, которое никто не видит.
     */
    fun noteOp(
        id: String,
        text: String,
        atIso: String,
        projectId: String,
        personIds: List<String>,
        sourceRef: String,
        opId: String,
    ): JSONObject {
        val data = JSONObject().put("id", id).put("at", atIso).put("kind", "note").put("summary", text.trim())
            .put("source", "raznoska").put("person_ids", arr(personIds))
        if (projectId.isNotBlank()) data.put("project_id", projectId)
        if (sourceRef.isNotBlank()) data.put("source_ref", sourceRef)
        return JSONObject().put("op", "interaction.add").put("op_id", opId).put("data", data)
    }

    /** Операция словами — для журнала и для «сервер не принял: …». */
    fun describe(op: JSONObject, s: Snapshot): String {
        fun taskName(ref: String): String = s.task(ref)?.let { "«${it.title.take(60)}»" } ?: "дело"
        return when (op.str("op")) {
            "task.create" -> "новое дело «${op.optJSONObject("task")?.str("title").orEmpty().take(60)}»"
            "task.set" -> "правка: ${taskName(op.str("id"))}"
            "task.done" -> "закрыть ${taskName(op.str("id"))}"
            "task.reopen" -> "вернуть ${taskName(op.str("id"))}"
            "task.cancel" -> "отменить ${taskName(op.str("id"))}"
            "comment.add" -> "комментарий к ${taskName(op.optJSONObject("comment")?.str("task_id").orEmpty())}"
            "comment.delete" -> "убрать комментарий"
            "suggestion.decide" -> (if (op.str("decision") == "reject") "отклонить" else "принять") + " предложение"
            "suggestion.seen" -> "«понятно» у закрытого само"
            "dictation.add" -> "текст наговорки"
            "interaction.add" -> "заметка в хронологию"
            "interaction.delete" -> "убрать запись хронологии"
            "deal.set" -> "сделка «${s.deals[op.str("id")]?.name?.take(60) ?: "?"}»"
            "payment.set" -> "оплата по сделке «${s.payments[op.str("id")]?.let { s.deals[it.dealId]?.name }?.take(60) ?: "?"}»"
            "person.set" -> "человек «${s.people[op.str("id")]?.label ?: "?"}»"
            "person.add" -> "имена и номера человека «${s.people[op.str("id")]?.label ?: "?"}»"
            "person.merge" -> "слить «${s.people[op.str("id")]?.label ?: "?"}» с «${s.people[op.str("into")]?.label ?: "?"}»"
            "svod.set" -> "запись Свода «${op.str("key")}»"
            "person.create" -> "новый человек «${op.optJSONObject("data")?.str("name").orEmpty().take(60)}»"
            "org.create" -> "организация «${op.optJSONObject("data")?.str("name").orEmpty().take(60)}»"
            "project.set" -> "проект «${s.projects[op.str("id")]?.name?.take(60) ?: "?"}»"
            else -> op.str("op")
        }
    }

    // ------------------------------------------------------------ виды

    /** Сфера дела в переключателе: «Входящие» — и в Работе, и в Доме. */
    fun inSphere(t: Task, sphere: String): Boolean =
        sphere == "all" || sphere.isBlank() || t.sphere == sphere || t.sphere == INBOX

    /**
     * Порядок: срок (пустой — в конец), время, давнее выше. С 05.10.2026 —
     * как в вебе, без «оплаченное выше»: деньги дела владелец убрал из
     * интерфейса, и порядок по ним читался бы как необъяснимая перестановка.
     */
    val ORDER: Comparator<Task> = compareBy<Task>({ it.dueDate.isBlank() }, { it.dueDate }, { it.dueTime.isBlank() }, { it.dueTime })
        .thenBy { it.createdAt }

    /** «Утро» — как в вебе: «сейчас», сегодня и просрочено, напомнить, от других. Раздела «оплачено, без срока» нет с 05.10.2026. */
    data class Morning(
        val now: List<Task>,
        val today: List<Task>,
        val nudge: List<Task>,
        val fromOthers: List<Task>,
        val newCount: Int,
    ) {
        val total: Int get() = now.size + today.size + nudge.size + fromOthers.size
    }

    private fun mineOpen(s: Snapshot, me: String, sphere: String): List<Task> =
        s.tasks.values.filter { it.open && (me.isBlank() || it.ownerId == me) && inSphere(it, sphere) }

    private fun le(date: String, today: String): Boolean = date.isNotBlank() && date <= today

    private fun ms(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

    private fun minusDays(today: String, n: Long): String =
        runCatching { LocalDate.parse(today).minusDays(n).toString() }.getOrDefault("")

    /**
     * «Утро» — `view_morning`. Дело попадает в первый подходящий раздел и
     * ниже не повторяется: на телефоне повтор читается как два дела.
     */
    fun morning(s: Snapshot, me: String, today: String, sphere: String, now: Long): Morning {
        val open = mineOpen(s, me, sphere)
        val seen = HashSet<String>()
        fun take(p: (Task) -> Boolean): List<Task> = open.filter { it.id !in seen && p(it) }.sortedWith(ORDER).also { l -> l.forEach { seen += it.id } }
        val nowList = take { it.focusOn == today }
        val todayList = take { it.ball == MINE && le(it.dueDate, today) }
        val nudge = take { it.ball == WAITING && (le(it.nudgeOn, today) || le(it.dueDate, today)) }
        val others = take { it.createdBy.isNotBlank() && it.createdBy != it.ownerId && (ms(it.createdAt) ?: 0L) > now - 3 * DAY_MS }
        return Morning(nowList, todayList, nudge, others, newOnes(s, me).size)
    }

    /** Неразобранные предложения владельцу — «Новое». Счётчик — только они. */
    fun newOnes(s: Snapshot, me: String): List<Suggestion> =
        s.suggestions.values.filter { it.pending && (me.isBlank() || it.forUser == me) }

    /**
     * Пачка «Нового»: одна встреча или одно окно дайджеста. `source` — откуда
     * (встреча, Telegram): значок в заголовке, `at` — дата встречи для порядка.
     */
    data class Batch(val ref: String, val title: String, val items: List<Suggestion>, val source: String = "", val at: String = "")

    /** «Новое» пачками, как в вебе: по дате встречи, свежие сверху; внутри — по времени появления. */
    fun newBatches(s: Snapshot, me: String): List<Batch> {
        val rows = newOnes(s, me).sortedBy { it.createdAt }
        val out = LinkedHashMap<String, MutableList<Suggestion>>()
        for (r in rows) out.getOrPut(r.batchRef.ifBlank { "one:" + r.id }) { mutableListOf() } += r
        return out.map { (k, items) ->
            Batch(
                ref = if (k.startsWith("one:")) "" else k,
                title = items.firstOrNull { it.batchTitle.isNotBlank() }?.batchTitle.orEmpty(),
                items = items,
                source = items.first().source,
                at = items.maxOf { it.at },
            )
        }.sortedByDescending { it.at }
    }

    /** Причины решения, которые ставит сам сервер (`store.AUTO_REASONS`). */
    val AUTO_REASONS = setOf("закрыто само", "уточнено само")

    /**
     * «Сделано само» за [days] дней, свежие сверху (`autoDone` веба): синк
     * приносит эти строки (accepted), раньше телефон их просто не показывал.
     * «Понятно» (`seen_at`) убирает их совсем: 06.10.2026 #268 висел неделю.
     */
    fun autoDone(s: Snapshot, me: String, now: Long, days: Int = 7): List<Suggestion> =
        s.suggestions.values.filter {
            it.autoDone && it.seenAt.isBlank() && (me.isBlank() || it.forUser == me) && (ms(it.decidedAt) ?: 0L) > now - days * DAY_MS
        }.sortedByDescending { ms(it.decidedAt) ?: 0L }

    /**
     * «Вернуть» у сделанного само (`autoDone` веба), одной пачкой: закрытие —
     * `task.reopen`, уточнение — `task.set` с `result.was` (как было); у обоих —
     * `comment.delete` комментария-основания, если сервер его записал
     * (`result.comment_id`), и [seen] — `suggestion.seen`. null — вернуть нечего:
     * дело не видно, закрытое уже открыли, у уточнения ни `was`, ни комментария.
     */
    fun autoUndoOps(sg: Suggestion, t: Task?, seen: Boolean): List<JSONObject>? {
        if (t == null) return null
        val r = sg.resultObj()
        val was = r.optJSONObject("was") ?: JSONObject()
        val comment = r.str("comment_id")
        val list = mutableListOf<JSONObject>()
        if (sg.kind == "close") {
            if (t.status != DONE) return null
            list += statusOp("task.reopen", t.id)
        } else {
            if (was.length() == 0 && comment.isBlank()) return null
            if (was.length() > 0) {
                // Что стоит сейчас — в `was` операции: правку поверх своей сервер не затрёт молча.
                val now = json(t)
                val cur = JSONObject().apply { for (k in was.keys()) put(k, now.opt(k) ?: JSONObject.NULL) }
                list += setOp(t.id, JSONObject(was.toString()), cur)
            }
        }
        if (comment.isNotBlank()) list += JSONObject().put("op", "comment.delete").put("op_id", newId()).put("id", comment)
        if (seen) list += seenOp(listOf(sg.id))
        return list
    }

    /**
     * «Уточнить: #N …» — что именно поменяется в деле, по полям предложения
     * `update`: новое название, срок «было → стало», мяч с человеком (имя —
     * как сказала автоматика: сервер сам найдёт человека при принятии) и
     * отдельно подробности (`note`), которые лягут комментарием к делу.
     */
    data class Update(
        val task: Task?,
        val title: String,
        val dueFrom: String,
        val dueTo: String,
        val ball: String,
        val person: String,
        val note: String,
    ) {
        val empty: Boolean get() = title.isBlank() && dueTo.isBlank() && ball.isBlank() && person.isBlank() && note.isBlank()
    }

    fun update(sg: Suggestion, s: Snapshot): Update {
        val p = sg.payloadObj()
        val t = s.tasks[sg.taskId]
        return Update(
            task = t,
            title = p.str("title"),
            dueFrom = if (p.str("due_date").isNotBlank()) t?.dueDate.orEmpty() else "",
            dueTo = p.str("due_date"),
            ball = p.str("ball"),
            person = p.str("person_name"),
            note = p.str("note"),
        )
    }

    /** Видна ли человеку CRM: владелец по роли или тот, кому открыты клиенты (`clients ≠ own`). */
    fun crmOn(s: Snapshot, me: String): Boolean = s.users[me]?.let { it.owner || it.clients != "own" } ?: false

    /** Открыты ли деньги фирмы (гонорары, оплаты): `sees_money` или владелец. Сервер у остальных отдаёт null. */
    fun moneyOn(s: Snapshot, me: String): Boolean = s.users[me]?.let { it.owner || it.seesMoney } ?: false

    /** Стадии воронки по порядку; archive — закрытые (с итогом). */
    val STAGES = listOf("lead", "proposal", "mandate", "active", "closing", "archive")
    val OPEN_STAGES = STAGES.dropLast(1)

    data class Waiting(val personId: String, val person: String, val items: List<Task>)

    /** «Жду» по людям (`view_waiting`): у кого мяч и с какого дня. */
    fun waiting(s: Snapshot, me: String, sphere: String): List<Waiting> {
        val rows = mineOpen(s, me, sphere).filter { it.ball == WAITING }
            .sortedWith(compareBy<Task>({ it.who.isBlank() }, { it.who.lowercase() }, { it.waitingSince.isBlank() }, { it.waitingSince }))
        val out = LinkedHashMap<String, MutableList<Task>>()
        for (t in rows) out.getOrPut(t.personId.ifBlank { "-" }) { mutableListOf() } += t
        return out.map { (k, items) ->
            Waiting(if (k == "-") "" else k, items.first().who.ifBlank { "без человека" }, items)
        }
    }

    data class PersonView(
        val person: Person?,
        val agenda: List<Task>,
        val waiting: List<Task>,
        val asked: List<Task>,
        val mineAbout: List<Task>,
    )

    /** «По человеку» (`view_person`): поднять при встрече, жду, просил, моё про него. */
    fun person(s: Snapshot, personId: String): PersonView {
        val open = s.tasks.values.filter { it.open }
        return PersonView(
            s.people[personId],
            open.filter { it.ball == AGENDA && it.personId == personId }.sortedWith(ORDER),
            open.filter { it.ball == WAITING && it.personId == personId }.sortedWith(ORDER),
            open.filter { it.requestedBy == personId }.sortedWith(ORDER),
            open.filter { it.ball == MINE && it.personId == personId }.sortedWith(ORDER),
        )
    }

    /** «Быстрое» (`view_quick`): моё на десять минут. */
    fun quick(s: Snapshot, me: String, sphere: String): List<Task> =
        mineOpen(s, me, sphere).filter { it.ball == MINE && it.estimateMin in 1..10 }.sortedWith(ORDER)

    data class ProjectView(val project: Project?, val deals: List<Deal>, val open: List<Task>, val done: List<Task>)

    /** «Проект» (`view_project`): открытое, сделки и тридцать последних закрытых. */
    fun project(s: Snapshot, projectId: String): ProjectView {
        val all = s.tasks.values.filter { it.projectId == projectId }
        return ProjectView(
            s.projects[projectId],
            s.dealsOf(projectId),
            all.filter { it.open }.sortedWith(ORDER),
            all.filter { !it.open }.sortedByDescending { it.completedAt }.take(30),
        )
    }

    data class Week(
        val stale: List<Task>,
        val waitingStale: List<Task>,
        val noNextStep: List<Project>,
        val expired: List<Suggestion>,
    )

    /**
     * «Неделя» (`view_week`): залежалое, молчащие «жду», деньги без следующего
     * шага и то, что погасло в «Новом» неразобранным. Условие отказа от
     * Todoist — неделя только на Делах.
     */
    fun week(s: Snapshot, me: String, today: String, sphere: String, now: Long): Week {
        val open = mineOpen(s, me, sphere)
        val twoWeeks = minusDays(today, 14)
        val oneWeek = minusDays(today, 7)
        val stale = open.filter {
            (it.dueDate.isNotBlank() && twoWeeks.isNotBlank() && it.dueDate < twoWeeks) ||
                ((ms(it.updatedAt) ?: Long.MAX_VALUE) < now - 21 * DAY_MS)
        }.sortedWith(ORDER)
        val waitingStale = open.filter {
            it.ball == WAITING && it.waitingSince.isNotBlank() && oneWeek.isNotBlank() && it.waitingSince < oneWeek &&
                (it.nudgeOn.isBlank() || it.nudgeOn < today)
        }.sortedWith(ORDER)
        val withNext = s.tasks.values.filter { it.open && it.ball == MINE }.map { it.projectId }.toSet()
        val noNext = s.projects.values.filter {
            it.live && (me.isBlank() || it.ownerId == me) && it.moneyDefault in setOf("paid", "potential") && it.id !in withNext &&
                (sphere == "all" || sphere.isBlank() || it.sphere == sphere)
        }.sortedWith(compareBy<Project>({ it.moneyDefault }, { it.name.lowercase() }))
        val expired = s.suggestions.values.filter {
            (me.isBlank() || it.forUser == me) && it.status == "expired" && (ms(it.decidedAt) ?: 0L) > now - 7 * DAY_MS
        }
        return Week(stale, waitingStale, noNext, expired)
    }

    /** Поиск по названию и заметкам, без регистра и «ё». */
    fun search(s: Snapshot, q: String, withClosed: Boolean = false): List<Task> {
        val n = norm(q) ?: return emptyList()
        return s.tasks.values.filter { (withClosed || it.open) && (norm(it.title + " " + it.notes)?.contains(n) == true) }
            .sortedWith(compareBy<Task> { !it.open }.then(ORDER)).take(50)
    }

    /** Проект строкой списка: сколько в нём открытых дел и есть ли просроченное. */
    data class ProjectRow(val project: Project, val open: Int, val late: Boolean)

    /**
     * «Проекты» — как боковая панель веба: избранные сверху, дальше по видам
     * (клиенты, внутреннее, личное), «Люди» — с кем больше всего открытых дел,
     * и архив. Число — открытые дела проекта (все, не только мои: так в вебе),
     * просрочка — флагом: на экране она красит число, а не ставит вторую точку.
     * Сфера отбирает живые проекты; люди и архив — без неё, как в вебе.
     */
    data class ProjectsNav(
        val inbox: Int,
        val inboxLate: Boolean,
        val favorites: List<ProjectRow>,
        val groups: List<Pair<String, List<ProjectRow>>>,
        val people: List<Pair<Person, Int>>,
        val archived: List<ProjectRow>,
    )

    val KINDS = listOf("client" to "Клиенты", "internal" to "Внутреннее", "personal" to "Личное")

    fun projectsNav(s: Snapshot, me: String, today: String, sphere: String, favorites: Set<String>): ProjectsNav {
        val open = s.tasks.values.filter { it.open }
        val openBy = open.filter { it.projectId.isNotBlank() }.groupingBy { it.projectId }.eachCount()
        val lateBy = open.filter { it.projectId.isNotBlank() && it.dueDate.isNotBlank() && it.dueDate < today }.map { it.projectId }.toSet()
        fun row(p: Project) = ProjectRow(p, openBy[p.id] ?: 0, p.id in lateBy)
        val live = s.projects.values.filter { it.live && (sphere == "all" || sphere.isBlank() || it.sphere == sphere) }
            .sortedBy { it.name.lowercase() }
        val favs = live.filter { it.id in favorites }
        // Клиенты — по свежести дел (09.10.2026, `freshBy` веба): чьё дело заведено или закрыто последним — сверху.
        val fresh = HashMap<String, String>()
        for (t in s.tasks.values) {
            if (t.projectId.isBlank()) continue
            val x = maxOf(t.createdAt, t.completedAt)
            if (x > (fresh[t.projectId] ?: "")) fresh[t.projectId] = x
        }
        val groups = KINDS.map { (kind, title) ->
            val list = live.filter { it.kind == kind && it.id !in favorites }
            title to (if (kind == "client") list.sortedByDescending { fresh[it.id] ?: "" } else list).map(::row)
        }
            .filter { it.second.isNotEmpty() }
        // Проект незнакомого вида не теряется — отдельной группой «Другое».
        val known = KINDS.map { it.first }.toSet()
        val odd = live.filter { it.kind !in known && it.id !in favorites }.map(::row)
        val withOdd = if (odd.isEmpty()) groups else groups + ("Другое" to odd)
        val people = open.filter { it.personId.isNotBlank() }.groupingBy { it.personId }.eachCount()
            .mapNotNull { (id, n) -> s.people[id]?.takeIf { it.live }?.let { it to n } }
            .sortedWith(compareByDescending<Pair<Person, Int>> { it.second }.thenBy { it.first.label.lowercase() })
        val inbox = mineOpen(s, me, "all").filter { it.projectId.isBlank() }
        return ProjectsNav(
            inbox = inbox.size,
            inboxLate = inbox.any { it.dueDate.isNotBlank() && it.dueDate < today },
            favorites = favs.map(::row),
            groups = withOdd,
            people = people,
            archived = s.projects.values.filter { !it.live }.sortedBy { it.name.lowercase() }.map(::row),
        )
    }

    /** Все открытые по проектам: «Входящие» первыми, дальше по имени — для тапа в Засечку. */
    fun byProject(s: Snapshot, me: String, sphere: String): List<Pair<Project?, List<Task>>> {
        val open = mineOpen(s, me, sphere)
        return open.groupBy { it.projectId }.toList()
            .sortedWith(compareBy<Pair<String, List<Task>>>({ it.first.isNotBlank() }, { s.projects[it.first]?.name?.lowercase() ?: it.second.first().projectName.lowercase() }))
            .map { (pid, list) -> s.projects[pid] to list.sortedWith(ORDER) }
    }

    private const val DAY_MS = 86_400_000L

    // ------------------------------------------------------------ справочник

    /** Та же нормализация, что `crm.norm` на сервере: регистр, «ё», пробелы. */
    fun norm(s: String?): String? =
        s?.trim()?.lowercase()?.replace('ё', 'е')?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    private fun named(n: String, name: String, aliases: List<String>, extra: String = ""): Boolean =
        n == norm(name) || (extra.isNotBlank() && n == norm(extra)) || aliases.any { norm(it) == n }

    /** Проект по имени или алиасу; не нашлось однозначно — однозначный префикс; иначе null. */
    fun findProject(s: Snapshot, text: String): Project? {
        val n = norm(text.trim().trim('#')) ?: return null
        val live = s.projects.values.filter { it.live }
        live.filter { named(n, it.name, it.aliases) }.let { if (it.size == 1) return it.first() }
        // Модель могла дописать хвост («Бета Групп / сделка») — берём голову.
        val head = norm(n.substringBefore(" / ").substringBefore('/'))
        if (head != null && head != n) live.filter { named(head, it.name, it.aliases) }.let { if (it.size == 1) return it.first() }
        return live.filter { p -> norm(p.name)?.startsWith(n) == true }.singleOrNull()
    }

    /** Человек по короткому имени, полному или алиасу. */
    fun findPerson(s: Snapshot, text: String): Person? {
        val n = norm(text.trim().trim('@')) ?: return null
        val live = s.people.values.filter { it.live }
        live.filter { named(n, it.name, it.aliases, it.short) }.let { if (it.size == 1) return it.first() }
        return live.filter { p -> norm(p.label)?.startsWith(n) == true || norm(p.name)?.startsWith(n) == true }.singleOrNull()
    }

    /**
     * Справочник для промпта Разноски: проекты с алиасами, люди с короткими
     * именами и метки — вместо живого каталога Todoist и команды, вписанной в
     * промпт руками. Только имена: что они значат, написано в самом промпте.
     */
    fun promptCatalog(s: Snapshot, maxPeople: Int = 400): String {
        val sb = StringBuilder()
        val projects = s.liveProjects()
        if (projects.isNotEmpty()) {
            sb.append("ПРОЕКТЫ (project — ровно одно имя из списка; в скобках — как ещё называют):\n")
            for (p in projects) {
                sb.append("— ").append(p.name)
                if (p.aliases.isNotEmpty()) sb.append(" (").append(p.aliases.joinToString(", ")).append(')')
                sb.append(" · ").append(if (p.sphere == "home") "дом" else "работа")
                if (p.kind == "client") sb.append(" · клиент")
                sb.append('\n')
            }
        }
        val people = s.livePeople().take(maxPeople)
        if (people.isNotEmpty()) {
            sb.append("\nЛЮДИ (person — короткое имя из списка; в скобках — полное и как ещё зовут):\n")
            for (p in people) {
                sb.append("— ").append(p.label)
                val more = listOfNotNull(p.name.takeIf { it != p.label && it.isNotBlank() }) + p.aliases
                if (more.isNotEmpty()) sb.append(" (").append(more.joinToString(", ")).append(')')
                sb.append('\n')
            }
        }
        if (s.labels.isNotEmpty()) {
            sb.append("\nМЕТКИ (только свободные контексты из этого списка, новых не выдумывать):\n")
            sb.append(s.labels.joinToString(", ")).append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * Предложение «create» — в черновик дела для карточки: поля задачи как
     * есть, имена (project_name, person_name) — в id по справочнику, как
     * `store._names` на сервере. Не нашлось — поле пустое, человек поправит.
     */
    fun draftOf(sg: Suggestion, s: Snapshot, me: String): Task {
        val p = sg.payloadObj()
        val base = s.tasks[sg.taskId]
        var t = base ?: Task(id = "suggestion:" + sg.id, title = "", ownerId = me, createdBy = me)
        val fields = JSONObject()
        for (k in EDITABLE) if (p.has(k)) fields.put(k, p.opt(k))
        t = applyFields(t, fields, s.today, t.updatedAt)
        if (t.projectId.isBlank()) findProject(s, p.str("project_name"))?.let { t = t.copy(projectId = it.id) }
        if (t.personId.isBlank()) findPerson(s, p.str("person_name"))?.let { t = t.copy(personId = it.id) }
        if (sg.kind == "close") t = t.copy(status = DONE)
        return deriveTask(t, s)
    }
}
