package ru.zf.slushalka

import java.net.URLDecoder

/**
 * Ссылки на Слушалку извне (14.10.2026) - из панели «Книги» в браузере телефона.
 *
 * - `slushalka://book?folder=<папка>` - открыть книгу домашней библиотеки. Книга - по имени папки в `Книги/`,
 *   как места и разметка: оно после раскладки не меняется. Ловит [MainActivity]; книга ищется на полке, на
 *   сервере и по прежним именам (`former`), нет нигде - строка внизу экрана, а не падение.
 * - `slushalka://app?id=<кто>&url=<https-адрес>` - открыть другое приложение семьи ([OpenAppActivity]).
 *   Нужно ради «Открыть Jellyfin» в панели «Кино»: Chrome со страницы открывает только окна с категорией
 *   BROWSABLE, а у Jellyfin для Android такого нет - прямая ссылка на него всегда уходила бы в браузер.
 *   Открывается только то, что есть в [APPS], - чужая страница не запустит этой ссылкой что угодно; `url` -
 *   только https, его открывает браузер, если самого приложения на телефоне нет.
 *
 * Со страницы ссылка уходит как `intent://…#Intent;scheme=slushalka;package=ru.zf.slushalka;
 * S.browser_fallback_url=…;end`: нет Слушалки или она старая (без этого приёма) - Chrome открывает запасной
 * адрес сам.
 */
object DeepLink {
    const val SCHEME = "slushalka"

    /** Белый список «открыть приложение семьи»: короткое имя в ссылке → пакет. */
    val APPS: Map<String, String> = mapOf("jellyfin" to "org.jellyfin.mobile")

    sealed class Link {
        data class Book(val folder: String) : Link()
        data class App(val id: String, val pkg: String, val fallback: String?) : Link()
    }

    /** Разобрать ссылку; чужое, кривое или не из белого списка - null. Без android.net.Uri: проверяется на машине. */
    fun parse(uri: String?): Link? {
        val s = uri?.trim().orEmpty()
        val sep = s.indexOf("://")
        if (sep <= 0 || !s.substring(0, sep).equals(SCHEME, ignoreCase = true)) return null
        val rest = s.substring(sep + 3)
        val host = rest.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
        val query = rest.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').filter { it.isNotEmpty() }
            .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
        return when (host) {
            "book" -> params["folder"]?.trim()
                // Имя папки, не путь: «/», «\» и «..» - не книга, а попытка уйти из библиотеки.
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_FOLDER && '/' !in it && '\\' !in it && it != "." && it != ".." }
                ?.let { Link.Book(it) }
            "app" -> {
                val id = params["id"]?.trim()?.lowercase() ?: return null
                val pkg = APPS[id] ?: return null
                Link.App(id, pkg, params["url"]?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) })
            }
            else -> null
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private const val MAX_FOLDER = 400
}
