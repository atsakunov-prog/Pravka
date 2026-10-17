package ru.zf.pravka.core

import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ArchiveSync
import ru.zf.pravka.data.FoodStore

/**
 * Еда — правда на сервере (docs/eda-server.md, часть 1): приём из архива в
 * дневник и обратно — тот же отпечаток (иначе телефон пересылал бы чужое как
 * своё), своя неотправленная правка сильнее чужой, удаление с компа убирает
 * приём, повтор окна курсора ничего не трогает.
 */
class FoodSyncTest {

    private val clock = ArchiveEvents.Clock(TimeZone.getTimeZone("Europe/Moscow"))

    // 2026-10-13 08:10 МСК
    private val t0 = 1_791_868_200_000L

    private fun meal(id: Long = t0, grams: Int = 60, photo: String = "", origin: String = "", source: String = "voice") = FoodStore.Meal(
        id = id, ts = id + 5 * 60_000, createdAt = id, kind = "завтрак", raw = "моя каша",
        items = listOf(
            MealItem("Геркулес", grams, 220, 8, 4, 38, 6, "точно", mapOf("mg" to 81.6, "fe" to 2.1)),
            MealItem("Креатин", 5, 0, 0, 0, 0, 0, "точно", emptyMap(), pill = true),
        ),
        note = "", source = source, photo = photo, confirmed = true, icuSynced = true, ribbonSynced = true,
        costUsd = 0.0123, model = "claude-sonnet-5-5", origin = origin,
    )

    private fun put(m: FoodStore.Meal): JSONObject {
        val u = ArchiveEvents.meal(m, clock)
        return JSONObject().put("kind", u.kind).put("key", u.key).put("op", "put").put("data", u.data)
            .put("at", "2026-10-13T08:20:00.000+03:00").put("device", "svc-eda")
    }

    private fun del(id: Long) = JSONObject().put("kind", FoodSync.KIND).put("key", "f$id").put("op", "del")
        .put("at", "2026-10-13T09:00:00.000+03:00").put("device", "svc-eda")

    private fun hash(m: FoodStore.Meal) = ArchiveEvents.meal(m, clock).hash

    // ------------------------------------------------------------------ формат

    @Test
    fun `приём туда и обратно — тот же отпечаток`() {
        val m = meal(photo = "f1.jpg")
        val back = FoodSync.meal(ArchiveEvents.meal(m, clock).data)
        assertNotNull(back)
        assertEquals(hash(m), hash(back!!))
        assertEquals(m.ts, back.ts)
        assertEquals(m.createdAt, back.createdAt)
        assertEquals(m.items, back.items)
        // Отметки «уехало» в архив не ходят — у разобранного их нет.
        assertFalse(back.icuSynced)
        assertFalse(back.ribbonSynced)
    }

    @Test
    fun `пример приёма из контракта сервера — тот же отпечаток после дневника`() {
        val f = listOf(File("../server/contract/batch.json"), File("server/contract/batch.json")).first { it.isFile }
        val events = JSONObject(f.readText()).getJSONArray("events")
        val data = (0 until events.length()).map { events.getJSONObject(it) }
            .first { it.optString("kind") == FoodSync.KIND && it.optString("op") == "put" }.getJSONObject("data")
        val m = FoodSync.meal(data)!!
        assertEquals(ArchiveEvents.hashOf(data), hash(m))
        assertEquals(3, m.items.size)
        assertTrue(m.items[2].pill)
    }

    @Test
    fun `приём с компа — пометка origin, время без миллисекунд, целые позиции`() {
        val data = ArchiveEvents.meal(meal(source = "ration"), clock).data
            .put("origin", "pc")
            .put("at", "2026-10-13T08:15:00+03:00")
            .put("created", JSONObject.NULL)
        data.getJSONArray("items").getJSONObject(0).put("grams", 60.4).put("kcal", 219.6)
        val m = FoodSync.meal(data)!!
        assertEquals("pc", m.origin)
        assertEquals("ration", m.source)
        assertEquals(t0 + 5 * 60_000, m.ts)
        assertEquals("created пусто — как ts", m.ts, m.createdAt)
        assertEquals(60, m.items[0].grams)
        assertEquals(220, m.items[0].kcal)
        // Поле origin едет назад, если телефон правил чужой приём, а у своих его нет вовсе.
        assertEquals("pc", ArchiveEvents.meal(m, clock).data.getString("origin"))
        assertFalse(ArchiveEvents.meal(meal(), clock).data.has("origin"))
    }

    @Test
    fun `вещества — до сотой, как на диске`() {
        val data = ArchiveEvents.meal(meal(), clock).data
        data.getJSONArray("items").getJSONObject(0).getJSONObject("micro").put("mg", 81.6049).put("zn", 0.0)
        val m = FoodSync.meal(data)!!
        assertEquals(81.6, m.items[0].micro["mg"]!!, 1e-9)
        assertFalse(m.items[0].micro.containsKey("zn"))
    }

