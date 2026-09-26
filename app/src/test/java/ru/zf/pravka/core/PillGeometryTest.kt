package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.PillGeometry.Box

// Пилюля диктовки (docs/pravka.md, «Пилюля диктовки»): посередине под строкой
// состояния (с завода) или над клавиатурой, и никогда — поверх кнопки, которая пишет.
class PillGeometryTest {

    // Телефон в пикселях при плотности 2,5: 412 × 915 dp.
    private val w = 1030
    private val h = 2288
    private val pillH = 140
    private val side = 40
    private val gap = 25
    private val minW = 500

    private fun overlaps(a: Box, b: Box) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun box(s: PillGeometry.Spot) = Box(s.x, s.y, s.x + s.width, s.y + pillH)

    @Test
    fun `пол - над клавиатурой, если она открыта, иначе над навигацией`() {
        assertEquals(h - 60 - gap, PillGeometry.floor(h, imeBottom = 0, navBottom = 60, gap = gap))
        // Клавиатура своей высотой уже накрывает навигацию: берётся большая, не сумма.
        assertEquals(h - 900 - gap, PillGeometry.floor(h, imeBottom = 900, navBottom = 60, gap = gap))
        // Ничего нет — просто зазор от края.
        assertEquals(h - gap, PillGeometry.floor(h, 0, 0, gap))
    }

    @Test
    fun `без препятствий - посередине, нижним краем на полу`() {
        val floor = PillGeometry.floor(h, 0, 60, gap)
        val s = PillGeometry.bottom(w, floor, wantW = 850, h = pillH, side, gap, minW, emptyList())
        assertEquals(850, s.width)
        assertEquals((w - 850) / 2, s.x)
        assertEquals(floor - pillH, s.y)
    }

    @Test
    fun `шире экрана не бывает - поля остаются`() {
        val s = PillGeometry.bottom(w, 2000, wantW = 5000, h = pillH, side, gap, minW, emptyList())
        assertEquals(w - 2 * side, s.width)
        assertEquals(side, s.x)
    }

    @Test
    fun `клавиатура поднимает пилюлю над собой`() {
        val floor = PillGeometry.floor(h, imeBottom = 900, navBottom = 60, gap = gap)
        val s = PillGeometry.bottom(w, floor, 850, pillH, side, gap, minW, emptyList())
        assertTrue("низ пилюли над клавиатурой", s.y + pillH <= h - 900)
    }

    @Test
    fun `диск у правого края внизу - пилюля ужимается влево и кнопку не накрывает`() {
        val floor = PillGeometry.floor(h, 0, 60, gap)
        val button = Box(w - 130, floor - 150, w - 10, floor - 30)
        val s = PillGeometry.bottom(w, floor, 850, pillH, side, gap, minW, listOf(button))
        assertTrue(!overlaps(box(s), button))
        assertEquals("на том же полу", floor - pillH, s.y)
        assertTrue("не уже порога", s.width >= minW)
        assertTrue("между левым полем и кнопкой", s.x >= side && s.x + s.width <= button.left - gap)
    }

    @Test
    fun `кнопка посередине внизу - вбок не влезть, пилюля встаёт над ней`() {
        val floor = PillGeometry.floor(h, 0, 60, gap)
        val button = Box(w / 2 - 60, floor - 120, w / 2 + 60, floor)
        val s = PillGeometry.bottom(w, floor, 850, pillH, side, gap, minW, listOf(button))
        assertTrue(!overlaps(box(s), button))
        assertEquals(button.top - gap - pillH, s.y)
        assertEquals("над кнопкой снова во всю ширину", 850, s.width)
    }

    @Test
    fun `кнопка высоко на экране пилюле не мешает`() {
        val floor = PillGeometry.floor(h, 0, 60, gap)
        val button = Box(w - 130, 400, w - 10, 520)
        val s = PillGeometry.bottom(w, floor, 850, pillH, side, gap, minW, listOf(button))
        assertEquals(850, s.width)
        assertEquals((w - 850) / 2, s.x)
    }

