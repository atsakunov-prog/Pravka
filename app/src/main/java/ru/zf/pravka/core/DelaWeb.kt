package ru.zf.pravka.core

import java.net.URI

/**
 * Дела в виде Todoist на телефоне (19.10.2026). Владелец 18.10: «скопировать и дизайн, и фичи Todoist»; 19.10 выбрал
 * «новый веб и в телефоне» — один код на всё: вкладка «Дела» показывает страницу `/t` сервера Дел внутри Правки
 * (обвязка сервисов Ковчега — `ServiceWebView.kt`), а прежняя родная вкладка остаётся запасной (переключатель в
 * настройках Дел).
 *
 * Вход. Страница ходит в API тем же токеном, что телефон (`Authorization: Bearer`): Правка отдаёт его мостиком
 * `PravkaDela.token()` — и только странице Дел своего сервера ([tokenFor]: адрес главной рамки той же схемы, хоста и
 * порта, что адрес Дел из QR). Другой сервис Ковчега в том же WebView токена не получит. Ни куки, ни ссылки входа,
 * ни нового пути на сервере.
 *
 * Здесь — чистая логика без Android, под тестом `DelaWebTest`.
 */
object DelaWeb {
    /** Флаг сервера в синке: страница `/t` у него есть (dela_0036). */
    const val FEATURE = "todo"

    /** Имя мостика в странице: `window.PravkaDela`. */
    const val BRIDGE = "PravkaDela"

    /** Адрес новой страницы Дел от адреса сервера из QR (`https://…:8443/` → `https://…:8443/t`). */
    fun pageUrl(base: String): String = base.trimEnd('/') + "/t"

    /** Показывать ли новый вид: сервер подключён, умеет `/t` и владелец не выключил его в настройках. */
    fun show(onServer: Boolean, linked: Boolean, features: Collection<String>, wanted: Boolean): Boolean =
        onServer && linked && wanted && FEATURE in features

    /** Та же схема, хост и порт (порт по умолчанию — по схеме). */
    fun sameOrigin(a: String?, b: String?): Boolean {
        val x = origin(a) ?: return false
        val y = origin(b) ?: return false
        return x == y
    }

    /** Токен — только странице Дел своего сервера; иначе пусто. */
    fun tokenFor(current: String?, base: String?, token: String?): String? =
        token?.takeIf { it.isNotBlank() && sameOrigin(current, base) }

    private fun origin(url: String?): Triple<String, String, Int>? {
        val u = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = u.host?.lowercase() ?: return null
        val port = if (u.port > 0) u.port else if (scheme == "https") 443 else 80
        return Triple(scheme, host, port)
    }
}
