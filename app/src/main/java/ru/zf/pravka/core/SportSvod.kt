package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.PlanStore

/**
 * Правила блока и справочник упражнений — записи Свода (13.10.2026,
 * docs/eda-server.md, часть 3; формат — `server/contract/svod.json`).
 *
 * С 12.10 правила были заморожены в `plan.json`, справочник — в
 * `exercises_live.json`: Notion сняли, и обновлять их было нечем. Теперь их
 * правит служба «Спорт» на компе, а телефон только читает — запись поверх
 * своих замороженных. Записи нет — телефон кладёт свои (засев, `author: seed`).
 *
 * Здесь — перевод правил в форму контракта и обратно, без Android: имена полей
 * у телефона и сервера разные (`runHrCeiling` ↔ `run_hr_ceiling`, `sourceText` ↔
 * `text`), и разойтись им нельзя — проверяют JVM-тесты.
 */
object SportSvod {

    const val RULES = "sport.rules"
    const val EXERCISES = "sport.exercises"

    /** Свои замороженные правила — в запись Свода (засев). Правил нет — null: класть нечего. */
    fun rulesValue(r: PlanStore.Rules, today: String): JSONObject? {
        if (!r.known) return null
        return JSONObject()
            .put("block_title", r.blockTitle)
            .put("run_hr_ceiling", r.runHrCeiling)
            .put("grey_zone_low", r.greyZoneLow)
            .put("grey_zone_high", r.greyZoneHigh)
            .put("cadence_min", r.cadenceMin)
            .put("runs_per_week_max", r.runsPerWeekMax)
            .put("hours_between_runs", r.hoursBetweenRuns)
            .put("ramp_needs_positive_tsb", r.rampNeedsPositiveTsb)
            .put("test_prep", r.testPrep)
            .put("cancel_order", r.cancelOrder)
            .put("knee", JSONObject().put("green", r.kneeGreen).put("yellow", r.kneeYellow).put("red", r.kneeRed))
            .put("week_plan", JSONArray().apply {
                for ((day, session) in r.weekPlan) put(JSONObject().put("day", day).put("session", session))
            })
            .put("extra", JSONArray().apply { r.extra.forEach { put(it) } })
            .put("text", r.sourceText)
            .put("updated", today)
    }

    /**
     * Запись Свода → правила. Нет правила — ноль или пусто, и светофор про него
     * молчит. `fetchedAt` — когда правила обновили (`updated`), а нет даты — когда
     * запись легла на сервер ([savedAt]): ноль значил бы «правил нет», а они есть.
     */
    fun rules(v: Any?, savedAt: String = ""): PlanStore.Rules? {
        val o = v as? JSONObject ?: return null
        val knee = o.optJSONObject("knee")
        val week = o.optJSONArray("week_plan")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val w = a.optJSONObject(i) ?: return@mapNotNull null
                val day = str(w, "day")
                val session = str(w, "session")
                if (day.isBlank() && session.isBlank()) null else day to session
            }
        }.orEmpty()
        val extra = o.optJSONArray("extra")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotBlank() && s != "null" } }
        }.orEmpty()
        val at = stamp(str(o, "updated")) ?: stamp(savedAt) ?: 1L
        return PlanStore.Rules(
            blockTitle = str(o, "block_title"),
            runHrCeiling = int(o, "run_hr_ceiling"),
            greyZoneLow = int(o, "grey_zone_low"),
            greyZoneHigh = int(o, "grey_zone_high"),
            cadenceMin = int(o, "cadence_min"),
            runsPerWeekMax = int(o, "runs_per_week_max"),
            hoursBetweenRuns = int(o, "hours_between_runs"),
            rampNeedsPositiveTsb = o.optBoolean("ramp_needs_positive_tsb", false),
            testPrep = str(o, "test_prep"),
            cancelOrder = str(o, "cancel_order"),
            kneeGreen = knee?.let { str(it, "green") }.orEmpty(),
            kneeYellow = knee?.let { str(it, "yellow") }.orEmpty(),
            kneeRed = knee?.let { str(it, "red") }.orEmpty(),
            weekPlan = week,
            extra = extra,
            sourceText = str(o, "text"),
            fetchedAt = at,
        )
    }

    /** «2026-10-13» или ISO с временем и поясом → мс; не дата — null. */
    private fun stamp(s: String): Long? {
        if (s.isBlank()) return null
        runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        return runCatching {
            java.time.LocalDate.parse(s.take(10)).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun int(o: JSONObject, key: String): Int =
        if (!o.has(key) || o.isNull(key)) 0 else o.optDouble(key, 0.0).takeIf { !it.isNaN() }?.let { Math.round(it).toInt() } ?: 0

    private fun str(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key).trim()
}
