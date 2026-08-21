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
        const val W_POMO25 = "pomo25"
        const val W_BREAK5 = "break5"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
        } else {
            when (intent?.getStringExtra(EXTRA_WHAT)) {
                W_POMO25 -> service.startPomodoro(25, isBreak = false)
                W_BREAK5 -> service.startPomodoro(5, isBreak = true)
                else -> service.onZasechkaTap()
            }
        }
        finish()
    }
}
