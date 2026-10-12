package ru.zf.pravka.core

import java.time.LocalDate
import kotlin.math.sqrt
import org.json.JSONArray
import ru.zf.pravka.core.Dela.str

/**
 * Круг жизни (12.10.2026, docs/dela-phone-12.md): направления дел, «Зачем», «Моё»,
 * принцип дня, дерево от направлений. Владелец: «каждое моё дело должно преследовать
 * какое-то направление… просто направление и Круг жизни», «не ради чего, а Зачем…
 * многоуровневые планы», «где-то должно быть дерево дел, прямо от направлений».
 *
 * Эталон — веб (`server/pravka_dela/static/app.js`: `dirsOf`, `stepsOf`, `movement`,
 * `mineTasks`, `principleToday`, `krugTree`) и правило 19 сервера; одно правило в трёх
 * местах (сервер — `tasks.krug_dirs`, веб, телефон): поправил веб — поправь здесь и
 * тест `DelaKrugTest`. Справочник направлений и принципы — Свод владельца
 * (`krug.directions`, `krug.principles`): личное, в APK его нет, едет синком.
 */
object DelaKrug {

    const val DIRECTIONS = "krug.directions"
    const val PRINCIPLES = "krug.principles"

    /** «Моё» на «Сегодня»: от одного до трёх дел, которых никто не ждёт (бриф, §9). */
    const val MINE_MAX = 3

    /** Направление: ключ (им размечены дела), место на круге (12 — сверху), имя, короткое имя, формула. */
    data class Direction(val key: String, val pos: Int, val name: String, val short: String, val formula: String) {
        val label: String get() = short.ifBlank { name }
    }

