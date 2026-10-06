package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.pravka.data.ZasechkaStore

// Дела в Засечке: ▶, «идёт», «в ленте», комментарий в дело (05.10.2026).
class ZasechkaTasksTest {

    private val h = 3_600_000L
    private val m = 60_000L
    private val now = 1_000 * h
    private var nextId = 1L

    private fun entry(start: Long, end: Long, task: String = "", title: String = "Дело") = ZasechkaStore.Entry(
        id = nextId++, start = start, end = end, raw = "", title = title, category = "Работа: текущая",
        client = "", useful = 0, source = if (task.isBlank()) "voice" else "task", synced = false, createdAt = start,
        task = task,
    )

    private fun task(
        id: String,
        num: Int,
        due: String = "",
        focus: String = "",
        ball: String = Dela.MINE,
        owner: String = "me",
        status: String = Dela.OPEN,
    ) = Dela.Task(id = id, num = num, title = "Дело $num", ownerId = owner, ball = ball, dueDate = due, focusOn = focus, status = status)

    private fun snap(vararg t: Dela.Task) = Dela.Snapshot(tasks = t.associateBy { it.id })

    @Test
    fun `время дела - все его записи, идущая до сейчас`() {
        val entries = listOf(
            entry(now - 5 * h, now - 4 * h, task = "a"),
            entry(now - 4 * h, now - 3 * h),
            entry(now - 20 * m, 0L, task = "a"),
            entry(now - 2 * h, now - 90 * m, task = "b"),
        )
        val spent = ZasechkaTasks.spent(entries, now)
        assertEquals(80 * m, spent["a"])
        assertEquals(30 * m, spent["b"])
        assertEquals("a", ZasechkaTasks.running(entries))
        assertEquals("", ZasechkaTasks.running(entries.dropLast(2)))
    }

    @Test
    fun `список взяться - идущее, сейчас, недавнее, сегодня и просрочка, без повторов`() {
        val today = "2026-10-05"
        val s = snap(
            task("run", 1),
            task("focus", 2, focus = today),
            task("recent", 3),
            task("old", 4),
            task("due", 5, due = "2026-10-01"),
            task("future", 6, due = "2026-10-20"),
            task("waiting", 7, due = "2026-10-01", ball = Dela.WAITING),
            task("other", 8, due = "2026-10-01", owner = "natasha"),
            task("closed", 9, due = "2026-10-01", status = Dela.DONE),
        )
        val entries = listOf(
            entry(now - 30 * 24 * h, now - 30 * 24 * h + h, task = "old"),
            entry(now - 2 * 24 * h, now - 2 * 24 * h + h, task = "recent"),
            entry(now - 3 * h, now - 2 * h, task = "due"),
            entry(now - 10 * m, 0L, task = "run"),
        )
        val ids = ZasechkaTasks.shortlist(s, "me", today, entries, now).map { it.id }
        // «due» начато из ленты сегодня — оно выше «recent», а просрочкой второй раз не встаёт.
        assertEquals(listOf("run", "focus", "due", "recent"), ids)

        // По частям: идущее не из «Сейчас» — первым, «Сейчас» — своим заголовком, ниже остальное.
        val p = ZasechkaTasks.parts(s, "me", today, entries, now)
        assertEquals("run", p.running?.id)
        assertEquals(listOf("focus"), p.now.map { it.id })
        assertEquals(listOf("due", "recent"), p.rest.map { it.id })
        assertEquals(ids, p.all.map { it.id })
    }

    @Test
    fun `идущее из «Сейчас» — среди «Сейчас» первым, отдельной строки нет`() {
        val today = "2026-10-05"
        val s = snap(task("a", 1, focus = today, due = "2026-10-01"), task("b", 2, focus = today), task("c", 3, due = today))
        val p = ZasechkaTasks.parts(s, "me", today, listOf(entry(now - 10 * m, 0L, task = "b")), now)
        assertNull(p.running)
        assertEquals(listOf("b", "a"), p.now.map { it.id })
        assertEquals(listOf("c"), p.rest.map { it.id })
        // Ничего не идёт и «Сейчас» пусто — весь список «остальным», заголовков нет.
        val q = ZasechkaTasks.parts(snap(task("c", 3, due = today)), "me", today, emptyList(), now)
        assertNull(q.running)
        assertEquals(emptyList<Dela.Task>(), q.now)
        assertEquals(listOf("c"), q.rest.map { it.id })
    }

    @Test
    fun `в дело уезжает дописанное, правка старого - нет`() {
        assertEquals("обсудили бюджет", ZasechkaTasks.added("", "обсудили бюджет"))
        assertEquals("до пятницы", ZasechkaTasks.added("обсудили бюджет", "обсудили бюджет\nдо пятницы"))
        assertNull(ZasechkaTasks.added("обсудили бюджет", "обсудили бюджет"))
        assertNull(ZasechkaTasks.added("обсудили бюджет", "обсудили план"))
        assertNull(ZasechkaTasks.added("обсудили бюджет", ""))
    }

    @Test
    fun `время словами`() {
        assertEquals("25 мин", ZasechkaTasks.label(25 * m))
        assertEquals("1 ч 20 мин", ZasechkaTasks.label(80 * m))
        assertEquals("3 ч", ZasechkaTasks.label(3 * h))
    }
}
