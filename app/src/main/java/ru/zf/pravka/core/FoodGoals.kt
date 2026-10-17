package ru.zf.pravka.core

import org.json.JSONObject

/**
 * Цели еды и веса — запись Свода `food.goals` (13.10.2026, docs/eda-server.md,
 * часть 2; формат — `server/contract/svod.json`). Одна правда вместо ручек в
 * настройках телефона и «Целей октября»: правят телефон («Настройки → Тело»,
 * «Посчитать от веса», цель веса во «Спорте») и служба «Еда» на компе, читают
 * все. Ручки телефона остаются его кэшем: без сети действует последнее известное.
 *
 * Здесь — разбор записи и своё поверх неё, без Android: проверяют JVM-тесты.
 */
object FoodGoals {

    const val KEY = "food.goals"

    data class Goals(
        val kcal: Int,
        val protein: Int,
        val fat: Int,
        val carbs: Int,
        /** К какому весу идёт; null — цели веса в записи нет. */
        val weightKg: Double? = null,
        /** ГГГГ-ММ-ДД — к какому дню; пусто — без срока. */
        val weightBy: String = "",
        val fiber: Int? = null,
        val updated: String = "",
        val note: String = "",
    ) {
        /** Цель веса целыми килограммами — так её держат ручки телефона. */
        val weightRound: Int? get() = weightKg?.let { Math.round(it).toInt() }
    }

    private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** Запись Свода → цели; не читается или калорий нет — null (тогда действуют свои ручки). */
    fun parse(v: Any?): Goals? {
        val o = v as? JSONObject ?: return null
        val kcal = num(o, "kcal")?.let { Math.round(it).toInt() } ?: return null
        if (kcal <= 0) return null
        return Goals(
            kcal = kcal,
            protein = int(o, "protein_g"),
            fat = int(o, "fat_g"),
            carbs = int(o, "carbs_g"),
            weightKg = num(o, "weight_kg")?.takeIf { it > 0 },
            weightBy = str(o, "weight_by").takeIf { DAY.matches(it) }.orEmpty(),
            fiber = num(o, "fiber_g")?.let { Math.round(it).toInt() }?.takeIf { it > 0 },
            updated = str(o, "updated"),
            note = str(o, "note"),
        )
    }

    /**
     * Своё поверх записи: ручки телефона — калории, БЖУ и цель веса; что телефону
     * не по ручкам (клетчатка, к какому дню, заметка, чужие поля) — как было в
     * записи [base]. Цель веса, которая целыми кг та же, что в записи («82.5» против
     * ручки «83»), остаётся дробной из записи: ручка её не меняла.
     */
    fun value(base: JSONObject?, kcal: Int, protein: Int, fat: Int, carbs: Int, weightKg: Int?, today: String): JSONObject {
        val o = base?.let { JSONObject(it.toString()) } ?: JSONObject()
        o.put("kcal", kcal).put("protein_g", protein).put("fat_g", fat).put("carbs_g", carbs)
        if (weightKg != null && weightKg > 0) {
            val was = num(o, "weight_kg")
            if (was == null || Math.round(was).toInt() != weightKg) o.put("weight_kg", weightKg)
        }
        o.put("updated", today)
        return o
    }

    /** Те же ли цели у ручек телефона и в записи — по тому, что ручки умеют держать. */
    fun sameKnobs(g: Goals, kcal: Int, protein: Int, fat: Int, carbs: Int, weightKg: Int): Boolean =
        g.kcal == kcal && g.protein == protein && g.fat == fat && g.carbs == carbs &&
            (g.weightRound == null || g.weightRound == weightKg)

    private fun num(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    private fun int(o: JSONObject, key: String): Int = num(o, key)?.let { Math.round(it).toInt() }?.coerceAtLeast(0) ?: 0

    private fun str(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key).trim()
}
