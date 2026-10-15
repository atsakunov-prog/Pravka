package ru.zf.pravka.core

import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Напоминания уведомлением телефона (захват, шаг 0 петли, `core/ReminderPlan.kt`): что пора показать, когда будить,
 * догон пропущенного за сутки, память показа, по месту — мигом приезда, кнопки «Сделано · Через час · Завтра» —
 * обычными операциями очереди, отметка показа — `task.reminded {via: phone}`.
 */
class ReminderPlanTest {

    private val msk: ZoneId = ZoneId.of("Europe/Moscow")
    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun task(id: String, at: String, place: String = "", kind: String = Dela.KIND_REMIND, status: String = Dela.OPEN, owner: String = "me", due: String = "") =
        Dela.Task(id = id, num = id.hashCode() and 0xff, title = "Дело $id", ownerId = owner, remindAt = at, remindPlace = place, kind = kind, status = status, dueDate = due)

    private fun snap(vararg t: Dela.Task, features: Set<String> = emptySet()) = Dela.Snapshot(tasks = t.associateBy { it.id }, features = features)

    @Test
    fun `в срок — показать, раньше — будильник на миг`() {
        val s = snap(task("a", "2026-10-15T12:00:00+03:00"), task("b", "2026-10-15T19:00:00+03:00"))
        val before = ms("2026-10-15T10:30:00+03:00")
        assertTrue(ReminderPlan.due(s, "me", before, emptySet()).isEmpty())
        assertEquals(ms("2026-10-15T12:00:00+03:00"), ReminderPlan.next(s, "me", before, emptySet()))
        val noon = ms("2026-10-15T12:00:05+03:00")
        val due = ReminderPlan.due(s, "me", noon, emptySet())
        assertEquals(listOf("a"), due.map { it.task.id })
        assertFalse("пять секунд — не опоздание", due.single().late)
        // Показал — следующий будильник на 19:00, тот же миг второй раз не звенит.
        val shown = due.map { it.key }.toSet()
        assertTrue(ReminderPlan.due(s, "me", noon + 60_000L, shown).isEmpty())
        assertEquals(ms("2026-10-15T19:00:00+03:00"), ReminderPlan.next(s, "me", noon, shown))
    }

    @Test
    fun `догон — не старше суток, с «должно было»`() {
        val s = snap(task("a", "2026-10-15T12:00:00+03:00"), task("old", "2026-10-13T12:00:00+03:00"))
        val now = ms("2026-10-15T18:00:00+03:00")
        val due = ReminderPlan.due(s, "me", now, emptySet())
        assertEquals(listOf("a"), due.map { it.task.id })
        assertTrue(due.single().late)
        assertEquals("должно было в 12:00 · Дела", ReminderPlan.body(due.single(), now, msk))
        // Вчерашнее — «вчера».
        val y = snap(task("y", "2026-10-14T21:00:00+03:00"))
        val d2 = ReminderPlan.due(y, "me", ms("2026-10-15T08:00:00+03:00"), emptySet()).single()
        assertEquals("должно было вчера в 21:00 · Дела", ReminderPlan.body(d2, ms("2026-10-15T08:00:00+03:00"), msk))
    }

    @Test
    fun `первый запуск не звенит прошлым, будущее — звенит`() {
        val s = snap(task("past", "2026-10-15T09:00:00+03:00"), task("next", "2026-10-15T12:00:00+03:00"))
        val now = ms("2026-10-15T10:30:00+03:00")
        val seed = ReminderPlan.seed(s, "me", now)
        assertEquals(setOf(ReminderPlan.key(s.tasks["past"]!!)), seed)
        assertTrue(ReminderPlan.due(s, "me", now, seed).isEmpty())
        assertEquals(ms("2026-10-15T12:00:00+03:00"), ReminderPlan.next(s, "me", now, seed))
    }

