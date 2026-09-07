package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ZasechkaStore

// Числа «Отчёта» — на выдуманном, но правдоподобном дне владельца: ночь через
// полночь, работа, порезанная тренировкой с часов, потери, идущее дело. Каждый
// тест — правило из docs/otchet.md: обрезка по окну, честное сравнение с тем
// же часом неделю назад, склейка разрезанного дела, сон без двоения.
class DayReportTest {

    private val h = 3_600_000L
    private val m = 60_000L

    // Полночь дня D; удобно считать от неё.
    private val day = 1_000L * 86_400_000L
    private val now = day + 15 * h + 20 * m  // 15:20 сегодняшнего дня

    private var nextId = 1L

    private fun e(
        start: Long,
        end: Long,
        category: String,
        title: String = category,
        source: String = "voice",
        client: String = "",
    ) = ZasechkaStore.Entry(
        id = nextId++, start = start, end = end, raw = "", title = title, category = category,
        client = client, useful = 0, source = source, synced = false, createdAt = start,
    )

    private val worth = mapOf(
        "работа: текущая" to 10, "спорт: бег" to 9, "семья" to 6, "еда" to 1,
        "сон" to 0, "отдых" to -2, "потери" to -5, "не размечено" to -5,
    )
    // Лямбда-свойство, а не функция: ссылка `::worthOf` внутри теста с кириллическим
    // именем рождает класс с этим именем в пути, и JVM без UTF-8 в локали его не
    // пишет («Malformed input or input contains unmappable characters»).
    private val worthOf: (String) -> Int = { c -> worth[c.trim().lowercase()] ?: 0 }

    // Лента дня D: сон с 23:30 накануне до 07:00 (разрезан полуночью), работа
    // 09:00–12:00, разрезанная пробежкой 10:30–11:00 с часов, обед, потери, и
    // идущая с 14:00 семья.
    private val ribbon = listOf(
        e(day - 30 * m, day, "Сон", source = "auto"),
        e(day, day + 7 * h, "Сон", source = "auto"),
        e(day + 7 * h, day + 9 * h, "Еда", "Завтрак с детьми"),
        e(day + 9 * h, day + 10 * h + 30 * m, "Работа: текущая", "Отчёт для Tasty"),
        e(day + 10 * h + 30 * m, day + 11 * h, "Спорт: бег", "Бег", source = "auto"),
        e(day + 11 * h, day + 12 * h, "Работа: текущая", "Отчёт для Tasty"),
        e(day + 12 * h, day + 13 * h, "Потери", "потери", source = "gap"),
        e(day + 13 * h, day + 14 * h, "Еда", "Обед"),
        e(day + 14 * h, 0L, "Семья", "Прогулка с Ромой"),
    )

    @Test
    fun `окно дня обрезается сейчас и идущее дело считается до этой минуты`() {
        val w = DayReport.dayWindow(day, now)
        assertEquals(day, w.from)
        assertEquals(now, w.to)
        val family = DayReport.minutesOf(ribbon, w, now, "Семья")
        assertEquals(80L, family)
    }

    @Test
    fun `минуты по категориям складываются в миллисекундах и режутся окном`() {
        val w = DayReport.dayWindow(day, now)
        val slices = DayReport.byCategory(ribbon, w, now, worthOf)
        val byName = slices.associateBy { it.category.lowercase() }
        // Сон в сутках D — только голова с полуночи: 7 часов, хвост накануне не в счёт.
        assertEquals(420L, byName["сон"]!!.minutes)
        assertEquals(150L, byName["работа: текущая"]!!.minutes)
        assertEquals(25, byName["работа: текущая"]!!.points)
        // Сортировка — по убыванию времени: сон первый.
        assertEquals("Сон", slices.first().category)
    }

    @Test
    fun `баланс считает плюс и минус порознь`() {
        val w = DayReport.dayWindow(day, now)
        val b = DayReport.balance(ribbon, w, now, worthOf)
        // работа 2.5 ч × 10 = 25, бег 0.5 × 9 = 4.5, еда 3 ч × 1 = 3, семья 80 м × 6 = 8; потери −5.
        assertEquals(40.5, b.plus, 0.01)
        assertEquals(-5.0, b.minus, 0.01)
        assertEquals(36, b.net)
        assertEquals(40.5 / 45.5, b.efficiency!!, 0.001)
    }

    @Test
    fun `пустой день не имеет КПД, а не нулевой`() {
        val b = DayReport.balance(emptyList(), DayReport.dayWindow(day, now), now, worthOf)
        assertNull(b.efficiency)
        assertEquals(0, b.net)
    }

    @Test
    fun `сдвинутое окно той же длины - сегодня до 15-20 против того же часа неделю назад`() {
        val w = DayReport.dayWindow(day, now)
        val ref = w.shifted(7)
        assertEquals(w.elapsedMs, ref.elapsedMs)
        assertEquals(day - 7 * DayReport.DAY_MS, ref.from)
        assertEquals(now - 7 * DayReport.DAY_MS, ref.to)
    }

    @Test
    fun `ночной сон склеивается через полночь и не двоится`() {
        assertEquals(7 * h + 30 * m, DayReport.nightSleepMs(ribbon, day, now))
        val r = DayReport.rhythm(ribbon, day, now)
        assertEquals(day + 7 * h, r.wakeMs)
        // Следующей ночи ещё нет — отбоя нет.
        assertNull(r.bedMs)
    }

