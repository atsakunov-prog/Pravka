package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.GripGesture.Act

/**
 * Язычок колонки разворота (отладка 16.10.2026, запись 9): держишь и ведёшь — колонка едет, тап — спрятать или
 * показать, подержал и отпустил — ничего. До 16.10 после долгого нажатия движение съедалось, и колонка стояла.
 */
class GripGestureTest {

    @Test
    fun quickTapToggles() {
        val g = GripGesture()
        assertEquals(Act.Toggle, g.up())
    }

    @Test
    fun jitterBelowSlopIsStillTap() {
        val g = GripGesture()
        assertEquals(Act.None, g.move(3f, 2f))
        assertEquals(Act.None, g.move(-2f, 1f))
        assertFalse(g.dragging)
        assertEquals(Act.Toggle, g.up())
    }

    @Test
    fun holdAndReleaseDoesNothing() {
        val g = GripGesture()
        assertEquals(Act.Held, g.longPress())
        // Палец чуть дрогнул — ещё не протяжка.
        assertEquals(Act.None, g.move(1f, 0.5f))
        assertEquals(Act.None, g.up())
    }

    @Test
    fun stillFingerPastLongPressIsNotTap() {
        // Палец стоял неподвижно — событий нет, таймер «держу» не успел: отпустил позже долгого нажатия — не тап.
        val g = GripGesture()
        assertEquals(Act.None, g.up(longHeld = true))
        // Тот же палец с дрожью меньше порога — тоже.
        val g2 = GripGesture()
        g2.move(2f, 1f)
        assertEquals(Act.None, g2.up(longHeld = true))
    }

    @Test
    fun holdThenMoveDrags() {
        // Сама запись 9: держит, вибрация, ведёт — колонка идёт за пальцем.
        val g = GripGesture()
        assertEquals(Act.Held, g.longPress())
        assertEquals(Act.Start(3f), g.move(3f, 0f))
        assertTrue(g.dragging)
        assertEquals(Act.Drag(10f), g.move(10f, 4f))
        assertEquals(Act.Drag(-6f), g.move(-6f, 0f))
        assertEquals(Act.End, g.up())
    }

    @Test
    fun moveRightAwayDragsWithAccumulatedShift() {
        val g = GripGesture()
        assertEquals(Act.None, g.move(5f, 1f))
        assertEquals(Act.Start(10f), g.move(5f, 0f))
        // Долгое нажатие посреди протяжки — уже не удержание.
        assertEquals(Act.None, g.longPress())
        assertEquals(Act.Drag(-4f), g.move(-4f, 0f))
        assertEquals(Act.End, g.up())
    }

    @Test
    fun verticalSwipeIsNotOurs() {
        val g = GripGesture()
        assertEquals(Act.None, g.move(2f, 12f))
        assertTrue(g.gone)
        // Дальше ни протяжки, ни тапа, ни удержания.
        assertEquals(Act.None, g.move(30f, 0f))
        assertEquals(Act.None, g.longPress())
        assertEquals(Act.None, g.up())
    }

    @Test
    fun diagonalWithMoreSidewaysStarts() {
        val g = GripGesture()
        assertEquals(Act.Start(9f), g.move(9f, 9f))
    }

    @Test
    fun cancelMidDragEnds() {
        // Жест оборвался (сложили телефон, ушли с экрана) — колонки не должны застрять «держат».
        val g = GripGesture()
        g.move(12f, 0f)
        assertEquals(Act.End, g.cancel())
        assertEquals(Act.None, g.up())
        // Оборвался без протяжки — ничего.
        assertEquals(Act.None, GripGesture().cancel())
    }
}
