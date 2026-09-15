package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import kotlin.math.abs

/**
 * Бегущая строка тикера диктовки. Владелец (15.09.2026): «плашка на одну
 * строчку, текст едет справа налево, как бегущая строка, уплывает влево с
 * фейдом; остановился — потихоньку притормаживает и останавливается; говорю
 * быстрее — едет быстрее». И после первой сборки: «текст должен выползать с
 * правого края, а не появляться слева; скорость рваная — разгон и торможение
 * помягче; фейд квадратный — сделать овальным и справа тоже».
 *
 * Механика. Якорь — ПРАВЫЙ край: конец текста всегда стремится к правому
 * краю (для короткого текста сдвиг отрицательный — строка прижата вправо, а в
 * самом начале уезжает за край целиком, и первые слова въезжают справа). Каждый
 * кадр скорость сглаживается к целевой (целевая пропорциональна отставанию), и
 * уже скорость двигает сдвиг: два уровня инерции вместо одного — вот откуда
 * мягкие разгон и торможение без рывков на каждом новом слове. Фейды слева и
 * справа — градиенты цвета плашки, рисуются внутри овального клипа, чтобы
 * повторять форму пилюли, а не её прямоугольник.
 *
 * Текст держится хвостом (MAX_CHARS): при обрезке головы сдвиг уменьшается на
 * её ширину, и картинка не прыгает. Всё рисование — один drawText на кадр.
 */
class MarqueeTickerView(
    context: Context,
    private val plateColor: Int,
    textColor: Int,
    textSizeSp: Float,
) : View(context) {

    private companion object {
        const val MAX_CHARS = 700
        /** Целевая скорость = отставание × GAIN: за секунду проходится ~86 % пути. */
        const val GAIN_PER_SEC = 2.0f
        /** Постоянная времени сглаживания скорости, с: разгон и торможение без рывков. */
        const val VELOCITY_TAU = 0.45f
    }

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = textSizeSp * resources.displayMetrics.scaledDensity
    }
    private val padL = 12f * density
    private val fadeL = 48f * density
    private val fadeR = 30f * density
    private val padR = fadeR + 10f * density   // конец текста стоит ДО правого фейда
    private val maxSpeed = 1100f * density     // px/s — потолок, чтобы не мельтешило
    private var fadeLeft: Paint? = null
    private var fadeRight: Paint? = null
    private val clip = Path()

    private var full = ""        // весь текст, как пришёл
    private var dropped = 0      // сколько знаков головы отрезано от full
    private var shown = ""       // full.substring(dropped) — что рисуем
    private var shownWidth = 0f
    private var offset = 0f      // текст начинается в x = padL − offset
    private var offsetReady = false
    private var velocity = 0f    // px/s, знак — направление
    private var lastFrameNs = 0L
    private var running = false

    private fun visibleWidth(): Float = (width - padL - padR).coerceAtLeast(1f)

    /** Куда стремится сдвиг: конец текста у правого края; короткий текст — прижат вправо. */
    private fun target(): Float = shownWidth - visibleWidth()

    fun reset() {
        full = ""; dropped = 0; shown = ""; shownWidth = 0f
        velocity = 0f
        // Пустая строка «стоит» за правым краем: первые слова въедут оттуда.
        offset = -visibleWidth()
        offsetReady = width > 0
        running = false; lastFrameNs = 0L
        removeCallbacks(frame)
        invalidate()
    }

    fun setTickerText(text: String) {
        if (text == full) return
        val append = full.isNotEmpty() && text.startsWith(full)
        val newDropped = (text.length - MAX_CHARS).coerceAtLeast(0)
        if (append) {
            // Голову отрезали ещё немного — сдвигаем на её ширину, чтобы буквы
            // остались на своих местах на экране.
            if (newDropped > dropped) offset -= paint.measureText(text, dropped, newDropped)
            dropped = maxOf(dropped, newDropped)
        } else {
            // Распознаватель переписал гипотезу (не дописал, а поправил) —
            // начинаем с честной головы, сдвиг оставляем, цель его подправит.
            dropped = newDropped
        }
        full = text
        shown = full.substring(dropped.coerceAtMost(full.length))
        shownWidth = paint.measureText(shown)
        ensureRunning()
        invalidate()
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameNs = 0L
        postOnAnimation(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) { running = false; return }
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrameNs = now
            val goal = target()
            val gap = goal - offset
            // Целевая скорость ~ отставанию; настоящая скорость догоняет её с
            // постоянной времени VELOCITY_TAU — второй уровень инерции.
            val vTarget = (gap * GAIN_PER_SEC).coerceIn(-maxSpeed, maxSpeed)
            velocity += (vTarget - velocity) * (dt / VELOCITY_TAU).coerceAtMost(1f)
            offset += velocity * dt
            // Не проскакиваем цель: доехали — стоим.
            if ((velocity > 0 && offset > goal) || (velocity < 0 && offset < goal)) {
                offset = goal
                velocity = 0f
            }
            if (abs(goal - offset) < 0.5f && abs(velocity) < 2f) {
                offset = goal
                velocity = 0f
                running = false
                lastFrameNs = 0L
                invalidate()
                return
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val transparent = plateColor and 0x00FFFFFF
        fadeLeft = Paint().apply {
            shader = LinearGradient(0f, 0f, padL + fadeL, 0f, plateColor, transparent, Shader.TileMode.CLAMP)
        }
        fadeRight = Paint().apply {
            shader = LinearGradient(w - fadeR, 0f, w.toFloat(), 0f, transparent, plateColor, Shader.TileMode.CLAMP)
        }
        clip.reset()
        val r = h / 2f
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        if (!offsetReady) {
            offset = -visibleWidth()
            offsetReady = true
        }
        ensureRunning()
    }

    override fun onDraw(canvas: Canvas) {
        if (shown.isEmpty()) return
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.save()
        // Всё — внутри овала плашки: и текст, и фейды повторяют её форму.
        canvas.clipPath(clip)
        canvas.drawText(shown, padL - offset, baseline, paint)
        // Слева гаснет то, что уплыло за край, — только когда оно есть.
        if (offset > 0.5f) {
            fadeLeft?.let { canvas.drawRect(0f, 0f, padL + fadeL, height.toFloat(), it) }
        }
        // Справа — ворота, через которые въезжают новые слова.
        fadeRight?.let { canvas.drawRect(width - fadeR, 0f, width.toFloat(), height.toFloat(), it) }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        super.onDetachedFromWindow()
    }
}
