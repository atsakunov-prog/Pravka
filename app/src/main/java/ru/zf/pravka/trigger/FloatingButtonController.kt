package ru.zf.pravka.trigger

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.data.Settings

// Floating button drawn from the accessibility service as a
// TYPE_ACCESSIBILITY_OVERLAY window (spec 5.3): no SYSTEM_ALERT_WINDOW
// permission, no foreground service. Visible only while an editable field
// is focused. Short tap = CLEAN, long press = mode menu, drag = move
// (snaps to the nearest edge, position saved per screen size - the
// foldable has two of them).
class FloatingButtonController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onMode: (ProofreadMode) -> Unit,
    private val onUndo: () -> Unit,
    private val onOpenApp: () -> Unit,
) {

    companion object {
        private const val LONG_PRESS_MS = 450L
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    private var button: FrameLayout? = null
    private var label: TextView? = null
    private var progress: ProgressBar? = null
    private var params: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var busy = false
    private var visible = false

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        val bounds = windowManager.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun positionKey(): String {
        val (w, h) = screenSize()
        return "${w}x$h"
    }

    fun show() {
        if (button == null) create()
        if (!visible) {
            button?.visibility = View.VISIBLE
            visible = true
        }
    }

    fun hide() {
        dismissMenu()
        if (visible && !busy) {
            button?.visibility = View.GONE
            visible = false
        }
    }

    fun setBusy(value: Boolean) {
        busy = value
        label?.visibility = if (value) View.GONE else View.VISIBLE
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        button?.alpha = if (value) 1f else idleAlpha
    }

    fun onConfigurationChanged() {
        val p = params ?: return
        scope.launch {
            val (xFraction, yFraction) = settings.fabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
    }

    fun destroy() {
        dismissMenu()
        button?.let { runCatching { windowManager.removeView(it) } }
        button = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create() {
        val container = FrameLayout(service)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1B3A5C.toInt())
        }
        container.background = background
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha

        label = TextView(service).apply {
            text = "П"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        progress = ProgressBar(service).apply {
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        val progressSize = dp(28)
        container.addView(
            progress,
            FrameLayout.LayoutParams(progressSize, progressSize, Gravity.CENTER),
        )

        val p = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (w, h) = screenSize()
            x = w - buttonSize
            y = h / 2
        }
        params = p
        button = container
        windowManager.addView(container, p)
        container.visibility = View.GONE

        scope.launch {
            val (xFraction, yFraction) = settings.fabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            runCatching { windowManager.updateViewLayout(container, p) }
        }
        // Owner-adjustable size and transparency (app settings) apply live.
        scope.launch {
            settings.fabSizeFlow.collect { sizeDp ->
                buttonSize = dp(sizeDp)
                p.width = buttonSize
                p.height = buttonSize
                label?.textSize = 12f + sizeDp / 4f
                runCatching { windowManager.updateViewLayout(container, p) }
            }
        }
        scope.launch {
            settings.fabAlphaFlow.collect { alpha ->
                idleAlpha = alpha
                if (!busy) container.alpha = idleAlpha
            }
        }

        container.setOnTouchListener(DragTouchListener())
    }

    private fun applyPosition(p: WindowManager.LayoutParams, xFraction: Float, yFraction: Float) {
        val (w, h) = screenSize()
        p.x = ((w - buttonSize) * xFraction.coerceIn(0f, 1f)).toInt()
        p.y = ((h - buttonSize) * yFraction.coerceIn(0f, 1f)).toInt()
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressFired = false
        private val longPressRunnable = Runnable {
            longPressFired = true
            showMenu()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longPressFired = false
                    view.alpha = 1f
                    view.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        view.removeCallbacks(longPressRunnable)
                    }
                    if (dragging && !longPressFired) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, p) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    if (!busy) view.alpha = idleAlpha
                    if (dragging) {
                        savePosition(view, p)
                    } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (!busy) onMode(ProofreadMode.CLEAN)
                    }
                }
            }
            return true
        }
    }

    // Free positioning - the button stays exactly where the owner drops it
    // (kept inside the screen), no edge snapping.
    private fun savePosition(view: View, p: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        p.x = p.x.coerceIn(0, w - buttonSize)
        p.y = p.y.coerceIn(0, h - buttonSize)
        runCatching { windowManager.updateViewLayout(view, p) }
        val xFraction = p.x.toFloat() / (w - buttonSize).coerceAtLeast(1)
        val yFraction = p.y.toFloat() / (h - buttonSize).coerceAtLeast(1)
        scope.launch { settings.setFabPosition(positionKey(), xFraction, yFraction) }
    }

    // Long-press menu: a second small accessibility overlay next to the button.
    private fun showMenu() {
        if (menu != null || busy) return
        val p = params ?: return

        val list = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF21B3A5C.toInt())
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(6).toFloat()
        }
        fun item(textRes: Int, action: () -> Unit) {
            val item = TextView(service).apply {
                text = service.getString(textRes)
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    dismissMenu()
                    action()
                }
            }
            list.addView(item)
        }
        item(R.string.fab_menu_business) { onMode(ProofreadMode.BUSINESS) }
        item(R.string.fab_menu_soften) { onMode(ProofreadMode.SOFTEN) }
        item(R.string.fab_menu_undo) { onUndo() }
        item(R.string.fab_menu_open_app) { onOpenApp() }

        val (w, h) = screenSize()
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (p.x < w / 2) p.x + buttonSize + dp(8) else p.x - dp(180)
            y = p.y.coerceIn(0, h - dp(180))
        }

        list.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else {
                false
            }
        }

        menu = list
        windowManager.addView(list, menuParams)
    }

    private fun dismissMenu() {
        menu?.let { runCatching { windowManager.removeView(it) } }
        menu = null
    }
}
