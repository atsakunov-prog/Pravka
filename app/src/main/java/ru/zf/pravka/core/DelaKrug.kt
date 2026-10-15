package ru.zf.pravka.core

import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela.str

/**
 * Круг жизни (12.10.2026, docs/dela-phone-12.md): направления дел, «Зачем», «Моё»,
 * принцип дня. Владелец: «каждое моё дело должно преследовать какое-то направление…
 * просто направление и Круг жизни», «не ради чего, а Зачем… многоуровневые планы».
 *
 * Дерево жизни (13.10.2026, docs/dela-phone-14.md, бриф 7.2): направлений девять, «из
 * них они подразделяются на цели, которые я ставлю, а дальше все дела идут в эти цели»;
 * «в каждом деле тоже должно быть это дерево и его место в нём: чтобы я понимал, зачем
 * я это делаю и что будет дальше». Цель — дело с пометкой `goal`: её нет в списках дел,
 * она живёт в дереве и в цепочке «Зачем». Главное направление дела — у корня цепочки,
 * свои направления, кроме главного, — метки.
 *
 * Эталон — веб (`server/pravka_dela/static/app.js`: `upOf`, `dirsOf`, `goalPath`,
 * `movement`, `mineTasks`, `goalPop`, `treeModel`, `treeList`, `placeBlock`) и правило 19
 * сервера; одно правило в трёх местах (сервер — `tasks.krug_dirs` и `krug.tree`, веб,
 * телефон): поправил веб — поправь здесь и тест `DelaKrugTest`. Справочник направлений и
 * принципы — Свод владельца (`krug.directions`, `krug.principles`): личное, в APK его
 * нет, едет синком.
 */
object DelaKrug {

    const val DIRECTIONS = "krug.directions"
    const val PRINCIPLES = "krug.principles"

    /** «Моё» на «Сегодня»: от одного до трёх дел, которых никто не ждёт (бриф, §9). */
    const val MINE_MAX = 3

    /** Закрытая цель — три события движения, закрытое дело — одно (`GOAL_POINTS` сервера). */
    const val GOAL_POINTS = 3

    /** Своих направлений у дела — главное и две метки (`krug.DIRS_MAX` сервера: больше он отвергнет). */
    const val DIRS_MAX = 3

    /** Ветка дерева без направления: дела, которым разборщик ещё не нашёл места. */
    const val NONE = "d:"

    /**
     * Направление: ключ (им размечены дела), место на круге (в 7.2 — 0…8, 0 сверху, дальше
     * через 40° по часовой; в 7.1 было 12 сверху), имя, короткое имя, формула, зона («Я»,
     * «Мир», «Семья»).
     */
    data class Direction(
        val key: String,
        val pos: Int,
        val name: String,
        val short: String,
        val formula: String,
        val zone: String = "",
    ) {
        val label: String get() = short.ifBlank { name }
    }

