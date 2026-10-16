package ru.zf.pravka.core

import org.json.JSONObject

/**
 * Дела внутри дела (15.10.2026, docs/dela-phone-23.md). Владелец: «У меня есть дело „Подготовить термшит и
 * отправить“. Я понимаю, что до этого надо из расшифровки встречи набросать драфт. Пишу это в „Что поправить в этом
 * деле“ — он добавляет в комментарии вместо того, чтобы сделать новое дело… под делом должно быть „до этого“ / „для
 * этого“ / „поправить это дело“ — и в карточке дела, и в обычном списке. И тогда дело ставится на сегодня и т. д. —
 * оно связывается с этим делом»; вечером — «вложенность одного в другое и показывать тоже в дереве», «это не отдельная
 * сущность, а обычные дела, вложенные в дело».
 *
 * Вложенное дело — обычное дело, у которого «Зачем» (`why_id`) — другое дело, а не цель дерева: свои поля, карточка,
 * свои вложенные. Порядок среди соседей — общее поле дела `pos`. Связь — поле Круга: её видит и ставит только хозяин
 * дела (правило базы «„зачем“ — только своё дело»), у чужого дела новое заводится рядом, без связи. Сделано последнее
 * открытое дело внутри — родитель не закрывается, а поднимается ([risen]).
 *
 * Одно правило в трёх местах: сервер (`store.step_of`, `step_due`, `_nest_rise`), веб (`stepDue`, `byPos`,
 * `stepsFirst`, `nestForest` в `app.js`) и телефон — здесь, под `DelaStepsTest`. Поправил одно — поправь остальные.
 * Слово «шаг» в интерфейсе сущностью не делаем: «Для этого», «Внутри», «для: …», «сначала: …».
 */
object DelaSteps {

    /** Сколько знаков названия дела в подписи строки («для: …», «сначала: …»). */
    const val TITLE_MAX = 40

    /**
     * Срок нового дела внутри, когда его не назвали: родитель в «Сейчас», просрочен или на сегодня — сегодня (до него
     * надо успеть); на будущую дату — тот же срок; без срока — без срока. «Сейчас» само не ставится — только если сказал.
     */
    fun due(parent: Dela.Task, today: String): String {
        val d = parent.dueDate.take(10)
        return when {
            parent.focusOn == today -> today
            d.isNotBlank() && d <= today -> today
            else -> d
        }
    }

    /**
     * Новое дело внутри [parent] — черновик карточки нового дела: «Зачем» — сам родитель (только своему и только
     * серверу, который знает Круг), проект, сделка и человек — от него (у цели — ничего, кроме «Зачем»), срок — [due].
     */
    fun draft(parent: Dela.Task, s: Dela.Snapshot, me: String, today: String, title: String = ""): Dela.Task {
        val goal = parent.goal
        return Dela.Task(
            id = Dela.newId(),
            title = title,
            ownerId = me,
            createdBy = me,
            source = "manual",
            projectId = if (goal) "" else parent.projectId,
            dealId = if (goal) "" else parent.dealId,
            personId = if (goal) "" else parent.personId,
            dueDate = due(parent, today),
            whyId = if (mine(parent, me) && s.krugOn) parent.id else "",
        )
    }

    private fun mine(t: Dela.Task, me: String) = me.isBlank() || t.ownerId.isBlank() || t.ownerId == me

    /** Можно ли вкладывать в [parent] и вынимать из него: своё дело на сервере с Кругом, не ориентир (`no_steps`). */
    fun canLink(parent: Dela.Task, s: Dela.Snapshot, me: String): Boolean = s.krugOn && !parent.noSteps && mine(parent, me)

    /**
     * Вложить, вынуть, поменять место — только своё открытое дело: не цель (у цели своё «Зачем») и не напоминание (захват:
     * оно не дело — сервер его в дело не вкладывает и в него ничего не кладёт; [Dela.Task.deed]).
     */
    fun canNest(t: Dela.Task, s: Dela.Snapshot, me: String): Boolean = s.krugOn && t.open && t.deed && mine(t, me)

