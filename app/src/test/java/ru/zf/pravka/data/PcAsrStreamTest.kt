package ru.zf.pravka.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.PcAsr
import ru.zf.pravka.core.PcAsr.Outcome
import java.io.IOException
import java.util.Collections

/**
 * Отправка звука тейка на комп против поддельного «Слуха» (перехватчик OkHttp, без сети):
 * куски по месту в звуке, повтор после обрыва, 409 «шли отсюда», итог и все дороги «Google сразу».
 */
class PcAsrStreamTest {

    /** Как настоящий архив+«Слух»: копит байты по смещению, end — текст. */
    private class FakeHome {
        var have = 0L
        val offs: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        var failNext = 0
        var down = false
        var endDelayMs = 0L
        var endTotal = -1L
        var dropped = false

        fun respond(req: Request): Response {
            if (down) throw IOException("нет связи")
            if (failNext > 0) {
                failNext--
                throw IOException("обрыв")
            }
            val path = req.url.encodedPath
            val body = Buffer().also { req.body?.writeTo(it) }.readByteArray()
            return when (path) {
                "/asr/chunk" -> {
                    val off = req.url.queryParameter("off")!!.toLong()
                    offs += off
                    if (off > have) reply(req, 409, JSONObject().put("have", have))
                    else {
                        have = maxOf(have, off + body.size)
                        reply(req, 200, JSONObject().put("have", have))
                    }
                }
                "/asr/end" -> {
                    endTotal = req.url.queryParameter("total")!!.toLong()
                    if (endDelayMs > 0) Thread.sleep(endDelayMs)
                    if (endTotal > have) reply(req, 409, JSONObject().put("have", have))
                    else reply(req, 200, JSONObject().put("text", "иван привет").put("sec", have / 32000.0).put("ms", 40))
                }
                "/asr/drop" -> {
                    dropped = true
                    reply(req, 200, JSONObject().put("ok", true))
                }
                else -> reply(req, 404, JSONObject())
            }
        }

        private fun reply(req: Request, code: Int, o: JSONObject) = Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(o.toString().toResponseBody("application/json".toMediaType())).build()
    }

    private fun stream(home: FakeHome): PcAsrStream {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { home.respond(it.request()) }).build()
        return PcAsrStream(http, "https://home.example:8443/", "t".repeat(40), "take_1", log = {})
    }

    /** [seconds] звука кусками по 50 мс, как пишет своя запись. */
    private fun speak(s: PcAsrStream, seconds: Double) {
        val piece = ByteArray(1600) { 1 }
        repeat((seconds * 20).toInt()) { s.write(piece, piece.size) }
    }

    @Test
    fun `звук уходит по месту, на стоп — хвост и итог`() = runBlocking {
        val home = FakeHome()
        val s = stream(home)
        speak(s, 2.5)
        s.close(complete = true)
        val r = s.await(5_000)
        assertEquals(Outcome.OK, r.outcome)
        assertEquals("иван привет", r.text)
        assertEquals(80_000L, home.have)
        assertEquals(80_000L, home.endTotal)
        // места растут без дыр: каждый следующий кусок — с того, что уже принято
        val o = home.offs.toList()
        assertEquals(0L, o.first())
        assertTrue(o.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `обрыв на куске — повтор, итог цел`() = runBlocking {
        val home = FakeHome().apply { failNext = 1 }
        val s = stream(home)
        speak(s, 1.5)
        s.close(complete = true)
        val r = s.await(5_000)
        assertEquals(Outcome.OK, r.outcome)
        assertEquals(48_000L, home.have)
    }

    @Test
    fun `дома нет связи — Google сразу, это не застревание`() = runBlocking {
        val home = FakeHome().apply { down = true }
        val s = stream(home)
        speak(s, 1.2)
        s.close(complete = true)
        val t0 = System.currentTimeMillis()
        val r = s.await(10_000)
        assertEquals(Outcome.UNAVAILABLE, r.outcome)
        assertTrue("не ждали весь бюджет", System.currentTimeMillis() - t0 < 5_000)
    }

    @Test
    fun `комп считает дольше бюджета — застрял`() = runBlocking {
        val home = FakeHome().apply { endDelayMs = 1_500 }
        val s = stream(home)
        speak(s, 1.1)
        s.close(complete = true)
        val r = s.await(300)
        assertEquals(Outcome.STUCK, r.outcome)
    }

    @Test
    fun `запись брошена до стопа — звук неполный`() = runBlocking {
        val s = stream(FakeHome())
        speak(s, 0.5)
        s.close(complete = false)
        assertEquals(Outcome.INCOMPLETE, s.await(1_000).outcome)
    }

    @Test
    fun `своей записи не было — нет компа, без ожидания`() = runBlocking {
        val s = stream(FakeHome())
        assertEquals(Outcome.UNAVAILABLE, s.await(5_000).outcome)
    }

    @Test
    fun `отмена — комп забывает тейк`() = runBlocking {
        val home = FakeHome()
        val s = stream(home)
        speak(s, 1.2)
        s.cancel()
        assertEquals(Outcome.CANCELLED, s.await(1_000).outcome)
        val until = System.currentTimeMillis() + 2_000
        while (!home.dropped && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(home.dropped)
    }

    @Test
    fun `отправка отстала больше чем на 10 с — бросаем, Google`() = runBlocking {
        val home = FakeHome().apply { endDelayMs = 0 }
        // сервер «висит» на каждом куске
        val slow = OkHttpClient.Builder().addInterceptor(Interceptor {
            Thread.sleep(3_000)
            home.respond(it.request())
        }).build()
        val s = PcAsrStream(slow, "https://home.example:8443/", "t".repeat(40), "take_2", log = {})
        speak(s, PcAsr.BEHIND_MAX_BYTES / PcAsr.BYTES_PER_SEC + 1.5)
        assertEquals(Outcome.INCOMPLETE, s.await(100).outcome)
    }
}
