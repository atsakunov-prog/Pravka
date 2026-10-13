package ru.zf.pravka.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Меню и вид Ковчега из Свода (Правка 5.0, этап 7, 14.10.2026): `ui.menu` и
 * `ui.look`. Заводское телефона — ровно `ui.menu_factory` контракта
 * (`server/contract/svod.json`), запись проверяется по правилам сервера, кривая
 * — заводское целиком; большие кнопки — экраны, их из меню не убрать. Адреса —
 * выдуманные (`example.org`).
 */
class KovchegSvodTest {

    private fun contract(): JSONObject {
        val f = listOf(File("../server/contract/svod.json"), File("server/contract/svod.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    private val nav = Kovcheg.navFrom(
        JSONArray(
            """[{"name": "Дом", "icon": "home", "url": "https://home.example.org/", "key": "dom"},
               {"name": "Дела", "icon": "dela", "url": "/", "key": "dela"},
               {"name": "Встречи", "icon": "mic", "url": "https://meet.example.org/"},
               {"name": "Кино", "icon": "film", "url": "https://kino.example.org/", "key": "kino"},
               {"name": "Курсы", "icon": "book", "url": "https://kursy.example.org/", "key": "kursy"},
               {"name": "VPN", "icon": "shield", "url": "https://vpn.example.org/"}]""",
        ),
    )
    private val own = Kovcheg.own(setOf(Kovcheg.SPORT, Kovcheg.FOOD, Kovcheg.DELA, Kovcheg.MONEY), crm = true)

    /** Меню как структура для сравнения: (название, подпись, [ключ]) — без подписей сервисов. */
    private fun shape(m: Kovcheg.Menu) = listOf(
        m.big.map { it.key to it.sub },
        m.directions.map { Triple(it.name, it.sub, it.items.map { s -> s.key }) },
        m.create?.let { Triple(it.name, it.sub, it.items.map { s -> s.key }) },
    )

    @Test
    fun `заводское — ровно ui menu_factory контракта`() {
        val ui = contract().getJSONObject("ui")
        val fromContract = Kovcheg.menuFrom(ui.getJSONObject("menu_factory"))!!
        assertEquals(shape(Kovcheg.FACTORY), shape(fromContract))
        // Названия и значки каталога — те же, что в контракте.
        val items = fromContract.big + fromContract.directions.flatMap { it.items } + fromContract.create!!.items
        for (s in items) assertEquals(s.key, Kovcheg.CATALOG.getValue(s.key), s.name to s.icon.ifEmpty { Kovcheg.CATALOG.getValue(s.key).second })
        assertTrue(contract().getJSONObject("svod").getJSONObject("keys").has(Kovcheg.MENU_KEY))
        // Токены вида телефона — те же имена, что у сервера.
        val desc = contract().getJSONObject("svod").getJSONObject("keys").getString(Kovcheg.LOOK_KEY)
        for (t in Kovcheg.TOKENS) assertTrue(t, desc.contains(t))
    }

    @Test
    fun `владелец переставил направление и переименовал сервис — так и в меню`() {
        val v = JSONObject(
            """{"v": 1, "directions": [
                 {"name": "Пара", "sub": "вдвоём хорошо", "items": [{"key": "kino", "name": "Фильмы"}]},
                 {"name": "Здоровье", "sub": "тело", "items": ["sport", "eda"]},
                 {"name": "Дело", "sub": "работа", "items": ["dela", "meetings", "crm"]},
                 {"name": "Быт", "sub": "дом", "items": ["dom", "vpn"]}],
               "create": null}""",
        )
        val menu = Kovcheg.menuFrom(v)!!
        val b = Kovcheg.build(menu, own, nav, "https://dela.example.org")
        assertEquals(listOf("Пара", "Здоровье", "Дело", "Быт", "Ещё"), b.directions.map { it.title })
        val para = b.directions.first()
        assertEquals("вдвоём хорошо", para.sub)
        assertEquals(listOf("Фильмы"), para.items.map { it.name })
        assertEquals(Kovcheg.Target.Web("https://kino.example.org/"), para.items.single().target)
        assertEquals(listOf("Дела", "Встречи", "CRM"), b.directions[2].items.map { it.name })
        // «Деньги» (свой экран), Claude и незнакомый сервису меню ключ «kursy» — в «Ещё»: ничего не теряется.
        assertEquals(listOf("Деньги", "Claude", "Курсы"), b.directions.last().items.map { it.name })
        // «Творчества» нет — его так записали; большие кнопки — заводские (поля big нет).
        assertNull(b.create)
        assertEquals(listOf("Сегодня", "Засечка", "Правка"), b.big.map { it.name })
    }

    @Test
    fun `большие кнопки — экраны, из меню не убрать, Засечка — только включённая`() {
        val v = JSONObject("""{"big": [{"key": "pravka", "name": "Диктовки", "sub": "всё сказанное"}], "directions": [{"name": "Дело", "items": ["dela"]}]}""")
        val b = Kovcheg.build(Kovcheg.menuFrom(v)!!, own, emptyList(), null, zasechka = false)
        assertEquals(listOf("Диктовки", "Сегодня"), b.big.map { it.name })
        assertEquals("всё сказанное", b.big.first().sub)
        assertEquals(Kovcheg.Target.Screen(Kovcheg.PRAVKA), b.big.first().target)
        assertEquals("день целиком", b.big[1].sub)
        // Веб «Спорта» и «Еды» на компе в меню телефона не идёт — их экраны и есть эти сервисы.
        val sportWeb = Kovcheg.navFrom(JSONArray("""[{"name": "Спорт", "icon": "activity", "url": "https://sport.example.org/"}]"""))
        val all = Kovcheg.build(Kovcheg.FACTORY, own, sportWeb, null).directions.flatMap { it.items }
        assertEquals(listOf(Kovcheg.Target.Screen(Kovcheg.SPORT)), all.filter { it.key == "sport" }.map { it.target })
    }

    @Test
    fun `кривая запись — заводское целиком`() {
        val bad = listOf(
            """{"directions": []}""",
            """{"directions": [{"name": "Дело", "items": ["dela"]}, {"name": "Быт", "items": ["dela"]}]}""",
            """{"directions": [{"name": "<b>Дело</b>"}]}""",
            """{"directions": [{"name": "Очень-очень длинное направление"}]}""",
            """{"directions": [{"name": "Дело", "items": [{"key": "Sport"}]}]}""",
            """{"directions": [{"name": "Дело", "items": [{"key": "kursy"}]}]}""",
            """{"directions": [{"name": "Дело"}, {"name": "дело"}]}""",
            """{"directions": "сломано"}""",
        )
        for (s in bad) assertNull(s, Kovcheg.menuFrom(JSONObject(s)))
        assertNull(Kovcheg.menuFrom(null))
        assertNull(Kovcheg.menuFrom(JSONArray()))
    }

    @Test
    fun `ключ сервиса — от сервера, у старого и в старом файле — по значку`() {
        assertEquals(listOf("dom", "dela", "meetings", "kino", "kursy", "vpn"), nav.map { it.key })
        assertEquals(nav, Kovcheg.navFrom(Kovcheg.navJson(nav)))
        // Файл, записанный до этапа 7, — без ключей.
        val old = Kovcheg.navFrom(JSONArray("""[{"name": "Кино", "icon": "film", "url": "https://k.example.org/"}, {"name": "Копии", "icon": "copies", "url": "https://c.example.org/"}]"""))
        assertEquals(listOf("kino", ""), old.map { it.key })
    }

    @Test
    fun `вид из Свода — только знакомые токены цветом rrggbb`() {
        val t = Kovcheg.tokens(JSONObject("""{"v": 1, "light": {"accent": "#2f7d4f", "accent-soft": "#EAF2ED", "evil": "#000000", "bg": "red"}, "dark": {"accent": "url(x)"}}"""))
        assertEquals(mapOf("accent" to 0xFF2F7D4FL, "accent-soft" to 0xFFEAF2EDL), t.light)
        assertEquals(emptyMap<String, Long>(), t.dark)
        assertEquals(Kovcheg.Tokens(), Kovcheg.tokens(null))
        assertEquals(Kovcheg.Tokens(), Kovcheg.tokens(JSONArray()))
    }

    @Test
    fun `шкала графиков от акцента — как заводская у заводского`() {
        val light = Kovcheg.ramp(0xFF2747C9L, dark = false)
        assertEquals(0xFF2747C9L, light[1])
        // Заводская светлая шкала: #6D84E0, #2747C9, #1C3497, #B8C4F2, #DFE5FA — выведенная в пределах пары десятков.
        val factory = listOf(0xFF6D84E0L, 0xFF2747C9L, 0xFF1C3497L, 0xFFB8C4F2L, 0xFFDFE5FAL)
        for ((a, b) in light.zip(factory)) for (s in listOf(16, 8, 0)) assertTrue(kotlin.math.abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)) <= 12)
        assertEquals(0xFFE9EDFAL, Kovcheg.mix(0xFF2747C9L, 0xFFFFFFFFL, 0.10))   // как мягкий акцент сервера
    }
}
