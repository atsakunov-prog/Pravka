package ru.zf.pravka.core

import org.json.JSONObject

/**
 * Правила, которыми телефон сам чинит тренировки в intervals.icu.
 *
 * BJJ (01.10.2026). Профиль часов «Mixed Martial Arts» (Forerunner 965 →
 * Garmin Connect) intervals кладёт как Walk: все пять BJJ с 9 сентября пришли
 * ходьбой, владелец правил их на Other руками. Владелец: «надо сделать так,
 * чтобы автоматом менялось на Other и добавить в название: BJJ: борьба».
 * Ходьба портит не только intervals: в ленте такая тренировка ложилась
 * «Передвижение: пешком», а не спортом, и автопилот пришивал к ней дорогу от
 * двери, как к прогулке.
 *
 * Тип меняется только через API (в скриптах intervals смена типа не
 * работает — она требует пересчёта анализа). Узнаём тренировку по имени,
 * которое даёт профиль часов, и по нашему же имени: если intervals принял
 * имя, а тип не сменил, правило должно узнать её и во второй раз.
 *
 * Файл без Android — под тестом `IcuFixesTest`.
 */
object IcuFixes {

    /** Имя, которое ставит профиль часов. */
    const val GARMIN_MMA = "Mixed Martial Arts"
    const val BJJ_TYPE = "Other"
    const val BJJ_NAME = "BJJ: борьба"

    /** В ленте BJJ — спорт; «Other» и так ложится сюда, но старые записи лежат ходьбой. */
    const val BJJ_CATEGORY = "Спорт: прочее"

    data class Fix(val type: String, val name: String)

    /** BJJ ли это: имя профиля часов или уже наше. Имя, которое владелец дал сам, — не наше дело. */
    fun isBjj(name: String): Boolean {
        val n = name.trim()
        return n.equals(GARMIN_MMA, ignoreCase = true) || n == BJJ_NAME
    }

    /** Что поменять у активности; null — менять нечего. */
    fun fix(type: String, name: String): Fix? {
        if (!isBjj(name)) return null
        if (type == BJJ_TYPE && name.trim() == BJJ_NAME) return null
        return Fix(BJJ_TYPE, BJJ_NAME)
    }

    /** Частичный PUT: только тип и имя, остальное в активности — как было. */
    fun payload(f: Fix): JSONObject = JSONObject().put("type", f.type).put("name", f.name)

    /**
     * Принял ли intervals тип. Ответ PUT — сама активность; тип в нём не тот —
     * поле молча не записалось, и повторять каждые полчаса бессмысленно.
     * Ответ без типа (или не JSON) — верим коду 2xx.
     */
    fun accepted(responseBody: String, f: Fix): Boolean {
        val o = runCatching { JSONObject(responseBody) }.getOrNull() ?: return true
        val t = o.optString("type")
        return t.isEmpty() || t == f.type
    }

    /**
     * Категория записи ленты, которую свип положил по прежнему типу: ходьба и
     * любое «Передвижение» — спорт; категорию, поставленную иначе, не трогаем.
     */
    fun ribbonCategory(old: String): String =
        if (old.isBlank() || old.startsWith("Передвижение")) BJJ_CATEGORY else old
}
