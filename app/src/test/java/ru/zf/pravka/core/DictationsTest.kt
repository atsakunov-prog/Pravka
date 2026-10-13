package ru.zf.pravka.core

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * История диктовок экрана «Правка» (Правка 5.0, этап 5): склейка тейка телефона с его
 * чисткой, разбор диктовок компа из архива (`server/contract/diktovka.json`), фильтр, поиск,
 * итоги дня, подписи.
 */
class DictationsTest {

    private val msk = ZoneId.of("Europe/Moscow")

    private fun take(ts: String, text: String, mic: String? = null, ok: Boolean = true, audio: String? = null, engine: String = "google") =
        JSONObject().put("ts", ts).put("engine", engine).put("audio_ms", 9_000L).put("text", text).put("ok", ok)
            .apply { if (mic != null) put("mic", mic); if (audio != null) put("audio", audio); if (!ok) put("error", "пустой результат") }

    private fun clean(ts: String, input: String, output: String, mode: String = "CLEAN", cost: Double = 0.0021) =
        JSONObject().put("ts", ts).put("mode", mode).put("model", "claude-sonnet-5-5").put("input", input)
            .put("output", output).put("latency_ms", 1400L).put("cost_usd", cost)

    @Test
    fun `тейк находит свою чистку по времени и словам, чужую не берёт`() {
        val takes = listOf(
            take("2026-10-13T12:44:05+03:00", "привет напомни во сколько завтра встреча", mic = "гарнитура «OpenComm2 by Shokz_II»", audio = "take-1.wav"),
            take("2026-10-13T12:50:00+03:00", "купить молоко и хлеб"),            // ушло в Засечку: чистки нет
            take("2026-10-13T13:00:00+03:00", "", ok = false),
        ).mapNotNull(Dictations::takeOf)
        val cleans = listOf(
            clean("2026-10-13T12:44:07+03:00", "привет напомни во сколько завтра встреча", "Привет! Напомни, во сколько завтра встреча?"),
            // Сразу после второго тейка, но про другое (вставленный текст из буфера) — не его.
            clean("2026-10-13T12:50:02+03:00", "совсем другой текст из буфера", "Совсем другой текст."),
            clean("2026-10-13T12:50:03+03:00", "ассистент", "ответ", mode = "ASSIST_REPLY"),
        ).mapNotNull(Dictations::cleanOf)
        val items = Dictations.phone(takes, cleans)
        assertEquals(3, items.size)
        val first = items[0]
        assertEquals("Привет! Напомни, во сколько завтра встреча?", first.wrote)
        assertEquals(Dictations.Origin.HEADSET, first.origin)
        assertEquals("Shokz", first.device)
        assertEquals("claude-sonnet-5-5", first.cleanModel)
        assertEquals(0.0021, first.cleanUsd, 1e-9)
        assertEquals("take-1.wav", first.audio)
        assertNull(items[1].wrote)
        assertEquals("купить молоко и хлеб", items[1].text)
        assertEquals(Dictations.Origin.PHONE, items[1].origin)
        assertFalse(items[2].ok)
        assertEquals("пустой результат", items[2].error)
    }

    @Test
    fun `одна чистка — одному тейку`() {
        val takes = listOf(
            take("2026-10-13T10:00:00+03:00", "созвон перенести на четверг"),
            take("2026-10-13T10:00:20+03:00", "созвон перенести на четверг"),
        ).mapNotNull(Dictations::takeOf)
        val cleans = listOf(clean("2026-10-13T10:00:02+03:00", "созвон перенести на четверг", "Созвон перенести на четверг."))
            .mapNotNull(Dictations::cleanOf)
        val items = Dictations.phone(takes, cleans)
        assertEquals(1, items.count { it.wrote != null })
        assertEquals("Созвон перенести на четверг.", items.first().wrote)
    }

