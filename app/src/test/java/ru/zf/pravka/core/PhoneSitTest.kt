package ru.zf.pravka.core

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Долгое сидение в телефоне режет дело (13.10.2026). Владелец: «если написано,
 * что я делаю завтрак, а я сижу уже минут семь в Клоде, то это систематизация…
 * а если вспомнил, что по работе надо написать, быстро написал — то это не
 * работа».
 */
class PhoneSitTest {

    private val min = 60_000L
    private val minMs = PhoneSit.MIN_DEFAULT * min

    /** Сегодня в [h]:[m] по часам телефона. */
    private fun at(h: Int, m: Int, s: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, s)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val claude = "com.anthropic.claude"
    private val tg = "org.telegram.messenger"
    private val home = "com.sec.android.app.launcher"
    private fun furniture(p: String) = PhoneSit.furniture(p)

    private fun stretch(vararg spans: PhoneSit.Span, finished: Boolean = true) =
        PhoneSit.Stretch(spans.minOf { it.start }, spans.maxOf { it.end }, spans.toList(), finished)

    @Test
    fun `куски переднего плана — из событий, мебель выпадает`() {
        val ev = listOf(
            PhoneSit.Front(at(8, 10), tg),
            PhoneSit.Back(at(8, 12), tg),
            PhoneSit.Front(at(8, 12), home),
            PhoneSit.Front(at(8, 12, 20), claude),
            PhoneSit.Dark(at(8, 20)),
        )
        val spans = PhoneSit.spans(ev, at(9, 0), ::furniture)
        assertEquals(listOf(tg, claude), spans.map { it.pkg })
        assertEquals(at(8, 12), spans[0].end)
        assertEquals(at(8, 12, 20), spans[1].start)
        assertEquals(at(8, 20), spans[1].end)
    }

    @Test
    fun `идущее приложение тянется до сейчас`() {
        val spans = PhoneSit.spans(listOf(PhoneSit.Front(at(8, 10), claude)), at(8, 30), ::furniture)
        assertEquals(at(8, 30), spans.single().end)
    }

    @Test
    fun `рабочий стол на минутку — то же сидение, погасил на пять — два`() {
        val spans = listOf(
            PhoneSit.Span(tg, at(8, 10), at(8, 13)),
            PhoneSit.Span(claude, at(8, 14), at(8, 20)),
            PhoneSit.Span(claude, at(8, 25), at(8, 27)),
        )
        val st = PhoneSit.stretches(spans, at(9, 0))
        assertEquals(2, st.size)
        assertEquals(at(8, 10), st[0].start)
        assertEquals(at(8, 20), st[0].end)
        assertEquals(9 * min, st[0].useMs)
        assertTrue(st.all { it.finished })
    }

    @Test
    fun `идущее сидение ждёт конца`() {
        val spans = listOf(PhoneSit.Span(claude, at(8, 10), at(8, 29)))
        val st = PhoneSit.stretches(spans, at(8, 30))
        assertFalse(st.single().finished)
        assertTrue(PhoneSit.due(st, 0L, minMs).isEmpty())
        // Три минуты без телефона — кончилось.
        assertTrue(PhoneSit.stretches(spans, at(8, 32)).single().finished)
    }

    @Test
    fun `быстро ответил — не режет, но отметку сдвигает`() {
        val st = PhoneSit.stretches(listOf(PhoneSit.Span(tg, at(8, 10), at(8, 13))), at(9, 0))
        val due = PhoneSit.due(st, 0L, minMs)
        assertFalse(due.single().long)
    }

    @Test
    fun `порог считает время в руках, а не размах с паузами`() {
        // 8:10–8:17 по краям, но телефон в руках 5,5 минут.
        val spans = listOf(
            PhoneSit.Span(tg, at(8, 10), at(8, 13)),
            PhoneSit.Span(tg, at(8, 14, 30), at(8, 17)),
        )
        val due = PhoneSit.due(PhoneSit.stretches(spans, at(9, 0)), 0L, minMs)
        assertFalse(due.single().long)
    }

    @Test
    fun `разобранное не повторяется, начало после отметки обрезается`() {
        val st = PhoneSit.stretches(listOf(PhoneSit.Span(claude, at(8, 0), at(8, 30))), at(9, 0))
        assertTrue(PhoneSit.due(st, at(8, 30), minMs).isEmpty())
        val cut = PhoneSit.due(st, at(8, 20), minMs).single()
        assertEquals(at(8, 20), cut.stretch.start)
        assertTrue(cut.long)
    }

    @Test
    fun `больше двух часов — кусками, первый не ждёт конца`() {
        val st = PhoneSit.stretches(listOf(PhoneSit.Span(claude, at(8, 0), at(10, 30))), at(10, 30))
        assertEquals(2, st.size)
        assertTrue(st[0].finished)
        assertEquals(at(10, 0), st[0].end)
        assertFalse(st[1].finished)
        assertEquals(1, PhoneSit.due(st, 0L, minMs).size)
    }

