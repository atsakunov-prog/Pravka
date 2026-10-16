package ru.zf.pravka.core

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Что не так?» на всех экранах (core/ScreenDebug.kt, docs/dela-phone-24.md): где строка и с каким маршрутом, строки
 * экрана из узлов семантики по порядку чтения, граница размера, текст страницы сервиса, снимок по контракту
 * `dela-debug.json` и кольцо переходов. Данные выдуманные.
 */
class ScreenDebugTest {

    @org.junit.Before
    @After
    fun clear() {
        ScreenDebug.clearNav()
        DelaDebug.clearOps()
    }

    private fun n(
        text: String = "",
        top: Float,
        left: Float = 0f,
        h: Float = 40f,
        w: Float = 100f,
        visible: Boolean = true,
        role: String = "",
        state: List<String> = emptyList(),
        children: List<ScreenDebug.Node> = emptyList(),
    ) = ScreenDebug.Node(
        texts = if (text.isEmpty()) emptyList() else listOf(text), role = role, state = state,
        top = top, left = left, bottom = top + h, right = left + w, visible = visible, children = children,
    )

    // ------------------------------------------------------------ где строка

    @Test
    fun `режим выключен — строки нет нигде, включён — только со флагом сервера`() {
        val feat = setOf(DelaDebug.FEATURE)
        assertFalse(ScreenDebug.show(null, feat))
        assertFalse(ScreenDebug.show(DelaDebug.Info(on = false), feat))
        assertFalse("старый сервер без флага debug", ScreenDebug.show(DelaDebug.Info(on = true), emptySet()))
        assertTrue(ScreenDebug.show(DelaDebug.Info(on = true, new = 2, last = 12), feat))
    }

    @Test
    fun `маршрут — экран режима, страница поверх, сервис, во вкладке Дел своя строка`() {
        assertEquals("app:today", ScreenDebug.at("today")?.route)
        assertEquals("app:zasechka", ScreenDebug.at("zasechka")?.route)
        // Вкладка Дел на своём сервере — строка Дел со снимком Дел, общей нет (не дублировать).
        assertNull(ScreenDebug.at(ScreenDebug.DELA, delaOwn = true))
        // Todoist вместо сервера — своей строки у Дел нет, общая есть.
        assertEquals("app:dela", ScreenDebug.at(ScreenDebug.DELA, delaOwn = false)?.route)
        // Страница поверх Дел — уже не Дела: строка общая, под ней — экран.
        val settings = ScreenDebug.at(ScreenDebug.DELA, pages = listOf("phone", "settings:dela"), delaOwn = true)!!
        assertEquals("app:page:settings:dela", settings.route)
        assertEquals("dela", settings.mode["under"])
        assertEquals("phone › settings:dela", settings.mode["pages"])
        val web = ScreenDebug.at("today", web = "Встречи", mode = mapOf("url" to "https://example.org/m/12"))!!
        assertEquals("web:Встречи", web.route)
        assertEquals("https://example.org/m/12", web.mode["url"])
        assertEquals("Путь", ScreenDebug.at("sport", mode = mapOf("body_part" to "Путь"))!!.mode["body_part"])
    }

    // ------------------------------------------------------------ порядок

    @Test
    fun `соседи на одной высоте — слева направо, хотя верх у них разный`() {
        // Шапка: значок (верх 12), название (верх 8), кнопки справа (верх 14) — одна строка экрана.
        val header = listOf(
            n("стоимость Claude", top = 14f, left = 300f, h = 28f, w = 40f),
            n("Ковчег", top = 12f, left = 16f, h = 32f, w = 32f),
            n("Засечка", top = 8f, left = 60f, h = 40f, w = 200f),
        )
        assertEquals(listOf("Ковчег", "Засечка", "стоимость Claude"), ScreenDebug.ordered(header).map { it.texts.single() })
    }