    @Test
    fun `кнопка и её отмена - обе препятствия`() {
        val floor = PillGeometry.floor(h, 0, 60, gap)
        val button = Box(w - 130, floor - 260, w - 10, floor - 140)
        val cancel = Box(w - 330, floor - 120, w - 150, floor - 40)
        val s = PillGeometry.bottom(w, floor, 850, pillH, side, gap, minW, listOf(button, cancel))
        assertTrue(!overlaps(box(s), button))
        assertTrue(!overlaps(box(s), cancel))
    }

    @Test
    fun `потолок - под строкой состояния или под вырезом, что глубже`() {
        assertEquals(80 + gap, PillGeometry.ceiling(statusTop = 80, cutoutTop = 0, gap = gap))
        assertEquals(120 + gap, PillGeometry.ceiling(statusTop = 80, cutoutTop = 120, gap = gap))
    }

    @Test
    fun `сверху без препятствий - посередине, верхним краем под потолком`() {
        val ceiling = PillGeometry.ceiling(80, 0, gap)
        val s = PillGeometry.top(w, ceiling, 850, pillH, side, gap, minW, emptyList())
        assertEquals(ceiling, s.y)
        assertEquals((w - 850) / 2, s.x)
        assertEquals(850, s.width)
    }

    @Test
    fun `сверху кнопка у правого края - пилюля ужимается влево`() {
        val ceiling = PillGeometry.ceiling(80, 0, gap)
        val button = Box(w - 130, ceiling, w - 10, ceiling + 120)
        val s = PillGeometry.top(w, ceiling, 850, pillH, side, gap, minW, listOf(button))
        assertTrue(!overlaps(box(s), button))
        assertEquals("под тем же потолком", ceiling, s.y)
        assertTrue(s.width >= minW)
    }

    @Test
    fun `сверху кнопка посередине - пилюля опускается под неё`() {
        val ceiling = PillGeometry.ceiling(80, 0, gap)
        val button = Box(w / 2 - 60, ceiling + 10, w / 2 + 60, ceiling + 130)
        val s = PillGeometry.top(w, ceiling, 850, pillH, side, gap, minW, listOf(button))
        assertTrue(!overlaps(box(s), button))
        assertEquals(button.bottom + gap, s.y)
        assertEquals(850, s.width)
    }

    @Test
    fun `место из настройки - по ключу, незнакомое - нет`() {
        assertEquals(PillGeometry.Place.TOP, PillGeometry.Place.fromKey("top"))
        assertEquals(PillGeometry.Place.BOTTOM, PillGeometry.Place.fromKey("bottom"))
        assertEquals(PillGeometry.Place.BESIDE, PillGeometry.Place.fromKey("button"))
        assertEquals(null, PillGeometry.Place.fromKey(null))
        assertEquals(null, PillGeometry.Place.fromKey("left"))
    }

    @Test
    fun `самая широкая полоса между препятствиями`() {
        val lane = PillGeometry.widestLane(1000, 0, 0, listOf(Box(100, 0, 200, 10), Box(700, 0, 800, 10)))
        assertEquals(200 to 700, lane)
        // Препятствие за полями не отнимает ничего внутри.
        assertEquals(40 to 960, PillGeometry.widestLane(1000, 40, 0, listOf(Box(0, 0, 20, 10))))
    }

    @Test
    fun `у кнопки - прежнее место сбоку, на стороне с местом`() {
        val left = Box(0, 1000, 120, 1120)
        val s = PillGeometry.beside(w, h, left, wantW = 850, h = pillH, gap = 20, margin = 60)
        assertEquals(120 + 20, s.x)
        assertEquals("по центру кнопки по высоте", 1000 - (pillH - 120) / 2, s.y)
        val right = Box(w - 120, 1000, w, 1120)
        val r = PillGeometry.beside(w, h, right, wantW = 850, h = pillH, gap = 20, margin = 60)
        assertEquals(w - 120 - r.width - 20, r.x)
        assertTrue("кнопка рядом остаётся видна", r.width <= w - 120 - 60)
    }
}
