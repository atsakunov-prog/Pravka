package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скрипт веба Дел внутри вкладки: токен — только своему адресу и только
 * строкой, голос — через мост. Правило «только этот адрес» строится из
 * адреса QR: ошибись источник — токен не дойдёт, или дойдёт чужим.
 */
class DelaWebScriptTest {

    @Test
    fun `источник адреса — схема, хост и нестандартный порт`() {
        assertEquals("https://dela.example.netcraze.pro:8443", DelaWebScript.origin("https://dela.example.netcraze.pro:8443/"))
        assertEquals("https://dela.example.am", DelaWebScript.origin("https://Dela.Example.am:443/api/x"))
        assertEquals("http://192.168.1.77:8102", DelaWebScript.origin("http://192.168.1.77:8102"))
        assertNull(DelaWebScript.origin("pravka-dela:{}"))
        assertNull(DelaWebScript.origin("ftp://x.ru"))
        assertNull(DelaWebScript.origin(""))
    }

    @Test
    fun `токен — строкой JSON, кавычка не становится кодом`() {
        val js = DelaWebScript.bootstrap("abc\"; alert(1); //")
        assertTrue(js.contains("var token = \"abc\\\"; alert(1); //\";"))
        assertTrue(js.contains("'Authorization', 'Bearer ' + token"))
        // Токен — только своим путям: относительный или тот же origin.
        assertTrue(js.contains("url.charAt(0) === '/'"))
        assertTrue(js.contains("location.origin + '/'"))
        // Голос — через мост Правки вместо распознавания браузера.
        assertTrue(js.contains("window." + DelaWebScript.BRIDGE))
        assertTrue(js.contains("window.webkitSpeechRecognition = Rec"))
        assertFalse(js.contains("abc\"; alert(1)\n"))
    }

    @Test
    fun `сказанное уходит в страницу строкой`() {
        assertEquals("window.__pravkaSaid && window.__pravkaSaid(\"на \\\"пятницу\\\"\")", DelaWebScript.said("на \"пятницу\""))
        assertEquals("window.__pravkaSaid && window.__pravkaSaid(\"\")", DelaWebScript.said(""))
    }
}