    /** Направления из Свода по кругу: сверху 12 (Здоровье), дальше по часовой. Нет записи — пусто. */
    fun directions(raw: Any? = Svod.value(DIRECTIONS)): List<Direction> {
        val a = raw as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = o.str("key").ifBlank { return@mapNotNull null }
            Direction(key, o.optInt("pos", 0), o.str("name").ifBlank { key }, o.str("short"), o.str("formula"))
        }.sortedBy { it.pos % 12 }
    }

    fun principles(raw: Any? = Svod.value(PRINCIPLES)): List<String> {
        val a = raw as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" } }
    }

    /** Круг включён: сервер его знает и справочник пришёл. */
    fun on(s: Dela.Snapshot, dirs: List<Direction>): Boolean = s.krugOn && dirs.isNotEmpty()

    /**
     * Принцип дня — по кругу, один в день (бриф, §6), как `krug.principle_of_day`:
     * номер дня — порядковый по-питоновски (`date.toordinal()` = эпоха + 719163).
     */
    fun principleOfDay(list: List<String>, today: String): String? {
        if (list.isEmpty()) return null
        val day = runCatching { LocalDate.parse(today.take(10)).toEpochDay() }.getOrNull() ?: return null
        return list[((day + 719_163L) % list.size).toInt()]
    }

    /**
     * Направления дела с наследованием (`dirsOf` веба, `tasks.krug_dirs` сервера):
     * свои; нет — первые непустые вверх по «Зачем»; нет и там — направления проекта
     * дела (или проекта ближайшей цели). Чужое дело ([me] не хозяин) — без направлений.
     */
    fun dirsOf(t: Dela.Task, s: Dela.Snapshot, me: String = ""): List<String> {
        if (me.isNotBlank() && t.ownerId.isNotBlank() && t.ownerId != me) return emptyList()
        val chain = chain(t, s)
        chain.firstOrNull { it.directions.isNotEmpty() }?.let { return it.directions }
        for (x in chain) s.krug[x.projectId]?.takeIf { x.projectId.isNotBlank() && it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** Дело и его цели вверх по «Зачем» — без кругов, не глубже 50. */
    fun chain(t: Dela.Task, s: Dela.Snapshot): List<Dela.Task> {
        val out = mutableListOf(t)
        val seen = hashSetOf(t.id)
        var x = t
        while (x.whyId.isNotBlank() && out.size < 50) {
            x = s.tasks[x.whyId] ?: break
            if (!seen.add(x.id)) break
            out += x
        }
        return out
    }

    /** Цели вверх по «Зачем» — без самого дела: «математика › сильная школа». */
    fun goals(t: Dela.Task, s: Dela.Snapshot): List<Dela.Task> = chain(t, s).drop(1)

    /** Открытые шаги к делу [id] (дела, у которых «Зачем» — оно). */
    fun steps(s: Dela.Snapshot, id: String): List<Dela.Task> = s.tasks.values.filter { it.open && it.whyId == id }

    /** Своё дело и все его шаги вниз по цепочке — их нельзя сделать «Зачем» этого дела (вышел бы круг). */
    fun below(s: Dela.Snapshot, id: String): Set<String> {
        val out = hashSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (x in s.tasks.values) if (x.whyId.isNotBlank() && x.whyId in out && out.add(x.id)) grew = true
        }
        return out
    }

    /**
     * Движение за неделю по направлениям (`movement` веба): закрытое дело — 1,
     * закрытая цель (у неё были шаги) — 3. Несколько направлений у дела — событие каждому.
     */
    fun movement(s: Dela.Snapshot, me: String, today: String): Map<String, Int> {
        val since = runCatching { LocalDate.parse(today).minusDays(6).toString() }.getOrDefault(today)
        val goals = s.tasks.values.mapNotNull { it.whyId.ifBlank { null } }.toHashSet()
        val out = HashMap<String, Int>()
        for (t in s.tasks.values) {
            if (t.status != Dela.DONE || (me.isNotBlank() && t.ownerId != me) || t.completedAt.isBlank()) continue
            if (DelaGroups.mskDay(t.completedAt) < since) continue
            for (k in dirsOf(t, s, me)) out[k] = (out[k] ?: 0) + if (t.id in goals) 3 else 1
        }
        return out
    }

    /** Радиус точки движения: min(10, 3·√N) — корень, чтобы мелкие покупки не забивали шкалу. */
    fun radius(events: Int): Double = minOf(10.0, 3 * sqrt(events.toDouble()))

    /**
     * «Моё» (`mineTasks` веба, сервер — `krug.mine_from`): до трёх открытых своих
     * дел, не «отбить» и не «мониторить», у которых «Хочу сам» или «Зачем» без своих
     * открытых шагов. Порядок: закреплённое «Хочу сам», потом направления, где за
     * неделю меньше всего движения, потом кто дольше ждёт.
     */
    fun mine(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> {
        val mv = movement(s, me, today)
        val withSteps = s.tasks.values.filter { it.open && it.whyId.isNotBlank() }.map { it.whyId }.toHashSet()
        fun least(t: Dela.Task): Int = dirsOf(t, s, me).minOfOrNull { mv[it] ?: 0 } ?: 0
        return s.tasks.values.filter {
            it.open && (me.isBlank() || it.ownerId == me) && Dela.inSphere(it, sphere) && !it.owed && it.ball != Dela.WAITING &&
                (it.want || (it.whyId.isNotBlank() && it.id !in withSteps))
        }.sortedWith(compareBy<Dela.Task>({ !it.want }, { least(it) }, { it.createdAt }, { it.num })).take(MINE_MAX)
    }

    /** Дела направления [key] деревом «Зачем»: цели с шагами лесенкой, потом остальное. Глубина — для отступа. */
    data class Line(val task: Dela.Task, val depth: Int)

    fun tree(items: List<Dela.Task>, s: Dela.Snapshot, today: String): List<Line> {
        val ids = items.map { it.id }.toHashSet()
        val kids = s.tasks.values.filter { it.open && it.whyId.isNotBlank() }.groupBy { it.whyId }
        val order = DelaGroups.byTime(today)
        val out = mutableListOf<Line>()
        val seen = HashSet<String>()
        fun walk(t: Dela.Task, depth: Int) {
            if (!seen.add(t.id)) return
            out += Line(t, depth)
            if (depth < 8) kids[t.id].orEmpty().sortedWith(order).forEach { walk(it, depth + 1) }
        }
        val roots = items.filter { it.whyId.isBlank() || it.whyId !in ids }
        // Цели (у них есть шаги) — сверху, лесенкой; остальное — по проектам и времени.
        val (goals, rest) = roots.partition { kids.containsKey(it.id) }
        goals.sortedWith(order).forEach { walk(it, 0) }
        rest.sortedWith(compareBy<Dela.Task>({ s.projects[it.projectId]?.name ?: "￿" }).then(order)).forEach { walk(it, 0) }
        return out
    }

    /** Имена направлений коротко: «Здоровье, Дом». */
    fun names(keys: List<String>, dirs: List<Direction>): String =
        keys.joinToString(", ") { k -> dirs.firstOrNull { it.key == k }?.label ?: k }
}