    @Test
    fun `список — сверху вниз, разворот — левая колонка целиком, потом правая`() {
        val list = listOf(n("третья", top = 100f), n("первая", top = 0f), n("вторая", top = 50f))
        assertEquals(listOf("первая", "вторая", "третья"), ScreenDebug.ordered(list).map { it.texts.single() })
        val left = n(top = 100f, left = 0f, h = 700f, w = 420f, children = listOf(n("Л1", top = 100f), n("Л2", top = 160f)))
        val right = n(top = 120f, left = 430f, h = 600f, w = 420f, children = listOf(n("П1", top = 120f, left = 430f), n("П2", top = 170f, left = 430f)))
        val root = n(top = 0f, h = 900f, w = 852f, children = listOf(right, n("Шапка", top = 0f, h = 60f, w = 852f), left))
        assertEquals(listOf("Шапка", "Л1", "Л2", "П1", "П2"), ScreenDebug.rows(root).map { it.text })
    }

    @Test
    fun `строка «сказать» поверх ленты внизу — после ленты`() {
        val feed = n(top = 60f, h = 840f, w = 411f, children = (0 until 5).map { n("пункт $it", top = 60f + it * 50f) })
        val say = n("Скажи, что сделать", top = 820f, left = 12f, h = 56f, w = 387f)
        val rows = ScreenDebug.rows(n(top = 0f, h = 900f, w = 411f, children = listOf(say, feed)))
        assertEquals("Скажи, что сделать", rows.last().text)
        assertEquals("пункт 0", rows.first().text)
    }

    // ------------------------------------------------------------ строки

    @Test
    fun `строка — тексты узла одной строкой, роль и состояние словами, было ли в окне`() {
        val toggle = ScreenDebug.Node(
            texts = listOf("Переливы", "  свет режима\nплывёт  ", "Переливы"), role = "переключатель", state = listOf("вкл"),
            top = 0f, bottom = 48f, right = 411f,
        )
        val below = n("Вид диска", top = 2000f, visible = false)
        val noText = n(top = 60f, role = "кнопка")
        val rows = ScreenDebug.rows(n(top = 0f, h = 2400f, children = listOf(below, toggle, noText)))
        assertEquals(2, rows.size)
        assertEquals("Переливы · свет режима плывёт", rows[0].text)
        assertEquals("переключатель · вкл", rows[0].status)
        assertTrue(rows[0].inView)
        assertEquals("Вид диска", rows[1].text)
        assertFalse("ниже окна — не было в окне", rows[1].inView)
    }

    @Test
    fun `ключи и токены — не в снимок, длинная строка — коротко`() {
        val key = "sk-ant-api03-" + "x".repeat(40)
        val rows = ScreenDebug.rows(n(top = 0f, children = listOf(
            n("Ключ API: $key", top = 0f),
            n("токен " + "A1b2C3d4".repeat(5) + " · ок", top = 50f),
            n("Ромашка: 2 дела · 18:50", top = 100f),
            n("а".repeat(1000), top = 150f),
        )))
        assertEquals("Ключ API: •••", rows[0].text)
        assertEquals("токен ••• · ок", rows[1].text)
        assertEquals("Ромашка: 2 дела · 18:50", rows[2].text)
        assertEquals(ScreenDebug.ROW_MAX, rows[3].text.length)
        assertTrue(rows[3].text.endsWith("…"))
    }

    // ------------------------------------------------------------ граница размера

    @Test
    fun `граница — сначала то, что было в окне, порядок экрана сохраняется`() {
        val rows = (0 until 10).map { i -> ScreenDebug.Row("строка $i", inView = i in 6..8) }
        val fit = ScreenDebug.fit(rows, maxRows = 5)
        assertEquals(listOf("строка 0", "строка 1", "строка 6", "строка 7", "строка 8"), fit.rows.map { it.text })
        assertEquals(10, fit.total)
        assertTrue(fit.cut)
        val chars = ScreenDebug.fit(rows, maxChars = "строка 0".length * 4)
        assertEquals(listOf("строка 0", "строка 6", "строка 7", "строка 8"), chars.rows.map { it.text })
        val all = ScreenDebug.fit(rows)
        assertEquals(10, all.rows.size)
        assertFalse(all.cut)
    }

