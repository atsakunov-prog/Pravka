package ru.zf.pravka.core

import java.text.Collator
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela.str

/**
 * Три группы, «Сегодня», уровни группировки и фильтры (10.10.2026,
 * docs/dela-phone-11.md). Владелец: «есть дела, которые ждут меня — люди что-то
 * спросили и ждут, они мега важные… второе — что я сам придумал… третье —
 * проверить, что мне люди должны… чёткий список на каждый день». Вечером назвал
 * группы — «Отбить», «Запустить», «Мониторить» — и попросил уровни: «первый
 * уровень группировки — проекты, второй — по категориям, третий — по времени».
 *
 * Эталон — веб (`server/pravka_dela/static/app.js`: `GRP`, `grpOf`, `grpSet`,
 * `inToday`, `whenDay`, `byTime`, `toggleToday`, `LEVELS`, `levelKey`,
 * `nested`, `branches`, `FILTERS_DEF`) и правила 17–18 сервера. Одно правило в
 * четырёх местах (сервер, вид базы, веб, телефон): поправил веб — поправь здесь
 * и тест `DelaGroupsTest`.
 */
object DelaGroups {

    // ------------------------------------------------------------ группы

    /** Группа дела; порядок — приоритет, везде этот. */
    enum class Grp(val key: String, val title: String, val sub: String) {
        OWED("owed", "Отбить", "люди ждут ответа или дела — первым делом"),
        SELF("self", "Запустить", "сам придумал"),
        CHECK("check", "Мониторить", "что должны мне — проверить, напомнить");

        companion object {
            fun of(key: String): Grp? = entries.firstOrNull { it.key == key }
        }
    }

    /** `grpOf` веба: «отбить» — флаг (или мяч «owed» из автоматики), «мониторить» — мяч у человека, остальное — «запустить». */
    fun grpOf(t: Dela.Task): Grp = when {
        t.owed || t.ball == Dela.OWED -> Grp.OWED
        t.ball == Dela.WAITING -> Grp.CHECK
        else -> Grp.SELF
    }

    /**
     * Дело в группе [g] (`grpSet` веба): повестка, ставшая «Запустить», остаётся
     * повесткой — это тоже «Запустить». Мяч ушёл к человеку — «отбить» гаснет.
     */
    fun withGrp(t: Dela.Task, g: Grp): Dela.Task = when (g) {
        Grp.OWED -> t.copy(ball = Dela.MINE, owed = true)
        Grp.CHECK -> t.copy(ball = Dela.WAITING, owed = false)
        Grp.SELF -> t.copy(ball = if (t.ball == Dela.AGENDA) Dela.AGENDA else Dela.MINE, owed = false)
    }

    /** Поля операции для группы — `grpSet` веба: `{ball, owed}`. */
    fun grpSet(t: Dela.Task, g: Grp): JSONObject = withGrp(t, g).let { JSONObject().put("ball", it.ball).put("owed", it.owed) }

    // ------------------------------------------------------------ «Сегодня»

    private fun mine(me: String, t: Dela.Task) = me.isBlank() || t.ownerId == me
    private fun le(d: String, today: String) = d.isNotBlank() && d <= today

    /**
     * На сегодня (`inToday` веба, сервер — `store.TODAY_IF`): открытое своё дело,
     * у которого срок сегодня или прошёл, «Сейчас» телефона, «отбить» без срока
     * (человек ждёт — значит, сегодня) или «мониторить», где пора напомнить.
     * Не сделал — завтра оно снова здесь, просроченным.
     */
    fun inToday(t: Dela.Task, me: String, today: String): Boolean =
        t.open && mine(me, t) && (
            t.focusOn == today || le(t.dueDate, today) ||
                (grpOf(t) == Grp.OWED && t.dueDate.isBlank()) ||
                (t.ball == Dela.WAITING && le(t.nudgeOn, today))
            )

