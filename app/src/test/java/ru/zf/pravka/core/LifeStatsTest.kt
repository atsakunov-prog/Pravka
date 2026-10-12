package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.LifeStats.DayPick
import ru.zf.pravka.core.LifeStats.Group
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.ZasechkaStore
import java.time.LocalDate
import java.time.ZoneOffset

// Общая статистика «Куда уходит день» (11.10.2026): числа отчёта, который
// владелец принёс от Claude, — на выдуманных сутках. Каждый тест — правило из
// docs/otchet.md: группы как в отчёте, сутки без ленты не в счёт, время без
// записи — «не размечено», выбор дня недели, окно будней, план против факта,
// главный рецепт еды.
class LifeStatsTest {

    private val zone = ZoneOffset.UTC
    private val h = 3_600_000L
    private val m = 60_000L
    private var nextId = 1L

    // 2026-10-05 — понедельник.
    private val mon = LocalDate.of(2026, 10, 5)

    private fun at(d: LocalDate, hh: Int, mm: Int = 0): Long =
        d.atStartOfDay(zone).toInstant().toEpochMilli() + hh * h + mm * m

    private fun e(start: Long, end: Long, category: String, title: String = category, source: String = "voice") =
        ZasechkaStore.Entry(
            id = nextId++, start = start, end = end, raw = "", title = title, category = category,
            client = "", useful = 0, source = source, createdAt = start,
        )

    /** Типовые сутки: сон до 7, быт, работа 10–13, соло 13–14, мастерская 14–18, семья до 23, сон. */
    private fun typical(d: LocalDate, solo: Boolean = true, outAt: Int? = null): List<ZasechkaStore.Entry> = buildList {
        add(e(at(d, 0), at(d, 7), "Сон", source = "auto"))
        add(e(at(d, 7), at(d, 10), "Быт"))
        add(e(at(d, 10), at(d, 13), "Работа: текущая"))
        if (solo) add(e(at(d, 13), at(d, 14), "Секс: соло"))
        else add(e(at(d, 13), at(d, 14), "Еда"))
        if (outAt != null) {
            add(e(at(d, 14), at(d, outAt), "Систематизация"))
            add(e(at(d, outAt), at(d, outAt, 30), "Передвижение: транспорт"))
            add(e(at(d, outAt, 30), at(d, 18), "Систематизация"))
        } else {
            add(e(at(d, 14), at(d, 18), "Систематизация"))
        }
        add(e(at(d, 18), at(d, 23), "Семья", title = "ужин с Марианной"))
        add(e(at(d, 23), at(d.plusDays(1), 0), "Сон", source = "auto"))
    }

    @Test
    fun groupsAsInTheReport() {
        assertEquals(Group.SLEEP, LifeStats.groupOf("Сон"))
        assertEquals(Group.SYS, LifeStats.groupOf("Систематизация"))
        assertEquals(Group.SOLO, LifeStats.groupOf("Секс: соло"))
        assertEquals(Group.FAMILY, LifeStats.groupOf("Секс: с Марианной"))
        assertEquals(Group.LIFE, LifeStats.groupOf("Передвижение: транспорт"))
        assertEquals(Group.LIFE, LifeStats.groupOf("Еда"))
        assertEquals(Group.LOSS, LifeStats.groupOf("Отдых"))
        assertEquals(Group.LOSS, LifeStats.groupOf("Потери"))
        assertEquals(Group.GAP, LifeStats.groupOf("Не размечено"))
        assertEquals(Group.WORK, LifeStats.groupOf("Работа: звонки"))
        assertEquals(Group.OTHER, LifeStats.groupOf("Социальное: внешнее"))
        assertEquals(Group.OTHER, LifeStats.groupOf("Чтение"))
        assertTrue(LifeStats.withMarianna("Семья", "ужин с Марианной"))
        assertFalse(LifeStats.withMarianna("Семья", "уроки с Борей"))
    }

    @Test
    fun entryOverMidnightSplitsAndEmptyTimeIsGap() {
        val d = mon
        val list = listOf(
            e(at(d, 0), at(d, 20), "Работа: текущая"),
            // Сон с 22:00 до 7:00 — половина в понедельнике, половина во вторнике.
            e(at(d, 22), at(d.plusDays(1), 7), "Сон"),
        )
        val r = LifeStats.ribbon(list, d, d.plusDays(1), at(d.plusDays(2), 0), zone)
        val monR = r.getValue(d)
        assertEquals(120.0, monR.minutes(Group.SLEEP), 0.01)
        // 20:00–22:00 без записи — «не размечено».
        assertEquals(120.0, monR.minutes(Group.GAP), 0.01)
        assertTrue(monR.full)
        val tueR = r.getValue(d.plusDays(1))
        assertEquals(420.0, tueR.minutes(Group.SLEEP), 0.01)
        // Во вторнике лента закрыла семь часов — сутки не в счёт.
        assertFalse(tueR.full)
    }

