package ru.zf.pravka.core

import java.text.Collator
import java.time.LocalDate
import java.time.LocalDateTime
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
     * делами внутри дела (docs/dela-phone-23.md) так стало бы любое дело с драфтом внутри.
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
        val withSteps = s.tasks.values.filter { it.open && it.deed && it.whyId.isNotBlank() }.map { it.whyId }.toHashSet()
        fun least(t: Dela.Task): Int = dirsOf(t, s, me).firstOrNull()?.let { mv[it]?.events } ?: 0
        return s.tasks.values.filter {
            it.open && it.deed && (me.isBlank() || it.ownerId == me) && Dela.inSphere(it, sphere) && !it.owed && it.ball != Dela.WAITING &&
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
        val open = s.tasks.values.filter { it.open && !it.remind && mine(it) }
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
            it.open && it.deed && (me.isBlank() || it.ownerId == me) && Dela.inSphere(it, sphere) &&
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

    // ------------------------------------------------------------ разборщик Круга: когда посмотрит

    private val MSK: java.time.ZoneId = java.time.ZoneId.of("Europe/Moscow")

    /** Сейчас по Москве — расписание разборщика живёт по Москве, а не по часам телефона. */
    fun mskNow(): LocalDateTime = LocalDateTime.now(MSK)

    /**
     * Слот разборщика Круга (`krug_auto.is_slot` сервера; расписание — слово владельца 12.10.2026): будни — :00 и :30
     * с 8:00 до 23:30, выходные — :00 с 8:00 до 23:00, и каждый день 00:00. Ночью (01:00–07:59) слотов нет.
     */
    fun isKrugSlot(t: LocalDateTime): Boolean {
        if (t.minute != 0 && t.minute != 30) return false
        if (t.hour == 0) return t.minute == 0
        if (t.hour < 8) return false
        return t.minute == 0 || t.dayOfWeek.value <= 5
    }

    /**
     * Ближайший слот разборщика ПОСЛЕ этой минуты (время — московское): будний 09:07 → 09:30, суббота 10:10 → 11:00,
     * 01:00 → 08:00. Ровно на слоте — следующий: пачка этого уже ушла.
     */
    fun nextKrugSlot(now: LocalDateTime): LocalDateTime {
        var t = now.withSecond(0).withNano(0).plusMinutes(1)
        t = when {
            t.minute == 0 || t.minute == 30 -> t
            t.minute < 30 -> t.withMinute(30)
            else -> t.withMinute(0).plusHours(1)
        }
        repeat(100) {
            if (isKrugSlot(t)) return t
            t = t.plusMinutes(30)
        }
        return t
    }

    /** «в 09:30» — когда разборщик Круга посмотрит дело без места (16.10.2026: вместо «предложит сам» / «в ближайшие полчаса»). */
    fun krugAt(now: LocalDateTime): String = nextKrugSlot(now).let { String.format(Locale.ROOT, "в %02d:%02d", it.hour, it.minute) }

    // ------------------------------------------------------------ место дела

    /** Сколько соседей открытого дела видно в «Месте в дереве» (само дело — всегда, в их порядке). */
    const val PLACE_SIBS = 8

    /** Сколько строк ниже открытого дела (два уровня); дальше — «и ещё N». */
    const val PLACE_DOWN = 12

    /** Роль строки «Места в дереве»: выше дела, сосед, само дело, внутри него, «и ещё N». */
    enum class Role { UP, SIB, ME, KID, REST }

    /** Значок строки — у каждой строки своё место под него, чтобы ступени не плыли: цель, дело, само дело, проект. */
    enum class Mark { GOAL, DEED, ME, PROJECT, NONE }

    /** Что внутри свёрнутого узла — прямые дети: подцели, проекты, дела. */
    data class Inside(val goals: Int = 0, val projects: Int = 0, val deeds: Int = 0) {
        val total: Int get() = goals + projects + deeds

        /** «3 внутри» (одни дела) или «2 подцели, 1 дело». */
        val words: String
            get() = if (goals == 0 && projects == 0) "$deeds внутри" else listOfNotNull(
                goals.takeIf { it > 0 }?.let { plural(it, "подцель", "подцели", "подцелей") },
                projects.takeIf { it > 0 }?.let { plural(it, "проект", "проекта", "проектов") },
                deeds.takeIf { it > 0 }?.let { plural(it, "дело", "дела", "дел") },
            ).joinToString(", ")
    }

    /**
     * Строка «Места в дереве»: глубина (0 — направление в шапке), роль, значок, текст; дело или цель — [task],
     * проект — [projectId] (тап открывает его страницу, а не карточку); [more] — у свёрнутого узла, что внутри.
     */
    data class PlaceRow(
        val depth: Int,
        val role: Role,
        val mark: Mark,
        val text: String,
        val task: Dela.Task? = null,
        val projectId: String = "",
        val more: Inside? = null,
    )

    /**
     * Место дела в дереве (`placeBlock` веба): главное направление и метки, цели и проект
     * над делом сверху вниз ([chain]), шаги дела (цели первыми), соседние шаги той же цели
     * и строка «дальше» — что изменится, когда это сделаешь. Рисуется строками [rows]
     * (16.10.2026, записи 3 и 10 отладки): предки, уровень родителя — соседи вместе с самим
     * делом в их порядке, под делом — то, что внутри него, на два уровня. [kids] и [sibs] —
     * для строки «дальше».
     */
    data class Place(
        val main: String,
        val labels: List<String>,
        val chain: List<Up>,
        val kids: List<Dela.Task>,
        val sibs: List<Dela.Task>,
        val next: String,
        val rows: List<PlaceRow> = emptyList(),
    )

    /**
     * [at] — московское время сейчас: дело без места честно говорит, когда разборщик Круга его посмотрит
     * ([nextKrugSlot]); null — без времени.
     */
    fun place(t: Dela.Task, s: Dela.Snapshot, today: String, me: String = "", at: LocalDateTime? = null): Place {
        val up = upOf(t, s).drop(1)
        val main = dirsOf(t, s, me).firstOrNull().orEmpty()
        val parent = up.firstOrNull()
        val order = compareBy<Dela.Task> { !it.goal }.then(DelaGroups.byTime(today))
        val g = parent?.task
        // Напоминание — не шаг и не дело внутри (правило 29): в «Месте в дереве» его нет (16.10.2026; `steps` его
        // ещё не отсеивает — вагон E плана).
        val sibs = if (g != null) {
            steps(s, g.id).filter { it.id != t.id && !it.remind }
                .sortedWith(if (g.goal) order else compareBy<Dela.Task> { !it.goal }.then(DelaSteps.BY_POS))
        } else emptyList()
        // У цели — её шаги и подцели (для «дальше»); дела внутри обычного дела — строками [rows] и блоком «Внутри».
        val kids = if (t.goal) steps(s, t.id).filter { !it.remind }.sortedWith(order) else emptyList()
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
            else -> nowhereNext(t, s, at)
        }
        val chain = up.asReversed()
        return Place(main, t.directions.filter { it != main }, chain, kids, sibs, next, rows(t, s, today, me, chain, parent))
    }

    /**
     * «Ни цели, ни направления» — что будет дальше, честно (16.10.2026, запись 5 отладки: «предложит сам» без
     * времени читалось как «никогда»). Разборщик Круга (`krug_auto.candidates` сервера) не берёт напоминания, дела
     * сделок и дела клиентов; о деле, про которое уже спросил, — ответ ждёт в «Новом»; остальное смотрит в ближайший слот.
     */
    private fun nowhereNext(t: Dela.Task, s: Dela.Snapshot, at: LocalDateTime?): String {
        val head = "Ни цели, ни направления: "
        val asked = s.suggestions.values.any { it.taskId == t.id && it.source == "krug" && it.status == "pending" }
        val client = t.projectId.isNotBlank() && s.projects[t.projectId]?.kind == "client"
        return head + when {
            asked -> "разборщик Круга уже спросил, куда его, — ответь в «Новом» или задай «Зачем»."
            t.remind -> "напоминания разборщик Круга не раскладывает — задай «Зачем», если нужно."
            t.dealId.isNotBlank() || client -> "дела сделок и клиентов разборщик Круга не берёт — задай «Зачем» сам."
            at != null -> "разборщик Круга посмотрит ${krugAt(at)} — или задай «Зачем»."
            else -> "разборщик Круга посмотрит в ближайший слот — или задай «Зачем»."
        }
    }

    /**
     * Строки «Места в дереве» (16.10.2026, записи 3 и 10 отладки). Предки сверху; на уровне родителя — соседи ВМЕСТЕ
     * с самим делом в их порядке (как в «Дереве»: цели по номеру, проекты, дела по времени, у родителя-дела — по
     * `pos`), до [PLACE_SIBS], само дело видно всегда; под делом — то, что внутри (у цели — подцели, проекты и дела,
     * у обычного дела — его дела внутри), на два уровня и не больше [PLACE_DOWN] строк, дальше — «и ещё N». Узел,
     * который не раскрыт, несёт [Inside]. Дети — одной картой [treeModel] за проход; напоминаний в ней нет. До 16.10
     * у дела его дела внутри здесь не повторялись (решение 15.10 «в „Внутри“ — и хватит»): владелец просит видеть
     * и дерево ниже дела — «Внутри» с флажками и порядком остаётся, дерево — для того, чтобы ходить.
     */
    private fun rows(t: Dela.Task, s: Dela.Snapshot, today: String, me: String, chain: List<Up>, parent: Up?): List<PlaceRow> {
        val m = treeModel(s, me, today)
        fun items(key: String): List<Item> = m.kids(key).filter { it.kind != Kind.PROJECT || m.open(it.key) > 0 }
        fun inside(key: String): Inside? {
            val k = items(key)
            return Inside(k.count { it.kind == Kind.GOAL }, k.count { it.kind == Kind.PROJECT }, k.count { it.kind == Kind.TASK })
                .takeIf { it.total > 0 }
        }
        fun projectName(id: String) = s.projects[id]?.name ?: "проект"
        fun goalText(x: Dela.Task) = x.title + if (x.goal && x.rhythm.isNotBlank()) " · ${x.rhythm}" else ""
        fun row(c: Item, depth: Int, role: Role, more: Inside?): PlaceRow = when (c.kind) {
            Kind.PROJECT -> PlaceRow(depth, role, Mark.PROJECT, c.project?.name ?: projectName(c.key.drop(2)), projectId = c.key.drop(2), more = more)
            Kind.GOAL -> PlaceRow(depth, role, Mark.GOAL, goalText(c.task!!), task = c.task, more = more)
            Kind.TASK -> PlaceRow(depth, role, Mark.DEED, c.task!!.title, task = c.task, more = more)
        }
        val out = mutableListOf<PlaceRow>()
        chain.forEachIndexed { i, x ->
            val task = x.task
            out += if (task == null) PlaceRow(i + 1, Role.UP, Mark.PROJECT, projectName(x.projectId), projectId = x.projectId)
            else PlaceRow(i + 1, Role.UP, if (task.goal) Mark.GOAL else Mark.DEED, goalText(task), task = task)
        }
        val d = chain.size + 1
        val meKey = "t:" + t.id
        // Уровень родителя: у дела прямо на направлении соседей нет — их видно в «Дереве», здесь было бы всё направление.
        val parentKey = when {
            parent == null -> null
            parent.task != null -> "t:" + parent.task.id
            else -> "p:" + parent.projectId
        }
        var level = parentKey?.let(::items).orEmpty()
        // Само дело не в дереве (сделано, родитель закрыт) — всё равно на своём месте, первым.
        if (level.none { it.key == meKey }) level = listOf(Item(if (t.goal) Kind.GOAL else Kind.TASK, meKey, task = t)) + level
        val at = level.indexOfFirst { it.key == meKey }
        val shown = when {
            level.size <= PLACE_SIBS -> level
            at < PLACE_SIBS -> level.take(PLACE_SIBS)
            else -> level.take(PLACE_SIBS - 1) + level[at]
        }
        for (c in shown) {
            if (c.key != meKey) {
                out += row(c, d, Role.SIB, inside(c.key))
                continue
            }
            out += PlaceRow(d, Role.ME, Mark.ME, t.title, task = t)
            // Под делом — два уровня: первый целиком (до предела), второй — у тех, чьи дети влезают целиком.
            val l1 = items(meKey)
            val down = l1.take(PLACE_DOWN)
            var room = PLACE_DOWN - down.size
            for (k in down) {
                val l2 = items(k.key)
                val open = l2.isNotEmpty() && l2.size <= room
                out += row(k, d + 1, Role.KID, if (open) null else inside(k.key))
                if (!open) continue
                room -= l2.size
                for (k2 in l2) out += row(k2, d + 2, Role.KID, inside(k2.key))
            }
            if (l1.size > down.size) out += PlaceRow(d + 1, Role.REST, Mark.NONE, "и ещё ${l1.size - down.size}")
        }
        if (level.size > shown.size) out += PlaceRow(d, Role.REST, Mark.NONE, "рядом ещё ${level.size - shown.size}")
        return out
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