    fun todayTasks(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> =
        s.tasks.values.filter { inToday(it, me, today) && Dela.inSphere(it, sphere) }

    /**
     * Когда дело делать (`whenDay` веба) — для уровня «время» и порядка:
     * срок; «Сейчас» и «отбить» без срока — сегодня; «мониторить» — срок или
     * день напоминания, что раньше. Сделанное и без срока — null.
     */
    fun whenDay(t: Dela.Task, today: String): String? {
        if (!t.open) return null
        return listOfNotNull(
            t.dueDate.ifBlank { null },
            today.takeIf { t.focusOn == today },
            today.takeIf { grpOf(t) == Grp.OWED && t.dueDate.isBlank() },
            t.nudgeOn.ifBlank { null }?.takeIf { t.ball == Dela.WAITING },
        ).minOrNull()
    }

    /**
     * По времени (`byTime` веба; владелец: «внутри уже сортировка по времени»):
     * когда делать, час, потом кто дольше ждёт (дело заведено раньше), потом номер.
     */
    fun byTime(today: String): Comparator<Dela.Task> =
        compareBy<Dela.Task>({ whenDay(it, today) ?: "9999" }, { it.dueTime.ifBlank { "99" } }, { it.createdAt }, { it.num })

    /** «Сегодня» одним списком в порядке групп — для Засечки и дня: «отбить», потом «запустить», потом «мониторить». */
    fun todayOrdered(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> =
        todayTasks(s, me, today, sphere).sortedWith(compareBy<Dela.Task> { grpOf(it).ordinal }.then(byTime(today)))

    /**
     * Быстрый ввод: «!отбить» (и старое «!ждут») — «Отбить», «!мониторить» (и
     * «!жду») — «Мониторить», «!запустить»; «!повестка» и «!сейчас» понимаются и
     * дальше, но в подсказке их нет. Метки вырезаются из названия.
     */
    data class Quick(val title: String, val grp: Grp? = null, val agenda: Boolean = false, val now: Boolean = false)

    fun quick(text: String): Quick {
        var q = Quick(text.trim())
        val words = text.trim().split(Regex("\\s+")).filter { w ->
            when (Dela.norm(w)) {
                "!отбить", "!ждут" -> { q = q.copy(grp = Grp.OWED); false }
                "!мониторить", "!жду" -> { q = q.copy(grp = Grp.CHECK); false }
                "!запустить" -> { q = q.copy(grp = Grp.SELF); false }
                "!повестка" -> { q = q.copy(agenda = true); false }
                "!сейчас" -> { q = q.copy(now = true); false }
                else -> true
            }
        }
        return q.copy(title = words.joinToString(" "))
    }

    /** Сделано сегодня (по Москве): «закрыто M» в шапке «Сегодня». */
    fun doneToday(s: Dela.Snapshot, me: String, today: String): Int =
        s.tasks.values.count { it.status == Dela.DONE && mine(me, it) && it.completedAt.isNotBlank() && mskDay(it.completedAt) == today }

    private val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    /** День момента по Москве (`MSK_DAY` веба); не разобрался — первые десять знаков. */
    fun mskDay(iso: String): String =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(MSK).toLocalDate().toString() }.getOrDefault(iso.take(10))

    /**
     * Солнце у дела (`toggleToday` веба): не на сегодня — срок сегодня; уже на
     * сегодня — «на завтра»: срок завтра, у «мониторить» с напоминанием сегодня —
     * и напоминание завтра, «Сейчас» снимается. [tomorrow] — завтрашний день.
     * Ответ — поля операции и подпись тоста («На сегодня» / «На завтра»).
     */
    fun toggleToday(t: Dela.Task, me: String, today: String, tomorrow: String): Pair<JSONObject, String> {
        if (!inToday(t, me, today)) return JSONObject().put("due_date", today) to "На сегодня"
        val set = JSONObject()
        if (t.focusOn == today) set.put("focus_on", JSONObject.NULL)
        if (t.ball == Dela.WAITING && le(t.nudgeOn, today)) set.put("nudge_on", tomorrow)
        if (le(t.dueDate, today) || (grpOf(t) == Grp.OWED && t.dueDate.isBlank()) || set.length() == 0) set.put("due_date", tomorrow)
        return set to "На завтра"
    }

    /** Как было — для `was` правки и «Вернуть» в тосте: те же ключи, прежние значения. */
    fun was(t: Dela.Task, set: JSONObject): JSONObject = JSONObject().apply {
        for (k in set.keys()) put(k, Dela.field(t, k))
    }

    // ------------------------------------------------------------ строка

    /**
     * Вторая строка вместо «мяча» (`taskRow` веба): «Отбить» — «Гуркин ждёт 3 дн»
     * (дни — с того дня, как дело завели; без человека — «ждут»), «Мониторить» —
     * «жду Наташу 5 дн» (с `waiting_since`), повестка — «при встрече с Ольгой»,
     * «Запустить» с человеком — «@Ольга» (если люди не названы заголовком).
     * [strong] — «отбить»: ярче остальных.
     */
    data class Wait(val text: String, val strong: Boolean)

    fun wait(t: Dela.Task, who: String, today: String, personGrouped: Boolean): Wait? = when {
        grpOf(t) == Grp.OWED -> {
            val d = if (t.createdAt.isNotBlank()) DelaViews.days(mskDay(t.createdAt), today) else 0L
            Wait((if (who.isNotBlank()) "$who ждёт" else "ждут") + if (d > 0) " $d дн" else "", strong = true)
        }
        t.ball == Dela.WAITING -> {
            val w = if (t.waitingSince.isNotBlank()) DelaViews.days(t.waitingSince, today) else 0L
            Wait("жду" + (if (who.isNotBlank()) " $who" else "") + if (w > 0) " $w дн" else "", strong = false)
        }
        t.ball == Dela.AGENDA -> Wait("при встрече" + if (who.isNotBlank()) " с $who" else "", strong = false)
        who.isNotBlank() && !personGrouped -> Wait("@$who", strong = false)
        else -> null
    }

    // ------------------------------------------------------------ уровни

    /** Уровень группировки (`LEVELS` веба) и подпись «Все …» кнопки «только одно» (`ALL_OF`). */
    enum class Level(val key: String, val title: String, val allOf: String) {
        PROJECT("project", "проекты", "Все проекты"),
        GRP("grp", "группы", "Все группы"),
        DATE("date", "время", "Всё время"),
        PERSON("person", "люди", "Все люди"),
        DEAL("deal", "сделки", "Все сделки");

        companion object {
            fun of(key: String): Level? = entries.firstOrNull { it.key == key }
        }
    }

    /** Уровней не больше трёх (владелец: «первый, второй, третий»). */
    const val MAX_LEVELS = 3

    /** С завода (`LEVELS_DEF` веба): «Сегодня» — группы, «Все дела» — проекты › группы › время, проект — группы › время. */
    val LEVELS_DEF: Map<String, List<Level>> = mapOf(
        "today" to listOf(Level.GRP),
        "all" to listOf(Level.PROJECT, Level.GRP, Level.DATE),
        "project" to listOf(Level.GRP, Level.DATE),
        "deal" to listOf(Level.GRP, Level.DATE),
    )

    fun levelsDef(view: String): List<Level> = LEVELS_DEF[view] ?: listOf(Level.GRP)

    /** «проекты › группы»; пусто — «без групп» (`levelsName` веба). */
    fun levelsName(levels: List<Level>): String = if (levels.isEmpty()) "без групп" else levels.joinToString(" › ") { it.title }

    /** Уровни строкой для памяти устройства и обратно: «project,grp,date». Пусто — «без групп», чужое — мимо. */
    fun levelsKey(levels: List<Level>): String = levels.joinToString(",") { it.key }.ifBlank { "-" }

    fun parseLevels(key: String?): List<Level>? {
        if (key.isNullOrBlank()) return null
        if (key == "-") return emptyList()
        return key.split(',').mapNotNull { Level.of(it.trim()) }.distinct().take(MAX_LEVELS)
    }

    /**
     * Выбор уровня [i] (`levelsBar` веба): null («—») убирает уровень и всё после
     * него; один уровень дважды не встаёт — повтор дальше по списку уходит.
     */
    fun setLevel(levels: List<Level>, i: Int, lv: Level?): List<Level> {
        val next = if (lv == null) levels.take(i) else levels.take(i) + lv + levels.drop(i + 1)
        return next.distinct().take(MAX_LEVELS)
    }

    /**
     * Ключ дела на уровне (`levelKey` веба): [order] — порядок веток, [title] —
     * заголовок, [id] — человек, проект или сделка (ветки по id: две «Наташи» —
     * две ветки), [full] — полное имя, когда коротких одинаковых больше одного,
     * [note] — подпись в списке «только одно», [find] — по чему его ищут.
     */
    data class Key(
        val order: String,
        val title: String,
        val id: String = "",
        val sub: String = "",
        val late: Boolean = false,
        val full: String = "",
        val note: String = "",
        val find: List<String> = emptyList(),
        val grp: Grp? = null,
        val projectId: String? = null,
        val personId: String? = null,
        val dealId: String? = null,
    )

    fun levelKey(t: Dela.Task, lv: Level, s: Dela.Snapshot, today: String): Key = when (lv) {
        Level.GRP -> grpOf(t).let { g -> Key((g.ordinal + 1).toString(), g.title, sub = g.sub, find = listOf(g.title, g.sub), grp = g) }
        Level.PROJECT -> s.projects[t.projectId]?.let { p ->
            Key("1" + p.name + "\u0001" + p.id, p.name, id = p.id, find = listOf(p.name) + p.aliases, projectId = p.id)
        } ?: Key("9", "Без проекта", find = listOf("без проекта"), projectId = "")
        Level.PERSON -> s.people[t.personId]?.let { p ->
            val org = DelaCrm.orgLabel(s, p.orgId)?.name.orEmpty()
            Key(
                "1" + p.label + "\u0001" + p.id, p.label, id = p.id, full = p.name,
                note = listOf(p.name.takeIf { p.short.isNotBlank() && p.short != p.name }.orEmpty(), p.role, org).filter { it.isNotBlank() }.joinToString(" · "),
                find = (listOf(p.name, p.short) + p.aliases + listOf(p.role, org)).filter { it.isNotBlank() },
                personId = p.id,
            )
        } ?: Key("9", "Без человека", find = listOf("без человека"))
        Level.DEAL -> s.deals[t.dealId]?.let { d ->
            val pn = s.projects[d.projectId]?.name.orEmpty()
            Key("1" + d.name + "\u0001" + d.id, d.name, id = d.id, note = pn, find = listOf(d.name, pn).filter { it.isNotBlank() }, dealId = d.id)
        } ?: Key("9", "Без сделки", find = listOf("без сделки"))
        Level.DATE -> dateKey(t, today)
    }

    /** «Время» как уровень: Просрочено, Сегодня, Завтра, дни недели, Следующая неделя, Позже, Без срока, Сделано — по [whenDay]. */
    private fun dateKey(t: Dela.Task, today: String): Key {
        if (!t.open) return Key("9done", "Сделано")
        val d = whenDay(t, today) ?: return Key("8none", "Без срока")
        val n = DelaViews.days(today, d)
        return when {
            n < 0 -> Key("0late", "Просрочено", late = true)
            n == 0L -> Key("1today", "Сегодня", sub = DelaViews.longDate(today))
            n == 1L -> Key("2tomorrow", "Завтра", sub = DelaViews.longDate(d))
            n < 7 -> Key("3$d", DelaViews.longDate(d))
            n < 14 -> Key("5next", "Следующая неделя")
            else -> Key("6later", "Позже")
        }
    }

    private val collator: Collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }

