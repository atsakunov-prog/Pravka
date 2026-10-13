package ru.zf.pravka.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.PhoneRemote.Item
import ru.zf.pravka.core.PhoneRemote.Opt
import ru.zf.pravka.core.PhoneRemote.Outcome
import ru.zf.pravka.core.PhoneRemote.Type

/**
 * Настройки телефона на сервере (docs/phone-settings-server.md, контракт server/contract/dela-phone.json):
 * снимок без секретов, проверка правки с компьютера, спор «кто позже», разбор синка.
 */
class PhoneRemoteTest {

    private fun contract(): JSONObject {
        val f = listOf(File("../server/contract/dela-phone.json"), File("server/contract/dela-phone.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    private val gap = Item("z.gap", "Дыра во времени", Type.INT, min = 0, max = 120, step = 15, unit = "мин")
    private val checkins = Item("z.checkins", "Спрашивать «всё ещё …?»", Type.BOOL)
    private val bedtime = Item("today.bedtime", "Время сна", Type.TIME, min = 1260, max = 1560, step = 15)
    private val model = Item("model.pravka", "Чистка диктовки", Type.CHOICE, options = listOf(Opt("claude-sonnet-5-5", "Сонет 5.5"), Opt("claude-opus-5-5", "Опус 5.5")), row = "pravka")
    private val marks = Item("today.marks", "Отметки", Type.SET, options = listOf(Opt("sport", "Спорт"), Opt("food", "Еда"), Opt("money", "Деньги")))
    private val city = Item("today.city", "Город погоды", Type.TEXT, maxLen = 20)
    private val places = Item("auto.places", "Места по Wi-Fi", Type.INFO, edit = false, why = "только на телефоне")
    private val groups = listOf(
        PhoneRemote.Group("zasechka", "Засечка", "напоминания", "clock", listOf(gap, checkins, places)),
        PhoneRemote.Group("today", "Сегодня", "сон, город", "sun", listOf(bedtime, marks, city)),
        PhoneRemote.Group("models", "Модели", "дороги", "claude", listOf(model)),
    )

    private fun edit(key: String, value: Any?, at: Long = 1_000_000L) = PhoneRemote.Edit("e-$key", "sasha-3f9a2c", key, value, at)

    @Test
    fun `значение правки проверяется по своей схеме`() {
        assertEquals(60, PhoneRemote.check(gap, 60).getOrThrow())
        assertEquals(60, PhoneRemote.check(gap, 60L).getOrThrow())
        assertTrue(PhoneRemote.check(gap, 130).exceptionOrNull()!!.message!!.contains("от 0 до 120"))
        assertTrue(PhoneRemote.check(gap, 50).exceptionOrNull()!!.message!!.contains("шаг 15"))
        assertTrue(PhoneRemote.check(gap, "60").isFailure)
        assertEquals(false, PhoneRemote.check(checkins, false).getOrThrow())
        assertTrue(PhoneRemote.check(checkins, "нет").isFailure)
        assertEquals(1500, PhoneRemote.check(bedtime, 1500).getOrThrow())
        assertTrue(PhoneRemote.check(bedtime, 1000).isFailure)
        assertEquals("claude-opus-5-5", PhoneRemote.check(model, "claude-opus-5-5").getOrThrow())
        assertTrue(PhoneRemote.check(model, "claude-haiku").isFailure)
        // Набор — в порядке вариантов, без повторов; чужого варианта нет.
        assertEquals(listOf("sport", "money"), PhoneRemote.check(marks, JSONArray(listOf("money", "sport", "money"))).getOrThrow())
        assertTrue(PhoneRemote.check(marks, listOf("dela")).isFailure)
        assertEquals("Санкт Петербург", PhoneRemote.check(city, "  Санкт \n Петербург ").getOrThrow())
        assertTrue(PhoneRemote.check(city, "x".repeat(21)).isFailure)
        assertTrue(PhoneRemote.check(places, "дом").exceptionOrNull()!!.message!!.contains("не правится"))
    }

    @Test
    fun `кто позже — тот и прав`() {
        val e = edit("z.gap", 60, at = 5_000L)
        assertEquals(PhoneRemote.Verdict.APPLY, PhoneRemote.decide(e, 45, null))
        assertEquals(PhoneRemote.Verdict.APPLY, PhoneRemote.decide(e, 45, 4_000L))   // на телефоне меняли раньше правки
        assertEquals(PhoneRemote.Verdict.CONFLICT, PhoneRemote.decide(e, 30, 6_000L)) // позже — осталось с телефона
        assertEquals(PhoneRemote.Verdict.SAME, PhoneRemote.decide(e, 60L, 6_000L))    // уже стоит — ставить нечего
    }

    @Test
    fun `правка решается до записи и пишется тем же путём`() {
        val now = 10_000_000L
        // Ставить — значение уже проверенное и в своём виде.
        assertEquals(Outcome.Write(60), PhoneRemote.resolve(edit("z.gap", 60L), gap, true, 45, null, now))
        assertEquals(Outcome.Write(listOf("sport", "food")), PhoneRemote.resolve(edit("today.marks", listOf("food", "sport")), marks, true, listOf("sport"), null, now))
        // Не ставить — сразу ответ: нет настройки, нет сеттера, показ, кривое значение, уже стоит, телефон позже.
        fun answer(o: Outcome) = (o as Outcome.Answer).ack
        assertFalse(answer(PhoneRemote.resolve(edit("nope", 1), null, true, null, null, now)).ok)
        assertTrue(answer(PhoneRemote.resolve(edit("z.gap", 60), gap, false, 45, null, now)).note.contains("не правится"))
        assertTrue(answer(PhoneRemote.resolve(edit("auto.places", "дача"), places, true, "дом", null, now)).note.contains("только на телефоне"))
        assertTrue(answer(PhoneRemote.resolve(edit("z.gap", 61), gap, true, 45, null, now)).note.contains("шаг"))
        assertTrue(answer(PhoneRemote.resolve(edit("z.gap", 45), gap, true, 45, null, now)).ok)
        val c = answer(PhoneRemote.resolve(edit("z.gap", 60, at = 1_000L), gap, true, 30, 2_000L, now))
        assertTrue(!c.ok && c.conflict && c.note.contains("поменяли позже") && c.note.contains("30 мин"))
    }

    @Test
    fun `ключи и токены в снимок не уходят`() {
        // В значениях — всё подряд, как собирает телефон; наружу — только описанное схемой и не секретное.
        val values = mapOf(
            "z.gap" to 45, "z.checkins" to true, "auto.places" to "дом, дача", "today.bedtime" to 1410,
            "today.marks" to setOf("money", "sport"), "today.city" to "Москва", "model.pravka" to "claude-sonnet-5-5",
            "anthropic_api_key" to "sk-ant-api03-SECRET", "icu_api_key" to "icu-SECRET", "todoist_token" to "tdst-SECRET",
            "dela.token" to "dela-SECRET", "home.password" to "pw-SECRET",
        )
        val withSecretItem = groups + PhoneRemote.Group("links", "Подключения", "", "link", listOf(
            Item("link.anthropic", "Anthropic", Type.INFO, edit = false),
            Item("anthropic_api_key", "Ключ", Type.TEXT, edit = true),
        ))
        val s = PhoneRemote.snapshot("sasha-3f9a2c", "Pixel", "5.0 (1)", withSecretItem, values + ("link.anthropic" to "ключ есть"),
            mapOf("z.gap" to 1L, "anthropic_api_key" to 2L), mapOf("zasechka" to "дыра от 45 мин", "чужая" to "x"), emptyList(), withSchema = true)
        val text = s.toString()
        assertFalse(text, text.contains("SECRET"))
        assertFalse(s.getJSONObject("values").has("anthropic_api_key"))
        assertFalse(s.getJSONObject("changed").has("anthropic_api_key"))
        // Секрет в схеме, даже помеченный «правится», уходит с edit false.
        val items = s.getJSONObject("schema").getJSONArray("groups").let { a -> (0 until a.length()).flatMap { i -> a.getJSONObject(i).getJSONArray("items").let { b -> (0 until b.length()).map { b.getJSONObject(it) } } } }
        assertFalse(items.first { it.getString("key") == "anthropic_api_key" }.getBoolean("edit"))
        assertEquals("ключ есть", s.getJSONObject("values").getString("link.anthropic"))
        assertEquals(JSONArray(listOf("sport", "money")).toString(), s.getJSONObject("values").getJSONArray("today.marks").toString())
        assertEquals(setOf("zasechka"), s.getJSONObject("status").keyz())
        for (k in listOf("anthropic_api_key", "icu_api_key", "todoist_token", "dela.token", "home.password", "archive.auth", "x.pin")) {
            assertTrue(k, PhoneRemote.isSecret(k))
        }
        for (k in listOf("keep_on_fold", "link.anthropic", "z.gap", "voice.own_mic", "auto.places", "buttons.size", "money.push_tbank")) {
            assertFalse(k, PhoneRemote.isSecret(k))
        }
    }

    @Test
    fun `схема уходит, только когда нужна, и её отпечаток устойчив`() {
        val a = PhoneRemote.snapshot("d", "n", "a", groups, emptyMap(), emptyMap(), emptyMap(), emptyList(), withSchema = false)
        assertFalse(a.has("schema"))
        val h = PhoneRemote.schemaHash(groups)
        assertEquals(h, a.getString("schema_hash"))
        assertEquals(h, PhoneRemote.schemaHash(groups.map { it.copy(items = it.items.toList()) }))
        val changed = groups.map { g -> if (g.key == "zasechka") g.copy(items = g.items.map { if (it.key == "z.gap") it.copy(max = 180) else it }) else g }
        assertTrue(h != PhoneRemote.schemaHash(changed))
        assertTrue(PhoneRemote.problems(groups).isEmpty())
        assertEquals(listOf("z.gap: дважды"), PhoneRemote.problems(groups + PhoneRemote.Group("x", "x", "", "", listOf(gap))))
    }

    @Test
    fun `ответы на правки — в снимке`() {
        val acks = listOf(PhoneRemote.Ack("1", true), PhoneRemote.Ack("2", false, conflict = true, note = "на телефоне поменяли позже"))
        val s = PhoneRemote.snapshot("d", "n", "a", groups, emptyMap(), emptyMap(), emptyMap(), acks, withSchema = false)
        val a = s.getJSONArray("acks")
        assertEquals(2, a.length())
        assertEquals(acks, (0 until a.length()).map { PhoneRemote.Ack.of(a.getJSONObject(it)) })
    }

    @Test
    fun `правки синка — только свои`() {
        val ex = contract().getJSONObject("sync").getJSONObject("example")
        val mine = PhoneRemote.edits(ex, "sasha-3f9a2c")
        assertEquals(1, mine.size)
        assertEquals("z_gap_min", mine[0].key)
        assertEquals(60, mine[0].value)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-14T12:10:00+03:00").toInstant().toEpochMilli(), mine[0].atMs)
        assertTrue(PhoneRemote.edits(ex, "marianna-01ab9e").isEmpty())
        assertTrue(PhoneRemote.edits(JSONObject(), "sasha-3f9a2c").isEmpty())
        // Без синка Дел — тот же разбор из вида phone.
        val view = JSONObject().put("pending", ex.getJSONObject("phone").getJSONArray("edits"))
        assertEquals(mine, PhoneRemote.pendingFromView(view, "sasha-3f9a2c"))
    }

    @Test
    fun `схема и снимок — по контракту`() {
        val c = contract()
        val types = c.getJSONObject("schema").getJSONObject("types").keyz()
        assertEquals(Type.entries.map { it.key }.toSet(), types)
        // Поля снимка — те, что описаны в контракте.
        val fields = c.getJSONObject("op_snapshot").getJSONObject("fields").keyz() + "op"
        val s = PhoneRemote.snapshot("d", "n", "a", groups, mapOf("z.gap" to 45), emptyMap(), emptyMap(), emptyList(), withSchema = true)
        assertTrue((s.keyz() - fields).toString(), fields.containsAll(s.keyz()))
        // Поля настройки — те, что в примере схемы контракта (и max у текста, step у числа).
        val known = c.getJSONObject("schema").getJSONObject("example").getJSONArray("groups").let { a ->
            (0 until a.length()).flatMap { i -> a.getJSONObject(i).getJSONArray("items").let { b -> (0 until b.length()).flatMap { b.getJSONObject(it).keyz() } } }
        }.toSet() + setOf("max", "step", "min")
        val mineKeys = PhoneRemote.schemaJson(groups).getJSONArray("groups").let { a ->
            (0 until a.length()).flatMap { i -> a.getJSONObject(i).getJSONArray("items").let { b -> (0 until b.length()).flatMap { b.getJSONObject(it).keyz() } } }
        }.toSet()
        assertTrue((mineKeys - known).toString(), known.containsAll(mineKeys))
    }

    @Test
    fun `значение словами и «поменяно с компьютера»`() {
        assertEquals("Опус 5.5", PhoneRemote.label(model, "claude-opus-5-5"))
        assertEquals("нет", PhoneRemote.label(checkins, false))
        assertEquals("23:30", PhoneRemote.label(bedtime, 1410))
        assertEquals("01:00", PhoneRemote.label(bedtime, 1500))
        assertEquals("60 мин", PhoneRemote.label(gap, 60))
        assertEquals("Спорт, Деньги", PhoneRemote.label(marks, listOf("sport", "money")))
        assertNull(PhoneRemote.fromJson(JSONObject.NULL))
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val now = java.time.ZonedDateTime.of(2026, 10, 14, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val at = now - 48 * 60_000L
        val old = now - 4 * 24 * 3_600_000L
        val list = listOf(
            PhoneRemote.Applied("z.gap", "zasechka", "Дыра во времени", "60 мин", at),
            PhoneRemote.Applied("today.city", "today", "Город погоды", "Казань", old),
        )
        assertEquals("в 14:12", PhoneRemote.whenWords(at, now))
        assertEquals("вчера в 15:00", PhoneRemote.whenWords(now - 24 * 3_600_000L, now))
        assertEquals(listOf("z.gap"), PhoneRemote.recent(list, now).map { it.key })
        assertEquals("поменяно с компьютера в 14:12", PhoneRemote.groupNote(list, "zasechka", now))
        assertNull(PhoneRemote.groupNote(list, "today", now))
    }
}

/** Ключи объекта набором: keySet() у JSONObject Android нет — только keys(). */
private fun JSONObject.keyz(): Set<String> = keys().asSequence().toSet()
