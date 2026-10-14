package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон в ленте вживую (13.10.2026). Владелец: «если больше семи минут —
 * каждую минуту скрин, и на десятой: кажется, это „дело“, галка и крестик…
 * нажал „да“ — перестаёт делать скрины… крестик — перестаёт следить… после
 * „да“ другое приложение — снова скрины и снова спрашивает».
 *
 * После «да» (14.10.2026): «телефон считает до момента, как я выключил сам
 * телефон. Если не выключил, то он раз в 5 минут проверяет и подтверждается…
 * Выключение телефона на 30 секунд и есть конец дела… Если я в рамках 30
 * секунд снова взял телефон, то тогда он смотрит опять каждую минуту».
 */
class PhoneLiveTest {

    private val min = 60_000L
    private val minMs = 7 * min
    private val t0 = 1_000_000_000L
    private fun at(m: Int, s: Int = 0) = t0 + m * min + s * 1000L

    private val claude = "com.anthropic.claude"
    private val tg = "org.telegram.messenger"
    private val yt = "com.google.android.youtube"

    /** Что впереди: приложение и предмет — как его считает автомат из текста снимка. */
    private fun mark(pkg: String, text: String) = PhoneLive.Mark(pkg, PhoneLive.subject(text))

    private val pravka = "Правка: снимки экрана · Claude · Как сделать подтверждение"
    private val zhenya = "Женя Соколов · был(а) 5 минут назад · Привет, по договору"
    private val marianna = "Марианна · в сети · Купишь хлеб?"

    /** Сидение одним приложением от t0 до [toMin], идёт ли ещё — по [now]. */
    private fun view(pkg: String, toMin: Int, now: Long, fromMin: Int = 0, offs: List<PhoneLive.Off> = emptyList()): PhoneLive.View {
        val span = PhoneSit.Span(pkg, at(fromMin), at(toMin))
        val sit = PhoneSit.stretches(listOf(span), now).last()
        return PhoneLive.View(sit, span.end, 0L, offs)
    }

    private val guess = PhoneLive.Guess("Claude: Правка", "Систематизация", "", at(0))

    /**
     * Прогнать минуты экрана с [from] по [to] при идущем сидении в [pkg]: снимок
     * (если нужен) показывает [text], потом пульс. Минуты без нужды в снимке —
     * приложение без предмета, как его видит статистика.
     */
    private fun minutes(
        start: PhoneLive.State,
        pkg: String,
        from: Int,
        to: Int,
        text: String = pravka,
    ): Pair<PhoneLive.State, List<PhoneLive.Do>> {
        var s = start
        val dos = ArrayList<PhoneLive.Do>()
        for (m in from..to) {
            val seen = if (s.snapDue(at(m))) mark(pkg, text) else PhoneLive.Mark(pkg, null)
            val mm = PhoneLive.minute(s, seen, at(m))
            s = mm.state
            dos += mm.dos
            val step = PhoneLive.pulse(s, view(pkg, m, at(m)), at(m), minMs)
            s = step.state
            dos += step.dos
        }
        return s to dos
    }

    /** Шаги автомата без строк журнала. */
    private fun List<PhoneLive.Do>.acts() = filterNot { it is PhoneLive.Note }

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

    /** «Да» галочкой в 0:10 — дело стоит, первое подтверждённое — экран Клода с Правкой. */
    private fun on(): PhoneLive.State {
        val step = PhoneLive.answer(asked(), yes = true, now = at(10))
        val open = step.dos.single() as PhoneLive.Open
        return PhoneLive.opened(step.state, 42, guess.title, open.apps, 7, since = open.since, mark = open.mark, now = at(10))
    }

