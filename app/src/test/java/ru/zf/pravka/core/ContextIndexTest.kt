package ru.zf.pravka.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контекст по делу на телефоне (аудит 15.10.2026, №67): разбор вида `context_index` и матчер — тот же, что у сервера.
 * Образец — `server/contract/dela-context-sample.json`: ключи и находки там построил `focus.py` сервера (его держит
 * `test_dela_context_sample.py`), телефон на том же ответе обязан найти то же. Люди выдуманные.
 */
class ContextIndexTest {

    private fun sample(): JSONObject {
        val f = listOf(File("../server/contract/dela-context-sample.json"), File("server/contract/dela-context-sample.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    private val sok = "00000000-0000-4000-8000-000000000001"
    private val maria = "00000000-0000-4000-8000-000000000002"
    private val ivan = "00000000-0000-4000-8000-000000000005"
    private val romashka = "00000000-0000-4000-8000-0000000000a1"

    private fun index(): ContextIndex = ContextIndex.parse(sample().getJSONObject("view")).also { assertNotNull(it) }!!

    @Test
    fun `разбор вида — связанные, ключи и таблицы`() {
        val ix = index()
        assertEquals(1, ix.rule)
        assertEquals(setOf(sok, ivan), ix.related)
        assertTrue("женя" in ix.people.getValue(sok))
        assertTrue(ix.projects.containsKey(romashka))
    }

    @Test
    fun `матчер телефона находит ровно то, что сервер, — на каждом случае образца`() {
        val ix = index()
        val cases = sample().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("text")
            val people = c.getJSONArray("people").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val projects = c.getJSONArray("projects").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            assertEquals("люди: $text", people, ix.peopleIn(text))
            assertEquals("проекты: $text", projects, ix.projectsIn(text))
        }
    }

    @Test
    fun `падежи и уменьшительные — да, другое имя — нет, латиница — транслитом`() {
        val ix = index()
        assertEquals(setOf(sok), ix.peopleIn("передать Жене"))
        assertEquals(setOf(sok), ix.peopleIn("встреча с Соколовым"))
        assertEquals(emptySet<String>(), ix.peopleIn("позвать Марину"))
        assertEquals(setOf(maria), ix.peopleIn("Марии"))
        assertEquals(setOf("00000000-0000-4000-8000-000000000006"), ix.peopleIn("письмо от Dmitry Landyshev"))
        // «Ё» и регистр — как у сервера (people.norm).
        assertEquals(setOf("00000000-0000-4000-8000-000000000003"), ix.peopleIn("ПЁТР звонил"))
        assertEquals(setOf(romashka), ix.projectsIn("у Ромашки"))
        assertEquals(emptySet<String>(), ix.projectsIn("ООО"))
    }

    @Test
    fun `формы слова — правилом сервера`() {
        val ix = index()
        assertTrue("соколов" in ix.forms("Соколовым"))
        assertTrue("миш" in ix.forms("Мише"))
        // «Марину» без окончания — «марин», не «мари»: «н» — не буква окончания.
        assertFalse("мари" in ix.forms("Марину"))
        assertTrue("дмитрий" in ix.forms("Dmitry"))
    }

    @Test
    fun `чужое правило, пустой ответ или нет ключей — вида нет, каталог как был`() {
        val v = sample().getJSONObject("view")
        assertNull(ContextIndex.parse(JSONObject(v.toString()).put("rule", 2)))
        assertNull(ContextIndex.parse(JSONObject(v.toString()).apply { remove("people") }))
        assertNull(ContextIndex.parse(JSONObject(v.toString()).apply { remove("endings") }))
        assertNull(ContextIndex.parse(null))
    }

    @Test
    fun `кэш вида — свежее шести часов, старше трёх суток не берётся`() {
        val now = 10L * 24 * 3_600_000L
        assertFalse(ContextIndex.stale(now - 5 * 3_600_000L, now))
        assertTrue(ContextIndex.stale(now - 7 * 3_600_000L, now))
        assertTrue(ContextIndex.stale(null, now))
        assertTrue(ContextIndex.usable(now - 2 * 24 * 3_600_000L, now))
        assertFalse(ContextIndex.usable(now - 4 * 24 * 3_600_000L, now))
        assertFalse(ContextIndex.usable(0L, now))
    }

    @Test
    fun `голова разбора — связанные, команда и кого индекс не знает, названные вне них — в хвост`() {
        val ix = index()
        val p = { id: String, name: String, short: String -> id to Dela.Person(id, name, short = short) }
        val s = Dela.Snapshot(
            people = mapOf(
                p(sok, "Евгений Соколов", ""), p(maria, "Мария Тестова", ""), p(ivan, "Иван Тестов", "Ваня"),
                p("00000000-0000-4000-8000-000000000004", "Миша Иванов", ""),
                // Заведён после вида — индекс его не знает: в голове, иначе не нашёлся бы нигде.
                p("новый", "Пётр Новый", ""),
                // Команда — пользователь Дел.
                p("коллега", "Ольга Тестова", "Оля"),
            ),
            users = mapOf("olga" to Dela.User("olga", "Оля", personId = "коллега")),
        )
        val keep = ix.keepFor(s)
        assertEquals(setOf(sok, ivan, "новый", "коллега"), keep)
        // Названы «Мария» и «Ваня»: Ваня уже в голове, Мария — в хвост.
        assertEquals(setOf(maria), ix.namedOutside(s, "спросить у Марии, Ваня в курсе", keep))
    }
}
