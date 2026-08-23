package ru.zf.pravka.core

// Одна позиция в тарелке — то, что вернула модель (или база по штрихкоду) и
// что владелец правит руками. Живёт в core рядом с ParsedTask по той же
// причине: её отдаёт провайдер, а хранит стор, и своего типа у каждого из них
// быть не должно.
//
// Все числа — на ВСЮ порцию, а не на 100 г, и целые: дневник еды не то место,
// где нужна точность до десятой грамма белка.
data class MealItem(
    val name: String,
    val grams: Int,
    val kcal: Int,
    val protein: Int,
    val fat: Int,
    val carbs: Int,
    val fiber: Int = 0,
    // Насколько цифрам можно верить: «точно» (упаковка или штрихкод),
    // «примерно» (продукт понятен, порция на глаз), «наугад» (составное
    // незнакомое блюдо). Пустая строка = модель не сказала.
    val sureness: String = "",
) {
    /** Сходятся ли калории с макросами: 4/9/4 с допуском. */
    fun kcalFromMacros(): Int = protein * 4 + fat * 9 + carbs * 4

    /**
     * Пересчёт позиции на другой вес — этим правят порцию руками, не заставляя
     * модель считать заново. Ноль граммов в исходной позиции пересчитать
     * нельзя (не от чего), поэтому она возвращается как есть.
     */
    fun scaledTo(newGrams: Int): MealItem {
        if (grams <= 0 || newGrams <= 0) return this
        val k = newGrams.toDouble() / grams
        return copy(
            grams = newGrams,
            kcal = Math.round(kcal * k).toInt(),
            protein = Math.round(protein * k).toInt(),
            fat = Math.round(fat * k).toInt(),
            carbs = Math.round(carbs * k).toInt(),
            fiber = Math.round(fiber * k).toInt(),
        )
    }

    companion object {
        val KINDS = listOf("завтрак", "обед", "ужин", "перекус")

        /** Вид приёма по времени суток — когда владелец его не назвал. */
        fun kindByHour(hour: Int): String = when (hour) {
            in 0..3 -> "перекус"
            in 4..11 -> "завтрак"
            in 12..16 -> "обед"
            in 17..22 -> "ужин"
            else -> "перекус"
        }
    }
}
