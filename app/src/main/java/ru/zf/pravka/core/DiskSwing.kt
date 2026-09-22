package ru.zf.pravka.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Диск «за шкирку». Владелец (22.09.2026): «беру за какую-то иконку и тащу, а
 * сам диск как будто виснет на ней. Ну то есть тогда эта иконка наверху, и я
 * этой иконкой, если я двигаю вправо-влево, могу так немножко даже раскачивать
 * диск».
 *
 * Это маятник: подвес — кнопка под пальцем, груз — центр кольца кнопок,
 * стержень — радиус кольца. Диск целиком поворачивается вместе со стержнем,
 * так что схваченная кнопка всегда смотрит на подвес, а в покое — вверх.
 *
 * Считается ТОЧКОЙ, а не углом: груз падает под тяжестью, тормозится воздухом,
 * а после каждого шага возвращается на длину стержня от подвеса (Verlet со
 * связью). Угловая формула требовала бы ускорения подвеса, то есть второй
 * производной пальца, — а касания приходят рывками, и раскачка дрожала бы
 * вместе с ними. Здесь палец только ведёт подвес, а скорость груза рождается
 * сама из того, КАК его потащило: рывок вбок — диск отстаёт и качается,
 * ровное движение — висит отвесно.
 *
 * Всё безразмерное (период, сопротивление) считается от длины стержня, поэтому
 * маятник ведёт себя одинаково при любом размере кнопок. Без Android:
 * JVM-тесты гоняют ту же арифметику, что и `trigger/DiskController.kt`.
 * Единицы — пиксели и секунды.
 */
class DiskSwing(val length: Float) {

    var pivotX = 0f
        private set
    var pivotY = 0f
        private set
    var bobX = 0f
        private set
    var bobY = 0f
        private set

    // Прошлое положение груза и подвеса — из них Verlet берёт скорость.
    private var prevX = 0f
    private var prevY = 0f
    private var prevPivotX = 0f
    private var prevPivotY = 0f
    private var lastH = SUBSTEP

    /**
     * Тяжесть подобрана под период: маятник длины L качается с периодом
     * 2π·√(L/g), отсюда g = L·(2π/T)². Настоящие 9,8 м/с² тут ни при чём —
     * диск в пять сантиметров качался бы с частотой дрожи.
     */
    private val gravity = length * (2f * PI.toFloat() / PERIOD).let { it * it }

    /**
     * Повесить: подвес в ([pivotX], [pivotY]), груз в ([bobX], [bobY]) — там,
     * где диск стоял в миг отрыва, и неподвижно. Длина стержня берётся из
     * конструктора, а не из этих точек: у выдавленного диска кольцо сдвинуто,
     * но кнопка от центра КОЛЬЦА всегда на его радиусе.
     */
    fun start(pivotX: Float, pivotY: Float, bobX: Float, bobY: Float) {
        this.pivotX = pivotX
        this.pivotY = pivotY
        prevPivotX = pivotX
        prevPivotY = pivotY
        this.bobX = bobX
        this.bobY = bobY
        prevX = bobX
        prevY = bobY
        lastH = SUBSTEP
        constrain()
    }

    /**
     * Шаг за [dtSec]: подвес догоняет палец ([targetX], [targetY]), груз падает,
     * тормозится и возвращается на стержень. Подшаги мелкие и одинаковые по
     * смыслу: у Verlet скорость — это сдвиг за прошлый подшаг, и подшаг другой
     * длины пересчитывает её, а не ломает.
     */
    fun step(targetX: Float, targetY: Float, dtSec: Float) {
        var remaining = dtSec.coerceIn(0f, MAX_DT)
        while (remaining > 1e-6f) {
            val h = min(remaining, SUBSTEP)
            remaining -= h
            prevPivotX = pivotX
            prevPivotY = pivotY
            // Подвес не прыгает в палец, а догоняет его: касания приходят
            // рывками, и груз, дёрнутый каждым событием, дрожал бы.
            val k = 1f - exp(-h / PIVOT_LAG)
            pivotX += (targetX - pivotX) * k
            pivotY += (targetY - pivotY) * k
            val scale = h / lastH
            var vx = (bobX - prevX) * scale
            var vy = (bobY - prevY) * scale
            // Воздух: ровное торможение плюс быстрое — второе гасит большие
            // махи (схватили за нижнюю кнопку, диск переворачивается), а
            // мелкую раскачку от пальца оставляет живой. Владелец просил
            // «немножко раскачивать», а не качели.
            val speed = hypot(vx, vy) / h
            val drag = exp(-(DRAG + DRAG_FAST * speed / length) * h)
            vx *= drag
            vy *= drag
            prevX = bobX
            prevY = bobY
            bobX += vx
            bobY += vy + gravity * h * h
            constrain()
            lastH = h
        }
    }

    /** Вернуть груз на длину стержня от подвеса: стержень жёсткий в обе стороны. */
    private fun constrain() {
        val dx = bobX - pivotX
        val dy = bobY - pivotY
        val d = hypot(dx, dy)
        if (d < 1e-3f) {
            // Груз в самом подвесе — направления нет; висим отвесно.
            bobX = pivotX
            bobY = pivotY + length
            return
        }
        bobX = pivotX + dx * length / d
        bobY = pivotY + dy * length / d
    }

    /**
     * Угол схваченной кнопки на кольце — от груза к подвесу, в [0, 360), по
     * соглашению `DiskGeometry` (0° вправо, ось y вниз). Висит отвесно — 270°:
     * кнопка наверху.
     */
    fun angle(): Float = DiskGeometry.angleOf(bobX, bobY, pivotX, pivotY)

    /** Скорость груза, пикселей в секунду: с ней диск уходит с пальца на бросок. */
    fun velocityX(): Float = (bobX - prevX) / lastH
    fun velocityY(): Float = (bobY - prevY) / lastH

    /**
     * Как быстро поворачивается диск, градусов в секунду (по часовой — плюс):
     * угловая скорость стержня. С ней пружина поворота подхватывает мах, а не
     * гасит его на отпускании.
     */
    fun spin(): Float {
        val rx = pivotX - bobX
        val ry = pivotY - bobY
        val wx = (pivotX - prevPivotX) / lastH - velocityX()
        val wy = (pivotY - prevPivotY) / lastH - velocityY()
        val r2 = rx * rx + ry * ry
        if (r2 < 1e-3f) return 0f
        return Math.toDegrees(((rx * wy - ry * wx) / r2).toDouble()).toFloat()
    }

    companion object {
        /**
         * Период малых качаний, секунд. Меньше — диск дрожит, как брелок;
         * больше — плывёт, как под водой, и отстаёт от пальца.
         */
        const val PERIOD = 0.85f
        /** Ровное торможение, в секунду: мах отклонения на сорок пять градусов гаснет за полсекунды с небольшим. */
        const val DRAG = 6f
        /**
         * Быстрое торможение — доля скорости груза в длинах стержня за
         * секунду. Не даёт маятнику перелететь через подвес при рывке.
         */
        const val DRAG_FAST = 0.32f
        /** За сколько секунд подвес догоняет палец (постоянная экспоненты). */
        const val PIVOT_LAG = 0.03f
        /** Подшаг интегрирования: на 240 Гц связь держится без дрожи. */
        const val SUBSTEP = 1f / 240f
        /** Кадр длиннее — считаем как 50 мс: догонять пропущенное время незачем. */
        const val MAX_DT = 1f / 20f
    }
}