    @Test
    fun `отбой дня - начало ночи, ушедшей за полночь`() {
        val yesterday = day - DayReport.DAY_MS
        val r = DayReport.rhythm(ribbon, yesterday, now)
        assertEquals(day - 30 * m, r.bedMs)
    }

    @Test
    fun `бодрствование - прошедшее время без сна`() {
        val w = DayReport.dayWindow(day, now)
        assertEquals(now - day - 7 * h, DayReport.awakeMs(ribbon, w, now))
    }

    @Test
    fun `полоса дня - доли суток, идущее дело до сейчас, будущее пусто`() {
        val strip = DayReport.strip(ribbon, day, now)
        // Хвост сна накануне в сутки D не попадает.
        assertEquals(8, strip.size)
        assertEquals(0f, strip.first().startFrac, 1e-6f)
        assertEquals(7f / 24f, strip.first().endFrac, 1e-5f)
        val last = strip.last()
        assertTrue(last.open)
        assertEquals((15f * 60 + 20) / (24f * 60), last.endFrac, 1e-5f)
    }

    @Test
    fun `треугольник раскладывает бодрствование по ценности, сон не в счёт`() {
        val w = DayReport.dayWindow(day, now)
        val slices = DayReport.byCategory(ribbon, w, now, worthOf)
        val b = DayReport.buckets(slices)
        assertEquals(listOf("+7…+10", "+3…+6", "0…+2", "−1…−4", "−5…−10"), b.map { it.label })
        assertEquals(180L, b[0].minutes)   // работа 150 + бег 30
        assertEquals(80L, b[1].minutes)    // семья
        assertEquals(180L, b[2].minutes)   // еда 3 ч; сон исключён
        assertEquals(0L, b[3].minutes)
        assertEquals(60L, b[4].minutes)    // потери
        val t = DayReport.triangle(slices)
        assertEquals((180 + 80) * m, t.topMs)
        assertEquals(60 * m, t.bottomMs)
        assertEquals(260.0 / 320.0, t.index!!, 1e-6)
    }

    @Test
    fun `разрезанная тренировкой работа склеивается в один глубокий блок`() {
        val w = DayReport.dayWindow(day, now)
        val f = DayReport.focus(ribbon, w, now)
        // Пробежка между двумя кусками отчёта — разрыв полчаса, больше пяти минут:
        // это ДВА блока, 90 и 60 минут, оба глубокие.
        assertEquals(150L, f.workMin)
        assertEquals(150L, f.deepMin)
        assertEquals(2, f.deepBlocks)
        assertEquals(1.0, f.deepShare!!, 1e-6)
        // Дела владельца: завтрак, отчёт ×2, обед, семья — сон, бег и потери не дела.
        assertEquals(5, f.entries)
        assertEquals(120L, f.longestMin)   // завтрак 07:00–09:00
    }

    @Test
    fun `куски одного дела с разрывом до пяти минут - один блок`() {
        val w = DayReport.Window(day, day + DayReport.DAY_MS)
        val parts = listOf(
            e(day + 9 * h, day + 9 * h + 40 * m, "Работа: текущая", "Модель"),
            e(day + 9 * h + 40 * m, day + 9 * h + 43 * m, "Сон", "Сон", source = "auto"),
            e(day + 9 * h + 43 * m, day + 10 * h, "Работа: текущая", "Модель"),
        )
        val blocks = DayReport.mergeSame(parts.filter { DayReport.isWork(it.category) }, w, now)
        assertEquals(1, blocks.size)
        assertEquals(60 * m, blocks.single().ms)
    }

    @Test
    fun `что съело день - без сна и заполнителей, по убыванию`() {
        val w = DayReport.dayWindow(day, now)
        val top = DayReport.topTitles(ribbon, w, now, 3)
        assertEquals(listOf("Отчёт для Tasty", "Завтрак с детьми", "Прогулка с Ромой"), top.map { it.title })
        assertEquals(150L, top.first().minutes)
    }

    @Test
    fun `клиенты и дела из Todoist`() {
        val w = DayReport.dayWindow(day, now)
        val withClients = ribbon + listOf(
            e(day + 8 * h, day + 8 * h + 30 * m, "Работа: звонки", "Созвон", client = "Tasty Coffee", source = "todoist"),
            e(day + 8 * h + 30 * m, day + 9 * h, "Работа: звонки", "Созвон", client = "Стаффджет"),
        )
        val clients = DayReport.byClient(withClients, w, now)
        assertEquals("Tasty Coffee" to 30L, clients.first())
        assertEquals(1, DayReport.todoistStarts(withClients, w))
    }

    @Test
    fun `медиана, место и дельта словами`() {
        assertEquals(20.0, DayReport.median(listOf(30.0, 10.0, 20.0))!!, 1e-9)
        assertEquals(15.0, DayReport.median(listOf(10.0, 20.0))!!, 1e-9)
        assertNull(DayReport.median(emptyList()))
        assertEquals(1, DayReport.rank(40.0, listOf(10.0, 20.0, 30.0)))
        assertEquals(3, DayReport.rank(15.0, listOf(10.0, 20.0, 30.0)))
        assertEquals("+12 м", DayReport.delta(72, 60, "м"))
        assertEquals("−3", DayReport.delta(7, 10))
        assertEquals("=", DayReport.delta(5, 5, "м"))
        assertEquals("1 ч 10 м", DayReport.dur(70))
        assertEquals("2 ч", DayReport.dur(120))
        assertEquals("0 м", DayReport.dur(0))
        assertEquals("−4", DayReport.signed(-4))
        assertEquals("73 %", DayReport.pct(0.734))
        assertEquals("—", DayReport.pct(null))
    }
}
