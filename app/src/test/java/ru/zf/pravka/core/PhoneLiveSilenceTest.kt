package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон в ленте без вечного молчания (14.10.2026). Владелец, 11:00: «внезапно
 * перестал работать алгоритм с телефоном целиком. Вот я сейчас сижу в
 * телефоне, а он мне даже ничего не говорит». В журнале: после обновления
 * «смотрю на экран три минуты» — и тишина; «ты сам сказал «Завтрак»» — и
 * двадцать минут в телефоне ни строки.
 *
 * Здесь движок без Android ([Phone]): события статистики, лента для охраны,
 * минуты экрана со снимками и пятиминутные тики — тем же ударом автомата
 * (`PhoneLive.beat`), что у `PhoneSitEngine`, и с файлом прежней сборки
 * (`PhoneLive.fromJson`). По тесту на выход из каждого состояния — назад к
 * проверке. Приложения и дела выдуманные.
 */
class PhoneLiveSilenceTest {

    private val min = 60_000L
    private val minMs = 7 * min
    private val t0 = 1_800_000_000_000L
    private fun at(m: Int, s: Int = 0) = t0 + m * min + s * 1000L

    private val chat = "org.example.chat"
    private val video = "org.example.video"

    /**
     * Телефон и движок без Android: [open] — приложение вышло на экран,
     * [dark] — экран погас; [minute] — минута службы (снимок, если автомат его
     * хочет, потом удар), [tick] — пятиминутный тик (только удар). Охрана при
     * начале проверки — как `PhoneSitEngine.startedWatch`.
     */
    private inner class Phone(var s: PhoneLive.State) {
        val ev = ArrayList<PhoneSit.Ev>()
        val ribbon = ArrayList<PhoneSit.Seen>()
        val dos = ArrayList<Pair<Long, PhoneLive.Do>>()
        val snapsAt = ArrayList<Long>()
        val guards = ArrayList<String>()
        var text = "Иван Петров · был(а) недавно · Привет"

        fun open(pkg: String, t: Long) { ev += PhoneSit.Front(t, pkg) }
        fun dark(t: Long) { ev += PhoneSit.Dark(t) }

        fun minute(now: Long) = beat(now, minute = true)
        fun tick(now: Long) = beat(now, minute = false)

        /** Минуты экрана с [from] по [to] включительно, каждая в [sec] секунд минуты. */
        fun minutes(from: Int, to: Int, sec: Int = 0) {
            for (m in from..to) minute(at(m, sec))
        }

        private fun beat(now: Long, minute: Boolean) {
            val events = ev.filter { it.at <= now }
            val spans = PhoneSit.spans(events, now) { PhoneSit.furniture(it) }.filter { it.end > s.handled }
            var seen: PhoneLive.Mark? = null
            if (minute) {
                val front = spans.maxByOrNull { it.end }?.takeIf { now - it.end <= 5_000L }
                if (front != null && s.snapDue(now)) {
                    snapsAt += now
                    seen = PhoneLive.Mark(front.pkg, PhoneLive.subject(text))
                } else if (front != null) {
                    seen = PhoneLive.Mark(front.pkg, null)
                }
            }
            val before = s
            val step = PhoneLive.beat(s, events, spans, seen, minute, now, minMs)
            s = step.state
            if (!before.watching && s.watching) startedWatch(spans, now)
            step.dos.forEach { dos += now to it }
        }

        private fun startedWatch(spans: List<PhoneSit.Span>, now: Long) {
            val seg = PhoneSit.Stretch(s.watchFrom, now, spans, finished = false).clip(s.watchFrom, now)
            val g = PhoneSit.guard(seg, ribbon, 0L, now)
            if (g.why.isNotBlank() && g.pieces.none { now - it.end <= PhoneSit.GAP_MS }) {
                s = PhoneLive.guarded(s, seg.spans.map { it.pkg }.toSet(), now)
                guards += g.why
            }
        }

        fun classify(): List<PhoneLive.Classify> = dos.map { it.second }.filterIsInstance<PhoneLive.Classify>()
        fun notes(): List<String> = dos.map { it.second }.filterIsInstance<PhoneLive.Note>().map { it.text }
    }

