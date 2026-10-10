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

    /**
     * Разделы веба (`VIEWS` веба). С 10.10.2026 их три (docs/dela-phone-11.md,
     * владелец: «не надо этих всех странных видов. Просто надо говорить: на
     * сегодня вот такие дела»): «Сегодня» — главный список тремя группами,
     * «Новое» — вопросы автоматики, «Все дела» — всё открытое.
     */
    enum class View(val key: String, val title: String) {
        TODAY("today", "Сегодня"),
        NEW("new", "Новое"),
        ALL("all", "Все дела");

        companion object {
            /**
             * Ключ раздела, как маршрут веба: «Сейчас», «Предстоящее», «Жду»,
             * «Неделя» и старое «Утро» ведут в «Сегодня», «Входящие» — в «Новое».
             */
            fun of(key: String): View = when (key) {
                "inbox" -> NEW
                else -> entries.firstOrNull { it.key == key } ?: TODAY
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

    /**
     * Группировка списка — выбор над ним, как `groupPicker` веба. «Группы»
     * («Отбить», «Запустить», «Мониторить», 10.10.2026) заменили «моё, жду,
     * повестка»: сохранённый ключ «ball» ведёт в них же.
     */
    enum class By(val key: String, val title: String) {
        DATE("date", "по датам"),
        PROJECT("project", "по проектам"),
        PERSON("person", "по людям"),
        GRP("grp", "группы"),
        DEAL("deal", "по сделкам"),
        NONE("none", "без групп");

        companion object {
            fun of(key: String, fallback: By): By = if (key == "ball") GRP else entries.firstOrNull { it.key == key } ?: fallback
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
     * Числа у разделов в меню — как `count()` у `VIEWS` веба: «Сегодня» — сколько
     * дел на сегодня, [todayHot] — среди них есть «отбить» (люди ждут), «Новое» —
     * только вопросы.
     */
    data class Counts(val today: Int, val new: Int, val todayHot: Boolean = false) {
        fun of(v: View): Int = when (v) {
            View.TODAY -> today
            View.NEW -> new
            View.ALL -> 0
        }

        fun hot(v: View): Boolean = v == View.TODAY && todayHot

        fun label(v: View): String = of(v).takeIf { it > 0 }?.toString().orEmpty()
    }

    fun counts(s: Dela.Snapshot, me: String, today: String, sphere: String, now: Long): Counts {
        val t = DelaGroups.todayTasks(s, me, today, sphere)
        return Counts(today = t.size, new = newCount(s, me, sphere, now), todayHot = t.any { DelaGroups.grpOf(it) == DelaGroups.Grp.OWED })
    }

    /**
     * Число «Нового» (`newCount` веба, 08.10.2026): только вопросы — ждущие
     * предложения. Поставленное и сделанное само ответа не ждут (владелец:
     * «уверен — поставил, не уверен — подскажи»).
     */
    @Suppress("UNUSED_PARAMETER")
    fun newCount(s: Dela.Snapshot, me: String, sphere: String, now: Long): Int = Dela.newOnes(s, me).size

    /** Раздел страницы: заголовок веба и дела в его порядке. */
    data class Section(val title: String, val items: List<Dela.Task>)

    /** «Все дела»: моё в сфере, с «Сделанными» по выбору. */
    fun list(s: Dela.Snapshot, me: String, sphere: String, showDone: Boolean): List<Dela.Task> =
        s.tasks.values.filter { mine(s, me, it) && Dela.inSphere(it, sphere) && (showDone || it.open) }

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

    // ------------------------------------------------------------ «Новое»: подскажи — поставил — само

    /*
     * «Новое» (08.10.2026, docs/dela-phone-6.md, `renderNew` веба). Владелец:
     * «в новом всё делится на "не уверен — подскажи" и "уверен — поставил"».
     * Сверху — только то, где без человека не обойтись; ниже — поставленное за
     * три дня одной строкой со значком источника; «Сделано само» — свёрнуто.
     * Что именно сказано и откуда — в карточке дела (`origin`).
     */

    /** «Поставил» — за сколько дней и сколько строк (`FEED_DAYS`, `FEED_MAX` веба). */
    const val FEED_DAYS = 3
    const val FEED_MAX = 40

    /** «Подскажи»: ждущие предложения, свежие сверху — по дате встречи, потом по времени появления. */
    fun asks(s: Dela.Snapshot, me: String): List<Dela.Suggestion> =
        Dela.newOnes(s, me).sortedWith(compareByDescending<Dela.Suggestion> { it.at }.thenByDescending { it.createdAt })

    /** «Поставил»: мои открытые дела, заведённые за [FEED_DAYS] дня, свежие сверху. Сделанное — не новость. */
    fun feed(s: Dela.Snapshot, me: String, sphere: String, now: Long): List<Dela.Task> =
        openMine(s, me, sphere).filter { ms(it.createdAt) > now - FEED_DAYS * 86_400_000L }.sortedByDescending { ms(it.createdAt) }

    /** «Поставил» по дням: «Сегодня», «Вчера», «Вторник, 6 октября» — первые [FEED_MAX] строк. */
    fun feedDays(list: List<Dela.Task>, today: String, zone: ZoneId): List<Pair<String, List<Dela.Task>>> {
        val out = LinkedHashMap<String, MutableList<Dela.Task>>()
        for (t in list.take(FEED_MAX)) {
            val day = runCatching { java.time.OffsetDateTime.parse(t.createdAt).atZoneSameInstant(zone).toLocalDate().toString() }
                .getOrDefault(t.createdAt.take(10))
            out.getOrPut(day) { mutableListOf() } += t
        }
        return out.map { (day, items) ->
            when (days(day, today)) {
                0L -> "Сегодня"
                1L -> "Вчера"
                else -> longDate(day)
            } to items.toList()
        }
    }

    private fun byName(n: String, vararg names: String): Boolean {
        val key = Dela.norm(n) ?: return false
        return key.isNotBlank() && names.any { it.isNotBlank() && Dela.norm(it) == key }
    }

    /**
     * Почему предложение ждёт человека (`askWhy` веба): вопрос самой автоматики
     * (`payload.ask`), не узнан проект, не узнан человек — по имени, короткому
     * и другим именам, без регистра. null — вопроса нет (старая встреча и т. п.).
     */
    fun askWhy(sg: Dela.Suggestion, s: Dela.Snapshot): String? {
        val p = sg.payloadObj()
        p.str("ask").takeIf { it.isNotBlank() }?.let { return it }
        val project = p.str("project_name")
        if (project.isNotBlank() && s.projects.values.none { byName(project, it.name, *it.aliases.toTypedArray()) }) {
            return "Не знаю проект «$project» — куда?"
        }
        val person = p.str("person_name")
        if (person.isNotBlank() && s.people.values.none { byName(person, it.name, it.short, *it.aliases.toTypedArray()) }) {
            return "Кто это — «$person»?"
        }
        return null
    }

    /** Предложение словами (`sugText` веба): что, подсказка одной строкой и подробности уточнения. */
    data class SugText(val title: String, val hint: String, val add: String = "")

    fun sugText(sg: Dela.Suggestion, s: Dela.Snapshot, today: String): SugText {
        val p = sg.payloadObj()
        fun v(k: String) = p.str(k)
        val ball = v("ball")
        if (sg.kind == "create") {
            return SugText(
                v("title").ifBlank { sg.quote },
                listOfNotNull(
                    v("project_name").takeIf { it.isNotBlank() },
                    v("person_name").takeIf { it.isNotBlank() },
                    BALL_WORD[ball]?.takeIf { ball.isNotBlank() && ball != Dela.MINE },
                    v("due_date").takeIf { it.isNotBlank() }?.let { "срок " + ddmm(it, today) },
                ).joinToString(" · "),
            )
        }
        val t = s.tasks[sg.taskId]
        val ref = t?.title ?: "дело не видно"
        val project = t?.let { s.projects[it.projectId]?.name ?: it.projectName }.orEmpty()
        return when (sg.kind) {
            "close" -> SugText("Закрыть: $ref", project)
            "assign" -> SugText("Взять себе: $ref", project)
            else -> {
                val person = v("person_name")
                SugText(
                    "Уточнить: $ref",
                    listOfNotNull(
                        v("title").takeIf { it.isNotBlank() }?.let { "название: «$it»" },
                        v("due_date").takeIf { it.isNotBlank() }?.let { d ->
                            "срок " + (t?.dueDate?.takeIf { it.isNotBlank() }?.let { ddmm(it, today) + " → " } ?: "") + ddmm(d, today)
                        },
                        if (ball.isNotBlank()) (BALL_WORD[ball] ?: ball) + if (person.isNotBlank()) " $person" else ""
                        else person.takeIf { it.isNotBlank() }?.let { "человек: $it" },
                    ).joinToString(" · "),
                    v("note"),
                )
            }
        }
    }

    /** Кнопка «да» у вопроса — по виду предложения (`ASK_YES` веба). */
    fun askYes(kind: String): String = when (kind) {
        "close" -> "Закрыть"
        "update" -> "Уточнить"
        "assign" -> "Взять"
        else -> "Поставить"
    }

    private fun stems(text: String): Set<String> =
        (Dela.norm(text) ?: "").replace(Regex("[^a-zа-я0-9]+"), " ").split(' ').filter { it.length > 3 }.map { it.take(5) }.toSet()

    /**
     * «Похоже, это уже есть» (`twinOf` веба): открытое моё дело, чьё название
     * совпадает с предложением «завести» началами слов — хотя бы на 60 % меньшего.
     */
    fun twinOf(sg: Dela.Suggestion, s: Dela.Snapshot, me: String): Dela.Task? {
        if (sg.kind != "create") return null
        val a = stems(sg.payloadObj().str("title").ifBlank { sg.quote })
        if (a.size < 2) return null
        var best: Dela.Task? = null
        var score = 0.0
        for (t in s.tasks.values) {
            if (!t.open || !mine(s, me, t)) continue
            val b = stems(t.title)
            if (b.size < 2) continue
            val sc = a.count { it in b }.toDouble() / minOf(a.size, b.size)
            if (sc > score) { score = sc; best = t }
        }
        return best.takeIf { score >= 0.6 }
    }

    /** «Сделано само» свёрнутым — одной строкой: «закрыл 3, уточнил 2». */
    fun autoSummary(list: List<Dela.Suggestion>): String {
        val closed = list.count { it.kind == "close" }
        return listOfNotNull(
            "закрыл $closed".takeIf { closed > 0 },
            "уточнил ${list.size - closed}".takeIf { list.size > closed },
        ).joinToString(", ")
    }

    /**
     * Значок источника (`ORIGIN` веба): форма — откуда, цвет — без радуги
     * (поправка задания 10): наговорка, встреча, Telegram, Claude — кремовым
     * `plan`, руками и перенос — бледным `meta`. [glyph] — имя значка для экрана.
     */
    data class Mark(val glyph: String, val plan: Boolean, val word: String)

    fun mark(source: String): Mark = when (source) {
        "voice", "dictation" -> Mark("mic", true, "наговорка")
        "meeting" -> Mark("meet", true, "встреча")
        "telegram", "userbot" -> Mark("send", true, "Telegram")
        "bot" -> Mark("send", true, "ответ в Telegram")
        "mcp" -> Mark("chat", true, "Claude")
        "web" -> Mark("pen", false, "в вебе")
        "import" -> Mark("list", false, "перенос")
        else -> Mark("pen", false, "руками")
    }

    /**
     * Откуда дело и когда — по долгому нажатию на значок «Поставил»: «встреча:
     * Альфа: тизер · сегодня 12:52». Номера нет нигде (поправка задания 10).
     * [batchTitle] — пачка предложения, из которого вышло дело.
     */
    fun markWords(t: Dela.Task, batchTitle: String, s: Dela.Snapshot, today: String, zone: ZoneId): String {
        val other = t.createdBy.isNotBlank() && t.createdBy != t.ownerId
        val who = if (other) "поставил(а) " + (s.people.values.firstOrNull { it.userId == t.createdBy }?.name ?: s.users[t.createdBy]?.name ?: t.createdBy) else null
        val m = mark(t.source)
        return listOfNotNull(who ?: (m.word + if (batchTitle.isNotBlank()) ": $batchTitle" else ""), whenWords(t.createdAt, today, zone).takeIf { it.isNotBlank() })
            .joinToString(" · ")
    }

    /** Предложение «завести», из которого вышло дело (`sugByTask` веба): по `result_task_id`. */
    fun sugByTask(s: Dela.Snapshot): Map<String, Dela.Suggestion> =
        s.suggestions.values.filter { it.kind == "create" && it.resultTaskId.isNotBlank() }.associateBy { it.resultTaskId }

    /**
     * Откуда дело (`origin` у `GET /api/task`, 08.10.2026): наговорка дословно
     * ([kind] dictation — [text]) или встреча и Telegram ([kind] suggestion —
     * пачка, цитата, ссылка на встречу только `https://`, [auto] — поставлено само).
     */
    data class Origin(
        val kind: String,
        val at: String,
        val text: String = "",
        val source: String = "",
        val batchTitle: String = "",
        val quote: String = "",
        val url: String = "",
        val auto: Boolean = false,
    ) {
        val dictation: Boolean get() = kind == "dictation"
    }

    /** null — старый сервер, дело руками или ответ без `origin`. */
    fun origin(o: JSONObject?): Origin? {
        if (o == null) return null
        val kind = o.str("kind").ifBlank { return null }
        return Origin(
            kind = kind,
            at = o.str("at"),
            text = o.str("text").trim(),
            source = o.str("source"),
            batchTitle = o.str("batch_title"),
            quote = o.str("quote").trim(),
            url = o.str("url").takeIf { it.startsWith("https://") }.orEmpty(),
            auto = !o.isNull("auto") && o.optBoolean("auto", false),
        )
    }

    /** Строка плашки «откуда»: «Наговорка · сегодня 12:52» или «Альфа: тизер · 08.10 · поставлено само». */
    fun originTop(o: Origin, today: String, zone: ZoneId): String =
        if (o.dictation) listOf("Наговорка", whenWords(o.at, today, zone)).filter { it.isNotBlank() }.joinToString(" · ")
        else listOfNotNull(o.batchTitle.ifBlank { mark(o.source).word }, "поставлено само".takeIf { o.auto }).joinToString(" · ")

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
        // «Группы» — как «Сегодня»: внутри выше тот, кому делать раньше и кто дольше ждёт.
        val sorted = items.sortedWith(if (by == By.GRP) DelaGroups.byTime(today) else Dela.ORDER)
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
                By.GRP -> DelaGroups.grpOf(t).let { g -> put((g.ordinal + 1).toString(), g.title, t, sub = g.sub) }
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

    /** Как человек дела назван в строке: короткое имя, иначе полное. */
    fun who(t: Dela.Task, s: Dela.Snapshot): String = s.people[t.personId]?.label ?: t.who.takeIf { t.personId.isNotBlank() }.orEmpty()

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
     * проекта), сделка, кто ждёт («Иван ждёт 3 дн», «жду Иван 5 дн», «при
     * встрече с Иван») или «@Иван», минуты, метки.
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
        // Вместо «мяча» — кто ждёт и сколько (10.10.2026, `taskRow` веба): «Олег ждёт 3 дн», «жду Олег 5 дн».
        DelaGroups.wait(t, who(t, s), today, personGrouped = by == By.PERSON)?.let { out += it.text }
        if (t.estimateMin > 0) out += "${t.estimateMin} м"
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
            // Сделанное само (08.10.2026) — « — само», как `touchChips` веба: решал не человек.
            else -> if (live.reason in Dela.AUTO_REASONS) "само" else "принято"
        }
        return "$from: $what — $st"
    }

    /**
     * Что поменяло уточнение само — словами, как `autoWords` веба: «название:
     * «…» · срок 17.10 → 12.10 · мяч: жду Ольга». Как было — `result.was`, как
     * стало — сам `result`; имя — `payload.person_name`. Пусто — старый сервер.
     */
    fun autoWords(sg: Dela.Suggestion, today: String): String {
        val r = sg.resultObj()
        val was = r.optJSONObject("was") ?: return ""
        val p = sg.payloadObj()
        fun v(o: org.json.JSONObject, k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotBlank() && it != "null" }.orEmpty()
        val due = v(r, "due_date")
        val wasDue = v(was, "due_date")
        val person = v(p, "person_name")
        return listOfNotNull(
            if (was.has("title")) "название: «${v(r, "title")}»" else null,
            if (was.has("due_date")) "срок " + (if (wasDue.isNotBlank()) ddmm(wasDue, today) + " → " else "") + (if (due.isNotBlank()) ddmm(due, today) else "без срока") else null,
            if (was.has("ball") || was.has("owed") || was.has("person_id")) {
                val ball = if (r.optBoolean("owed", false)) Dela.OWED else v(r, "ball")
                (BALL_WORD[ball] ?: ball) + if (person.isNotBlank()) " $person" else ""
            } else null,
        ).joinToString(" · ")
    }

    /**
     * Группа словом — `grpWord` веба (10.10.2026: слов «мяч», «моё», «повестка» в
     * группах нет): «owed» — «отбить», ждём от человека — «мониторить», повестка —
     * «при встрече», своё — «запустить».
     */
    private val BALL_WORD = mapOf(Dela.OWED to "отбить", Dela.MINE to "запустить", Dela.WAITING to "мониторить", Dela.AGENDA to "при встрече")

    // ------------------------------------------------------------ человек

    /** Разделы страницы человека — заголовками веба. */
    fun personSections(s: Dela.Snapshot, personId: String): List<Section> {
        // `renderPerson` веба (10.10.2026): тремя группами. Его просьбы ко мне (`requested_by`) — тоже «отбить».
        val his = s.tasks.values.filter { it.open && (it.personId == personId || it.requestedBy == personId) }
        fun g(t: Dela.Task) = DelaGroups.grpOf(t)
        return listOf(
            Section("Отбить: ждёт от меня", his.filter { g(it) == DelaGroups.Grp.OWED || (it.requestedBy == personId && g(it) == DelaGroups.Grp.SELF) }),
            Section("Запустить: о нём и с ним", his.filter { g(it) == DelaGroups.Grp.SELF && it.requestedBy != personId }),
            Section("Мониторить: жду от него", his.filter { g(it) == DelaGroups.Grp.CHECK }),
        )
    }

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