    @Test
    fun hourProfileAveragesOnlyChosenWeekday() {
        val tue = mon.plusDays(1)
        val nextMon = mon.plusDays(7)
        val list = typical(mon, solo = true) + typical(tue, solo = false) + typical(nextMon, solo = false)
        val r = LifeStats.ribbon(list, mon, nextMon, at(nextMon.plusDays(1), 0), zone)
        val mondays = LifeStats.hourProfile(r, DayPick.MON)
        assertEquals(2, mondays.days)
        // Соло было в одном понедельнике из двух: в среднем полчаса в часе 13.
        assertEquals(30.0, mondays.hours[13][Group.SOLO]!!, 0.01)
        val all = LifeStats.hourProfile(r, DayPick.ALL)
        assertEquals(3, all.days)
        assertEquals(20.0, all.hours[13][Group.SOLO]!!, 0.01)
        // Каждый час — ровно 60 минут, сколько бы групп в нём ни было.
        for (hour in all.hours) assertEquals(60.0, hour.values.sum(), 0.01)
        assertEquals(0, LifeStats.hourProfile(r, DayPick.SUN).days)
    }

    @Test
    fun overlappingEntriesDoNotOverfillAnHour() {
        val d = mon
        val list = typical(d) + listOf(
            // Сон с часов поверх сна из ленты и звонок поверх работы — нахлёст.
            e(at(d, 1), at(d, 6), "Сон", source = "auto"),
            e(at(d, 11), at(d, 11, 30), "Звонки"),
        )
        val r = LifeStats.ribbon(list, d, d, at(d.plusDays(1), 0), zone)
        assertEquals(1440.0, r.getValue(d).pieces.sumOf { it.minutes }, 0.01)
        val p = LifeStats.hourProfile(r, DayPick.ALL)
        for (hour in p.hours) assertEquals(60.0, hour.values.sum(), 0.01)
        // Время заняла запись, начатая раньше: работа с 10 до 13 осталась целой.
        assertEquals(180.0, r.getValue(d).minutes(Group.WORK), 0.01)
    }

    @Test
    fun dayPickByWeekday() {
        assertEquals(DayPick.MON, DayPick.of(mon))
        assertEquals(DayPick.SUN, DayPick.of(mon.plusDays(6)))
        assertTrue(DayPick.WEEKDAYS.accepts(mon.plusDays(4)))
        assertFalse(DayPick.WEEKDAYS.accepts(mon.plusDays(5)))
        assertTrue(DayPick.WEEKENDS.accepts(mon.plusDays(6)))
        assertTrue(DayPick.FRI.accepts(mon.plusDays(4)))
        assertFalse(DayPick.FRI.accepts(mon.plusDays(3)))
    }

    @Test
    fun averageDayAddsLeakTogether() {
        val r = LifeStats.ribbon(typical(mon), mon, mon, at(mon.plusDays(1), 0), zone)
        val lines = LifeStats.averageDay(r).associateBy { it.label }
        assertEquals(8.0, lines.getValue("Сон").hours, 0.01)
        assertEquals(3.0, lines.getValue("Работа").hours, 0.01)
        assertEquals(4.0, lines.getValue("Мастерская").hours, 0.01)
        assertEquals(1.0, lines.getValue("Утечка: потери, отдых, соло, дыры").hours, 0.01)
        assertEquals(24.0, lines.values.sumOf { it.hours }, 0.01)
    }

