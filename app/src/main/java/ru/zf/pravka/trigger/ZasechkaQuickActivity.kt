package ru.zf.pravka.trigger

import android.app.Activity
import android.os.Bundle
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

// Trampoline behind the reminder notification's "Надиктовать" action: an
// Activity is the only thing a notification can launch, but the actual work
// (grab the mic, start a Засечка take) lives in the accessibility service.
// Starts the take and gets out of the way immediately.
class ZasechkaQuickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
        } else {
            service.onZasechkaTap()
        }
        finish()
    }
}
