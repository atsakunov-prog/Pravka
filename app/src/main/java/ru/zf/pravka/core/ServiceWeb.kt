package ru.zf.pravka.core

import java.net.URI
import java.net.URLDecoder

/**
 * Страница сервиса внутри приложения: название и значок — для шапки, адрес —
 * откуда начать. Приходит из меню Ковчега (`Kovcheg.Target.Web`) или с плитки
 * «Сегодня» (Шаббат); адреса — только из синка Дел (`/api/me` → `look.nav`).
 */
data class ServicePage(val name: String, val icon: String, val url: String) {
    companion object {
        /** Обратно из сохранённого состояния экрана: три строки — название, значок, адрес. */
        fun of(saved: List<String>?): ServicePage? =
            saved?.takeIf { it.size == 3 && it[2].isNotBlank() }?.let { ServicePage(it[0], it[1], it[2]) }
    }
}

/**
 * Сервисы Ковчега внутри Правки (14.10.2026, владелец: «две копии одного и
 * того же» — одно приложение, страницы сервисов внутри него с шапкой Ковчега;
 * план 5.0 §4: «страницей веба внутри приложения правка сервера видна сразу»).
 *
 * Здесь — чистая логика без Android, под тестом `ServiceWebTest`:
 * какой адрес открывается внутри, какой уходит наружу и куда; домен общей
 * куки темы и сама кука; разбор ссылок `intent://`; какое приложение зовёт
 * шапка сервиса (Jellyfin у Кино, Слушалка у Книг).
 *
 * Почему внутри — только адреса сервисов. WebView держит вход владельца во
 * все сервисы семьи (куки); чужая страница в нём жила бы рядом с этими
 * куками и под шапкой Ковчега выглядела бы своей. Поэтому внутри — хосты из
 * `look.nav`, хост Дел и всё под их общим доменом; прочее — браузером.
 * Ни адресов, ни домена в коде нет: репозиторий публичный, всё приходит синком.
 */
object ServiceWeb {

    /** Кука темы общего слоя Ковчега (`kovcheg.js`: `kv-theme`, «light» или «dark»). */
    const val THEME_COOKIE = "kv-theme"

    /** Год, как у `kovcheg.js` (`max-age=31536000`). */
    private const val THEME_MAX_AGE = 31_536_000

    /** Метка в User-Agent: сервис может узнать, что открыт внутри Правки, а не в браузере. */
    const val UA_MARK = "PravkaApp"

    /** Спрятанная страница старше этого при возвращении перечитывается: правка сервера видна сразу. */
    const val STALE_MS = 10 * 60_000L

    const val JELLYFIN_PACKAGE = "org.jellyfin.mobile"
    const val SLUSHALKA_PACKAGE = "ru.zf.slushalka"

    /** Куда идёт переход со страницы сервиса. */
    sealed class Route {
        /** Адрес сервиса — грузится в этом же WebView. */
        data object Inside : Route()

        /** Чужой сайт — браузером (Custom Tab), не рядом с куками сервисов. */
        data class Browser(val url: String) : Route()

        /** `intent://…#Intent;…;end` — разобрать и запустить приложение; нет его — `browser_fallback_url`. */
        data class Intent(val url: String) : Route()

        /** Своя схема приложения (`slushalka://`), `tel:`, `mailto:`, `geo:` — системе. */
        data class App(val url: String) : Route()

        /** `about:`, `blob:`, `data:`, `javascript:` — дело самого WebView. */
        data object Stay : Route()

        /** `file:`, `content:`, пустое — не открывать вовсе: файлового доступа у страниц нет. */
        data object Block : Route()
    }

    /**
     * Что пускать внутрь: точные хосты сервисов [hosts] и всё под общим
     * доменом [domain] (у сервисов семьи он один — `<сервис>.<домен>`).
     */
    class Rules(val hosts: Set<String>, val domain: String?) {
        fun inside(url: String): Boolean {
            val u = parse(url) ?: return false
            if (u.scheme != "http" && u.scheme != "https") return false
            val h = u.host
            return h in hosts || under(h, domain)
        }
    }

