package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import kotlin.math.abs

/**
 * Бегущая строка тикера диктовки. Владелец (15.09.2026): «плашка на одну
 * строчку, текст едет справа налево, как бегущая строка, уплывает влево с
 * фейдом; остановился — потихоньку притормаживает и останавливается; говорю
 * быстрее — едет быстрее».
 *
 * Механика без таймеров и без анимаций «по событию»: у строки есть цель —
 * показать хвост текста у правого края — и текущий сдвиг. Каждый кадр сдвиг
 * подтягивается к цели со скоростью, пропорциональной отставанию: приехало
 * много новых слов — отставание большое, строка едет быстро; слова кончились —
 * отставание тает, скорость падает до минимума и строка мягко встаёт. Слева —
 * градиент цвета плашки поверх текста: уплывшие слова гаснут, а не рубятся.
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
        /** Доля отставания, съедаемая за секунду: 3.5 → за секунду проезжает ~97 % пути. */
        const val GAIN_PER_SEC = 3.5f
    }

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = textSizeSp * resources.displayMetrics.scaledDensity
    }
    private val padH = 16f * density
    private val fadeW = 44f * density
    private val minSpeed = 28f * density      // px/s — ниже этого просто доезжаем
    private val maxSpeed = 1400f * density    // px/s — потолок, чтобы не мельтешило
    private var fade: Paint? = null

    private var full = ""        // весь текст, как пришёл
    private var dropped = 0      // сколько знаков головы отрезано от full
    private var shown = ""       // full.substring(dropped) — что рисуем
    private var shownWidth = 0f
    private var offset = 0f      // насколько текст уехал влево, px
    private var lastFrameNs = 0L
    private var running = false

    fun reset() {
        full = ""; dropped = 0; shown = ""; shownWidth = 0f; offset = 0f
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
            if (newDropped > dropped) {
                offset = (offset - paint.measureText(text, dropped, newDropped)).coerceAtLeast(0f)
            }
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

    private fun target(): Float = (shownWidth - (width - 2 * padH)).coerceAtLeast(0f)

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
            if (abs(gap) < 0.5f) {
                offset = goal
                running = false
                lastFrameNs = 0L
                invalidate()
                return
            }
            // Скорость ~ отставанию, в коридоре [min, max]; знак — куда ехать.
            val raw = gap * GAIN_PER_SEC
            val speed = if (raw > 0) raw.coerceIn(minSpeed, maxSpeed) else raw.coerceIn(-maxSpeed, -minSpeed)
            offset += speed * dt
            if ((speed > 0 && offset > goal) || (speed < 0 && offset < goal)) offset = goal
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, padH + fadeW, 0f,
                plateColor, plateColor and 0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
        }
        ensureRunning()
    }

    override fun onDraw(canvas: Canvas) {
        if (shown.isEmpty()) return
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(shown, padH - offset, baseline, paint)
        // Уплывшее слева гаснет в цвет плашки — фейд только когда есть что гасить.
        if (offset > 0.5f) {
            fade?.let { canvas.drawRect(0f, 0f, padH + fadeW, height.toFloat(), it) }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        super.onDetachedFromWindow()
    }
}