    @Test
    fun windowCalendarKnowsOutingSoloAndMeetings() {
        val tue = mon.plusDays(1)
        val list = typical(mon, solo = true) +
            typical(tue, solo = false, outAt = 15).filter { it.category != "Работа: текущая" } + listOf(
                // Два куска одной встречи с разрывом в три минуты — одна встреча.
                e(at(tue, 10), at(tue, 10, 40), "Работа: звонки", title = "Додо"),
                e(at(tue, 10, 43), at(tue, 11, 10), "Работа: звонки", title = "Додо"),
                e(at(tue, 11, 10), at(tue, 13), "Работа: текущая"),
            )
        val r = LifeStats.ribbon(list, mon, tue, at(tue.plusDays(1), 0), zone)
        val w = LifeStats.window(r, setOf(tue))
        assertEquals(2, w.days)
        val monMark = w.marks.first { it.date == mon }
        val tueMark = w.marks.first { it.date == tue }
        assertTrue(monMark.solo)
        assertFalse(monMark.out)
        assertFalse(tueMark.solo)
        assertTrue(tueMark.out)
        assertTrue(tueMark.bjj)
        assertEquals(1, tueMark.meetings)
        assertEquals(1, w.home.size)
        // Соло в окне: час в одном из двух будних дней — 30 минут в среднем.
        assertEquals(30.0, w.rows.first { it.label == "соло" }.minutes, 0.01)
        assertTrue(w.rows.any { it.label == "дороги" })
    }

    @Test
    fun weeksAverageMinutesPerDay() {
        val days = (0L..6L).map { mon.plusDays(it) }
        val list = days.flatMap { typical(it, solo = it == mon) }
        val r = LifeStats.ribbon(list, mon, days.last(), at(days.last().plusDays(1), 0), zone)
        val health = days.map { LifeStats.HealthIn(it, if (it == mon) 5.5 else 7.0, 52, 6000, 0.0) }
        val weeks = LifeStats.weeks(r, health, mon, days.last())
        assertEquals(1, weeks.size)
        val wk = weeks.single()
        assertEquals(7, wk.days)
        assertEquals(180.0, wk.perDay.getValue(LifeStats.Line.WORK), 0.01)
        assertEquals(60.0 / 7, wk.perDay.getValue(LifeStats.Line.SOLO), 0.01)
        assertEquals(300.0, wk.perDay.getValue(LifeStats.Line.MARIANNA), 0.01)
        assertEquals(1, wk.shortNights)
        assertEquals(7, wk.nights)
        // Записи владельца: сон и заполнитель не в счёт.
        assertEquals(5.0, wk.entriesPerDay, 0.01)
    }

    @Test
    fun planFactMatchesByDayAndKind() {
        val plan = listOf(
            LifeStats.PlanIn(mon, "Зарядка · 6 пунктов", "WeightTraining", charger = true, strength = true),
            LifeStats.PlanIn(mon.plusDays(1), "Зарядка", "WeightTraining", charger = true, strength = true),
            LifeStats.PlanIn(mon, "Лёгкий бег", "Run", charger = false, strength = false),
            LifeStats.PlanIn(mon.plusDays(2), "Zwift Z2", "Ride", charger = false, strength = false),
            LifeStats.PlanIn(mon.plusDays(3), "BJJ", "Other", charger = false, strength = false),
            LifeStats.PlanIn(mon.plusDays(3), "заметка", "Note", charger = false, strength = false),
        )
        val workouts = listOf(
            LifeStats.WorkoutIn(mon, "Run", "Москва Бег", 32, 40, 163, 5.0, 359, 3),
            // Велосипед как транспорт — не Zwift.
            LifeStats.WorkoutIn(mon.plusDays(2), "Ride", "Москва Велосипед", 40, 15, 120, 9.0, 0, 0),
            LifeStats.WorkoutIn(mon.plusDays(3), "Other", "BJJ: борьба", 60, 37, 124, 0.0, 0, 0),
            LifeStats.WorkoutIn(mon.plusDays(4), "Other", "BJJ: борьба", 55, 30, 122, 0.0, 0, 0),
        )
        val gtg = listOf(LifeStats.GtgIn(mon, charged = true, hangSec = 20))
        val pf = LifeStats.planFact(plan, workouts, gtg, emptyList(), mon, mon.plusDays(6))
        val rows = pf.rows.associateBy { it.kind }
        assertEquals(2, rows.getValue(LifeStats.SportKind.CHARGE).planned)
        assertEquals(1, rows.getValue(LifeStats.SportKind.CHARGE).done)
        assertEquals(1, rows.getValue(LifeStats.SportKind.RUN).done)
        assertEquals(0, rows.getValue(LifeStats.SportKind.RIDE).done)
        assertEquals(1, rows.getValue(LifeStats.SportKind.BJJ).done)
        // Заметка — не план.
        assertEquals(5, pf.planned)
        assertEquals(3, pf.done)
        assertEquals(1, pf.bjjUnplanned)
    }

    private fun item(name: String, grams: Int, kcal: Int, protein: Int) =
        MealItem(name = name, grams = grams, kcal = kcal, protein = protein, fat = 5, carbs = 20)

