package ru.zf.pravka.trigger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// «Разноска» в меню выделения текста (ACTION_PROCESS_TEXT) — рядом с
// «Правкой». Выделил дайджест в чате, расшифровку встречи, письмо — и дела
// разбираются из него так же, как из наговора.
//
// Активность ничего не рисует и сразу уходит: разбор и плашка живут в службе,
// поверх того приложения, где текст и был выделен.
class ProcessTasksActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val service = PravkaAccessibilityService.instance
        when {
            service == null -> {
                Haptics.error(this)
                Feedback.toast(this, getString(R.string.toast_no_service))
            }
            text.isBlank() -> Haptics.error(this)
            else -> {
                Haptics.start(this)
                service.raznoskaFromText(text)
            }
        }
        finish()
    }
}