    /** Направления из Свода по кругу: сверху Здоровье, дальше по часовой. Нет записи — пусто. */
    fun directions(raw: Any? = Svod.value(DIRECTIONS)): List<Direction> {
        val a = raw as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = o.str("key").ifBlank { return@mapNotNull null }
            Direction(key, o.optInt("pos", 0), o.str("name").ifBlank { key }, o.str("short"), o.str("formula"), o.str("zone"))
        }.distinctBy { it.key }.sortedBy { it.pos % 12 }
    }

    fun principles(raw: Any? = Svod.value(PRINCIPLES)): List<String> {
        val a = raw as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" } }
    }

    /** Круг включён: сервер его знает и справочник пришёл. */
    fun on(s: Dela.Snapshot, dirs: List<Direction>): Boolean = s.krugOn && dirs.isNotEmpty()

    /** Дерево жизни включено: Круг есть и сервер знает цели (фича `tree`) — только тогда вкладка и поля целей. */
    fun treeOn(s: Dela.Snapshot, dirs: List<Direction>): Boolean = on(s, dirs) && s.treeOn

    /**
     * Принцип дня — по кругу, один в день (бриф, §6), как `krug.principle_of_day`:
     * номер дня — порядковый по-питоновски (`date.toordinal()` = эпоха + 719163).
     */
    fun principleOfDay(list: List<String>, today: String): String? {
        if (list.isEmpty()) return null
        val day = runCatching { LocalDate.parse(today.take(10)).toEpochDay() }.getOrNull() ?: return null
        return list[((day + 719_163L) % list.size).toInt()]
    }

    /** Зоны круга по порядку («Я», «Мир», «Семья»), в каждой — её направления подряд (`renderKrug` веба). */
    fun zones(dirs: List<Direction>): List<Pair<String, List<Direction>>> {
        val out = mutableListOf<Pair<String, MutableList<Direction>>>()
        for (d in dirs) {
            if (out.isEmpty() || out.last().first != d.zone) out += d.zone to mutableListOf()
            out.last().second += d
        }
        return out
    }

    /**
     * Угол оси направления на циферблате, градусы по часовой от верха (`dialSvg` веба):
     * девять направлений — по их месту (через 40°), иначе — по порядку, ровно по кругу.
     */
    fun angle(d: Direction, i: Int, n: Int): Float = (if (n == 9) d.pos else i) * 360f / n.coerceAtLeast(1)

    // ------------------------------------------------------------ цепочка вверх

    /**
     * Звено цепочки вверх (`upOf` веба): дело или цель — [task]; проект в Круге — без
     * [task], с [projectId]. У звена — свои направления и «Зачем».
     */
    data class Up(val task: Dela.Task?, val projectId: String, val directions: List<String>, val whyId: String) {
        val isProject: Boolean get() = task == null
    }

    /**
     * Цепочка вверх, как `tasks.krug_dirs` сервера: само дело, его «Зачем» и выше; у звена
     * без «Зачем» (кроме цели) — проект дела, если у проекта есть строка Круга, а от проекта
     * — цель, на которой он висит. Без кругов, не длиннее 60.
     */
    fun upOf(t: Dela.Task, s: Dela.Snapshot): List<Up> {
        val out = mutableListOf<Up>()
        val seen = HashSet<String>()
        var x: Up? = Up(t, t.projectId, t.directions, t.whyId)
        while (x != null && out.size < 60) {
            out += x
            x.task?.let { seen += it.id }
            if (x.whyId.isNotBlank()) {
                val w = s.tasks[x.whyId]
                x = if (w != null && w.id !in seen) Up(w, w.projectId, w.directions, w.whyId) else null
                continue
            }
            val task = x.task ?: break
            if (task.goal || task.projectId.isBlank()) break
            x = s.krug[task.projectId]?.let { Up(null, task.projectId, it.directions, it.whyId) }
        }
        return out
    }

    /**
     * Направления дела (`dirsOf` веба, `tasks.krug_dirs` сервера): `[главное, метки…]`, не
     * больше трёх. Главное — первое направление самого верхнего звена цепочки, у которого
     * направления есть (корень дерева); метки — свои направления дела, кроме главного. В 7.1
     * свои были сильнее наследованных — теперь своё под целью становится меткой. Чужое дело
     * ([me] не хозяин) — без направлений.
     */
    fun dirsOf(t: Dela.Task, s: Dela.Snapshot, me: String = ""): List<String> {
        if (me.isNotBlank() && t.ownerId.isNotBlank() && t.ownerId != me) return emptyList()
        val main = upOf(t, s).asReversed().firstNotNullOfOrNull { it.directions.firstOrNull() } ?: return emptyList()
        return (listOf(main) + t.directions.filter { it != main }).take(DIRS_MAX)
    }

    /** Открытые шаги к делу [id]: дела и подцели, у которых «Зачем» — оно. */
    fun steps(s: Dela.Snapshot, id: String): List<Dela.Task> = s.tasks.values.filter { it.open && it.whyId == id }

    /** Своё дело и всё, что ниже него по «Зачем», — туда его же не повесишь (иначе вышел бы круг). */
    fun below(s: Dela.Snapshot, id: String): Set<String> {
        val out = hashSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (x in s.tasks.values) if (x.whyId.isNotBlank() && x.whyId in out && out.add(x.id)) grew = true
        }
        return out
    }

    /** Короткое имя направления по ключу (`dirName` веба); не нашлось в справочнике — сам ключ. */
    fun label(key: String, dirs: List<Direction>): String = dirs.firstOrNull { it.key == key }?.label ?: key

    /** Путь цели словами (`goalPath` веба): «Здоровье › Еда › Белок 140+». Проекты в пути не называются. */
    fun goalPath(g: Dela.Task, s: Dela.Snapshot, dirs: List<Direction>): String {
        val main = dirsOf(g, s).firstOrNull()
        return (listOfNotNull(main?.let { label(it, dirs) }) + upOf(g, s).asReversed().mapNotNull { it.task?.title })
            .filter { it.isNotBlank() }.joinToString(" › ")
    }

    // ------------------------------------------------------------ движение и «Моё»

    /** Движение направления за неделю: закрыто дел и событий (дело — 1, цель — 3). */
    data class Move(val done: Int, val events: Int) {
        val radius: Double get() = radius(events)
    }

    private fun weekStart(today: String): String =
        runCatching { LocalDate.parse(today.take(10)).minusDays(6).toString() }.getOrDefault(today)

    /**
     * Движение за неделю по направлениям (`movement` веба, `krug.movement` сервера): закрытое
     * дело — 1, закрытая цель (узел дерева) — 3. Считается только в главном направлении: метка
     * свою сторону не двигает. До 15.10.2026 целью считалось и дело, у которого были шаги, но с
     * делами внутри дела (docs/dela-phone-22.md) так стало бы любое дело с драфтом внутри.
     */
    fun movement(s: Dela.Snapshot, me: String, today: String): Map<String, Move> {
        val since = weekStart(today)
        val out = HashMap<String, Move>()
        for (t in s.tasks.values) {
            if (t.status != Dela.DONE || (me.isNotBlank() && t.ownerId != me) || t.completedAt.isBlank()) continue
            if (DelaGroups.mskDay(t.completedAt) < since) continue
            val k = dirsOf(t, s, me).firstOrNull() ?: continue
            val m = out[k] ?: Move(0, 0)
            out[k] = Move(m.done + 1, m.events + if (t.goal) GOAL_POINTS else 1)
        }
        return out
    }

    /**
     * Шаг к цели (`towardGoal` веба, `krug.toward_goal` сервера): вверх по «Зачем» есть цель дерева. Дело внутри
     * обычного дела, над которым целей нет (драфт для клиентского дела), — не шаг к цели: «Моё» — то, что хочу я.
     */
    fun towardGoal(t: Dela.Task, s: Dela.Snapshot): Boolean {
        var x = t
        repeat(60) {
            val w = x.whyId.takeIf { it.isNotBlank() && it != x.id }?.let { s.tasks[it] } ?: return false
            if (w.goal) return true
            x = w
        }
        return false
    }

    /** Радиус точки движения: min(10, 3·√N) — корень, чтобы мелкие покупки не забивали шкалу. */
    fun radius(events: Int): Double = minOf(10.0, 3 * sqrt(events.toDouble()))

    /**
     * «Моё» (`mineTasks` веба, сервер — `krug.mine_from`): до трёх открытых своих
     * дел, не «отбить» и не «мониторить», у которых «Хочу сам» или «Зачем» к цели ([towardGoal])
     * без своих открытых дел внутри. Порядок: закреплённое «Хочу сам», потом главные направления, где за
     * неделю меньше всего движения, потом кто дольше ждёт. Цели — не дела: их здесь нет.
     * С 13.10.2026 — без того, что и так на виду: срок сегодня («Сегодня») или уже в «Сейчас»;
     * отсеивается до отбора трёх, а не после, иначе «Моё» показывало бы меньше, чем есть
     * (`krug.mine(skip=…)` сервера).
     */
    fun mine(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> {
        val mv = movement(s, me, today)
        val withSteps = s.tasks.values.filter { it.open && it.whyId.isNotBlank() }.map { it.whyId }.toHashSet()
        fun least(t: Dela.Task): Int = dirsOf(t, s, me).firstOrNull()?.let { mv[it]?.events } ?: 0
        return s.tasks.values.filter {
            it.open && !it.goal && (me.isBlank() || it.ownerId == me) && Dela.inSphere(it, sphere) && !it.owed && it.ball != Dela.WAITING &&
                !DelaGroups.isNow(it, today) && it.dueDate != today &&
                (it.want || (it.whyId.isNotBlank() && it.id !in withSteps && towardGoal(it, s)))
        }.sortedWith(compareBy<Dela.Task>({ !it.want }, { least(it) }, { it.createdAt }, { it.num })).take(MINE_MAX)
    }

    // ------------------------------------------------------------ дерево

    /** Что за узел: цель, проект в Круге или дело. Порядок — порядок детей узла (`treeModel` веба). */
    enum class Kind { GOAL, PROJECT, TASK }

    /** Узел дерева; [key] — «t:<id>» у цели и дела, «p:<id>» у проекта. */
    data class Item(val kind: Kind, val key: String, val task: Dela.Task? = null, val project: Dela.Project? = null)

    fun dirKey(key: String): String = "d:$key"

    private val collator: Collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }

    /**
     * Дерево жизни из копии (`treeModel` веба, сервер — `krug.tree`): дети узла (цели по
     * номеру, проекты по имени, дела по времени), открытых дел под узлом и движения под ним
     * за неделю. Ключи узлов: «t:<id>», «p:<id>», «d:<направление>» («d:» — без направления).
     */
    class Tree internal constructor(
        private val children: Map<String, List<Item>>,
        private val movedBy: Map<String, Int>,
        private val parent: (Dela.Task) -> String,
    ) {
        private val openBy = HashMap<String, Int>()

        fun kids(key: String): List<Item> = children[key].orEmpty()

        /** Открытых дел под узлом на любой глубине; цели не считаются — они не дела. */
        fun open(key: String): Int {
            openBy[key]?.let { return it }
            openBy[key] = 0 // круг (его не бывает, но вдруг) не вешает
            val n = kids(key).sumOf { (if (it.kind == Kind.TASK) 1 else 0) + open(it.key) }
            openBy[key] = n
            return n
        }

        /** Событий движения под узлом за 7 дней: закрытое дело — 1, цель — 3. */
        fun moved(key: String): Int = movedBy[key] ?: 0

        /**
         * Место дела в дереве — ключ родителя: «Зачем» (если цель открыта) → проект дела, если у
         * проекта есть строка Круга (не у целей) → главное направление («без зачем») → «d:».
         */
        fun parentKey(t: Dela.Task): String = parent(t)
    }

    fun treeModel(s: Dela.Snapshot, me: String, today: String): Tree {
        fun mine(t: Dela.Task) = me.isBlank() || t.ownerId == me
        val open = s.tasks.values.filter { it.open && mine(it) }
        val live = open.map { it.id }.toHashSet()
        fun parentKey(t: Dela.Task): String = when {
            t.whyId.isNotBlank() && t.whyId in live -> "t:" + t.whyId
            !t.goal && t.projectId.isNotBlank() && s.krug.containsKey(t.projectId) -> "p:" + t.projectId
            else -> dirKey(dirsOf(t, s, me).firstOrNull().orEmpty())
        }
        fun projKey(id: String): String {
            val p = s.krug[id] ?: Dela.KrugProject()
            return if (p.whyId.isNotBlank() && p.whyId in live) "t:" + p.whyId else dirKey(p.directions.firstOrNull().orEmpty())
        }
        val kids = HashMap<String, MutableList<Item>>()
        for (t in open) kids.getOrPut(parentKey(t)) { mutableListOf() } += Item(if (t.goal) Kind.GOAL else Kind.TASK, "t:" + t.id, task = t)
        for (id in s.krug.keys) {
            val p = s.projects[id]?.takeIf { it.live } ?: continue
            kids.getOrPut(projKey(id)) { mutableListOf() } += Item(Kind.PROJECT, "p:$id", project = p)
        }
        // Закрытое за неделю — каждому узлу вверх по дереву, до направления.
        val since = weekStart(today)
        val moved = HashMap<String, Int>()
        for (t in s.tasks.values) {
            if (t.status != Dela.DONE || !mine(t) || t.completedAt.isBlank() || DelaGroups.mskDay(t.completedAt) < since) continue
            var k: String? = parentKey(t)
            var n = 0
            while (k != null && n++ < 30) {
                moved[k] = (moved[k] ?: 0) + if (t.goal) GOAL_POINTS else 1
                k = when {
                    k.startsWith("t:") -> s.tasks[k.drop(2)]?.let(::parentKey)
                    k.startsWith("p:") -> projKey(k.drop(2))
                    else -> null
                }
            }
        }
        val order = DelaGroups.byTime(today)
        val byKind = Comparator<Item> { a, b ->
            val r = a.kind.compareTo(b.kind)
            if (r != 0) r else when (a.kind) {
                Kind.PROJECT -> collator.compare(a.project?.name.orEmpty(), b.project?.name.orEmpty())
                Kind.GOAL -> (a.task?.num ?: 0).compareTo(b.task?.num ?: 0)
                // Дела внутри обычного дела — по месту среди соседей (15.10.2026, `pos`), как сервер и веб.
                Kind.TASK -> if (DelaSteps.parentOf(a.task!!, s) != null) DelaSteps.BY_POS.compare(a.task, b.task!!) else order.compare(a.task, b.task!!)
            }
        }
        return Tree(kids.mapValues { (_, list) -> list.sortedWith(byKind) }, moved, ::parentKey)
    }

    /** Строка «Дерева» и «Колонок»: узел, глубина, раскрыт ли и есть ли что раскрывать. */
    data class Row(val item: Item, val depth: Int, val expanded: Boolean, val hasKids: Boolean)

    /** Свёрнута ли ветка с завода: направления и цели раскрыты, проекты — свёрнуты (`isFolded` веба). */
    fun foldedByDefault(key: String): Boolean = key.startsWith("p:")

    /**
     * Ветки узла [key] строками (`treeBranch` веба): цель — плюсиком, проект — только с
     * открытыми делами, дело — строкой, его шаги под ним видны всегда. [folded] — свёрнута ли
     * ветка (что запомнил человек; не помнит — [foldedByDefault]). Глубже 12 не идёт.
     */
    fun outline(m: Tree, key: String, folded: (String) -> Boolean, depth: Int = 0): List<Row> {
        val out = mutableListOf<Row>()
        val seen = HashSet<String>()
        fun walk(k: String, d: Int) {
            if (d > 12) return
            for (c in m.kids(k)) {
                if (!seen.add(c.key)) continue
                if (c.kind == Kind.PROJECT && m.open(c.key) == 0) continue
                val open = c.kind == Kind.TASK || !folded(c.key)
                out += Row(c, d, open, m.kids(c.key).isNotEmpty())
                if (open) walk(c.key, d + 1)
            }
        }
        walk(key, depth)
        return out
    }

    /** Кнопки уровней дерева: 1…[TREE_LEVELS] и «Всё» (0). */
    const val TREE_LEVELS = 4

    /**
     * Уровни дерева (`level` веба; владелец 13.10.2026: «наверху — какой уровень нужен: первый — только
     * направления, второй… нажал — и всё сжимается до этого уровня»). Направление — уровень 1, его цели,
     * проекты и дела — 2, и так вниз. [level] — до какого уровня видно (0 — «Всё»): ветка уровня ≥ [level]
     * свёрнута. Ответ — все ветки, которые сворачиваются (направления [roots], цели, проекты), с «свёрнута ли».
     */
    fun levelFolds(m: Tree, roots: List<String>, level: Int): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        fun fold(lv: Int) = level > 0 && lv >= level
        fun walk(k: String, lv: Int) {
            if (lv > 14) return
            for (c in m.kids(k)) {
                if (c.kind != Kind.TASK) out[c.key] = fold(lv)
                walk(c.key, lv + 1)
            }
        }
        for (r in roots) {
            out[r] = fold(1)
            walk(r, 2)
        }
        return out
    }

    /** Дело или цель с путём в дереве — строка «Списка» и листа «Куда повесить». */
    data class Placed(val task: Dela.Task, val path: String)

    /** «Без зачем»: в цепочке вверх нет ни одной цели (проект не в счёт). */
    fun loose(t: Dela.Task, s: Dela.Snapshot): Boolean = upOf(t, s).drop(1).none { !it.isProject }

    /**
     * «Список» дерева (`treeList` веба): открытые свои дела (без целей) плоско, по пути в
     * дереве. Фильтры: направление [dir] (главное или метка), цель [goal] — всё, что под ней
     * на любой глубине, [onlyLoose] — «без зачем». Путь — путь ближайшей цели, без неё —
     * главное направление.
     */
    fun treeList(
        s: Dela.Snapshot,
        me: String,
        sphere: String,
        dirs: List<Direction>,
        today: String,
        dir: String = "",
        goal: String = "",
        onlyLoose: Boolean = false,
    ): List<Placed> {
        val under = if (goal.isNotBlank()) below(s, goal) else null
        val order = DelaGroups.byTime(today)
        return s.tasks.values.filter {
            it.open && !it.goal && (me.isBlank() || it.ownerId == me) && Dela.inSphere(it, sphere) &&
                (dir.isBlank() || dir in dirsOf(it, s, me)) && (under == null || it.id in under) && (!onlyLoose || loose(it, s))
        }.map { t ->
            val g = upOf(t, s).drop(1).firstOrNull { !it.isProject }?.task
            Placed(t, g?.let { goalPath(it, s, dirs) } ?: dirsOf(t, s, me).firstOrNull()?.let { label(it, dirs) }.orEmpty())
        }.sortedWith { a, b -> collator.compare(a.path, b.path).takeIf { it != 0 } ?: order.compare(a.task, b.task) }
    }

    /** Открытые свои цели с путями, по пути — выбор цели в фильтре «Списка». */
    fun goalList(s: Dela.Snapshot, me: String, dirs: List<Direction>): List<Placed> =
        s.tasks.values.filter { it.open && it.goal && (me.isBlank() || it.ownerId == me) }
            .map { Placed(it, goalPath(it, s, dirs)) }.sortedWith { a, b -> collator.compare(a.path, b.path) }

    /**
     * Куда повесить (`goalPop` веба): открытые свои цели — кроме [exclude] (само дело и то, что
     * ниже него) и ориентиров (`no_steps`), с путём от направления. Дело с делами внутри (15.10.2026)
     * целью не стало: вкладывают «Вложить в…» (`DelaSteps.pool`), а не «Зачем».
     */
    fun goalPool(s: Dela.Snapshot, me: String, dirs: List<Direction>, exclude: Set<String> = emptySet()): List<Placed> =
        s.tasks.values.filter {
            it.open && (me.isBlank() || it.ownerId == me) && it.id !in exclude && !it.noSteps && it.goal
        }.map { Placed(it, goalPath(it, s, dirs)) }.sortedWith { a, b -> collator.compare(a.path, b.path) }

    /** Поиск в листе целей: каждое слово — где-то в пути (`goalPop` веба), до сорока строк. */
    fun findGoals(pool: List<Placed>, q: String): List<Placed> {
        val words = (Dela.norm(q) ?: "").split(' ').filter { it.isNotBlank() }
        return pool.filter { p -> Dela.norm(p.path).orEmpty().let { n -> words.all { n.contains(it) } } }.take(40)
    }

    /**
     * Повесить дело или цель [t] (`moveTo` веба): на цель [goal] — новое «Зачем»; прямо на
     * направление [dir] — «без зачем» (своё направление — им, а у дела проекта в Круге
     * направление и так от проекта); ни того ни другого — убрать «Зачем». Ответ — поля
     * правки и слова для тоста; поля null — вешать нельзя или незачем, слова — почему.
     */
    data class Hang(val set: JSONObject?, val said: String)

    fun hang(t: Dela.Task, s: Dela.Snapshot, dirs: List<Direction>, goal: Dela.Task? = null, dir: String = ""): Hang = when {
        goal != null && goal.noSteps -> Hang(null, "Под этой целью дел не заводят — она ориентир")
        goal != null && goal.id in below(s, t.id) -> Hang(null, "Цель не может быть шагом своего шага")
        goal != null && t.whyId == goal.id -> Hang(null, "")
        goal != null -> Hang(JSONObject().put("why_id", goal.id), "Зачем: " + goal.title)
        dir.isNotBlank() -> {
            val byProject = !t.goal && t.projectId.isNotBlank() && s.krug.containsKey(t.projectId)
            Hang(
                JSONObject().put("why_id", JSONObject.NULL).put("directions", Dela.arr(if (byProject) t.directions else listOf(dir))),
                "Направление: " + label(dir, dirs),
            )
        }
        t.whyId.isNotBlank() -> Hang(JSONObject().put("why_id", JSONObject.NULL), "Без «Зачем»")
        else -> Hang(null, "")
    }

    // ------------------------------------------------------------ место дела

    /**
     * Место дела в дереве (`placeBlock` веба): главное направление и метки, цели и проект
     * над делом сверху вниз ([chain]), шаги дела (цели первыми), соседние шаги той же цели
     * и строка «дальше» — что изменится, когда это сделаешь.
     */
    data class Place(
        val main: String,
        val labels: List<String>,
        val chain: List<Up>,
        val kids: List<Dela.Task>,
        val sibs: List<Dela.Task>,
        val next: String,
    )

    fun place(t: Dela.Task, s: Dela.Snapshot, today: String, me: String = ""): Place {
        val up = upOf(t, s).drop(1)
        val main = dirsOf(t, s, me).firstOrNull().orEmpty()
        val parent = up.firstOrNull()
        val order = compareBy<Dela.Task> { !it.goal }.then(DelaGroups.byTime(today))
        val g = parent?.task
        val sibs = if (g != null) steps(s, g.id).filter { it.id != t.id }.sortedWith(order) else emptyList()
        // У цели — её шаги и подцели; дела внутри обычного дела — блоком «Внутри» карточки (15.10.2026), здесь не повторяем.
        val kids = if (t.goal) steps(s, t.id).sortedWith(order) else emptyList()
        // Цель над целью: у дела-цели её «Зачем», проект не в счёт.
        val gg = g?.let { upOf(it, s).getOrNull(1)?.task }
        val next = when {
            t.goal -> {
                val sub = kids.count { it.goal }
                val parts = listOfNotNull(
                    sub.takeIf { it > 0 }?.let { plural(it, "подцель", "подцели", "подцелей") },
                    (kids.size - sub).takeIf { it > 0 }?.let { plural(it, "шаг", "шага", "шагов") },
                ).joinToString(" и ")
                if (parts.isNotEmpty()) "Под целью $parts" + (g?.let { " — а сама она ведёт к «${it.title}»" } ?: "")
                else "Пустая цель — сигнал, а не мусор: какой первый шаг к ней? Впиши его ниже."
            }
            // Дело внутри обычного дела (15.10.2026): родитель не закроется сам — поднимется, когда сделано всё внутри.
            g != null && !g.goal -> {
                val left = sibs.count { !it.goal }
                if (left > 0) "Сделаешь — внутри «${g.title}» останется ещё $left"
                else "Последнее открытое внутри «${g.title}» — сделаешь, и оно поднимется: без срока или позже — на сегодня"
            }
            g != null -> {
                val left = sibs.count { !it.goal }
                if (left > 0) "Сделаешь — к цели «${g.title}» останется ${plural(left, "шаг", "шага", "шагов")}"
                else "Это последний открытый шаг к цели «${g.title}»" + (gg?.let { " — дальше «${it.title}»" } ?: "")
            }
            parent != null && parent.isProject -> {
                val pg = up.getOrNull(1)?.task
                "Двигает проект «${s.projects[parent.projectId]?.name.orEmpty()}»" + (pg?.let { " — а он ведёт к «${it.title}»" } ?: "")
            }
            main.isNotBlank() -> "Висит прямо на направлении — «без зачем». Повесь на цель, и будет видно, куда это ведёт."
            else -> "Ни цели, ни направления: разборщик предложит место сам, или задай «Зачем»."
        }
        return Place(main, t.directions.filter { it != main }, up.asReversed(), kids, sibs, next)
    }

    /** Подпись цели в строке (`taskRow` веба): «практика · ежедневно», «цель · 3 шага» или просто «цель». */
    fun goalChip(t: Dela.Task, s: Dela.Snapshot): String? {
        // У обычного дела с делами внутри — «сначала: …» (`DelaSteps.firstLine`, 15.10.2026): целью оно не стало.
        if (!t.goal) return null
        val n = if (t.open) steps(s, t.id).size else 0
        return when {
            t.goal && t.rhythm.isNotBlank() -> "практика · " + t.rhythm
            n > 0 -> "цель · " + plural(n, "шаг", "шага", "шагов")
            t.goal -> "цель"
            else -> null
        }
    }

    /** Имена направлений коротко: «Здоровье, Дом». */
    fun names(keys: List<String>, dirs: List<Direction>): String = keys.joinToString(", ") { label(it, dirs) }

    /** «1 шаг», «3 шага», «5 шагов» — как `plural` веба. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return "$n " + when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }
}
