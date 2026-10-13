package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// «Сегодня» вида v2 (Правка 5.0, этап 2): лента с вечера к утру, списки
// «Сейчас» и «Сегодня», завтра строкой, дайджест абзацами, время Шаббата.
class TodayV2Test {

    private val zone: ZoneId = ZoneOffset.ofHours(3)
    private val day = LocalDate.of(2026, 10, 13)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int = 0) = dayStart + h * 3_600_000L + m * 60_000L

    // ---------------------------------------------------------------- лента

    @Test
    fun `лента — с вечера к утру, события под своим отрезком и тоже свежие сверху`() {
        val r = DayAssembler.assemble(
            DayAssembler.Input(
                dayStart = dayStart,
                now = at(19),
                entries = listOf(
                    DayAssembler.EntryIn(1, at(-1), at(8), "сон", "Сон", 0),
                    DayAssembler.EntryIn(2, at(9), at(12), "Финмодель", "Работа: текущая", 10),
                    DayAssembler.EntryIn(3, at(12), at(13), "не размечено", "Не размечено", -5, gap = true),
                    DayAssembler.EntryIn(4, at(13), 0L, "Обед", "Еда", 1),
                ),
                marks = listOf(
                    DayAssembler.MarkIn(at(8), DayAssembler.Source.SPORT, "Сон 7 ч · счёт 80", "sleep"),
                    DayAssembler.MarkIn(at(10), DayAssembler.Source.DELA, "Сделано: отчёт", "dela:task:1"),
                    DayAssembler.MarkIn(at(11), DayAssembler.Source.MONEY, "Аптека · −500 ₽", "money:1"),
                    DayAssembler.MarkIn(at(13, 30), DayAssembler.Source.FOOD, "Обед · 700 ккал, белок 40 г", "meal:1"),
                ),
            ),
        )
        val rows = Lenta.rows(r.items, sourceOf = { if (it == 1L) "auto" else "voice" })
        val segs = rows.filterIsInstance<Lenta.Row.Seg>()
        assertEquals(listOf(4L, 3L, 2L, 1L), segs.map { it.entryId })
        assertTrue(segs.first().current)
        // Заполнитель — «не размечено» без категории и источника; сон с часов — Garmin.
        assertEquals("не размечено — разметить", segs[1].title)
        assertEquals("", segs[1].sub)
        assertEquals("Работа: текущая · голос", segs[2].sub)
        assertEquals("Сон · Garmin", segs[3].sub)
        // Под работой — деньги (11:00) выше дела (10:00).
        val afterWork = rows.drop(rows.indexOf(segs[2]) + 1).takeWhile { it is Lenta.Row.Event }.map { (it as Lenta.Row.Event).kind }
        assertEquals(listOf(Lenta.Kind.MONEY, Lenta.Kind.DELA), afterWork)
        // Сон с вечера — «от» настоящее, вчерашнее.
        assertEquals(at(-1), segs[3].from)
        assertEquals(Lenta.Kind.SLEEP, (rows.last() as Lenta.Row.Event).kind)
        assertEquals(Lenta.Kind.FOOD, (rows[1] as Lenta.Row.Event).kind)
    }

    @Test
    fun `откуда запись словом`() {
        assertEquals("голос", Lenta.sourceWord("voice", "Еда"))
        assertEquals("руками", Lenta.sourceWord("edit", "Еда"))
        assertEquals("авто", Lenta.sourceWord("auto", "Работа: звонки"))
        assertEquals("Garmin", Lenta.sourceWord("auto", "Спорт: прочее"))
        assertEquals("автопилот", Lenta.sourceWord(CalendarRules.SOURCE, "Работа: звонки"))
        assertEquals("", Lenta.sourceWord("gap", "Не размечено"))
    }

    // ---------------------------------------------------------------- дела

    private fun task(id: String, f: Dela.Task.() -> Dela.Task = { this }) =
        Dela.Task(id = id, title = "дело $id", ownerId = "me", createdAt = "2026-10-0${id.length}T10:00:00+03:00").f()

    @Test
    fun `сейчас — только отмеченное, сегодня — срок ровно сегодня, просроченное — нигде`() {
        val s = Dela.Snapshot(
            tasks = listOf(
                task("a") { copy(focusOn = "2026-10-13") },
                task("b") { copy(dueDate = "2026-10-13", dueTime = "15:00") },
                task("c") { copy(dueDate = "2026-10-12") },
                task("d") { copy(dueDate = "2026-10-13") },
                task("e") { copy(dueDate = "2026-10-13", focusOn = "2026-10-13") },
                task("f") { copy(dueDate = "2026-10-13", ownerId = "other") },
                task("g") { copy(dueDate = "2026-10-14", dueTime = "09:00") },
            ).associateBy { it.id },
        )
        assertEquals(setOf("a", "e"), TodayAgenda.now(s, "me", "2026-10-13").map { it.id }.toSet())
        assertEquals(listOf("b", "d"), TodayAgenda.due(s, "me", "2026-10-13").map { it.id })
        val rows = TodayAgenda.rows(
            TodayAgenda.due(s, "me", "2026-10-13"),
            listOf(CalEvent(1, 1, "БЖЖ", at(13), at(14), false, false, false, "", true)),
            dayStart, now = at(18), zone = zone,
        )
        assertEquals(listOf("13:00–14:00", "15:00", "за день"), rows.map { it.whenText })
        assertTrue(rows[0].past)
        assertEquals("встреча · прошла", rows[0].sub)
        val line = TodayAgenda.tomorrowLine(
            TodayAgenda.due(s, "me", "2026-10-14", "2026-10-13"),
            listOf(CalEvent(2, 2, "Созвон с банком", at(24 + 14), at(24 + 15), false, false, false, "", true)),
            zone = zone,
        )
        assertEquals("завтра: дело g 9:00, Созвон с банком 14:00", line)
    }

    // ---------------------------------------------------------------- дайджест

    @Test
    fun `дайджест — заголовки и жирное имя`() {
        val b = DigestNote(0L, "# Ждут ответа\n- Ольга: ответить про сроки до пятницы\nКороткий абзац без имени\n").blocks()
        assertEquals(3, b.size)
        assertTrue(b[0].heading)
        assertEquals("Ольга:", b[1].lead)
        assertEquals("ответить про сроки до пятницы", b[1].text)
        assertEquals("", b[2].lead)
    }

    @Test
    fun `дайджест из синка — пример контракта, разделы из пунктов`() {
        val f = listOf(java.io.File("../server/contract/dela-digest.json"), java.io.File("server/contract/dela-digest.json")).first { it.isFile }
        val ex = org.json.JSONObject(f.readText()).getJSONObject("digest_object").getJSONObject("example")
        val d = DigestNote.parse(ex)!!
        assertEquals("вт 13.10, 20:00", d.title)
        assertEquals("чатов 12 · сообщений 148", d.counts)
        assertEquals("В Дела: поставлено 2, вопросов 1, закрыто само 1.", d.dela)
        assertEquals("digest:2026-10-13T2000", d.batch)
        val b = d.blocks()
        assertEquals(listOf("Ждут тебя", "По работе", "Дом", "Прочее"), b.filter { it.heading }.map { it.text })
        assertEquals("Иван Петров:", b[1].lead)
        assertEquals("Олег (Бета Групп · сделка):", b[2].lead)
        assertEquals("Бета Групп:", b[4].lead)
        // Без чисел и Дел — не пишется ничего; пустая сводка — одна строка.
        val bare = org.json.JSONObject(ex.toString()).put("chats", org.json.JSONObject.NULL).put("msgs", org.json.JSONObject.NULL)
            .put("dela", org.json.JSONObject.NULL).put("summary", org.json.JSONObject())
        val e = DigestNote.parse(bare)!!
        assertEquals("", e.counts)
        assertEquals("", e.dela)
        assertEquals(listOf("Ничего, что ждёт тебя."), e.blocks().map { it.text })
        // Без id — не сводка.
        assertEquals(null, DigestNote.parse(org.json.JSONObject().put("ended_at", "2026-10-13T20:00:00+03:00")))
    }

    @Test
    fun `раньше — сводки вида без той, что на карточке, свежие сверху, первые слова`() {
        val f = listOf(java.io.File("../server/contract/dela-digest.json"), java.io.File("server/contract/dela-digest.json")).first { it.isFile }
        val ex = org.json.JSONObject(f.readText()).getJSONObject("digest_object").getJSONObject("example")
        fun copy(id: String, ended: String, label: String) =
            org.json.JSONObject(ex.toString()).put("id", id).put("ended_at", ended).put("label", label)
        val resp = org.json.JSONObject()
            .put("last", ex)
            .put("items", org.json.JSONArray()
                .put(ex)
                .put(copy("b", "2026-10-13T11:00:00+03:00", "вт 13.10, 11:00 · ночь"))
                .put(copy("c", "2026-10-13T15:00:00+03:00", "вт 13.10, 15:00"))
                .put(org.json.JSONObject().put("label", "без id"))
                .put(copy("b", "2026-10-13T11:00:00+03:00", "повтор")))
            .put("days", 2)
        val list = DigestNote.earlier(resp, ex.getString("id"))
        assertEquals(listOf("c", "b"), list.map { it.id })
        assertEquals(listOf("вт 13.10, 15:00", "вт 13.10, 11:00 · ночь"), list.map { it.title })
        // Пустой ответ (сводок за окно нет) — пустой список, а не сбой.
        assertTrue(DigestNote.earlier(org.json.JSONObject().put("last", org.json.JSONObject.NULL), "x").isEmpty())
        // Первые слова — пункты без заголовков разделов, длинное — с многоточием.
        val p = list.first().preview()
        assertTrue(p, p.startsWith("Иван Петров: ждёт модель по фондам до пятницы · Олег (Бета Групп · сделка):"))
        assertTrue(p.length <= DigestNote.PREVIEW_MAX + 1 && p.endsWith("…"))
        assertEquals("Ничего, что ждёт тебя.", DigestNote(0L, "").copy(parsed = DigestNote.blocksOf(null)).preview())
    }

    @Test
    fun `кто и чат — как у сервера`() {
        assertEquals("Иван Петров", DigestNote.whereOf("Иван Петров", "Иван Петров"))
        assertEquals("Олег (Бета Групп · сделка)", DigestNote.whereOf("Олег", "Бета Групп · сделка"))
        assertEquals("Олег", DigestNote.whereOf("Олег", ""))
        assertEquals("Чат", DigestNote.whereOf("", "Чат"))
        assertEquals("В Дела: новых дел нет.", DigestNote.delaLine(org.json.JSONObject()))
    }

    // ---------------------------------------------------------------- Шаббат

    @Test
    fun `Шаббат в Москве — свечи около 17 09, исход около 18 20`() {
        val msk = ZoneId.of("Europe/Moscow")
        val tue = LocalDate.of(2026, 10, 13).atTime(21, 0).atZone(msk).toInstant().toEpochMilli()
        val t = ShabbatTimes.next(tue, 55.7558, 37.6173, msk)
        assertNotNull(t)
        assertEquals(LocalDate.of(2026, 10, 16), t!!.friday)
        val candles = java.time.Instant.ofEpochMilli(t.candles).atZone(msk).toLocalTime()
        val exit = java.time.Instant.ofEpochMilli(t.exit).atZone(msk).toLocalTime()
        assertTrue("свечи $candles", kotlin.math.abs(candles.toSecondOfDay() - 17 * 3600 - 9 * 60) <= 5 * 60)
        assertTrue("исход $exit", kotlin.math.abs(exit.toSecondOfDay() - 18 * 3600 - 20 * 60) <= 8 * 60)
        assertTrue(!t.now)
        // В субботу днём — нынешний Шаббат идёт; после исхода — следующий.
        val sat = LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(msk).toInstant().toEpochMilli()
        assertTrue(ShabbatTimes.next(sat, 55.7558, 37.6173, msk)!!.now)
        val satNight = LocalDate.of(2026, 10, 17).atTime(22, 0).atZone(msk).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 23), ShabbatTimes.next(satNight, 55.7558, 37.6173, msk)!!.friday)
    }
}