    /**
     * Ветка дерева (`nested` веба): заголовок, [depth] — уровень (0 — раздел,
     * 1 и 2 — подзаголовки), дела ветки по времени и ветки глубже. У последнего
     * уровня [children] пусты, дела — строками.
     */
    data class Node(
        val key: String,
        val title: String,
        val depth: Int,
        val level: Level?,
        val items: List<Dela.Task>,
        val children: List<Node> = emptyList(),
        val sub: String = "",
        val late: Boolean = false,
        val grp: Grp? = null,
        val projectId: String? = null,
        val personId: String? = null,
        val dealId: String? = null,
    ) {
        val leaf: Boolean get() = children.isEmpty()
    }

    /**
     * Дела уровнями (`nested` веба). Без уровней — одна ветка без заголовка.
     * Внутри последнего — по времени. Пустых веток не бывает: они собираются из
     * самих дел. Ключ ветки — путь от корня, чтобы списку было за что держаться.
     */
    fun nested(items: List<Dela.Task>, levels: List<Level>, s: Dela.Snapshot, today: String, depth: Int = 0, path: String = ""): List<Node> {
        val sorted = items.sortedWith(byTime(today))
        val lv = levels.getOrNull(depth)
            ?: return if (depth == 0 && sorted.isNotEmpty()) listOf(Node("all", "", 0, null, sorted)) else emptyList()
        data class B(val k: Key, val list: MutableList<Dela.Task>)
        val buckets = LinkedHashMap<String, B>()
        for (t in sorted) {
            val k = levelKey(t, lv, s, today)
            buckets.getOrPut(k.order) { B(k, mutableListOf()) }.list += t
        }
        val titles = buckets.values.groupingBy { it.k.title }.eachCount()
        return buckets.entries.sortedWith { a, b -> collator.compare(a.key, b.key) }.map { (order, b) ->
            val k = b.k
            val key = "$path/${lv.key}:$order"
            val deeper = if (depth + 1 < levels.size) nested(b.list, levels, s, today, depth + 1, key) else emptyList()
            Node(
                key = key,
                title = if ((titles[k.title] ?: 0) > 1 && k.full.isNotBlank()) k.full else k.title,
                depth = depth, level = lv, items = b.list.toList(), children = deeper,
                sub = k.sub, late = k.late, grp = k.grp, projectId = k.projectId, personId = k.personId, dealId = k.dealId,
            )
        }
    }

