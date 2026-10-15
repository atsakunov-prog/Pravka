package ru.zf.pravka.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замки и «Только тебе» на телефоне (аудит 15.10.2026, №8–11; задание `docs/dela-phone-22.md`, правило 26 сервера):
 * телефон читает новые поля синка и видов, не падает на скрытых и не шлёт новое старому серверу. Контракт — раздел
 * `privacy` и виды `server/contract/dela-crm.json`. Люди выдуманные.
 */
class DelaPrivacyTest {

    private fun file(name: String): JSONObject {
        val f = listOf(File("../server/contract/$name"), File("server/contract/$name")).first { it.isFile }
        return JSONObject(f.readText())
    }

    private val crm by lazy { file("dela-crm.json") }
    private fun view(name: String): JSONObject = crm.getJSONObject("views_crm").getJSONObject(name).getJSONObject("response")

    private val today = "2026-10-15"
    private val now = java.time.OffsetDateTime.parse("2026-10-15T12:00:00+03:00").toInstant().toEpochMilli()

    private fun sync(features: List<String>, people: JSONArray = JSONArray(), comments: JSONArray = JSONArray(), users: JSONArray = JSONArray()): Dela.Snapshot =
        Dela.merge(
            Dela.Snapshot(),
            JSONObject().put("ok", true).put("full", true).put("seq", 10).put("today", today)
                .put("features", JSONArray(features)).put("people", people).put("comments", comments).put("users", users)
                .put("tasks", JSONArray().put(JSONObject().put("id", "t1").put("title", "Ромашка: договор").put("owner_id", "sasha").put("status", "open"))),
            now,
        )

    // ------------------------------------------------------------ синк

    @Test
    fun `приватный комментарий из синка — с замком, обычный — без, и копия на диске это помнит`() {
        val s = sync(
            listOf("privacy"),
            comments = JSONArray()
                .put(JSONObject().put("id", "c1").put("task_id", "t1").put("author_id", "sasha").put("text", "только мне: проверить цифры").put("private", true))
                .put(JSONObject().put("id", "c2").put("task_id", "t1").put("author_id", "sasha").put("text", "созвонились")),
        )
        assertTrue(s.comments.getValue("c1").private)
        assertFalse(s.comments.getValue("c2").private)
        val back = Dela.fromJson(Dela.toJson(s))
        assertTrue(back.comments.getValue("c1").private)
        assertTrue(s.privacyOn)
    }

    @Test
    fun `человек по ярусу — скрытое пусто, не падаем, tier читается, старый сервер — без яруса`() {
        val work = JSONObject().put("id", "p1").put("name", "Иван Тестов").put("tier", "work").put("phones", JSONArray().put("+70000000000"))
            .put("traits", JSONObject.NULL).put("note", JSONObject.NULL).put("seeks", JSONObject.NULL).put("aliases", JSONArray())
            .put("birth_day", JSONObject.NULL).put("hub", false).put("owner_id", "sasha")
        val colleague = JSONObject().put("id", "p2").put("name", "Ольга Тестова").put("short", "Оля").put("tier", "colleague")
            .put("phones", JSONArray()).put("emails", JSONArray()).put("telegram_username", JSONObject.NULL).put("user_id", "olga")
        val old = JSONObject().put("id", "p3").put("name", "Пётр Старый").put("traits", "любит цифры")
        val s = sync(listOf("privacy"), people = JSONArray().put(work).put(colleague).put(old),
            users = JSONArray().put(JSONObject().put("id", "anna").put("name", "Анна").put("role", "member").put("clients", "all")))
        val p1 = s.people.getValue("p1")
        assertEquals("work", p1.tier)
        assertEquals("", p1.traits)
        assertEquals("", p1.note)
        assertEquals(0, p1.birthDay)
        assertEquals("sasha", p1.ownerId)
        assertEquals("colleague", s.people.getValue("p2").tier)
        assertEquals("", s.people.getValue("p3").tier)
        assertEquals("p1", Dela.fromJson(Dela.toJson(s)).people.getValue("p1").let { it.id + if (it.tier == "work") "" else "!" })
        // Строки карточки: у сотрудника пометок нет — строк нет, ничего не рисуется пустым.
        val info = DelaViews.personInfo(p1, s).toMap()
        assertEquals("+70000000000", info["Телефон"])
        assertNull(info["Характер"])
        // Видит ли целиком: ярус сильнее роли; у старого сервера — роль или автор.
        assertFalse(DelaCrm.personWhole(p1, s, "anna"))
        assertTrue(DelaCrm.personWhole(p1.copy(tier = "owner"), s, "anna"))
        assertFalse(DelaCrm.personWhole(s.people.getValue("p3"), s, "anna"))
        assertTrue(DelaCrm.personWhole(s.people.getValue("p3").copy(ownerId = "anna"), s, "anna"))
    }

