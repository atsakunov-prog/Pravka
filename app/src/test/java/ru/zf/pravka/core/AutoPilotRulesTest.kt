package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- дорога по словам ----

    @Test
    fun `дорога по словам`() {
        assertTrue(AutoPilotRules.travelish("Еду в Летово", "Семья"))
        assertTrue(AutoPilotRules.travelish("Такси в аэропорт", ""))
        assertFalse(AutoPilotRules.travelish("Работа: ЗФ", "Работа"))
    }
}
