package ru.zf.pravka.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.zf.pravka.core.MealItem
import ru.zf.pravka.core.Micronutrients

// Штрихкод упаковки → КБЖУ на 100 г из Open Food Facts.
//
// Зачем вообще, если модель и так считает: на упаковке КБЖУ НАПИСАН. Модель
// его угадывает («протеиновый батончик» - это от 180 до 400 ккал), а база
// знает точно, и такая позиция помечается «точно», а не «примерно».
//
// Открытый API без ключа и без регистрации; их правило одно - представиться в
// User-Agent, что мы и делаем. База знает не всё, особенно российские марки:
// «не нашлось» здесь нормальный ответ, а не ошибка - тогда владелец снимает
// этикетку камерой, и КБЖУ читает модель.
class OpenFoodFacts(private val client: OkHttpClient) {

    companion object {
        private const val UA = "Pravka/2.0 (личное приложение; a.tsakunov@znakomiy.pro)"
        private val FIELDS = listOf(
            "product_name", "product_name_ru", "generic_name", "brands",
            "quantity", "serving_size", "serving_quantity", "nutriments",
        ).joinToString(",")

        /** Ключ справочника → имя нутриента в Open Food Facts. */
        private val OFF_KEYS = listOf(
            "va" to "vitamin-a",
            "vd" to "vitamin-d",
            "ve" to "vitamin-e",
            "vk" to "vitamin-k",
            "vc" to "vitamin-c",
            "b1" to "vitamin-b1",
            "b2" to "vitamin-b2",
            // Ниацин у них по старому названию, витамин PP.
            "b3" to "vitamin-pp",
            "b6" to "vitamin-b6",
            "b9" to "vitamin-b9",
            "b12" to "vitamin-b12",
            "ca" to "calcium",
            "fe" to "iron",
            "mg" to "magnesium",
            "zn" to "zinc",
            "k" to "potassium",
            "na" to "sodium",
            "se" to "selenium",
            "i" to "iodine",
            "o3" to "omega-3-fat",
        )
    }

    /** Найденный продукт: всё на 100 г, плюс порция, если она объявлена. */
    data class Product(
        val code: String,
        val name: String,
        val brand: String,
        val quantity: String,       // «330 мл», «100 г» — как на упаковке
        val servingGrams: Int,      // 0 = порция не объявлена
        val kcal100: Double,
        val protein100: Double,
        val fat100: Double,
        val carbs100: Double,
        val fiber100: Double,
        /** Витамины и элементы на 100 г, ключами `Micronutrients` — что база знает. */
        val micro100: Map<String, Double> = emptyMap(),
    ) {
        val known: Boolean get() = kcal100 > 0 || protein100 > 0 || fat100 > 0 || carbs100 > 0

        val title: String
            get() = listOf(brand, name).filter { it.isNotBlank() }.joinToString(" ").ifBlank { code }

        /** Позиция дневника на [grams] граммов этого продукта. */
        fun item(grams: Int): MealItem {
            val k = grams / 100.0
            return MealItem(
                name = title + (if (grams > 0) ", $grams г" else ""),
                grams = grams,
                kcal = Math.round(kcal100 * k).toInt(),
                protein = Math.round(protein100 * k).toInt(),
                fat = Math.round(fat100 * k).toInt(),
                carbs = Math.round(carbs100 * k).toInt(),
                fiber = Math.round(fiber100 * k).toInt(),
                sureness = "точно",
                micro = Micronutrients.scaleBy(micro100, k),
            )
        }
    }

    /**
     * Витамины и элементы с упаковки. Open Food Facts хранит все `_100g` в
     * СИ-граммах, даже когда на этикетке написано «12 мг»: витамин C 12 мг
     * приезжает как 0.012. Поэтому каждое вещество переводится в свою
     * единицу справочника, а не берётся как есть — иначе дневная норма
     * закрывалась бы тысячными долями.
     *
     * Знает база не всё и не всегда: чего нет — того нет, выдумывать тут
     * нечего, штрихкод для того и нужен, что на упаковке НАПИСАНО.
     */
    private fun micro(n: JSONObject): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for ((id, key) in OFF_KEYS) {
            val grams = n.optDouble(key + "_100g", 0.0)
            if (grams <= 0 || !grams.isFinite()) continue
            val nutrient = Micronutrients.byId(id) ?: continue
            val value = when (nutrient.unit) {
                "мкг" -> grams * 1_000_000
                "мг" -> grams * 1_000
                else -> grams
            }
            // Заслон от перепутанных единиц в чужой базе: сто норм в ста
            // граммах не бывает ни у одного продукта.
            if (value > 0 && value < nutrient.norm * 100) out[id] = value
        }
        return out
    }

    suspend fun lookup(barcode: String): Result<Product> = withContext(Dispatchers.IO) {
        runCatching {
            val code = barcode.filter { it.isDigit() }
            require(code.length in 6..14) { "Это не похоже на штрихкод" }
            val url = "https://world.openfoodfacts.org/api/v2/product/$code.json?fields=$FIELDS"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (response.code == 404) throw NoSuchElementException("Такого штрихкода в базе нет")
                if (!response.isSuccessful) {
                    throw IllegalStateException("Open Food Facts: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            val o = JSONObject(body)
            if (o.optInt("status", 0) != 1) throw NoSuchElementException("Такого штрихкода в базе нет")
            val p = o.optJSONObject("product") ?: throw NoSuchElementException("Пустой ответ базы")
            val n = p.optJSONObject("nutriments") ?: JSONObject()
            // Килокалории бывают только в килоджоулях: переводим сами.
            val kcal = n.optDouble("energy-kcal_100g", 0.0).takeIf { it > 0 }
                ?: (n.optDouble("energy-kj_100g", 0.0) / 4.184)
            val product = Product(
                code = code,
                name = p.optString("product_name_ru").ifBlank { p.optString("product_name") }
                    .ifBlank { p.optString("generic_name") },
                brand = p.optString("brands").split(",").firstOrNull()?.trim().orEmpty(),
                quantity = p.optString("quantity"),
                servingGrams = p.optDouble("serving_quantity", 0.0).toInt(),
                kcal100 = kcal,
                protein100 = n.optDouble("proteins_100g", 0.0),
                fat100 = n.optDouble("fat_100g", 0.0),
                carbs100 = n.optDouble("carbohydrates_100g", 0.0),
                fiber100 = n.optDouble("fiber_100g", 0.0),
                micro100 = micro(n),
            )
            if (!product.known) throw NoSuchElementException("В базе есть товар, но без КБЖУ")
            product
        }
    }
}
