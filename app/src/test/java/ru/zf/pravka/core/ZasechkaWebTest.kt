package ru.zf.pravka.core

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ZasechkaStore

/**
 * Команды Засечки из веба (`core/ZasechkaWeb.kt`, контракт `server/contract/dela-zasechka.json`): что телефон делает с
 * «Начать», «Стоп», «Поправить» с компьютера при своей ленте. Свежее слово — телефона; повтор не заводит дело дважды.
 */
class ZasechkaWebTest {

    private val msk: ZoneId = ZoneId.of("Europe/Moscow")
    private val day: LocalDate = LocalDate.of(2026, 10, 14)
    private fun t(h: Int, m: Int): Long = day.atTime(h, m).atZone(msk).toInstant().toEpochMilli()
    private val now = t(12, 0)
    private val cats = listOf("Работа: текущая", "Еда", "Сон")

    private fun e(id: Long, start: Long, end: Long, title: String, category: String = "Работа: текущая") = ZasechkaStore.Entry(
        id = id, start = start, end = end, raw = title, title = title, category = category, client = "", useful = 0,
        source = "voice", createdAt = start,
    )

    private val lenta = listOf(
        e(1, t(0, 0), t(7, 0), "Сон", "Сон"),
        e(2, t(7, 0), t(7, 30), "Завтрак", "Еда"),
        e(3, t(7, 30), 0L, "Тизер для Альфы"),
    )

    private fun cmd(kind: String, args: JSONObject, saidAt: Long = t(11, 50)) =
        ZasechkaWeb.Cmd("c1", kind, args, saidAt, "wait", "")

    @Test
    fun `пример контракта разбирается — по порядку нажатия, взятая чужим — с именем`() {
        val f = listOf(File("../server/contract/dela-zasechka.json"), File("server/contract/dela-zasechka.json")).first { it.isFile }
        val example = JSONObject(f.readText()).getJSONObject("sync").getJSONArray("example")
        val one = ZasechkaWeb.parse(example).single()
        assertEquals("start", one.kind)
        assertEquals("Тизер для Альфы", one.args.getString("title"))
        assertEquals("", one.takenBy)
        val two = JSONArray()
            .put(JSONObject().put("id", "b").put("kind", "stop").put("said_at", "2026-10-14T10:05:00+03:00").put("taken_by", "fold-ab12cd"))
            .put(JSONObject().put("id", "a").put("kind", "start").put("said_at", "2026-10-14T10:00:00.123+03:00"))
            .put(JSONObject().put("kind", "start").put("said_at", "2026-10-14T10:00:00+03:00"))   // без id — мимо
        assertEquals(listOf("a", "b"), ZasechkaWeb.parse(two).map { it.id })
        assertEquals("fold-ab12cd", ZasechkaWeb.parse(two)[1].takenBy)
    }

    @Test
    fun `начать — с мига нажатия, категория как в справочнике, из дела — с делом`() {
        val p = ZasechkaWeb.plan(
            cmd("start", JSONObject().put("title", " Звонок Ивану ").put("category", "работа: ТЕКУЩАЯ").put("task_id", "t-1").put("project_id", "p-1")),
            lenta, cats, now, msk,
        )
        assertEquals(ZasechkaWeb.Plan.Start(t(11, 50), "Звонок Ивану", "Работа: текущая", "", "t-1", "p-1"), p)
        assertEquals("начато «Звонок Ивану» с 11:50", ZasechkaWeb.note(p, msk))
    }

    @Test
    fun `повтор после обрыва связи — уже сделано, второй раз не заводится`() {
        val after = lenta + e(4, t(11, 50), 0L, "Звонок Ивану")
        val p = ZasechkaWeb.plan(cmd("start", JSONObject().put("title", "Звонок Ивану")), after, cats, now, msk)
        assertTrue(p is ZasechkaWeb.Plan.Already)
        assertEquals(4L, (p as ZasechkaWeb.Plan.Already).entryId)
    }

    @Test
    fun `на телефоне начали позже нажатия — свежее слово телефона`() {
        val after = lenta.take(2) + e(3, t(7, 30), t(11, 55), "Тизер для Альфы") + e(5, t(11, 55), 0L, "Обед", "Еда")
        val p = ZasechkaWeb.plan(cmd("start", JSONObject().put("title", "Звонок")), after, cats, now, msk)
        assertTrue(p.toString(), p is ZasechkaWeb.Plan.Refuse && "позже нажатия" in p.why)
    }

