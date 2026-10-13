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
 * Рацион владельца (13.10.2026): продукты и блюда — запись Свода
 * `food.ration`, заводское семя — `assets/ration.json`. Случаи — его слова:
 * «обычная каша — всё, что там есть, кроме мягкого творога», «индейка,
 * сладкий соус чили», «индейку с кускусом».
 */
class RationTest {

    private val asset: String =
        listOf(File("src/main/assets/ration.json"), File("app/src/main/assets/ration.json")).first { it.isFile }.readText()

    private val book: Ration.Book = Ration.parse(asset)!!

    private fun dish(id: String) = book.dishes.first { it.id == id }

    @Test
    fun `заводской рацион читается целиком и без замечаний`() {
        assertTrue(book.problems.toString(), book.problems.isEmpty())
        assertEquals(20, book.products.size)
        assertEquals(listOf("kasha", "turkey-kuskus", "lunch", "dinner"), book.dishes.map { it.id })
        assertEquals(Ration.VERSION, JSONObject(asset).getInt("version"))
    }

    @Test
    fun `моя каша — без мягкого творога, с креатином, на 905 ккал`() {
        val kasha = dish("kasha")
        val items = book.items(kasha)
        assertEquals(
            listOf("Геркулес", "Голубика", "Клубника", "Протеин", "Творог 5%", "Креатин моногидрат 5 г"),
            items.map { it.name },
        )
        assertEquals(listOf(60, 250, 150, 66, 200, 0), items.map { it.grams })
        assertFalse(items.any { it.name.contains("мягкий", ignoreCase = true) })
        // Опус 13.10 насчитал «каша моя без мягкого творога» в 905 ккал — та же каша.
        assertEquals(905, book.kcal(kasha))
        assertEquals("завтрак", kasha.kind)
        assertTrue("каша моя" in kasha.aliases && "обычная каша" in kasha.aliases)
        val creatine = items.last()
        assertTrue(creatine.pill)
        assertEquals(0, creatine.kcal)
    }

    @Test
    fun `индейка в сладком чили — продукт с его словами, честно примерная`() {
        val turkey = book.product("turkey-chili")!!
        assertEquals("Индейка в сладком соусе чили ВкусВилл", turkey.name)
        assertTrue("индейка сладкий соус чили" in turkey.aliases)
        assertEquals("примерно", turkey.sure)
        val i = turkey.item()
        assertEquals(250, i.grams)
        assertEquals("примерно", i.sureness)
        // Калории сходятся с макросами — как требуют от модели.
        assertTrue(kotlin.math.abs(i.kcal - i.kcalFromMacros()) <= i.kcal / 10)
    }

    @Test
    fun `индейка с кускусом — блюдо из двух продуктов, приём по времени`() {
        val d = dish("turkey-kuskus")
        assertEquals("", d.kind)
        val items = book.items(d)
        assertEquals(listOf("Индейка в сладком соусе чили ВкусВилл", "Кускус"), items.map { it.name })
        assertEquals(listOf(250, 200), items.map { it.grams })
        assertEquals(328 + 224, book.kcal(d))
    }

    @Test
    fun `блок промпта знает слова владельца и правила блюд`() {
        val block = Ration.promptBlock(book)
        // «Мягкий творог 5%» в его речи — это мягкий 1.5%, а не творог 5%.
        val soft = block.lines().first { it.startsWith("- Творог мягкий") }
        assertTrue(soft, soft.contains("мягкий творог 5%"))
        val kasha = block.lines().first { it.startsWith("- Моя каша") }
        assertTrue(kasha, kasha.contains("каша моя") && kasha.contains("Творог 5% 200 г"))
        assertFalse(kasha, kasha.contains("Творог мягкий"))
        assertTrue(kasha, kasha.contains("Креатин моногидрат 5 г — 905 ккал"))
        assertTrue(block.contains("- Индейка с кускусом"))
        assertTrue(block.contains("«Без X» — убери X"))
        assertTrue(block.contains("sure «примерно»"))
        assertTrue(block.lines().first { it.startsWith("- Креатин") }.contains("pill: true"))
    }

    @Test
    fun `распознавателю — названия и как он их называет`() {
        val words = book.biasing()
        assertTrue("Индейка с кускусом" in words)
        assertTrue("каша моя" in words)
        assertTrue("индейка" in words)
        assertEquals(words.size, words.distinct().size)
    }

    @Test
    fun `порция пересчитывается от веса, имя без граммов`() {
        val tvorog = book.product("tvorog5")!!
        val i = tvorog.item(400)
        assertEquals("Творог 5%", i.name)
        assertEquals(400, i.grams)
        assertEquals(468, i.kcal)
        assertEquals(66, i.protein)
        // Вещества едут за весом.
        assertEquals(480.0, i.micro.getValue("ca"), 0.01)
    }

