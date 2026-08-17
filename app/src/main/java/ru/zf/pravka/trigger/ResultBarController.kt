package ru.zf.pravka.trigger

import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ru.zf.pravka.R
import ru.zf.pravka.core.WordDiff

// Post-fix action bar (spec 9.2): after a fix lands in the field, a small
// bar appears bottom-center for a few seconds with three actions - undo,
// "what changed" (word diff sheet) and quick add-to-dictionary (tap a
// wrongly-"fixed" word pair to protect it). Editorial palette: ink,
// vermilion ring, paper text - same as the button and the icon.
class ResultBarController(
    private val service: PravkaAccessibilityService,
    private val onUndo: () -> Unit,
    private val onAddToDict: (correct: String, wrong: String) -> Unit,
    private val onRedo: (directive: String) -> Unit,
) {

    companion object {
        private const val SHOW_MS = 12_000L
        private val INK = 0xF5241F19.toInt()
        private val VERMILION = 0xFFC13B2A.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
        private val PAPER_DIM = 0xFFB9AF9E.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density

    private var bar: View? = null
    private var panel: View? = null
    private var before: String = ""
    private var after: String = ""
    private var shownAt = 0L
    private val hideRunnable = Runnable { dismiss() }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun pillBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(INK)
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), VERMILION)
    }

    private fun chip(text: String, onClick: () -> Unit): TextView =
        TextView(service).apply {
            this.text = text
            setTextColor(PAPER)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }

    /** Shows the bar for the fix [before] -> [after]. */
    fun show(before: String, after: String) {
        dismiss()
        this.before = before
        this.after = after

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBackground()
            elevation = dp(6).toFloat()
        }
        fun divider() {
            row.addView(
                View(service).apply { setBackgroundColor(PAPER_DIM) },
                LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        }
        row.addView(chip(service.getString(R.string.result_undo)) { dismiss(); onUndo() })
        divider()
        row.addView(chip(service.getString(R.string.result_diff)) { showDiffPanel() })
        divider()
        row.addView(chip(service.getString(R.string.result_dict)) { showDictPanel() })

        // Second row: one-tap reworks on the stronger model.
        val redoRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBackground()
            elevation = dp(6).toFloat()
        }
        fun redoDivider() {
            redoRow.addView(
                View(service).apply { setBackgroundColor(PAPER_DIM) },
                LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        }
        redoRow.addView(chip(service.getString(R.string.redo_shorter)) {
            dismiss(); onRedo(ru.zf.pravka.core.Prompts.REDO_SHORTER)
        })
        redoDivider()
        redoRow.addView(chip(service.getString(R.string.redo_longer)) {
            dismiss(); onRedo(ru.zf.pravka.core.Prompts.REDO_LONGER)
        })
        redoDivider()
        redoRow.addView(chip(service.getString(R.string.redo_polish)) {
            dismiss(); onRedo(ru.zf.pravka.core.Prompts.REDO_POLISH)
        })

        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(row)
        column.addView(
            redoRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

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
        if (runCatching { windowManager.addView(column, params) }.isSuccess) {
            bar = column
            shownAt = android.os.SystemClock.uptimeMillis()
            column.postDelayed(hideRunnable, SHOW_MS)
        }
    }

    /**
     * Dismiss triggered by window-change events. The result toast, the IME
     * and this bar itself all fire such events right after a fix - a grace
     * period keeps them from killing the bar the moment it appears.
     */
    fun dismissIfStale() {
        if (bar == null) return
        if (android.os.SystemClock.uptimeMillis() - shownAt < 2000L) return
        dismiss()
    }

    fun dismiss() {
        dismissPanel()
        bar?.let {
            it.removeCallbacks(hideRunnable)
            runCatching { windowManager.removeView(it) }
        }
        bar = null
    }

    private fun dismissPanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    /** Keeps the bar alive while a panel is open. */
    private fun resetTimer() {
        bar?.let {
            it.removeCallbacks(hideRunnable)
            it.postDelayed(hideRunnable, SHOW_MS)
        }
    }

    private fun panelContainer(titleRes: Int): LinearLayout =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(INK)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), VERMILION)
            }
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(
                TextView(service).apply {
                    text = service.getString(titleRes)
                    setTextColor(PAPER)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(8))
                }
            )
        }

    private fun showPanel(content: LinearLayout) {
        dismissPanel()
        resetTimer()
        val scroll = ScrollView(service).apply {
            addView(content)
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismissPanel()
                    v.performClick()
                    true
                } else false
            }
        }
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            (bounds.width() - dp(32)).coerceAtMost(dp(420)),
            // Capped height: long diffs scroll instead of covering the screen.
            (bounds.height() / 2).coerceAtMost(dp(400)),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(180)
        }
        if (runCatching { windowManager.addView(scroll, params) }.isSuccess) {
            panel = scroll
        }
    }

    private fun showDiffPanel() {
        val changes = WordDiff.changes(before, after)
            .filter { it.before.isNotBlank() || it.after.isNotBlank() }
        val container = panelContainer(R.string.diff_panel_title)
        if (changes.isEmpty()) {
            container.addView(
                TextView(service).apply {
                    text = service.getString(R.string.diff_panel_empty)
                    setTextColor(PAPER_DIM)
                    textSize = 14f
                }
            )
        }
        for (c in changes.take(40)) {
            container.addView(
                TextView(service).apply {
                    text = when {
                        c.before.isBlank() -> "+ ${c.after}"
                        c.after.isBlank() -> "− ${c.before}"
                        else -> "${c.before} → ${c.after}"
                    }
                    setTextColor(PAPER)
                    textSize = 14f
                    setPadding(0, dp(4), 0, dp(4))
                }
            )
        }
        showPanel(container)
    }

    private fun showDictPanel() {
        val pairs = WordDiff.wordPairs(before, after)
        val container = panelContainer(R.string.dict_panel_title)
        if (pairs.isEmpty()) {
            container.addView(
                TextView(service).apply {
                    text = service.getString(R.string.dict_panel_empty)
                    setTextColor(PAPER_DIM)
                    textSize = 14f
                }
            )
        }
        for (p in pairs.take(30)) {
            container.addView(
                TextView(service).apply {
                    text = "${p.before} ← ${p.after}"
                    setTextColor(PAPER)
                    textSize = 15f
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener {
                        dismiss()
                        onAddToDict(p.before, p.after)
                    }
                }
            )
        }
        showPanel(container)
    }
}
