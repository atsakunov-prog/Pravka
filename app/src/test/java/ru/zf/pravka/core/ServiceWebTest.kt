package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сервисы Ковчега внутри приложения (14.10.2026): что открывается в WebView, что
 * уходит браузером или приложению, кука темы общего слоя, ссылки `intent://`.
 * Адреса выдуманные (`example.org`, `home.lan`): настоящих в публичном
 * репозитории нет — они приходят синком Дел.
 */
class ServiceWebTest {

    private val nav = listOf(
        Kovcheg.Nav("Дела", "dela", "/"),
        Kovcheg.Nav("Встречи", "mic", "https://meet.example.org/"),
        Kovcheg.Nav("Шаббат", "candles", "https://shabbat.example.org/"),
        Kovcheg.Nav("Кино", "film", "https://kino.example.org/"),
        Kovcheg.Nav("Книги", "book", "https://books.example.org/"),
    )
    private val base = "https://dela.example.org/"
    private val rules = ServiceWeb.rules(nav, base)

    @Test
    fun `внутри — сервисы меню, Дела и всё под их доменом`() {
        assertEquals("example.org", rules.domain)
        assertEquals(ServiceWeb.Route.Inside, ServiceWeb.route("https://meet.example.org/m/42?x=1", rules))
        assertEquals(ServiceWeb.Route.Inside, ServiceWeb.route("https://dela.example.org/static/index.html", rules))
        // Веб Jellyfin на соседнем имени того же домена — тоже свой: кнопка «Открыть Jellyfin» без приложения.
        assertEquals(ServiceWeb.Route.Inside, ServiceWeb.route("https://media.example.org/web/", rules))
        assertEquals(ServiceWeb.Route.Inside, ServiceWeb.route("HTTPS://Kino.Example.ORG/", rules))
    }

    @Test
    fun `чужое — браузером, и похожее на своё тоже`() {
        for (u in listOf(
            "https://www.youtube.com/watch?v=1",
            "https://example.org.evil.net/",
            "https://evilexample.org/",
            // Имя пользователя перед @ — не хост: настоящий хост тут evil.net.
            "https://meet.example.org@evil.net/",
        )) {
            assertEquals(u, ServiceWeb.Route.Browser(u), ServiceWeb.route(u, rules))
        }
        // Правил ещё нет (синк не пришёл) — внутрь ничего.
        assertEquals(ServiceWeb.Route.Browser("https://meet.example.org/"), ServiceWeb.route("https://meet.example.org/", null))
    }

    @Test
    fun `схемы — приложению, WebView или никуда`() {
        assertEquals(ServiceWeb.Route.Intent("intent://x#Intent;end"), ServiceWeb.route("intent://x#Intent;end", rules))
        assertEquals(ServiceWeb.Route.App("slushalka://book?folder=a"), ServiceWeb.route("slushalka://book?folder=a", rules))
        assertEquals(ServiceWeb.Route.App("tel:+79990000000"), ServiceWeb.route("tel:+79990000000", rules))
        assertEquals(ServiceWeb.Route.App("mailto:a@example.org"), ServiceWeb.route("mailto:a@example.org", rules))
        assertEquals(ServiceWeb.Route.Stay, ServiceWeb.route("about:blank", rules))
        assertEquals(ServiceWeb.Route.Stay, ServiceWeb.route("blob:https://meet.example.org/1", rules))
        assertEquals(ServiceWeb.Route.Stay, ServiceWeb.route("javascript:void(0)", rules))
        assertEquals(ServiceWeb.Route.Block, ServiceWeb.route("file:///sdcard/a.txt", rules))
        assertEquals(ServiceWeb.Route.Block, ServiceWeb.route("content://media/1", rules))
        assertEquals(ServiceWeb.Route.Block, ServiceWeb.route("без схемы", rules))
    }

    @Test
    fun `общий домен — родитель большинства, поровну — родитель Дел, правило родителя как у сервера`() {
        assertEquals("example.org", ServiceWeb.parentOf("dela.example.org"))
        assertEquals("b.example.org", ServiceWeb.parentOf("a.b.example.org"))
        assertNull(ServiceWeb.parentOf("example.org"))
        assertNull(ServiceWeb.parentOf("10.0.0.5"))
        assertNull(ServiceWeb.parentOf("dela.localhost"))
        // Дела на старом имени, сервисы — на новом: кука — домен сервисов (иначе с `kovcheg.js` их будет две).
        val moved = listOf(
            Kovcheg.Nav("Встречи", "mic", "https://meet.new.example/"),
            Kovcheg.Nav("Дети", "kids", "https://kids.new.example/"),
        )
        assertEquals("new.example", ServiceWeb.familyDomain(moved, "https://dela.old.example/"))
        // Поровну — родитель адреса Дел.
        assertEquals("old.example", ServiceWeb.familyDomain(moved.take(1), "https://dela.old.example/"))
        // Адреса напрямую по IP — общего домена нет, только точные хосты.
        val lan = listOf(Kovcheg.Nav("Дети", "kids", "http://10.0.0.5:8080/"))
        val r = ServiceWeb.rules(lan, "https://10.0.0.5:8443/")
        assertNull(r.domain)
        assertTrue(r.inside("http://10.0.0.5:8080/week"))
        assertFalse(r.inside("http://10.0.0.6/"))
        assertNull(ServiceWeb.familyDomain(emptyList(), null))
    }

