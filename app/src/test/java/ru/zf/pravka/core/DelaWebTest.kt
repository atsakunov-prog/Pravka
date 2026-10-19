package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дела в виде Todoist на телефоне (19.10.2026): адрес страницы, когда показывать новый вид и кому мостик отдаёт токен.
 * Адреса выдуманные (`example.org`): настоящих в публичном репозитории нет — адрес Дел приходит QR-кодом.
 */
class DelaWebTest {

    private val base = "https://dela.example.org:8443/"

    @Test
    fun `адрес страницы — от адреса сервера`() {
        assertEquals("https://dela.example.org:8443/t", DelaWeb.pageUrl(base))
        assertEquals("https://dela.example.org/t", DelaWeb.pageUrl("https://dela.example.org"))
    }

    @Test
    fun `новый вид — только со своим сервером, умеющим t, и не выключенный`() {
        assertTrue(DelaWeb.show(onServer = true, linked = true, features = listOf("todo", "debug"), wanted = true))
        assertFalse(DelaWeb.show(onServer = true, linked = true, features = listOf("debug"), wanted = true)) // старый сервер
        assertFalse(DelaWeb.show(onServer = true, linked = true, features = listOf("todo"), wanted = false)) // выключил
        assertFalse(DelaWeb.show(onServer = true, linked = false, features = listOf("todo"), wanted = true)) // нет QR
        assertFalse(DelaWeb.show(onServer = false, linked = true, features = listOf("todo"), wanted = true)) // Todoist
    }

    @Test
    fun `токен — только странице Дел своего сервера`() {
        assertEquals("tok", DelaWeb.tokenFor("https://dela.example.org:8443/t#/today", base, "tok"))
        assertNull(DelaWeb.tokenFor("https://kino.example.org/", base, "tok")) // другой сервис Ковчега
        assertNull(DelaWeb.tokenFor("https://dela.example.org/t", base, "tok")) // другой порт
        assertNull(DelaWeb.tokenFor("http://dela.example.org:8443/t", base, "tok")) // другая схема
        assertNull(DelaWeb.tokenFor("https://dela.example.org.evil.test:8443/t", base, "tok"))
        assertNull(DelaWeb.tokenFor(null, base, "tok"))
        assertNull(DelaWeb.tokenFor("https://dela.example.org:8443/t", base, ""))
        assertNull(DelaWeb.tokenFor("about:blank", base, "tok"))
    }

    @Test
    fun `порт по умолчанию — по схеме`() {
        assertTrue(DelaWeb.sameOrigin("https://a.example.org/t", "https://A.example.org:443/"))
        assertFalse(DelaWeb.sameOrigin("https://a.example.org/t", "https://a.example.org:8443/"))
    }
}
