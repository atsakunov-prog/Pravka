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

    private fun assertFits(db: NotionLifeSchema.Db, row: org.json.JSONObject) {
        val extra = keys(row) - db.columns.map { it.name }.toSet()
        assertTrue("лишние колонки для «${db.name}»: $extra", extra.isEmpty())
        assertTrue("нет заголовка «${db.title.name}»", row.has(db.title.name))
    }

    private fun entry(
        id: Long, start: Long, end: Long, title: String, category: String,
        source: String = "voice", raw: String = "", client: String = "",
    ) = ZasechkaStore.Entry(
        id = id, start = start, end = end, raw = raw, title = title, category = category,
        client = client, useful = 0, source = source, synced = false, createdAt = start,
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
    fun `справочник - категории, приложения, цели и синк`() {
        val c = ZasechkaStore.Category("Работа: текущая", "работа по клиентам", baseMin = 90, value = 10)
        assertFits(NotionLifeSchema.SPRAVOCHNIK, NotionLifeSchema.categoryRow(c, 3, now))
        assertFits(NotionLifeSchema.SPRAVOCHNIK, NotionLifeSchema.appRow("com.google.android.youtube", "YouTube", "Потери", true, 47, now))
        assertFits(NotionLifeSchema.SPRAVOCHNIK, NotionLifeSchema.targetRow("Калории", "kcal", 2500, "ккал", 1, now))
        assertFits(NotionLifeSchema.SPRAVOCHNIK, NotionLifeSchema.statusRow("последний обход 12:00", now))
        assertFits(NotionLifeSchema.SPRAVOCHNIK, NotionLifeSchema.nowRow(null, now))
        assertFits(
            NotionLifeSchema.SPRAVOCHNIK,
            NotionLifeSchema.nowRow(entry(1, now - 600_000L, 0L, "Программирую Засечку", "Систематизация"), now),
        )
    }

    @Test
    fun `сутки складываются в Дни и не трогают Сашины поля`() {
        val start = NotionLifeSchema.dayStartOf("2026-09-04")
        val h = 3_600_000L
        val main = listOf(
            entry(1, start - 2 * h, start + 7 * h, "сон", "Сон", source = "auto"),
            entry(2, start + 7 * h, start + 8 * h, "Завтрак", "Еда"),
            entry(3, start + 8 * h, start + 12 * h, "Работа над отчётом", "Работа: текущая", client = "Tasty Coffee"),
            entry(4, start + 12 * h, start + 13 * h, "Отдых на диване", "Отдых"),
            entry(5, start + 13 * h, start + 24 * h, "не размечено", "Не размечено", source = "gap"),
        )
        val prev = entry(0, start - 3 * h, start - 2 * h, "сон", "Сон", source = "auto")
        val row = NotionLifeSchema.dayRow(
            date = "2026-09-04",
            main = main.filter { it.start >= start },
            allClosed = main + prev,
            meals = emptyList(),
            phone = NotionLifeSchema.PhoneDay(screenMin = 100, youtubeMin = 20, calls = 2, callsMin = 9, callers = "Мама"),
            body = NotionLifeSchema.BodyDay(workouts = 1, workoutMin = 32, gtgStatus = "частично", weightKg = 86.3),
            budgetOf = { (it.end - it.start) / 60_000L },
            worthOf = { c -> if (c.startsWith("Работа")) 10 else if (c == "Отдых") -2 else 0 },
            now = start + 30 * h,
        )
        assertNotNull(row)
        row!!
        assertFits(NotionLifeSchema.DNI, row)
        for (his in listOf("Дети дома", "Марианна дома днём", "Якорь утра", "Заметка дня")) {
            assertFalse("приложение тронуло поле Саши «$his»", row.has(his))
        }
        assertEquals(240, row.getJSONObject("Работа").getInt("number"))
        assertEquals(60, row.getJSONObject("Диван").getInt("number"))
        assertEquals(1, row.getJSONObject("Контактов").getInt("number"))
        // Сон цепочкой через полночь: кусок 21:00–22:00, кусок 22:00–07:00 = 600 минут.
        assertEquals(600, row.getJSONObject("Сон мин").getInt("number"))
        assertEquals("21:00", row.getJSONObject("Отбой").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
        assertEquals("07:00", row.getJSONObject("Подъём").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
        // Первое действие после подъёма — не еда: работа в 8:00, через 60 минут.
        assertEquals(60, row.getJSONObject("Первое действие через").getInt("number"))
        assertEquals(20, row.getJSONObject("YouTube").getInt("number"))
        assertEquals(38, row.getJSONObject("Балл дня").getInt("number"))
        assertEquals("частично", row.getJSONObject("Зарядка").getJSONObject("select").getString("name"))
        assertEquals(86.3, row.getJSONObject("Вес кг").getDouble("number"), 0.01)
    }

    @Test
    fun `пустые сутки строкой не заводятся`() {
        assertNull(
            NotionLifeSchema.dayRow(
                "2026-08-19", emptyList(), emptyList(), emptyList(),
                NotionLifeSchema.PhoneDay(), NotionLifeSchema.BodyDay(), { 0L }, { 0 }, now,
            ),
        )
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
