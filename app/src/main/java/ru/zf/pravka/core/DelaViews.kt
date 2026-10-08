package ru.zf.pravka.core

import java.text.Collator
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONObject
import ru.zf.pravka.core.Dela.str

/**
 * Виды Дел на телефоне — как в вебе (05.10.2026, владелец: «адаптировать
 * приложение так, чтобы оно показывало те же категории, людей, группы и т.п.,
 * что и в вебе»). Эталон — `server/pravka_dela/static/app.js`: разделы и их
 * числа (`VIEWS`), группировки (`GROUPS`, `grouped`), подпись строки
 * (`taskRow`, `dueLabel`), «Неделя», поиск, страница человека. Здесь то же
 * правило на копии телефона: вкладка открывается без сети, а читается так же,
 * как веб на ПК. Поправил веб — поправь здесь и тест `DelaViewsTest`.
 *
 * С 06.10.2026 (docs/dela-phone-3.md) «Утра» и «Входящих» нет — владелец:
 * «утро непонятно, что это такое… входящие — чем они отличаются от нового».
 * Первым — «Сейчас» (до пяти дел на сегодня), дела без проекта и поставленные
 * другими — в «Новом» рядом с наговорками и предложениями автоматики;
 * просроченное — наверху «Предстоящего», «пора напомнить» — наверху «Жду».
 */
object DelaViews {

    // ------------------------------------------------------------ разделы

    /** Разделы веба по порядку боковой панели (`VIEWS` веба). */
    enum class View(val key: String, val title: String) {
        NOW("now", "Сейчас"),
        NEW("new", "Новое"),
        UPCOMING("upcoming", "Предстоящее"),
        WAITING("waiting", "Жду"),
        WEEK("week", "Неделя"),
        ALL("all", "Все дела");

        companion object {
            /**
             * Ключ раздела, как маршрут веба: старые «Утро» и «Входящие» ведут туда,
             * где их дела теперь живут, — в «Сейчас» и в «Новое».
             */
            fun of(key: String): View = when (key) {
                "morning" -> NOW
                "inbox" -> NEW
                else -> entries.firstOrNull { it.key == key } ?: NOW
            }
        }
    }

    /**
     * «Сейчас» — дела, которые обязан сделать сегодня, не больше пяти (владелец,
     * 06.10.2026). Шестое не встаёт ни молнией, ни из карточки: веб и правка
     * словами на сервере (`ask.NOW_MAX`) держат то же число.
     */
    const val NOW_MAX = 5

    /** Ответ, когда шестое не встаёт: тот же у молнии и у карточки. */
    const val NOW_FULL = "В «Сейчас» уже $NOW_MAX — сначала убери одно"

    /** Подпись строки дела без проекта — она же кнопка выбора проекта (как в вебе). */
    const val NO_PROJECT = "без проекта"

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
    private fun ms(iso: String): Long = runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** Просрочено: открытое со сроком раньше сегодня (`isLate` веба). */
    fun isLate(t: Dela.Task, today: String): Boolean = t.open && t.dueDate.isNotBlank() && t.dueDate < today

    /** «Пора напомнить» (`nudgeDue` веба): мяч у человека, настал день напоминания или срок. */
    fun nudgeDue(t: Dela.Task, today: String): Boolean =
        t.ball == Dela.WAITING && (le(t.nudgeOn, today) || le(t.dueDate, today))

    /**
     * Дела «Сейчас» (`nowTasks` веба): мои открытые, отмеченные на сегодня, —
     * во всех сферах: пять на день, а не пять на сферу.
     */
    fun nowTasks(s: Dela.Snapshot, me: String, today: String): List<Dela.Task> =
        s.tasks.values.filter { it.open && mine(s, me, it) && isNow(it, today) }.sortedWith(Dela.ORDER)

    /**
     * Влезут ли [ids] в «Сейчас» (`nowRoom` веба): уже стоящие не считаются
     * дважды. false — было бы шестое; ответ владельцу — [NOW_FULL].
     */
    fun nowRoom(s: Dela.Snapshot, me: String, today: String, ids: Collection<String>): Boolean {
        val have = nowTasks(s, me, today).map { it.id }.toSet()
        return have.size + ids.toSet().count { it !in have } <= NOW_MAX
    }

    /** «Поставили другие» за три дня (`fromOthers` веба): моё, заведённое не мной. */
    fun fromOthers(s: Dela.Snapshot, me: String, sphere: String, now: Long): List<Dela.Task> =
        openMine(s, me, sphere).filter { it.createdBy.isNotBlank() && it.createdBy != it.ownerId && ms(it.createdAt) > now - 3 * 86_400_000L }
            .sortedWith(Dela.ORDER)

