package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * «Подскажи» двумя заходами (шаг 2 петли, docs/dela-phone-23.md) против контракта с сервером
 * (`server/contract/dela-asks.json`): та же таблица фаз и тот же пример очереди, что проверяет сервер
 * (`tests/test_dela_asks.py`), — одно правило в трёх местах (сервер, веб, телефон). Данные выдуманные.
 */
class AsksTest {

    private fun contract(): JSONObject {
        val f = listOf(File("../server/contract/dela-asks.json"), File("server/contract/dela-asks.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** Миг по Москве: «2026-10-15T15:00». */
    private fun msk(local: String): Long = LocalDateTime.parse(local).atZone(Asks.MSK).toInstant().toEpochMilli()

    /** Поверх: поля [over] — поверх [base] (ритм примера поверх ритма таблицы). */
    private fun over(base: JSONObject, over: JSONObject?): JSONObject {
        val out = JSONObject(base.toString())
        over?.keys()?.forEach { k -> out.put(k, over.get(k)) }
        return out
    }

    // ---------------------------------------------------------------- заводское

    @Test
    fun `заводское — как в контракте`() {
        val svod = contract().getJSONObject("svod")
        val f = svod.getJSONObject("asks.rhythm").getJSONObject("factory")
        val rh = Asks.FACTORY
        assertEquals(f.getString("evening"), rh.evening)
        assertEquals(f.getString("fri"), rh.fri)
        assertEquals(f.getString("fri_winter"), rh.friWinter)
        assertEquals(f.getJSONArray("winter").getString(0) to f.getJSONArray("winter").getString(1), rh.winter)
        assertEquals(f.getString("today"), rh.today)
        assertEquals(f.getString("night_end"), rh.nightEnd)
        assertEquals(f.getInt("today_max"), rh.todayMax)
        assertEquals(f.getInt("evening_max"), rh.eveningMax)
        assertEquals(setOf("evening", "fri", "fri_winter", "winter", "today", "night_end", "today_max", "evening_max"), f.keys().asSequence().toSet())
        val w = svod.getJSONObject("day.quiet").getJSONObject("factory").getJSONObject("weekend")
        assertEquals(Asks.Weekend(w.getInt("dow"), w.getString("from")), Asks.QUIET_FACTORY.weekend)
        // Флаг — тот, что в контракте.
        assertEquals(contract().getJSONObject("features").getString("flag"), Dela.FEATURE_ASKS)
        // Нет записи Свода — заводское.
        assertEquals(Asks.FACTORY, Asks.rhythm(null))
        assertEquals(Asks.QUIET_FACTORY, Asks.quiet(null))
    }

    // ---------------------------------------------------------------- фазы

    @Test
    fun `таблица фаз контракта — фаза и ближайший заход`() {
        val ph = contract().getJSONObject("phases")
        val ex = ph.getJSONArray("examples")
        assertTrue(ex.length() >= 20)
        for (i in 0 until ex.length()) {
            val e = ex.getJSONObject(i)
            val rh = Asks.rhythm(over(ph.getJSONObject("rhythm"), e.optJSONObject("rhythm")))
            val q = Asks.quiet(if (e.has("quiet")) e.get("quiet") else ph.getJSONObject("quiet"))
            val at = ms(e.getString("at"))
            val why = e.optString("_") + " — " + e.getString("at")
            assertEquals(why, e.getString("phase"), Asks.phase(at, rh, q).key)
            val n = Asks.next(at, rh, q)
            assertEquals(why, e.getString("next"), n?.label)
            assertEquals(why, e.getString("next_phase"), n?.phase?.key)
        }
    }

    @Test
    fun `часы — московские, а не пояс телефона`() {
        val tz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Vladivostok"))
            assertEquals(Asks.Phase.TODAY, Asks.phase(msk("2026-10-15T15:00"), Asks.FACTORY, Asks.QUIET_FACTORY))
            assertEquals("2026-10-15", Asks.day(msk("2026-10-15T23:30")))
        } finally {
            java.util.TimeZone.setDefault(tz)
        }
    }

    // ---------------------------------------------------------------- Свод

    @Test
    fun `Свод поверх заводского по полю, кривое — заводское`() {
        val rh = Asks.rhythm(JSONObject("""{"today":"16:30","night_end":"4:00","today_max":3,"evening_max":0,"evening":"кривое","future":{"x":1}}"""))
        assertEquals("16:30", rh.today)
        assertEquals("04:00", rh.nightEnd)
        assertEquals(3, rh.todayMax)
        assertEquals(Asks.FACTORY.eveningMax, rh.eveningMax)
        assertEquals(Asks.FACTORY.evening, rh.evening)
        // Дробное, да/нет, строка, время без двух цифр минут, несуществующий день окна — заводское.
        val bad = Asks.rhythm(JSONObject("""{"today_max":5.5,"evening_max":true,"fri":"9:5","fri_winter":1330,"winter":["11-31","01-15"],"night_end":null}"""))
        assertEquals(Asks.FACTORY, bad)
        assertEquals("09:05", Asks.rhythm(JSONObject("""{"fri":" 9:05 "}""")).fri)
        assertEquals("02-29" to "03-01", Asks.rhythm(JSONObject("""{"winter":["02-29","03-01"]}""")).winter)
        // Не словарь — заводское целиком.
        assertEquals(Asks.FACTORY, Asks.rhythm(JSONArray("""["кривое"]""")))
        assertEquals(Asks.FACTORY, Asks.rhythm("18:00"))
        // day.quiet: только weekend; dow вне 1…7 — заводской, from — свой; null и false — тишины нет.
        assertEquals(Asks.Weekend(5, "16:00"), Asks.quiet(JSONObject("""{"weekend":{"dow":9,"from":"16:00"},"daily":[{"from":"21:00","to":"09:15"}]}""")).weekend)
        assertNull(Asks.quiet(JSONObject("""{"weekend":null}""")).weekend)
        assertNull(Asks.quiet(JSONObject("""{"weekend":false}""")).weekend)
        assertEquals(Asks.QUIET_FACTORY, Asks.quiet(JSONObject("""{"weekend":"кривое"}""")))
        assertEquals(Asks.QUIET_FACTORY, Asks.quiet(JSONObject("""{"v":1,"daily":[]}""")))
        assertEquals(Asks.QUIET_FACTORY, Asks.quiet(JSONArray("""["кривое"]""")))
    }

    @Test
    fun `ритм и тишина — из кэша Свода`() {
        val was = Svod.current
        try {
            Svod.current = mapOf(
                Svod.ASKS_RHYTHM to Svod.Entry(Svod.ASKS_RHYTHM, value = """{"today":"15:30","today_max":"много"}"""),
                Svod.DAY_QUIET to Svod.Entry(Svod.DAY_QUIET, value = """{"v":1,"weekend":{"dow":5,"from":"14:45"}}"""),
            )
            assertEquals("15:30", Asks.rhythm().today)
            assertEquals(5, Asks.rhythm().todayMax)
            assertEquals(Asks.Weekend(5, "14:45"), Asks.quiet().weekend)
            assertEquals(Asks.Phase.QUIET, Asks.phase(ms("2026-10-16T14:50:00+03:00")))
            assertEquals(Asks.Phase.BEFORE, Asks.phase(ms("2026-10-15T15:10:00+03:00")))
        } finally {
            Svod.current = was
        }
    }

    // ---------------------------------------------------------------- очередь

    private fun sugs(e: JSONObject, me: String = ""): List<Dela.Suggestion> {
        val a = e.getJSONArray("suggestions")
        return (0 until a.length()).map { i -> Dela.suggestion(a.getJSONObject(i))!!.copy(forUser = me) }
    }

    private fun ids(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun checkGroups(why: String, expect: JSONArray, got: List<Asks.Group>) {
        assertEquals(why, expect.length(), got.size)
        for (i in 0 until expect.length()) {
            val g = expect.getJSONObject(i)
            val x = got[i]
            assertEquals("$why: группа $i", g.getString("kind"), x.kind)
            assertEquals("$why: группа $i", if (g.isNull("id")) null else g.getString("id"), x.id)
            assertEquals("$why: группа $i", g.getString("title"), x.title)
            assertEquals("$why: группа $i", g.getInt("n"), x.n)
            assertEquals("$why: группа $i", g.getInt("urgent"), x.urgent)
            assertEquals("$why: группа $i", ids(g.getJSONArray("ids")), x.items.map { it.id })
        }
    }

    @Test
    fun `пример очереди контракта — группы, порядок, сгорающее, план числом`() {
        val ex = contract().getJSONObject("queue").getJSONArray("examples")
        for (i in 0 until ex.length()) {
            val e = ex.getJSONObject(i)
            val expect = e.getJSONObject("expect")
            val day = e.getString("day")
            // Голое правило.
            val (gs, hot, plan) = Asks.groups(sugs(e), day)
            checkGroups("groups", expect.getJSONArray("groups"), gs)
            assertEquals(ids(expect.getJSONArray("urgent_ids")), hot.map { it.id })
            assertEquals(expect.getInt("plan"), plan)
            // Через копию синка — как его видит «Сегодня» (день и фаза — по часам Москвы).
            val s = Dela.Snapshot(suggestions = sugs(e, "me").associateBy { it.id }, features = setOf(Dela.FEATURE_ASKS, Dela.FEATURE_PLAN))
            val q = Asks.queue(s, "me", msk(day + "T15:00"), Asks.FACTORY, Asks.QUIET_FACTORY)
            assertTrue(q.on)
            assertEquals(Asks.Phase.TODAY, q.phase)
            assertEquals(expect.getInt("total"), q.total)
            assertEquals(expect.getInt("urgent"), q.urgent.size)
            assertEquals(expect.getInt("rest"), q.rest)
            assertEquals(expect.getInt("plan"), q.plan)
            checkGroups("queue", expect.getJSONArray("groups"), q.groups)
            assertEquals(ids(expect.getJSONArray("urgent_ids")), q.urgent.map { it.id })
            // Чужие и решённые — не в очереди.
            val other = s.copy(suggestions = s.suggestions + mapOf(
                "x1" to Dela.Suggestion("x1", forUser = "other", payload = """{"urgency":"today","urgent_on":"$day"}"""),
                "x2" to Dela.Suggestion("x2", forUser = "me", status = "accepted"),
            ))
            assertEquals(expect.getInt("total"), Asks.queue(other, "me", msk(day + "T15:00"), Asks.FACTORY, Asks.QUIET_FACTORY).total)
        }
    }

    private fun ask(id: String, created: String, payload: String = "{}", batch: String = "", source: String = "meeting") = Dela.Suggestion(
        id = id, forUser = "me", source = source, batchTitle = batch, createdAt = created, payload = payload,
    )

    @Test
    fun `сгорает только в день urgent_on, предмет без штампа — мелочь`() {
        val hot = ask("a", "2026-10-15T10:00:00+03:00", """{"urgency":"today","urgent_on":"2026-10-15"}""")
        assertTrue(Asks.urgent(hot, "2026-10-15"))
        assertFalse("завтра тот же вопрос — вечерний", Asks.urgent(hot, "2026-10-16"))
        assertFalse(Asks.urgent(ask("b", "", """{"urgency":"evening"}"""), "2026-10-15"))
        assertFalse(Asks.urgent(ask("c", "", """{"urgency":"today"}"""), "2026-10-15"))
        // Без about — мелочь по пачке, без пачки — «Прочее»; кривой вид — тоже мелочь; кандидат плана — plan.
        assertEquals(Asks.About("small", null, "Telegram 14.10"), Asks.about(ask("d", "", "{}", "Telegram 14.10")))
        assertEquals(Asks.About("small", null, "Прочее"), Asks.about(ask("e", "", "не JSON")))
        assertEquals("small", Asks.about(ask("f", "", """{"about":{"kind":"сделка","title":"x"}}""", "Пачка")).kind)
        assertEquals("plan", Asks.about(ask("g", "", "{}", "Сейчас на пт", source = Dela.PLAN_SOURCE)).kind)
        assertEquals(Asks.About("client", "c1", "ООО Ромашка"),
            Asks.about(ask("h", "", """{"about":{"kind":"client","id":"c1","title":"ООО Ромашка"}}""")))
        // Тот же вопрос с новым payload (новый rev) — новый штамп, а не старый из кэша.
        val re = hot.copy(rev = 2, payload = """{"urgency":"evening"}""")
        assertFalse(Asks.urgent(re, "2026-10-15"))
    }

    @Test
    fun `имя группы — свежее из копии, вопрос без created_at — самый старый`() {
        val s = Dela.Snapshot(
            projects = mapOf("c1" to Dela.Project(id = "c1", name = "ООО Ромашка-Плюс")),
            people = mapOf("p1" to Dela.Person(id = "p1", name = "Иван Тестов", short = "Ваня")),
            suggestions = listOf(
                ask("a", "2026-10-15T10:00:00+03:00", """{"about":{"kind":"client","id":"c1","title":"ООО Ромашка"}}"""),
                ask("b", "2026-10-14T10:00:00+03:00", """{"about":{"kind":"person","id":"p1","title":"Иван"}}"""),
                ask("c", "", """{"about":{"kind":"person","id":"p1","title":"Иван"}}"""),
                ask("d", "2026-10-13T10:00:00+03:00", """{"about":{"kind":"meeting","title":""}}"""),
            ).associateBy { it.id },
            features = setOf(Dela.FEATURE_ASKS),
        )
        val q = Asks.queue(s, "me", msk("2026-10-15T20:00"), Asks.FACTORY, Asks.QUIET_FACTORY)
        assertEquals(listOf("ООО Ромашка-Плюс", "Ваня", "Встреча"), q.groups.map { it.title })
        assertEquals(listOf("c", "b"), q.groups[1].items.map { it.id })
    }

    @Test
    fun `без флага — прежний список, свежие сверху`() {
        val s = Dela.Snapshot(
            suggestions = listOf(
                ask("old", "2026-10-13T10:00:00+03:00", """{"urgency":"today","urgent_on":"2026-10-15"}"""),
                ask("new", "2026-10-15T10:00:00+03:00"),
                ask("mid", "2026-10-14T10:00:00+03:00"),
            ).associateBy { it.id },
        )
        val q = Asks.queue(s, "me", msk("2026-10-15T10:00"), Asks.FACTORY, Asks.QUIET_FACTORY)
        assertFalse(q.on)
        assertEquals(listOf("new", "mid", "old"), q.all.map { it.id })
        assertEquals(DelaViews.asks(s, "me").map { it.id }, q.all.map { it.id })
        assertTrue(q.groups.isEmpty())
        val c = Asks.card(q, null, 2)!!
        assertEquals(Asks.Mode.LEGACY, c.mode)
        assertEquals(3, c.count)
        assertEquals(listOf("new", "mid"), c.rows.map { it.id })
        assertEquals(1, c.more)
        // Пусто — карточки нет.
        assertNull(Asks.card(Asks.queue(Dela.Snapshot(), "me", msk("2026-10-15T10:00")), null, 4))
    }

    // ---------------------------------------------------------------- строка и карточка

    /** Очередь: [hot] сгорающих сегодня (15.10) и [cold] вечерних у разных людей — на миг [at] по Москве. */
    private fun queueAt(at: String, hot: Int, cold: Int, rh: Asks.Rhythm = Asks.FACTORY): Asks.Queue {
        val day = at.take(10)
        val list = (1..hot).map { ask("h$it", "2026-10-15T0$it:00:00+03:00", """{"urgency":"today","urgent_on":"$day","about":{"kind":"person","title":"Иван Тестов"}}""") } +
            (1..cold).map { ask("c$it", "2026-10-14T1$it:00:00+03:00", """{"urgency":"evening","about":{"kind":"client","id":"k$it","title":"ООО Ромашка $it"}}""") }
        val s = Dela.Snapshot(suggestions = list.associateBy { it.id }, features = setOf(Dela.FEATURE_ASKS))
        return Asks.queue(s, "me", msk(at), rh, Asks.QUIET_FACTORY)
    }

    @Test
    fun `строка свёрнутой очереди — четыре случая`() {
        // Чт до 15:00: сгорающее к 15:00, остальное к вечеру.
        assertEquals("Подскажи · к 15:00: 1 · к вечеру: 6", Asks.foldText(queueAt("2026-10-15T10:00", 1, 6)))
        // Нулевые части не пишутся.
        assertEquals("Подскажи · к вечеру: 6", Asks.foldText(queueAt("2026-10-15T10:00", 0, 6)))
        // Пт до захода: ближайший заход — вечерний, сразу всё.
        assertEquals("Подскажи · к 14:00: 7", Asks.foldText(queueAt("2026-10-16T10:00", 1, 6)))
        // С 15:00 без сгорающего — «к вечеру».
        assertEquals("Подскажи · к вечеру: 6", Asks.foldText(queueAt("2026-10-15T16:00", 0, 6)))
        // Выходные — до понедельника тихо.
        assertEquals("Подскажи: 7 — до понедельника тихо", Asks.foldText(queueAt("2026-10-17T12:00", 1, 6)))
        // Ночь на понедельник — ещё тихо, заход сегодня в 15:00.
        assertEquals("Подскажи: 7 — до 15:00 тихо", Asks.foldText(queueAt("2026-10-19T04:00", 1, 6)))
    }

    @Test
    fun `пределы — пять сгорающих и семь вечером, дальше «в Новом»`() {
        val today = Asks.card(queueAt("2026-10-15T15:30", 6, 3), null, 4)!!
        assertEquals(Asks.Mode.URGENT, today.mode)
        assertEquals(5, today.rows.size)
        assertEquals(1, today.more)
        assertEquals("к вечеру: 3", today.fold)
        val evening = Asks.card(queueAt("2026-10-15T18:30", 2, 7), null, 4)!!
        assertEquals(Asks.Mode.GROUPED, evening.mode)
        assertEquals(7, evening.groups.sumOf { it.second.size })
        assertEquals(2, evening.more)
        assertEquals(9, evening.count)
        // Первая группа — со сгорающим, её подзаголовок — с числом всей группы.
        val (g, items) = evening.groups.first()
        assertEquals("Иван Тестов · 2", Asks.groupHead(g))
        assertEquals("сгорает 2", Asks.groupUrgent(g))
        assertEquals(2, items.size)
        assertNull(Asks.groupUrgent(evening.groups[1].first))
        // Свод: свои пределы.
        val narrow = Asks.card(queueAt("2026-10-15T18:30", 2, 7, Asks.FACTORY.copy(eveningMax = 3)), null, 4)!!
        assertEquals(3, narrow.groups.sumOf { it.second.size })
        assertEquals(6, narrow.more)
    }
}
