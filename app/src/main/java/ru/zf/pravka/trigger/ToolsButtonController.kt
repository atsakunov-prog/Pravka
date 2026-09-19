package ru.zf.pravka.trigger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.data.Settings

/**
 * Серая кнопка «инструменты»: четвёртая в связке и на кольце, на месте
 * бывшей зелёной «Е». Тап — веер быстрых настроек шестерёнки (модель чистки ·
 * микрофон · обновления · спрятать всё), долгое нажатие — экран настроек
 * приложения. Владелец (19.09.2026, вечер): «уберём кружок спорт и поставим
 * вместо него кружок настройки — инструменты перекрещенные; при этом
 * шестерёнку крутящуюся надо оставить, она классно выглядит».
 *
 * Серая, а не цветная: не режим, а служба — кнопки режимов заявляют о себе
 * цветом, служебные молчат (та же логика, что у шестерёнки и ручки). Глиф —
 * молоток и гаечный ключ крестом — рисуется от центра вида, не текст и не
 * готовая иконка: урок галочки ручки («какая-то галочка не посередине»).
 *
 * Одно окно, ровно с кнопку, TYPE_ACCESSIBILITY_OVERLAY. Тикера, плашек и
 * «отмены» у неё нет — записывать ей нечего, поэтому контроллер в разы короче
 * четырёх режимных. В стопке едет в связке, как все (`ChainFollower`), на
 * диске стоит на кольце и крутит его (`RingButton`). Окно снимается из
 * WindowManager, когда кнопка убрана или за краем: правило складывания Fold.
 */
