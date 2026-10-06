package ru.zf.pravka.core

import java.text.Collator
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Виды Дел на телефоне — как в вебе (05.10.2026, владелец: «адаптировать
 * приложение так, чтобы оно показывало те же категории, людей, группы и т.п.,
 * что и в вебе»). Эталон — `server/pravka_dela/static/app.js`: разделы и их
 * числа (`VIEWS`), группировки (`GROUPS`, `grouped`), подпись строки
 * (`taskRow`, `dueLabel`), «Неделя», поиск, страница человека. Здесь то же
 * правило на копии телефона: вкладка открывается без сети, а читается так же,
 * как веб на ПК. Поправил веб — поправь здесь и тест `DelaViewsTest`.
 */
object DelaViews {

    // ------------------------------------------------------------ разделы

    /** Разделы веба по порядку боковой панели. */
    enum class View(val key: String, val title: String) {
        MORNING("morning", "Утро"),
        UPCOMING("upcoming", "Предстоящее"),
        NEW("new", "Новое"),
        WAITING("waiting", "Жду"),
        WEEK("week", "Неделя"),
        INBOX("inbox", "Входящие"),
        ALL("all", "Все дела"),
    }

    /** Группировка списка — выбор над ним, как `groupPicker` веба. */
    enum class By(val key: String, val title: String) {
        DATE("date", "по датам"),
        PROJECT("project", "по проектам"),
        PERSON("person", "по людям"),
        BALL("ball", "по мячу"),
        DEAL("deal", "по сделкам"),
        NONE("none", "без групп");

        companion object {
            fun of(key: String, fallback: By): By = entries.firstOrNull { it.key == key } ?: fallback
        }
    }

    private fun mine(s: Dela.Snapshot, me: String, t: Dela.Task): Boolean = me.isBlank() || t.ownerId == me

    /** `openMine` веба: открытые мои в сфере («Входящие» — в любой). */
    fun openMine(s: Dela.Snapshot, me: String, sphere: String): List<Dela.Task> =
        s.tasks.values.filter { it.open && mine(s, me, it) && Dela.inSphere(it, sphere) }

    private fun isNow(t: Dela.Task, today: String) = t.focusOn == today
    private fun le(d: String, today: String) = d.isNotBlank() && d <= today

    /** Числа у разделов в меню — как `count()` у `VIEWS` веба. */
    data class Counts(val morning: Int, val upcoming: Int, val new: Int, val waiting: Int, val inbox: Int) {
        fun of(v: View): Int = when (v) {
            View.MORNING -> morning
            View.UPCOMING -> upcoming
            View.NEW -> new
            View.WAITING -> waiting
            View.INBOX -> inbox
            else -> 0
        }
    }

    fun counts(s: Dela.Snapshot, me: String, today: String, sphere: String): Counts {
        val m = openMine(s, me, sphere)
        val week = plusDays(today, 7)
        return Counts(
            morning = m.count { (it.ball == Dela.MINE && le(it.dueDate, today)) || isNow(it, today) },
            upcoming = m.count { it.dueDate.isNotBlank() && it.dueDate > today && it.dueDate <= week },
            new = Dela.newOnes(s, me).size,
            waiting = m.count { it.ball == Dela.WAITING },
            inbox = m.count { it.projectId.isBlank() },
        )
    }

    /** Раздел «Утра»: заголовок веба и дела в его порядке. */
    data class Section(val title: String, val items: List<Dela.Task>)

