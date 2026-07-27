package ru.zf.pravka.trigger

import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import ru.zf.pravka.R

// Transient undo chip: appears bottom-center for a few seconds after a fix
// is written into the field. Replaces the undo item of the removed floating
// button - the system accessibility button has no long-press menu, and undo
// is the safety net that must stay one tap away. Editorial palette: ink
// pill, vermilion ring, paper text (same as ui/Theme.kt and the icon).
class UndoChipController(
    private val service: PravkaAccessibilityService,
    private val onUndo: () -> Unit,
) {

    companion object {
        private const val SHOW_MS = 8000L
        private val INK = 0xF5241F19.toInt()
        private val VERMILION = 0xFFC13B2A.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private var chip: TextView? = null
    private val hideRunnable = Runnable { dismiss() }

    private fun dp(value: Int): Int = (value * density).toInt()

    fun show() {
        dismiss()
        val view = TextView(service).apply {
            text = service.getString(R.string.undo_chip)
            setTextColor(PAPER)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(INK)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), VERMILION)
            }
            elevation = dp(6).toFloat()
            setOnClickListener {
                dismiss()
                onUndo()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }
        if (runCatching { windowManager.addView(view, params) }.isSuccess) {
            chip = view
            view.postDelayed(hideRunnable, SHOW_MS)
        }
    }

    fun dismiss() {
        chip?.let {
            it.removeCallbacks(hideRunnable)
            runCatching { windowManager.removeView(it) }
        }
        chip = null
    }
}