    @Test
    fun `нечитаемый приём — null, а не пустой приём`() {
        assertNull(FoodSync.meal(JSONObject().put("id", 1)))
        assertNull(FoodSync.meal(ArchiveEvents.meal(meal(), clock).data.put("at", "вчера")))
        assertNull(FoodSync.meal(ArchiveEvents.meal(meal(), clock).data.put("items", org.json.JSONArray())))
    }

    // ------------------------------------------------------------------ что класть

    @Test
    fun `новый приём с компа ложится, отпечаток — в квитанцию`() {
        val pc = meal(id = t0 + 1, origin = "pc", source = "ration")
        val plan = FoodSync.plan(listOf(put(pc)), emptyMap(), { null }, clock)
        assertEquals(listOf(pc.id), plan.put.map { it.id })
        assertEquals(hash(plan.put.single()), plan.sent["f${pc.id}"])
        assertFalse(plan.put.single().icuSynced)
        assertEquals(1, plan.changed)
    }

    @Test
    fun `поправили на компе свой приём — кладём, отметки «уехало» сняты, снимок свой остаётся`() {
        val mine = meal(photo = "f1.jpg")
        val onPc = meal(grams = 80).copy(photo = "")
        val plan = FoodSync.plan(listOf(put(onPc)), mapOf(mine.id to mine), { hash(mine) }, clock)
        val got = plan.put.single()
        assertEquals(80, got.items[0].grams)
        assertEquals("f1.jpg", got.photo)
        assertFalse(got.icuSynced)
        assertFalse(got.ribbonSynced)
        assertEquals(hash(got), plan.sent["f${mine.id}"])
    }

    @Test
    fun `своя неотправленная правка сильнее`() {
        val sentVersion = meal()
        val mineNow = meal(grams = 90)          // правили на телефоне, ещё не ушло
        val onPc = meal(grams = 70)
        val plan = FoodSync.plan(listOf(put(onPc)), mapOf(mineNow.id to mineNow), { hash(sentVersion) }, clock)
        assertTrue(plan.put.isEmpty())
        assertEquals(listOf("f${mineNow.id}"), plan.kept)
        // Квитанция не трогается: телефон отправит своё следом.
        assertTrue(plan.sent.isEmpty())
    }

    @Test
    fun `своё неотправленное удаление сильнее чужой правки`() {
        val sentVersion = meal()
        val plan = FoodSync.plan(listOf(put(meal(grams = 70))), emptyMap(), { hash(sentVersion) }, clock)
        assertTrue(plan.put.isEmpty())
        assertEquals(1, plan.kept.size)
    }

    @Test
    fun `квитанции стёрты — правда сервер`() {
        val mine = meal(grams = 90)
        val plan = FoodSync.plan(listOf(put(meal(grams = 70))), mapOf(mine.id to mine), { null }, clock)
        assertEquals(70, plan.put.single().items[0].grams)
    }

    @Test
    fun `повтор того же снимка — дневник не трогаем, квитанцию пишем`() {
        val mine = meal()
        val plan = FoodSync.plan(listOf(put(mine)), mapOf(mine.id to mine), { null }, clock)
        assertTrue(plan.put.isEmpty())
        assertEquals(0, plan.changed)
        assertEquals(hash(mine), plan.sent["f${mine.id}"])
    }

    @Test
    fun `удалили на компе — уходит, своя правка — остаётся, незнакомое — только квитанция`() {
        val mine = meal()
        val clean = FoodSync.plan(listOf(del(mine.id)), mapOf(mine.id to mine), { hash(mine) }, clock)
        assertEquals(listOf(mine.id), clean.gone)
        assertEquals(ArchiveEvents.GONE, clean.sent["f${mine.id}"])

        val edited = meal(grams = 90)
        val dirty = FoodSync.plan(listOf(del(mine.id)), mapOf(mine.id to edited), { hash(mine) }, clock)
        assertTrue(dirty.gone.isEmpty())
        assertEquals(1, dirty.kept.size)

        val unknown = FoodSync.plan(listOf(del(42)), emptyMap(), { null }, clock)
        assertTrue(unknown.gone.isEmpty())
        assertEquals(ArchiveEvents.GONE, unknown.sent["f42"])
        assertEquals(0, unknown.changed)
    }

    @Test
    fun `чужие виды и кривые ключи — мимо`() {
        val other = JSONObject().put("kind", "pravka.take").put("key", "pc-1").put("op", "put").put("data", JSONObject())
        val badKey = put(meal()).put("key", "x1")
        val plan = FoodSync.plan(listOf(other, badKey), emptyMap(), { null }, clock)
        assertEquals(0, plan.changed)
        assertEquals(1, plan.broken.size)
    }

    // ------------------------------------------------------------------ квитанции

    @Test
    fun `курсор забора — в квитанциях, стёрли квитанции — забор с начала`() {
        val f = Files.createTempFile("ledger", ".json").toFile()
        val l = ArchiveSync.Ledger()
        l.kind(FoodSync.KIND)["f1"] = "abc"
        l.cursors["food"] = "2026-10-13T09:00:00.000+03:00"
        l.write(f)
        val back = ArchiveSync.Ledger.read(f)
        assertEquals("2026-10-13T09:00:00.000+03:00", back.cursors["food"])
        assertEquals("abc", back.kinds[FoodSync.KIND]?.get("f1"))
        f.delete()
        assertTrue(ArchiveSync.Ledger.read(f).cursors.isEmpty())
    }

