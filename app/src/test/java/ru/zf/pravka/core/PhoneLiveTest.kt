package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон в ленте вживую (13.10.2026). Владелец: «если больше семи минут —
 * каждую минуту скрин, и на десятой: кажется, это „дело“, галка и крестик…
 * нажал „да“ — перестаёт делать скрины… крестик — перестаёт следить… после
 * „да“ другое приложение — снова скрины и снова спрашивает».
 */
class PhoneLiveTest {

    private val min = 60_000L
    private val minMs = 7 * min
    private val t0 = 1_000_000_000L
    private fun at(m: Int, s: Int = 0) = t0 + m * min + s * 1000L

    private val claude = "com.anthropic.claude"
    private val tg = "org.telegram.messenger"
    private val yt = "com.google.android.youtube"

    /** Сидение одним приложением от t0 до [toMin], идёт ли ещё — по [now]. */
    private fun view(pkg: String, toMin: Int, now: Long, fromMin: Int = 0): PhoneLive.View {
        val span = PhoneSit.Span(pkg, at(fromMin), at(toMin))
        val sit = PhoneSit.stretches(listOf(span), now).last()
        return PhoneLive.View(sit, span.end, 0L)
    }

    private val guess = PhoneLive.Guess("Claude: Правка", "Систематизация", "", at(0))

    /** Прогнать минуты подсмотра с [from] по [to] при идущем сидении в [pkg]. */
    private fun minutes(start: PhoneLive.State, pkg: String, from: Int, to: Int): Pair<PhoneLive.State, List<PhoneLive.Do>> {
        var s = start
        val dos = ArrayList<PhoneLive.Do>()
        for (m in from..to) {
            s = PhoneLive.minute(s)
            val step = PhoneLive.pulse(s, view(pkg, m, at(m)), at(m), minMs)
            s = step.state
            dos += step.dos
        }
        return s to dos
    }

    @Test
    fun `до семи минут на экран не смотрим, с седьмой смотрим, на десятой — Сонету`() {
        val (s6, d6) = minutes(PhoneLive.State(), claude, 1, 6)
        assertFalse(s6.watching)
        assertTrue(d6.isEmpty())
        val (s7, _) = minutes(s6, claude, 7, 7)
        assertTrue(s7.watching)
        assertTrue(s7.wantsSnap)
        assertEquals(0, s7.snaps)
        val (s9, d9) = minutes(s7, claude, 8, 9)
        assertEquals(2, s9.snaps)
        assertTrue(d9.isEmpty())
        val (s10, d10) = minutes(s9, claude, 10, 10)
        assertEquals(listOf(PhoneLive.Classify(at(0), at(10))), d10)
        assertTrue(s10.thinking)
        assertFalse(s10.wantsSnap)
        // Сонет думает — второго запроса нет.
        assertTrue(minutes(s10, claude, 11, 11).second.isEmpty())
    }

    private fun asked(): PhoneLive.State {
        val (s, _) = minutes(PhoneLive.State(), claude, 1, 10)
        return PhoneLive.classified(s, guess, setOf(claude), at(10)).state
    }

    @Test
    fun `галочка — дело в ленту, снимков больше нет`() {
        val s = asked()
        assertEquals(guess, s.guess)
        assertFalse(s.wantsSnap)
        val step = PhoneLive.answer(s, yes = true)
        assertEquals(listOf(PhoneLive.Open(guess, setOf(claude), silent = false)), step.dos)
        val on = PhoneLive.opened(step.state, entryId = 42, title = guess.title, apps = setOf(claude), resumeId = 7)
        assertEquals(PhoneLive.Base.ON, on.base)
        assertEquals(7L, on.resumeId)
        // Дальше Клод — ни одного снимка.
        val (later, dos) = minutes(on, claude, 11, 40)
        assertFalse(later.watching)
        assertTrue(dos.isEmpty())
    }

    @Test
    fun `молчание на вопрос — да`() {
        val s = asked()
        val step = PhoneLive.pulse(s, view(claude, 11, at(11)), at(10, 40), minMs)
        assertEquals(listOf(PhoneLive.Open(guess, setOf(claude), silent = true)), step.dos)
    }

    @Test
    fun `крестик — молчим, пока впереди те же приложения`() {
        val off = PhoneLive.answer(asked(), yes = false).state
        assertEquals(PhoneLive.Base.OFF, off.base)
        assertEquals(setOf(claude), off.apps)
        val (later, dos) = minutes(off, claude, 11, 30)
        assertFalse(later.watching)
        assertTrue(dos.isEmpty())
    }

    @Test
    fun `Сонет сказал «то же дело» — вопроса нет, молчим`() {
        val (s, _) = minutes(PhoneLive.State(), tg, 1, 10)
        val off = PhoneLive.classified(s, null, setOf(tg), at(10)).state
        assertEquals(PhoneLive.Base.OFF, off.base)
        assertEquals(null, off.guess)
    }

