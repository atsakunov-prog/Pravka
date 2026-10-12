package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore

// Структура книги «Вся жизнь» живёт в коде, и главный способ её сломать —
// написать в строку колонку, которой у листа нет: книга возьмёт только
// колонки схемы, и значение молча пропадёт. Поэтому каждый построитель
// проверяется против своего листа.
class LifeSchemaTest {

    private val now = 1_757_073_600_000L // 05.09.2026 около полудня по Москве

    private fun keys(o: org.json.JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun text(row: org.json.JSONObject, key: String): String =
        row.getJSONObject(key).getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content")

    private fun assertFits(db: LifeSchema.Db, row: org.json.JSONObject) {
        val extra = keys(row) - db.columns.map { it.name }.toSet()
        assertTrue("лишние колонки для «${db.name}»: $extra", extra.isEmpty())
        assertTrue("нет заголовка «${db.title.name}»", row.has(db.title.name))
    }

    private fun entry(
        id: Long, start: Long, end: Long, title: String, category: String,
        source: String = "voice", raw: String = "", client: String = "", comment: String = "",
        useful: Int = 0,
    ) = ZasechkaStore.Entry(
        id = id, start = start, end = end, raw = raw, title = title, category = category,
        client = client, useful = useful, source = source, synced = false, createdAt = start,
        comment = comment,
    )

    private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    /** Начало ISO-строки даты со временем в зоне JVM — без смещения, его формат пишет сам. */
    private fun isoLocal(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ms))

    private fun titleText(row: org.json.JSONObject, key: String): String =
        row.getJSONObject(key).getJSONArray("title").getJSONObject(0).getJSONObject("text").getString("content")

    private fun dateStart(row: org.json.JSONObject, key: String): String =
        row.getJSONObject(key).getJSONObject("date").getString("start")

    @Test
    fun `в каждой базе ровно один заголовок и нет одноимённых колонок`() {
        for (db in LifeSchema.ALL) {
            assertEquals(db.name, 1, db.columns.count { it.type == "title" })
            assertEquals(db.name, db.columns.size, db.columns.map { it.name }.toSet().size)
        }
        assertEquals(LifeSchema.ALL.size, LifeSchema.ALL.map { it.name }.toSet().size)
    }

