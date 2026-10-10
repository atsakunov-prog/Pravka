package ru.zf.pravka.core

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// «Где мы» (10.10.2026): лестница цены точки — лежит телефон, едет, просили
// обновить; какая точка лучше; когда отправлять; формат файла в облаке.
class WherePolicyTest {

    private val min = 60_000L
    private val zone = ZoneId.of("Europe/Moscow")
    private val t0 = LocalDateTime.of(2026, 10, 10, 15, 0).atZone(zone).toInstant().toEpochMilli()

    /** Кутузовский и Летово — для расстояний на глаз. */
    private fun home(at: Long, acc: Float = 20f, src: String = "fused") = WhereFix(55.7447, 37.5372, acc, at, src)
    private fun letovo(at: Long, acc: Float = 20f) = WhereFix(55.5946, 37.4116, acc, at, "fused")

    // ---- Когда искать свою точку ----

    @Test
    fun `лежит телефон — свою точку не ищем раньше часа`() {
        val fix = home(t0 - 40 * min)
        assertEquals(WherePolicy.Need.NONE, WherePolicy.need(t0, fix, motionAt = 0L, askAt = 0L, preciseFor = 0L, triedAt = 0L))
        assertEquals(
            WherePolicy.Need.BALANCED,
            WherePolicy.need(t0, home(t0 - 61 * min), motionAt = 0L, askAt = 0L, preciseFor = 0L, triedAt = 0L),
        )
    }

    @Test
    fun `поехал — Wi-Fi и вышки раз в пять минут, без GPS`() {
        val motion = t0 - 2 * min
        assertEquals(WherePolicy.Need.BALANCED, WherePolicy.need(t0, home(t0 - 6 * min), motion, 0L, 0L, 0L))
        assertEquals(WherePolicy.Need.NONE, WherePolicy.need(t0, home(t0 - 3 * min), motion, 0L, 0L, 0L))
        // Толчок был давно — снова «лежит».
        assertEquals(WherePolicy.Need.NONE, WherePolicy.need(t0, home(t0 - 6 * min), t0 - 20 * min, 0L, 0L, 0L))
    }

    @Test
    fun `просили обновить — свой GPS один раз на просьбу`() {
        val ask = t0 - 2 * min
        val fix = home(t0 - 30 * min)
        assertEquals(WherePolicy.Need.PRECISE, WherePolicy.need(t0, fix, 0L, ask, preciseFor = 0L, triedAt = t0 - min))
        // На эту просьбу GPS уже был — второй раз не включаем.
        assertEquals(WherePolicy.Need.NONE, WherePolicy.need(t0, fix, 0L, ask, preciseFor = ask, triedAt = t0 - min))
        // Точка свежее просьбы — отвечать уже нечем лучше.
        assertEquals(WherePolicy.Need.NONE, WherePolicy.need(t0, home(t0 - min), 0L, ask, preciseFor = 0L, triedAt = t0 - min))
        // Просьба старше получаса — не будит.
        assertEquals(WherePolicy.Need.NONE, WherePolicy.need(t0, fix, 0L, t0 - 40 * min, preciseFor = 0L, triedAt = t0 - min))
    }

    @Test
    fun `неудача не молотит — между попытками четыре минуты`() {
        assertEquals(WherePolicy.Need.NONE, WherePolicy.need(t0, null, 0L, 0L, 0L, triedAt = t0 - 2 * min))
        assertEquals(WherePolicy.Need.BALANCED, WherePolicy.need(t0, null, 0L, 0L, 0L, triedAt = t0 - 5 * min))
    }

    // ---- Какая точка лучше ----

    @Test
    fun `грубая точка по вышкам не затирает свежий GPS`() {
        val gps = home(t0, acc = 8f, src = "gps")
        val cell = WhereFix(55.75, 37.55, 1500f, t0 + 30_000L, "network")
        assertFalse(WherePolicy.better(cell, gps))
        // А через десять минут — уже да: прежняя устарела.
        assertTrue(WherePolicy.better(cell.copy(at = t0 + 11 * min), gps))
        // Уехал дальше обоих кругов — тоже да.
        assertTrue(WherePolicy.better(WhereFix(55.5946, 37.4116, 1500f, t0 + min, "network"), gps))
        // Старая точка никогда не лучше новой.
        assertFalse(WherePolicy.better(home(t0 - min), gps))
        assertTrue(WherePolicy.better(home(t0 + min, acc = 10f), gps))
    }

    // ---- Когда отправлять ----

    @Test
    fun `сдвиг меньше шестидесяти метров не отправляется, переезд — сразу`() {
        val sent = home(t0 - 10 * min)
        val near = WhereFix(sent.lat + 0.0002, sent.lon, 20f, t0, "fused")
        assertFalse(WherePolicy.shouldSend(t0, near, sent, t0 - 10 * min, 0L, false))
        assertTrue(WherePolicy.shouldSend(t0, letovo(t0), sent, t0 - 10 * min, 0L, false))
    }

    @Test
    fun `навигатор не заваливает облако — не чаще двух минут`() {
        val sent = home(t0 - min)
        assertFalse(WherePolicy.shouldSend(t0, letovo(t0), sent, t0 - min, 0L, false))
    }