    @Test
    fun `галочка — дело в ленту, дальше раз в пять минут одним снимком, Сонета не зовём`() {
        val s = asked()
        assertEquals(guess, s.guess)
        assertFalse(s.wantsSnap)
        val step = PhoneLive.answer(s, yes = true, now = at(10))
        assertEquals(listOf(PhoneLive.Open(guess, setOf(claude), silent = false, since = at(10), mark = mark(claude, pravka))), step.dos)
        val on = on()
        assertEquals(PhoneLive.Base.ON, on.base)
        assertEquals(7L, on.resumeId)
        assertEquals(setOf(mark(claude, pravka)), on.marks)
        // Дальше Клод с той же Правкой: снимок только раз в пять минут, ни одного запроса.
        var s2 = on
        val dos = ArrayList<PhoneLive.Do>()
        var looks = 0
        for (m in 11..40) {
            if (s2.snapDue(at(m))) looks++
            val seen = if (s2.snapDue(at(m))) mark(claude, pravka) else PhoneLive.Mark(claude, null)
            val mm = PhoneLive.minute(s2, seen, at(m))
            dos += mm.dos
            val p = PhoneLive.pulse(mm.state, view(claude, m, at(m)), at(m), minMs)
            dos += p.dos
            s2 = p.state
        }
        assertEquals(6, looks)
        assertFalse(s2.watching)
        assertTrue(dos.acts().isEmpty())
        assertEquals(6, dos.count { it is PhoneLive.Note })
    }

    @Test
    fun `молчание на вопрос — да, погасание считается с вопроса`() {
        val s = asked()
        val step = PhoneLive.pulse(s, view(claude, 11, at(11)), at(10, 40), minMs)
        assertEquals(listOf(PhoneLive.Open(guess, setOf(claude), silent = true, since = at(10), mark = mark(claude, pravka))), step.dos)
    }

