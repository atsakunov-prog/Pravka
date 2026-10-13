package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Рацион владельца (13.10.2026): его постоянные ПРОДУКТЫ с КБЖУ на 100 г и
 * БЛЮДА — наборы этих продуктов с граммами. Правда — запись Свода
 * `food.ration` на сервере Дел (`server/contract/svod.json`), заводское
 * семя — `assets/ration.json`; держит и отдаёт `data/RationBook.kt`.
 *
 * Почему продукты и блюда, а не приёмы с граммами, как было: рацион — снимок
 * базы Notion «Рацион», где граммы работали переключателем («у одного
 * граммы, у другого 0»), а «каша» была приёмом «1 Завтрак». Владелец говорит
 * блюдами — «моя каша», «индейка с кускусом», — и блюдо должно быть вещью со
 * своим именем, своими словами и своим составом, а не догадкой модели по
 * группе строк. Notion снят 12.10.2026, а с ним и этот вид.
 *
 * Каждому продукту и блюду — [Product.aliases] / [Dish.aliases]: как владелец
 * их называет голосом. Они едут модели в промпт (по ним «индейку с
 * кускусом» узнают, даже если услышано криво) и распознавателю подсказкой.
 *
 * Чистая логика: разбор JSON, блок промпта, разворот блюда в позиции — под
 * JVM-тестами (`RationTest`).
 */
object Ration {

    const val VERSION = 2

    /** Продукт. Все числа — на 100 г; [grams] — обычная порция владельца. */
    data class Product(
        val id: String,
        val name: String,
        val aliases: List<String> = emptyList(),
        val grams: Int = 0,
        val kcal100: Double = 0.0,
        val protein100: Double = 0.0,
        val fat100: Double = 0.0,
        val carbs100: Double = 0.0,
        /**
         * Насколько верить числам. С завода «точно»: рацион снят с его
         * этикеток. «примерно» — цифры прикинуты (индейка ВкусВилл, пока
         * этикетку не сняли): в дневнике позиция честно так и помечена.
         */
        val sure: String = "точно",
        /** Таблетка (креатин): калорий нет, позиция с `pill`. */
        val pill: Boolean = false,
        val note: String = "",
        /**
         * Витамины и элементы на 100 г, ключами `Micronutrients`. На
         * этикетках их почти нет — числа справочные, порядок величины.
         * Нужны затем, что тап по рациону пишет без модели, и без них
         * полоски витаминов после «моей каши» стояли бы пустыми.
         */
        val micro100: Map<String, Double> = emptyMap(),
    ) {
        /** Позиция дневника на [grams] граммов (0 — обычная порция). */
        fun item(grams: Int = 0): MealItem {
            if (pill) {
                return MealItem(name = name, grams = 0, kcal = 0, protein = 0, fat = 0, carbs = 0, sureness = sure, micro = micro100, pill = true)
            }
            val weight = if (grams > 0) grams else this.grams.coerceAtLeast(1)
            val k = weight / 100.0
            return MealItem(
                name = name,
                grams = weight,
                kcal = Math.round(kcal100 * k).toInt(),
                protein = Math.round(protein100 * k).toInt(),
                fat = Math.round(fat100 * k).toInt(),
                carbs = Math.round(carbs100 * k).toInt(),
                sureness = sure,
                micro = Micronutrients.scaleBy(micro100, k),
            )
        }
    }

    /** Продукт блюда и его граммы (у таблетки — 0). */
    data class Portion(val product: String, val grams: Int)

    /**
     * Блюдо — набор продуктов. [kind] — обычный приём («завтрак»); пусто —
     * по времени суток. Слово владельца и время важнее: «моя каша» в 12:40 —
     * это его обед.
     */
    data class Dish(
        val id: String,
        val name: String,
        val kind: String = "",
        val aliases: List<String> = emptyList(),
        val items: List<Portion> = emptyList(),
        val note: String = "",
    )

