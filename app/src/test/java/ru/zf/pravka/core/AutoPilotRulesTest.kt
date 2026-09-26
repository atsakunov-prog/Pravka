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
