package ru.zf.pravka.core

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run

/**
 * Ночной разбор: расписание, разбор ответов модели и правило «применять
 * само или предлагать». Без Android — всё это проверяет JVM-тест, а движок
 * (`NightReview`) только ходит в сеть и в сторы.
 */
object NightReviewPolicy {
    const val DAILY = "daily"
    const val WEEKLY = "weekly"

    /** Сколько изменений один прогон применяет сам — остальное предложениями. */
    const val AUTO_CAP = 30

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
                // У заметки поле note — совет разработчику (владелец, 17.09: «по
                // каждому внизу должен быть совет»), у записи словаря — условие HINT.
                note = (if (kind == "note") c.optString("advice") else c.optString("note")).trim(),
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
     * Применять само: уверенность high и вид из списка, если проверка не
     * отклонила (approve или unsure; reject — не применяется) и согласование
     * не придержало (hold ставится раньше этого вызова). Владелец (17.09.2026,
     * глядя на простыню предложений): «просто автоматом применял всё, что
     * нашла, за исключением низковероятных вещей» — раньше требовалось ещё и
     * явное approve, и половина высоких висела кнопками. Плюс страховка,
     * которой не доверили ни одной модели: HARD на короткое кириллическое
     * слово (до четырёх букв — «губ», «поле», «прод») сам не ложится никогда,
     * только предложением.
     */
    fun autoApply(c: Change): Boolean {
        if (c.isNote || c.confidence != "high" || c.kind !in AUTO_KINDS) return false
        if (c.verdict == "reject" || c.verdict == "hold") return false
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

    fun headline(applied: Int, proposed: Int, rejected: Int, notes: Int, dropped: Int = 0): String = buildString {
        append("Применено ").append(applied)
        append(", предложено ").append(proposed)
        if (rejected > 0) append(", отклонено проверкой ").append(rejected)
        if (dropped > 0) append(", отброшено низких ").append(dropped)
        if (notes > 0) append(", идей ").append(notes)
        append('.')
    }

    /**
     * Встречается ли слово в текстах окна как отдельное слово (без учёта
     * регистра). Дневной прогон правит существующую запись словаря, только
     * если её слово вообще было в этот день: иначе каждую ночь всплывали
     * записи годовой давности («Ви», «Мора» — владелец, 17.09: «взял очень
     * большой объём»). Полная ревизия словаря — дело недельного.
     */
    fun mentioned(word: String, texts: String): Boolean {
        val w = word.trim()
        if (w.isEmpty() || texts.isEmpty()) return false
        val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return re.containsMatchIn(texts)
    }

    /** Строка изменения для лога и экрана: вид и слово. */
    fun line(c: Change): String = when (c.kind) {
        "dict_add" -> "${c.mode}: ${c.from}${if (c.to.isNotBlank()) " → ${c.to}" else ""}${if (c.note.isNotBlank()) " (когда: ${c.note})" else ""}"
        else -> c.title()
    }

    /**
     * Лог для Claude Code (владелец, 17.09.2026: «эти советы вместе с
     * предложениями можно будет выгружать в виде лога и отправлять в Клод
     * код, чтобы он правил»). Markdown по прогонам: идеи с советами,
     * применённое, предложенное, что владелец вернул и отклонил, сводка.
     */
    fun exportLog(runs: List<Run>, dateOf: (Long) -> String, nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder("# Ночной разбор Правки — лог для Claude Code\n\n")
        sb.append("Выгружено ").append(dateOf(nowMs)).append(". Прогонов: ").append(runs.size).append(".\n")
        sb.append("Статусы: применено — легло в словарь само или по кнопке; предложено — ждёт решения владельца; ")
        sb.append("вернул / отклонил владелец — сигнал, что автомат ошибся.\n\n")
        for (run in runs.sortedByDescending { it.startedAt }) {
            val kind = when {
                run.isShadow -> "тень второй модели"
                run.kind == WEEKLY -> "неделя"
                else -> "сутки"
            }
            sb.append("## ").append(dateOf(run.startedAt)).append(" · ").append(kind)
            sb.append(" · период ").append(dateOf(run.fromMs)).append("–").append(dateOf(run.toMs)).append("\n")
            if (run.error.isNotBlank()) sb.append("Сбой: ").append(run.error).append("\n")
            if (run.isShadow) {
                sb.append(run.summary).append("\n\n")
                val ex = run.changes.filter { it.kind == "shadow" }
                if (ex.isNotEmpty()) {
                    sb.append("### Примеры\n")
                    for (c in ex) {
                        sb.append("- ").append(if (c.verdict == "tie" || c.verdict == "same") "равноценно" else if (c.verdict == "both_bad") "оба плохо" else "лучше ${c.verdict}")
                        sb.append(": ").append(c.verdictWhy)
                        if (c.why.isNotBlank()) sb.append(" [").append(c.why).append("]")
                        sb.append("\n  - надиктовано: ").append(c.from.replace("\n", " "))
                        sb.append("\n  - ").append(c.mode).append(": ").append(c.to.replace("\n", " "))
                        sb.append("\n  - ").append(c.toMode).append(": ").append(c.note.replace("\n", " ")).append("\n")
                    }
                }
                sb.append("\n")
                continue
            }
            val notes = run.changes.filter { it.kind == "note" }
            val applied = run.changes.filter { it.status == "applied" }
            val proposed = run.changes.filter { it.status == "proposed" }
            val reverted = run.changes.filter { it.status == "reverted" }
            val rejectedByOwner = run.changes.filter { it.status == "rejected" && "владелец" in it.statusNote }
            sb.append(headline(applied.size, proposed.size, run.changes.count { it.status == "rejected" }, notes.size, run.changes.count { it.status == "dropped" }))
            if (run.costUsd > 0) sb.append(" Стоил $").append("%.2f".format(java.util.Locale.US, run.costUsd)).append('.')
            sb.append("\n\n")
            fun section(title: String, list: List<Change>, render: (Change) -> String) {
                if (list.isEmpty()) return
                sb.append("### ").append(title).append("\n")
                for (c in list) sb.append("- ").append(render(c)).append("\n")
                sb.append("\n")
            }
            section("Идеи по приложению", notes) { c ->
                c.why + if (c.note.isNotBlank()) "\n  - Совет: ${c.note}" else ""
            }
            section("Применено", applied) { c -> line(c) + " — " + c.why }
            section("Предложено (владелец не решил)", proposed) { c ->
                line(c) + " — " + c.why + if (c.verdictWhy.isNotBlank()) " [${c.verdictWhy}]" else ""
            }
            section("Вернул владелец", reverted) { c -> line(c) + " — " + c.why }
            section("Отклонил владелец", rejectedByOwner) { c -> line(c) + " — " + c.why }
            val body = run.summary.split("\n\n").filterIndexed { i, p -> !(i == 0 && p.startsWith("Применено ")) }.joinToString("\n\n").trim()
            if (body.isNotBlank()) sb.append("### Сводка разбора\n").append(body).append("\n\n")
            for (r in run.replies) sb.append("Владелец: ").append(r.text).append("\nРазбор: ").append(r.result).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }
}