    /** Дела дерева в порядке показа — то, что видит Claude. */
    fun flat(nodes: List<Node>): List<Dela.Task> = nodes.flatMap { if (it.leaf) it.items else flat(it.children) }

    // ------------------------------------------------------------ «только одно»

    /**
     * Ветка первого уровня для списка «только одно» (`branches` веба): [title]
     * — полное имя, если короткие совпали; [n] — сколько дел.
     */
    data class Branch(val key: String, val title: String, val id: String, val n: Int, val note: String, val find: List<String>)

    /** Ветки первого уровня с числом дел: группы и время — по порядку, остальное — где больше дел. */
    fun branches(items: List<Dela.Task>, lv: Level, s: Dela.Snapshot, today: String): List<Branch> {
        val m = LinkedHashMap<String, Pair<Key, Int>>()
        for (t in items) {
            val k = levelKey(t, lv, s, today)
            m[k.order] = (m[k.order]?.first ?: k) to ((m[k.order]?.second ?: 0) + 1)
        }
        val titles = m.values.groupingBy { it.first.title }.eachCount()
        val list = m.map { (order, v) ->
            val k = v.first
            Branch(order, if ((titles[k.title] ?: 0) > 1 && k.full.isNotBlank()) k.full else k.title, k.id, v.second, k.note, listOf(k.title) + k.find)
        }
        return if (lv == Level.GRP || lv == Level.DATE) list.sortedWith { a, b -> collator.compare(a.key, b.key) }
        else list.sortedWith(compareByDescending<Branch> { it.n }.thenComparator { a, b -> collator.compare(a.title, b.title) })
    }

