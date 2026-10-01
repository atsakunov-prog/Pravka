package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.AutoPilotRules.Arrival

// Правила автопилота — на случаях из жизни владельца (сентябрь 2026):
// «приехал домой — остановил передвижение, но не спросил, что делаю»,
// «ушёл из Летово — не спросил, точно ли ещё встречаю Серёжу»,
// «подключился к машине — не переключил на поездку».
class AutoPilotRulesTest {

    private val h = 3_600_000L
    private val m = 60_000L
    private val now = 100 * h

    // ---- приезд ----

    @Test
    fun `открытая дорога закрывается по приезду`() {
        val v = AutoPilotRules.arrival(
            openTitle = "Поездка в Летово", openCategory = "Передвижение: транспорт",
            openStart = now - 40 * m, place = "Летово", leftPlace = "дом", leftAtMs = now - 40 * m, now = now,
        )
        assertEquals(Arrival.CLOSE_TRAVEL, v)
    }

    @Test
    fun `дорога узнаётся по названию даже в чужой категории`() {
        val v = AutoPilotRules.arrival(
            openTitle = "Поездка на велосипеде", openCategory = "Спорт: вело",
            openStart = now - h, place = "дом", leftPlace = "", leftAtMs = 0L, now = now,
        )
        assertEquals(Arrival.CLOSE_TRAVEL, v)
    }

    @Test
    fun `тренировка по приезду не закрывается сама - вопрос`() {
        val v = AutoPilotRules.arrival(
            openTitle = "Бег", openCategory = "Спорт: бег",
            openStart = now - h, place = "дом", leftPlace = "", leftAtMs = 0L, now = now,
        )
        assertEquals(Arrival.ASK_SPORT, v)
    }

    @Test
    fun `переезд Летово - дом с открытой встречей - спрашиваем`() {
        // Встреча с Серёжей открыта с 14:00, сеть Летово пропала в 15:30,
        // дом увиделся в 16:05. В ленте до сих пор «встреча» — она устарела.
        val v = AutoPilotRules.arrival(
            openTitle = "Встреча с Серёжей", openCategory = "Семья",
            openStart = now - 2 * h, place = "дом", leftPlace = "Летово", leftAtMs = now - 35 * m, now = now,
        )
        assertEquals(Arrival.ASK_STILL, v)
    }

    @Test
    fun `роутер мигнул дома при открытой работе - молчим`() {
        val v = AutoPilotRules.arrival(
            openTitle = "Работа: ЗФ", openCategory = "Работа",
            openStart = now - 3 * h, place = "дом", leftPlace = "дом", leftAtMs = now - 2 * m, now = now,
        )
        assertEquals(Arrival.SILENT, v)
    }

    @Test
    fun `тот же дом, но сети не было час - спрашиваем`() {
        val v = AutoPilotRules.arrival(
            openTitle = "Работа: ЗФ", openCategory = "Работа",
            openStart = now - 3 * h, place = "дом", leftPlace = "дом", leftAtMs = now - h, now = now,
        )
        assertEquals(Arrival.ASK_STILL, v)
    }

    @Test
    fun `служба перезапустилась - отъезда нет - молчим`() {
        val v = AutoPilotRules.arrival(
            openTitle = "Работа: ЗФ", openCategory = "Работа",
            openStart = now - 3 * h, place = "дом", leftPlace = "", leftAtMs = 0L, now = now,
        )
        assertEquals(Arrival.SILENT, v)
    }

    @Test
    fun `дело начато уже после отъезда - владелец в курсе, молчим`() {
        // Уехал из дома в 9:00, в 9:40 сказал «встреча с Серёжей», сеть
        // Летово увиделась в 9:45. Переспрашивать через пять минут нельзя.
        val v = AutoPilotRules.arrival(
            openTitle = "Встреча с Серёжей", openCategory = "Семья",
            openStart = now - 5 * m, place = "Летово", leftPlace = "дом", leftAtMs = now - 45 * m, now = now,
        )
        assertEquals(Arrival.SILENT, v)
    }

    @Test
    fun `ничего не открыто, место сменилось - что делаешь`() {
        val v = AutoPilotRules.arrival(
            openTitle = null, openCategory = null, openStart = 0L,
            place = "Летово", leftPlace = "дом", leftAtMs = now - 50 * m, now = now,
        )
        assertEquals(Arrival.ASK_WHAT, v)
    }