    /**
     * «Утро» веба: Сейчас · На сегодня и просроченное · Пора напомнить ·
     * Поставили другие. Как в вебе, дело в «Сейчас» в «сегодня» не повторяется;
     * остальные разделы друг друга не исключают.
     */
    fun morning(s: Dela.Snapshot, me: String, today: String, sphere: String, now: Long): List<Section> {
        val m = openMine(s, me, sphere)
        val from = now - 3 * 86_400_000L
        fun ms(iso: String) = runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)
        return listOf(
            Section("Сейчас", m.filter { isNow(it, today) }),
            Section("На сегодня и просроченное", m.filter { it.ball == Dela.MINE && le(it.dueDate, today) && !isNow(it, today) }),
            Section("Пора напомнить", m.filter { it.ball == Dela.WAITING && (le(it.nudgeOn, today) || le(it.dueDate, today)) }),
            Section("Поставили другие", m.filter { it.createdBy.isNotBlank() && it.createdBy != it.ownerId && ms(it.createdAt) > from }),
        ).map { it.copy(items = it.items.sortedWith(Dela.ORDER)) }
    }

    /** «Предстоящее»: моё с датой (или и без), по датам; «Только мяч у меня» — без «жду» и «повестки». */
    fun upcoming(s: Dela.Snapshot, me: String, sphere: String, mineOnly: Boolean, withUndated: Boolean): List<Dela.Task> =
        openMine(s, me, sphere).filter { (!mineOnly || it.ball == Dela.MINE) && (withUndated || it.dueDate.isNotBlank()) }

    /** «Жду»: что должны другие — веб раскладывает по людям. */
    fun waiting(s: Dela.Snapshot, me: String, sphere: String): List<Dela.Task> =
        openMine(s, me, sphere).filter { it.ball == Dela.WAITING }

    /** «Входящие» и «Все дела»: моё в сфере, с «Сделанными» по выбору. */
    fun list(s: Dela.Snapshot, me: String, sphere: String, inboxOnly: Boolean, showDone: Boolean): List<Dela.Task> =
        s.tasks.values.filter {
            mine(s, me, it) && Dela.inSphere(it, sphere) && (showDone || it.open) && (!inboxOnly || it.projectId.isBlank())
        }

    /** «Неделя» веба: три раздела с его заголовками. */
    data class Week(val stale: List<Dela.Task>, val waitStale: List<Dela.Task>, val noStep: List<Dela.Project>)

    fun week(s: Dela.Snapshot, me: String, today: String, sphere: String, now: Long): Week {
        val w = Dela.week(s, me, today, sphere, now)
        val busy = s.tasks.values.filter { it.open && it.ball == Dela.MINE && it.projectId.isNotBlank() }.map { it.projectId }.toSet()
        // Веб берёт проекты без следующего шага во всех сферах: отбор сферой — только у дел.
        val noStep = s.projects.values.filter {
            it.live && (me.isBlank() || it.ownerId == me) && it.moneyDefault in setOf("paid", "potential") && it.id !in busy
        }.sortedBy { it.name.lowercase() }
        return Week(w.stale, w.waitingStale, noStep)
    }

    /**
     * Поиск веба: каждое слово — где угодно в названии, заметках, имени проекта
     * или человека, без регистра и «ё».
     */
    fun search(s: Dela.Snapshot, q: String, showDone: Boolean): List<Dela.Task> {
        val words = (Dela.norm(q) ?: return emptyList()).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return s.tasks.values.filter { t ->
            val hay = Dela.norm(listOf(t.title, t.notes, s.projects[t.projectId]?.name.orEmpty(), s.people[t.personId]?.label.orEmpty()).joinToString(" ")).orEmpty()
            (showDone || t.open) && words.all { hay.contains(it) }
        }
    }

    // ------------------------------------------------------------ группы

    /** Группа списка: заголовок (и подпись дня), просрочка, куда ведёт заголовок. */
    data class Group(
        val key: String,
        val title: String,
        val sub: String = "",
        val late: Boolean = false,
        val items: List<Dela.Task>,
        val projectId: String? = null,
        val personId: String? = null,
    )

    private val collator: Collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }

    /**
     * `grouped` веба: по датам (Просрочено, Сегодня, Завтра, дни недели,
     * Следующая неделя, Позже, Без даты, Сделано), проектам («Входящие»
     * первыми), людям, мячу, сделкам или одним списком. Порядок групп — по
     * ключу, как у веба; дела внутри — в порядке срока.
     */
    fun group(items: List<Dela.Task>, by: By, s: Dela.Snapshot, today: String): List<Group> {
        val sorted = items.sortedWith(Dela.ORDER)
        if (by == By.NONE) return if (sorted.isEmpty()) emptyList() else listOf(Group("all", "", items = sorted))
        data class Bucket(val title: String, val sub: String, val late: Boolean, val projectId: String?, val personId: String?, val list: MutableList<Dela.Task>)
        val buckets = LinkedHashMap<String, Bucket>()
        fun put(key: String, title: String, t: Dela.Task, sub: String = "", late: Boolean = false, projectId: String? = null, personId: String? = null) {
            buckets.getOrPut(key) { Bucket(title, sub, late, projectId, personId, mutableListOf()) }.list += t
        }
        for (t in sorted) {
            when (by) {
                By.DATE -> when {
                    !t.open -> put("9done", "Сделано", t)
                    t.dueDate.isBlank() -> put("8none", "Без даты", t)
                    else -> {
                        val n = days(today, t.dueDate)
                        when {
                            n < 0 -> put("0late", "Просрочено", t, late = true)
                            n == 0L -> put("1today", "Сегодня", t, sub = longDate(today))
                            n == 1L -> put("2tomorrow", "Завтра", t, sub = longDate(t.dueDate))
                            n < 7 -> put("3" + t.dueDate, longDate(t.dueDate), t)
                            n < 14 -> put("5next", "Следующая неделя", t)
                            else -> put("6later", "Позже", t)
                        }
                    }
                }
                By.PROJECT -> {
                    val p = s.projects[t.projectId]
                    if (p != null) put("1" + p.name, p.name, t, projectId = p.id) else put("0", "Входящие", t, projectId = "")
                }
                By.PERSON -> {
                    val p = s.people[t.personId]
                    if (p != null) put("1" + p.label, p.label, t, personId = p.id) else put("2", "Без человека", t)
                }
                By.BALL -> when (t.ball) {
                    Dela.AGENDA -> put("2", "Повестка", t)
                    Dela.WAITING -> put("3", "Жду", t)
                    else -> put("1", "Моё", t)
                }
                By.DEAL -> {
                    val d = s.deals[t.dealId]
                    if (d != null) put("1" + d.name, d.name, t) else put("2", "Без сделки", t)
                }
                By.NONE -> Unit
            }
        }
        return buckets.entries.sortedWith { a, b -> collator.compare(a.key, b.key) }
            .map { (k, b) -> Group(k, b.title, b.sub, b.late, b.list.toList(), b.projectId, b.personId) }
    }

    // ------------------------------------------------------------ строка

    private val BALL = mapOf(Dela.MINE to "моё", Dela.WAITING to "жду", Dela.AGENDA to "повестка")

    /** Срок строкой, как `dueLabel` веба; [late] — просрочено (красным). */
    data class Due(val text: String, val late: Boolean, val today: Boolean)

    fun due(t: Dela.Task, today: String): Due? {
        if (t.dueDate.isBlank()) return null
        val n = days(today, t.dueDate)
        val time = if (t.dueTime.isNotBlank()) " " + t.dueTime.take(5) else ""
        return when {
            n < 0 && t.open -> Due("${ddmm(t.dueDate, today)} · ${-n} дн. назад", late = true, today = false)
            n == 0L -> Due("сегодня$time", late = false, today = true)
            n == 1L -> Due("завтра$time", late = false, today = false)
            n in 2..6 -> Due(WD_SHORT[weekday(t.dueDate)] + " " + ddmm(t.dueDate, today) + time, late = false, today = false)
            else -> Due(ddmm(t.dueDate, today) + time, late = false, today = false)
        }
    }

    /**
     * Подписи под названием — как метки строки веба: срок (если не группа по
     * датам, а просроченный — всегда), проект или «Входящие» (если не группа
     * по проектам и не страница проекта), сделка, мяч с человеком и давностью
     * «жду» («жду Иван · 3 дн.») или «@Иван», минуты, метки. Номер — последним.
     */
    fun chips(t: Dela.Task, by: By?, s: Dela.Snapshot, today: String, onProjectPage: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val d = due(t, today)
        if (d != null && (by != By.DATE || d.late)) out += d.text
        // Напоминание в Telegram (06.10.2026): «⏰ 11:00», «⏰ завтра 09:00», «⏰ дом».
        // В вебе такой подписи ещё нет — задание серверу (docs/dela-server-remind.md) её просит.
        date(today)?.let { day -> out += DelaRemind.chip(t, day, java.time.ZoneId.systemDefault()) }
        if (by != By.PROJECT && !onProjectPage) {
            out += if (t.projectId.isBlank()) "Входящие" else s.projects[t.projectId]?.name ?: t.projectName.ifBlank { "проект" }
        }
        val deal = s.deals[t.dealId]?.name ?: t.dealName.takeIf { t.dealId.isNotBlank() }
        if (!deal.isNullOrBlank() && by != By.DEAL) out += deal
        val who = s.people[t.personId]?.label ?: t.who.takeIf { t.personId.isNotBlank() }.orEmpty()
        if (t.ball != Dela.MINE && by != By.BALL) {
            val since = if (t.ball == Dela.WAITING && t.waitingSince.isNotBlank()) " · ${days(t.waitingSince, today)} дн." else ""
            out += (BALL[t.ball] ?: t.ball) + (if (who.isNotBlank()) " $who" else "") + since
        } else if (who.isNotBlank() && by != By.PERSON) {
            out += "@$who"
        }
        if (t.estimateMin > 0) out += "${t.estimateMin} мин"
        out += t.labels
        return out.filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------ человек

    /** Разделы страницы человека — заголовками веба. */
    fun personSections(v: Dela.PersonView): List<Section> = listOf(
        Section("Повестка с ним", v.agenda),
        Section("Жду от него", v.waiting),
        Section("Его просьбы ко мне", v.asked),
        Section("Моё о нём", v.mineAbout),
    )

    /** Строки карточки человека, как `renderPerson` веба: пустое не показывается. */
    fun personInfo(p: Dela.Person, s: Dela.Snapshot): List<Pair<String, String>> {
        val client = if (p.orgId.isNotBlank()) s.projects.values.firstOrNull { it.orgId == p.orgId } else null
        val bd = if (p.birthDay > 0 && p.birthMonth in 1..12) "${p.birthDay} ${MONTHS[p.birthMonth - 1]}" + (if (p.birthYear > 0) " ${p.birthYear}" else "") else ""
        return listOf(
            "Роль" to p.role,
            "Клиент" to client?.name.orEmpty(),
            "Телефон" to p.phones.joinToString(", "),
            "Почта" to p.emails.joinToString(", "),
            "Telegram" to p.telegram.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }.orEmpty(),
            "День рождения" to bd,
            "Как часто" to (PERSON_CADENCE[p.cadence] ?: ""),
            "Ищет" to p.seeks,
            "Предлагает" to p.offers,
            "Характер" to p.traits,
            "Откуда" to p.source,
            "Заметка" to p.note,
        ).filter { it.second.isNotBlank() }
    }

    /** «Как часто» на странице человека — словами веба (у него квартал — «раз в 2–3 месяца»). */
    private val PERSON_CADENCE = mapOf("month" to "раз в месяц", "quarter" to "раз в 2–3 месяца", "year" to "раз в год", "none" to "не видимся")

    // ------------------------------------------------------------ даты

    private val WD = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val WD_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    private fun date(iso: String): LocalDate? = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()

    /** Дней от [from] до [to] (`D.diff(to, from)` веба). */
    fun days(from: String, to: String): Long {
        val a = date(from) ?: return 0
        val b = date(to) ?: return 0
        return ChronoUnit.DAYS.between(a, b)
    }

    private fun plusDays(iso: String, n: Long): String = date(iso)?.plusDays(n)?.toString() ?: iso

    private fun weekday(iso: String): Int = (date(iso)?.dayOfWeek?.value ?: 1) - 1

    /** «07.10», чужой год — «07.10.27». */
    fun ddmm(iso: String, today: String): String = DelaAsk.ddmm(iso, today)

    /** «Понедельник, 5 октября» — подпись дня, как `D.long` веба. */
    fun longDate(iso: String): String {
        val d = date(iso) ?: return iso
        val wd = WD[d.dayOfWeek.value - 1]
        return wd.replaceFirstChar { it.uppercase() } + ", ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
    }
}
