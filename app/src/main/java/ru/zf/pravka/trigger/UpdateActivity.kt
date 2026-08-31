package ru.zf.pravka.trigger

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

// Трамплин за уведомлением об обновлении: тапнуть можно только по Activity,
// а работа живёт в Updates. Ставит (или сперва докачивает) и уходит.
class UpdateActivity : Activity() {

    companion object {
        const val EXTRA_WHAT = "what"
        const val W_INSTALL = "install"     // APK уже в кэше
        const val W_DOWNLOAD = "download"   // сеть была платная - качаем по тапу
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as PravkaApp
        val updates = app.updates
        updates.dismissNotification()
        when (intent?.getStringExtra(EXTRA_WHAT)) {
            W_DOWNLOAD -> {
                val build = updates.state.value.latest
                if (build == null) {
                    Feedback.toast(this, getString(R.string.upd_toast_unknown))
                } else {
                    Feedback.toast(this, getString(R.string.upd_toast_downloading))
                    app.appScope.launch {
                        // Скачали - и снова тик: он сам покажет «готово, ставить».
                        // Ставить прямо отсюда нельзя: активити уже закрылась, а
                        // из фона система запуск установщика не пустит.
                        if (updates.download(build) != null) updates.tick()
                    }
                }
            }
            else -> install()
        }
        finish()
    }

    private fun install() {
        val updates = (applicationContext as PravkaApp).updates
        val file = updates.readyNow()
        if (file == null) {
            Feedback.toast(this, getString(R.string.upd_toast_gone))
            return
        }
        if (!updates.canInstall()) {
            // Первый раз: Андроид требует явного «этому приложению можно».
            Feedback.toast(this, getString(R.string.upd_toast_allow), long = true)
            runCatching { startActivity(updates.allowInstallIntent()) }
            return
        }
        runCatching { startActivity(updates.installIntent(file)) }
            .onFailure { Feedback.toast(this, getString(R.string.upd_toast_failed, it.message.orEmpty())) }
    }
}