    // ------------------------------------------------------------------ цели

    @Test
    fun `цели из Свода — числа, вес, срок`() {
        val g = FoodGoals.parse(JSONObject("""{"kcal": 2200, "protein_g": 160, "fat_g": 70.4, "carbs_g": 230, "fiber_g": 30, "weight_kg": 82.5, "weight_by": "2026-11-30", "updated": "2026-10-13", "note": "дефицит"}"""))!!
        assertEquals(2200, g.kcal)
        assertEquals(70, g.fat)
        assertEquals(83, g.weightRound)
        assertEquals("2026-11-30", g.weightBy)
        assertEquals(30, g.fiber)
        assertNull(FoodGoals.parse(JSONObject("""{"protein_g": 160}""")))
        assertNull(FoodGoals.parse(null))
        assertEquals("", FoodGoals.parse(JSONObject("""{"kcal": 2000, "weight_by": "к Новому году"}"""))!!.weightBy)
    }

    @Test
    fun `ручки телефона поверх записи — чужие поля целы, дробный вес не трогаем`() {
        val base = JSONObject("""{"kcal": 2200, "protein_g": 160, "fat_g": 70, "carbs_g": 230, "fiber_g": 30, "weight_kg": 82.5, "weight_by": "2026-11-30", "note": "дефицит"}""")
        val same = FoodGoals.value(base, 2400, 170, 75, 260, 83, "2026-10-17")
        assertEquals(2400, same.getInt("kcal"))
        assertEquals(30, same.getInt("fiber_g"))
        assertEquals("2026-11-30", same.getString("weight_by"))
        assertEquals("дефицит", same.getString("note"))
        assertEquals(82.5, same.getDouble("weight_kg"), 1e-9)
        assertEquals("2026-10-17", same.getString("updated"))
        val moved = FoodGoals.value(base, 2400, 170, 75, 260, 80, "2026-10-17")
        assertEquals(80, moved.getInt("weight_kg"))
        // Запись-основа не меняется на месте.
        assertEquals(2200, base.getInt("kcal"))
        val seed = FoodGoals.value(null, 2500, 160, 80, 280, 80, "2026-10-17")
        assertEquals(setOf("kcal", "protein_g", "fat_g", "carbs_g", "weight_kg", "updated"), seed.keys().asSequence().toSet())
        assertTrue(FoodGoals.sameKnobs(FoodGoals.parse(same)!!, 2400, 170, 75, 260, 83))
        assertFalse(FoodGoals.sameKnobs(FoodGoals.parse(same)!!, 2400, 170, 75, 260, 80))
    }

    // ------------------------------------------------------------------ правила блока

    @Test
    fun `правила блока — в форму контракта и обратно`() {
        val r = ru.zf.pravka.data.PlanStore.Rules(
            blockTitle = "Блок 3", runHrCeiling = 142, greyZoneLow = 150, greyZoneHigh = 165, cadenceMin = 170,
            runsPerWeekMax = 3, hoursBetweenRuns = 48, rampNeedsPositiveTsb = true, testPrep = "сон 8 часов",
            cancelOrder = "сначала бег", kneeGreen = "можно", kneeYellow = "только вело", kneeRed = "отдых",
            weekPlan = listOf("Пн" to "Силовая A", "Ср" to "Zwift"), extra = listOf("не больше 10% в неделю"),
            sourceText = "страница блока", fetchedAt = 1_760_000_000_000L, pageId = "notion-1",
        )
        val v = SportSvod.rulesValue(r, "2026-10-13")!!
        assertEquals(142, v.getInt("run_hr_ceiling"))
        assertEquals("страница блока", v.getString("text"))
        assertEquals("только вело", v.getJSONObject("knee").getString("yellow"))
        assertEquals("Zwift", v.getJSONArray("week_plan").getJSONObject(1).getString("session"))
        val back = SportSvod.rules(JSONObject(v.toString()))!!
        assertEquals(r.copy(fetchedAt = back.fetchedAt, pageId = ""), back)
        assertTrue(back.known)
        assertNotEquals(0L, back.fetchedAt)
        // Своих правил нет — засевать нечего.
        assertNull(SportSvod.rulesValue(ru.zf.pravka.data.PlanStore.Rules(), "2026-10-13"))
    }

    @Test
    fun `правило без числа — ноль, и запись без даты всё равно «правила есть»`() {
        val r = SportSvod.rules(JSONObject("""{"block_title": "Блок 4", "run_hr_ceiling": null, "extra": ["", "темп по ощущениям"]}"""), savedAt = "")!!
        assertEquals(0, r.runHrCeiling)
        assertEquals(listOf("темп по ощущениям"), r.extra)
        assertTrue(r.known)
        assertNull(SportSvod.rules("не объект"))
    }
}