    @Test
    fun `диктовки компа из контракта сервера`() {
        val f = listOf(File("../server/contract/diktovka.json"), File("server/contract/diktovka.json")).first { it.isFile }
        val events = JSONObject(f.readText()).getJSONArray("events")
        val takes = HashMap<String, JSONObject>()
        val cleans = HashMap<String, JSONObject>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            (if (e.getString("kind") == "pravka.take") takes else cleans)[e.getString("key")] = e.getJSONObject("data")
        }
        val items = Dictations.pc(takes, cleans).sortedBy { it.atMs }
        assertEquals(2, items.size)
        val a = items[0]
        assertEquals(Dictations.Origin.PC, a.origin)
        assertEquals("компьютер", a.device)
        assertTrue(a.ok)
        assertTrue(a.wrote!!.startsWith("Коллеги, добрый день!"))
        assertEquals("claude-sonnet-5-5", a.cleanModel)
        assertEquals("pasted", a.status)
        assertEquals(12_400L, a.audioMs)
        assertEquals("GigaAM-v3, по ходу речи", Dictations.engineLabel(a.engine))
        assertEquals(LocalDate.of(2026, 10, 13), Dictations.dayOf(a.atMs, msk))
        val b = items[1]
        assertFalse(b.ok)
        assertNull(b.wrote)
        assertEquals("ничего не разобрал", b.error)
    }

    @Test
    fun `без Сонета у компа записалось то, что ушло в окно`() {
        val t = JSONObject().put("id", 1791889200123L).put("at", "2026-10-13T14:00:00.123+03:00").put("origin", "pc")
            .put("engine", "gigaam cpu").put("audio_ms", 5000).put("text", "Прикинь что вышло").put("ok", true)
            .put("status", "pasted").put("final", "Прикинь, что вышло")
        val i = Dictations.pc(mapOf("pc-1791889200123" to t), emptyMap()).single()
        assertEquals("Прикинь, что вышло", i.wrote)
        assertNull(i.cleanModel)
    }

    @Test
    fun `фильтр, поиск без регистра и с ё, итоги дня`() {
        val day = LocalDate.of(2026, 10, 13)
        val at = day.atTime(12, 0).atZone(msk).toInstant().toEpochMilli()
        val items = listOf(
            Dictations.Item("a", at, Dictations.Origin.PHONE, "телефон", "google", 60_000, "ещё раз про отчёт", "Ещё раз про отчёт.", true),
            Dictations.Item("b", at + 1, Dictations.Origin.HEADSET, "Shokz", "google", 30_000, "", null, false, error = "пустой результат"),
            Dictations.Item("c", at + 2, Dictations.Origin.PC, "компьютер", "gigaam cpu", 90_000, "модель по кварталу", "Модель по кварталу.", true),
            Dictations.Item("d", at - 86_400_000, Dictations.Origin.PC, "компьютер", "gigaam cpu", 10_000, "вчера", null, true),
        )
        assertEquals(listOf("a", "b"), Dictations.view(items, Dictations.Filter.PHONE).map { it.key })
        assertEquals(listOf("c", "d"), Dictations.view(items, Dictations.Filter.PC).map { it.key })
        assertEquals(listOf("a"), Dictations.view(items, Dictations.Filter.ALL, "ЕЩЕ РАЗ").map { it.key })
        assertEquals(listOf("c"), Dictations.view(items, Dictations.Filter.ALL, "кварталу.").map { it.key })
        val st = Dictations.stats(items, day, msk)
        assertEquals(Dictations.DayStats(3, 180_000, 1), st)
        val days = Dictations.byDay(Dictations.merge(items), msk)
        assertEquals(listOf(day, day.minusDays(1)), days.map { it.first })
        assertEquals(listOf("c", "b", "a"), days[0].second.map { it.key })
    }

    @Test
    fun `подписи — гарнитура, движок, цена, длина`() {
        assertEquals(Dictations.Origin.PHONE to "телефон", Dictations.deviceOf("телефон"))
        assertEquals(Dictations.Origin.PHONE to "телефон", Dictations.deviceOf(null))
        assertEquals(Dictations.Origin.HEADSET to "Shokz", Dictations.deviceOf("телефон → гарнитура «OpenComm2 by Shokz_II»"))
        assertEquals(Dictations.Origin.HEADSET to "Pixel Buds", Dictations.deviceOf("гарнитура «Pixel Buds»"))
        assertEquals(Dictations.Origin.HEADSET to "гарнитура", Dictations.deviceOf("гарнитура"))
        assertEquals("Google, по сети", Dictations.engineLabel("google-net"))
        assertEquals("Whisper small, видеокарта, по ходу речи", Dictations.engineLabel("small cuda по ходу"))
        assertEquals("Groq large-v3", Dictations.engineLabel("whisper-large-v3"))
        assertEquals("\$0,011", Dictations.usd(0.011))
        assertEquals("\$0,02", Dictations.usd(0.0204))
        assertEquals("\$0,003", Dictations.usd(0.00265))
        assertEquals("<\$0,001", Dictations.usd(0.0002))
        assertNull(Dictations.usd(0.0))
        assertEquals("15 с", Dictations.duration(15_200))
        assertEquals("3 мин 55 с", Dictations.duration(235_000))
        assertEquals("2 мин", Dictations.duration(120_000))
        assertEquals("1 ч 2 мин", Dictations.duration(3_725_000))
        assertEquals("оставлено в буфере", Dictations.statusLabel("clipboard"))
        assertNull(Dictations.statusLabel(null))
    }

    @Test
    fun `движки телефона — те же слова, что в настройках`() {
        // Подписи живут в core без Android, а значения — в Settings: разъедутся — тест скажет.
        assertEquals("google", ru.zf.pravka.data.Settings.SPEECH_GOOGLE)
        assertEquals("google-net", ru.zf.pravka.data.Settings.SPEECH_GOOGLE_NET)
        assertEquals("google-replay", ru.zf.pravka.data.Settings.SPEECH_GOOGLE_REPLAY)
        assertEquals("whisper-small", ru.zf.pravka.data.Settings.SPEECH_WHISPER_SMALL)
        assertEquals("whisper-base", ru.zf.pravka.data.Settings.SPEECH_WHISPER_BASE)
    }
}
