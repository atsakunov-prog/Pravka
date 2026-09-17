package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Серая «отмена» рядом с идущей записью: тап — наговор выбрасывается, ничего
 * не вставляется и не пишется. Была только у «П»; владелец (18.09.2026): «на
 * каждом баббле должна быть отмена, как на правке» — теперь одна на четыре
 * кнопки.
 *
 * Стоит НЕ под кнопкой, а под ближним концом бегущей строки — по диагонали от
 * кнопки. Под кнопкой стоит следующая кнопка стопки: у «П» это «З», у «З» —
 * «Д», и пилюля ложилась бы прямо на неё. Сторона — та же, что у строки
 * (где есть место).
 *
 * Прозрачность — та же настройка, что у кнопок (владелец: «прозрачность
 * бабблов должна быть такая же, как и прозрачность кнопок»), а не своя 0,9.
 */
class CancelBubble(private val context: Context, private val windowManager: WindowManager) {

    private companion object {
        private val PAPER = 0xFFF7F3EA.toInt()
        private val GREY = 0xFF6E6659.toInt()  // ink-soft: пилюля молчит цветом
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private var pill: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val shown: Boolean get() = pill != null

    fun show(alpha: Float, onCancel: () -> Unit) {
        hide()
        val v = TextView(context).apply {
            text = "отмена"
            setTextColor(PAPER)
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(GREY)
            }
            this.alpha = alpha
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onCancel() }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        pill = v
        params = p
    }

    /** Показать уже собранную пилюлю у кнопки; зовётся и на каждом сдвиге кнопки. */
    fun place(buttonX: Int, buttonY: Int, buttonSize: Int, screenW: Int, screenH: Int) {
        val v = pill ?: return
        val p = params ?: return
        // Ширина нужна до показа: справа от кнопки пилюля начинается у
        // кнопки, слева — кончается у неё.
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = v.measuredWidth.coerceAtLeast(dp(60))
        val h = v.measuredHeight.coerceAtLeast(dp(30))
        val gap = dp(8)
        val centre = buttonX + buttonSize / 2
        p.x = (if (centre < screenW / 2) buttonX + buttonSize + gap else buttonX - gap - w)
            .coerceIn(0, (screenW - w).coerceAtLeast(0))
        p.y = (buttonY + buttonSize + dp(6)).coerceIn(0, (screenH - h).coerceAtLeast(0))
        if (v.parent == null) {
            runCatching { windowManager.addView(v, p) }
        } else {
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    fun setAlpha(alpha: Float) {
        pill?.alpha = alpha
    }

    fun hide() {
        pill?.let { if (it.parent != null) runCatching { windowManager.removeView(it) } }
        pill = null
        params = null
    }
}