    @Test
    fun `вопрос висит, экран погас — молчание да, и дело кончается в миг погасания`() {
        val s = asked()
        // Погасил в 0:10:10, не ответив; пульс тика в 0:15.
        val offs = listOf(PhoneLive.Off(at(10, 10), 0L))
        val yes = PhoneLive.pulse(s, PhoneLive.View(null, at(10, 10), 0L, offs), at(15), minMs)
        val open = yes.dos.single() as PhoneLive.Open
        assertTrue(open.silent)
        val on = PhoneLive.opened(yes.state, 42, guess.title, open.apps, 7, open.since, open.mark, now = at(15))
        val step = PhoneLive.pulse(on, PhoneLive.View(null, at(10, 10), 0L, offs), at(15), minMs)
        assertEquals(listOf(PhoneLive.Close(42, 7, at(10, 10), dark = true)), step.dos)
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

    // ------------------------------------------------------------ после «да»: экран

    @Test
    fun `экран погас на полминуты и больше — дело закрыто в миг погасания, вернулось прежнее`() {
        val on = on()
        // Погас в 0:20, зажёгся в 0:21 — минута темноты.
        val v = view(claude, 20, at(21, 30), offs = listOf(PhoneLive.Off(at(20), at(21))))
        val step = PhoneLive.pulse(on, v, at(21, 30), minMs)
        assertEquals(listOf(PhoneLive.Close(42, 7, at(20), dark = true)), step.dos)
        assertEquals(PhoneLive.Base.IDLE, step.state.base)
        assertEquals(at(20), step.state.handled)
        // Ещё тёмный: 10 секунд — ждём, 30 — закрыто в миг погасания.
        val dark = listOf(PhoneLive.Off(at(20), 0L))
        assertTrue(PhoneLive.pulse(on, PhoneLive.View(null, at(20), 0L, dark), at(20, 10), minMs).dos.isEmpty())
        assertEquals(
            listOf(PhoneLive.Close(42, 7, at(20), dark = true)),
            PhoneLive.pulse(on, PhoneLive.View(null, at(20), 0L, dark), at(20, 30), minMs).dos,
        )
    }

    @Test
    fun `погасание до «да» дело не кончает`() {
        val on = on()
        // Гас минуту на восьмой минуте сидения — до ответа; после «да» экран горит.
        val v = view(claude, 12, at(12), offs = listOf(PhoneLive.Off(at(8), at(9))))
        val step = PhoneLive.pulse(on, v, at(12), minMs)
        assertTrue(step.dos.acts().isEmpty())
        assertEquals(PhoneLive.Base.ON, step.state.base)
    }

    @Test
    fun `экран погас меньше чем на полминуты и зажёгся — дело идёт, смотрим каждую минуту`() {
        val on = on()
        val blink = listOf(PhoneLive.Off(at(12), at(12, 20)))
        val step = PhoneLive.pulse(on, view(claude, 13, at(13), offs = blink), at(13), minMs)
        assertTrue(step.dos.acts().isEmpty())
        val s = step.state
        assertEquals(PhoneLive.Base.ON, s.base)
        assertTrue(s.everyMinute)
        assertEquals(at(12, 20), s.litAt)
        // Тем же морганием второй раз не отзываемся.
        assertTrue(PhoneLive.pulse(s, view(claude, 13, at(13), offs = blink), at(13), minMs).dos.isEmpty())
        // Поминутно: снимок каждую минуту, а не раз в пять.
        assertTrue(s.snapDue(at(14)))
        // Пять минут подряд то же — снова раз в пять минут.
        val (back, dos) = minutes(s, claude, 14, 18)
        assertFalse(back.everyMinute)
        assertFalse(back.watching)
        assertTrue(dos.acts().isEmpty())
        assertFalse(back.snapDue(at(19)))
        assertTrue(back.snapDue(at(23)))
    }

    @Test
    fun `после моргания другое на экране — проверка и вопрос, как после переключения`() {
        val blink = listOf(PhoneLive.Off(at(12), at(12, 20)))
        val s = PhoneLive.pulse(on(), view(claude, 13, at(13), offs = blink), at(13), minMs).state
        // Взял телефон — а там Телеграм с Марианной.
        val m = PhoneLive.minute(s, mark(tg, marianna), at(14))
        assertTrue(m.state.watching)
        assertEquals(1, m.state.snaps)
        assertEquals(at(10), m.state.watchFrom)
        assertFalse(m.state.everyMinute)
        val (s16, dos) = minutes(m.state, tg, 15, 16, marianna)
        assertEquals(listOf(PhoneLive.Classify(at(10), at(16))), dos.acts())
        assertTrue(s16.thinking)
    }

    // ------------------------------------------------------------ после «да»: подтверждения

    @Test
    fun `пять минут то же — без запроса Сонету`() {
        val on = on()
        assertFalse(on.snapDue(at(14)))
        assertTrue(on.snapDue(at(15)))
        val m = PhoneLive.minute(on, mark(claude, pravka), at(15))
        assertTrue(m.dos.acts().isEmpty())
        assertFalse(m.state.watching)
        assertEquals(at(20), m.state.confirmAt)
        assertEquals(at(15), m.state.seenAt)
        // Цифры в заголовке («был 5 минут назад» → «7 минут») — тот же предмет.
        val tgOn = PhoneLive.opened(PhoneLive.State(), 42, "Переписка: Женя Соколов", setOf(tg), 7, at(10), mark(tg, zhenya), at(10))
        val later = zhenya.replace("5 минут", "7 минут")
        assertTrue(PhoneLive.minute(tgOn, mark(tg, later), at(15)).dos.acts().isEmpty())
        assertFalse(PhoneLive.minute(tgOn, mark(tg, later), at(15)).state.watching)
    }

    @Test
    fun `пять минут — другое приложение — проверка и вопрос`() {
        val on = on()
        // Сверка в 0:15: впереди YouTube. Кусок — с мига, когда своё видели последний раз.
        val m = PhoneLive.minute(on, mark(yt, "Как собрать велосипед · 12:40"), at(15))
        assertTrue(m.state.watching)
        assertEquals(at(10), m.state.watchFrom)
        assertEquals(1, m.state.snaps)
        val spans = listOf(PhoneSit.Span(claude, at(0), at(13)), PhoneSit.Span(yt, at(13), at(17)))
        var s = m.state
        val dos = ArrayList<PhoneLive.Do>()
        for (t in 16..17) {
            val mm = PhoneLive.minute(s, mark(yt, "Как собрать велосипед · 12:40"), at(t))
            val v = PhoneLive.View(null, at(t), PhoneLive.outsideSince(spans, s.apps, at(t)))
            val p = PhoneLive.pulse(mm.state, v, at(t), minMs)
            dos += p.dos
            s = p.state
        }
        assertEquals(listOf(PhoneLive.Classify(at(10), at(17))), dos.acts())
        // Сонет: другое — вопрос на «З»; «да» — новое дело, вернуть потом — всё тот же «Завтрак».
        val yg = PhoneLive.Guess("YouTube", "Потери", "", at(13))
        val q = PhoneLive.classified(s, yg, setOf(yt), at(17, 10)).state
        assertEquals(yg, q.guess)
        val yes = PhoneLive.answer(q, yes = true, now = at(17, 20))
        val open = yes.dos.single() as PhoneLive.Open
        assertEquals(mark(yt, "Как собрать велосипед · 12:40"), open.mark)
        val next = PhoneLive.opened(yes.state, 43, "YouTube", open.apps, 42, open.since, open.mark, at(17, 20))
        assertEquals(7L, next.resumeId)
        assertEquals(setOf(yt), next.apps)
    }

    @Test
    fun `тот же Телеграм, другой чат — проверка, Сонет сказал «то же» — новый чат подтверждён`() {
        val tgOn = PhoneLive.opened(PhoneLive.State(), 42, "Переписка: Женя Соколов", setOf(tg), 7, at(10), mark(tg, zhenya), at(10))
        val m = PhoneLive.minute(tgOn, mark(tg, marianna), at(15))
        assertTrue(m.state.watching)
        val (s, dos) = minutes(m.state, tg, 16, 17, marianna)
        assertEquals(1, dos.acts().count { it is PhoneLive.Classify })
        val same = PhoneLive.classified(s, null, setOf(tg), at(17, 10)).state
        assertFalse(same.watching)
        assertEquals(setOf(mark(tg, zhenya), mark(tg, marianna)), same.marks)
        // Дальше и тот, и другой чат — подтверждаем сами.
        assertTrue(PhoneLive.minute(same, mark(tg, marianna), at(23)).dos.acts().isEmpty())
        assertFalse(PhoneLive.minute(same, mark(tg, zhenya), at(23)).state.watching)
    }

    @Test
    fun `глянул в другое и вернулся к третьему снимку — Сонета не зовём`() {
        val m = PhoneLive.minute(on(), mark(tg, marianna), at(15))
        assertTrue(m.state.watching)
        var s = m.state
        val dos = ArrayList<PhoneLive.Do>()
        for (t in 16..17) {
            val mm = PhoneLive.minute(s, mark(claude, pravka), at(t))
            val p = PhoneLive.pulse(mm.state, view(claude, t, at(t)), at(t), minMs)
            dos += p.dos
            s = p.state
        }
        assertTrue(dos.acts().isEmpty())
        assertFalse(s.watching)
        assertEquals(at(22), s.confirmAt)
    }

    @Test
    fun `крестик посреди дела из телефона — по экрану больше не сверяем`() {
        val m = PhoneLive.minute(on(), mark(tg, marianna), at(15))
        val (s, _) = minutes(m.state, tg, 16, 17, marianna)
        val q = PhoneLive.classified(s, PhoneLive.Guess("Переписка: Марианна", "Семья", "", at(14)), setOf(tg), at(17)).state
        val no = PhoneLive.answer(q, yes = false, now = at(17, 10)).state
        assertEquals(PhoneLive.Base.ON, no.base)
        assertEquals(42L, no.entryId)
        assertTrue(no.marks.isEmpty())
        assertEquals(setOf(claude, tg), no.apps)
        // Снимков для сверки больше нет; погасание по-прежнему кончает дело.
        assertFalse(no.snapDue(at(30)))
        val off = PhoneLive.pulse(no, PhoneLive.View(null, at(31), 0L, listOf(PhoneLive.Off(at(31), at(32)))), at(32), minMs)
        assertEquals(listOf(PhoneLive.Close(42, 7, at(31), dark = true)), off.dos)
    }

    @Test
    fun `экран погас посреди проверки — дело закрыто, кусок проверки — задним числом`() {
        val m = PhoneLive.minute(on(), mark(yt, "ролик"), at(15))
        val v = PhoneLive.View(null, at(16), 0L, listOf(PhoneLive.Off(at(16), 0L)))
        val step = PhoneLive.pulse(m.state, v, at(20), minMs)
        assertEquals(listOf(PhoneLive.Close(42, 7, at(16), dark = true), PhoneLive.Behind(at(10), at(16))), step.dos)
    }

    @Test
    fun `экран горит, а телефоном не пользуются пять минут — дело кончилось в миг последнего касания`() {
        val on = on()
        // Последний раз в руках в 0:25 (заставка на подставке); в 0:28 ещё ждём, в 0:30 — закрыть в 0:25.
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
        assertEquals(listOf(PhoneLive.Behind(at(0), at(8))), step.dos.acts())
        // Конец проверки — строкой в журнал (14.10.2026: тишина читалась как поломка).
        assertTrue((step.dos.first() as PhoneLive.Note).text.contains("кончилась до вопроса"))
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
    fun `владелец сказал сам — молчим о тех же приложениях, сидение кончилось — снова с нуля`() {
        val off = PhoneLive.ownerTook(on(), at(20))
        assertEquals(PhoneLive.Base.OFF, off.base)
        assertEquals(0L, off.resumeId)
        assertTrue(off.marks.isEmpty())
        // Его слово — про время до 0:20; дальше сидение от порога — проверка (PhoneLiveSilenceTest).
        assertEquals(at(20), off.freshFrom)
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

    // ------------------------------------------------------------ предмет и погасания

    @Test
    fun `предмет — первые строки экрана без цифр, другой чат — другой предмет`() {
        assertEquals(PhoneLive.subject(zhenya), PhoneLive.subject(zhenya.replace("5 минут", "12 минут")))
        // Сама переписка ниже заголовка в предмет не входит.
        assertEquals(PhoneLive.subject(zhenya), PhoneLive.subject("Женя Соколов · был(а) 5 минут назад · Созвон завтра?"))
        assertNotEquals(PhoneLive.subject(zhenya), PhoneLive.subject(marianna))
        assertNull(PhoneLive.subject(""))
        assertNull(PhoneLive.subject("12:40 · 5"))
    }

    @Test
    fun `погасания экрана — из событий «погас» и «зажёгся»`() {
        val ev = listOf(
            PhoneSit.Front(at(1), claude),
            PhoneSit.Back(at(5), claude),
            PhoneSit.Dark(at(5)),
            // Приложение «вышло» на тёмном экране (замок) — темнота не кончается.
            PhoneSit.Front(at(5, 1), claude),
            PhoneSit.Lit(at(5, 20)),
            PhoneSit.Dark(at(9)),
        )
        assertEquals(listOf(PhoneLive.Off(at(5), at(5, 20)), PhoneLive.Off(at(9), 0L)), PhoneLive.offs(ev))
        // В кусках «зажёгся» ничего не открывает: кусок — с приложения впереди.
        val spans = PhoneSit.spans(ev, at(10)) { false }
        assertEquals(listOf(at(1) to at(5), at(5, 1) to at(9)), spans.map { it.start to it.end })
    }

    @Test
    fun `уведомление разбудило экран — темнота та же, дело закрыто в миг первого погасания`() {
        // Погасил в 0:20; в 0:20:20 экран зажгло уведомление (приложений никто не открывал), в 0:20:30 погас снова.
        val offs = listOf(PhoneLive.Off(at(20), at(20, 20)), PhoneLive.Off(at(20, 30), 0L))
        val spans = listOf(PhoneSit.Span(claude, at(10), at(20)))
        val merged = PhoneLive.merge(offs, spans)
        assertEquals(listOf(PhoneLive.Off(at(20), 0L)), merged)
        val step = PhoneLive.pulse(on(), PhoneLive.View(null, at(20), 0L, merged), at(21), minMs)
        assertEquals(listOf(PhoneLive.Close(42, 7, at(20), dark = true)), step.dos)
        // Взял и открыл приложение между погасаниями — это два погасания: первое — моргание.
        val used = spans + PhoneSit.Span(tg, at(20, 21), at(20, 29))
        assertEquals(offs, PhoneLive.merge(offs, used))
    }
}
