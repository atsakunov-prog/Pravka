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
     * Разделы веба (`VIEWS` веба). С 10.10.2026 их было три (docs/dela-phone-11.md,
     * владелец: «не надо этих всех странных видов. Просто надо говорить: на
     * сегодня вот такие дела»); с 13.10.2026 (этап 4 «Правки 5.0», docs/dela-phone-19.md)
     * первым — «Сейчас»: что владелец отметил сам, под ним вопросы «Нового» и «Сегодня» без
     * уже взятого. «Сегодня» — срок сегодня, «Новое» — вопросы автоматики, «Все дела» — всё
     * открытое, просроченное сверху.
     */
    enum class View(val key: String, val title: String) {
        /** Главная (`renderNow` веба): «Сейчас», «Новое» с кнопками, «Сегодня» без взятого в «Сейчас». */
        NOW("now", "Сейчас"),
        TODAY("today", "Сегодня"),
        NEW("new", "Новое"),
        ALL("all", "Все дела"),
        /** Круг жизни (12.10.2026): обзор девяти направлений; виден, только когда Круг включён (`DelaKrug.on`). */
        KRUG("krug", "Круг жизни"),
        /**
         * Дерево жизни (13.10.2026, docs/dela-phone-14.md): направления › цели › проекты и дела —
         * плюсиками, колонками или списком; видно, только когда сервер знает цели (`DelaKrug.treeOn`).
         */
        TREE("tree", "Дерево жизни");

        companion object {
            /**
             * Ключ раздела, как маршрут веба (`route` в `app.js`): «Входящие» — в «Новое»,
             * «Неделя» — во «Все дела»; «Утро», «Предстоящее», «Жду» и незнакомое — на главную,
             * в «Сейчас». Старый ключ «Сегодня» остаётся «Сегодня».
             */
            fun of(key: String): View = when (key) {
                "inbox" -> NEW
                "week" -> ALL
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
    /** Круг жизни: у дела нет направления — ни своего, ни наследованного (разметит разборщик). */
    const val NO_DIRECTION = "без направления"

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

    /** `openMine` веба: открытые мои в сфере («Входящие» — в любой), без целей — цель не делают, к ней идут. */
    fun openMine(s: Dela.Snapshot, me: String, sphere: String): List<Dela.Task> =
        s.tasks.values.filter { it.open && !it.goal && mine(s, me, it) && Dela.inSphere(it, sphere) }

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
        s.tasks.values.filter { it.open && !it.goal && mine(s, me, it) && isNow(it, today) }.sortedWith(Dela.ORDER)

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
     * Числа у разделов — как `count()` у `VIEWS` веба: «Сейчас» — сколько взято (во
     * всех сферах), «Сегодня» — со сроком сегодня, [todayHot] — среди них есть «отбить»
     * (люди ждут), «Новое» — только вопросы, «Все дела» — только просроченное ([late]
     * цветом беды: оно живёт там и само никуда не встаёт).
     */
    data class Counts(val today: Int, val new: Int, val todayHot: Boolean = false, val now: Int = 0, val late: Int = 0) {
        fun of(v: View): Int = when (v) {
            View.NOW -> now
            View.TODAY -> today
            View.NEW -> new
            View.ALL -> late
            View.KRUG, View.TREE -> 0
        }

        /** Число акцентом (`.count.hot` холста): взятое в «Сейчас», «Сегодня», где люди ждут, и вопросы «Нового». */
        fun hot(v: View): Boolean = (v == View.NOW && now > 0) || (v == View.TODAY && todayHot) || (v == View.NEW && new > 0)

        /** Число цветом беды (`.count.bad`): просроченное у «Всех дел». */
        fun bad(v: View): Boolean = v == View.ALL && late > 0

        fun label(v: View): String = of(v).takeIf { it > 0 }?.toString().orEmpty()
    }

    fun counts(s: Dela.Snapshot, me: String, today: String, sphere: String, now: Long): Counts {
        val t = DelaGroups.todayTasks(s, me, today, sphere)
        return Counts(
            today = t.size,
            new = newCount(s, me, sphere, now),
            todayHot = t.any { DelaGroups.grpOf(it) == DelaGroups.Grp.OWED },
            now = nowTasks(s, me, today).size,
            late = DelaGroups.lateTasks(s, me, today, sphere).size,
        )
    }

    /**
     * Подпись шапки Дел вида v2 — сводка дня: «3 сегодня · 2 просрочено · 1 закрыто», сфера — первым словом,
     * когда выбрана не «Все» ([sphere] = null). Число впереди слова (14.10.2026, владелец: «укорачиваем
     * подписи»): прежнее «сегодня 3 · просрочено 2 · за…» на сложенном резалось, а число и есть то, что ищет
     * глаз. Просрочка — раньше «закрыто»: не влезет строка — переносится вторая, а важное остаётся первым.
     */
    fun headLine(sphere: String?, today: Int, late: Int, done: Int): String = listOfNotNull(
        sphere,
        "$today сегодня",
        "$late просрочено".takeIf { late > 0 },
        "$done закрыто".takeIf { done > 0 },
    ).joinToString(" · ")

    /**
     * «Ждут от тебя без срока» и «жду больше недели» (аудит 15.10.2026, №5) — числами в шапке «Сейчас» рядом с
     * «просрочено N» (`waitLinks` веба). Правило одно в трёх местах — сервер (`store.OWED_NODUE_IF`,
     * `WAITING_STALE_IF`), веб (`owedNoDue`, `waitingStale`) и здесь: свои открытые, не цели, в сфере; «Отбить» без
     * срока; «Мониторить», где человек молчит дольше [WAITING_STALE_DAYS] дней, а напоминание не назначено или прошло
     * (`Dela.week().waitingStale` — то же правило, второе не пишем). Сами ни в «Сейчас», ни в «Сегодня» не встают:
     * сроков не выдумываем, только показываем.
     */
    data class Waits(val owedNoDue: Int, val waitingStale: Int) {
        val any: Boolean get() = owedNoDue > 0 || waitingStale > 0
    }

    /** Дней молчания до «жду больше недели» — `store.WAITING_STALE_DAYS`; правило считает `Dela.week`. */
    const val WAITING_STALE_DAYS = 7

    fun owedNoDue(s: Dela.Snapshot, me: String, sphere: String): List<Dela.Task> =
        openMine(s, me, sphere).filter { DelaGroups.grpOf(it) == DelaGroups.Grp.OWED && it.dueDate.isBlank() }

    fun waits(s: Dela.Snapshot, me: String, today: String, sphere: String, now: Long): Waits =
        Waits(owedNoDue(s, me, sphere).size, Dela.waitingStale(Dela.myOpen(s, me, sphere), today).size)

    /** Части строки: «ждут от тебя без срока: N», «жду больше недели: M»; ноль — части нет. */
    fun waitWords(w: Waits): List<String> = listOfNotNull(
        "ждут от тебя без срока: ${w.owedNoDue}".takeIf { w.owedNoDue > 0 },
        "жду больше недели: ${w.waitingStale}".takeIf { w.waitingStale > 0 },
    )

    /**
     * Число «Нового» (`newCount` веба, 08.10.2026): только вопросы — ждущие
     * предложения. Поставленное и сделанное само ответа не ждут (владелец:
     * «уверен — поставил, не уверен — подскажи»).
     */
    @Suppress("UNUSED_PARAMETER")
    fun newCount(s: Dela.Snapshot, me: String, sphere: String, now: Long): Int = Dela.newOnes(s, me).size

    /** Раздел страницы: заголовок веба и дела в его порядке. */
    data class Section(val title: String, val items: List<Dela.Task>)

    /** «Все дела»: моё в сфере, с «Сделанными» по выбору. Целей нет — они в дереве и в поиске (13.10.2026). */
    fun list(s: Dela.Snapshot, me: String, sphere: String, showDone: Boolean): List<Dela.Task> =
        s.tasks.values.filter { !it.goal && mine(s, me, it) && Dela.inSphere(it, sphere) && (showDone || it.open) }

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

    // ------------------------------------------------------------ «Новое» одной пачкой

    /*
     * Одна пачка «Нового» (`renderNew(batch)` веба, `#/new/<batch_ref>`; доводка 5.0, 14.10.2026):
     * «Открыть «Новое»» у дайджеста на «Сегодня» ведёт к пачке ЭТОЙ сводки (`dela.batch` —
     * тот же `batch_ref`, что у предложений из неё), а не во всё «Новое». Правило — как в вебе:
     * вопросы пачки, все открытые дела, заведённые из её предложений (без окна в три дня),
     * сделанное само по ней за год.
     */

    /** «Подскажи» пачки [batch] — в том же порядке, что [asks]. */
    fun asksOfBatch(s: Dela.Snapshot, me: String, batch: String): List<Dela.Suggestion> =
        asks(s, me).filter { it.batchRef == batch }

    /** «Поставил» пачки: открытые дела, заведённые из её предложений, свежие сверху. */
    fun feedOfBatch(s: Dela.Snapshot, batch: String): List<Dela.Task> {
        val from = sugByTask(s)
        return s.tasks.values.filter { it.open && from[it.id]?.batchRef == batch }.sortedByDescending { ms(it.createdAt) }
    }

    /** «Сделано само» пачки — за год, как в вебе: пачка из старой сводки тоже открывается целиком. */
    fun autoOfBatch(s: Dela.Snapshot, me: String, batch: String, now: Long): List<Dela.Suggestion> =
        Dela.autoDone(s, me, now, days = 365).filter { it.batchRef == batch }

    /** Подпись пачки: название из её предложений, нет — сам `batch_ref`. */
    fun batchTitle(s: Dela.Snapshot, batch: String): String =
        s.suggestions.values.firstOrNull { it.batchRef == batch && it.batchTitle.isNotBlank() }?.batchTitle ?: batch

    /** Кто назван именем: [hit] — он один; [many] — двое и больше на одном ярусе (не узнан). */
    data class Match<T>(val hit: T?, val many: List<T>)

    /**
     * Кто назван именем — тем же правилом, что сервер (`crm.match_name`, dela_0020; аудит 15.10.2026, №1) и веб
     * (`byName`): живые карточки, ярусами. Один на ярусе — он; двое и больше — не узнан, [Match.many] — кто они (к
     * следующему ярусу не идём). До того телефон смотрел имя, короткое и другие имена одним ярусом и видел только «ни
     * одного такого» — двусмысленности не замечал вовсе.
     */
    fun <T> byName(list: Collection<T>, name: String, tiers: List<(T) -> List<String>>): Match<T> {
        val n = Dela.norm(name) ?: return Match(null, emptyList())
        for (tier in tiers) {
            val got = list.filter { x -> tier(x).any { it.isNotBlank() && Dela.norm(it) == n } }
            if (got.size == 1) return Match(got.first(), emptyList())
            if (got.size > 1) return Match(null, got)
        }
        return Match(null, emptyList())
    }

    /** Ярусы людей: своё имя и короткое имя, потом другие имена. Только живые (не архив, не слитые). */
    fun personMatch(s: Dela.Snapshot, name: String): Match<Dela.Person> =
        byName(s.people.values.filter { it.live }, name, listOf({ p -> listOf(p.name, p.short) }, { p -> p.aliases }))

    /** У проектов имя и другие имена — один ярус. */
    fun projectMatch(s: Dela.Snapshot, name: String): Match<Dela.Project> =
        byName(s.projects.values.filter { it.live }, name, listOf({ p -> listOf(p.name) + p.aliases }))

    /** «A», «A или B», «A, B или C» (`orWords` веба). */
    fun orWords(names: List<String>): String =
        if (names.size > 1) names.dropLast(1).joinToString(", ") + " или " + names.last() else names.firstOrNull().orEmpty()

    /**
     * Почему предложение ждёт человека (`askWhy` веба): вопрос самой автоматики
     * (`payload.ask`), иначе — что не узналось по имени. Сервер уже посмотрел имена
     * (`payload.held` есть, хоть пустой, — с 15.10.2026) — его вердикт сильнее своего
     * точного сравнения: «Женя Соколов» он узнаёт уверенным «кто это». Нет `held`
     * (старый сервер) — сами, ярусами ([personMatch]): двое — «Кто это — «X»: A или B?»,
     * никого — «Кто это — «X»?». Имя смотрим, только если id не стоит. null — вопроса нет.
     */
    fun askWhy(sg: Dela.Suggestion, s: Dela.Snapshot): String? {
        val p = sg.payloadObj()
        p.str("ask").takeIf { it.isNotBlank() }?.let { return it }
        val project = p.str("project_name").takeIf { p.str("project_id").isBlank() }.orEmpty()
        val person = p.str("person_name").takeIf { p.str("person_id").isBlank() }.orEmpty()
        if (p.has("held") && !p.isNull("held")) {
            // Только пока имя и сейчас не узнаётся (`heldResolved` веба): справочник мог пополниться после того, как
            // сервер записал held, — тогда вопрос снят, при «Да» сервер имя узнает (проверка 15.10.2026).
            val parts = p.optString("held").split("; ").filterNot { x ->
                (project.isNotBlank() && x.contains("«$project»") && x.contains("проект") && projectMatch(s, project).hit != null) ||
                    (person.isNotBlank() && x.contains("«$person»") && !x.contains("проект") && personMatch(s, person).hit != null)
            }
            if (project.isNotBlank() && parts.any { it.contains("«$project»") && it.contains("проект") }) {
                return if (parts.any { it.startsWith("не один проект") }) "Какой проект — «$project»?" else "Не знаю проект «$project» — куда?"
            }
            if (person.isNotBlank() && parts.any { it.contains("«$person»") && !it.contains("проект") }) return "Кто это — «$person»?"
            return null
        }
        if (project.isNotBlank()) {
            val r = projectMatch(s, project)
            if (r.many.isNotEmpty()) return "Какой проект — «$project»: ${orWords(r.many.take(3).map { it.name })}?"
            if (r.hit == null) return "Не знаю проект «$project» — куда?"
        }
        if (person.isNotBlank()) {
            val r = personMatch(s, person)
            if (r.many.isNotEmpty()) return "Кто это — «$person»: ${orWords(r.many.take(3).map { it.name })}?"
            if (r.hit == null) return "Кто это — «$person»?"
        }
        return null
    }

    /**
     * Почему не встало само — строкой сервера (`payload.held`: причины через «; », `heldLine` веба) без того, что
     * уже сказал вопрос [q]: часть, целиком входящая в вопрос; часть про имя, о котором вопрос и спрашивает; при
     * `pick` — часть про человека (вопрос «Кто это — X или Y?» и есть она); у вопроса о человеке со встречи с
     * `pick` — «похожие уже есть: …» (вопрос «Это A или B?» их называет). Пусто — null, строки нет.
     */
    fun heldLine(sg: Dela.Suggestion, q: String?): String? {
        val p = sg.payloadObj()
        val held = if (p.has("held") && !p.isNull("held")) p.optString("held") else return null
        val nq = Dela.norm(q).orEmpty()
        val person = p.str("person_name")
        val project = p.str("project_name")
        val pick = pick(sg)
        fun asked(x: String) = listOf(person, project).any { n -> n.isNotBlank() && x.contains("«$n»") && nq.contains(Dela.norm("«$n»").orEmpty()) }
        val left = held.split("; ").map { it.trim() }.filter { x ->
            x.isNotBlank() && !(nq.isNotBlank() && nq.contains(Dela.norm(x).orEmpty())) &&
                !(pick && person.isNotBlank() && x.contains("«$person»")) && !asked(x) &&
                !(sg.kind == "person" && p.str("ask").isNotBlank() && x.startsWith("похожие уже есть"))
        }
        return if (left.isEmpty()) null else "Ждёт: " + left.joinToString("; ")
    }

    /** Вариант ответа кнопкой (`payload.options`): номер, подпись и «нет» ли это (`reject`). */
    data class Option(val index: Int, val label: String, val reject: Boolean)

    /**
     * Варианты ответа кнопками (`sugOptions` веба, 13.10.2026): у вопроса автоматики — её собственные ответы
     * («Это Иван Петров», «Без человека», «Не для CRM»), а не «Да / Не надо». Сервер без фичи `options` их не примет —
     * тогда null, как раньше. Меньше двух — тоже null (сервер такие и не хранит).
     */
    fun options(sg: Dela.Suggestion, s: Dela.Snapshot): List<Option>? {
        if (Dela.FEATURE_OPTIONS !in s.features) return null
        val a = sg.payloadObj().optJSONArray("options") ?: return null
        val out = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val label = o.str("label").ifBlank { return@mapNotNull null }
            Option(i, label, o.optBoolean("reject", false))
        }
        return out.takeIf { it.size >= 2 }
    }

    /** Вопрос ждёт выбора (`payload.pick`): «Да» без варианта сервер не примет (аудит №1, №25). */
    fun pick(sg: Dela.Suggestion): Boolean = sg.payloadObj().optBoolean("pick", false)

    private val MANY_WORDS = listOf("двое", "трое", "четверо", "пятеро", "шестеро", "семеро")
    private val MANY_NUM = Regex("^\\d+ с именем")

    /**
     * «Да» без выбора сервер не примет (`store._who_guard`, аудит №1): вопрос с `pick` или человек «завести» /
     * «уточнить» назван именем, которое у сервера двусмысленно, — его `held` говорит «двое с именем «X»: …» или «на
     * «X» похожи: …». Тогда «Поставить» и «Поставить все» не показываем: ответ — кнопкой варианта, «Поправить» с
     * выбором человека или словами Claude. Без `held` (старый сервер) — как раньше: старый «Да» принимал.
     */
    fun choiceOnly(sg: Dela.Suggestion): Boolean {
        if (pick(sg)) return true
        if (sg.kind != "create" && sg.kind != "update") return false
        val p = sg.payloadObj()
        val name = p.str("person_name")
        if (name.isBlank() || p.str("person_id").isNotBlank() || !p.has("held") || p.isNull("held")) return false
        return p.optString("held").split("; ").map { it.trim() }.any { x ->
            x.contains("«$name»") && (x.startsWith("на «") || MANY_WORDS.any { x.startsWith("$it с именем") } || MANY_NUM.containsMatchIn(x))
        }
    }

    /** Ответить можно одним «Да»: вариантов кнопками нет и выбор не нужен. */
    fun plainYes(sg: Dela.Suggestion, s: Dela.Snapshot): Boolean = options(sg, s) == null && !choiceOnly(sg)

    /**
     * «Поставить все» / «Не надо все» — только когда у каждого вопроса ответ — одно «Да» (как веб: при вариантах
     * кнопки «все» нет). Иначе «Поставить все» считало бы отказанные сервером в «поставлено: N».
     */
    fun bulkOk(asks: List<Dela.Suggestion>, s: Dela.Snapshot): Boolean = asks.size > 1 && asks.all { plainYes(it, s) }

    /** Вариант «нет» среди кнопок — «Не для CRM», «Никто», «Нет»: им отвечает «Нет» там, где кнопок не видно. */
    fun rejectOption(sg: Dela.Suggestion, s: Dela.Snapshot): Option? = options(sg, s)?.firstOrNull { it.reject }

    /** Тост после ответа кнопкой (`answer` веба): «Ответ: Это Иван Петров» или «Не надо: Не для CRM». */
    fun answerSaid(o: Option): String = (if (o.reject) "Не надо: " else "Ответ: ") + o.label

    /** Причина «нет» у вопроса о человеке без вариантов (кнопка «Не для CRM» веба): служба больше не спросит. */
    const val NOT_CRM = "не для CRM"

    /** Причина «нет» вопроса о человеке: та, что у его варианта «нет» («не он в Telegram»), иначе [NOT_CRM]. */
    fun personNoReason(sg: Dela.Suggestion): String {
        val a = sg.payloadObj().optJSONArray("options") ?: return NOT_CRM
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("reject", false)) return o.str("reason").ifBlank { NOT_CRM }
        }
        return NOT_CRM
    }

    /**
     * Ссылка значка источника (`sugMark` веба): встреча — у дела её адрес в `source_ref`, у вопроса о человеке со
     * встречи (№25) — в `payload.speaker.url`. Только `https://`.
     */
    fun sugLink(sg: Dela.Suggestion): String? =
        listOf(sg.sourceRef, sg.payloadObj().optJSONObject("speaker")?.str("url").orEmpty()).firstOrNull { it.startsWith("https://") }

    /** Предложение словами (`sugText` веба): что, подсказка одной строкой и подробности уточнения. */
    data class SugText(val title: String, val hint: String, val add: String = "")

    fun sugText(sg: Dela.Suggestion, s: Dela.Snapshot, today: String): SugText {
        val p = sg.payloadObj()
        fun v(k: String) = p.str(k)
        val ball = v("ball")
        // Новый человек (dela_0019, `sugText` веба): кто и откуда; у «это он?» — с кем похож. Полный вид — docs/dela-phone-20.md.
        if (sg.kind == "person") {
            val d = p.optJSONObject("person") ?: JSONObject()
            val title = v("title").ifBlank { d.str("name") }
            // «Это он в Telegram?» (№25): заголовок — сама карточка; «похоже: <она же>» повторило бы его.
            val like = s.people[v("person_id")]?.takeIf { !p.optBoolean("rename") && it.name != title }
            return SugText(
                title,
                listOfNotNull(v("where").takeIf { it.isNotBlank() }, like?.let { "похоже: ${it.name}" }, d.str("role").takeIf { it.isNotBlank() })
                    .joinToString(" · "),
            )
        }
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
        // Вопрос разборщика Круга (`sugText` веба): дело и цель, которую он предлагает, — путём от направления.
        if (sg.source == "krug") return SugText(ref, v("goal_path").takeIf { it.isNotBlank() }?.let { "цель: $it" } ?: project)
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
        "person" -> "Завести"
        else -> "Поставить"
    }

    /** То же по самому предложению: вопрос «это он?» о человеке (`person_id` в payload) — «Да, это он», а не «Завести». */
    fun askYes(sg: Dela.Suggestion): String =
        if (sg.kind == "person" && sg.payloadObj().str("person_id").isNotBlank()) "Да, это он" else askYes(sg.kind)

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

    /** «Сделано само» свёрнутым — одной строкой: «закрыл 3, уточнил 2, завёл людей: 1» (`autoBlock` веба). */
    fun autoSummary(list: List<Dela.Suggestion>): String {
        val closed = list.count { it.kind == "close" }
        val people = list.count { it.kind == "person" }
        // created: false — сервер дописал ключ в живую карточку, человека не заводил.
        val added = list.count { it.kind == "person" && !it.resultObj().optBoolean("created", true) }
        val upd = list.size - closed - people
        return listOfNotNull(
            "закрыл $closed".takeIf { closed > 0 },
            "уточнил $upd".takeIf { upd > 0 },
            "завёл людей: ${people - added}".takeIf { people - added > 0 },
            "дописал в карточки: $added".takeIf { added > 0 },
        ).joinToString(", ")
    }

    /**
     * Откуда сделанное само — словами под заголовком (`autoFrom` веба): «по встречам и Telegram», «по Telegram и
     * телефонной книжке». С 15.10.2026 людей заводят и встречи (№25): прежнее «по встречам и Telegram» на всё подряд
     * стало неправдой у людей из книжки.
     */
    fun autoFrom(list: List<Dela.Suggestion>): String {
        fun has(src: String) = list.any { it.source == src || (src == "telegram" && it.source == "userbot") }
        val words = listOfNotNull("встречам".takeIf { has("meeting") }, "Telegram".takeIf { has("telegram") }, "телефонной книжке".takeIf { has("contacts") })
        return if (words.isEmpty()) "" else "по " + orWords(words).replace(" или ", " и ")
    }

    /**
     * Строка человека, заведённого само: «Новый человек: Пётр Новиков»; карточки не видно — как его назвали.
     * [made] — сервер завёл карточку (`result.created`); false — дописал номер, Telegram или почту в уже живую (ключ
     * нашёлся у неё): строка «Дописано в карточку: …» и без «Вернуть» — архив убрал бы настоящую карточку (проверка
     * 15.10.2026). [live] — есть что вернуть: заведена, видна и не в архиве.
     */
    data class AutoPerson(val personId: String, val name: String, val hint: String, val live: Boolean, val made: Boolean = true)

    fun autoPerson(sg: Dela.Suggestion, s: Dela.Snapshot): AutoPerson {
        val r = sg.resultObj()
        val p = s.people[r.str("person_id")]
        val pl = sg.payloadObj()
        val made = r.optBoolean("created", true)
        return AutoPerson(
            personId = p?.id.orEmpty(),
            name = p?.name ?: pl.str("title").ifBlank { pl.optJSONObject("person")?.str("name").orEmpty() }.ifBlank { "карточка не видна" },
            hint = listOfNotNull(pl.str("where").takeIf { it.isNotBlank() }, sg.quote.takeIf { it.isNotBlank() }?.let { "«" + it.take(160) + "»" }).joinToString(" · "),
            live = made && p != null && p.live,
            made = made,
        )
    }

    /**
     * Счётчик откатов автоматики за неделю (аудит 15.10.2026, №29) — строкой под «Сделано само» (`autoWeekLine`
     * веба): что поставлено, закрыто, уточнено и заведено само и сколько из этого ты отменил или вернул (в скобках),
     * как ответил на её вопросы. Считает сервер (`GET /api/view/week`, поле `automation.all`) — по делам, людям и
     * комментариям; старый сервер поля не знает — null, строки нет. Нули пропускаются.
     */
    fun automationLine(week: JSONObject?): String? {
        val b = week?.optJSONObject("automation")?.optJSONObject("all") ?: return null
        fun n(k: String) = b.optInt(k, 0)
        val ans = listOf("yes" to "да", "edited" to "с правкой", "no" to "нет", "expired" to "погасло").filter { n(it.first) > 0 }
            .joinToString(", ") { "${it.second} ${n(it.first)}" }
        val parts = listOfNotNull(
            "поставлено само ${n("created")} (отменил ${n("created_undone")})".takeIf { n("created") > 0 },
            "закрыто ${n("closed")} (вернул ${n("closed_undone")})".takeIf { n("closed") > 0 },
            "уточнено ${n("updated")} (${n("updated_undone")})".takeIf { n("updated") > 0 },
            "людей ${n("people")} (${n("people_undone")})".takeIf { n("people") > 0 },
            "вопросов ${n("asked")}: $ans".takeIf { n("asked") > 0 },
        )
        return if (parts.isEmpty()) null else "за неделю: " + parts.joinToString(" · ")
    }

    /** Частые «нет» автоматике (`no_reasons`): ««не дело» — 3»; пусто — "" (подсказка под строкой счётчика). */
    fun automationNo(week: JSONObject?): String {
        val a = week?.optJSONObject("automation")?.optJSONObject("all")?.optJSONArray("no_reasons") ?: return ""
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.str("reason").isNotBlank() }
            .joinToString("; ") { "«${it.str("reason")}» — ${it.optInt("n", 0)}" }
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
        // Люди из телефонной книжки (dela_0019) и разборщик Круга — как `ORIGIN` веба.
        "contacts" -> Mark("phone", true, "телефонная книжка")
        "krug" -> Mark("circle", true, "разборщик Круга")
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

    /** Уровень группировки — та же группировка строки: что названо заголовком, строка не повторяет. */
    /** Уровень — группировка строки (что названо заголовком, строка не повторяет); у направлений своей нет. */
    fun byOf(lv: DelaGroups.Level): By? = when (lv) {
        DelaGroups.Level.PROJECT -> By.PROJECT
        DelaGroups.Level.GRP -> By.GRP
        DelaGroups.Level.DATE -> By.DATE
        DelaGroups.Level.PERSON -> By.PERSON
        DelaGroups.Level.DEAL -> By.DEAL
        DelaGroups.Level.DIR -> null
    }

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
    fun chips(
        t: Dela.Task,
        by: By?,
        s: Dela.Snapshot,
        today: String,
        onProjectPage: Boolean = false,
        also: Set<By> = emptySet(),
        /** Кто смотрит: у чужого дела Круга нет (направления и цели — только хозяину). "" — своё. */
        me: String = "",
        /** Строка в дереве жизни: направление и «зачем» названы самим деревом. */
        inTree: Boolean = false,
        /** Строка «Деревом» (15.10.2026): дела внутри видны отступом — «для: …» и «сначала: …» не нужны. */
        nested: Boolean = false,
    ): List<String> {
        // [also] — остальные уровни группировки (10.10.2026): что названо заголовком, в строке не повторяется.
        fun on(b: By) = by == b || b in also
        val out = mutableListOf<String>()
        val d = due(t, today)
        if (d != null && (!on(By.DATE) || d.late)) out += d.text
        // Напоминание в Telegram (06.10.2026): «⏰ 11:00», «⏰ завтра 09:00», «⏰ дом».
        // В вебе такой подписи ещё нет — задание серверу (docs/dela-server-remind.md) её просит.
        date(today)?.let { day -> out += DelaRemind.chip(t, day, java.time.ZoneId.systemDefault()) }
        if (!on(By.PROJECT) && !onProjectPage) {
            out += if (t.projectId.isBlank()) NO_PROJECT else s.projects[t.projectId]?.name ?: t.projectName.ifBlank { "проект" }
        }
        val deal = s.deals[t.dealId]?.name ?: t.dealName.takeIf { t.dealId.isNotBlank() }
        if (!deal.isNullOrBlank() && !on(By.DEAL)) out += deal
        // Вместо «мяча» — кто ждёт и сколько (10.10.2026, `taskRow` веба): «Олег ждёт 3 дн», «жду Олег 5 дн».
        DelaGroups.wait(t, who(t, s), today, personGrouped = on(By.PERSON))?.let { out += it.text }
        if (t.estimateMin > 0) out += "${t.estimateMin} м"
        out += t.labels
        // Круг жизни (12.10.2026, `taskRow` веба): направления дела (главное, потом метки) и «зачем: <цель>»;
        // у цели — «цель · N шагов» или «практика · ритм» (13.10.2026). В дереве место дела видно и так.
        // Дела внутри дела (15.10.2026, `DelaSteps`): у вложенного — «для: <дело>» вместо «зачем», у родителя —
        // «сначала: <первое открытое внутри>» и сколько ещё (раньше — «цель · N шагов»: дело с делами внутри целью не стало).
        val mine = me.isBlank() || t.ownerId.isBlank() || t.ownerId == me
        if (s.krugOn && mine) {
            val dirs = DelaKrug.directions()
            if (dirs.isNotEmpty()) {
                val ks = DelaKrug.dirsOf(t, s)
                if (ks.isNotEmpty() && !inTree) out += DelaKrug.names(ks, dirs)
                else if (ks.isEmpty() && t.open) out += NO_DIRECTION
                if (!inTree) s.tasks[t.whyId]?.takeIf { it.goal }?.let { g -> out += "зачем: " + if (g.title.length > 34) g.title.take(33) + "…" else g.title }
                DelaKrug.goalChip(t, s)?.let { out += it }
            }
            if (!inTree && !nested) {
                DelaSteps.forLine(t, s)?.let { out += it }
                DelaSteps.firstLine(t, s)?.let { out += it }
            }
        }
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
        val his = s.tasks.values.filter { it.open && !it.goal && (it.personId == personId || it.requestedBy == personId) }

        fun g(t: Dela.Task) = DelaGroups.grpOf(t)
        return listOf(
            Section("Отбить: ждёт от меня", his.filter { g(it) == DelaGroups.Grp.OWED || (it.requestedBy == personId && g(it) == DelaGroups.Grp.SELF) }),
            Section("Запустить: о нём и с ним", his.filter { g(it) == DelaGroups.Grp.SELF && it.requestedBy != personId }),
            Section("Мониторить: жду от него", his.filter { g(it) == DelaGroups.Grp.CHECK }),
        )
    }

    /** Строки карточки человека, как `renderPerson` веба: пустое не показывается. */
    /**
     * Строки карточки человека — как веб. [marksApart] (флаг `privacy`, смотрящий видит карточку целиком, аудит №10) —
     * пометки о человеке («День рождения», «Как часто», «Ищет», «Предлагает», «Характер», «Откуда», «Заметка») не здесь,
     * а в [personMarks] — под «Только тебе» с замком: команда этого не видит.
     */
    fun personInfo(p: Dela.Person, s: Dela.Snapshot, marksApart: Boolean = false): List<Pair<String, String>> {
        val client = if (p.orgId.isNotBlank()) s.projects.values.firstOrNull { it.orgId == p.orgId } else null
        val open = listOf(
            "Роль" to p.role,
            "Клиент" to client?.name.orEmpty(),
            "Телефон" to p.phones.joinToString(", "),
            "Почта" to p.emails.joinToString(", "),
            "Telegram" to p.telegram.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }.orEmpty(),
        ).filter { it.second.isNotBlank() }
        return if (marksApart) open else open + personMarks(p)
    }

    /** Пометки о человеке — то, что сервер отдаёт только видящему карточку целиком (`crm.v_people`, ярус `owner`). */
    fun personMarks(p: Dela.Person): List<Pair<String, String>> {
        val bd = if (p.birthDay > 0 && p.birthMonth in 1..12) "${p.birthDay} ${MONTHS[p.birthMonth - 1]}" + (if (p.birthYear > 0) " ${p.birthYear}" else "") else ""
        return listOf(
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