    @Test
    fun `пометки владельцу — отдельно, под «Только тебе», контакты — в строках`() {
        val p = Dela.Person("p", "Иван Тестов", phones = listOf("+70000000001"), traits = "любит цифры", note = "был в Ромашке",
            birthDay = 3, birthMonth = 4, cadence = "month", tier = "owner")
        val s = Dela.Snapshot(people = mapOf("p" to p))
        val open = DelaViews.personInfo(p, s, marksApart = true).toMap()
        assertEquals(setOf("Телефон"), open.keys)
        val marks = DelaViews.personMarks(p).toMap()
        assertEquals(setOf("День рождения", "Как часто", "Характер", "Заметка"), marks.keys)
        // Без флага (или не владелец) — как было: всё одним списком.
        assertEquals(DelaViews.personInfo(p, s).size, open.size + marks.size)
    }

    // ------------------------------------------------------------ виды

    @Test
    fun `хронология вида — team_sees и хозяин записи, замок словами`() {
        val row = DelaCrm.client(view("client")).timeline.first()
        assertEquals("all", row.teamSees)
        assertEquals("sasha", row.ownerId)
        assertNull(DelaCrm.lockWord(row))
        assertEquals("видишь только ты", DelaCrm.lockWord(row.copy(private = true)))
        assertEquals("команде — без итога", DelaCrm.lockWord(row.copy(teamSees = "fact")))
        assertEquals("видишь только ты", DelaCrm.lockWord(row.copy(digestRef = "digest:2026-10-15")))
        val priv = DelaCrm.interaction(JSONObject().put("id", "i").put("at", "2026-10-15T10:00:00+03:00").put("kind", "note")
            .put("summary", "только мне").put("private", true).put("team_sees", "none").put("owner_id", "sasha"))!!
        assertTrue(priv.private)
        assertEquals("none", priv.teamSees)
    }

    @Test
    fun `«скрыть» — своя общая запись при флаге, крестик у встречи и дайджеста — хозяину`() {
        val mine = DelaCrm.Interaction("i", "2026-10-15T10:00:00+03:00", "call", "созвон", ownerId = "sasha", teamSees = "all")
        assertTrue(DelaCrm.canHide(mine, "sasha", privacyOn = true))
        assertFalse(DelaCrm.canHide(mine, "sasha", privacyOn = false))
        assertFalse(DelaCrm.canHide(mine, "anna", privacyOn = true))
        assertFalse(DelaCrm.canHide(mine.copy(private = true), "sasha", privacyOn = true))
        val meeting = mine.copy(source = "meeting")
        assertTrue(DelaCrm.canDelete(meeting, "sasha"))
        assertFalse(DelaCrm.canDelete(meeting, "anna"))
        assertTrue(DelaCrm.canDelete(mine, "anna"))
        // «Скрыть» поверх кэша — замок сразу, до ответа сервера.
        val hidden = DelaCrm.overlayTimeline(listOf(mine), listOf(DelaCrm.interactionHideOp("i")), Dela.Snapshot()) { true }.single()
        assertTrue(hidden.private)
        val op = DelaCrm.interactionHideOp("i")
        assertEquals("interaction.set", op.getString("op"))
        assertEquals(true, op.getJSONObject("set").getBoolean("private"))
    }

    @Test
    fun `«Только тебе» у видов — разбор, отсутствие поля у старого сервера — блока нет`() {
        val c = DelaCrm.client(view("client"))
        val n = assertNotNullAnd(c.insights).single()
        assertEquals("note", n.kind)
        assertEquals("заметка", n.kindWord)
        assertEquals(listOf("Иван"), n.people)
        assertEquals("new", n.state)
        assertNotNull(DelaCrm.dealView(view("deal")).insights)
        assertNotNull(DelaCrm.dossier(view("dossier")).insights)
        // Старый сервер поля не шлёт — null, не пустой список: блока нет вовсе.
        val old = JSONObject(view("client").toString()).apply { remove("insights") }
        assertNull(DelaCrm.client(old).insights)
        // Команде — пустой список: блок не рисуется (ему и владельцу нужна роль owner).
        assertEquals(emptyList<DelaCrm.PrivateNote>(), DelaCrm.client(JSONObject(view("client").toString()).put("insights", JSONArray())).insights)
        // Незнакомый вид этапа 6 — как есть.
        assertEquals("кто за столом", DelaCrm.PrivateNote("x", kind = "table", text = "т").kindWord)
        assertEquals("новое_слово", DelaCrm.PrivateNote("x", kind = "новое_слово", text = "т").kindWord)
    }