    @Test
    fun `снимок огромного экрана — в границах снимка Дел, а не сотни килобайт`() {
        val rows = (0 until 2000).map { i -> ScreenDebug.Row("строка $i " + "т".repeat(290), inView = i < 20) }
        val snap = ScreenDebug.snapshot(ScreenDebug.At("app:food"), "Еда", "чт, 16 окт", rows)
        val json = snap.toString()
        assertTrue("снимок ${json.length} знаков", json.length < 40_000)
        assertEquals(2000, snap.getInt("rows_total"))
        assertTrue(snap.getBoolean("rows_cut"))
        val a = snap.getJSONArray("rows")
        assertTrue(a.length() <= ScreenDebug.ROWS)
        // Всё, что было в окне, легло.
        assertEquals(20, (0 until a.length()).count { a.getJSONObject(it).getBoolean("in_view") })
    }

    // ------------------------------------------------------------ снимок

    @Test
    fun `снимок — маршрут, шапка, режим, строки, страница, журнал и очередь без записок отладки`() {
        val at = ScreenDebug.at("zasechka", mode = mapOf("look" to "v2"))!!
        val rows = listOf(
            ScreenDebug.Row("Сейчас · Ромашка: смета · 0:42", true, "кнопка"),
            ScreenDebug.Row("Итог дня", false),
        )
        val web = ScreenDebug.Web("https://example.org/m/12", "Встреча", "0 из 2400 (окно 820)", listOf(ScreenDebug.Row("Итоги встречи", true, group = "страница")))
        val queue = listOf(
            JSONObject().put("op", "task.set").put("id", "t-1"),
            JSONObject().put("op", "debug.add").put("note", JSONObject()),
        )
        val s = ScreenDebug.snapshot(
            at, "Засечка", "чт, 16 окт · очки +84", rows, web, mapOf("theme" to "тёмная", "wide" to false, "empty" to ""),
            "999 5.0", listOf("10-16 10:14:52.000  синк Дел: ок"), queue,
        )
        assertEquals(1, s.getInt("v"))
        assertEquals("app:zasechka", s.getString("route"))
        assertEquals("phone", s.getString("kind"))
        assertEquals("Засечка", s.getString("title"))
        assertEquals("чт, 16 окт · очки +84", s.getString("sub"))
        val mode = s.getJSONObject("mode")
        assertEquals("v2", mode.getString("look"))
        assertEquals("тёмная", mode.getString("theme"))
        assertFalse("ложь и пустое в режим не идут", mode.has("wide") || mode.has("empty"))
        val r = s.getJSONArray("rows")
        assertEquals(3, r.length())
        assertEquals("Сейчас · Ромашка: смета · 0:42", r.getJSONObject(0).getString("title"))
        assertEquals("кнопка", r.getJSONObject(0).getString("status"))
        assertTrue(r.getJSONObject(0).getBoolean("in_view"))
        assertFalse(r.getJSONObject(1).getBoolean("in_view"))
        assertEquals("страница", r.getJSONObject(2).getString("group"))
        assertEquals(3, s.getInt("rows_total"))
        assertFalse(s.has("rows_cut"))
        assertEquals("https://example.org/m/12", s.getJSONObject("web").getString("url"))
        assertEquals("999 5.0", s.getString("build"))
        assertEquals(1, s.getJSONArray("events").length())
        assertEquals("записка отладки в очереди снимка не нужна", 1, s.getJSONArray("queue").length())
    }

    @Test
    fun `лист «Отладка» читает общий снимок теми же словами — строки экрана и что было в окне`() {
        val s = ScreenDebug.snapshot(
            ScreenDebug.At("app:money"), "Деньги", "октябрь",
            listOf(ScreenDebug.Row("Траты", true, "вкладка · выбрано"), ScreenDebug.Row("Кофе · 350 ₽", false)),
        )
        val lines = DelaDebug.snapshotLines(s)
        assertEquals("экран: Деньги · app:money · октябрь", lines[0])
        assertTrue(lines.any { it.startsWith("строки списка: 2") })
        assertTrue(lines.any { it.contains("★") && it.contains("Траты") && it.contains("вкладка · выбрано") })
        assertTrue(lines.any { it.contains("Кофе · 350 ₽") && !it.contains("★") })
    }