    /** Файл `phone-sit.json` сборки до 14.10 (вечер): без погасаний, предмета и отсрочки слова. */
    private fun oldFile(base: String, apps: List<String> = emptyList(), entry: Long = 0L, title: String = "", resume: Long = 0L) =
        JSONObject(
            """{"format":"pravka-phone-sit","base":"$base","apps":[${apps.joinToString(",") { "\"$it\"" }}],""" +
                """"entry":$entry,"title":"$title","resume":$resume,"handled":${at(-60)},"log":[]}"""
        )

    // ------------------------------------------------------------ обновление посреди сидения

    @Test
    fun `обновление посреди сидения — файл прежней сборки, проверка доходит до снимков и Сонета`() {
        // В телефоне с −0:03; в 0:00 встала новая сборка и прочла прежний файл.
        val p = Phone(PhoneLive.fromJson(oldFile("IDLE"), at(0)))
        p.open(chat, at(-3))
        // Служба после обновления: первая минута через полминуты, тик — через минуту и раз в пять.
        for (m in 0..10) {
            if (m == 1 || m == 6) p.tick(at(m))
            p.minute(at(m, 30))
        }
        // Семь минут в руках — в 0:04:30: смотрим, три снимка, на третьем — Сонету.
        assertEquals(listOf(at(5, 30), at(6, 30), at(7, 30)), p.snapsAt)
        assertEquals(listOf(PhoneLive.Classify(at(-3), at(7, 30))), p.classify())
        assertTrue(p.s.thinking)
        assertTrue(p.notes().isEmpty())
    }

    @Test
    fun `обновление после «молчу» прежней сборки — молчание не навечно, сидение от порога проверяется`() {
        // Прежняя сборка молчала о чате до конца сидения (его слово или крестик) — файл без отсрочки.
        val p = Phone(PhoneLive.fromJson(oldFile("OFF", listOf(chat)), at(0)))
        assertEquals(at(0), p.s.freshFrom)
        p.open(chat, at(-20))
        p.minutes(0, 12, sec = 30)
        // До 0:07 после загрузки — ни взгляда; семь минут в руках после неё — проверка и Сонет.
        assertEquals(listOf(at(8, 30), at(9, 30), at(10, 30)), p.snapsAt)
        assertEquals(listOf(PhoneLive.Classify(at(0), at(10, 30))), p.classify())
    }

    @Test
    fun `обновление посреди дела из телефона — переключение проверяется, погасание закрывает`() {
        val file = oldFile("ON", listOf(chat), entry = 42, title = "Переписка: Иван", resume = 7)
        val p = Phone(PhoneLive.fromJson(file, at(0)))
        assertEquals(PhoneLive.Base.ON, p.s.base)
        assertEquals(at(0), p.s.since)
        // Предмета прежняя сборка не знала — сверка по приложениям, снимков на сверку нет.
        assertFalse(p.s.snapDue(at(1)))
        p.open(chat, at(-10))
        p.open(video, at(2))
        p.minutes(0, 8, sec = 30)
        assertEquals(listOf(PhoneLive.Classify(at(2), at(7, 30))), p.classify())
        // Отдельно: тот же файл, экран погас в 0:03 — дело закрыто в миг погасания.
        val q = Phone(PhoneLive.fromJson(file, at(0)))
        q.open(chat, at(-10))
        q.dark(at(3))
        q.tick(at(4))
        assertEquals(listOf(PhoneLive.Close(42, 7, at(3), dark = true)), q.dos.map { it.second }.filterNot { it is PhoneLive.Note })
    }

    @Test
    fun `файл — всё решённое туда и обратно, проверка на диск не ложится`() {
        val s = PhoneLive.State(
            base = PhoneLive.Base.OFF,
            apps = setOf(chat, video),
            entryId = 5,
            title = "Дело",
            resumeId = 3,
            since = at(1),
            marks = setOf(PhoneLive.Mark(chat, 123), PhoneLive.Mark(video, null)),
            litAt = at(2),
            handled = at(3),
            freshFrom = at(4),
            watchFrom = at(5),
            snaps = 2,
            thinking = true,
        )
        val back = PhoneLive.fromJson(JSONObject(PhoneLive.toJson(s).toString()), at(10))
        assertEquals(s.copy(watchFrom = 0L, snaps = 0, thinking = false), back)
        // Текста экрана в файле нет — только пакет и хэш предмета.
        assertFalse(PhoneLive.toJson(s).toString().contains("Иван"))
    }