    /** Дело, внутри которого это дело: «Зачем» — дело из копии, а не цель дерева (у цели своя подпись «зачем: …») и не напоминание. */
    fun parentOf(t: Dela.Task, s: Dela.Snapshot): Dela.Task? = parentIn(t, s.tasks)

    private fun parentIn(t: Dela.Task, tasks: Map<String, Dela.Task>): Dela.Task? =
        t.whyId.takeIf { it.isNotBlank() && it != t.id }?.let { tasks[it] }?.takeIf { it.deed }

    /** Место среди соседей: `pos`, 0 — после занумерованных, по времени заведения (как сервер и веб). */
    val BY_POS: Comparator<Dela.Task> = compareBy<Dela.Task>({ it.pos <= 0 }, { it.pos }, { it.createdAt }, { it.num })

    /** Открытые дела внутри (не подцели и не напоминания) — по месту; первое — «следующее» («сначала: …» у родителя). */
    fun insideOf(id: String, s: Dela.Snapshot): List<Dela.Task> = insideIn(id, s.tasks)

    private fun insideIn(id: String, tasks: Map<String, Dela.Task>): List<Dela.Task> =
        tasks.values.filter { it.open && it.deed && it.whyId == id && it.id != id }.sortedWith(BY_POS)

    /** Сделанных внутри — числом под «Внутри». */
    fun doneInside(id: String, s: Dela.Snapshot): Int = s.tasks.values.count { it.status == Dela.DONE && it.deed && it.whyId == id }

    private fun short(title: String): String = if (title.length > TITLE_MAX) title.take(TITLE_MAX - 1) + "…" else title

    /** Вторая строка дела внутри: «для: <родитель>»; не внутри — null. */
    fun forLine(t: Dela.Task, s: Dela.Snapshot): String? = parentOf(t, s)?.let { "для: " + short(it.title) }

    /** Вторая строка родителя: «сначала: <первое открытое внутри>» и сколько ещё; у цели — нет (у неё «цель · N шагов»). */
    fun firstLine(t: Dela.Task, s: Dela.Snapshot): String? {
        if (!t.open || !t.deed) return null
        val st = insideOf(t.id, s)
        if (st.isEmpty()) return null
        return "сначала: " + short(st[0].title) + if (st.size > 1) " (ещё ${st.size - 1})" else ""
    }

    /**
     * В одном плоском списке дело внутри — прямо перед своим родителем (вложенное вложенного — перед ним), соседи — по
     * месту. Дела внутри одного дела, чьего родителя в списке нет ([s] знает, что родитель — дело, а не цель), — подряд
     * и по месту, там, где стоит первое из них (`stepsFirst` веба). Остальной порядок — как был; чего в списке нет, то не
     * подтягивается.
     */
    fun stepsFirst(list: List<Dela.Task>, s: Dela.Snapshot? = null): List<Dela.Task> {
        if (list.size < 2) return list
        val ids = list.map { it.id }.toSet()
        fun under(t: Dela.Task) = t.whyId.isNotBlank() && t.whyId in ids && t.whyId != t.id
        val kids = list.filter(::under).groupBy { it.whyId }.mapValues { (_, v) -> v.sortedWith(BY_POS) }
        val lone = if (s == null) emptyMap()
        else list.filter { !under(it) && parentOf(it, s) != null }.groupBy { it.whyId }.filterValues { it.size > 1 }
        if (kids.isEmpty() && lone.isEmpty()) return list
        val out = ArrayList<Dela.Task>(list.size)
        val placed = HashSet<String>()
        fun place(x: Dela.Task) {
            if (!placed.add(x.id)) return
            for (c in kids[x.id].orEmpty()) place(c)
            out.add(x)
        }
        for (x in list) {
            if (under(x)) continue
            val sibs = lone[x.whyId]
            if (sibs != null && sibs.any { it.id == x.id }) sibs.sortedWith(BY_POS).forEach(::place) else place(x)
        }
        // Круг «Зачем» внутри списка (правило базы его не пускает) — всё равно никого не теряем.
        for (x in list) place(x)
        return out
    }

