package ru.zf.pravka.trigger

import android.app.Activity
import android.os.Bundle
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

// Трамплин за кнопками уведомлений автопилота: уведомление умеет запускать
// только Activity, работа живёт в AutoPilot при службе доступности.
class AutoPilotActivity : Activity() {

    companion object {
        const val EXTRA_WHAT = "what"
        const val EXTRA_AT = "at"
        const val EXTRA_PLACE = "place"
        /** Запись, о которой кнопка (отмена автопоездки), и та, что шла до неё. */
        const val EXTRA_ID = "id"
        const val EXTRA_PREV = "prev"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
        } else {
            service.autoPilot.onAction(
                what = intent?.getStringExtra(EXTRA_WHAT).orEmpty(),
                atMs = intent?.getLongExtra(EXTRA_AT, 0L) ?: 0L,
                fromPlace = intent?.getStringExtra(EXTRA_PLACE).orEmpty(),
                id = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L,
                prevId = intent?.getLongExtra(EXTRA_PREV, 0L) ?: 0L,
            )
        }
        finish()
    }
}
