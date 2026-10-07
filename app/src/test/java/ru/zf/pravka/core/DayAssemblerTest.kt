package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.DayAssembler.DayItem
import ru.zf.pravka.core.DayAssembler.MIN

// Хроника «Сегодня» (DESIGN §11.5): пример из макета `screens/01` один в один —
// силовая до 21:15 → очередь дел с 21:30; 45 + 30 + 20 + 10 + 40 м → конец в
// 23:55 → «дела не влезают на 25 м» при сне в 23:30.
class DayAssemblerTest {

    private val day = 1_790_000_000_000L - Math.floorMod(1_790_000_000_000L, 86_400_000L)
    private fun at(h: Int, m: Int) = day + (h * 60 + m) * MIN

    private fun entry(id: Long, from: Long, to: Long, title: String, cat: String, worth: Int) =
        DayAssembler.EntryIn(id, from, to, title, cat, worth)

    private val entries = listOf(
        entry(1, day - 20 * MIN, day, "Сон", "Сон", 0),
        entry(2, day, at(7, 5), "Сон", "Сон", 0),
        entry(3, at(7, 5), at(17, 45), "Работа", "Работа: текущая", 10),
        entry(4, at(17, 45), at(18, 30), "Уроки со старшим", "Семья", 6),
        entry(5, at(18, 30), 0L, "Ужин с семьёй", "Еда", 1),
    )

    private fun input(now: Long = at(18, 51)) = DayAssembler.Input(
        dayStart = day,
        now = now,
        entries = entries,
        marks = listOf(
            DayAssembler.MarkIn(at(13, 30), DayAssembler.Source.FOOD, "640 ккал"),
            DayAssembler.MarkIn(at(17, 50), DayAssembler.Source.MONEY, "−2 337 ₽"),
        ),
        pending = listOf(DayAssembler.PendingIn(at(18, 40), DayAssembler.Source.FOOD, "Паста, салат · ≈ 720 ккал", "m1")),
        calendar = listOf(DayAssembler.CalendarIn(at(20, 0), at(20, 30), "Родительское собрание 3 «Б»")),
        workouts = listOf(DayAssembler.WorkoutIn(at(20, 30), 45, "Силовая А")),
        tasks = listOf(
            DayAssembler.TaskIn("61", "Бета: финмодель", "#61", 45),
            DayAssembler.TaskIn("62", "Бета: ковенанты", "", 30),
            DayAssembler.TaskIn("41", "Налоговый вычет", "#41", 20),
            DayAssembler.TaskIn("70", "Иван: 1С", "", 10),
            DayAssembler.TaskIn("55", "Орион: КП", "#55", 40),
        ),
        bedtimeMin = 23 * 60 + 30,
    )

    @Test
    fun `очередь дел встаёт после силовой и не влезает на 25 минут`() {
        val r = DayAssembler.assemble(input())
        val tasks = r.items.filterIsInstance<DayItem.Task>()
        assertEquals(listOf(at(21, 30), at(22, 15), at(22, 45), at(23, 5), at(23, 15)), tasks.map { it.start })
        assertTrue(tasks.first().first)
        val group = r.items.filterIsInstance<DayItem.TaskGroup>().single()
        assertEquals(5, group.count)
        assertEquals(145, group.minutes)
        val sleep = r.items.last() as DayItem.Sleep
        assertEquals(at(23, 30), sleep.at)
        assertEquals(25, sleep.overflowMin)
    }

    @Test
    fun `сон после полуночи - минутами сверх суток, дела влезают`() {
        // Настройка «Время сна» 01:00 хранится как 1500: сон — в этом же дне.
        val r = DayAssembler.assemble(input().copy(bedtimeMin = 25 * 60))
        val sleep = r.items.last() as DayItem.Sleep
        assertEquals(day + 25 * 60 * MIN, sleep.at)
        assertEquals(0, sleep.overflowMin)
    }