    // ------------------------------------------------------------ слово владельца — про своё время

    @Test
    fun `сказал «Завтрак» и сидит дальше — через семь минут после слова снова проверка`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        // В 0:06:50 сказал в «З»: «уже десять минут завтракаю» — запись с 0:-3, идёт.
        p.ribbon += PhoneSit.Seen(at(-3), 0L, "Завтрак", "Еда", "voice", "уже десять минут завтракаю", at(6, 50))
        p.minutes(1, 13)
        // В 0:07 сидение дотянуло до порога — его слово сильнее: не смотрим.
        assertEquals(1, p.guards.size)
        assertTrue(p.guards.single().contains("ты сам сказал «Завтрак»"))
        assertEquals(PhoneLive.Base.OFF, p.s.base)
        assertEquals(at(7), p.s.freshFrom)
        assertTrue(p.snapsAt.isEmpty())
        // Сидит дальше: семь минут после слова — новое сидение, проверка и Сонет.
        p.minutes(14, 20)
        assertEquals(1, p.guards.size)
        assertEquals(listOf(at(15), at(16), at(17)), p.snapsAt)
        assertEquals(listOf(PhoneLive.Classify(at(7), at(17))), p.classify())
        assertTrue(p.notes().any { it.contains("новое сидение") })
    }

    @Test
    fun `после проверки от слова Сонет сказал «то же» — по кругу не зовём, другое приложение — зовём`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        p.ribbon += PhoneSit.Seen(at(-3), 0L, "Завтрак", "Еда", "voice", "завтракаю", at(6, 50))
        p.minutes(1, 17)
        assertEquals(1, p.classify().size)
        p.s = PhoneLive.classified(p.s, null, setOf(chat), at(17, 10)).state
        assertEquals(0L, p.s.freshFrom)
        // Тот же чат ещё двадцать минут — молчим (договорённость про «то же»), без нового запроса.
        p.minutes(18, 40)
        assertEquals(1, p.classify().size)
        // Ушёл в видео на две минуты — проверка заново.
        p.open(video, at(41))
        p.minutes(41, 46)
        assertEquals(listOf(PhoneLive.Classify(at(41), at(46))), p.classify().drop(1))
    }

    @Test
    fun `встреча ещё идёт — охрана раз в семь минут, кончилась — проверка`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        val meeting = PhoneSit.Seen(at(-5), 0L, "Встреча", "Работа", CalendarRules.SOURCE)
        p.ribbon += meeting
        p.minutes(1, 21)
        // 0:07, 0:14, 0:21 — каждый раз «не смотрю», ни снимка, ни Сонета.
        assertEquals(3, p.guards.size)
        assertTrue(p.snapsAt.isEmpty())
        assertTrue(p.classify().isEmpty())
        // Встреча кончилась в 0:22 — следующее сидение от порога проверяется.
        p.ribbon.clear()
        p.ribbon += meeting.copy(end = at(22))
        p.minutes(22, 31)
        assertEquals(3, p.guards.size)
        assertEquals(listOf(PhoneLive.Classify(at(21), at(31))), p.classify())
    }

    @Test
    fun `сам переключился посреди дела из телефона — его слово, а через семь минут снова проверка`() {
        val on = PhoneLive.opened(PhoneLive.State(handled = at(-60)), 42, "Переписка: Иван", setOf(chat), 7, at(0), null, at(0))
        val p = Phone(PhoneLive.ownerTook(on, at(10)))
        p.open(chat, at(0))
        p.minutes(10, 16)
        assertTrue(p.snapsAt.isEmpty())
        p.minutes(17, 20)
        assertEquals(listOf(PhoneLive.Classify(at(10), at(20))), p.classify())
    }

    // ------------------------------------------------------------ крестик и «то же»: прежняя договорённость, но не навечно

    @Test
    fun `после крестика — другое приложение две минуты — проверка`() {
        val p = Phone(PhoneLive.State(base = PhoneLive.Base.OFF, apps = setOf(chat), handled = at(-60)))
        p.open(chat, at(-10))
        p.minutes(-9, -1)
        assertTrue(p.snapsAt.isEmpty())
        p.open(video, at(0))
        p.minutes(0, 6, sec = 30)
        assertEquals(listOf(PhoneLive.Classify(at(0), at(5, 30))), p.classify())
    }

    @Test
    fun `после крестика — отложил телефон, взял снова — новое сидение от порога проверяется`() {
        val p = Phone(PhoneLive.State(base = PhoneLive.Base.OFF, apps = setOf(chat), handled = at(-60)))
        p.open(chat, at(-10))
        p.minutes(-10, 4, sec = 30)
        p.dark(at(5))
        p.tick(at(8))
        assertEquals(PhoneLive.Base.IDLE, p.s.base)
        p.open(chat, at(9))
        p.minutes(9, 20, sec = 30)
        assertEquals(listOf(PhoneLive.Classify(at(9), at(19, 30))), p.classify())
    }

    // ------------------------------------------------------------ проверка и Сонет: по часам, а не «пока сидит»

    @Test
    fun `проверка без минут экрана — через пять минут по часам Сонету по приложениям`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        p.minutes(1, 7)
        assertTrue(p.s.watching)
        // Дальше служба на экран не смотрит (минут нет), а телефон в руках: только тики.
        p.tick(at(10))
        assertTrue(p.classify().isEmpty())
        p.tick(at(12))
        assertEquals(listOf(PhoneLive.Classify(at(0), at(12))), p.classify())
        assertTrue(p.notes().any { it.contains("служба на экран не смотрит") })
    }

    @Test
    fun `проверка кончилась до вопроса — строкой в журнал, а не тишиной`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        p.minutes(1, 8)
        p.dark(at(8, 10))
        p.tick(at(12))
        assertFalse(p.s.watching)
        assertEquals(PhoneLive.Base.IDLE, p.s.base)
        assertTrue(p.notes().single().contains("кончилась до вопроса — телефон отложен"))
        // Восемь минут в руках — Сонету задним числом, как в первом заходе.
        assertEquals(listOf(PhoneLive.Behind(at(0), at(8, 10))), p.dos.map { it.second }.filterIsInstance<PhoneLive.Behind>())
    }

    @Test
    fun `Сонет молчит дольше четырёх минут — не ждём, через три минуты спрашиваем снова`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        p.minutes(1, 10)
        assertEquals(1, p.classify().size)
        assertTrue(p.s.thinking)
        // Ответа нет (запрос завис): минуты идут, второй запрос не шлём, пока не выйдет срок.
        p.minutes(11, 13)
        assertEquals(1, p.classify().size)
        p.minutes(14, 14)
        assertFalse(p.s.thinking)
        assertTrue(p.notes().any { it.contains("Сонет молчит") })
        p.minutes(15, 17)
        assertEquals(listOf(PhoneLive.Classify(at(0), at(10)), PhoneLive.Classify(at(0), at(17))), p.classify())
    }

    @Test
    fun `ответ Сонета пришёл после конца проверки — вопроса нет`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        p.minutes(1, 10)
        assertTrue(p.s.thinking)
        p.dark(at(10, 10))
        p.tick(at(14))
        assertFalse(p.s.watching)
        val late = PhoneLive.classified(p.s, PhoneLive.Guess("Чат", "Работа", "", at(0)), setOf(chat), at(14, 30))
        assertNull(late.state.guess)
        assertTrue(late.dos.single() is PhoneLive.Note)
        // После срока «думает» — тоже: вопрос пришёл бы к проверке, которая его уже не ждёт.
        val stale = PhoneLive.failed(p.s.copy(watchFrom = at(0), thinking = true), at(15))
        assertNull(PhoneLive.classified(stale, PhoneLive.Guess("Чат", "Работа", "", at(0)), setOf(chat), at(15, 10)).state.guess)
    }

    @Test
    fun `Сонет не ответил — часы проверки с нуля, без запроса на каждом пульсе`() {
        val p = Phone(PhoneLive.State(handled = at(-60)))
        p.open(chat, at(0))
        p.minutes(1, 10)
        p.s = PhoneLive.failed(p.s, at(10, 20))
        p.tick(at(11))
        p.minutes(12, 12)
        assertEquals(1, p.classify().size)
        p.minutes(13, 14)
        assertEquals(2, p.classify().size)
    }
}
