package ru.zf.pravka.trigger

import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Стрелка-галочка под стопкой: «⌄» — развернуть, «⌃» — свернуть.
 *
 * Владелец предложил это сам, и предложение лучше прежнего: «может быть, там
 * внизу под ними сделать такое, знаешь, стрелочку такую, как галочка, вниз, на
 * которую нажимаешь, и выпадают эти две кнопки». Прежний приём — торчащие из
 * под «З» края «Д» и «Е» — на деле наезжал на саму «З» и целиться приходилось
 * в трёхмиллиметровую полоску; стрелка честнее: у неё своя площадь и она прямо
 * говорит, что будет.
 *
 * Маленькая (вдвое меньше кнопки) и приглушённая: это не пятая кнопка, а ручка
 * от ящика. Своё окно — потому что все кнопки здесь такие, служба рисует их
 * поверх всего как TYPE_ACCESSIBILITY_OVERLAY.
 */
class StackChevronController(
    private val service: PravkaAccessibilityService,
) {

    private companion object {
        private val AMBER = 0xFFF78810.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
        private const val ALPHA = 0.7f
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val sizePx: Int = dp(26)

    var onTap: (() -> Unit)? = null

    fun show(collapsed: Boolean) {
        if (view == null) {
            val v = TextView(service).apply {
                setTextColor(PAPER)
                textSize = 13f
                gravity = Gravity.CENTER
                alpha = ALPHA
                elevation = dp(4).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AMBER)
                }
                setOnClickListener { onTap?.invoke() }
            }
            val p = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            params = p
            view = v
            runCatching { windowManager.addView(v, p) }
        }
        view?.visibility = View.VISIBLE
        setCollapsed(collapsed)
    }

    /** «⌄» — под стрелкой что-то спрятано; «⌃» — сложить обратно. */
    fun setCollapsed(collapsed: Boolean) {
        view?.text = if (collapsed) "⌄" else "⌃"
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        params = null
    }

    /** Ставится центром под хвостовой кнопкой; за край экрана не уходит. */
    fun moveTo(buttonX: Int, buttonY: Int, buttonSize: Int) {
        val p = params ?: return
        val v = view ?: return
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val w = bounds?.width() ?: 0
        val h = bounds?.height() ?: 0
        p.x = (buttonX + (buttonSize - sizePx) / 2)
            .coerceIn(0, (w - sizePx).coerceAtLeast(0))
        p.y = (buttonY + buttonSize + dp(4))
            .coerceIn(0, (h - sizePx).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(v, p) }
    }
}
