package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Диск «за шкирку» (docs/telo.md): маятник на кнопке под пальцем. Владелец
// просил «немножко раскачивать» — проверяем, что качается, что немножко и что
// успокаивается, а не болтается качелями.
class DiskSwingTest {

    private val length = 140f
    private val frame = 1f / 120f

    /** Отклонение от отвеса, градусов: 0 — висит, плюс — груз правее подвеса. */
    private fun tilt(s: DiskSwing): Float =
        Math.toDegrees(kotlin.math.atan2((s.bobX - s.pivotX).toDouble(), (s.bobY - s.pivotY).toDouble())).toFloat()

    /** Повесить с отклонением [degrees] и покоем подвеса в нуле. */
    private fun hung(degrees: Float): DiskSwing {
        val a = Math.toRadians(degrees.toDouble())
        return DiskSwing(length).also {
            it.start(0f, 0f, (length * sin(a)).toFloat(), (length * cos(a)).toFloat())
        }
    }

    /** Прогнать [seconds] кадрами по 120 Гц, подвес ведёт [target]; вернуть отклонения по кадрам. */
    private fun run(s: DiskSwing, seconds: Float, target: (Float) -> Pair<Float, Float> = { 0f to 0f }): List<Float> {
        val out = mutableListOf<Float>()
        var t = 0f
        while (t < seconds) {
            val (x, y) = target(t)
            s.step(x, y, frame)
            t += frame
            out += tilt(s)
        }
        return out
    }

    @Test
    fun `висящий отвесно висит и кнопка наверху`() {
        val s = hung(0f)
        val tilts = run(s, 1f)
        assertTrue(tilts.all { abs(it) < 0.01f })
        // Схваченная кнопка — над центром кольца: 270° по соглашению геометрии.
        assertEquals(270f, s.angle(), 0.01f)
    }

    @Test
    fun `стержень не растягивается`() {
        val s = hung(60f)
        run(s, 2f) { t -> (300f * sin(t * 9f)) to (80f * cos(t * 5f)) }
        assertEquals(length, hypot(s.bobX - s.pivotX, s.bobY - s.pivotY), 0.5f)
    }

    @Test
    fun `взяли сбоку - качнулся немножко и повис`() {
        // «П» у правого края — вверх-влево от центра: груз на 45° от отвеса.
        val tilts = run(hung(45f), 1.5f)
        val over = -tilts.minOrNull()!!
        // Проскок на другую сторону есть — это и есть «раскачивать», — но
        // небольшой: качели дали бы почти те же сорок пять.
        assertTrue("проскок $over", over > 3f && over < 15f)
        // И за секунду он гаснет.
        val late = tilts.drop((1f / frame).toInt())
        assertTrue("после секунды ${late.maxOf { abs(it) }}", late.all { abs(it) < 3f })
    }

    @Test
    fun `взяли за нижнюю кнопку - перевернулся, но не крутится колесом`() {
        val tilts = run(hung(150f), 2f)
        // Перевалил через отвес и проскочил умеренно, а не вышел на второй круг.
        val over = -tilts.minOrNull()!!
        assertTrue("проскок $over", over < 25f)
        assertTrue(tilts.all { abs(it) <= 150.5f })
        val late = tilts.drop((1.5f / frame).toInt())
        assertTrue("после полутора секунд ${late.maxOf { abs(it) }}", late.all { abs(it) < 3f })
    }

    @Test
    fun `повёл вправо - диск отстаёт влево и качается`() {
        val s = hung(0f)
        // Ведём подвес вправо на три длины за четверть секунды и стоим.
        val tilts = run(s, 2f) { t -> (length * 3f * (t / 0.25f).coerceAtMost(1f)) to 0f }
        val moving = tilts.take((0.25f / frame).toInt())
        // Пока тянут вправо, груз позади — слева от подвеса.
        assertTrue("отставание ${moving.last()}", moving.last() < -20f)
        // Остановились — диск догоняет и перелетает вперёд: раскачка.
        assertTrue(tilts.maxOrNull()!! > 3f)
        // Но не качели: к концу висит.
        assertTrue(abs(tilts.last()) < 2f)
    }

    @Test
    fun `подвес догоняет палец, а не прыгает в него`() {
        val s = hung(0f)
        s.step(100f, 0f, frame)
        assertTrue(s.pivotX > 0f && s.pivotX < 100f)
        run(s, 0.3f) { 100f to 0f }
        assertEquals(100f, s.pivotX, 0.5f)
    }

    @Test
    fun `ровно ведомый диск уходит с пальца со скоростью пальца`() {
        val s = hung(0f)
        val speed = 600f
        run(s, 1.5f) { t -> (speed * t) to 0f }
        assertEquals(speed, s.velocityX(), speed * 0.05f)
        assertEquals(0f, s.velocityY(), speed * 0.05f)
        // Висит почти отвесно — не крутится.
        assertEquals(0f, s.spin(), 20f)
    }

    @Test
    fun `мах назван угловой скоростью со знаком`() {
        // Отпустили с 45° вправо: груз идёт к отвесу, то есть влево и вниз, и
        // кнопка от груза поворачивается по часовой (с 225° к 270°): угол
        // растёт, скорость со знаком плюс.
        val s = hung(45f)
        run(s, 0.1f)
        val before = s.angle()
        s.step(0f, 0f, frame)
        val after = s.angle()
        val observed = DiskGeometry.delta(before, after) / frame
        assertTrue("мах $observed", observed > 0f)
        assertEquals(observed, s.spin(), abs(observed) * 0.2f + 1f)
    }

    @Test
    fun `маятник одинаков при любом размере кнопок`() {
        val small = DiskSwing(100f).also { it.start(0f, 0f, 70.71f, 70.71f) }
        val big = DiskSwing(200f).also { it.start(0f, 0f, 141.42f, 141.42f) }
        repeat(90) {
            small.step(0f, 0f, frame)
            big.step(0f, 0f, frame)
            assertEquals(tilt(small), tilt(big), 0.5f)
        }
    }

    @Test
    fun `пропущенный кадр не раскачивает`() {
        val s = hung(30f)
        // Кадр в полсекунды считается как 50 мс: догонять время незачем.
        s.step(0f, 0f, 0.5f)
        assertTrue(abs(tilt(s)) < 30f)
        assertEquals(length, hypot(s.bobX - s.pivotX, s.bobY - s.pivotY), 0.5f)
    }
}
