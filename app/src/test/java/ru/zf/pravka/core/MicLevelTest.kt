package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Пульс кнопки по громкости (docs/telo.md, «Кнопка слышит»). Наружу расти
// нельзя — окно ровно с кружок, — поэтому на записи кнопка поджата и
// распрямляется от звука.
class MicLevelTest {

    @Test
    fun `тишина - ноль, крик - единица, между ними ровно`() {
        assertEquals(0f, MicLevel.normalise(MicLevel.FLOOR_DB), 0f)
        assertEquals(1f, MicLevel.normalise(MicLevel.CEILING_DB), 0f)
        assertEquals(0.5f, MicLevel.normalise(3f), 0.001f)
        // За краями шкала не ломается: распознаватель отдаёт что угодно.
        assertEquals(0f, MicLevel.normalise(-120f), 0f)
        assertEquals(1f, MicLevel.normalise(120f), 0f)
        assertEquals(0f, MicLevel.normalise(Float.NaN), 0f)
    }

    @Test
    fun `кнопка поджата в тишине и распрямляется от голоса`() {
        assertEquals(MicLevel.QUIET, MicLevel.scale(0f), 0f)
        assertEquals(1f, MicLevel.scale(1f), 0f)
        assertTrue(MicLevel.scale(0.5f) > MicLevel.QUIET)
        assertTrue(MicLevel.scale(0.5f) < 1f)
        // Наружу не растём никогда: окно отрезало бы круг по краям.
        assertTrue(MicLevel.scale(5f) <= 1f)
    }

    @Test
    fun `вверх шкала идёт быстрее, чем вниз`() {
        val up = MicLevel.smooth(0f, 1f)
        val down = MicLevel.smooth(1f, 0f)
        assertTrue("удар слышен сразу", up > 0.5f)
        assertTrue("спад глаз додумывает", down > 0.5f)
        assertTrue(up > 1f - down)
    }

    @Test
    fun `сглаживание сходится и за края не выходит`() {
        var v = 0f
        repeat(20) { v = MicLevel.smooth(v, 1f) }
        assertEquals(1f, v, 0.01f)
        repeat(40) { v = MicLevel.smooth(v, 0f) }
        assertEquals(0f, v, 0.01f)
        assertTrue(MicLevel.smooth(0f, 9f) <= 1f)
    }
}