    @Test
    fun `правка руками с ошибкой не выключает блюдо целиком`() {
        val json = """
            {"version": 2, "products": [
              {"id": "a", "name": "Овсянка", "grams": 60, "kcal100": 366, "p100": 12, "f100": 6.5, "c100": 62},
              {"id": "a", "name": "Дубль", "grams": 1},
              {"name": "Без id"}
            ], "dishes": [
              {"id": "k", "name": "Каша", "kind": "Завтрак", "items": [{"product": "a", "grams": 80}, {"product": "нет", "grams": 10}]},
              {"id": "e", "name": "Пустое", "items": [{"product": "нет", "grams": 10}]}
            ]}
        """.trimIndent()
        val b = Ration.parse(json)!!
        assertEquals(listOf("Овсянка"), b.products.map { it.name })
        assertEquals(listOf("Каша"), b.dishes.map { it.name })
        assertEquals("завтрак", b.dishes[0].kind)
        assertEquals(listOf(80), b.items(b.dishes[0]).map { it.grams })
        // Дубль, без id, чужая строка в «Каше», чужая строка и пустота «Пустого».
        assertEquals(5, b.problems.size)
    }

    @Test
    fun `не тот вид — не рацион`() {
        // Прежний снимок Notion: приёмы и переключатели граммов, без products.
        assertNull(Ration.parse("""{"source": "notion:Рацион", "items": [{"id": "x", "name": "Творог", "meal": "1 Завтрак"}]}"""))
        assertNull(Ration.parse("не json"))
        assertNull(Ration.parse(null))
        assertNotNull(Ration.parse("""{"products": []}"""))
        assertTrue(Ration.parse("""{"products": []}""")!!.empty)
    }

    // ---- Правка на телефоне (владелец: «надо иметь возможность этот рацион редактировать») ----

    @Test
    fun `запись Свода туда и обратно — тот же рацион`() {
        val json = Ration.toJson(book, "2026-10-13")
        assertEquals(Ration.VERSION, json.getInt("version"))
        assertEquals("2026-10-13", json.getString("updated"))
        val again = Ration.parse(json.toString())!!
        assertEquals(book.products, again.products)
        assertEquals(book.dishes, again.dishes)
        // Пустое не пишется: «точно» и не-добавка — без полей.
        val gerkules = json.getJSONArray("products").getJSONObject(0)
        assertFalse(gerkules.has("sure") || gerkules.has("pill"))
        val turkey = (0 until json.getJSONArray("products").length()).map { json.getJSONArray("products").getJSONObject(it) }
            .first { it.getString("id") == "turkey-chili" }
        assertEquals("примерно", turkey.getString("sure"))
    }

    @Test
    fun `новый продукт получает id как у коннектора, правка — на своём месте`() {
        assertEquals("indeyka-s-pastoy", Ration.newId("Индейка с пастой", emptySet()))
        assertEquals("yogurt-grecheskiy-2", Ration.newId("Йогурт греческий 2%", emptySet()))
        assertEquals("kuskus-2", Ration.newId("Кускус", setOf("kuskus")))
        val yogurt = Ration.Product("", "Йогурт греческий 2%", listOf("греческий йогурт"), 150, 66.0, 8.0, 2.0, 4.0)
        val added = Ration.withProduct(book, null, yogurt)
        assertEquals("yogurt-grecheskiy-2", added.products.last().id)
        assertEquals(21, added.products.size)
        val turkey = book.product("turkey-chili")!!
        val fixed = Ration.withProduct(added, turkey.id, turkey.copy(kcal100 = 140.0, sure = "точно"))
        assertEquals(added.products.indexOfFirst { it.id == "turkey-chili" }, fixed.products.indexOfFirst { it.id == "turkey-chili" })
        assertEquals(140.0, fixed.product("turkey-chili")!!.kcal100, 0.0)
        assertTrue(Ration.parse(Ration.toJson(fixed, "2026-10-13").toString())!!.problems.isEmpty())
    }

    @Test
    fun `убрал продукт — ушёл и из блюд, пустое блюдо — следом`() {
        val noKuskus = Ration.withoutProduct(book, "kuskus")
        assertEquals(listOf("turkey-chili"), noKuskus.dishes.first { it.id == "turkey-kuskus" }.items.map { it.product })
        val noTurkey = Ration.withoutProduct(noKuskus, "turkey-chili")
        assertNull(noTurkey.dishes.firstOrNull { it.id == "turkey-kuskus" })
        assertTrue(Ration.parse(Ration.toJson(noTurkey, "2026-10-13").toString())!!.problems.isEmpty())
    }

    @Test
    fun `блюдо заводится, правится и убирается`() {
        val d = Ration.Dish("", "Индейка с пастой", "ужин", listOf("индейка с макаронами"),
            listOf(Ration.Portion("turkey-chili", 300), Ration.Portion("pasta", 200)))
        val added = Ration.withDish(book, null, d)
        val dish = added.dishes.last()
        assertEquals("indeyka-s-pastoy", dish.id)
        assertEquals(393 + 260, added.kcal(dish))
        val kasha = added.dishes.first { it.id == "kasha" }
        val noBerries = Ration.withDish(added, "kasha", kasha.copy(items = kasha.items.filter { it.product != "klubnika" }))
        assertEquals(905 - 48, noBerries.kcal(noBerries.dishes.first { it.id == "kasha" }))
        assertEquals(added.dishes.map { it.id }, noBerries.dishes.map { it.id })
        assertNull(Ration.withoutDish(noBerries, "kasha").dishes.firstOrNull { it.id == "kasha" })
    }
}