    @Test
    fun `записка с общим снимком — та же операция debug add, что у Дел`() {
        val s = ScreenDebug.snapshot(ScreenDebug.At("app:today"), "Сегодня", null, listOf(ScreenDebug.Row("Сейчас", true)))
        val op = DelaDebug.addOp("op-1", "n-1", "погода не та", "999", "411×900 dp сложен", "2026-10-16T10:15:00+03:00", s, org.json.JSONArray())
        assertEquals("debug.add", op.getString("op"))
        val note = op.getJSONObject("note")
        assertEquals("phone", note.getString("source"))
        assertEquals("app:today", note.getJSONObject("snapshot").getString("route"))
    }

    // ------------------------------------------------------------ страница сервиса

    @Test
    fun `текст страницы сервиса — ответ evaluateJavascript строкой JSON`() {
        val inner = JSONObject().put("url", "https://example.org/m/12?token=abc&x=1#top").put("title", "Встреча · итоги")
            .put("y", 640).put("h", 2400).put("vh", 820)
            .put("rows", org.json.JSONArray().put(org.json.JSONArray().put("Итоги встречи").put(1)).put(org.json.JSONArray().put("  Решили:\n созвон ").put(0)))
        val raw = JSONObject.quote(inner.toString())
        val w = ScreenDebug.web(raw)!!
        assertEquals("https://example.org/m/12?token=•••&x=1#top", w.url)
        assertEquals("Встреча · итоги", w.title)
        assertEquals("640 из 2400 (окно 820)", w.scroll)
        assertEquals(listOf("Итоги встречи", "Решили: созвон"), w.rows.map { it.text })
        assertEquals(listOf(true, false), w.rows.map { it.inView })
        assertTrue(w.rows.all { it.group == "страница" })
        assertNull(ScreenDebug.web(null))
        assertNull(ScreenDebug.web("null"))
        assertNull(ScreenDebug.web("не json"))
        assertNotNull(ScreenDebug.web(JSONObject.quote(JSONObject().put("url", "https://example.org").put("error", "boom").toString())))
    }

    @Test
    fun `адрес без значений ключей`() {
        assertEquals("https://example.org/a", ScreenDebug.url("https://example.org/a"))
        assertEquals("https://example.org/a?page=2&api_key=•••&Auth=•••", ScreenDebug.url("https://example.org/a?page=2&api_key=s3cr3t&Auth=zzz"))
    }

    // ------------------------------------------------------------ переходы

    @Test
    fun `переходы — своё кольцо, тот же экран подряд не пишется, в действиях — вид nav по времени`() {
        val t0 = 1_760_598_000_000L
        ScreenDebug.noteNav("app:today", "Сегодня", t0)
        ScreenDebug.noteNav("app:today", "Сегодня", t0 + 1_000)
        ScreenDebug.noteNav("app:zasechka", "Засечка", t0 + 2_000)
        DelaDebug.note("toast", "Записал", now = t0 + 1_500)
        assertEquals(2, ScreenDebug.recentNav().size)
        repeat(40) { ScreenDebug.noteNav("app:page:$it", "", t0 + 3_000 + it) }
        assertEquals(ScreenDebug.NAV, ScreenDebug.recentNav().size)
        ScreenDebug.clearNav()
        ScreenDebug.noteNav("app:today", "Сегодня", t0)
        ScreenDebug.noteNav("app:zasechka", "Засечка", t0 + 2_000)
        val now = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(t0 + 5_000), ZoneOffset.ofHours(3))
        val a = DelaDebug.actions(emptyList(), emptyList(), now, DelaDebug.recentNotes() + ScreenDebug.recentNav())
        assertEquals(listOf("nav", "toast", "nav"), (0 until a.length()).map { a.getJSONObject(it).getString("kind") })
        assertEquals("Засечка [app:zasechka]", a.getJSONObject(2).getString("text"))
        assertTrue(DelaDebug.actionLines(a).last().contains("переход: Засечка [app:zasechka]"))
    }
}
