package ru.zf.pravka.trigger

import android.app.Activity
import android.os.Bundle
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

// Trampoline behind Засечка notification actions: an Activity is the only
// thing a notification can launch, but the actual work lives in the
// accessibility service. Starts the requested action and gets out of the way.
class ZasechkaQuickActivity : Activity() {

    companion object {
        const val EXTRA_WHAT = "what"
        const val W_RECORD = "record"    // default: start a voice take
        /**
         * Якорь времени тейка: пуш автопилота знает, КОГДА кончилась дорога
         * («машина отключилась в 14:02») — сказанное ляжет с этого момента,
         * а не с секунды, когда владелец договорил. [EXTRA_UNTIL] — конец
         * закрытой дыры, тогда сказанное — вставка ровно в неё.
         */
        const val EXTRA_AT = "at"
        const val EXTRA_UNTIL = "until"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
        } else {
            // Единственное действие — тейк; «что» оставлено на будущее.
            service.onZasechkaTap(
                anchorStart = intent?.getLongExtra(EXTRA_AT, 0L) ?: 0L,
                anchorEnd = intent?.getLongExtra(EXTRA_UNTIL, 0L) ?: 0L,
            )
        }
        finish()
    }
}
