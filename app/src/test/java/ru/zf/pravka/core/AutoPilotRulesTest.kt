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
    fun `окно прошло - решение устарело`() {
        assertEquals(AutoPilotRules.Walk.NONE, AutoPilotRules.walkVerdict(6, now - h, now, null, null, 0L))
    }

    // ---- «всё ещё …?» ----

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

    @Test
    fun `зарядка вечером, экран погас - отбой позднее из двух`() {
        val charged = now - 5 * m
        val off = now - 2 * m
        assertEquals(off, AutoPilotRules.bedtimeCandidate(charged, off, chargeHour = 23))
        assertEquals(charged, AutoPilotRules.bedtimeCandidate(charged, now - 30 * m, chargeHour = 0))
    }

    @Test
    fun `зарядка с семи вечера на столе - не отбой`() {
        assertEquals(0L, AutoPilotRules.bedtimeCandidate(now - 4 * h, now - 2 * m, chargeHour = 19))
    }

    @Test
    fun `экран горит или нет зарядки - не отбой`() {
        assertEquals(0L, AutoPilotRules.bedtimeCandidate(now - 5 * m, 0L, chargeHour = 23))
        assertEquals(0L, AutoPilotRules.bedtimeCandidate(0L, now - 5 * m, chargeHour = 23))
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
