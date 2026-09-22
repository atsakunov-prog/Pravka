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
        assertEquals(0, DiskGeometry.DOCK_INSET)
    }

    @Test
    fun `инерция - диск катится вбок, как колесо`() {
        val r = 240f
        // Везём вправо — кольцо проворачивается против часовой (угол убывает).
        assertTrue(DiskGeometry.roll(100f, r, 1f) < 0f)
        assertTrue(DiskGeometry.roll(-100f, r, 1f) > 0f)
        // Качение без проскальзывания: путь, равный радиусу, — это радиан.
        assertEquals(-57.3f, DiskGeometry.roll(r, r, 1f), 0.1f)
        // Охота катиться делит угол ровно пополам, ноль — не катимся.
        assertEquals(DiskGeometry.roll(r, r, 1f) / 2f, DiskGeometry.roll(r, r, 0.5f), 0.01f)
        assertEquals(0f, DiskGeometry.roll(r, r, 0f), 0f)
        // Диск без радиуса (ещё не расставлен) не крутится и не делит на ноль.
        assertEquals(0f, DiskGeometry.roll(100f, 0f, 1f), 0f)
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

    // Экранная ось y смотрит вниз: 225° — вверх-влево, 135° — вниз-влево.
    @Test
    fun `дома у правого края П вверху-слева, З внизу-слева, Д и инструменты за краем`() {
        val facing = 180f
        assertEquals(225f, DiskGeometry.slotAngle(0, 4, facing, 0f), 0f)   // П: вверх-влево
        assertEquals(135f, DiskGeometry.slotAngle(1, 4, facing, 0f), 0f)   // З: вниз-влево
        assertEquals(45f, DiskGeometry.slotAngle(2, 4, facing, 0f), 0f)    // Д: вниз-вправо, за краем
        assertEquals(315f, DiskGeometry.slotAngle(3, 4, facing, 0f), 0f)   // инструменты: вверх-вправо, за краем
    }

    @Test
    fun `у левого края те же две внутри, П снова сверху - раскладка зеркальна`() {
        assertEquals(315f, DiskGeometry.slotAngle(0, 4, 0f, 0f), 0f)   // П: вверх-вправо
        assertEquals(45f, DiskGeometry.slotAngle(1, 4, 0f, 0f), 0f)    // З: вниз-вправо
        assertEquals(135f, DiskGeometry.slotAngle(2, 4, 0f, 0f), 0f)   // Д: вниз-влево, за краем
        assertEquals(1f, DiskGeometry.sense(0f), 0f)
        assertEquals(-1f, DiskGeometry.sense(180f), 0f)
    }

    @Test
    fun `четверть против часовой выводит четвёртый слот на место П`() {
        assertEquals(225f, DiskGeometry.slotAngle(3, 4, 180f, -90f), 0f)
        // По часовой — «З» встаёт наверх, «Д» вниз.
        assertEquals(225f, DiskGeometry.slotAngle(1, 4, 180f, 90f), 0f)
        assertEquals(135f, DiskGeometry.slotAngle(2, 4, 180f, 90f), 0f)
    }

    @Test
    fun `три кнопки - через 120, одна - прямо на лице`() {
        assertEquals(240f, DiskGeometry.slotAngle(0, 3, 180f, 0f), 0f)
        assertEquals(120f, DiskGeometry.slotAngle(1, 3, 180f, 0f), 0f)
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
    fun `докование - за край зашёл, центр встал на край`() {
        val plate = DiskGeometry.plateRadius(button, gear, gap)  // 83
        val inset = DiskGeometry.DOCK_INSET                       // 0: центр ровно на краю
        // Правый край: центр в 1040 из 1080, тарелка вылезает — центр на самом краю.
        assertEquals(1080f to 500f, DiskGeometry.dock(1040f, 500f, 1080, 2000, plate, inset))
        // Левый край.
        assertEquals(0f to 500f, DiskGeometry.dock(30f, 500f, 1080, 2000, plate, inset))
        // Посреди экрана — где отпустили.
        assertEquals(540f to 700f, DiskGeometry.dock(540f, 700f, 1080, 2000, plate, inset))
        // По вертикали тарелка не уходит за экран.
        assertEquals(540f to 83f, DiskGeometry.dock(540f, 10f, 1080, 2000, plate, inset))
        assertEquals(540f to (2000f - 83f), DiskGeometry.dock(540f, 1990f, 1080, 2000, plate, inset))
    }

    // Убранный диск (владелец, 22.09.2026): стекло за краем, «П» и «З»
    // выдавлены из него на экран целиком, между ними — краешек со стрелкой.
    @Test
    fun `убранный диск оставляет на экране четверть радиуса - краешек со стрелкой`() {
        val plate = DiskGeometry.plateRadius(button, gear, gap)   // 83
        assertEquals(0.25f, DiskGeometry.SLIVER, 0f)
        assertEquals(62, DiskGeometry.tuckDepth(plate))           // 83 − четверть
        // Краешек шире стрелки: та сидит на 0,09 радиуса от кромки и сама
        // размером 0,065 — вместе меньше четверти.
        assertTrue(plate * DiskGeometry.SLIVER > plate * (0.09f + 0.065f))
    }

    @Test
    fun `вынос передних кнопок вдоль лица - косинус полушага`() {
        val ring = DiskGeometry.ringRadius(button, gear, gap)     // 53
        assertEquals(ring * 0.7071f, DiskGeometry.frontReach(ring, 4), 0.01f)
        assertEquals(ring * 0.7071f, DiskGeometry.frontReach(ring, 2), 0.01f)
        assertEquals(ring * 0.5f, DiskGeometry.frontReach(ring, 3), 0.01f)
        // Одна кнопка стоит прямо на лице — весь радиус кольца.
        assertEquals(ring, DiskGeometry.frontReach(ring, 1), 0.01f)
    }

    @Test
    fun `выдавливание ставит переднюю кнопку у края экрана целиком`() {
        val w = 1080
        val plate = DiskGeometry.plateRadius(button, gear, gap)   // 83
        val ring = DiskGeometry.ringRadius(button, gear, gap)     // 53
        val edge = gap
        val push = DiskGeometry.extrusion(plate, ring, button, 4, edge)
        // Правый край: центр тарелки ушёл за экран на глубину уборки.
        val (cx, cy) = DiskGeometry.dock(1040f, 900f, w, 2000, plate, -DiskGeometry.tuckDepth(plate))
        assertEquals((w + DiskGeometry.tuckDepth(plate)).toFloat(), cx, 0.01f)
        // Кнопки стоят вокруг СДВИНУТОГО центра: лицо смотрит влево.
        val bx = cx - push
        val (px, py) = DiskGeometry.slotOrigin(bx, cy, ring, DiskGeometry.slotAngle(0, 4, 180f, 0f), button)
        val (zx, zy) = DiskGeometry.slotOrigin(bx, cy, ring, DiskGeometry.slotAngle(1, 4, 180f, 0f), button)
        // Целиком на экране, дальней кромкой в зазоре от края.
        assertEquals(w - edge, px + button)
        assertEquals(w - edge, zx + button)
        assertTrue(DiskGeometry.onScreen(px, py, button, w, 2000, margin = 4))
        assertTrue(DiskGeometry.onScreen(zx, zy, button, w, 2000, margin = 4))
        // «П» сверху, «З» снизу, и между ними остаётся просвет под краешек.
        assertTrue(py + button < zy)
        // А стекла на экране остаётся ровно краешек — четверть радиуса.
        assertEquals(plate * DiskGeometry.SLIVER, w - (cx - plate), 0.6f)
    }

    @Test
    fun `у левого края выдавливание зеркально`() {
        val w = 1080
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val ring = DiskGeometry.ringRadius(button, gear, gap)
        val push = DiskGeometry.extrusion(plate, ring, button, 4, gap)
        val (cx, cy) = DiskGeometry.dock(40f, 900f, w, 2000, plate, -DiskGeometry.tuckDepth(plate))
        assertEquals(-DiskGeometry.tuckDepth(plate).toFloat(), cx, 0.01f)
        val bx = cx + push   // лицо смотрит вправо
        val (px, _) = DiskGeometry.slotOrigin(bx, cy, ring, DiskGeometry.slotAngle(0, 4, 0f, 0f), button)
        assertEquals(gap, px)
    }

    @Test
    fun `кольцо, вынесшее кнопки на экран само, обратно их не вдавливает`() {
        // Выдуманный диск: кольцо больше тарелки — так не бывает, но формула
        // не должна тащить кнопки к краю, если запас уже есть.
        assertEquals(0f, DiskGeometry.extrusion(80f, 400f, 48, 4, 8), 0f)
    }

    // Контур стекла: круг, растянутый под выдавленные кнопки (владелец,
    // 22.09.2026: «надо чтобы они выдавливались вместе с краем!»).
    private fun tuckedPods(): FloatArray {
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val ring = DiskGeometry.ringRadius(button, gear, gap)
        val push = DiskGeometry.extrusion(plate, ring, button, 4, gap)
        // Правый край: лицо влево, «П» вверху-слева, «З» внизу-слева.
        val out = FloatArray(4)
        listOf(0, 1).forEachIndexed { k, index ->
            val a = Math.toRadians(DiskGeometry.slotAngle(index, 4, 180f, 0f).toDouble())
            out[k * 2] = (-push + ring * kotlin.math.cos(a)).toFloat()
            out[k * 2 + 1] = (ring * kotlin.math.sin(a)).toFloat()
        }
        return out
    }

    /** Докуда от центра тарелки достаёт сама КНОПКА по лучу [i] — ноль, если луч мимо. */
    private fun buttonReach(pods: FloatArray, pod: Int, i: Int, rays: Int): Float {
        val px = pods[pod * 2]
        val py = pods[pod * 2 + 1]
        val d = kotlin.math.hypot(px, py)
        val phi = kotlin.math.atan2(py, px)
        val a = i * 2.0 * Math.PI / rays - phi
        val across = d * kotlin.math.sin(a)
        val k = (button / 2f) * (button / 2f) - across * across
        if (k <= 0.0) return 0f
        return (d * kotlin.math.cos(a) + kotlin.math.sqrt(k)).toFloat().coerceAtLeast(0f)
    }

    @Test
    fun `невыдавленный диск - ровный круг, контур считать незачем`() {
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val ring = DiskGeometry.ringRadius(button, gear, gap)
        val podR = DiskGeometry.podRadius(button, gap)
        // Карман кнопки касается кромки изнутри — в этом весь смысл радиуса.
        assertEquals(plate, ring + podR, 0.01f)
        val out = FloatArray(DiskGeometry.BLOB_RAYS)
        val tmp = FloatArray(DiskGeometry.BLOB_RAYS)
        val pods = FloatArray(4)
        listOf(0, 1).forEachIndexed { k, index ->
            val a = Math.toRadians(DiskGeometry.slotAngle(index, 4, 180f, 0f).toDouble())
            pods[k * 2] = (ring * kotlin.math.cos(a)).toFloat()
            pods[k * 2 + 1] = (ring * kotlin.math.sin(a)).toFloat()
        }
        assertFalse(DiskGeometry.blob(out, tmp, plate, pods, podR))
        assertEquals(plate, out[0], 0.01f)
        assertEquals(plate, out[90], 0.01f)
    }

    @Test
    fun `выдавленные кнопки тянут стекло за собой и остаются под ним`() {
        val rays = DiskGeometry.BLOB_RAYS
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val podR = DiskGeometry.podRadius(button, gap)
        val pods = tuckedPods()
        val out = FloatArray(rays)
        val tmp = FloatArray(rays)
        assertTrue(DiskGeometry.blob(out, tmp, plate, pods, podR))
        for (pod in 0..1) {
            for (i in 0 until rays) {
                // Стекло нигде не тоньше самой кнопки: кнопка не торчит из него.
                assertTrue(
                    "луч $i, кнопка $pod: стекло ${out[i]}, кнопка ${buttonReach(pods, pod, i, rays)}",
                    out[i] >= buttonReach(pods, pod, i, rays) - 0.01f,
                )
            }
            // И над кнопкой стекло ещё есть — карман, а не обрез по кромке.
            val d = kotlin.math.hypot(pods[pod * 2], pods[pod * 2 + 1])
            val at = Math.toDegrees(
                kotlin.math.atan2(pods[pod * 2 + 1], pods[pod * 2]).toDouble(),
            ).let { DiskGeometry.norm(it.toFloat()) }
            val i = (at / 360f * rays).toInt() % rays
            assertTrue(out[i] > d + button / 2f)
        }
    }

    @Test
    fun `контур нигде не тоньше тарелки и без обрывов`() {
        val rays = DiskGeometry.BLOB_RAYS
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val out = FloatArray(rays)
        val tmp = FloatArray(rays)
        DiskGeometry.blob(out, tmp, plate, tuckedPods(), DiskGeometry.podRadius(button, gap))
        var jump = 0f
        for (i in 0 until rays) {
            assertTrue(out[i] >= plate - 0.01f)
            jump = maxOf(jump, kotlin.math.abs(out[i] - out[(i + 1) % rays]))
        }
        // Сглаживание превращает обрыв в плечо: сырой максимум прыгал бы на
        // добрых два десятка точек, а приклеенный шарик читается сразу.
        assertTrue("скачок $jump", jump < 10f)
    }

    @Test
    fun `краешек между кнопками остаётся краешком`() {
        val rays = DiskGeometry.BLOB_RAYS
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val out = FloatArray(rays)
        val tmp = FloatArray(rays)
        DiskGeometry.blob(out, tmp, plate, tuckedPods(), DiskGeometry.podRadius(button, gap))
        // Луч на лице (180°) — ровно между «П» и «З».
        val face = out[rays / 2]
        assertTrue(face >= plate)
        // Растяжка сюда добирается, но краешек не съедает: он всё ещё тоньше
        // трети радиуса, и стрелке на кромке есть где стоять.
        assertTrue("лицо $face", face < plate * 1.3f)
        assertTrue(face - DiskGeometry.tuckDepth(plate) > plate * DiskGeometry.SLIVER)
    }

    @Test
    fun `окно стекла по центру кольца накрывает растяжку, а режет только спину тарелки за краем`() {
        // Окно стекла одного размера (тарелка плюс тень) стоит по центру
        // КОЛЬЦА: размер окна на ходу не меняется (владелец, 22.09.2026: «сначала
        // вырастают уши и потом дерганием он прячется»). Держится это на двух
        // вещах, при любом повороте и числе кнопок (крутить убранный диск можно):
        // растянутое стекло не выходит за радиус тарелки от центра кольца, а
        // то, что окно обрезает, — спина самой тарелки, и у убранного диска
        // она за краем экрана.
        val rays = DiskGeometry.BLOB_RAYS
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val ring = DiskGeometry.ringRadius(button, gear, gap)
        val podR = DiskGeometry.podRadius(button, gap)
        val tuck = DiskGeometry.tuckDepth(plate)
        val out = FloatArray(rays)
        val tmp = FloatArray(rays)
        for (count in 1..4) {
            val push = DiskGeometry.extrusion(plate, ring, button, count, gap)
            for (turn in 0 until 360 step 5) {
                // Лицо вправо (0°): кольцо сдвинуто на push по +x.
                val pods = FloatArray(count * 2)
                for (i in 0 until count) {
                    val a = Math.toRadians(DiskGeometry.slotAngle(i, count, 0f, turn.toFloat()).toDouble())
                    pods[i * 2] = (push + ring * kotlin.math.cos(a)).toFloat()
                    pods[i * 2 + 1] = (ring * kotlin.math.sin(a)).toFloat()
                }
                DiskGeometry.blob(out, tmp, plate, pods, podR)
                for (i in 0 until rays) {
                    val a = i * 2.0 * Math.PI / rays
                    // От центра КОЛЬЦА — там центр окна.
                    val x = (out[i] * kotlin.math.cos(a)).toFloat() - push
                    val y = (out[i] * kotlin.math.sin(a)).toFloat()
                    val at = "кнопок $count, поворот $turn, луч $i"
                    assertTrue("$at: вперёд $x", x <= plate + 0.5f)
                    assertTrue("$at: поперёк $y", kotlin.math.abs(y) <= plate + 0.5f)
                    if (x < -plate - 0.5f) {
                        // За задней стенкой окна — только сама тарелка, не растяжка…
                        assertEquals("$at: режется растяжка", plate, out[i], 0.5f)
                        // …и у убранного диска это за краем экрана (край — там,
                        // где центр тарелки плюс глубина уборки).
                        assertTrue("$at: видно обрез", x + push - tuck < 0f)
                    }
                }
            }
        }
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