    @Test
    fun `ответ на просьбу уходит без паузы, «я здесь» — раз в полчаса`() {
        val sent = home(t0 - min)
        val ask = t0 - 30_000L
        assertTrue(WherePolicy.shouldSend(t0, home(t0), sent, t0 - min, ask, false))
        val still = home(t0 - 50 * min)
        assertTrue(WherePolicy.shouldSend(t0, still, still, t0 - 31 * min, 0L, false))
        assertFalse(WherePolicy.shouldSend(t0, still, still, t0 - 20 * min, 0L, false))
        // Пришёл домой (место по Wi-Fi сменилось) — видно на карте, отправляем.
        assertTrue(WherePolicy.shouldSend(t0, still, still, t0 - 20 * min, 0L, true))
        assertFalse(WherePolicy.shouldSend(t0, null, null, 0L, 0L, true))
    }

    // ---- Что видно на карте ----

    @Test
    fun `лежащий телефон верен на момент последнего «я здесь»`() {
        val b = beacon(home(t0 - 50 * min), sentAt = t0 - 5 * min)
        assertEquals(t0 - 5 * min, WherePolicy.seenAt(b))
        assertEquals(t0 - 50 * min, WherePolicy.seenAt(b.copy(moving = true)))
        assertEquals(1f, WherePolicy.alpha(t0, t0 - 5 * min))
        assertEquals(0.45f, WherePolicy.alpha(t0, t0 - 5 * 60 * min))
    }

    @Test
    fun `сколько назад — словами`() {
        assertEquals("только что", WherePolicy.ago(t0, t0 - 30_000L, zone))
        assertEquals("7 мин назад", WherePolicy.ago(t0, t0 - 7 * min, zone))
        assertEquals("в 13:05", WherePolicy.ago(t0, t0 - 115 * min, zone))
        assertEquals("вчера в 22:10", WherePolicy.ago(t0, t0 - (16 * 60 + 50) * min, zone))
        assertEquals("08.10 в 15:00", WherePolicy.ago(t0, t0 - 2 * 24 * 60 * min, zone))
        assertEquals("±20 м", WherePolicy.accuracy(20.4f))
        assertEquals("±1,5 км", WherePolicy.accuracy(1500f))
        assertEquals("±2 км", WherePolicy.accuracy(2000f))
    }

    @Test
    fun `строка под именем`() {
        val b = beacon(home(t0 - 7 * min), sentAt = t0 - 7 * min).copy(place = "Дом", battery = 64)
        assertEquals("Дом · 7 мин назад · ±20 м · 64%", WherePolicy.line(t0, b, zone))
        assertEquals("в пути · 7 мин назад · ±20 м · 64% ⚡", WherePolicy.line(t0, b.copy(place = "", moving = true, charging = true), zone))
    }

    @Test
    fun `расстояние Кутузовский — Летово около восемнадцати км`() {
        val d = WherePolicy.distanceM(home(t0), letovo(t0))
        assertTrue("$d", d in 17_000.0..20_000.0)
    }

    @Test
    fun `цвет человека один на всех телефонах`() {
        assertEquals(0xFFFF8A3D, WherePolicy.color("sasha"))
        assertEquals(WherePolicy.color("borya"), WherePolicy.color("borya"))
    }

    // ---- Файл в облаке ----

    @Test
    fun `точка туда и обратно`() {
        val b = beacon(home(t0, acc = 12.34f), sentAt = t0 + 1000).copy(battery = 64, charging = true, place = "Дом", avatarAt = 77L)
        val back = WherePolicy.fromJson(WherePolicy.toJson(b))
        assertNotNull(back)
        assertEquals(b.copy(fix = b.fix.copy(acc = 12.3f)), back)
    }

    @Test
    fun `чужой или битый файл — не точка`() {
        assertNull(WherePolicy.fromJson("{}"))
        assertNull(WherePolicy.fromJson("""{"device":"x","lat":91,"lon":0}"""))
        assertNull(WherePolicy.fromJson("не json"))
        // Новые поля будущего формата не мешают.
        assertNotNull(WherePolicy.fromJson("""{"v":2,"device":"x-1","lat":55.7,"lon":37.5,"at":1,"sent":2,"новое":true}"""))
    }

    @Test
    fun `имена файлов в папке Где`() {
        assertEquals("sasha-3f9a2c" to WherePolicy.Kind.BEACON, WherePolicy.parseName("sasha-3f9a2c.json"))
        assertEquals("sasha-3f9a2c" to WherePolicy.Kind.ASK, WherePolicy.parseName("sasha-3f9a2c.ask.json"))
        assertEquals("marianna-01ab9e" to WherePolicy.Kind.AVATAR, WherePolicy.parseName("marianna-01ab9e.jpg"))
        assertNull(WherePolicy.parseName("readme.txt"))
        assertNull(WherePolicy.parseName(".json"))
        assertEquals(1234L, WherePolicy.askAt(WherePolicy.askJson(1234L, "Саша")))
    }

    private fun beacon(fix: WhereFix, sentAt: Long) = WhereBeacon(
        device = "sasha-3f9a2c", person = "sasha", name = "Саша", fix = fix, sentAt = sentAt,
    )
}