    @Test
    fun `переставили — новый ключ, звенит снова, закрытое и чужое — нет`() {
        val a = task("a", "2026-10-15T12:00:00+03:00")
        val shown = setOf(ReminderPlan.key(a))
        val moved = a.copy(remindAt = "2026-10-15T13:00:00+03:00")
        assertTrue(ReminderPlan.key(moved) !in shown)
        assertEquals(1, ReminderPlan.due(snap(moved), "me", ms("2026-10-15T13:00:01+03:00"), shown).size)
        // Одна и та же точка во времени в разной записи — один ключ: сервер и телефон пишут смещение по-разному.
        assertEquals(ReminderPlan.key(a), ReminderPlan.key(a.copy(remindAt = "2026-10-15T09:00:00Z")))
        val now = ms("2026-10-15T12:30:00+03:00")
        assertTrue(ReminderPlan.due(snap(a.copy(status = Dela.DONE)), "me", now, emptySet()).isEmpty())
        assertTrue(ReminderPlan.due(snap(a.copy(ownerId = "natasha")), "me", now, emptySet()).isEmpty())
        // Дело со сроком и напоминанием звенит тоже — со словами про срок.
        val deed = task("d", "2026-10-15T12:00:00+03:00", kind = Dela.KIND_TASK, due = "2026-10-16")
        val dd = ReminderPlan.due(snap(deed), "me", ms("2026-10-15T12:00:30+03:00"), emptySet()).single()
        assertEquals("12:00 · дело на завтра · Дела", ReminderPlan.body(dd, ms("2026-10-15T12:00:30+03:00"), msk))
    }

    @Test
    fun `по месту — без мига молчит, приезд ставит миг и звенит «ты дома»`() {
        val home = task("h", "", place = "Дом")
        val now = ms("2026-10-15T18:40:00+03:00")
        assertTrue(ReminderPlan.due(snap(home), "me", now, emptySet()).isEmpty())
        assertNull(ReminderPlan.next(snap(home), "me", now, emptySet()))
        // Приезд: DelaRemind.arrivalOps ставит remind_at = миг приезда — это и есть показ.
        val s = Dela.Snapshot(tasks = mapOf(home.id to home), features = setOf(Dela.FEATURE_REMIND))
        val ops = DelaRemind.arrivalOps(s, "me", "Дом", DelaRemind.now(now, msk))
        val arrived = Dela.overlay(s, ops, "me", "2026-10-15", "2026-10-15T18:40:00+03:00")
        val due = ReminderPlan.due(arrived, "me", now + 1_000L, emptySet()).single()
        assertEquals("ты дома · Дела", ReminderPlan.body(due, now + 1_000L, msk))
        assertEquals("приехал: Офис", ReminderPlan.placeWords("Офис"))
    }

    @Test
    fun `кнопки — операции очереди, как у бота`() {
        val t = task("a", "2026-10-15T12:00:00+03:00", place = "")
        val now = ms("2026-10-15T12:01:30+03:00")
        val done = ReminderPlan.doneOp(t, "op1")
        assertEquals("task.done", done.getString("op"))
        assertEquals("a", done.getString("id"))
        val hour = ReminderPlan.moveOp(t, ReminderPlan.hourLater(now, msk), "op2")
        assertEquals("task.set", hour.getString("op"))
        assertEquals("2026-10-15T13:01:00+03:00", hour.getJSONObject("set").getString("remind_at"))
        assertTrue(hour.getJSONObject("set").isNull("remind_place"))
        assertEquals("2026-10-15T12:00:00+03:00", hour.getJSONObject("was").getString("remind_at"))
        assertEquals("2026-10-16T09:00:00+03:00", ReminderPlan.tomorrowMorning(now, msk))
        // Поверх копии «Через час» — то же напоминание, новый миг: строка остаётся напоминанием, а не делом.
        val s = snap(t)
        val moved = Dela.overlay(s, listOf(hour), "me", "2026-10-15", "2026-10-15T12:01:30+03:00").tasks["a"]!!
        assertTrue(moved.remind)
        assertEquals("2026-10-15T13:01:00+03:00", moved.remindAt)
    }