    private fun seen(
        from: Long, to: Long, title: String, category: String, source: String = "voice",
        raw: String = "", created: Long = 0L,
    ) = PhoneSit.Seen(from, to, title, category, source, raw, created)

    @Test
    fun `завтрак — к Сонету, сон, встреча, дорога, спорт и факт робота — нет`() {
        val s = stretch(PhoneSit.Span(claude, at(8, 10), at(8, 25)))
        val now = at(9, 0)
        val breakfast = seen(at(8, 0), 0L, "Завтрак", "Еда", raw = "готовлю завтрак", created = at(8, 0))
        assertEquals(1, PhoneSit.guard(s, listOf(breakfast), minMs, now).pieces.size)
        for (blocker in listOf(
            seen(at(7, 0), 0L, "сон", "Сон", source = "auto"),
            seen(at(8, 0), at(9, 0), "Созвон с Ильёй", "Работа: звонки", source = CalendarRules.SOURCE),
            seen(at(8, 0), 0L, "Поездка", "Передвижение: транспорт"),
            seen(at(8, 0), 0L, "Бег", "Спорт: бег"),
            seen(at(8, 5), at(8, 30), "Звонок: Мама", "Семья", source = "auto"),
        )) {
            val g = PhoneSit.guard(s, listOf(blocker), minMs, now)
            assertTrue(blocker.title, g.pieces.isEmpty())
            assertTrue(blocker.title, g.why.isNotBlank())
        }
    }

    @Test
    fun `сказал в «З» посреди сидения — дальше его слово`() {
        val s = stretch(PhoneSit.Span(claude, at(8, 10), at(8, 30)))
        val now = at(9, 0)
        // В 8:22 сказал «сажусь работать»: до этого 12 минут — Сонету, после — его слово.
        val said = seen(at(8, 22), 0L, "Работа", "Работа: текущая", raw = "сажусь работать", created = at(8, 22))
        val g = PhoneSit.guard(s, listOf(seen(at(8, 0), at(8, 22), "Завтрак", "Еда"), said), minMs, now)
        assertEquals(at(8, 22), g.pieces.single().end)
        assertTrue(g.why.contains("ты сам сказал"))
        // Сказал «с восьми завтракаю» в 8:15 — всё сидение его.
        val retro = seen(at(8, 0), 0L, "Завтрак", "Еда", raw = "с восьми завтракаю", created = at(8, 15))
        assertTrue(PhoneSit.guard(s, listOf(retro), minMs, now).pieces.isEmpty())
        // Продолжение после звонка (копия без надиктовки) — не слово владельца.
        val resumed = seen(at(8, 12), 0L, "Завтрак", "Еда", raw = "", created = at(8, 12))
        assertEquals(1, PhoneSit.guard(s, listOf(resumed), minMs, now).pieces.size)
    }

    @Test
    fun `дыры не охраняют`() {
        val s = stretch(PhoneSit.Span(tg, at(14, 0), at(14, 20)))
        val gap = seen(at(13, 30), at(14, 30), "не размечено", "Не размечено", source = "gap")
        assertEquals(1, PhoneSit.guard(s, listOf(gap), minMs, at(15, 0)).pieces.size)
    }

    private val cats = listOf("Еда", "Систематизация", "Работа: текущая", "Семья", "Потери", "Сон", "Не размечено")

    @Test
    fun `ответ — куски внутри сидения, края прилипают`() {
        val s = stretch(PhoneSit.Span(claude, at(8, 10, 40), at(8, 25, 10)))
        val r = PhoneSit.parse(
            """{"parts":[{"from":"08:10","to":"08:25","category":"«Систематизация»","title":"Claude: Правка","client":""}],"why":"правит Правку"}""",
            s, cats, minMs,
        )!!
        val p = r.parts.single()
        assertEquals(s.start, p.start)
        assertEquals(s.end, p.end)
        assertEquals("Систематизация", p.category)
        assertEquals("Claude: Правка", p.title)
        assertEquals("правит Правку", r.why)
    }

    @Test
    fun `пустые куски — так и есть`() {
        val s = stretch(PhoneSit.Span(tg, at(10, 0), at(10, 12)))
        val r = PhoneSit.parse("""```json
{"parts":[],"why":"переписка с клиентом — та же работа"}
```""", s, cats, minMs)!!
        assertTrue(r.parts.isEmpty())
    }