    /** Поиск в списке веток (`pickPop` веба): каждое слово — в имени, коротком, других именах, должности, компании. */
    fun findBranches(list: List<Branch>, q: String): List<Branch> {
        val words = (Dela.norm(q) ?: "").split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return list
        return list.filter { b ->
            val keys = b.find.mapNotNull { Dela.norm(it) }
            words.all { w -> keys.any { it.contains(w) } }
        }
    }

    /**
     * Выбранная ветка первого уровня (`pick` веба): человек, проект и сделка — по
     * [id] (переименуешь — фильтр не потеряет свою «Наташу»), остальное — по ключу.
     */
    data class Pick(val lv: Level, val key: String, val title: String, val id: String = "")

    fun pickOf(b: Branch, lv: Level): Pick = Pick(lv, b.key, b.title, b.id)

    /** Выбор действует, только пока первый уровень тот же (`pickOn` веба). */
    fun pickOn(pick: Pick?, levels: List<Level>): Boolean = pick != null && levels.firstOrNull() == pick.lv

    fun picked(items: List<Dela.Task>, levels: List<Level>, pick: Pick?, s: Dela.Snapshot, today: String): List<Dela.Task> {
        if (pick == null || !pickOn(pick, levels)) return items
        return items.filter { t ->
            val k = levelKey(t, pick.lv, s, today)
            if (pick.id.isNotBlank()) k.id == pick.id else k.order == pick.key
        }
    }