    @Test
    fun `ничего не открыто, тот же дом после долгого мигания ночью - молчим`() {
        val v = AutoPilotRules.arrival(
            openTitle = null, openCategory = null, openStart = 0L,
            place = "дом", leftPlace = "дом", leftAtMs = now - 2 * h, now = now,
        )
        assertEquals(Arrival.SILENT, v)
    }

    // ---- машина ----

    @Test
    fun `машина узнаётся по адресу без имени`() {
        assertTrue(AutoPilotRules.isCar(null, "AA:BB:CC:DD:EE:FF", "Volvo", "aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `машина узнаётся по имени без регистра и по началу имени`() {
        assertTrue(AutoPilotRules.isCar("volvo", "", "Volvo", ""))
        assertTrue(AutoPilotRules.isCar("Volvo Media", "11:22", "Volvo", ""))
    }

    @Test
    fun `наушники - не машина`() {
        assertFalse(AutoPilotRules.isCar("AirPods", "11:22", "Volvo", "AA:BB"))
        assertFalse(AutoPilotRules.isCar("", "", "Volvo", ""))
    }

    // ---- машина после отъезда: одна дорога, не две ----

    @Test
    fun `машина через три минуты после потери дома - поездка с момента отъезда`() {
        // Вышел из дома в 9:00 (сеть пропала), в 9:03 подключилась машина:
        // дорога началась у двери, «Работа» закрывается в 9:00.
        val left = now - 3 * m
        val start = AutoPilotRules.carTripStart(
            connectedAt = now, leftPlace = "дом", leftAtMs = left, openStart = now - 3 * h,
        )
        assertEquals(left, start)
    }

    @Test
    fun `отъезда не было - поездка с подключения`() {
        assertEquals(now, AutoPilotRules.carTripStart(now, "", 0L, now - h))
    }

    @Test
    fun `сеть пропала давно - это другая история, поездка с подключения`() {
        val start = AutoPilotRules.carTripStart(
            connectedAt = now, leftPlace = "дом", leftAtMs = now - 40 * m, openStart = now - 3 * h,
        )
        assertEquals(now, start)
    }

    @Test
    fun `после отъезда владелец начал дело сам - его не режем`() {
        // Ушёл из Летово в 15:30, в 15:33 сказал «звонок с Ильёй», в 15:36
        // машина подключилась: звонок остаётся звонком, поездка — с 15:36.
        val start = AutoPilotRules.carTripStart(
            connectedAt = now, leftPlace = "Летово", leftAtMs = now - 6 * m, openStart = now - 3 * m,
        )
        assertEquals(now, start)
    }

    @Test
    fun `ничего не открыто - поездка всё равно с момента отъезда`() {
        val left = now - 10 * m
        assertEquals(left, AutoPilotRules.carTripStart(now, "дом", left, null))
    }

    // ---- якорь времени из пуша ----

    @Test
    fun `якорь годится, если после него ничего не началось`() {
        // Машина отключилась в 14:00, «Сказать» нажато в 14:05, открыта
        // поездка с 13:00: сказанное начинается в 14:00.
        val anchor = now - 5 * m
        assertEquals(anchor, AutoPilotRules.anchoredStart(anchor, now, latestRealStart = now - h))
    }

    @Test
    fun `дело, начатое ровно в якорь, якорь не ломает`() {
        val anchor = now - 5 * m
        assertEquals(anchor, AutoPilotRules.anchoredStart(anchor, now, latestRealStart = anchor))
    }

    @Test
    fun `после якоря владелец уже что-то начал - якорь не годится`() {
        val anchor = now - 20 * m
        assertNull(AutoPilotRules.anchoredStart(anchor, now, latestRealStart = now - 10 * m))
    }

    @Test
    fun `якорь в будущем, пустой или старше шести часов - не годится`() {
        assertNull(AutoPilotRules.anchoredStart(0L, now, 0L))
        assertNull(AutoPilotRules.anchoredStart(now + m, now, 0L))
        assertNull(AutoPilotRules.anchoredStart(now - 7 * h, now, 0L))
    }

    // ---- дело места ----

    @Test
    fun `дело места находится без регистра, пустое название - дела нет`() {
        val deals = mapOf(
            "Летово" to PlaceDeal("Забираю Серёжу", "Семья"),
            "дача" to PlaceDeal("", ""),
        )
        assertEquals("Забираю Серёжу", AutoPilotRules.dealFor("летово", deals)?.title)
        assertNull(AutoPilotRules.dealFor("дача", deals))
        assertNull(AutoPilotRules.dealFor("дом", deals))
    }

    // ---- дорога по словам ----

    @Test
    fun `дорога по словам`() {
        assertTrue(AutoPilotRules.travelish("Еду в Летово", "Семья"))
        assertTrue(AutoPilotRules.travelish("Такси в аэропорт", ""))
        assertFalse(AutoPilotRules.travelish("Работа: ЗФ", "Работа"))
    }
}

// ---- короткий выход, ходьба, «всё ещё …?» и тренировка от двери (27.09.2026) ----

class AutoPilotShortExitTest {

    private val h = 3_600_000L
    private val m = 60_000L
    private val now = 100 * h

    @Test
    fun `вопрос уехал - через десять минут после потери сети, не раньше`() {
        assertEquals(10 * m, AutoPilotRules.leaveAskDelay(now, now))
        // Отъезд «по видимости» заметили с опозданием в пять минут — ждём ещё пять.
        assertEquals(5 * m, AutoPilotRules.leaveAskDelay(now - 5 * m, now))
        assertEquals(0L, AutoPilotRules.leaveAskDelay(now - 15 * m, now))
    }

    @Test
    fun `вело с часов через четыре минуты после отъезда - от двери`() {
        val left = now - 4 * m
        assertEquals(left, AutoPilotRules.stitchedStart(now, "дом", left, now - 2 * h))
    }

    @Test
    fun `вело кончилось в другом месте - передвижение, круг от дома - тренировка`() {
        assertEquals("Передвижение: вело", AutoPilotRules.activityCategory("Спорт: вело", "Ride", true))
        assertEquals("Спорт: вело", AutoPilotRules.activityCategory("Спорт: вело", "Ride", false))
        assertEquals("Спорт: бег", AutoPilotRules.activityCategory("Спорт: бег", "Run", true))
    }

    @Test
    fun `станок и зал - не от двери`() {
        assertTrue(AutoPilotRules.outdoor("Ride"))
        assertFalse(AutoPilotRules.outdoor("VirtualRide"))
        assertFalse(AutoPilotRules.outdoor("WeightTraining"))
    }

    // ---- ходьба ----

    @Test
    fun `двадцать минут движения без машины - пешком с момента отъезда`() {
        val v = AutoPilotRules.walkVerdict(
            motions = 6, leftAtMs = now - 21 * m, now = now,
            openTitle = "Работа: ЗФ", openCategory = "Работа: текущая", latestOwnerStart = now - 3 * h,
        )
        assertEquals(AutoPilotRules.Walk.START, v)
    }

    @Test
    fun `рано - ждём`() {
        val v = AutoPilotRules.walkVerdict(6, now - 12 * m, now, null, null, 0L)
        assertEquals(AutoPilotRules.Walk.WAIT, v)
    }

    @Test
    fun `телефон лежал - не ходьба`() {
        val v = AutoPilotRules.walkVerdict(1, now - 21 * m, now, "Работа: ЗФ", "Работа: текущая", now - 3 * h)
        assertEquals(AutoPilotRules.Walk.NONE, v)
    }

    @Test
    fun `дорога уже идёт или владелец сказал своё - молчим`() {
        assertEquals(
            AutoPilotRules.Walk.NONE,
            AutoPilotRules.walkVerdict(6, now - 21 * m, now, "Поездка из «дом»", "Передвижение: транспорт", now - 21 * m),
        )
        assertEquals(
            AutoPilotRules.Walk.NONE,
            AutoPilotRules.walkVerdict(6, now - 21 * m, now, "Кофе с Ильёй", "Социальное: внешнее", now - 5 * m),
        )
    }

    @Test
    fun `сказал на выходе, куда идёт - не ходьба`() {
        val v = AutoPilotRules.walkVerdict(
            6, now - 21 * m, now, "Разговор с другом", "Социальное: внешнее",
            latestOwnerStart = now - 25 * m, ownerTold = true,
        )
        assertEquals(AutoPilotRules.Walk.NONE, v)
    }

    // ---- «пошёл развозить детей» (01.10.2026) ----

    @Test
    fun `пошёл развозить детей за пять минут до потери сети - ответ на куда`() {
        val left = now - 10 * m
        assertTrue(AutoPilotRules.ownerToldLeave(left - 5 * m, "voice", "Развожу детей", "Пошёл развозить детей", left))
        // Сказал уже после отъезда — тем более.
        assertTrue(AutoPilotRules.ownerToldLeave(left + 2 * m, "voice", "Встреча с другом", "встречаюсь с Ильёй", left))
    }

    @Test
    fun `слова выхода растягивают окно до получаса, без них - десять минут`() {
        val left = now
        assertTrue(AutoPilotRules.ownerToldLeave(left - 25 * m, "voice", "Разговор с другом", "Пошёл поговорить с другом", left))
        assertTrue(AutoPilotRules.ownerToldLeave(left - 20 * m, "voice", "Отвожу Борю в школу", "отвожу Борю", left))
        // «Завтракаю» за двадцать минут до выхода — не ответ: дорога и вопрос в силе.
        assertFalse(AutoPilotRules.ownerToldLeave(left - 20 * m, "voice", "Завтрак", "Завтракаю", left))
        assertTrue(AutoPilotRules.ownerToldLeave(left - 8 * m, "voice", "Завтрак", "Завтракаю", left))
        // Час назад — давно, какие бы ни были слова.
        assertFalse(AutoPilotRules.ownerToldLeave(left - 60 * m, "voice", "Прогулка", "Пошёл гулять", left))
    }

    @Test
    fun `робот, заполнитель и запись без слов - не слово владельца`() {
        assertFalse(AutoPilotRules.ownerToldLeave(now - 2 * m, "auto", "сон", "", now))
        assertFalse(AutoPilotRules.ownerToldLeave(now - 2 * m, "gap", "Не размечено", "", now))
        assertFalse(AutoPilotRules.ownerToldLeave(null, null, "", "", now))
        // Встреча из календаря и дело по подъёму — владельческий источник, но без надиктовки.
        assertFalse(AutoPilotRules.ownerToldLeave(now - 2 * m, "voice", "Созвон Tasty Coffee", "", now))
    }

    @Test
    fun `окно прошло - решение устарело`() {
        assertEquals(AutoPilotRules.Walk.NONE, AutoPilotRules.walkVerdict(6, now - h, now, null, null, 0L))
    }

    // ---- «всё ещё …?» ----

    @Test
    fun `всё ещё - по всем делам, кроме тех, где движение и есть дело`() {
        assertTrue(AutoPilotRules.stillAskable("Работа: ЗФ", "Работа: текущая"))
        assertTrue(AutoPilotRules.stillAskable("Обед", "Еда"))
        assertTrue(AutoPilotRules.stillAskable("Разговор с Марианной", "Семья"))
        assertTrue(AutoPilotRules.stillAskable("Сериал", "Отдых"))
        assertTrue(AutoPilotRules.stillAskable("Ютуб", "Потери"))
        assertFalse(AutoPilotRules.stillAskable("Поездка из «дом»", "Передвижение: транспорт"))
        assertFalse(AutoPilotRules.stillAskable("Бег", "Спорт: бег"))
        assertFalse(AutoPilotRules.stillAskable("Уборка", "Быт"))
        assertFalse(AutoPilotRules.stillAskable("Сон", "Сон"))
        assertFalse(AutoPilotRules.stillAskable("Не размечено", "Не размечено"))
    }

    @Test
    fun `вынес мусор - один толчок, сеть на месте - не спрашиваем`() {
        assertFalse(AutoPilotRules.stillAsk(motions = 1, leftAfterMotion = false, leaveAsked = true))
    }

    @Test
    fun `встал и не сел - спрашиваем`() {
        assertTrue(AutoPilotRules.stillAsk(motions = 3, leftAfterMotion = false, leaveAsked = true))
    }

    @Test
    fun `сеть пропала после движения - спросит вопрос уехал, здесь молчим`() {
        assertFalse(AutoPilotRules.stillAsk(motions = 1, leftAfterMotion = true, leaveAsked = true))
        // Вопрос «уехал?» выключен — тогда спрашиваем здесь.
        assertTrue(AutoPilotRules.stillAsk(motions = 1, leftAfterMotion = true, leaveAsked = false))
    }
}

// ---- отбой и подъём (27.09.2026) ----

class AutoPilotBedtimeTest {

    private val h = 3_600_000L
    private val m = 60_000L
    private val now = 100 * h

    // ---- сон сам (01.10.2026) ----

    private val zone = java.util.TimeZone.getTimeZone("Europe/Moscow")

    /** Миг [day] октября 2026 в [hh]:[mm] по Москве. */
    private fun at(day: Int, hh: Int, mm: Int = 0): Long {
        val c = java.util.Calendar.getInstance(zone)
        c.clear()
        c.set(2026, java.util.Calendar.OCTOBER, day, hh, mm, 0)
        return c.timeInMillis
    }

    @Test
    fun `зарядка в 23-10, экран погас в 23-12 - сон с 23-12, решаем через полчаса`() {
        val p = AutoPilotRules.sleepPlan(at(1, 23, 10), at(1, 23, 12), 0L, at(1, 21, 0), zone)!!
        assertEquals(at(1, 23, 12), p.from)
        assertEquals(at(1, 23, 42), p.decideAt)
    }

    @Test
    fun `лёг в 22-15 - сон с 22-15, но решаем не раньше 23-00`() {
        val p = AutoPilotRules.sleepPlan(at(1, 22, 10), at(1, 22, 15), 0L, 0L, zone)!!
        assertEquals(at(1, 22, 15), p.from)
        assertEquals(at(1, 23, 0), p.decideAt)
    }

    @Test
    fun `толчок и слово владельца двигают начало сна`() {
        val moved = AutoPilotRules.sleepPlan(at(1, 23, 0), at(1, 23, 2), at(1, 23, 20), 0L, zone)!!
        assertEquals(at(1, 23, 20), moved.from)
        assertEquals(at(1, 23, 50), moved.decideAt)
        // «Читаю» в 23:30 гарнитурой — не спит, сон не раньше этого.
        val said = AutoPilotRules.sleepPlan(at(1, 23, 0), at(1, 23, 2), 0L, at(1, 23, 30), zone)!!
        assertEquals(at(1, 23, 30), said.from)
    }

    @Test
    fun `после полуночи - решаем через полчаса, 23-00 уже прошли`() {
        val p = AutoPilotRules.sleepPlan(at(2, 0, 40), at(2, 0, 45), 0L, 0L, zone)!!
        assertEquals(at(2, 0, 45), p.from)
        assertEquals(at(2, 1, 15), p.decideAt)
    }

    @Test
    fun `зарядка с семи вечера на столе - не сон`() {
        assertNull(AutoPilotRules.sleepPlan(at(1, 19, 0), at(1, 23, 0), 0L, 0L, zone))
    }

    @Test
    fun `экран горит или нет зарядки - не сон`() {
        assertNull(AutoPilotRules.sleepPlan(at(1, 23, 0), 0L, 0L, 0L, zone))
        assertNull(AutoPilotRules.sleepPlan(0L, at(1, 23, 0), 0L, 0L, zone))
    }

    @Test
    fun `взял телефон в четыре ночи - не подъём, с пяти утра - подъём`() {
        for (hour in listOf(22, 23, 0, 1, 2, 3, 4)) assertFalse("час $hour", AutoPilotRules.sleepWakeHour(hour))
        for (hour in listOf(5, 6, 7, 9, 13, 21)) assertTrue("час $hour", AutoPilotRules.sleepWakeHour(hour))
    }

    @Test
    fun `подъём в будни в семь - сборы детей`() {
        assertTrue(AutoPilotRules.wakeDealDue(dayOfWeek = 2, wakeHour = 7, wakeAt = now, latestOwnerStart = now - 9 * h))
        assertTrue(AutoPilotRules.wakeDealDue(dayOfWeek = 6, wakeHour = 6, wakeAt = now, latestOwnerStart = 0L))
    }

    @Test
    fun `суббота и воскресенье - без сборов`() {
        assertFalse(AutoPilotRules.wakeDealDue(dayOfWeek = 7, wakeHour = 7, wakeAt = now, latestOwnerStart = 0L))
        assertFalse(AutoPilotRules.wakeDealDue(dayOfWeek = 1, wakeHour = 7, wakeAt = now, latestOwnerStart = 0L))
    }

    @Test
    fun `проснулся в одиннадцать или в четыре - не сборы`() {
        assertFalse(AutoPilotRules.wakeDealDue(2, 11, now, 0L))
        assertFalse(AutoPilotRules.wakeDealDue(2, 4, now, 0L))
    }

    @Test
    fun `владелец уже сказал зарядка после подъёма - молчим`() {
        assertFalse(AutoPilotRules.wakeDealDue(3, 7, now, latestOwnerStart = now + 3 * m))
    }
}