class ToolsButtonController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onShortTap: () -> Unit,
    private val onLongPress: () -> Unit,
) : RingButton {

    private companion object {
        private const val LONG_PRESS_MS = 450L
        /** Приглушённый «ink-soft», как у шестерёнки и ручки: служебная кнопка молчит цветом. */
        private val GREY = 0xFF6E6659.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private fun dp(value: Int): Int = (value * density).toInt()

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    private var button: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    /** Убрана в ручку или в точку «всё убрано». */
    private var stashed = false
    /** Окно реально висит в WindowManager. */
    private var attached = false
    /** Идёт складывание: окно снято на время перехода. */
    private var folded = false
    /** Диск: окно целиком за краем экрана — снято (см. `DiskController`). */
    private var offscreen = false
    private var enabled = true

    override var ringMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val p = params ?: return
            val noLimits = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            p.flags = if (value) p.flags or noLimits else p.flags and noLimits.inv()
            if (attached) button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }

    override var onRingDrag: ((Float, Float, Float, Float, Int) -> Unit)? = null
    override var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null
    override var onFrame: (() -> Unit)? = null

    // Размер экрана — binder-вызов, кэшируется до смены конфигурации (как у «П»).
    private var cachedScreen: Pair<Int, Int>? = null

    private fun screenSize(): Pair<Int, Int> = cachedScreen ?: run {
        val bounds = windowManager.currentWindowMetrics.bounds
        (bounds.width() to bounds.height()).also { cachedScreen = it }
    }

    private fun positionKey(): String {
        val (w, h) = screenSize()
        return "${w}x$h"
    }

    fun show() {
        if (button == null) create()
        applyStash()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value && button == null) create()
        applyStash()
    }

    override fun setStacked(value: Boolean) {
        stashed = value
        val v = button ?: return
        if (value) {
            v.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(140).start()
            v.postDelayed({ if (stashed) applyStash() }, 150)
        } else {
            // Без анимации проявления: только что прикреплённое окно ещё не
            // прошло компоновку, и аниматор на нём не стартует (см. «П»).
            v.animate().cancel()
            applyStash()
        }
    }

    override fun setFolded(value: Boolean) {
        folded = value
        applyStash()
    }

    override fun setOffscreen(value: Boolean) {
        if (offscreen == value) return
        offscreen = value
        applyStash()
    }

    /**
     * ЕДИНСТВЕННОЕ место, которое решает, висит ли окно. Спрятанная кнопка
     * уходит из WindowManager, а не остаётся невидимым окном: складывание
     * Fold платит за каждое окно (правило проекта).
     */
    private fun applyStash() {
        val v = button ?: return
        val p = params ?: return
        val want = !stashed && !folded && enabled && !offscreen
        if (want && (!attached || v.visibility != View.VISIBLE || v.alpha <= 0.02f)) {
            v.visibility = View.VISIBLE
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = idleAlpha
        }
        if (want == attached) return
        attached = want
        if (want) {
            runCatching { windowManager.addView(v, p) }
        } else {
            runCatching { windowManager.removeView(v) }
        }
    }

    override fun reattach() {
        val v = button ?: return
        val p = params ?: return
        if (!attached) return
        runCatching { windowManager.removeView(v) }
        runCatching { windowManager.addView(v, p) }
    }

    override fun currentPosition(): Pair<Int, Int>? = params?.let { it.x to it.y }

    override fun buttonSizePx(): Int = buttonSize

    // Бусы: та же пружина, что у режимных кнопок (`ChainFollower`).
    private val follower = ChainFollower(
        apply = frame@{ x, y ->
            val p = params ?: return@frame
            val view = button ?: return@frame
            p.x = x
            p.y = y
            runCatching { windowManager.updateViewLayout(view, p) }
            onFrame?.invoke()
        },
        onSettled = settled@{ settle ->
            val p = params ?: return@settled
            val view = button ?: return@settled
            if (settle) savePosition(view, p)
        },
    )

    override fun followTo(x: Int, y: Int, settle: Boolean, link: Int, snap: Boolean) {
        if (!enabled) return
        val view = button ?: return
        val p = params ?: return
        val (w, h) = screenSize()
        val targetX = if (ringMode) x else x.coerceIn(0, (w - buttonSize).coerceAtLeast(0))
        val targetY = if (ringMode) y else y.coerceIn(0, (h - buttonSize).coerceAtLeast(0))
        // Снятое окно догонять нечем — встаёт сразу; со snap — и видимое тоже
        // (диск ведёт анимацию сам). См. те же строки у «П».
        if (!attached || snap) {
            follower.stop()
            p.x = targetX
            p.y = targetY
            runCatching { windowManager.updateViewLayout(view, p) }
            if (settle) savePosition(view, p)
            return
        }
        follower.follow(p.x, p.y, targetX, targetY, link, settle)
    }

    override fun onConfigurationChanged() {
        cachedScreen = null
        val p = params ?: return
        // На диске место кнопки — дело диска.
        if (ringMode) return
        scope.launch {
            val (xFraction, yFraction) = settings.toolsFabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
    }

    override fun windowCount(): Int = if (attached) 1 else 0

    override fun destroy() {
        follower.stop()
        button?.let { runCatching { windowManager.removeView(it) } }
        attached = false
        button = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create() {
        val container = FrameLayout(service)
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(GREY)
        }
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha
        container.addView(
            ToolsGlyph(service),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val p = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (ringMode) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val (w, h) = screenSize()
            x = w - buttonSize
            y = (h * 0.88f).toInt()
        }
        params = p
        button = container
        // Окно вешает applyStash — здесь только собрать.
        attached = false

        scope.launch {
            val (xFraction, yFraction) = settings.toolsFabPosition(positionKey())
            if (!ringMode) {
                applyPosition(p, xFraction, yFraction)
                if (attached) runCatching { windowManager.updateViewLayout(container, p) }
            }
        }
        scope.launch {
            settings.fabSizeFlow.collect { sizeDp ->
                buttonSize = dp(sizeDp)
                p.width = buttonSize
                p.height = buttonSize
                if (attached) runCatching { windowManager.updateViewLayout(container, p) }
            }
        }
        scope.launch {
            settings.fabAlphaFlow.collect { alpha ->
                idleAlpha = alpha
                container.alpha = idleAlpha
            }
        }

        container.setOnTouchListener(DragTouchListener())
    }

    private fun applyPosition(p: WindowManager.LayoutParams, xFraction: Float, yFraction: Float) {
        val (w, h) = screenSize()
        p.x = ((w - buttonSize) * xFraction.coerceIn(0f, 1f)).toInt()
        p.y = ((h - buttonSize) * yFraction.coerceIn(0f, 1f)).toInt()
    }

    /** Те же жесты, что у режимных кнопок: тап, долгое нажатие, перетаскивание (связка или диск). */
    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressFired = false
        private var pressed: View? = null
        private val longPressRunnable = Runnable {
            longPressFired = true
            pressed?.let { BubbleMotion.nod(it) }
            onLongPress()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            // Карманный страж, как у всех кнопок: на локскрине в покое жест глотается.
            if (service.isLockedIdle()) return true
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longPressFired = false
                    pressed = view
                    view.alpha = 1f
                    BubbleMotion.press(view)
                    view.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    if (ringMode) onRingDrag?.invoke(event.rawX, event.rawY, event.x, event.y, MotionEvent.ACTION_DOWN)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        view.removeCallbacks(longPressRunnable)
                        BubbleMotion.lift(view)
                    }
                    if (dragging && !longPressFired && ringMode) {
                        onRingDrag?.invoke(event.rawX, event.rawY, event.x, event.y, MotionEvent.ACTION_MOVE)
                    } else if (dragging && !longPressFired) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, p) }
                        onDragged?.invoke(p.x, p.y, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    pressed = null
                    BubbleMotion.release(view)
                    view.alpha = idleAlpha
                    if (ringMode) {
                        onRingDrag?.invoke(event.rawX, event.rawY, event.x, event.y, MotionEvent.ACTION_UP)
                        if (!dragging && !longPressFired && event.actionMasked == MotionEvent.ACTION_UP) onShortTap()
                    } else if (dragging) {
                        savePosition(view, p)
                        onDragged?.invoke(p.x, p.y, true)
                    } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                        onShortTap()
                    }
                }
            }
            return true
        }
    }

    private fun savePosition(view: View, p: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        p.x = p.x.coerceIn(0, w - buttonSize)
        p.y = p.y.coerceIn(0, h - buttonSize)
        runCatching { windowManager.updateViewLayout(view, p) }
        val xFraction = p.x.toFloat() / (w - buttonSize).coerceAtLeast(1)
        val yFraction = p.y.toFloat() / (h - buttonSize).coerceAtLeast(1)
        scope.launch { settings.setToolsFabPosition(positionKey(), xFraction, yFraction) }
    }

    /**
     * Молоток и гаечный ключ крестом. Ключ — из нижнего левого угла вверх
     * направо, с разомкнутым кольцом на конце; молоток — из нижнего правого
     * вверх налево, с бойком поперёк рукояти. Всё от центра вида, в круге
     * радиуса 0,36 ширины: поворот и сжатие кнопки ничего не режут.
     */
    private class ToolsGlyph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val oval = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            val shaft = w * 0.085f

            // Ключ: рукоять снизу-слева к верху-справа, кольцо с разрывом наружу.
            paint.strokeWidth = shaft
            canvas.drawLine(cx - w * 0.27f, cy + w * 0.27f, cx + w * 0.14f, cy - w * 0.14f, paint)
            val ringR = w * 0.11f
            val kx = cx + w * 0.21f
            val ky = cy - w * 0.21f
            oval.set(kx - ringR, ky - ringR, kx + ringR, ky + ringR)
            // Разрыв кольца смотрит вверх-вправо (315° в экранных углах): дуга
            // начинается после разрыва и обходит кольцо, не доходя до него.
            canvas.drawArc(oval, 315f + 40f, 280f, false, paint)

            // Молоток: рукоять снизу-справа к верху-слева, боёк поперёк.
            canvas.drawLine(cx + w * 0.27f, cy + w * 0.27f, cx - w * 0.15f, cy - w * 0.15f, paint)
            paint.strokeWidth = w * 0.2f
            val hx = cx - w * 0.2f
            val hy = cy - w * 0.2f
            val half = w * 0.12f
            canvas.drawLine(hx - half, hy + half, hx + half, hy - half, paint)
        }
    }
}