    @Test
    fun `после сейчас - свободно до календаря, потом план`() {
        val r = DayAssembler.assemble(input())
        val i = r.nowIndex
        assertTrue(r.items[i] is DayItem.Now)
        val free = r.items[i + 1] as DayItem.Free
        assertEquals(at(18, 51), free.from)
        assertEquals(at(20, 0), free.to)
        val cal = r.items[i + 2] as DayItem.Planned
        assertEquals(false, cal.workout)
        val gym = r.items[i + 3] as DayItem.Planned
        assertEquals(true, gym.workout)
        assertEquals(at(21, 15), gym.end)
        // 15 минут между силовой и очередью — запас, а не «свободно».
        assertTrue(r.items[i + 4] is DayItem.TaskGroup)
    }

    @Test
    fun `отметки прикреплены к записи, в чей интервал попали`() {
        val r = DayAssembler.assemble(input())
        val money = r.items.filterIsInstance<DayItem.Mark>().first { it.source == DayAssembler.Source.MONEY }
        assertEquals(4L, money.entryId)
        val idx = r.items.indexOf(money)
        assertEquals(4L, (r.items[idx - 1] as DayItem.Entry).id)
        val pend = r.items.filterIsInstance<DayItem.Pending>().single()
        assertEquals(5L, pend.entryId)
        assertTrue("ждущее — до линии «сейчас»", r.items.indexOf(pend) < r.nowIndex)
    }

    @Test
    fun `отметки и ждущее под записью - по времени вместе`() {
        // Время отметки видно в колонке времени (07.10.2026): «18:50 Зарядка»
        // над «18:40 Говядина» читалось бы как ошибка.
        val r = DayAssembler.assemble(
            input().copy(marks = input().marks + DayAssembler.MarkIn(at(18, 50), DayAssembler.Source.SPORT, "Зарядка · 3 упр.")),
        )
        val under = r.items.dropWhile { !(it is DayItem.Entry && it.id == 5L) }.drop(1)
            .takeWhile { it is DayItem.Mark || it is DayItem.Pending }
        assertEquals(listOf(at(18, 40), at(18, 50)), under.map { (it as? DayItem.Mark)?.at ?: (it as DayItem.Pending).at })
    }

    @Test
    fun `сон с вечера - с полуночи, но полная ночь во второй строке`() {
        val r = DayAssembler.assemble(input())
        val sleep = r.items.filterIsInstance<DayItem.Entry>().first()
        assertEquals(day, sleep.start)
        assertEquals(day - 20 * MIN, sleep.fullStart)
        assertTrue(sleep.fromYesterday)
        assertEquals(445, sleep.fullMinutes) // 7 ч 25 м
    }

    @Test
    fun `у текущей записи очков нет, а балл копит её прошедшее`() {
        val r = DayAssembler.assemble(input())
        val cur = r.items.filterIsInstance<DayItem.Entry>().last()
        assertTrue(cur.current)
        assertEquals(null, cur.points)
        // 10 ч 40 м работы × 10 + 45 м × 6 + 21 м × 1 = 106,67 + 4,5 + 0,35 → 112
        assertEquals(112, r.score)
    }

    @Test
    fun `прошлый день - без сейчас и без будущего`() {
        val r = DayAssembler.assemble(input(now = day + 30 * 3_600_000L))
        assertEquals(-1, r.nowIndex)
        assertTrue(r.items.none { it is DayItem.Task || it is DayItem.Sleep || it is DayItem.Now })
    }

    @Test
    fun `будущий день - только план`() {
        val r = DayAssembler.assemble(input(now = day - 3_600_000L))
        assertTrue(r.items.none { it is DayItem.Entry || it is DayItem.Now })
        assertTrue(r.items.any { it is DayItem.Task })
    }

    @Test
    fun `четверть часа вверх`() {
        assertEquals(at(21, 30), DayAssembler.roundUpQuarter(at(21, 16)))
        assertEquals(at(21, 30), DayAssembler.roundUpQuarter(at(21, 30)))
    }
}
