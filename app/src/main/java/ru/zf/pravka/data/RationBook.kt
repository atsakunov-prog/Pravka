package ru.zf.pravka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.core.MealItem

// Справочник продуктов: 24 позиции штатного рациона владельца с КБЖУ на 100 г,
// снятыми с настоящих этикеток (база Notion «Рацион»).
//
// Зачем, если модель и так считает: «творог» в её голове — это средний творог
// из интернета, а у владельца это ВкусВилл 5% на 117 ккал/100 г, и на этикетке
// написано именно 5%, а не 6% («сверено» — его же пометка). Половина его еды
// повторяется каждый день, и по этой половине угадывать нечего.
//
// Работает как словарь Правки: сначала подсказка модели блоком в промпте,
// потом — точный пересчёт по граммам, если продукт узнан. Файл статический
// (assets), собирается `tools/gen_reference.py`.
class RationBook(private val context: Context) {

    companion object {
        private const val ASSET = "ration.json"
    }

    /** Продукт рациона. Все числа — на 100 г. */
    data class Product(
        val id: String,
        val name: String,
        val meal: String,        // «1 Завтрак» | «2 Обед» | «3 Ужин» | «Слот · опция»
        val defaultGrams: Int,   // штатная порция владельца
        val kcal100: Double,
        val protein100: Double,
        val fat100: Double,
        val carbs100: Double,
        val note: String,
    ) {
        /** Позиция дневника на [grams] граммов. */
        fun item(grams: Int): MealItem {
            val weight = if (grams > 0) grams else defaultGrams.coerceAtLeast(100)
            val k = weight / 100.0
            return MealItem(
                name = shortName + ", $weight г",
                grams = weight,
                kcal = Math.round(kcal100 * k).toInt(),
                protein = Math.round(protein100 * k).toInt(),
                fat = Math.round(fat100 * k).toInt(),
                carbs = Math.round(carbs100 * k).toInt(),
                sureness = "точно",
            )
        }

        /** Без служебных уточнений в скобках: «Творог 5%», а не «Творог 5% (ужин)». */
        val shortName: String
            get() = name.substringBefore(" (").trim().ifBlank { name }
    }

    @Volatile private var items: List<Product> = emptyList()
    val all: List<Product> get() = items
    val loaded: Boolean get() = items.isNotEmpty()

    suspend fun load(): List<Product> {
        if (items.isNotEmpty()) return items
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                parse(JSONObject(text))
            }.getOrNull()
        }
        if (parsed != null) items = parsed
        return items
    }

    private fun parse(o: JSONObject): List<Product> {
        val out = mutableListOf<Product>()
        val array = o.optJSONArray("items") ?: return out
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            out.add(
                Product(
                    id = p.optString("id"),
                    name = p.optString("name"),
                    meal = p.optString("meal"),
                    defaultGrams = p.optInt("grams"),
                    kcal100 = p.optDouble("kcal100", 0.0),
                    protein100 = p.optDouble("p100", 0.0),
                    fat100 = p.optDouble("f100", 0.0),
                    carbs100 = p.optDouble("c100", 0.0),
                    note = p.optString("note"),
                )
            )
        }
        return out
    }

    /**
     * Сказанное название → продукт рациона. По основам слов: «творожок мягкий»
     * найдёт «Творог мягкий высокобелковый 1.5%». Не нашли — null, и тогда
     * КБЖУ считает модель как обычно.
     */
    fun match(spoken: String): Product? {
        val said = ExerciseBook.normalize(spoken).split(' ')
            .filter { it.length > 2 }.map { ExerciseBook.stem(it) }.toSet()
        if (said.isEmpty()) return null
        var best: Product? = null
        var bestScore = 0.0
        for (p in items) {
            val theirs = ExerciseBook.normalize(p.shortName).split(' ')
                .filter { it.length > 2 }.map { ExerciseBook.stem(it) }.toSet()
            if (theirs.isEmpty()) continue
            val shared = theirs.count { it in said }
            if (shared == 0) continue
            val score = shared.toDouble() / theirs.size
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return if (bestScore >= 0.5) best else null
    }

    /**
     * Блок промпта: штатный рацион с точными цифрами. Уезжает в разбор еды —
     * дешевле пары сотен токенов, а «творог» перестаёт быть средним по стране.
     */
    fun promptBlock(): String {
        if (items.isEmpty()) return ""
        return buildString {
            append("Штатный рацион владельца — КБЖУ на 100 г с настоящих этикеток.\n")
            append("Если сказанное похоже на позицию отсюда, считай ПО ЭТИМ цифрам:\n")
            for (p in items) {
                append("- ").append(p.shortName)
                append(": ").append(fmt(p.kcal100)).append(" ккал")
                append(", Б").append(fmt(p.protein100))
                append(" Ж").append(fmt(p.fat100))
                append(" У").append(fmt(p.carbs100))
                append(" на 100 г")
                if (p.defaultGrams > 0) append("; обычная порция ").append(p.defaultGrams).append(" г")
                append('\n')
            }
        }
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", v)

    /** Голосовые имена продуктов для смещения распознавателя. */
    fun biasing(): List<String> = items.map { it.shortName }.distinct()
}
