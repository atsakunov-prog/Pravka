package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
 * справа — МАСКА: строка рисуется в свой слой, и края этого слоя стираются
 * градиентом (DST_OUT). Прежние фейды красили края цветом плашки и держались,
 * пока плашка была сплошной; пилюля диктовки (26.09.2026) — стекло с
 * градиентом и свечением, и полоса одного цвета легла бы на неё заплаткой.
 * Маска гасит сами буквы, под ними остаётся то стекло, какое есть.
 *
 * Текст держится хвостом (MAX_CHARS): при обрезке головы сдвиг уменьшается на
 * её ширину, и картинка не прыгает. Всё рисование — один drawText на кадр.
 *
 * Подсказка ([setHint]) — не бегущий текст, а надпись посередине, как «Ask
 * Gemini» (владелец, 26.09.2026: «надо ставить его посередине, а не
 * выезжающим»). Стоит, пока слов нет, чуть тусклее слов; первое слово её
 * гасит за [HINT_FADE_S], а само въезжает справа, как всегда.
 */
class MarqueeTickerView(
    context: Context,
    textColor: Int,
    textSizeSp: Float,
) : View(context) {

    private companion object {
        const val MAX_CHARS = 700
        /** Целевая скорость = отставание × GAIN: за секунду проходится ~86 % пути. */
        const val GAIN_PER_SEC = 2.0f
        /** Постоянная времени сглаживания скорости, с: разгон и торможение без рывков. */
        const val VELOCITY_TAU = 0.45f
        /** За сколько гаснет подсказка, когда пришло первое слово, с. */
        const val HINT_FADE_S = 0.16f
        /** Подсказка тусклее слов: это приглашение, а не сказанное. */
        const val HINT_ALPHA = 0.72f
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

    init {
        // Свой слой — чтобы маска стирала только строку, а не стекло под ней.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private var full = ""        // весь текст, как пришёл
    private var dropped = 0      // сколько знаков головы отрезано от full
    private var shown = ""       // full.substring(dropped) — что рисуем
    private var shownWidth = 0f
    private var offset = 0f      // текст начинается в x = padL − offset
    private var offsetReady = false
    private var velocity = 0f    // px/s, знак — направление
    private var lastFrameNs = 0L
    private var running = false

    private val hintPaint = TextPaint(paint)
    private var hint = ""
    private var hintWidth = 0f
    /** Видимость подсказки 0..1 и куда она идёт: слов нет — к единице, есть — к нулю. */
    private var hintAlpha = 0f
    private var hintTarget = 0f

    private fun visibleWidth(): Float = (width - padL - padR).coerceAtLeast(1f)

    /** Куда стремится сдвиг: конец текста у правого края; короткий текст — прижат вправо. */
    private fun target(): Float = shownWidth - visibleWidth()

    fun reset() {
        full = ""; dropped = 0; shown = ""; shownWidth = 0f
        hint = ""; hintWidth = 0f; hintAlpha = 0f; hintTarget = 0f
        velocity = 0f
        // Пустая строка «стоит» за правым краем: первые слова въедут оттуда.
        offset = -visibleWidth()
        offsetReady = width > 0
        running = false; lastFrameNs = 0L
        removeCallbacks(frame)
        invalidate()
    }

    /**
     * Надпись посередине, пока слов нет. Смена подсказки («секунду…» →
     * «слушаю») — сразу, без мигания: это одна надпись, у неё поменялось
     * слово. Пришли слова — подсказка не нужна, новую не показываем.
     */
    fun setHint(text: String) {
        if (text == hint) return
        hint = text
        hintWidth = hintPaint.measureText(text)
        if (full.isEmpty()) {
            hintTarget = if (text.isEmpty()) 0f else 1f
            // Первая подсказка показа — сразу: пилюля и так проявляется целиком.
            if (hintAlpha == 0f) hintAlpha = hintTarget
        }
        ensureRunning()
        invalidate()
    }

    fun setTickerText(text: String) {
        if (text == full) return
        // Пошли слова — подсказка уступает место, гаснет в кадрах.
        hintTarget = if (text.isEmpty() && hint.isNotEmpty()) 1f else 0f
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
            if (hintAlpha != hintTarget) {
                val step = dt / HINT_FADE_S
                hintAlpha = if (hintAlpha < hintTarget) minOf(hintTarget, hintAlpha + step)
                else maxOf(hintTarget, hintAlpha - step)
            }
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
            if (abs(goal - offset) < 0.5f && abs(velocity) < 2f && hintAlpha == hintTarget) {
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
        // Маска: непрозрачное стирает букву целиком, прозрачное не трогает.
        val erase = 0xFF000000.toInt()
        val keep = 0x00000000
        fadeLeft = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            shader = LinearGradient(0f, 0f, padL + fadeL, 0f, erase, keep, Shader.TileMode.CLAMP)
        }
        fadeRight = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            shader = LinearGradient(w - fadeR, 0f, w.toFloat(), 0f, keep, erase, Shader.TileMode.CLAMP)
        }
        if (!offsetReady) {
            offset = -visibleWidth()
            offsetReady = true
        }
        ensureRunning()
    }

    override fun onDraw(canvas: Canvas) {
        if (shown.isEmpty() && hintAlpha <= 0f) return
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        if (hintAlpha > 0f && hint.isNotEmpty()) {
            // Посередине; длиннее места — от левого края, хвост гаснет в правом фейде.
            val x = ((width - hintWidth) / 2f).coerceAtLeast(padL)
            hintPaint.alpha = (255 * HINT_ALPHA * hintAlpha).toInt()
            canvas.drawText(hint, x, baseline, hintPaint)
        }
        // Форму держит пилюля (её контур режет детей), здесь — только строка и маска.
        if (shown.isNotEmpty()) canvas.drawText(shown, padL - offset, baseline, paint)
        // Слева гаснет то, что уплыло за край, — только когда оно есть.
        if (offset > 0.5f) {
            fadeLeft?.let { canvas.drawRect(0f, 0f, padL + fadeL, height.toFloat(), it) }
        }
        // Справа — ворота, через которые въезжают новые слова.
        fadeRight?.let { canvas.drawRect(width - fadeR, 0f, width.toFloat(), height.toFloat(), it) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        super.onDetachedFromWindow()
    }
}