    /**
     * Верхний предок дела среди дел [list] (само дело — если его родителя в списке нет), `topIn` веба: по нему уровни
     * группировки ([DelaGroups.nested]) кладут дело внутри туда же, где родитель.
     */
    fun topIn(list: List<Dela.Task>): (Dela.Task) -> Dela.Task {
        val by = list.associateBy { it.id }
        return { t ->
            var x = t
            for (n in 0 until 60) {
                val p = x.whyId.takeIf { it.isNotBlank() && it != x.id }?.let { by[it] } ?: break
                if (p.id == t.id) break
                x = p
            }
            x
        }
    }

    /**
     * Открытые дела внутри дел [list] — все уровни, кроме тех, что и так в нём, с глубиной (`innerOf` веба). Главная
     * «Сейчас»: дела внутри дел из «Сейчас» стоят прямо над родителем отступом (в предел пяти не считаются) и в
     * «Сегодня» ниже не повторяются — «Для этого» у дела в «Сейчас» ставит дело внутри на сегодня, а не в «Сейчас».
     */
    fun inner(list: List<Dela.Task>, s: Dela.Snapshot): List<Pair<Dela.Task, Int>> {
        val have = list.map { it.id }.toHashSet()
        val out = ArrayList<Pair<Dela.Task, Int>>()
        fun walk(id: String, depth: Int) {
            for (c in insideOf(id, s)) {
                if (!have.add(c.id)) continue
                out += c to depth
                walk(c.id, depth + 1)
            }
        }
        for (t in list) walk(t.id, 1)
        return out
    }

    /** «Деревом» (`nestForest` веба): корни списка (их родителя в списке нет) и дела внутри каждого — по месту. */
    data class Forest(val roots: List<Dela.Task>, val kids: Map<String, List<Dela.Task>>)

    fun forest(list: List<Dela.Task>): Forest {
        val ids = list.map { it.id }.toSet()
        fun under(t: Dela.Task) = t.whyId.isNotBlank() && t.whyId in ids && t.whyId != t.id
        val kids = list.filter(::under).groupBy { it.whyId }.mapValues { (_, v) -> v.sortedWith(BY_POS) }
        val roots = list.filterNot(::under)
        // Круг «Зачем» внутри списка (не бывает — правило базы): без корня его дела пропали бы — показываем корнями.
        val shown = HashSet<String>()
        fun walk(t: Dela.Task) {
            if (!shown.add(t.id)) return
            kids[t.id].orEmpty().forEach(::walk)
        }
        roots.forEach(::walk)
        return Forest(roots + list.filter { it.id !in shown }, kids)
    }

    /** Строка дерева: дело, глубина и плюсик — null (внутри пусто), true (раскрыто), false (свёрнуто). */
    data class Line(val task: Dela.Task, val depth: Int, val fold: Boolean?)

    /** Корни [roots] с делами внутри — строками по порядку показа; свёрнутое ([folded]) не раскрывается. */
    fun lines(roots: List<Dela.Task>, kids: Map<String, List<Dela.Task>>, folded: Set<String>): List<Line> {
        val out = ArrayList<Line>()
        fun walk(t: Dela.Task, depth: Int, seen: Set<String>) {
            val inner = kids[t.id].orEmpty().filter { it.id !in seen }
            val open = t.id !in folded
            out.add(Line(t, depth, if (inner.isEmpty()) null else open))
            if (open) for (c in inner) walk(c, depth + 1, seen + c.id)
        }
        for (t in roots) walk(t, 0, setOf(t.id))
        return out
    }

    /** Что поднялось: родитель после подъёма и сдвинулся ли срок (без срока или позже — на сегодня). */
    data class Rise(val parent: Dela.Task, val moved: Boolean)

