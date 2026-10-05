package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.slushalka.data.Cloud

/**
 * MOVE через роутер домашнего сервера: полный адрес в `Destination` получал
 * 502, и все записи телефона застревали в `.partial`. Форма и повтор
 * ломаются молча - места просто перестают доезжать, - поэтому проверяются.
 */
class CloudMoveTest {

    private val full = "https://books.example.ru/%D0%9A%D0%BD%D0%B8%D0%B3%D0%B8/a%20b/x.json"

    @Test
    fun destinationIsPathFromServerRoot() {
        assertEquals("/%D0%9A%D0%BD%D0%B8%D0%B3%D0%B8/a%20b/x.json", Cloud.pathOf(full))
        // Облако не в корне сервера: путь - от корня сервера, с папкой WebDAV.
        assertEquals("/dav/_Слушалка/места.json", Cloud.pathOf("https://h:8443/dav/_Слушалка/места.json"))
        assertEquals("/", Cloud.pathOf("https://h"))
    }

    @Test
    fun pathGoesFirstUnlessFullWorkedBefore() {
        assertEquals(listOf(Cloud.pathOf(full), full), Cloud.destinations(full, preferFull = false))
        assertEquals(listOf(full, Cloud.pathOf(full)), Cloud.destinations(full, preferFull = true))
    }

    @Test
    fun badGatewayTriesTheOtherForm() {
        val sent = ArrayList<String>()
        // Прежде сработал полный адрес, а теперь роутер на него отвечает 502.
        val out = Cloud.moveVia(Cloud.destinations(full, preferFull = true)) { d ->
            sent += d
            if (d == full) 502 else 201
        }
        assertEquals(Cloud.pathOf(full), out.form)
        assertEquals(listOf(full, Cloud.pathOf(full)), sent)
        assertEquals(2, out.tries)
    }

    @Test
    fun pathWorksAtOnce() {
        val out = Cloud.moveVia(Cloud.destinations(full, preferFull = false)) { 204 }
        assertEquals(Cloud.pathOf(full), out.form)
        assertEquals(1, out.tries)
    }

    @Test
    fun bothFormsFailThenNoForm() {
        val out = Cloud.moveVia(Cloud.destinations(full, preferFull = false)) { 502 }
        assertNull(out.form)
        assertEquals(502, out.code)
        assertEquals(2, out.tries)
        // Обрыв соединения - тоже повод попробовать вторую форму.
        val torn = Cloud.moveVia(Cloud.destinations(full, preferFull = false)) { d ->
            if (d == full) 201 else Cloud.NO_ANSWER
        }
        assertEquals(full, torn.form)
    }

    @Test
    fun errorsNotAboutFormDoNotRepeat() {
        var calls = 0
        val out = Cloud.moveVia(Cloud.destinations(full, preferFull = false)) { calls++; 404 }
        assertNull(out.form)
        assertEquals(1, calls)
        assertEquals(404, out.code)
    }
}
