package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.zf.pravka.core.OverlaySight.Box
import ru.zf.pravka.core.OverlaySight.Look
import ru.zf.pravka.core.OverlaySight.Step

// Видит ли система наши окна (28.09.2026: после складывания Fold на Android
// 17 окна диска висели, а на экране их не было — лечил только перезапуск
// службы). Числа — с телефона владельца: разложенный экран 2076x2152,
// кнопка 131 px (убранный диск ставит «П» на x=1945 — ровно в край), стекло 438 px.
class OverlaySightTest {

    private val w = 2076
    private val h = 2152

    private fun button(x: Int, y: Int) = Box(x, y, x + 131, y + 131)

    // Убранный диск у правого края: «П» и «З» выдавлены, стекло за краем наполовину.
    private val tucked = listOf(
        Box(1883, 376, 1883 + 438, 376 + 438),
        button(1945, 466),
        button(1945, 612),
    )

    @Test
    fun `система показывает всё - видно всё`() {
        val overlays = listOf(
            Box(1883, 376, 2076, 814),   // стекло, обрезанное краем экрана
            button(1945, 466),
            button(1945, 612),
        )
        assertEquals(3, OverlaySight.expected(tucked, w, h))
        assertEquals(3, OverlaySight.seen(tucked, overlays, w, h, slack = 4))
    }

    @Test
    fun `окна висят, а в списке системы их нет - не видно ни одного`() {
        // Чужие окна в списке есть (приложение, строка состояния), наших — нет.
        assertEquals(0, OverlaySight.seen(tucked, emptyList(), w, h, slack = 4))
    }

    @Test
    fun `диск едет к краю - узнаём по размеру, не по месту`() {
        // Список окон у системы на кадр-другой позади: кнопки ещё на 60 px левее.
        val overlays = listOf(button(1885, 466), button(1885, 612))
        assertEquals(2, OverlaySight.seen(tucked, overlays, w, h, slack = 4))
    }

    @Test
    fun `одно окно системы - одна наша кнопка`() {
        // Пять кнопок одного размера не «находятся» в одной.
        val ours = List(5) { i -> button(100, 100 + 200 * i) }
        val overlays = listOf(button(100, 300))
        assertEquals(1, OverlaySight.seen(ours, overlays, w, h, slack = 4))
    }

    @Test
    fun `чужой оверлей другого размера за наш не считается`() {
        val overlays = listOf(Box(0, 0, w, h), Box(0, 0, 131, 60))
        assertEquals(0, OverlaySight.seen(tucked, overlays, w, h, slack = 4))
    }

    @Test
    fun `окно наполовину за краем - сверяем обрезанное с обрезанным`() {
        // Система называет только видимую часть окна; наша — обрезается так же.
        val ours = listOf(button(2000, 900))
        val overlays = listOf(Box(2000, 900, 2076, 1031))
        assertEquals(1, OverlaySight.seen(ours, overlays, w, h, slack = 4))
    }

    @Test
    fun `окно целиком за краем - не в счёт ни там, ни там`() {
        val ours = listOf(button(2100, 500), button(1945, 466))
        assertEquals(1, OverlaySight.expected(ours, w, h))
        assertEquals(1, OverlaySight.seen(ours, listOf(button(1945, 466)), w, h, slack = 4))
    }

    // ---- Что делать дальше ----

    private val hidden = Look(expected = 3, seen = 0, listed = 12)

    private fun next(
        look: Look = hidden,
        cleanStart: Boolean = false,
        blind: Boolean = false,
        rehung: Boolean = true,
        trusted: Boolean = true,
        idle: Boolean = true,
        since: Long = Long.MAX_VALUE,
        later: Int = 0,
        confirmed: Boolean = true,
    ) = OverlaySight.next(look, cleanStart, blind, rehung, trusted, idle, since, later, confirmed)

    @Test
    fun `видно хоть одно - всё в порядке`() {
        assertEquals(Step.FINE, next(look = Look(3, 1, 12), rehung = false))
        assertEquals(Step.FINE, next(look = Look(0, 0, 12), rehung = false))
    }

    @Test
    fun `система не назвала ни одного окна - взгляд не состоялся`() {
        assertEquals(Step.UNKNOWN, next(look = Look(3, 0, 0), rehung = false))
    }

    @Test
    fun `на чистом старте не вижу - слепа сама проверка, ничего не трогаем`() {
        // Окна только что повешены при открытом телефоне: они на экране.
        assertEquals(Step.BLIND, next(cleanStart = true, rehung = false, trusted = false))
        // И дальше в эту жизнь службы её «не вижу» не значит ничего.
        assertEquals(Step.BLIND, next(blind = true, rehung = false))
        assertEquals(Step.BLIND, next(blind = true))
        // А увиденное на старте — нормальный итог.
        assertEquals(Step.FINE, next(look = Look(6, 6, 14), cleanStart = true, trusted = false))
    }

    @Test
    fun `первое не вижу - перевесить, а не перезапускать`() {
        assertEquals(Step.REHANG, next(rehung = false))
        // Даже с недоверенной проверкой: перевешивание безвредно.
        assertEquals(Step.REHANG, next(rehung = false, trusted = false))
    }

    @Test
    fun `не видно и после перевешивания - сперва ещё взгляд, потом перезапуск`() {
        // Переход складывания мог ещё идти: одно «не вижу» после перевешивания — не приговор.
        assertEquals(Step.CONFIRM, next(confirmed = false))
        assertEquals(Step.RESTART, next(confirmed = true))
    }

    @Test
    fun `проверка ни разу не видела наших окон - не перезапускать, а сказать`() {
        assertEquals(Step.GIVE_UP, next(trusted = false))
        // Но и слова — только после подтверждения.
        assertEquals(Step.CONFIRM, next(trusted = false, confirmed = false))
    }

    @Test
    fun `перезапуск только что был - не петля, а слова`() {
        assertEquals(Step.GIVE_UP, next(since = 5 * 60_000L))
        assertEquals(Step.RESTART, next(since = OverlaySight.RESTART_GAP_MS))
    }

    @Test
    fun `идёт работа - ждать, но не вечно`() {
        assertEquals(Step.LATER, next(idle = false, later = 0))
        assertEquals(Step.LATER, next(idle = false, later = OverlaySight.MAX_LATER - 1))
        assertEquals(Step.GIVE_UP, next(idle = false, later = OverlaySight.MAX_LATER))
    }
}