    @Test
    fun `чужая категория, сон и короткий кусок выпадают`() {
        val s = stretch(PhoneSit.Span(tg, at(10, 0), at(10, 30)))
        val r = PhoneSit.parse(
            """{"parts":[
              {"from":"10:00","to":"10:04","category":"Семья","title":"Переписка с Марианной"},
              {"from":"10:04","to":"10:20","category":"Работа: текущая","title":"Переписка: Додо","client":"Додо"},
              {"from":"10:20","to":"10:30","category":"Сон","title":"сон"},
              {"from":"10:20","to":"10:30","category":"Бездельничанье","title":"?"}
            ],"why":"x"}""",
            s, cats, minMs,
        )!!
        assertEquals(listOf("Переписка: Додо"), r.parts.map { it.title })
        assertEquals("Додо", r.parts.single().client)
        assertEquals(3, r.dropped.size)
    }

    @Test
    fun `куски не налезают друг на друга`() {
        val s = stretch(PhoneSit.Span(claude, at(8, 0), at(8, 30)))
        val r = PhoneSit.parse(
            """{"parts":[
              {"from":"08:00","to":"08:16","category":"Систематизация","title":"Claude: Правка"},
              {"from":"08:15","to":"08:30","category":"Потери","title":"YouTube"}
            ],"why":""}""",
            s, cats, minMs,
        )!!
        assertEquals(2, r.parts.size)
        assertEquals(r.parts[0].end, r.parts[1].start)
    }

    @Test
    fun `сидение через полночь — время в нужных сутках`() {
        val start = at(23, 50)
        val s = PhoneSit.Stretch(start, start + 25 * min, listOf(PhoneSit.Span(claude, start, start + 25 * min)), true)
        val r = PhoneSit.parse(
            """{"parts":[{"from":"23:55","to":"00:15","category":"Систематизация","title":"Claude"}],"why":""}""",
            s, cats, minMs,
        )!!
        assertEquals(start + 5 * min, r.parts.single().start)
        assertEquals(start + 25 * min, r.parts.single().end)
    }

    @Test
    fun `непрочитанный ответ — null, а не пустота`() {
        val s = stretch(PhoneSit.Span(claude, at(8, 0), at(8, 30)))
        assertNull(PhoneSit.parse("не знаю", s, cats, minMs))
    }

    @Test
    fun `текст экрана — без мусора, номеров и повторов`() {
        val t = PhoneSit.clean(
            listOf(
                "  Женя Соколов  ",
                "12:41",
                "был(а) недавно",
                "Женя Соколов",
                "Номер карты 5536 9137 1234 5678",
                "",
                "87",
                "Пришли, пожалуйста,\nсверку до пятницы",
            )
        )
        assertEquals(
            "Женя Соколов · был(а) недавно · Номер карты … · Пришли, пожалуйста, сверку до пятницы",
            t,
        )
        assertTrue(PhoneSit.clean(List(200) { "строка номер ${'а' + it % 30} $it" }).length <= PhoneSit.TEXT_CAP)
    }

    @Test
    fun `свои люди в тексте — подсказкой, Мама не цепляет Маматова`() {
        val people = listOf("Женя Соколов" to "работа, клиент Ромашка", "Мама" to "семья")
        assertEquals(
            listOf("Женя Соколов — работа, клиент Ромашка"),
            PhoneSit.whoInText("Чаты · Женя Соколов · Маматов Игорь", people),
        )
        assertEquals(listOf("Мама — семья"), PhoneSit.whoInText("Мама: купи хлеба", people))
    }

    @Test
    fun `банки, пароли, Госуслуги и сама Правка — без текста`() {
        assertTrue(PhoneSit.privateText("com.idamob.tinkoff.android"))
        assertTrue(PhoneSit.privateText("ru.rostel.gosuslugi"))
        assertTrue(PhoneSit.privateText("ru.zf.pravka"))
        assertFalse(PhoneSit.privateText(tg))
        assertFalse(PhoneSit.privateText(claude))
    }

    @Test
    fun `строки для промпта и комментарий`() {
        val s = stretch(
            PhoneSit.Span(claude, at(8, 10), at(8, 17)),
            PhoneSit.Span(claude, at(8, 17, 30), at(8, 20)),
            PhoneSit.Span(tg, at(8, 20), at(8, 22)),
        )
        val label = { p: String -> if (p == claude) "Claude" else "Telegram" }
        assertEquals("08:10–08:20 Claude (10 мин)\n08:20–08:22 Telegram (2 мин)", PhoneSit.timeline(s, label))
        assertEquals("Claude 10 мин · Telegram 2 мин", PhoneSit.appsLine(s, label))
        val snaps = listOf(
            PhoneSit.Snap(at(8, 11), claude, "Правка: засечка подсматривает"),
            PhoneSit.Snap(at(8, 12), claude, "Правка: засечка подсматривает"),
            PhoneSit.Snap(at(8, 21), tg, "Женя Соколов"),
            PhoneSit.Snap(at(9, 0), tg, "после сидения"),
        )
        assertEquals(
            listOf("08:11 · Claude · Правка: засечка подсматривает", "08:21 · Telegram · Женя Соколов"),
            PhoneSit.snapLines(s, snaps, label),
        )
    }
}
