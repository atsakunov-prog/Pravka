package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Диск плавающих кнопок (docs/telo.md, «Диск вместо стопки»): кольцо вокруг
// шестерёнки, дом лицом внутрь экрана, щелчки поворота, докование к краю.
class DiskGeometryTest {

    private val button = 48
    private val gear = StackGeometry.gearSize(button)  // 34
    private val gap = 8

    @Test
    fun `кольцо - полшестерёнки, полтора просвета, полкнопки`() {
        assertEquals(17f + 12f + 24f, DiskGeometry.ringRadius(button, gear, gap), 0.01f)
        assertEquals(53f + 24f + 6f, DiskGeometry.plateRadius(button, gear, gap), 0.01f)
        assertEquals(17 + 4, DiskGeometry.dockInset(gear, gap))
    }

    @Test
    fun `шаг - четверть для четырёх и двух, треть для трёх`() {
        assertEquals(90f, DiskGeometry.spread(4), 0f)
        assertEquals(120f, DiskGeometry.spread(3), 0f)
        assertEquals(90f, DiskGeometry.spread(2), 0f)
    }

    @Test
    fun `диск смотрит внутрь экрана`() {
        assertEquals(180f, DiskGeometry.facing(cx = 1000f, frameW = 1080), 0f)
        assertEquals(0f, DiskGeometry.facing(cx = 80f, frameW = 1080), 0f)
    }

    @Test
    fun `дома у правого края П вверху-слева, З внизу-слева, Д и Е за краем`() {
        val facing = 180f
        assertEquals(135f, DiskGeometry.slotAngle(0, 4, facing, 0f), 0f)   // П: вверх-влево
        assertEquals(225f, DiskGeometry.slotAngle(1, 4, facing, 0f), 0f)   // З: вниз-влево
        assertEquals(315f, DiskGeometry.slotAngle(2, 4, facing, 0f), 0f)   // Д: вниз-вправо, за краем
        assertEquals(45f, DiskGeometry.slotAngle(3, 4, facing, 0f), 0f)    // Е: вверх-вправо, за краем
    }

    @Test
    fun `у левого края те же две кнопки внутри - лицо развёрнуто`() {
        assertEquals(315f, DiskGeometry.slotAngle(0, 4, 0f, 0f), 0f)   // П: вверх-вправо
        assertEquals(45f, DiskGeometry.slotAngle(1, 4, 0f, 0f), 0f)    // З: вниз-вправо
    }

    @Test
    fun `поворот на четверть выводит Е на место П`() {
        assertEquals(135f, DiskGeometry.slotAngle(3, 4, 180f, 90f), 0f)
    }

    @Test
    fun `три кнопки - через 120, одна - прямо на лице`() {
        assertEquals(120f, DiskGeometry.slotAngle(0, 3, 180f, 0f), 0f)
        assertEquals(240f, DiskGeometry.slotAngle(1, 3, 180f, 0f), 0f)
        assertEquals(0f, DiskGeometry.slotAngle(2, 3, 180f, 0f), 0f)
        assertEquals(180f, DiskGeometry.slotAngle(0, 1, 180f, 0f), 0f)
    }

    @Test
    fun `окно кнопки - центр на кольце минус полкнопки`() {
        val r = DiskGeometry.ringRadius(button, gear, gap)  // 53
        val (x, y) = DiskGeometry.slotOrigin(cx = 500f, cy = 500f, radius = r, angle = 180f, size = button)
        assertEquals(500 - 53 - 24, x)
        assertEquals(500 - 24, y)
        val (x2, y2) = DiskGeometry.slotOrigin(500f, 500f, r, 90f, button)
        assertEquals(500 - 24, x2)
        assertEquals(500 + 53 - 24, y2)
    }

    @Test
    fun `угол пальца и кратчайшая разница через ноль`() {
        assertEquals(90f, DiskGeometry.angleOf(0f, 0f, 0f, 10f), 0.01f)
        assertEquals(180f, DiskGeometry.angleOf(0f, 0f, -10f, 0f), 0.01f)
        assertEquals(20f, DiskGeometry.delta(350f, 10f), 0.01f)
        assertEquals(-20f, DiskGeometry.delta(10f, 350f), 0.01f)
        assertEquals(180f, DiskGeometry.delta(0f, 180f), 0.01f)
    }

    @Test
    fun `щелчок - ближайшая четверть, бросок доворачивает`() {
        assertEquals(90f, DiskGeometry.snap(70f, 90f), 0f)
        assertEquals(0f, DiskGeometry.snap(40f, 90f), 0f)
        // Лёгкий мах (400°/с) с 20° — через четверть; сильный (1500°/с) — через две.
        assertEquals(90f, DiskGeometry.snap(20f, 90f, velocity = 400f), 0f)
        assertEquals(180f, DiskGeometry.snap(20f, 90f, velocity = 1500f), 0f)
        // Быстрее потолка не бывает.
        assertEquals(180f, DiskGeometry.snap(20f, 90f, velocity = 9000f), 0f)
        assertEquals(-90f, DiskGeometry.snap(-20f, 90f, velocity = -600f), 0f)
    }

    @Test
    fun `дом - ближайший полный круг`() {
        assertEquals(0f, DiskGeometry.home(170f), 0f)
        assertEquals(360f, DiskGeometry.home(190f), 0f)
        assertEquals(-360f, DiskGeometry.home(-200f), 0f)
    }

    @Test
    fun `докование - за край зашёл, к краю и прижался`() {
        val plate = DiskGeometry.plateRadius(button, gear, gap)  // 83
        val inset = DiskGeometry.dockInset(gear, gap)            // 21
        // Правый край: центр в 1040 из 1080, тарелка вылезает — центр на 1080 − 21.
        assertEquals(1059f to 500f, DiskGeometry.dock(1040f, 500f, 1080, 2000, plate, inset))
        // Левый край.
        assertEquals(21f to 500f, DiskGeometry.dock(30f, 500f, 1080, 2000, plate, inset))
        // Посреди экрана — где отпустили.
        assertEquals(540f to 700f, DiskGeometry.dock(540f, 700f, 1080, 2000, plate, inset))
        // По вертикали тарелка не уходит за экран.
        assertEquals(540f to 83f, DiskGeometry.dock(540f, 10f, 1080, 2000, plate, inset))
        assertEquals(540f to (2000f - 83f), DiskGeometry.dock(540f, 1990f, 1080, 2000, plate, inset))
    }

    @Test
    fun `окно за краем целиком - невидимо, торчит краем - видимо`() {
        assertFalse(DiskGeometry.onScreen(x = 1080, y = 500, size = 48, frameW = 1080, frameH = 2000, margin = 4))
        assertFalse(DiskGeometry.onScreen(x = 1078, y = 500, size = 48, frameW = 1080, frameH = 2000, margin = 4))
        assertTrue(DiskGeometry.onScreen(x = 1060, y = 500, size = 48, frameW = 1080, frameH = 2000, margin = 4))
        assertFalse(DiskGeometry.onScreen(x = -48, y = 500, size = 48, frameW = 1080, frameH = 2000, margin = 4))
        assertTrue(DiskGeometry.onScreen(x = -30, y = 500, size = 48, frameW = 1080, frameH = 2000, margin = 4))
    }
}
