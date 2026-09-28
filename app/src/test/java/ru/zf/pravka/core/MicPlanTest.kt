package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Куда смотрит микрофон в тейке (docs/agreements.md, «Микрофон выбирает
// владелец, а не подключение»). Случай, ради которого это писалось: машина.
class MicPlanTest {

    @Test
    fun `телефон и ничего вокруг - стартуем без задержки`() {
        assertEquals(
            MicPlan.Route.AS_IS,
            MicPlan.choose(phoneMic = true, headsetPresent = false, btRouteUp = false, callInProgress = false),
        )
        // Гарнитура подключена, но канал не поднят: вход и так встроенный,
        // перекладывать нечего — лишний переезд стоил бы первых слов.
        assertEquals(
            MicPlan.Route.AS_IS,
            MicPlan.choose(phoneMic = true, headsetPresent = true, btRouteUp = false, callInProgress = false),
        )
    }

    @Test
    fun `машина держит канал, а слушать велено телефон - забираем вход себе`() {
        assertEquals(
            MicPlan.Route.BUILTIN,
            MicPlan.choose(phoneMic = true, headsetPresent = true, btRouteUp = true, callInProgress = false),
        )
        // Канал поднят, а гарнитуры среди входов не видно (машина отдаёт
        // только выход) — всё равно забираем: слышно-то именно её микрофон.
        assertEquals(
            MicPlan.Route.BUILTIN,
            MicPlan.choose(phoneMic = true, headsetPresent = false, btRouteUp = true, callInProgress = false),
        )
    }

    @Test
    fun `гарнитура - только когда она на месте`() {
        assertEquals(
            MicPlan.Route.HEADSET,
            MicPlan.choose(phoneMic = false, headsetPresent = true, btRouteUp = false, callInProgress = false),
        )
        assertEquals(
            MicPlan.Route.AS_IS,
            MicPlan.choose(phoneMic = false, headsetPresent = false, btRouteUp = false, callInProgress = false),
        )
    }

    @Test
    fun `идёт разговор - маршрут не трогаем ни при каком выборе`() {
        assertEquals(
            MicPlan.Route.AS_IS,
            MicPlan.choose(phoneMic = true, headsetPresent = true, btRouteUp = true, callInProgress = true),
        )
        assertEquals(
            MicPlan.Route.AS_IS,
            MicPlan.choose(phoneMic = false, headsetPresent = true, btRouteUp = false, callInProgress = true),
        )
    }

    @Test
    fun `ждём переезда только тогда, когда сами его заказали и заявку приняли`() {
        assertTrue(MicPlan.waitsForRoute(MicPlan.Route.BUILTIN, changed = true))
        assertTrue(MicPlan.waitsForRoute(MicPlan.Route.HEADSET, changed = true))
        // Система отказала — ждать нечего, стартуем сразу.
        assertFalse(MicPlan.waitsForRoute(MicPlan.Route.BUILTIN, changed = false))
        assertFalse(MicPlan.waitsForRoute(MicPlan.Route.AS_IS, changed = true))
    }

    // ---- Кружок микрофона в пилюле: смена посреди тейка ----

    @Test
    fun `начал у телефона, надел наушники - переводим на гарнитуру`() {
        assertEquals(
            MicPlan.Change.HEADSET,
            MicPlan.change(wantHeadset = true, headsetPresent = true, headsetRouted = false, btRouteUp = false, callInProgress = false),
        )
        // Машина держала канал, а мы забрали вход телефону: канал «поднят»,
        // но слушает телефон — переводить на гарнитуру всё равно надо.
        assertEquals(
            MicPlan.Change.HEADSET,
            MicPlan.change(wantHeadset = true, headsetPresent = true, headsetRouted = false, btRouteUp = true, callInProgress = false),
        )
    }

    @Test
    fun `наушников нет - остаётся телефон, и об этом говорится`() {
        assertEquals(
            MicPlan.Change.NO_HEADSET,
            MicPlan.change(wantHeadset = true, headsetPresent = false, headsetRouted = false, btRouteUp = false, callInProgress = false),
        )
    }

    @Test
    fun `обратно на телефон - забираем вход, если связь смотрит на гарнитуру`() {
        assertEquals(
            MicPlan.Change.BUILTIN,
            MicPlan.change(wantHeadset = false, headsetPresent = true, headsetRouted = true, btRouteUp = true, callInProgress = false),
        )
        // Канал держит машина, связь не наша — вход всё равно забираем себе.
        assertEquals(
            MicPlan.Change.BUILTIN,
            MicPlan.change(wantHeadset = false, headsetPresent = true, headsetRouted = false, btRouteUp = true, callInProgress = false),
        )
        // Канал не поднят — слушает и так телефон.
        assertEquals(
            MicPlan.Change.NOTHING,
            MicPlan.change(wantHeadset = false, headsetPresent = true, headsetRouted = false, btRouteUp = false, callInProgress = false),
        )
    }

    @Test
    fun `уже на гарнитуре - трогать нечего`() {
        assertEquals(
            MicPlan.Change.NOTHING,
            MicPlan.change(wantHeadset = true, headsetPresent = true, headsetRouted = true, btRouteUp = true, callInProgress = false),
        )
    }

    @Test
    fun `посреди разговора микрофон не переключаем`() {
        assertEquals(
            MicPlan.Change.CALL,
            MicPlan.change(wantHeadset = true, headsetPresent = true, headsetRouted = false, btRouteUp = false, callInProgress = true),
        )
        assertEquals(
            MicPlan.Change.CALL,
            MicPlan.change(wantHeadset = false, headsetPresent = true, headsetRouted = true, btRouteUp = true, callInProgress = true),
        )
    }
}