    @Test
    fun `операции «Только тебе» — по контракту, своё видно сразу`() {
        val add = DelaCrm.noteAddOp("Сомневается в сроках", personIds = listOf("p1"), id = "n1", opId = "o1")
        assertEquals("insight.add", add.getString("op"))
        val x = add.getJSONObject("insight")
        assertEquals("note", x.getString("kind"))
        assertEquals("phone", x.getString("source"))
        assertEquals("p1", x.getJSONArray("person_ids").getString(0))
        assertFalse(x.has("project_id"))
        val deal = DelaCrm.noteAddOp("т", projectId = "r", dealId = "d").getJSONObject("insight")
        assertEquals("r", deal.getString("project_id"))
        assertEquals("d", deal.getString("deal_id"))
        assertEquals("seen", DelaCrm.noteStateOp("n1", "seen").getJSONObject("set").getString("state"))
        assertEquals("insight.delete", DelaCrm.noteDeleteOp("n1").getString("op"))
        // Поверх кэша: новая — сразу, «Мимо» — свёрнута, убранная — ушла.
        val base = listOf(DelaCrm.PrivateNote("a", text = "старое", at = "2026-10-14T10:00:00+03:00"),
            DelaCrm.PrivateNote("b", text = "второе", at = "2026-10-13T10:00:00+03:00"))
        val ops = listOf(add, DelaCrm.noteStateOp("a", "dismissed"), DelaCrm.noteDeleteOp("b"))
        val got = DelaCrm.overlayNotes(base, ops) { o -> o.optJSONArray("person_ids")?.optString(0) == "p1" }
        assertEquals(listOf("a", "n1"), got.map { it.id }.sorted())
        assertTrue(got.first { it.id == "a" }.off)
        assertTrue(got.first { it.id == "n1" }.local)
        // Чужой карточке своё не подходит.
        assertEquals(listOf("a"), DelaCrm.overlayNotes(base, ops) { false }.map { it.id })
    }

    @Test
    fun `comment add с private — поле только когда true, и только при флаге`() {
        val plain = Dela.commentOp("t1", "созвонились")
        assertFalse(plain.getJSONObject("comment").has("private"))
        val mine = Dela.commentOp("t1", "только мне", private = true)
        assertTrue(mine.getJSONObject("comment").getBoolean("private"))
        // Своё поверх копии: приватный комментарий с замком сразу.
        val s = sync(listOf("privacy"))
        val over = Dela.overlay(s, listOf(mine), "sasha", today, "2026-10-15T12:00:00+03:00")
        assertTrue(over.comments.values.single().private)
        // Флаг: без privacy телефон поле не шлёт (вкладка зовёт commentOp с private = mineOnly && privacyOn).
        assertFalse(sync(emptyList()).privacyOn)
    }

    @Test
    fun `состав сделки — хозяин проекта, себя убрать можно, старый сервер — как раньше`() {
        val s = Dela.Snapshot(
            projects = mapOf("r" to Dela.Project("r", "ООО Ромашка", ownerId = "sasha")),
            users = mapOf("sasha" to Dela.User("sasha", "Владелец", role = "owner"), "anna" to Dela.User("anna", "Анна", personId = "pa")),
            people = mapOf("pa" to Dela.Person("pa", "Анна Тестова", userId = "anna")),
            features = setOf("privacy"),
        )
        assertTrue(DelaCrm.grantsProject(s, "sasha", "r"))
        assertFalse(DelaCrm.grantsProject(s, "anna", "r"))
        assertTrue(DelaCrm.grantsProject(s.copy(features = emptySet()), "anna", "r"))
        assertTrue(DelaCrm.grantsProject(s.copy(projects = mapOf("r" to Dela.Project("r", "ООО Ромашка", ownerId = "anna"))), "anna", "r"))
        assertTrue(DelaCrm.isMe(s, "anna", "pa"))
        assertFalse(DelaCrm.isMe(s, "anna", "pb"))
    }

    private fun <T> assertNotNullAnd(x: T?): T {
        assertNotNull(x)
        return x!!
    }
}
