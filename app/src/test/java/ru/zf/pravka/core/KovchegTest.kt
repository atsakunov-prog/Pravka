package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Меню Ковчега (Правка 5.0, план §3): направления — по значку сервиса, как в
 * вебе Дел; свои экраны приложения — по режимам профиля; веб — только с
 * адресом из `/api/me`. Адреса здесь выдуманные (`example.org`): настоящих в
 * публичном репозитории нет.
 */
class KovchegTest {

    private val me = JSONObject(
        """
        {"user": "owner", "look": {"domain": "example.org", "icon": "", "nav": [
          {"name": "Дом", "icon": "home", "url": "https://home.example.org/"},
          {"name": "Дела", "icon": "dela", "url": "/"},
          {"name": "Встречи", "icon": "mic", "url": "https://meet.example.org/"},
          {"name": "Деньги", "icon": "wallet", "url": "https://money.example.org/"},
          {"name": "Спорт", "icon": "activity", "url": "https://sport.example.org/"},
          {"name": "Шаббат", "icon": "candles", "url": "https://shabbat.example.org/"},
          {"name": "Дети", "icon": "kids", "url": "https://kids.example.org/"},
          {"name": "VPN", "icon": "shield", "url": "https://vpn.example.org/"},
          {"name": "Книги", "icon": "book", "url": "https://books.example.org/"},
          {"name": "Кино", "icon": "film", "url": "https://kino.example.org/"},
          {"name": "Копии", "icon": "copies", "url": "https://copies.example.org/"},
          {"name": "кривой", "icon": "Bad Icon", "url": "https://x.example.org/"},
          {"name": "без адреса", "icon": "globe", "url": "javascript:alert(1)"}
        ]}}
        """.trimIndent(),
    )

    private val allModes = setOf(Kovcheg.SPORT, Kovcheg.FOOD, Kovcheg.DELA, Kovcheg.MONEY)

    @Test
    fun `сервисы из look nav — кривые записи молча мимо`() {
        val nav = Kovcheg.navFrom(me)
        assertEquals(11, nav.size)
        assertTrue(nav.none { it.name == "кривой" || it.name == "без адреса" })
        // Туда и обратно — телефон помнит их файлом.
        assertEquals(nav, Kovcheg.navFrom(Kovcheg.navJson(nav)))
        // Старый сервер без look — пусто, не ошибка.
        assertEquals(emptyList<Kovcheg.Nav>(), Kovcheg.navFrom(JSONObject("""{"user":"x"}""")))
    }

    @Test
    fun `восемь направлений по порядку, свои экраны — приложения`() {
        val dirs = Kovcheg.directions(Kovcheg.own(allModes, crm = true), Kovcheg.navFrom(me), "https://dela.example.org")
        assertEquals(
            listOf("Здоровье", "Ум", "Дело", "Деньги", "Круг", "Родительство", "Пара", "Быт", "Ещё"),
            dirs.map { it.title },
        )
        val byTitle = dirs.associateBy { it.title }
        // Спорт и Еда — экраны приложения, веб «Спорта» на компе в меню телефона не идёт.
        assertEquals(listOf("Спорт", "Еда"), byTitle.getValue("Здоровье").items.map { it.name })
        assertTrue(byTitle.getValue("Здоровье").items.all { it.target is Kovcheg.Target.Screen })
        // Дела — экран, Встречи — веб; порядок — как в направлении.
        assertEquals(listOf("Дела", "Встречи"), byTitle.getValue("Дело").items.map { it.name })
        assertEquals(Kovcheg.Target.Screen(Kovcheg.DELA), byTitle.getValue("Дело").items[0].target)
        assertEquals(Kovcheg.Target.Web("https://meet.example.org/"), byTitle.getValue("Дело").items[1].target)
        assertEquals(Kovcheg.Target.Screen(Kovcheg.MONEY), byTitle.getValue("Деньги").items.single().target)
        assertEquals(Kovcheg.Target.Screen(Kovcheg.CRM), byTitle.getValue("Круг").items.single().target)
        assertEquals(listOf("Дети", "Шаббат"), byTitle.getValue("Родительство").items.map { it.name })
        assertEquals(listOf("Дом", "VPN"), byTitle.getValue("Быт").items.map { it.name })
        // Незнакомый значок — в «Ещё», последним, как в вебе.
        assertEquals(listOf("Копии"), byTitle.getValue("Ещё").items.map { it.name })
    }

    @Test
    fun `без адресов и с выключенными режимами — пустые направления не показываются`() {
        val dirs = Kovcheg.directions(Kovcheg.own(setOf(Kovcheg.DELA), crm = false), emptyList(), null)
        assertEquals(listOf("Дело"), dirs.map { it.title })
        assertEquals(listOf("Дела"), dirs.single().items.map { it.name })
        // CRM без Дел не бывает.
        assertTrue(Kovcheg.own(setOf(Kovcheg.SPORT), crm = true).none { it.name == "CRM" })
    }

    @Test
    fun `столбики без дыр — направление k в столбик k по модулю n`() {
        val dirs = Kovcheg.directions(Kovcheg.own(allModes, crm = true), Kovcheg.navFrom(me), null)
            .filter { it.title != "Ещё" }
        val two = Kovcheg.columns(dirs, 2)
        assertEquals(listOf("Здоровье", "Дело", "Круг", "Пара"), two[0].map { it.title })
        assertEquals(listOf("Ум", "Деньги", "Родительство", "Быт"), two[1].map { it.title })
        val four = Kovcheg.columns(dirs, 4)
        assertEquals(listOf("Здоровье", "Круг"), four[0].map { it.title })
        assertEquals(listOf("Деньги", "Быт"), four[3].map { it.title })
    }

    @Test
    fun `адрес — полный, сами Дела и чужие схемы — мимо`() {
        assertEquals("https://a.example.org/x", Kovcheg.absolute("https://a.example.org/x", null))
        assertNull(Kovcheg.absolute("/", "https://dela.example.org"))
        assertEquals("https://dela.example.org/x", Kovcheg.absolute("/x", "https://dela.example.org/"))
        assertNull(Kovcheg.absolute("/x", null))
        assertNull(Kovcheg.absolute("ftp://a", null))
    }

    @Test
    fun `большие кнопки и Творчество`() {
        assertEquals(listOf("Сегодня", "Засечка", "Правка"), Kovcheg.BIG.map { it.name })
        assertEquals(listOf("день целиком", "что делаю сейчас", "все диктовки"), Kovcheg.BIG.map { it.sub })
        assertEquals(Kovcheg.Target.Claude, Kovcheg.CREATE.items.single().target)
        assertEquals("создаю новое", Kovcheg.CREATE.sub)
    }
}
