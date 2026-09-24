package ru.zf.slushalka

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import ru.zf.slushalka.data.GoogleAuth

/**
 * Возврат из браузера после входа в Google (`ru.zf.slushalka:/oauth2redirect?code=…`):
 * код уходит ждущему входу, а на экран возвращается Слушалка. Своего окна нет.
 */
class GoogleAuthActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!GoogleAuth.onRedirect(intent?.data?.encodedQuery)) {
            Toast.makeText(this, "Вход в Google устарел — нажми «Подключить Google Drive» ещё раз", Toast.LENGTH_LONG).show()
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