    private fun meal(d: LocalDate, hh: Int, items: List<MealItem>) = FoodStore.Meal(
        id = nextId++, ts = at(d, hh), createdAt = at(d, hh), kind = "завтрак", raw = "", items = items, confirmed = true,
    )

    @Test
    fun recipeFoundWhenItRepeats() {
        val bowl = listOf(
            item("Протеин", 66, 250, 50), item("Творог 5%", 200, 240, 34),
            item("Творог мягкий", 200, 200, 20), item("Геркулес", 60, 210, 7), item("Голубика", 250, 140, 2),
        )
        val meals = (0L until 8L).flatMap { i ->
            val d = mon.plusDays(i)
            // В половине дней грамм творога другой и клубника вместо голубики — тот же рецепт.
            val b = if (i % 2 == 0L) bowl else bowl.dropLast(1) + item("Клубника", 150, 50, 1)
            listOf(meal(d, 9, b), meal(d, 19, listOf(item("Поке с лососем", 350, 600, 30))))
        }
        val f = LifeStats.food(meals, mon, mon.plusDays(9), proteinGoal = 140, zone = zone)
        assertEquals(8, f.recordedDays)
        val r = f.recipe
        assertNotNull(r)
        assertEquals(8, r!!.days)
        assertTrue(r.items.any { it.name == "Протеин" })
        assertTrue(r.proteinShare > 0.7)
        assertEquals(8, f.proteinGoalDays)
        // Дни без записей в средние не входят.
        assertEquals(f.days.filter { it.kcal > 0 }.sumOf { it.kcal } / 8.0, f.avgKcal, 0.01)
    }

    @Test
    fun noRecipeWhenMealsDiffer() {
        val meals = (0L until 6L).map { i ->
            meal(mon.plusDays(i), 9, listOf(item("Блюдо $i а", 100, 100, 10), item("Гарнир ${'a' + i.toInt()}", 100, 100, 5), item("Соус ${'k' + i.toInt()}", 20, 50, 0)))
        }
        assertNull(LifeStats.food(meals, mon, mon.plusDays(6), 0, zone).recipe)
    }

    @Test
    fun phoneTopAppsSkipFurniture() {
        val days = (0L until 7L).associate { i ->
            mon.plusDays(i).toString() to PhoneStore.Day(
                screenMs = 600 * m,
                pickups = 150,
                apps = mapOf(
                    "com.anthropic.claude" to 160 * m,
                    "org.telegram.messenger" to 90 * m,
                    "com.google.android.youtube" to 60 * m,
                    "com.sec.android.app.launcher" to 300 * m,
                ),
            )
        }
        val p = LifeStats.phone(days, mon, mon.plusDays(6), furniture = { it.contains("launcher") }, topN = 2)
        assertEquals(listOf("com.anthropic.claude", "org.telegram.messenger"), p.top)
        assertEquals(600.0, p.screenMin, 0.01)
        assertEquals(160.0, p.topMin.getValue("com.anthropic.claude"), 0.01)
        assertEquals(1, p.weeks.size)
    }
    @Test
    fun moneyMonthsBeforeFirstStatementAreDropped() {
        fun m(id: String, d: String, rub: Long, cat: String) = MoneyEntry(
            id = id, owner = "sasha", source = MoneyEntry.Source.TINKOFF,
            ts = MoneyStats.startOf(LocalDate.parse(d)) + 12 * h, rubKop = rub * 100, what = id, category = cat,
        )
        val entries = listOf(
            m("a", "2026-08-10", -1_000, "groceries"),
            m("b", "2026-09-10", -3_000, "groceries"),
            m("c", "2026-09-12", -500, "health"),
            m("i", "2026-09-15", 10_000, "inc_zf"),
        )
        val money = LifeStats.money(entries, LocalDate.of(2026, 10, 11), 8, MoneyScope.PERSONAL, MoneyScope.ZF)
        // Февраль–июль без выписок срезаны: август, сентябрь и идущий октябрь.
        assertEquals(listOf(8, 9, 10), money.months.map { it.month.monthValue })
        assertTrue(money.months.last().partial)
        assertEquals(300_000L, money.months[1].groups.getValue(MoneyCategories.G_HOME))
        assertEquals(50_000L, money.months[1].groups.getValue(MoneyCategories.G_HEALTH))
        assertEquals(1_000_000L, money.months[1].incomeZf)
        // Продукты в среднем — за три месяца с данными, а не за восемь.
        assertEquals(400_000L / 3, money.categories.first { it.first == "groceries" }.second)
        assertEquals(listOf(-100_000L, 550_000L, 550_000L), money.running)
    }
}