    @Test
    fun `старше полусуток и пустое — отказ словами`() {
        val old = ZasechkaWeb.plan(cmd("start", JSONObject().put("title", "x"), saidAt = now - 13 * 3_600_000L), lenta, cats, now, msk)
        assertTrue(old is ZasechkaWeb.Plan.Refuse && "устарела" in old.why)
        val empty = ZasechkaWeb.plan(cmd("start", JSONObject().put("title", "  ")), lenta, cats, now, msk)
        assertTrue(empty is ZasechkaWeb.Plan.Refuse)
        val alien = ZasechkaWeb.plan(cmd("fly", JSONObject()), lenta, cats, now, msk)
        assertTrue(alien is ZasechkaWeb.Plan.Refuse && "обнови" in alien.why)
    }

    @Test
    fun `стоп — только той записи, что веб видел идущей`() {
        assertEquals(ZasechkaWeb.Plan.Stop(3, t(11, 50)), ZasechkaWeb.plan(cmd("stop", JSONObject().put("entry_id", 3)), lenta, cats, now, msk))
        val closed = lenta.take(2) + e(3, t(7, 30), t(11, 50), "Тизер для Альфы")
        assertTrue(ZasechkaWeb.plan(cmd("stop", JSONObject().put("entry_id", 3)), closed, cats, now, msk) is ZasechkaWeb.Plan.Already)
        val earlier = lenta.take(2) + e(3, t(7, 30), t(10, 0), "Тизер для Альфы")
        val r = ZasechkaWeb.plan(cmd("stop", JSONObject().put("entry_id", 3)), earlier, cats, now, msk)
        assertTrue(r is ZasechkaWeb.Plan.Refuse && "уже остановлено" in r.why)
        assertTrue(ZasechkaWeb.plan(cmd("stop", JSONObject().put("entry_id", 99)), lenta, cats, now, msk) is ZasechkaWeb.Plan.Refuse)
    }

    private fun edit(id: Long, was: JSONObject, set: JSONObject) =
        cmd("edit", JSONObject().put("entry_id", id).put("day", day.toString()).put("was", was).put("set", set))

    @Test
    fun `поправить — запись как видел веб, новое время местное, полночь — конец суток`() {
        val was = JSONObject().put("title", "Завтрак").put("category", "Еда").put("start", "07:00").put("end", "07:30")
        val p = ZasechkaWeb.plan(edit(2, was, JSONObject().put("title", "Завтрак с детьми").put("end", "07:40")), lenta, cats, now, msk)
        assertTrue(p.toString(), p is ZasechkaWeb.Plan.Edit)
        p as ZasechkaWeb.Plan.Edit
        assertEquals(listOf("Завтрак с детьми", t(7, 0), t(7, 40), "edit"), listOf(p.entry.title, p.entry.start, p.entry.end, p.entry.source))
        assertEquals("поправлено «Завтрак с детьми»: название, конец", ZasechkaWeb.note(p, msk))
        assertEquals(day.plusDays(1).atStartOfDay(msk).toInstant().toEpochMilli(), ZasechkaWeb.at(day, "00:00", msk, endOfDay = true))
        assertEquals(t(9, 5), ZasechkaWeb.at(day, "9:05", msk))
        assertEquals(null, ZasechkaWeb.at(day, "25:00", msk))
    }

    @Test
    fun `поправить — поправили на телефоне раньше, уже так, у идущего конца нет`() {
        val stale = JSONObject().put("title", "Завтрак").put("category", "Еда").put("start", "06:50").put("end", "07:30")
        val r = ZasechkaWeb.plan(edit(2, stale, JSONObject().put("title", "Х")), lenta, cats, now, msk)
        assertTrue(r is ZasechkaWeb.Plan.Refuse && "поправили на телефоне" in r.why)
        val was = JSONObject().put("title", "Завтрак").put("category", "Еда").put("start", "07:00").put("end", "07:30")
        assertTrue(ZasechkaWeb.plan(edit(2, was, JSONObject().put("title", "Завтрак")), lenta, cats, now, msk) is ZasechkaWeb.Plan.Already)
        val open = JSONObject().put("title", "Тизер для Альфы").put("category", "Работа: текущая").put("start", "07:30").put("end", "")
        val noEnd = ZasechkaWeb.plan(edit(3, open, JSONObject().put("end", "11:00")), lenta, cats, now, msk)
        assertTrue(noEnd is ZasechkaWeb.Plan.Refuse && "Стоп" in noEnd.why)
        val renamed = ZasechkaWeb.plan(edit(3, open, JSONObject().put("start", "07:45").put("category", "еда")), lenta, cats, now, msk)
        assertTrue(renamed is ZasechkaWeb.Plan.Edit && renamed.entry.open && renamed.entry.category == "Еда" && renamed.entry.start == t(7, 45))
    }
}
