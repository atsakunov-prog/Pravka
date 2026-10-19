package ru.zf.pravka

import android.webkit.JavascriptInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.zf.pravka.core.DelaWeb
import ru.zf.pravka.core.ServicePage

// Дела в виде Todoist на телефоне (19.10.2026, core/DelaWeb.kt): вкладка «Дела» — страница /t сервера Дел внутри
// Правки, той же обвязкой, что сервисы Ковчега (ServiceWebScreen: шапка Ковчега, «назад», тема, пул WebView). Один код
// с вебом на компе — правка сервера видна на телефоне сразу, без новой сборки.

/**
 * Мостик `window.PravkaDela` в страницу Дел: токен телефона — только странице Дел своего сервера (адрес главной рамки
 * сверяется с адресом из QR каждый раз, [DelaWeb.tokenFor]). Методы зовутся с потока WebView, не с главного: читают
 * только неизменяемое и `@Volatile`.
 */
internal class DelaWebBridge(private val app: PravkaApp, private val page: ServiceWebPage) {
    @JavascriptInterface
    fun token(): String {
        val link = app.delaSync.link.value
        return DelaWeb.tokenFor(page.mainUrl, link?.url, link?.token).orEmpty()
    }
}

/**
 * Вкладка «Дела» новым видом: [base] — адрес сервера Дел из QR, [onClose] — «назад» с главной страницы Дел.
 * [openTaskId] — дело из уведомления или ссылки: страница открывает его окном (`#/task/<id>`).
 */
@Composable
internal fun DelaWebTab(app: PravkaApp, base: String, openTaskId: String?, onOpenHandled: () -> Unit, onClose: () -> Unit) {
    val page = remember(base) { ServicePage("Дела", "dela", DelaWeb.pageUrl(base)) }
    val bridge = remember(app) { DelaWeb.BRIDGE to { p: ServiceWebPage -> DelaWebBridge(app, p) as Any } }
    val goTo = openTaskId?.let { DelaWeb.pageUrl(base) + "#/task/" + android.net.Uri.encode(it) }
    ServiceWebScreen(app, page, onClose = onClose, bridge = bridge, goTo = goTo, onGone = onOpenHandled)
}
