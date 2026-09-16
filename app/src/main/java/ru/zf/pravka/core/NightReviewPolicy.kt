package ru.zf.pravka.core

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.NightReviewStore.Change

/**
 * Ночной разбор: расписание, разбор ответов модели и правило «применять
 * само или предлагать». Без Android — всё это проверяет JVM-тест, а движок
 * (`NightReview`) только ходит в сеть и в сторы.
 */
object NightReviewPolicy {
    const val DAILY = "daily"
    const val WEEKLY = "weekly"

    /** Сколько изменений один прогон применяет сам — остальное предложениями. */
    const val AUTO_CAP = 20

    /** Виды, которые вообще можно применить (заметки — нет, промпт не трогаем). */
    val AUTO_KINDS = setOf("dict_add", "dict_disable", "dict_mode", "rule_disable", "rule_enable")

    /**
     * Что пора запускать. Дневной — раз в сутки, начиная с часа запуска;
     * недельный — по пятницам: владелец («дневные утром, а недельные в
     * пятницу») читает его утром пятницы, значит идёт он в ночь на пятницу.
     * Ручные запуски тоже считаются: разобрали сутки днём — ночью не повторяем.
     */
    fun dueKinds(nowMs: Long, hour: Int, lastDailyStart: Long, lastWeeklyStart: Long): List<String> {
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        if (now.get(Calendar.HOUR_OF_DAY) < hour) return emptyList()
        val out = mutableListOf<String>()
        if (!sameDay(lastDailyStart, nowMs)) out += DAILY
        if (now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY && !sameDay(lastWeeklyStart, nowMs)) out += WEEKLY
        return out
    }

    /** Раз в сутки после часа запуска — тем же правилом живёт тень второй модели. */
    fun dueDaily(nowMs: Long, hour: Int, lastStart: Long): Boolean =
        DAILY in dueKinds(nowMs, hour, lastStart, nowMs)

    /** Причина сбоя разбора ответа — коротко: JSONException тащит в message весь ответ модели. */
    fun shortReason(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ")
        return if (m.length > 160) m.take(160) + "…" else m
    }

    fun sameDay(a: Long, b: Long): Boolean {
        if (a <= 0L || b <= 0L) return false
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** Окно журналов: сутки или неделя назад от момента запуска. */
    fun window(kind: String, nowMs: Long): Pair<Long, Long> =
        (nowMs - (if (kind == WEEKLY) 7 else 1) * 86_400_000L) to nowMs

    data class Analysis(val summary: String, val changes: List<Change>)

    /** Ответ первого прохода → изменения с id вида «dict-3». Неизвестные виды отбрасываются. */
    fun parseAnalysis(raw: String, prefix: String): Analysis {
        val o = jsonObject(raw)
        val arr = o.optJSONArray("changes") ?: JSONArray()
        val out = mutableListOf<Change>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            var kind = c.optString("kind").trim().lowercase()
            // Удалять записи разбору нельзя — только выключать (обратимо).
            if (kind == "dict_delete" || kind == "dict_remove") kind = "dict_disable"
            if (kind !in AUTO_KINDS && kind != "note") continue
            val from = c.optString("from").trim()
            if (kind.startsWith("dict") && from.isEmpty()) continue
            val confidence = if (c.optString("confidence").trim().lowercase() == "high") "high" else "low"
            out += Change(
                id = "$prefix-${out.size + 1}",
                kind = kind,
                mode = c.optString("mode").trim().uppercase(),
                toMode = c.optString("to_mode").trim().uppercase(),
                from = from,
                to = c.optString("to").trim(),
                note = c.optString("note").trim(),
                ruleId = c.optLong("rule_id"),
                why = c.optString("why").trim(),
                confidence = confidence,
                status = if (kind == "note") "note" else "proposed",
            )
        }
        return Analysis(o.optString("summary").trim(), out)
    }

    /** Ответ проверки → id → (вердикт, почему). */
    fun parseVerdicts(raw: String): Map<String, Pair<String, String>> {
        val o = jsonObject(raw)
        val arr = o.optJSONArray("verdicts") ?: JSONArray()
        val out = LinkedHashMap<String, Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val verdict = when (v.optString("verdict").trim().lowercase()) {
                "approve", "approved" -> "approve"
                "reject", "rejected" -> "reject"
                else -> "unsure"
            }
            out[v.optString("id").trim()] = verdict to v.optString("why").trim()
        }
        return out
    }

    data class Audit(val assessment: String, val holds: Map<String, String>)

    /** Ответ согласования: оценка целиком и что придержать (id → почему). */
    fun parseAudit(raw: String): Audit {
        val o = jsonObject(raw)
        val arr = o.optJSONArray("holds") ?: JSONArray()
        val holds = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val id = h.optString("id").trim()
            if (id.isNotEmpty()) holds[id] = h.optString("why").trim()
        }
        return Audit(o.optString("assessment").trim(), holds)
    }

    data class ReplyPlan(val actions: List<Pair<String, String>>, val answer: String)

    /** Ответ владельца, переведённый моделью в действия по id. */
    fun parseReply(raw: String): ReplyPlan {
        val o = jsonObject(raw)
        val arr = o.optJSONArray("actions") ?: JSONArray()
        val actions = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val action = a.optString("action").trim().lowercase()
            if (action in setOf("revert", "apply", "reject")) actions += a.optString("id").trim() to action
        }
        return ReplyPlan(actions, o.optString("answer").trim())
    }

    /**
     * Применять само: уверенность high, вердикт проверки approve и вид из
     * списка. Плюс страховка, которой не доверили ни одной модели: HARD на
     * короткое кириллическое слово (до четырёх букв — «губ», «поле», «прод»)
     * сам не ложится никогда, только предложением.
     */
    fun autoApply(c: Change): Boolean {
        if (c.isNote || c.confidence != "high" || c.verdict != "approve" || c.kind !in AUTO_KINDS) return false
        if (c.kind == "dict_add" && c.mode == "HARD" && shortCyrillicWord(c.from)) return false
        return true
    }

    fun shortCyrillicWord(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && ' ' !in t && t.length <= 4 && t.all { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
    }

    /** Первый {…последний} из ответа: модель иногда обрамляет JSON словами. */
    fun jsonObject(raw: String): JSONObject {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        require(s >= 0 && e > s) { "В ответе модели нет JSON" }
        return JSONObject(raw.substring(s, e + 1))
    }

    fun headline(applied: Int, proposed: Int, rejected: Int, notes: Int): String = buildString {
        append("Применено ").append(applied)
        append(", предложено ").append(proposed)
        if (rejected > 0) append(", отклонено проверкой ").append(rejected)
        if (notes > 0) append(", заметок ").append(notes)
        append('.')
    }
}