    data class Book(
        val products: List<Product> = emptyList(),
        val dishes: List<Dish> = emptyList(),
        /** Что в записи не так (блюдо ссылается на продукт, которого нет). */
        val problems: List<String> = emptyList(),
    ) {
        val empty: Boolean get() = products.isEmpty()

        fun product(id: String): Product? = products.firstOrNull { it.id == id }

        /** Блюдо → позиции дневника с граммами блюда. */
        fun items(dish: Dish): List<MealItem> =
            dish.items.mapNotNull { p -> product(p.product)?.item(p.grams) }

        /** Калории блюда — для строки в рационе. */
        fun kcal(dish: Dish): Int = items(dish).sumOf { it.kcal }

        /** Слова для распознавателя: названия и как владелец их называет. */
        fun biasing(): List<String> =
            (products.flatMap { listOf(it.name) + it.aliases } + dishes.flatMap { listOf(it.name) + it.aliases })
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // ------------------------------------------------------------ разбор

    /**
     * JSON рациона (запись Свода или файл APK) → книга. Не тот вид (нет
     * `products`) — null: читающий тогда держится за запас. Блюдо со
     * ссылкой на неизвестный продукт теряет эту строку, а не всё блюдо, и
     * это записано в [Book.problems] — правка руками не должна молча
     * выключать «мою кашу».
     */
    fun parse(json: Any?): Book? {
        val o = when (json) {
            is JSONObject -> json
            is String -> runCatching { JSONObject(json) }.getOrNull()
            else -> null
        } ?: return null
        val pa = o.optJSONArray("products") ?: return null
        val problems = ArrayList<String>()
        val products = ArrayList<Product>()
        val seen = HashSet<String>()
        for (i in 0 until pa.length()) {
            val p = pa.optJSONObject(i) ?: continue
            val id = p.optString("id").trim()
            val name = p.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) {
                problems += "продукт №${i + 1} без id или названия"
                continue
            }
            if (!seen.add(id)) {
                problems += "продукт «$id» дважды — беру первый"
                continue
            }
            products += Product(
                id = id,
                name = name,
                aliases = strings(p.optJSONArray("aliases")),
                grams = p.optInt("grams", 0).coerceAtLeast(0),
                kcal100 = p.optDouble("kcal100", 0.0).orZero(),
                protein100 = p.optDouble("p100", 0.0).orZero(),
                fat100 = p.optDouble("f100", 0.0).orZero(),
                carbs100 = p.optDouble("c100", 0.0).orZero(),
                sure = p.optString("sure").trim().ifEmpty { "точно" },
                pill = p.optBoolean("pill", false),
                note = p.optString("note").trim(),
                micro100 = micro(p.optJSONObject("micro")),
            )
        }
        val dishes = ArrayList<Dish>()
        val da = o.optJSONArray("dishes") ?: JSONArray()
        for (i in 0 until da.length()) {
            val d = da.optJSONObject(i) ?: continue
            val id = d.optString("id").trim()
            val name = d.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) {
                problems += "блюдо №${i + 1} без id или названия"
                continue
            }
            val portions = ArrayList<Portion>()
            val ia = d.optJSONArray("items") ?: JSONArray()
            for (k in 0 until ia.length()) {
                val it = ia.optJSONObject(k) ?: continue
                val pid = it.optString("product").trim()
                if (pid !in seen) {
                    problems += "в «$name» продукт «$pid», которого нет в списке"
                    continue
                }
                portions += Portion(pid, it.optInt("grams", 0).coerceAtLeast(0))
            }
            if (portions.isEmpty()) {
                problems += "блюдо «$name» без продуктов"
                continue
            }
            dishes += Dish(
                id = id,
                name = name,
                kind = d.optString("kind").trim().lowercase().takeIf { it in MealItem.KINDS }.orEmpty(),
                aliases = strings(d.optJSONArray("aliases")),
                items = portions,
                note = d.optString("note").trim(),
            )
        }
        return Book(products, dishes, problems)
    }

    private fun Double.orZero(): Double = if (isNaN() || this < 0) 0.0 else this

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun micro(o: JSONObject?): Map<String, Double> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (key in o.keys()) {
            val n = Micronutrients.byId(key) ?: continue
            val v = o.optDouble(key, 0.0)
            if (v > 0) out[n.id] = v
        }
        return out
    }

    // ------------------------------------------------------------ промпт

    /**
     * Блок `{RATION}` промпта еды (и общего разбора «Т»). Правила блюд живут
     * ЗДЕСЬ, а не в тексте промпта: промпт — запись Свода, которую правит
     * недельный тюнер, а рацион меняется своим ходом — и вместе с ним должно
     * меняться то, что модель знает о «моей каше».
     */
    fun promptBlock(book: Book): String {
        if (book.empty) return ""
        return buildString {
            append("Рацион владельца — его постоянные продукты, КБЖУ на 100 г с его этикеток.\n")
            append("Сказанное похоже на продукт отсюда — по названию или по тому, как он его называет, ")
            append("даже если распознавание исказило слова, — считай ПО ЭТИМ цифрам и не оценивай заново; ")
            append("вес не назван — бери обычную порцию. sure — как у продукта.\n\n")
            append("Продукты:\n")
            for (p in book.products) {
                append("- ").append(p.name)
                if (p.aliases.isNotEmpty()) append(" (говорит: ").append(p.aliases.joinToString(", ")).append(")")
                if (p.pill) {
                    append(": добавка — pill: true, 0 ккал")
                } else {
                    append(": ").append(fmt(p.kcal100)).append(" ккал")
                    append(", Б").append(fmt(p.protein100))
                    append(" Ж").append(fmt(p.fat100))
                    append(" У").append(fmt(p.carbs100))
                    append(" на 100 г")
                    if (p.grams > 0) append("; обычная порция ").append(p.grams).append(" г")
                }
                if (p.sure != "точно") append("; цифры не с этикетки — sure «").append(p.sure).append("»")
                if (p.note.isNotBlank()) append(" — ").append(p.note.take(160))
                append('\n')
            }
            if (book.dishes.isNotEmpty()) {
                append("\nБлюда — его наборы. Названо блюдо (по имени или как он его называет) — это ВСЕ его ")
                append("продукты, каждый своей позицией, с граммами блюда и цифрами из списка выше. ")
                append("«Без X» — убери X; «с Y», «и ещё Y» — добавь Y; «вместо X — Y» — замени; ")
                append("названный вес продукта сильнее граммов блюда. Приём блюда — подсказка: ")
                append("его слово и время важнее.\n")
                for (d in book.dishes) {
                    append("- ").append(d.name)
                    val tags = ArrayList<String>()
                    if (d.kind.isNotEmpty()) tags += "обычно ${d.kind}"
                    if (d.aliases.isNotEmpty()) tags += "говорит: " + d.aliases.joinToString(", ")
                    if (tags.isNotEmpty()) append(" (").append(tags.joinToString("; ")).append(")")
                    append(": ")
                    append(d.items.mapNotNull { p ->
                        book.product(p.product)?.let { pr -> if (pr.pill) pr.name else "${pr.name} ${p.grams} г" }
                    }.joinToString(", "))
                    append(" — ").append(book.kcal(d)).append(" ккал")
                    if (d.note.isNotBlank()) append(". ").append(d.note.take(200))
                    append('\n')
                }
            }
        }.trimEnd()
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", v)
}
