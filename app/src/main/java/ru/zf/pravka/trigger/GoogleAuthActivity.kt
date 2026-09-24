package ru.zf.pravka.trigger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import ru.zf.pravka.MainActivity
import ru.zf.pravka.provider.GoogleAuth
import ru.zf.pravka.ui.Feedback

/**
 * Возврат из браузера после входа в Google (`ru.zf.pravka:/oauth2redirect?code=…`):
 * код уходит ждущему входу (`GoogleAuth.onRedirect`), а на экран возвращается
 * Правка — там, где нажали «Подключить». Своего окна нет.
 */
class GoogleAuthActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!GoogleAuth.onRedirect(intent?.data?.encodedQuery)) {
            // Пока был открыт браузер, система выгрузила Правку — вход начат заново не был.
            Feedback.toast(this, "Вход в Google устарел — нажми «Подключить Google Drive» ещё раз", long = true)
        }
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        finish()
    }
}