    @Test
    fun `строка ленты ложится в Засечку`() {
        val e = entry(42, now - 90 * 60_000L, now - 30 * 60_000L, "Встреча с Серёжей", "Семья", raw = "встреча с серёжей")
        val row = LifeSchema.ribbonRow(e, minutes = 60, worth = 6, now = now)
        assertFits(LifeSchema.ZASECHKA, row)
        assertEquals(6.0, row.getJSONObject("Очки").getDouble("number"), 0.01)
        assertEquals("голос", row.getJSONObject("Записано").getJSONObject("select").getString("name"))
        assertEquals("t42", row.getJSONObject("EntryId").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
    }

    @Test
    fun `комментарий к делу едет своей колонкой, пустой - пустым массивом`() {
        val e = entry(43, now - 90 * 60_000L, now - 30 * 60_000L, "Созвон с Ильёй", "Звонки", comment = "Договорились о бюджете до пятницы")
        val row = LifeSchema.ribbonRow(e, minutes = 60, worth = 7, now = now)
        assertFits(LifeSchema.ZASECHKA, row)
        assertEquals("Договорились о бюджете до пятницы", text(row, "Комментарий"))
        // Пустой комментарий кладётся тоже: колонка у строки есть всегда.
        val bare = LifeSchema.ribbonRow(e.copy(comment = ""), minutes = 60, worth = 7, now = now)
        assertEquals(0, bare.getJSONObject("Комментарий").getJSONArray("rich_text").length())
    }

    @Test
    fun `длинный комментарий доезжает до ячейки целиком`() {
        // Мысли за долгое дело складываются в один комментарий; хвост за
        // 1900-м знаком раньше молча терялся.
        val thoughts = (1..60).joinToString("\n") { "Мысль номер $it: " + "слово ".repeat(12).trim() }
        assertTrue(thoughts.length > 4000)
        val e = entry(45, now - 90 * 60_000L, now - 30 * 60_000L, "Работа над отчётом", "Работа", comment = thoughts)
        val row = LifeSchema.ribbonRow(e, minutes = 60, worth = 7, now = now)
        assertEquals(thoughts, text(row, "Комментарий"))
        assertEquals(Xlsx.Cell.Text(thoughts), LifeXlsx.cell(LifeSchema.ZASECHKA.columns.first { it.name == "Комментарий" }, row))
    }

    @Test
    fun `начало и конец дела - две даты, для глаз - Когда и Время коротко, оценка - числом или пустотой`() {
        val start = now - 90 * 60_000L
        val end = now - 30 * 60_000L
        val e = entry(44, start, end, "Разговор с Наташей", "Работа: звонки", useful = 4)
        val row = LifeSchema.ribbonRow(e, minutes = 60, worth = 7, now = now)
        assertFits(LifeSchema.ZASECHKA, row)
        // По-экселевски: «Начало» и «Конец» — настоящие даты, «Минуты» — их
        // разность; диапазона «Дата» в строке больше нет.
        assertTrue(dateStart(row, "Начало").startsWith(isoLocal(start)))
        assertTrue(dateStart(row, "Конец").startsWith(isoLocal(end)))
        assertFalse(row.has("Дата"))
        // Владелец: «первая колонка — название, вторая — 14:59–16:16, без
        // сентябрей». Для глаз — короткий текст рядом с настоящими датами.
        assertEquals("Разговор с Наташей", titleText(row, "Дело"))
        assertEquals("${hhmm(start)}–${hhmm(end)}", text(row, "Время"))
        assertEquals(LifeSchema.humanDay(LifeSchema.dayKey(start)), text(row, "Когда"))
        assertEquals(4, row.getJSONObject("Оценка").getInt("number"))
        // Не оценивал — колонка стирается, а не получает ноль: иначе средняя
        // оценка по дням считалась бы с нулями.
        val bare = LifeSchema.ribbonRow(e.copy(useful = 0), minutes = 60, worth = 7, now = now)
        assertTrue(bare.getJSONObject("Оценка").isNull("number"))
    }

    @Test
    fun `заполнитель и авто-факты помечаются словами`() {
        assertEquals("заполнитель", LifeSchema.recorded("gap"))
        assertEquals("auto", LifeSchema.sourceKind("gap"))
        assertEquals("авто: телефон или часы", LifeSchema.recorded("auto"))
        assertEquals("manual", LifeSchema.sourceKind("edit"))
        assertTrue(LifeSchema.RECORDED.contains(LifeSchema.recorded("todoist")))
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
        val row = LifeSchema.mealRow(m)
        assertFits(LifeSchema.EDA, row)
        assertEquals(380, row.getJSONObject("Ккал").getInt("number"))
        assertEquals("фото", row.getJSONObject("Записано").getJSONObject("select").getString("name"))
        assertEquals("завтрак", row.getJSONObject("Вид").getJSONObject("select").getString("name"))
        assertEquals("Завтрак · Омлет, Капучино", titleText(row, "Приём"))
        assertEquals(hhmm(now), text(row, "Время"))
        assertEquals(LifeSchema.humanDay(LifeSchema.dayKey(now)), text(row, "Когда"))
        assertTrue(dateStart(row, "Дата").startsWith(isoLocal(now)))
        // Уверенность приёма — по самой слабой позиции: «точно» и «примерно» дают «примерно».
        assertEquals("примерно", row.getJSONObject("Уверенность").getJSONObject("select").getString("name"))
    }

    @Test
    fun `витамины приёма едут своей колонкой, а таблетка помечена в составе`() {
        val m = FoodStore.Meal(
            id = 8, ts = now, createdAt = now, kind = "завтрак", raw = "творог и витамин D",
            items = listOf(
                MealItem(
                    name = "Творог 5%", grams = 200, kcal = 234, protein = 33, fat = 10, carbs = 3,
                    sureness = "точно", micro = mapOf("ca" to 240.0, "b12" to 2.0),
                ),
                MealItem(
                    name = "Витамин D3 2000 МЕ", grams = 0, kcal = 0, protein = 0, fat = 0, carbs = 0,
                    sureness = "точно", micro = mapOf("vd" to 50.0), pill = true,
                ),
            ),
            confirmed = true,
        )
        val row = LifeSchema.mealRow(m)
        assertFits(LifeSchema.EDA, row)
        assertEquals("Витамин D 50 мкг · Витамин B12 2 мкг · Кальций 240 мг", text(row, "Витамины"))
        assertTrue(text(row, "Состав").contains("Витамин D3 2000 МЕ (таблетка)"))
    }

    @Test
    fun `сутки витаминов ложатся в свою базу колонкой на вещество`() {
        val date = LifeSchema.dayKey(now)
        val total = FoodStore.DayTotal(
            date = date, kcal = 1800, protein = 120, fat = 60, carbs = 180, fiber = 20, meals = 3,
            micro = mapOf("ca" to 900.0, "vd" to 52.5, "mg" to 120.0, "vc" to 2500.0),
            microFood = mapOf("ca" to 900.0, "mg" to 120.0, "vc" to 2500.0),
            microPills = mapOf("vd" to 50.0),
            pills = "Витамин D3 2000 МЕ",
        )
        val row = LifeSchema.vitaminRow(total)!!
        assertFits(LifeSchema.VITAMINY, row)
        assertEquals(52.5, row.getJSONObject("Витамин D, мкг").getDouble("number"), 0.001)
        assertEquals(900.0, row.getJSONObject("Кальций, мг").getDouble("number"), 0.001)
        // Вещества, которого за день не было, в строке нет вовсе: ноль в ячейке
        // читался бы как измеренный ноль.
        assertFalse(row.has("Йод, мкг"))
        assertTrue(text(row, "Мало").contains("Магний"))
        assertEquals("Витамин C", text(row, "Перебор"))
        assertEquals("Витамин D3 2000 МЕ", text(row, "Из банки"))
        assertEquals("v$date", text(row, "Ключ"))
        // Пустые сутки строкой не становятся: «витаминов не ел» и «не считали» -
        // разные вещи.
        assertNull(LifeSchema.vitaminRow(total.copy(micro = emptyMap())))
    }

    @Test
    fun `справочник норм ложится строкой на вещество`() {
        for ((i, n) in Micronutrients.ALL.withIndex()) {
            val row = LifeSchema.normRow(n, i + 1)
            assertFits(LifeSchema.NORMY, row)
            assertEquals(n.name, titleText(row, "Вещество"))
            assertEquals(n.why, text(row, "Зачем"))
            assertEquals("n:" + n.id, text(row, "Ключ"))
            // Колонка справочника обязана совпасть с колонкой «Витаминов»,
            // иначе VLOOKUP в книге Excel ищет то, чего нет.
            assertTrue(LifeSchema.VITAMINY.has(text(row, "Колонка")))
        }
    }

    @Test
    fun `уверенность приёма - самая слабая среди позиций`() {
        fun item(s: String) = MealItem(name = "x", grams = 100, kcal = 100, protein = 5, fat = 5, carbs = 5, sureness = s)
        assertEquals("точно", LifeSchema.mealSureness(listOf(item("точно"), item("точно"))))
        assertEquals("наугад", LifeSchema.mealSureness(listOf(item("точно"), item("наугад"), item("примерно"))))
        assertEquals("примерно", LifeSchema.mealSureness(listOf(item("примерно"))))
        // Модель уверенности не назвала — колонка не пишется, чем гадать.
        assertNull(LifeSchema.mealSureness(listOf(item(""))))
        assertNull(LifeSchema.mealSureness(listOf(item("точно"), item(""))))
        assertNull(LifeSchema.mealSureness(emptyList()))
        // Все варианты — из списка опций колонки: фильтр книги видит ровно их.
        assertTrue(LifeSchema.SURENESS.containsAll(listOf("точно", "примерно", "наугад")))
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
        val row = LifeSchema.workoutRow(w)
        assertFits(LifeSchema.TRENIROVKI, row)
        assertEquals("бег", row.getJSONObject("Вид").getJSONObject("select").getString("name"))
        assertEquals("5:53", row.getJSONObject("Темп").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
        assertEquals(32, row.getJSONObject("Минуты").getInt("number"))
        // Как у дела: две даты вместо диапазона, для глаз — «Когда» и «Время».
        val start = now - 3_600_000L
        val end = start + 1920 * 1000L
        assertTrue(dateStart(row, "Начало").startsWith(isoLocal(start)))
        assertTrue(dateStart(row, "Конец").startsWith(isoLocal(end)))
        assertEquals("Бег · Утренний бег", titleText(row, "Тренировка"))
        assertEquals("${hhmm(start)}–${hhmm(end)}", text(row, "Время"))
    }

    @Test
    fun `силовая и зарядка ложатся в свои базы`() {
        val s = StrengthStore.Session(
            id = 1, date = "2026-09-05", block = "A · дом", title = "Силовая A",
            exercises = listOf(StrengthStore.ExerciseLog(exerciseId = "goblet", name = "Гоблет")),
            feel = 2, note = "тяжело пошли свинги", minutes = 35, done = true,
        )
        val sRow = LifeSchema.sessionRow(s)
        assertFits(LifeSchema.SILOVYE, sRow)
        assertEquals("05.09 · сб · Силовая A", titleText(sRow, "Сессия"))
        // День один, и колонка дня одна: «Дата» дублировала «День».
        assertEquals("2026-09-05", dateStart(sRow, "День"))
        assertFalse(sRow.has("Дата"))
        val g = StrengthStore.GtgDay(date = "2026-09-05", charged = true, hangSec = 25, negatives = 3, knee = "зелёный")
        val row = LifeSchema.gtgRow(g)
        assertFits(LifeSchema.ZARYADKA, row)
        assertEquals("выполнена", row.getJSONObject("Статус").getJSONObject("select").getString("name"))
    }

    @Test
    fun `силовая с одними галочками чек-листа тоже едет, пустая - нет`() {
        val bare = StrengthStore.Session(id = 2, date = "2026-09-06", block = "A · дом", title = "Силовая A", exercises = emptyList())
        assertFalse("пустая сессия — не строка", LifeSchema.sessionMatters(bare))
        assertTrue("галочки чек-листа — сессия", LifeSchema.sessionMatters(bare.copy(checkedIds = listOf("goblet"))))
        assertTrue("самочувствие — сессия", LifeSchema.sessionMatters(bare.copy(feel = 3)))
        assertTrue("закрыта кнопкой — сессия", LifeSchema.sessionMatters(bare.copy(done = true)))
        assertTrue("минуты — сессия", LifeSchema.sessionMatters(bare.copy(minutes = 20)))
        val row = LifeSchema.sessionRow(bare.copy(checkedIds = listOf("goblet", "swing")))
        assertFits(LifeSchema.SILOVYE, row)
        assertEquals(2, row.getJSONObject("Галочек").getInt("number"))
        assertEquals(0, row.getJSONObject("Упражнений").getInt("number"))
    }

    @Test
    fun `категории - справочник с ценностью часа и порядком`() {
        val c = ZasechkaStore.Category("Работа: текущая", "работа по клиентам", baseMin = 90, value = 10)
        val row = LifeSchema.categoryRow(c, 3)
        assertFits(LifeSchema.KATEGORII, row)
        assertEquals(10, row.getJSONObject("Ценность часа").getInt("number"))
        assertEquals(90, row.getJSONObject("Базовое время мин").getInt("number"))
        assertEquals(3, row.getJSONObject("Порядок").getInt("number"))
        assertEquals("cat:работа: текущая", text(row, "Ключ"))
    }

    @Test
    fun `телефон за сутки ложится в Телефон, пустой день строкой не заводится`() {
        val p = LifeSchema.PhoneDay(
            screenMin = 192, pickups = 41, glances = 12, youtubeMin = 47, telegramMin = 32, claudeMin = 70,
            callsMin = 18, calls = 3, callers = "Мама, Петя", apps = "Claude 1 ч 10 м · YouTube 47 м",
        )
        val row = LifeSchema.phoneRow("2026-09-04", p)
        assertNotNull(row)
        row!!
        assertFits(LifeSchema.TELEFON, row)
        assertEquals(47, row.getJSONObject("YouTube").getInt("number"))
        assertEquals(70, row.getJSONObject("Claude").getInt("number"))
        assertEquals(3, row.getJSONObject("Звонков").getInt("number"))
        assertEquals("2026-09", row.getJSONObject("Месяц").getJSONObject("select").getString("name"))
        assertEquals("2026-W36", row.getJSONObject("Неделя").getJSONObject("select").getString("name"))
        assertEquals("p2026-09-04", text(row, "Ключ"))
        assertNull(LifeSchema.phoneRow("2026-08-19", LifeSchema.PhoneDay()))
    }

    @Test
    fun `форма за сутки ложится в Форму, TSB считается из CTL и ATL`() {
        val h = SportStore.Health(
            date = "2026-09-04", restingHr = 52, hrv = 61, sleepHours = 7.2, sleepScore = 82, sleepQuality = 3,
            steps = 9100, weightKg = 86.3, vo2max = 47.0, ctl = 41.6, atl = 52.1, readiness = 0,
            kcal = 2100, protein = 145, fat = 70, carbs = 210, comments = "",
        )
        val row = LifeSchema.healthRow(h)
        assertNotNull(row)
        row!!
        assertFits(LifeSchema.FORMA, row)
        assertEquals(-10.5, row.getJSONObject("TSB").getDouble("number"), 0.01)
        assertEquals(86.3, row.getJSONObject("Вес кг").getDouble("number"), 0.01)
        assertEquals(145, row.getJSONObject("Белок съедено").getInt("number"))
        assertEquals(70, row.getJSONObject("Жиры съедено").getInt("number"))
        assertEquals(210, row.getJSONObject("Углеводы съедено").getInt("number"))
        assertFalse("нулевая готовность не пишется", row.has("Готовность"))
        assertEquals("h2026-09-04", text(row, "Ключ"))
        val empty = h.copy(restingHr = 0, hrv = 0, sleepHours = 0.0, steps = 0, weightKg = 0.0, vo2max = 0.0, ctl = 0.0, atl = 0.0, kcal = 0, protein = 0)
        assertNull(LifeSchema.healthRow(empty))
    }

    @Test
    fun `завтрашний прогноз формы строкой не становится`() {
        // intervals отдаёт день вперёд с одними CTL/ATL: «08.09 · вт» при живом
        // 07.09 читался как мусор. День наступит — строка появится.
        val tomorrow = SportStore.Health(
            date = "2026-09-08", restingHr = 0, hrv = 0, sleepHours = 0.0, sleepScore = 0, sleepQuality = 0,
            steps = 0, weightKg = 0.0, vo2max = 0.0, ctl = 16.0, atl = 17.9, readiness = 0,
            kcal = 0, protein = 0, fat = 0, carbs = 0, comments = "",
        )
        assertNull(LifeSchema.healthRow(tomorrow, today = "2026-09-07"))
        assertNotNull(LifeSchema.healthRow(tomorrow, today = "2026-09-08"))
        assertNotNull(LifeSchema.healthRow(tomorrow, today = "2026-09-09"))
    }

    @Test
    fun `ISO-неделя - понедельник первый, год берётся у недели`() {
        assertEquals("2026-W36", LifeSchema.weekKey("2026-09-04"))
        assertEquals("2026-W36", LifeSchema.weekKey("2026-09-06")) // воскресенье той же недели
        assertEquals("2026-W37", LifeSchema.weekKey("2026-09-07")) // понедельник
        assertEquals("2026-W01", LifeSchema.weekKey("2025-12-29")) // 29.12.2025 — первая неделя 2026
        assertEquals("2020-W53", LifeSchema.weekKey("2021-01-03")) // 03.01.2021 — 53-я неделя 2020
        assertEquals("2026-09", LifeSchema.monthKey("2026-09-04"))
    }

    @Test
    fun `в каждой строке по дням есть Месяц и Неделя для итогов`() {
        val e = entry(7, now - 3_600_000L, now, "Разбор", "Систематизация")
        val ribbon = LifeSchema.ribbonRow(e, 60, 4, now)
        assertTrue(ribbon.has("Месяц") && ribbon.has("Неделя"))
        val w = SportStore.Workout(
            id = "i1", start = now - 3_600_000L, type = "Run", name = "Утренний бег", seconds = 1920,
            movingSeconds = 1900, distanceM = 5040.0, elevationM = 12.0, load = 40, intensity = 80,
            avgHr = 163, maxHr = 175, avgWatts = 0, normWatts = 0, paceSecPerKm = 372, gapSecPerKm = 0,
            cadence = 170, calories = 380, feel = 0, rpe = 0, decoupling = 0.0, efficiency = 0.0,
            zoneMinutes = emptyList(), icuUrl = "",
        )
        val row = LifeSchema.workoutRow(w)
        assertTrue(row.has("Месяц") && row.has("Неделя"))
    }
}