    @Test
    fun `адрес открытой страницы — свой, даже если меню ещё пустое`() {
        val r = ServiceWeb.rules(emptyList(), null, "https://shabbat.home.lan/")
        assertTrue(r.inside("https://shabbat.home.lan/parsha"))
        assertFalse(r.inside("https://other.home.lan/"))
    }

    @Test
    fun `кука темы — как у kovcheg js`() {
        assertEquals(
            "kv-theme=dark; Path=/; Max-Age=31536000; SameSite=Lax; Domain=example.org; Secure",
            ServiceWeb.themeCookie(true, "https://meet.example.org/m/1", "example.org"),
        )
        assertEquals(
            "kv-theme=light; Path=/; Max-Age=31536000; SameSite=Lax; Secure",
            ServiceWeb.themeCookie(false, "https://other.example.net/", "example.org"),
        )
        // Напрямую по http — без Secure (иначе браузер куку не примет), только на хост.
        assertEquals(
            "kv-theme=light; Path=/; Max-Age=31536000; SameSite=Lax",
            ServiceWeb.themeCookie(false, "http://10.0.0.5:8080/", null),
        )
        assertTrue(ServiceWeb.themeScript(true).contains("KV.setTheme('dark')"))
        assertTrue(ServiceWeb.themeScript(false).contains("KV.setTheme('light')"))
    }

    @Test
    fun `intent — пакет, схема и адрес на замену`() {
        val jf = ServiceWeb.parseIntent(
            "intent://media.example.org/web/#Intent;scheme=https;package=org.jellyfin.mobile;" +
                "S.browser_fallback_url=https%3A%2F%2Fmedia.example.org%2Fweb%2F%3Fa%3D1%2B2;end",
        )!!
        assertEquals("org.jellyfin.mobile", jf.pkg)
        assertEquals("https", jf.scheme)
        assertEquals("https://media.example.org/web/", jf.data)
        // «+» в процентной записи — плюс, а не пробел.
        assertEquals("https://media.example.org/web/?a=1+2", jf.fallback)

        val sl = ServiceWeb.parseIntent("intent://book?folder=%D0%94%D1%8E%D0%BD%D0%B0#Intent;scheme=slushalka;package=ru.zf.slushalka;end")!!
        assertEquals("ru.zf.slushalka", sl.pkg)
        assertEquals("slushalka://book?folder=%D0%94%D1%8E%D0%BD%D0%B0", sl.data)
        assertNull(sl.fallback)

        // Замена только http(s): javascript: в fallback не пускаем.
        assertNull(ServiceWeb.parseIntent("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end")!!.fallback)
        // Кривой пакет — как будто его нет.
        assertNull(ServiceWeb.parseIntent("intent://x#Intent;package=../evil;end")!!.pkg)
        assertNull(ServiceWeb.parseIntent("https://example.org/"))
        val bare = ServiceWeb.parseIntent("intent://x")!!
        assertNull(bare.pkg)
        assertNull(bare.fallback)
    }

    @Test
    fun `шапка — Jellyfin у Кино, Слушалка у Книг, у остальных ничего`() {
        assertEquals(ServiceWeb.JELLYFIN_PACKAGE, ServiceWeb.appLink("film")!!.pkg)
        assertTrue(ServiceWeb.appLink("film")!!.webFallback)
        assertEquals("Слушалка", ServiceWeb.appLink("book")!!.label)
        assertFalse(ServiceWeb.appLink("book")!!.webFallback)
        assertNull(ServiceWeb.appLink("candles"))
        val js = ServiceWeb.findIntentScript(ServiceWeb.JELLYFIN_PACKAGE)
        assertTrue(js.contains("package=org.jellyfin.mobile;"))
        assertTrue(runCatching { ServiceWeb.findIntentScript("x');alert(1);('") }.isFailure)
    }

    @Test
    fun `ответ evaluateJavascript — строкой`() {
        assertEquals("intent://a#Intent;end", ServiceWeb.jsString("\"intent://a#Intent;end\""))
        assertNull(ServiceWeb.jsString("null"))
        assertNull(ServiceWeb.jsString(null))
        assertNull(ServiceWeb.jsString("1"))
        assertNull(ServiceWeb.jsString("\"\""))
    }

    @Test
    fun `спрятанная надолго — перечитать, складывание — нет`() {
        assertFalse(ServiceWeb.needsReload(0L, 1_000_000L))
        assertFalse(ServiceWeb.needsReload(1_000L, 1_000L + 2_000L))
        assertTrue(ServiceWeb.needsReload(1_000L, 1_000L + ServiceWeb.STALE_MS))
    }

    @Test
    fun `страница сервиса — из сохранённого состояния`() {
        assertEquals(ServicePage("Кино", "film", "https://kino.example.org/"), ServicePage.of(listOf("Кино", "film", "https://kino.example.org/")))
        assertNull(ServicePage.of(listOf("Кино", "film")))
        assertNull(ServicePage.of(listOf("Кино", "film", "")))
        assertNull(ServicePage.of(null))
    }
}