    /**
     * Правила по сервисам из синка: [nav] — `look.nav`, [base] — адрес Дел
     * (относительные адреса меню — от него), [extra] — адрес открытой
     * страницы (Шаббат с плитки «Сегодня» — тоже из `look.nav`, но пусть
     * откроется, даже если список пока пуст).
     */
    fun rules(nav: List<Kovcheg.Nav>, base: String?, extra: String? = null): Rules {
        val urls = nav.mapNotNull { Kovcheg.absolute(it.url, base) } + listOfNotNull(base, extra)
        val hosts = urls.mapNotNull { host(it) }.toSet()
        return Rules(hosts, familyDomain(nav, base))
    }

    /**
     * Общий домен сервисов — им ставится кука темы, так же как её ставит
     * `kovcheg.js` (`domain=` на весь домен семьи): иначе выбор темы на
     * странице и наш выбор жили бы двумя разными куками. Правило родителя —
     * то же, что у сервера Дел (`api._cookie_domain`): `сервис.домен` → `домен`;
     * IP, `.localhost` и двухсловный хост родителя не дают. Берётся родитель,
     * общий для большинства сервисов меню; поровну — родитель адреса Дел.
     */
    fun familyDomain(nav: List<Kovcheg.Nav>, base: String?): String? {
        val baseParent = base?.let(::host)?.let(::parentOf)
        val parents = nav.mapNotNull { Kovcheg.absolute(it.url, base) }.mapNotNull { host(it) }.mapNotNull(::parentOf) +
            listOfNotNull(baseParent)
        if (parents.isEmpty()) return null
        return parents.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key == baseParent })
            .first().key
    }

    /** Родитель хоста по правилу сервера: `a.b.c` → `b.c`; меньше трёх слов, IP, `.localhost` — null. */
    fun parentOf(host: String): String? {
        val h = host.lowercase()
        val parts = h.split('.')
        if (parts.size < 3 || h.replace(".", "").all { it.isDigit() } || h.endsWith(".localhost")) return null
        return parts.drop(1).joinToString(".")
    }

    /** Куда вести адрес со страницы сервиса. */
    fun route(url: String, rules: Rules?): Route {
        val scheme = url.substringBefore(':', "").trim().lowercase()
        return when (scheme) {
            "http", "https" -> if (rules?.inside(url) == true) Route.Inside else Route.Browser(url)
            "intent" -> Route.Intent(url)
            "about", "blob", "data", "javascript" -> Route.Stay
            "", "file", "content" -> Route.Block
            else -> if (SCHEME.matches(scheme)) Route.App(url) else Route.Block
        }
    }

    /**
     * Кука темы для адреса [url]: «light» или «dark» — как в приложении.
     * Хост под общим доменом — кука на весь домен (как у `kovcheg.js`), иначе
     * — только на этот хост (у `kovcheg.js` так же для адреса не на домене).
     */
    fun themeCookie(dark: Boolean, url: String, domain: String?): String {
        val u = parse(url)
        val parts = mutableListOf("$THEME_COOKIE=${if (dark) "dark" else "light"}", "Path=/", "Max-Age=$THEME_MAX_AGE", "SameSite=Lax")
        if (u != null && under(u.host, domain)) parts += "Domain=$domain"
        if (u?.scheme == "https") parts += "Secure"
        return parts.joinToString("; ")
    }

    /**
     * Переключить тему открытой страницы без перезагрузки: общий слой
     * Ковчега умеет `KV.setTheme` (он же перепишет куку). Ответ «1» — страница
     * переключилась сама; иначе (не наша страница, ещё грузится) — перечитать.
     */
    fun themeScript(dark: Boolean): String {
        val v = if (dark) "dark" else "light"
        return "(function(){try{if(window.KV&&typeof KV.setTheme==='function'){KV.setTheme('$v');return 1}}catch(e){}return 0})()"
    }

    /** Ссылка `intent://` по правилам Chrome: что нужно решить до запуска. */
    data class IntentLink(
        /** `package=` — какое приложение. */
        val pkg: String?,
        /** `scheme=` — схема данных (`slushalka`, `https`…). */
        val scheme: String?,
        /** Адрес данных: `scheme` + часть до `#Intent`. */
        val data: String?,
        /** `S.browser_fallback_url` — куда, если приложения нет; только http(s). */
        val fallback: String?,
    )

    /** Разобрать `intent://…#Intent;…;end`; не `intent:` — null. Значения — в процентной записи (`Uri.encode`). */
    fun parseIntent(url: String): IntentLink? {
        if (!url.startsWith("intent:", ignoreCase = true)) return null
        val mark = url.indexOf("#Intent;")
        val head = if (mark >= 0) url.substring("intent:".length, mark) else url.substring("intent:".length)
        val tail = if (mark >= 0) url.substring(mark + "#Intent;".length) else ""
        val params = HashMap<String, String>()
        for (part in tail.split(';')) {
            if (part.isEmpty() || part == "end") continue
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            params[part.substring(0, eq)] = decode(part.substring(eq + 1))
        }
        val scheme = params["scheme"]?.takeIf { SCHEME.matches(it.lowercase()) }
        val fallback = params["S.browser_fallback_url"]?.takeIf {
            val s = it.substringBefore(':').lowercase()
            (s == "http" || s == "https") && parse(it) != null
        }
        val data = if (scheme != null && head.isNotEmpty()) "$scheme:$head" else null
        return IntentLink(params["package"]?.takeIf { PACKAGE.matches(it) }, scheme, data, fallback)
    }

    /** Приложение, которое зовёт шапка сервиса: подпись, пакет, значок набора и есть ли у него веб на замену. */
    data class AppLink(val label: String, val pkg: String, val glyph: String, val webFallback: Boolean)

    /**
     * Кино — «Jellyfin» (нет приложения — веб Jellyfin по ссылке со страницы
     * Кино), Книги — «Слушалка». Сервис узнаётся по значку, как в меню
     * Ковчега: названия и адреса приходят с сервера.
     */
    fun appLink(icon: String): AppLink? = when (icon) {
        "film" -> AppLink("Jellyfin", JELLYFIN_PACKAGE, "play", webFallback = true)
        "book" -> AppLink("Слушалка", SLUSHALKA_PACKAGE, "headphones", webFallback = false)
        else -> null
    }

    /**
     * Найти на странице ссылку `intent:` на приложение [pkg] (кнопка «Открыть
     * Jellyfin» на странице Кино) — у неё `browser_fallback_url`, веб Jellyfin.
     * Скрипт только читает страницу: моста в приложение (`addJavascriptInterface`) нет.
     */
    fun findIntentScript(pkg: String): String {
        require(PACKAGE.matches(pkg)) { "пакет: $pkg" }
        return "(function(){var a=document.querySelectorAll('a[href^=\"intent:\"]');" +
            "for(var i=0;i<a.length;i++){var h=a[i].getAttribute('href')||'';if(h.indexOf('package=$pkg;')>=0)return h}return null})()"
    }

    /** Ответ `evaluateJavascript` (JSON-значение) — строкой; не строка или пусто — null. */
    fun jsString(raw: String?): String? = runCatching {
        org.json.JSONTokener(raw ?: return null).nextValue() as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Перечитать ли страницу, спрятанную в [hiddenAt] (0 — не пряталась): дольше [STALE_MS] — да. */
    fun needsReload(hiddenAt: Long, now: Long): Boolean = hiddenAt > 0 && now - hiddenAt >= STALE_MS

    /** Хост адреса строчными; не адрес — null. */
    fun host(url: String): String? = parse(url)?.host

    private class Parsed(val scheme: String, val host: String)

    private fun parse(url: String): Parsed? = runCatching {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        Parsed(scheme, host)
    }.getOrNull()

    private fun under(host: String, domain: String?): Boolean =
        !domain.isNullOrBlank() && (host == domain || host.endsWith(".$domain"))

    /** Процентная запись без «+ — пробел»: `Uri.encode` пишет пробел как %20, а «+» в адресе — это плюс. */
    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]{0,31}$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
}