    @Test
    fun `отметка показа — для бота, по ключу, повтор безвреден`() {
        val t = task("a", "2026-10-15T12:00:00+03:00")
        val d = ReminderPlan.due(snap(t), "me", ms("2026-10-15T12:00:02+03:00"), emptySet()).single()
        val op = ReminderPlan.shownOp(d, ms("2026-10-15T12:00:02+03:00"), msk)
        assertEquals("task.reminded", op.getString("op"))
        assertEquals("phone", op.getString("via"))
        assertEquals("2026-10-15T12:00:00+03:00", op.getString("remind_at"))
        assertEquals(op.getString("op_id"), ReminderPlan.shownOp(d, ms("2026-10-15T12:05:00+03:00"), msk).getString("op_id"))
        assertTrue(Dela.isUuid(op.getString("op_id")))
        val set = ReminderPlan.settingsOp(true, "x")
        assertTrue(set.getJSONObject("settings").getJSONObject("remind_phone").getBoolean("on"))
    }

    @Test
    fun `строка на Сегодня — время, по месту, вчерашнее не отмечено`() {
        assertEquals("12:00", ReminderPlan.whenOnToday(task("a", "2026-10-15T12:00:00+03:00"), "2026-10-15", msk))
        assertEquals("дома", ReminderPlan.whenOnToday(task("h", "", place = "дом"), "2026-10-15", msk))
        assertEquals("вчера 12:00 · не отмечено", ReminderPlan.whenOnToday(task("y", "2026-10-14T12:00:00+03:00"), "2026-10-15", msk))
        assertEquals("завтра 9:00", ReminderPlan.whenOnToday(task("t", "2026-10-16T09:00:00+03:00"), "2026-10-15", msk))
    }

    @Test
    fun `память — последние ключи, без повторов`() {
        val have = (1..ReminderPlan.KEEP).map { "k$it" }
        val out = ReminderPlan.remember(have, listOf("k1", "new"))
        assertEquals(ReminderPlan.KEEP, out.size)
        assertEquals("new", out.last())
        assertEquals("k1", out[out.size - 2])
        assertFalse("k2" in out)
    }

    @Test
    fun `перед встречей — идеи клиента за десять минут`() {
        val romashka = Dela.Project(id = "p1", name = "ООО Ромашка", aliases = listOf("Ромашка"))
        val idea = Dela.Idea(id = "i1", text = "предложить Ромашке косткаттинг", projectId = "p1", createdAt = "2026-10-15T10:31:00+03:00")
        val s = Dela.Snapshot(projects = mapOf("p1" to romashka), ideas = mapOf("i1" to idea), features = setOf(Dela.FEATURE_IDEAS))
        val start = ms("2026-10-15T15:00:00+03:00")
        val meet = CalEvent(1, 10, "Ромашка: квартальный", start, start + 3_600_000L, false, false, false, "a@b", true)
        val other = CalEvent(2, 11, "Созвон с банком", start, start + 1_800_000L, false, false, false, "a@b", true)
        val meets = ReminderPlan.meetings(listOf(meet, other), s)
        assertEquals(listOf(meet), meets.map { it.event })
        assertEquals(start - ReminderPlan.MEET_AHEAD_MS, ReminderPlan.meetNext(meets, ms("2026-10-15T14:00:00+03:00"), emptySet()))
        val due = ReminderPlan.meetDue(meets, ms("2026-10-15T14:50:00+03:00"), emptySet())
        assertEquals(1, due.size)
        assertEquals("Через 10 мин: Ромашка: квартальный", ReminderPlan.meetTitle(due.single(), ms("2026-10-15T14:50:00+03:00")))
        assertEquals("💡 1: предложить Ромашке косткаттинг", ReminderPlan.meetBody(due.single()))
        // Встреча началась — поздно; сказано — второй раз нет.
        assertTrue(ReminderPlan.meetDue(meets, start + 1, emptySet()).isEmpty())
        assertTrue(ReminderPlan.meetDue(meets, ms("2026-10-15T14:55:00+03:00"), setOf(due.single().key)).isEmpty())
        // Использованная идея не всплывает.
        val used = s.copy(ideas = mapOf("i1" to idea.copy(status = "used")))
        assertTrue(ReminderPlan.meetings(listOf(meet), used).isEmpty())
    }
}
