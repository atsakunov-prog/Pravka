package ru.zf.pravka.trigger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * Серая ручка от ящика: маленький кружок, из которого выпадают кнопки.
 *
 * Их две, и делают они разное:
 *   СВЕРХУ, над «П», с многоточием — убирает и возвращает ВСЕ четыре кнопки.
 *   СНИЗУ, под хвостом, с галочкой — убирает и возвращает «Д» и «Е».
 *
 * Оба глифа РИСУЮТСЯ, а не пишутся текстом, и это не педантизм. Первая
 * версия ставила в TextView символ «⌄»: у него своя высота в шрифте, он сидит
 * в кружке заметно выше центра, и владелец сказал прямо — «какая-то галочка
 * не посередине, выглядит очень некрасиво». Нарисованный глиф центрируется
 * по построению, в любом шрифте и при любом размере.
 *
 * Серый, а не янтарный: ручка — не пятая кнопка. Кнопки заявляют о себе
 * цветом, ручка обязана молчать.
 */
class StackHandleController(
    private val service: PravkaAccessibilityService,
    private val dots: Boolean,
) {

    private companion object {
        /** Приглушённый «ink-soft» из палитры: рядом с янтарём он не спорит. */
        private val GREY = 0xFF6E6659.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
        private const val ALPHA = 0.72f
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private var glyph: HandleGlyph? = null
    private var params: WindowManager.LayoutParams? = null

    val sizePx: Int = dp(22)

    var onTap: (() -> Unit)? = null

    /**
     * Ручку можно таскать, как кнопку. Владелец: «надо сделать так, чтобы это
     * многоточие можно было двигать точно так же, как правку». По-другому и
     * нельзя: когда все кнопки убраны, ручка — единственное, что на экране, и
     * если она приросла к месту, то место уже не поменять.
     */
    var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    fun currentPosition(): Pair<Int, Int>? = params?.let { it.x to it.y }

    @SuppressLint("ClickableViewAccessibility")
    fun show(collapsed: Boolean) {
        if (glyph == null) {
            val v = HandleGlyph(service, dots).apply {
                alpha = ALPHA
                elevation = dp(3).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GREY)
                }
            }
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            v.setOnTouchListener { _, event ->
                val p = params ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = p.x
                        startY = p.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                        if (dragging) {
                            val bounds = runCatching { windowManager.currentWindowMetrics.bounds }
                                .getOrNull()
                            val w = bounds?.width() ?: 0
                            val h = bounds?.height() ?: 0
                            p.x = (startX + dx.toInt()).coerceIn(0, (w - sizePx).coerceAtLeast(0))
                            p.y = (startY + dy.toInt()).coerceIn(0, (h - sizePx).coerceAtLeast(0))
                            runCatching { windowManager.updateViewLayout(v, p) }
                            onDragged?.invoke(p.x, p.y, false)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            onDragged?.invoke(p.x, p.y, true)
                        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                            onTap?.invoke()
                        }
                    }
                }
                true
            }
            val p = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            params = p
            glyph = v
            runCatching { windowManager.addView(v, p) }
        }
        glyph?.visibility = View.VISIBLE
        setCollapsed(collapsed)
    }

    /** Сложено — галочка вниз («выпадут»); разложено — вверх («уберутся»). */
    fun setCollapsed(collapsed: Boolean) {
        val v = glyph ?: return
        if (v.pointUp == !collapsed) return
        v.pointUp = !collapsed
        v.invalidate()
    }

    /**
     * Перепись окон для журнала складывания: каждое наше оверлейное окно
     * складывание Fold пересчитывает и ждёт, и ручки тут не исключение.
     */
    fun windowCount(): Int = if (glyph != null) 1 else 0

    /** Именно removeView, а не GONE: скрытое окно стоит столько же, сколько видимое. */
    fun hide() {
        glyph?.let { runCatching { windowManager.removeView(it) } }
        glyph = null
        params = null
    }

    /** Ставится по центру кнопки: [above] — над ней, иначе под ней. */
    fun moveTo(buttonX: Int, buttonY: Int, buttonSize: Int, above: Boolean) {
        val p = params ?: return
        val v = glyph ?: return
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val w = bounds?.width() ?: 0
        val h = bounds?.height() ?: 0
        p.x = (buttonX + (buttonSize - sizePx) / 2)
            .coerceIn(0, (w - sizePx).coerceAtLeast(0))
        p.y = (if (above) buttonY - sizePx - dp(5) else buttonY + buttonSize + dp(5))
            .coerceIn(0, (h - sizePx).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /**
     * Глиф ручки. Рисуется от центра вида, поэтому сидит ровно посередине
     * кружка — в отличие от символа в шрифте, у которого свои поля.
     */
    private class HandleGlyph(context: Context, private val dots: Boolean) : View(context) {

        /** Только для галочки: вверх = «уберутся», вниз = «выпадут». */
        var pointUp = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            if (dots) {
                paint.style = Paint.Style.FILL
                val r = w * 0.075f
                val gap = w * 0.215f
                canvas.drawCircle(cx - gap, cy, r, paint)
                canvas.drawCircle(cx, cy, r, paint)
                canvas.drawCircle(cx + gap, cy, r, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.095f
                val dx = w * 0.19f
                val dy = h * 0.095f
                val tip = if (pointUp) cy - dy else cy + dy
                val side = if (pointUp) cy + dy else cy - dy
                canvas.drawLine(cx - dx, side, cx, tip, paint)
                canvas.drawLine(cx, tip, cx + dx, side, paint)
            }
        }
    }
}
