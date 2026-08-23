package ru.zf.pravka.trigger

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.ui.Feedback

// Трамплин за кнопками уведомления «тренировка приехала»: уведомление умеет
// запускать только Activity, а работа — один PUT в intervals. Тот же приём,
// что у ZasechkaQuickActivity.
//
// Служба тут не нужна: пишем через app-scope, он живёт с приложением и не
// умирает вместе с этой прозрачной активностью.
class SportQuickActivity : Activity() {

    companion object {
        const val EXTRA_ACTIVITY_ID = "activityId"
        const val EXTRA_FEEL = "feel"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activityId = intent?.getStringExtra(EXTRA_ACTIVITY_ID).orEmpty()
        val feel = intent?.getIntExtra(EXTRA_FEEL, 0) ?: 0
        val app = application as PravkaApp
        if (activityId.isNotBlank() && feel in 1..5) {
            app.appScope.launch {
                val outcome = app.icuSportSync.pushFeel(activityId, feel)
                Feedback.toast(
                    app,
                    outcome.fold(
                        { "✓ Самочувствие $feel/5 записано в intervals" },
                        { e -> e.message ?: "Не уехало — попробуй из intervals" },
                    ),
                )
            }
        }
        finish()
    }
}