    @Test
    fun `отложил на пять минут — дело кончилось в миг, когда отложил`() {
        val on = PhoneLive.opened(PhoneLive.answer(asked(), true).state, 42, guess.title, setOf(claude), 7)
        // Последний раз в руках в 0:25; в 0:28 ещё ждём, в 0:30 — закрыть в 0:25.
        val v = PhoneLive.View(null, at(25), 0L)
        assertTrue(PhoneLive.pulse(on, v, at(28), minMs).dos.isEmpty())
        val step = PhoneLive.pulse(on, v, at(30), minMs)
        assertEquals(listOf(PhoneLive.Close(42, 7, at(25))), step.dos)
        assertEquals(PhoneLive.Base.IDLE, step.state.base)
        assertEquals(at(25), step.state.handled)
    }

    @Test
    fun `после да — другое приложение две минуты — смотрим заново и спрашиваем`() {
        val on = PhoneLive.opened(PhoneLive.answer(asked(), true).state, 42, guess.title, setOf(claude), 7)
        val spans = listOf(PhoneSit.Span(claude, at(0), at(20)), PhoneSit.Span(yt, at(20), at(21)))
        // Минута ютуба — не переключение.
        val one = PhoneLive.View(null, at(21), PhoneLive.outsideSince(spans, on.apps, at(21)))
        assertFalse(PhoneLive.pulse(on, one, at(21), minMs).state.watching)
        // Две — смотрим с 0:20.
        val spans2 = listOf(PhoneSit.Span(claude, at(0), at(20)), PhoneSit.Span(yt, at(20), at(22)))
        val two = PhoneLive.View(null, at(22), PhoneLive.outsideSince(spans2, on.apps, at(22)))
        val w = PhoneLive.pulse(on, two, at(22), minMs).state
        assertEquals(at(20), w.watchFrom)
        assertEquals(PhoneLive.Base.ON, w.base)
        // Ютуб — другое дело: «да» ставит его, а вернуть после — по-прежнему то, что шло до Клода.
        val yg = PhoneLive.Guess("YouTube", "Потери", "", at(20))
        val asked = PhoneLive.classified(w.copy(snaps = 3, thinking = true), yg, setOf(yt), at(25)).state
        val opened = PhoneLive.opened(PhoneLive.answer(asked, true).state, 43, "YouTube", setOf(yt), resumeId = 42)
        assertEquals(7L, opened.resumeId)
        assertEquals(setOf(yt), opened.apps)
    }

    @Test
    fun `после да — Сонет сказал «то же» — приложения добавляются к делу`() {
        val on = PhoneLive.opened(PhoneLive.answer(asked(), true).state, 42, guess.title, setOf(claude), 7)
        val w = on.copy(watchFrom = at(20), snaps = 3, thinking = true)
        val same = PhoneLive.classified(w, null, setOf(tg), at(25)).state
        assertEquals(PhoneLive.Base.ON, same.base)
        assertEquals(setOf(claude, tg), same.apps)
        assertFalse(same.watching)
    }

    @Test
    fun `сидение кончилось до вопроса — задним числом, по-старому`() {
        val (s8, _) = minutes(PhoneLive.State(), claude, 1, 8)
        assertTrue(s8.watching)
        // Телефон в руках до 0:08, в 0:12 — сидение кончилось.
        val v = view(claude, 8, at(12))
        val step = PhoneLive.pulse(s8, v, at(12), minMs)
        assertEquals(listOf(PhoneLive.Behind(at(0), at(8))), step.dos)
        assertEquals(PhoneLive.Base.IDLE, step.state.base)
        assertFalse(step.state.watching)
    }

    @Test
    fun `короткое сидение кончилось — ничего`() {
        val v = view(tg, 3, at(10))
        val step = PhoneLive.pulse(PhoneLive.State(), v, at(10), minMs)
        assertTrue(step.dos.isEmpty())
        assertEquals(at(3), step.state.handled)
    }

    @Test
    fun `владелец сказал сам — молчим до конца сидения`() {
        val on = PhoneLive.opened(PhoneLive.answer(asked(), true).state, 42, guess.title, setOf(claude), 7)
        val off = PhoneLive.ownerTook(on)
        assertEquals(PhoneLive.Base.OFF, off.base)
        assertEquals(0L, off.resumeId)
        // Сидение кончилось — снова с нуля.
        val idle = PhoneLive.pulse(off, PhoneLive.View(null, at(30), 0L), at(34), minMs).state
        assertEquals(PhoneLive.Base.IDLE, idle.base)
    }

    @Test
    fun `переключение — от конца назад по чужим кускам`() {
        val spans = listOf(
            PhoneSit.Span(claude, at(0), at(10)),
            PhoneSit.Span(tg, at(10), at(12)),
            PhoneSit.Span(yt, at(12, 30), at(15)),
        )
        assertEquals(at(10), PhoneLive.outsideSince(spans, setOf(claude), at(15)))
        // Впереди своё — переключения нет; телефон отложен — тоже.
        assertEquals(0L, PhoneLive.outsideSince(spans, setOf(yt), at(15)))
        assertEquals(0L, PhoneLive.outsideSince(spans, setOf(claude), at(20)))
    }
}
