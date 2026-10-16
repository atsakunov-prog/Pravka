package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сделанное флажком стоит на месте (отладка 16.10.2026 вечер, запись 30: «текст в колонке прыгает… промахиваюсь и
 * кликаю на другое»). Строка не исчезает в тот же кадр и не сдвигает список под пальцем: списки считают её по копии до
 * касания, пока вкладка её держит. Данные выдуманные.
 */
class DelaLingerTest {

    private val today = "2026-10-16"
    private val nowIso = "2026-10-16T22:30:00+03:00"

    private fun task(id: String, f: (Dela.Task) -> Dela.Task = { it }) =
        f(Dela.Task(id = id, title = "Дело $id", ownerId = "me", createdBy = "me", dueDate = today, createdAt = "2026-10-10T10:00:00+03:00"))

    private fun snap(vararg t: Dela.Task) = Dela.Snapshot(tasks = t.associateBy { it.id })

    private fun ids(list: List<Dela.Task>) = list.map { it.id }

    /** Флажок у «b»: очередь закрывает дело сразу (как `Dela.overlay` поверх очереди на телефоне). */
    private fun done(s: Dela.Snapshot, id: String) = Dela.overlay(s, listOf(Dela.statusOp("task.done", id)), "me", today, nowIso)

    @Test
    fun `сделанная строка стоит на своём месте, пока её держат`() {
        val s = snap(task("a"), task("b") { it.copy(owed = true) }, task("c") { it.copy(ball = Dela.WAITING) })
        // Порядок — как рисует «Сегодня» на главной: группами, внутри по времени.
        val before = ids(DelaGroups.todayByGroups(s, "me", today, "all"))
        assertEquals(listOf("b", "a", "c"), before)
        val live = done(s, "b")
        // Без удержания — строка уходит в тот же кадр, всё ниже поднимается (так прыгало).
        assertFalse("b" in ids(DelaGroups.todayTasks(live, "me", today, "all")))
        val held = DelaLinger.tap(emptyMap(), s.tasks.getValue("b"))
        val view = DelaLinger.view(live, held)
        assertEquals(before, ids(DelaGroups.todayByGroups(view, "me", today, "all")))
        // Группа «Отбить», где «b» была одна, не пропадает вместе с карточкой.
        val groups = DelaGroups.nested(DelaGroups.todayTasks(view, "me", today, "all"), listOf(DelaGroups.Level.GRP), view, today)
        assertEquals(listOf("Отбить", "Запустить", "Мониторить"), groups.map { it.title })
        // Рисуется сделанной: живая копия её закрыла.
        assertEquals(setOf("b"), DelaLinger.doneHere(held, live))
        // Сервер, синк и другие экраны видят её сделанной — держит только список вкладки.
        assertFalse(live.tasks.getValue("b").open)
    }

    @Test
    fun `в «Сейчас» сделанное тоже не уходит из-под пальца`() {
        val s = snap(task("a") { it.copy(focusOn = today) }, task("b") { it.copy(focusOn = today) }, task("c") { it.copy(focusOn = today) })
        val before = ids(DelaGroups.nowOrdered(s, "me", today))
        val live = done(s, "a")
        val view = DelaLinger.view(live, DelaLinger.tap(emptyMap(), s.tasks.getValue("a")))
        assertEquals(before, ids(DelaGroups.nowOrdered(view, "me", today)))
    }

    @Test
    fun `повторный тап — вернуть в работу, держать незачем`() {
        val s = snap(task("a"), task("b"))
        val live = done(s, "b")
        val held = DelaLinger.tap(emptyMap(), s.tasks.getValue("b"))
        // Тап по стоящей сделанной строке: решает живое дело (закрыто) — его отпускаем, а очередь шлёт task.reopen.
        val after = DelaLinger.tap(held, live.tasks.getValue("b"))
        assertTrue(after.isEmpty())
        val reopened = Dela.overlay(live, listOf(Dela.statusOp("task.reopen", "b")), "me", today, nowIso)
        assertTrue(reopened.tasks.getValue("b").open)
        // Вернул, а держать не перестали (тап из карточки) — копия не подменяется: живое и так открыто.
        assertSame(reopened, DelaLinger.view(reopened, held))
        assertTrue(DelaLinger.doneHere(held, reopened).isEmpty())
    }

    @Test
    fun `отпускаем через четыре секунды без касаний, касание продлевает`() {
        assertEquals(DelaLinger.HOLD_MS, DelaLinger.left(1_000L, 1_000L))
        assertEquals(1_000L, DelaLinger.left(1_000L, 1_000L + DelaLinger.HOLD_MS - 1_000L))
        assertEquals(0L, DelaLinger.left(1_000L, 1_000L + DelaLinger.HOLD_MS))
        assertEquals(0L, DelaLinger.left(1_000L, 99_000L))
        // Ничего не держим — копия та же самая, без лишней перерисовки.
        val s = snap(task("a"))
        assertSame(s, DelaLinger.view(s, emptyMap()))
    }
}