    /** Мои открытые без проекта — бывшие «Входящие», теперь в «Новом»: «Без проекта — куда их?». */
    fun noProject(s: Dela.Snapshot, me: String, sphere: String): List<Dela.Task> =
        openMine(s, me, sphere).filter { it.projectId.isBlank() }.sortedWith(Dela.ORDER)

    /**
     * Числа у разделов в меню — как `count()` у `VIEWS` веба. [upcomingHot] и
     * [waitingHot] — `hot()` веба: есть просроченное, пора кому-то напомнить.
     */
    data class Counts(
        val now: Int,
        val new: Int,
        val upcoming: Int,
        val waiting: Int,
        val upcomingHot: Boolean = false,
        val waitingHot: Boolean = false,
    ) {
        fun of(v: View): Int = when (v) {
            View.NOW -> now
            View.UPCOMING -> upcoming
            View.NEW -> new
            View.WAITING -> waiting
            else -> 0
        }

        fun hot(v: View): Boolean = (v == View.UPCOMING && upcomingHot) || (v == View.WAITING && waitingHot)

        /** Число раздела словами: у «Сейчас» — «3 из 5», у остальных — число или пусто. */
        fun label(v: View): String = when {
            v == View.NOW -> "$now из $NOW_MAX"
            of(v) > 0 -> of(v).toString()
            else -> ""
        }
    }

    fun counts(s: Dela.Snapshot, me: String, today: String, sphere: String, now: Long): Counts {
        val m = openMine(s, me, sphere)
        val week = plusDays(today, 7)
        return Counts(
            now = nowTasks(s, me, today).size,
            new = newCount(s, me, sphere, now),
            upcoming = m.count { it.dueDate.isNotBlank() && it.dueDate <= week },
            waiting = m.count { it.ball == Dela.WAITING },
            upcomingHot = m.any { isLate(it, today) },
            waitingHot = m.any { nudgeDue(it, today) },
        )
    }

    /**
     * Число «Нового» (`newCount` веба): предложения автоматики, закрытое само за
     * неделю, дела без проекта и поставленные другими — всё, что ждёт, чтобы его
     * разложили. Наговорки не считаются: их дела уже разложены.
     */
    fun newCount(s: Dela.Snapshot, me: String, sphere: String, now: Long): Int =
        Dela.newOnes(s, me).size + Dela.autoClosed(s, me, now).size + noProject(s, me, sphere).size + fromOthers(s, me, sphere, now).size

    /** Раздел страницы: заголовок веба и дела в его порядке. */
    data class Section(val title: String, val items: List<Dela.Task>)

    /**
     * «Сейчас» (`renderNow` веба): до пяти дел на сегодня — сначала они, потом
     * всё остальное. [pick] — моё просроченное и на сегодня, чего ещё нет в
     * «Сейчас» («Выбрать на сегодня»), [next] — моё на завтра, пока место
     * осталось. «Сейчас» — во всех сферах, выбирать — из своей.
     */
    data class Now(val now: List<Dela.Task>, val pick: List<Dela.Task>, val next: List<Dela.Task>) {
        val left: Int get() = (NOW_MAX - now.size).coerceAtLeast(0)
        val pickTitle: String get() = if (left > 0) "Выбрать на сегодня: просрочено и на сегодня" else "Ещё на сегодня и просрочено"
    }

    fun now(s: Dela.Snapshot, me: String, today: String, sphere: String): Now {
        val m = openMine(s, me, sphere).filter { !isNow(it, today) && it.ball == Dela.MINE }
        val nowList = nowTasks(s, me, today)
        val tomorrow = plusDays(today, 1)
        return Now(
            now = nowList,
            pick = m.filter { le(it.dueDate, today) }.sortedWith(Dela.ORDER),
            next = if (nowList.size < NOW_MAX) m.filter { it.dueDate == tomorrow }.sortedWith(Dela.ORDER) else emptyList(),
        )
    }

    /** «Предстоящее»: моё с датой (или и без), по датам; «Только мяч у меня» — без «жду» и «повестки». */
    fun upcoming(s: Dela.Snapshot, me: String, sphere: String, mineOnly: Boolean, withUndated: Boolean): List<Dela.Task> =
        openMine(s, me, sphere).filter { (!mineOnly || it.ball == Dela.MINE) && (withUndated || it.dueDate.isNotBlank()) }

