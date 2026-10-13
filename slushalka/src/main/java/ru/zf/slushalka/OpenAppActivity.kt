package ru.zf.slushalka

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * «Открыть приложение семьи» по ссылке `slushalka://app?id=…` (14.10.2026) - пересадка без своего экрана.
 *
 * Ради кнопки «Открыть Jellyfin» в панели «Кино»: Chrome со страницы пускает только в окна с категорией
 * BROWSABLE, а у Jellyfin для Android такого окна нет, и прямая intent-ссылка на него всегда уходила бы в
 * браузер. Здесь - прозрачное окно вне истории и вне задачи Слушалки: запускает приложение из белого списка
 * [DeepLink.APPS] и сразу закрывается, так что «назад» из Jellyfin ведёт в браузер, а не в Слушалку. Нет такого
 * приложения - открывает `url` из ссылки (только https) в браузере; нет и его - строка «не нашёл».
 */
class OpenAppActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = DeepLink.parse(intent?.dataString) as? DeepLink.Link.App
        val launch = link?.let { packageManager.getLaunchIntentForPackage(it.pkg) }
        val ok = runCatching {
            when {
                launch != null -> startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                link?.fallback != null -> startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(link.fallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                else -> error("нечего открыть")
            }
        }.isSuccess
        if (!ok) {
            Toast.makeText(
                this,
                if (link == null) "Слушалка не знает такой ссылки" else "На телефоне нет приложения «${link.id}»",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }
}
