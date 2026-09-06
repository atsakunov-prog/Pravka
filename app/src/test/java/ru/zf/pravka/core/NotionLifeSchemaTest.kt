package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore

// Структура Notion живёт в коде, и главный способ её сломать — написать в
// строку колонку, которой в базе нет: Notion ответит 400, строка не доедет,
// а владелец увидит «не принято». Поэтому каждый построитель проверяется
// против своей схемы, а устаревшие колонки не должны пересекаться с живыми.
class NotionLifeSchemaTest {

    private val now = 1_757_073_600_000L // 05.09.2026 около полудня по Москве

    private fun keys(o: org.json.JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun text(row: org.json.JSONObject, key: String): String =
        row.getJSONObject(key).getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content")

    private fun assertFits(db: NotionLifeSchema.Db, row: org.json.JSONObject) {
        val extra = keys(row) - db.columns.map { it.name }.toSet()
        assertTrue("лишние колонки для «${db.name}»: $extra", extra.isEmpty())
        assertTrue("нет заголовка «${db.title.name}»", row.has(db.title.name))
    }

    private fun entry(
        id: Long, start: Long, end: Long, title: String, category: String,
        source: String = "voice", raw: String = "", client: String = "", comment: String = "",
    ) = ZasechkaStore.Entry(
        id = id, start = start, end = end, raw = raw, title = title, category = category,
        client = client, useful = 0, source = source, synced = false, createdAt = start,
        comment = comment,
    )

    @Test
    fun `в каждой базе ровно один заголовок и нет одноимённых колонок`() {
        for (db in NotionLifeSchema.ALL) {
            assertEquals(db.name, 1, db.columns.count { it.type == "title" })
            assertEquals(db.name, db.columns.size, db.columns.map { it.name }.toSet().size)
            val clash = db.retired.toSet() intersect db.columns.map { it.name }.toSet()
            assertTrue("устаревшее совпало с живым в «${db.name}»: $clash", clash.isEmpty())
        }
        assertEquals(NotionLifeSchema.ALL.size, NotionLifeSchema.ALL.map { it.name }.toSet().size)
    }

    @Test
    fun `строка ленты ложится в Засечку`() {
        val e = entry(42, now - 90 * 60_000L, now - 30 * 60_000L, "Встреча с Серёжей", "Семья", raw = "встреча с серёжей")
        val row = NotionLifeSchema.ribbonRow(e, minutes = 60, worth = 6, now = now)
        assertFits(NotionLifeSchema.ZASECHKA, row)
        assertEquals(6.0, row.getJSONObject("Очки").getDouble("number"), 0.01)
        assertEquals("голос", row.getJSONObject("Записано").getJSONObject("select").getString("name"))
        assertEquals("t42", row.getJSONObject("EntryId").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
    }

    @Test
    fun `комментарий к делу едет своей колонкой, пустой - пустым массивом`() {
        val e = entry(43, now - 90 * 60_000L, now - 30 * 60_000L, "Созвон с Ильёй", "Звонки", comment = "Договорились о бюджете до пятницы")
        val row = NotionLifeSchema.ribbonRow(e, minutes = 60, worth = 7, now = now)
        assertFits(NotionLifeSchema.ZASECHKA, row)
        assertEquals("Договорились о бюджете до пятницы", text(row, "Комментарий"))
        // Пустой комментарий кладётся тоже: иначе снятый в приложении текст
        // остался бы висеть в Notion.
        val bare = NotionLifeSchema.ribbonRow(e.copy(comment = ""), minutes = 60, worth = 7, now = now)
        assertEquals(0, bare.getJSONObject("Комментарий").getJSONArray("rich_text").length())
    }

    @Test
    fun `заполнитель и авто-факты помечаются словами`() {
        assertEquals("заполнитель", NotionLifeSchema.recorded("gap"))
        assertEquals("auto", NotionLifeSchema.sourceKind("gap"))
        assertEquals("авто: телефон или часы", NotionLifeSchema.recorded("auto"))
        assertEquals("manual", NotionLifeSchema.sourceKind("edit"))
        assertTrue(NotionLifeSchema.RECORDED.contains(NotionLifeSchema.recorded("todoist")))
    }

    @Test
    fun `приём еды ложится в Еду со составом`() {
        val m = FoodStore.Meal(
            id = 7, ts = now, createdAt = now, kind = "завтрак", raw = "омлет из трёх яиц и кофе",
            items = listOf(
                MealItem(name = "Омлет", grams = 180, kcal = 290, protein = 20, fat = 22, carbs = 2, fiber = 0, sureness = "точно"),
                MealItem(name = "Капучино", grams = 200, kcal = 90, protein = 5, fat = 4, carbs = 8, fiber = 0, sureness = "примерно"),
            ),
            source = "photo", confirmed = true,
        )
        val row = NotionLifeSchema.mealRow(m)
        assertFits(NotionLifeSchema.EDA, row)
        assertEquals(380, row.getJSONObject("Ккал").getInt("number"))
        assertEquals("фото", row.getJSONObject("Записано").getJSONObject("select").getString("name"))
        assertEquals("завтрак", row.getJSONObject("Вид").getJSONObject("select").getString("name"))
    }

    @Test
    fun `тренировка с часов ложится в Тренировки`() {
        val w = SportStore.Workout(
            id = "i123", start = now - 3_600_000L, type = "Run", name = "Утренний бег", seconds = 1920,
            movingSeconds = 1900, distanceM = 5040.0, elevationM = 12.0, load = 40, intensity = 80,
            avgHr = 163, maxHr = 175, avgWatts = 322, normWatts = 330, paceSecPerKm = 353, gapSecPerKm = 350,
            cadence = 172, calories = 380, feel = 2, rpe = 6, decoupling = 3.0, efficiency = 1.2,
            zoneMinutes = emptyList(), icuUrl = "https://intervals.icu/activities/i123",
        )
        val row = NotionLifeSchema.workoutRow(w)
        assertFits(NotionLifeSchema.TRENIROVKI, row)
        assertEquals("бег", row.getJSONObject("Вид").getJSONObject("select").getString("name"))
        assertEquals("5:53", row.getJSONObject("Темп").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
        assertEquals(32, row.getJSONObject("Минуты").getInt("number"))
    }

    @Test
    fun `силовая и зарядка ложатся в свои базы`() {
        val s = StrengthStore.Session(
            id = 1, date = "2026-09-05", block = "A · дом", title = "Силовая A",
            exercises = listOf(StrengthStore.ExerciseLog(exerciseId = "goblet", name = "Гоблет")),
            feel = 2, note = "тяжело пошли свинги", minutes = 35, done = true,
        )
        assertFits(NotionLifeSchema.SILOVYE, NotionLifeSchema.sessionRow(s))
        val g = StrengthStore.GtgDay(date = "2026-09-05", charged = true, hangSec = 25, negatives = 3, knee = "зелёный")
        val row = NotionLifeSchema.gtgRow(g)
        assertFits(NotionLifeSchema.ZARYADKA, row)
        assertEquals("выполнена", row.getJSONObject("Статус").getJSONObject("select").getString("name"))
    }

    @Test
    fun `категории - справочник с ценностью часа и порядком`() {
        val c = ZasechkaStore.Category("Работа: текущая", "работа по клиентам", baseMin = 90, value = 10)
        val row = NotionLifeSchema.categoryRow(c, 3)
        assertFits(NotionLifeSchema.KATEGORII, row)
        assertEquals(10, row.getJSONObject("Ценность часа").getInt("number"))
        assertEquals(90, row.getJSONObject("Базовое время мин").getInt("number"))
        assertEquals(3, row.getJSONObject("Порядок").getInt("number"))
        assertEquals("cat:работа: текущая", text(row, "Ключ"))
    }

    @Test
    fun `телефон за сутки ложится в Телефон, пустой день строкой не заводится`() {
        val p = NotionLifeSchema.PhoneDay(
            screenMin = 192, pickups = 41, glances = 12, youtubeMin = 47, telegramMin = 32, claudeMin = 70,
            callsMin = 18, calls = 3, callers = "Мама, Петя", apps = "Claude 1 ч 10 м · YouTube 47 м",
        )
        val row = NotionLifeSchema.phoneRow("2026-09-04", p)
        assertNotNull(row)
        row!!
        assertFits(NotionLifeSchema.TELEFON, row)
        assertEquals(47, row.getJSONObject("YouTube").getInt("number"))
        assertEquals(70, row.getJSONObject("Claude").getInt("number"))
        assertEquals(3, row.getJSONObject("Звонков").getInt("number"))
        assertEquals("2026-09", row.getJSONObject("Месяц").getJSONObject("select").getString("name"))
        assertEquals("2026-W36", row.getJSONObject("Неделя").getJSONObject("select").getString("name"))
        assertEquals("p2026-09-04", text(row, "Ключ"))
        assertNull(NotionLifeSchema.phoneRow("2026-08-19", NotionLifeSchema.PhoneDay()))
    }

    @Test
    fun `форма за сутки ложится в Форму, TSB считается из CTL и ATL`() {
        val h = SportStore.Health(
            date = "2026-09-04", restingHr = 52, hrv = 61, sleepHours = 7.2, sleepScore = 82, sleepQuality = 3,
            steps = 9100, weightKg = 86.3, vo2max = 47.0, ctl = 41.6, atl = 52.1, readiness = 0,
            kcal = 2100, protein = 145, fat = 70, carbs = 210, comments = "",
        )
        val row = NotionLifeSchema.healthRow(h)
        assertNotNull(row)
        row!!
        assertFits(NotionLifeSchema.FORMA, row)
        assertEquals(-10.5, row.getJSONObject("TSB").getDouble("number"), 0.01)
        assertEquals(86.3, row.getJSONObject("Вес кг").getDouble("number"), 0.01)
        assertEquals(145, row.getJSONObject("Белок съедено").getInt("number"))
        assertFalse("нулевая готовность не пишется", row.has("Готовность"))
        assertEquals("h2026-09-04", text(row, "Ключ"))
        val empty = h.copy(restingHr = 0, hrv = 0, sleepHours = 0.0, steps = 0, weightKg = 0.0, vo2max = 0.0, ctl = 0.0, atl = 0.0, kcal = 0, protein = 0)
        assertNull(NotionLifeSchema.healthRow(empty))
    }

    @Test
    fun `ISO-неделя - понедельник первый, год берётся у недели`() {
        assertEquals("2026-W36", NotionLifeSchema.weekKey("2026-09-04"))
        assertEquals("2026-W36", NotionLifeSchema.weekKey("2026-09-06")) // воскресенье той же недели
        assertEquals("2026-W37", NotionLifeSchema.weekKey("2026-09-07")) // понедельник
        assertEquals("2026-W01", NotionLifeSchema.weekKey("2025-12-29")) // 29.12.2025 — первая неделя 2026
        assertEquals("2020-W53", NotionLifeSchema.weekKey("2021-01-03")) // 03.01.2021 — 53-я неделя 2020
        assertEquals("2026-09", NotionLifeSchema.monthKey("2026-09-04"))
    }

    @Test
    fun `в каждой строке по дням есть Месяц и Неделя для итогов Notion`() {
        val e = entry(7, now - 3_600_000L, now, "Разбор", "Систематизация")
        val ribbon = NotionLifeSchema.ribbonRow(e, 60, 4, now)
        assertTrue(ribbon.has("Месяц") && ribbon.has("Неделя"))
        val w = SportStore.Workout(
            id = "i1", start = now - 3_600_000L, type = "Run", name = "Утренний бег", seconds = 1920,
            movingSeconds = 1900, distanceM = 5040.0, elevationM = 12.0, load = 40, intensity = 80,
            avgHr = 163, maxHr = 175, avgWatts = 0, normWatts = 0, paceSecPerKm = 372, gapSecPerKm = 0,
            cadence = 170, calories = 380, feel = 0, rpe = 0, decoupling = 0.0, efficiency = 0.0,
            zoneMinutes = emptyList(), icuUrl = "",
        )
        val row = NotionLifeSchema.workoutRow(w)
        assertTrue(row.has("Месяц") && row.has("Неделя"))
    }

    @Test
    fun `тело базы для создания несёт все колонки`() {
        val body = NotionLifeSchema.createBody(NotionLifeSchema.EDA, NotionLifeSchema.HUB_DEFAULT)
        val props = body.getJSONObject("properties")
        assertEquals(NotionLifeSchema.EDA.columns.size, props.length())
        assertTrue(props.getJSONObject("Вид").has("select"))
        assertTrue(props.getJSONObject("Приём").has("title"))
    }
}