    /**
     * Сделано последнее открытое дело внутри (`_nest_rise` сервера): родитель — открытое дело, не цель — НЕ
     * закрывается, а поднимается: без срока или со сроком позже — на сегодня. [done] — уже сделанное (в [tasks] — тоже).
     * Только дела: сделанное напоминание родителя не поднимает (`tasks.nest_rise` сервера, dela_0032).
     */
    fun risen(done: Dela.Task, tasks: Map<String, Dela.Task>, today: String): Rise? {
        if (done.status != Dela.DONE || !done.deed) return null
        val p = parentIn(done, tasks) ?: return null
        if (!p.open || insideIn(p.id, tasks).any { it.id != done.id }) return null
        val d = p.dueDate.take(10)
        val moved = d.isBlank() || d > today
        return Rise(if (moved) p.copy(dueDate = today) else p, moved)
    }

    /** Подъём родителя, если сделать [t] сейчас (тост после «Сделано»). */
    fun rise(t: Dela.Task, s: Dela.Snapshot, today: String): Rise? {
        if (!t.open) return null
        val done = t.copy(status = Dela.DONE)
        return risen(done, s.tasks + (t.id to done), today)
    }

    /** «всё внутри сделано — теперь: «…», на сегодня» — тостом после «Сделано» (как веб и reply сервера). */
    fun riseWords(r: Rise): String = "всё внутри сделано — теперь: «${short(r.parent.title)}»" + if (r.moved) ", на сегодня" else ""

    /** «Завёл для «…»: «…», на сегодня» — тостом после «Для этого» (строка сервера `steps_said`). */
    fun madeWords(parent: Dela.Task, made: Dela.Task, today: String): String =
        if (made.whyId == parent.id) "Завёл для «${short(parent.title)}»: «${short(made.title)}», ${dueWords(made.dueDate, today)}"
        else "Завёл «${short(made.title)}», ${dueWords(made.dueDate, today)} — отдельно: вложить в чужое дело нельзя"

    /** Куда можно вложить [ids]: свои открытые дела, не цели, не напоминания и не ориентиры, кроме самих дел и того, что внутри них. */
    fun pool(s: Dela.Snapshot, me: String, ids: Collection<String>): List<Dela.Task> {
        val below = ids.flatMap { DelaKrug.below(s, it) }.toSet()
        return s.tasks.values.filter { it.open && it.deed && !it.noSteps && mine(it, me) && it.id !in below }
            .sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("ru"))) { it.title })
    }

    /** Вложить [t] в [parentId] ("" — вынуть): правка `why_id` с тем, что телефон видел. */
    fun nestOp(t: Dela.Task, parentId: String): JSONObject =
        Dela.setOp(t.id, JSONObject().put("why_id", Dela.nul(parentId)), JSONObject().put("why_id", Dela.nul(t.whyId)))

    /**
     * Раньше ([dir] = -1) или позже (+1) среди соседей: соседи нумеруются 1, 2, 3…, шлются только сдвинутые. Пусто —
     * двигать некуда (первое вверх, последнее вниз) или дело не внутри дела.
     */
    fun moveOps(t: Dela.Task, dir: Int, s: Dela.Snapshot): List<JSONObject> {
        val p = parentOf(t, s) ?: return emptyList()
        val list = insideOf(p.id, s).toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        val j = i + dir
        if (i < 0 || j !in list.indices) return emptyList()
        list[i] = list[j].also { list[j] = list[i] }
        return list.mapIndexedNotNull { n, x ->
            if (x.pos == n + 1) null
            else Dela.setOp(x.id, JSONObject().put("pos", n + 1), JSONObject().put("pos", if (x.pos > 0) x.pos else JSONObject.NULL))
        }
    }

    /** Подпись срока в тосте: «на сегодня», «на завтра», «на 20.10», «без срока». */
    fun dueWords(due: String, today: String): String = when {
        due.isBlank() -> "без срока"
        due.take(10) == today -> "на сегодня"
        runCatching { java.time.LocalDate.parse(today).plusDays(1).toString() }.getOrNull() == due.take(10) -> "на завтра"
        due.length >= 10 -> "на " + due.substring(8, 10) + "." + due.substring(5, 7)
        else -> "на $due"
    }
}