    fun pickJson(p: Pick): JSONObject = JSONObject().put("lv", p.lv.key).put("key", p.key).put("title", p.title)
        .apply { if (p.id.isNotBlank()) put("id", p.id) }

    fun pick(o: JSONObject?): Pick? {
        if (o == null) return null
        val lv = Level.of(o.str("lv")) ?: return null
        return Pick(lv, o.str("key"), o.str("title"), o.str("id"))
    }

    // ------------------------------------------------------------ фильтры

    /** Что показывает фильтр (`SCOPES` веба): сегодня, всё открытое или одна группа. */
    enum class Scope(val key: String, val title: String) {
        TODAY("today", "Сегодня"),
        ALL("all", "Все дела"),
        OWED("owed", "Отбить"),
        SELF("self", "Запустить"),
        CHECK("check", "Мониторить");

        companion object {
            fun of(key: String): Scope = entries.firstOrNull { it.key == key } ?: ALL
        }
    }

    /** Фильтр — что показать и уровни, с выбором «только одно» (`filters[]` в `users.settings`). */
    data class Filter(val id: String, val name: String, val scope: Scope, val levels: List<Level>, val pick: Pick? = null)

    /**
     * С завода — пока своих нет (`FILTERS_DEF` веба): сегодня по проектам, и кому
     * отбить и кого мониторить — по людям: перед звонком видно всё, что должен
     * человеку и что жду от него.
     */
    val FILTERS_DEF = listOf(
        Filter("f-today-proj", "Сегодня по проектам", Scope.TODAY, listOf(Level.PROJECT, Level.GRP)),
        Filter("f-owed-people", "Отбить — по людям", Scope.OWED, listOf(Level.PERSON)),
        Filter("f-check-people", "Мониторить — по людям", Scope.CHECK, listOf(Level.PERSON)),
    )

    /** `settings.filters` из `/api/me`: нет поля (или не список) — заводские; незнакомое внутри — мимо. */
    fun filters(raw: Any?): List<Filter> {
        val a = raw as? JSONArray ?: return FILTERS_DEF
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id").ifBlank { return@mapNotNull null }
            val lv = o.optJSONArray("levels")
            Filter(
                id = id,
                name = o.str("name").ifBlank { "Фильтр" },
                scope = Scope.of(o.str("scope")),
                levels = if (lv == null) emptyList() else (0 until lv.length()).mapNotNull { Level.of(lv.optString(it)) }.distinct().take(MAX_LEVELS),
                pick = pick(o.optJSONObject("pick")),
            )
        }
    }

    fun json(f: Filter): JSONObject = JSONObject().put("id", f.id).put("name", f.name).put("scope", f.scope.key)
        .put("levels", JSONArray().apply { f.levels.forEach { put(it.key) } })
        .apply { f.pick?.takeIf { pickOn(it, f.levels) }?.let { put("pick", pickJson(it)) } }

    fun json(list: List<Filter>): JSONArray = JSONArray().apply { list.forEach { put(json(it)) } }

    /** Операция сохранения — список целиком (`user.settings` сливает верхний уровень настроек). */
    fun saveOp(list: List<Filter>, opId: String = Dela.newId()): JSONObject =
        JSONObject().put("op", "user.settings").put("op_id", opId).put("settings", JSONObject().put("filters", json(list)))

    /** Имя по умолчанию (`addFilter` веба): «Сегодня: проекты › группы — Наташа». */
    fun suggestName(scope: Scope, levels: List<Level>, pick: Pick?): String =
        "${scope.title}: ${levelsName(levels)}" + (pick?.takeIf { pickOn(it, levels) }?.let { " — " + it.title } ?: "")

    /** Дела фильтра (`scopeItems` веба). */
    fun scopeItems(s: Dela.Snapshot, me: String, today: String, sphere: String, scope: Scope): List<Dela.Task> = when (scope) {
        Scope.TODAY -> todayTasks(s, me, today, sphere)
        else -> s.tasks.values.filter {
            it.open && mine(me, it) && Dela.inSphere(it, sphere) && (scope == Scope.ALL || grpOf(it).key == scope.key)
        }
    }
}