    /** «Жду»: что должны другие — веб раскладывает по людям. */
    fun waiting(s: Dela.Snapshot, me: String, sphere: String): List<Dela.Task> =
        openMine(s, me, sphere).filter { it.ball == Dela.WAITING }

    /**
     * «Жду» веба: «Пора напомнить» первой группой (красным, как просрочка),
     * остальное — по людям.
     */
    fun waitingGroups(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Group> {
        val items = waiting(s, me, sphere)
        val due = items.filter { nudgeDue(it, today) }.sortedWith(Dela.ORDER)
        val head = if (due.isEmpty()) emptyList() else listOf(Group("0nudge", "Пора напомнить", late = true, items = due))
        return head + group(items.filter { !nudgeDue(it, today) }, By.PERSON, s, today)
    }

    /** «Все дела»: моё в сфере, с «Сделанными» по выбору. */
    fun list(s: Dela.Snapshot, me: String, sphere: String, showDone: Boolean): List<Dela.Task> =
        s.tasks.values.filter { mine(s, me, it) && Dela.inSphere(it, sphere) && (showDone || it.open) }

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
     * Поиск веба: каждое слово — где угодно в названии, заметках, имени проекта,
     * человека или сделки, в метках; без регистра и «ё».
     */
    fun search(s: Dela.Snapshot, q: String, showDone: Boolean): List<Dela.Task> {
        val words = words(q)
        if (words.isEmpty()) return emptyList()
        return s.tasks.values.filter { t ->
            (showDone || t.open) && hit(words, t.title, t.notes, s.projects[t.projectId]?.name.orEmpty(), s.people[t.personId]?.label.orEmpty(),
                s.people[t.personId]?.name.orEmpty(), s.deals[t.dealId]?.name.orEmpty(), t.labels.joinToString(" "))
        }
    }

    private fun words(q: String): List<String> = (Dela.norm(q) ?: "").split(' ').filter { it.isNotBlank() }
    private fun hit(words: List<String>, vararg parts: String): Boolean {
        val hay = Dela.norm(parts.filter { it.isNotBlank() }.joinToString(" ")).orEmpty()
        return words.isNotEmpty() && words.all { hay.contains(it) }
    }

    /** Сколько строк в группе находок — дальше «и ещё N — уточни запрос» (`SEARCH_SHOWN` веба). */
    const val SEARCH_SHOWN = 8

    /**
     * Находки общего поиска (06.10.2026, docs/dela-phone-4.md, `renderSearch`
     * веба): клиенты и разделы (проекты справочника), проекты клиентов (сделки),
     * люди (имя, должность, компания) и дела. Всё — из копии, на каждую букву,
     * без сети; каждое слово запроса должно найтись.
     */
    data class Found(
        val projects: List<Dela.Project>,
        val deals: List<Dela.Deal>,
        val people: List<Dela.Person>,
        val tasks: List<Dela.Task>,
    ) {
        val total: Int get() = projects.size + deals.size + people.size + tasks.size
    }

    fun searchAll(s: Dela.Snapshot, q: String, showDone: Boolean): Found {
        val w = words(q)
        if (w.isEmpty()) return Found(emptyList(), emptyList(), emptyList(), emptyList())
        val projects = s.projects.values.filter { hit(w, it.name, it.aliases.joinToString(" "), it.note) }
            .sortedWith(compareBy<Dela.Project>({ !it.live }, { it.name.lowercase() }))
        val deals = s.deals.values.filter { hit(w, it.name, s.projects[it.projectId]?.name.orEmpty(), it.dealType) }
            .sortedWith(compareBy<Dela.Deal>({ it.closed }, { it.name.lowercase() }))
        val people = s.people.values.filter {
            it.live && hit(w, it.name, it.short, it.aliases.joinToString(" "), it.role, DelaCrm.orgLabel(s, it.orgId)?.name.orEmpty())
        }.sortedBy { it.name.lowercase() }
        return Found(projects, deals, people, search(s, q, showDone))
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
     * Следующая неделя, Позже, Без даты, Сделано), проектам («Без проекта»
     * первыми, заголовок ведёт в «Новое»), людям, мячу, сделкам или одним
     * списком. Порядок групп — по ключу, как у веба; дела внутри — в порядке срока.
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
                    if (p != null) put("1" + p.name, p.name, t, projectId = p.id) else put("0", "Без проекта", t, projectId = "")
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
     * датам, а просроченный — всегда), проект или «без проекта» (если не группа
     * по проектам и не страница проекта; строка рисует его кнопкой выбора
     * проекта), сделка, мяч с человеком и давностью «жду» («жду Иван · 3 дн.»)
     * или «@Иван», минуты, метки. Номер — последним.
     */
    fun chips(t: Dela.Task, by: By?, s: Dela.Snapshot, today: String, onProjectPage: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val d = due(t, today)
        if (d != null && (by != By.DATE || d.late)) out += d.text
        // Напоминание в Telegram (06.10.2026): «⏰ 11:00», «⏰ завтра 09:00», «⏰ дом».
        // В вебе такой подписи ещё нет — задание серверу (docs/dela-server-remind.md) её просит.
        date(today)?.let { day -> out += DelaRemind.chip(t, day, java.time.ZoneId.systemDefault()) }
        if (by != By.PROJECT && !onProjectPage) {
            out += if (t.projectId.isBlank()) NO_PROJECT else s.projects[t.projectId]?.name ?: t.projectName.ifBlank { "проект" }
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

    // ------------------------------------------------------------ наговорки

    /** Заметка наговорки в хронологию: что легло и к какому проекту. */
    data class DictNote(val summary: String, val projectId: String)

    /**
     * Наговорка в «Новом» (вид сервера `dictations`, 06.10.2026): что сказано
     * дословно ([text] пуст, если текст до сервера не дошёл — старый телефон),
     * её дела любого статуса, заметки в хронологию и [touches] — что потом про
     * эти дела сказала автоматика (встреча, Telegram): дело то же, оно срослось
     * и из наговорки не уходит.
     */
    data class Dictation(
        val ref: String,
        val at: String,
        val text: String,
        val source: String,
        val tasks: List<Dela.Task>,
        val notes: List<DictNote>,
        val touches: List<Dela.Suggestion>,
    )

    /** Ответ `GET /api/view/dictations` (`store.view_dictations`): `items[]`, свежие сверху. */
    fun dictations(o: JSONObject?): List<Dictation> {
        val a = o?.optJSONArray("items") ?: return emptyList()
        fun objs(x: JSONObject, key: String): List<JSONObject> {
            val arr = x.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        return (0 until a.length()).mapNotNull { i ->
            val d = a.optJSONObject(i) ?: return@mapNotNull null
            val ref = d.str("ref").ifBlank { return@mapNotNull null }
            Dictation(
                ref = ref,
                at = d.str("at"),
                text = d.str("text").trim(),
                source = d.str("source"),
                tasks = objs(d, "tasks").mapNotNull { Dela.task(it) },
                notes = objs(d, "notes").map { DictNote(it.str("summary"), it.str("project_id")) }.filter { it.summary.isNotBlank() },
                touches = objs(d, "touches").mapNotNull { Dela.suggestion(it) },
            )
        }
    }

    /**
     * Дела наговорки — из своей копии, если она их знает: там и только что
     * поставленная галка, и новое имя проекта. Не знает (чужая сфера, синк ещё не
     * дошёл) — как сказал сервер.
     */
    fun dictTasks(d: Dictation, s: Dela.Snapshot): List<Dela.Task> = d.tasks.map { s.tasks[it.id] ?: it }

    /** «сегодня 10:42», «вчера 18:05», «05.10 09:00» — когда сказано, как `whenWords` веба. */
    fun whenWords(iso: String, today: String, zone: ZoneId): String {
        val t = runCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone) }.getOrNull() ?: return ""
        val day = t.toLocalDate().toString()
        val hm = String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
        return when (days(day, today)) {
            0L -> "сегодня $hm"
            1L -> "вчера $hm"
            else -> ddmm(day, today) + " " + hm
        }
    }

    /** Откуда наговорка: «с телефона», «в вебе». */
    fun dictSource(source: String): String = when (source) {
        "phone" -> "с телефона"
        "web" -> "в вебе"
        else -> ""
    }

    private val SUG_FROM = mapOf("meeting" to "встреча", "telegram" to "Telegram", "userbot" to "Telegram", "mcp" to "Claude")

    /**
     * Что потом сказала про дело автоматика — словами, как `touchChips` веба:
     * «Бета, 05.10: закрыть — ждёт решения ниже». Статус — из своей копии, если
     * она знает предложение (там и только что принятое).
     */
    fun touchWord(sg: Dela.Suggestion, s: Dela.Snapshot): String {
        val live = s.suggestions[sg.id] ?: sg
        val from = live.batchTitle.ifBlank { SUG_FROM[live.source] ?: live.source }
        val what = if (live.kind == "close") "закрыть" else "уточнение"
        val st = when (live.status) {
            "pending" -> "ждёт решения ниже"
            "rejected" -> "отклонил"
            "expired" -> "погасло"
            else -> "принято"
        }
        return "$from: $what — $st"
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
