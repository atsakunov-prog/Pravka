package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Тень второй модели (16.09.2026) — чистая политика без Android: окно и
 * потолок диктовок, слепая раздача сторон A/B, разбор ответа судьи, подсчёт
 * и текст сводки. Владелец: «может, ночью первой пропустим большой кусок
 * диктовок через Opus? А дальше уже дневной. А разбирает Сонет против Опуса
 * пускай Fable 5.1 high». Движок, который ходит в сеть и в сторы, — ShadowRun.
 */
object ShadowPolicy {
    const val KIND = "shadow"
    const val STAGE_CLEAN = "shadow_clean"
    const val STAGE_JUDGE = "shadow_judge"

    /** Первая ночь — большой кусок: месяц, но не больше двухсот диктовок. */
    const val FIRST_WINDOW_MS = 30 * 86_400_000L
    const val FIRST_CAP = 200
    /** Дальше — с конца прошлого прогона, но не глубже недели и не больше 120. */
    const val CATCH_UP_MS = 7 * 86_400_000L
    const val DAILY_CAP = 120
    /** Один запрос судье — не больше стольких знаков пар: длинные тексты владельца в один запрос не лезут. */
    const val JUDGE_CHUNK_CHARS = 60_000
    /** Сколько примеров хранить в карточке и сколько знаков каждого текста. */
    const val EXAMPLES = 24
    const val TEXT_CAP = 700

    private val dayFmt = SimpleDateFormat("dd.MM", Locale.US)

    data class Window(val fromMs: Long, val toMs: Long, val cap: Int, val first: Boolean)

    /**
     * Окно тени. Большой кусок ([big]) — месяц с потолком 200: только для самого
     * первого запуска, когда прогонов тени ещё не было, или по кнопке «за
     * месяц». Всё остальное — сутки: с конца последнего удачного прогона, не
     * глубже недели (владелец, 17.09: «потом он делает не 200, а только за
     * 1 день» — застрявший первый батч не должен повторяться сам).
     */
    fun window(nowMs: Long, lastDoneTo: Long?, big: Boolean): Window =
        if (big) Window(nowMs - FIRST_WINDOW_MS, nowMs, FIRST_CAP, first = true)
        else Window(lastDoneTo?.coerceAtLeast(nowMs - CATCH_UP_MS) ?: (nowMs - 86_400_000L), nowMs, DAILY_CAP, first = false)

    /** Одна диктовка в сравнении. */
    data class Take(
        val id: String,
        val tsMs: Long,
        /** Надиктовано (до словаря), как в журнале. */
        val input: String,
        /** После HARD-замен словаря — именно это уходит модели. */
        val prepared: String,
        /** Что сделала дневная модель (из журнала). */
        val day: String,
        val dayCostUsd: Double,
        /** Сторона A — вторая модель, а не дневная: судья не знает, где чья. */
        val flip: Boolean,
        /** Шла директива прозы — вторая модель получает её так же. */
        val prose: Boolean = false,
        /** Как поправил владелец руками (эталон для измерения промпта); пусто — правки не было. */
        val owner: String = "",
        val shadow: String = "",
        val failure: String = "",
        /** «same» — совпало (без судьи); название модели — кто лучше; both_bad; unjudged. */
        val verdict: String = "",
        val why: String = "",
        /** Изъяны проигравшего по словарю судьи (lost, grammar, punctuation…). */
        val flaws: List<String> = emptyList(),
    )

    /** Сторона второй модели — детерминированно от id, чтобы половина пар шла в обратном порядке. */
    fun flip(id: String): Boolean {
        var h = 17
        for (ch in id) h = h * 31 + ch.code
        return (h and 1) == 1
    }

    /** Одинаково ли слово в слово: пробелы и регистр не считаются. */
    fun same(a: String, b: String): Boolean = norm(a) == norm(b)

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** Пара для судьи: надиктовано и две чистки под слепыми буквами. */
    fun renderTake(t: Take): String {
        val a = if (t.flip) t.shadow else t.day
        val b = if (t.flip) t.day else t.shadow
        return "<пара id=\"${t.id}\">\n<d>${t.prepared}</d>\n<a>$a</a>\n<b>$b</b>\n</пара>"
    }

    /** Режет список (id, текст) на запросы не длиннее [maxChars]; одна пара всегда влезает целиком. */
    fun <T> chunks(items: List<T>, maxChars: Int, size: (T) -> Int): List<List<T>> {
        val out = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        var used = 0
        for (it in items) {
            val n = size(it)
            if (cur.isNotEmpty() && used + n > maxChars) {
                out.add(cur); cur = ArrayList(); used = 0
            }
            cur.add(it); used += n
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    data class Verdict(val better: String, val why: String, val flaws: List<String>)
    data class Judge(val summary: String, val verdicts: Map<String, Verdict>)

    /** Ответ судьи: {"summary": …, "verdicts": [{"id", "better": "A|B|same|both_bad", "why", "loser_flaws": […]}]}. */
    fun parseJudge(raw: String): Judge {
        val o = NightReviewPolicy.jsonObject(raw)
        val arr = o.optJSONArray("verdicts") ?: JSONArray()
        val map = LinkedHashMap<String, Verdict>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.optString("id").trim()
            if (id.isEmpty()) continue
            val flaws = ArrayList<String>()
            v.optJSONArray("loser_flaws")?.let { f -> for (j in 0 until f.length()) f.optString(j).trim().lowercase().takeIf { it.isNotEmpty() }?.let(flaws::add) }
            map[id] = Verdict(v.optString("better").trim(), v.optString("why").trim(), flaws)
        }
        return Judge(o.optString("summary").trim(), map)
    }

    /** Слепая буква → кто это был: название модели, same, both_bad; непонятное — unjudged. */
    fun resolve(better: String, flip: Boolean, dayLabel: String, shadowLabel: String): String =
        when (better.trim().uppercase()) {
            "A" -> if (flip) shadowLabel else dayLabel
            "B" -> if (flip) dayLabel else shadowLabel
            "SAME" -> "same"
            "BOTH_BAD" -> "both_bad"
            else -> "unjudged"
        }

    private val flawLabels = mapOf(
        "lost" to "потеря слов и смысла",
        "negation" to "потеряно «не»",
        "invented" to "выдумано",
        "grammar" to "падежи, род, лицо",
        "punctuation" to "пунктуация и членение",
        "names" to "имена и ослышки",
        "extra" to "лишнее в ответе",
        "style" to "переписано или сокращено",
    )

    fun flawLabel(tag: String): String = flawLabels[tag] ?: tag

    data class Tally(
        val total: Int,
        val same: Int,
        val shadowBetter: Int,
        val dayBetter: Int,
        val tie: Int,
        val bothBad: Int,
        val failed: Int,
        val unjudged: Int,
        /** Изъяны дневной модели там, где она проиграла; и второй — где проиграла она. */
        val dayFlaws: Map<String, Int>,
        val shadowFlaws: Map<String, Int>,
    ) {
        val judged: Int get() = shadowBetter + dayBetter + tie + bothBad
    }

    fun tally(takes: List<Take>, dayLabel: String, shadowLabel: String): Tally {
        val dayFlaws = LinkedHashMap<String, Int>()
        val shadowFlaws = LinkedHashMap<String, Int>()
        var same = 0; var sb = 0; var db = 0; var tie = 0; var bad = 0; var failed = 0; var un = 0
        for (t in takes) {
            when {
                t.failure.isNotBlank() -> failed++
                t.verdict == "same" -> same++
                t.verdict == shadowLabel -> { sb++; for (f in t.flaws) dayFlaws[f] = (dayFlaws[f] ?: 0) + 1 }
                t.verdict == dayLabel -> { db++; for (f in t.flaws) shadowFlaws[f] = (shadowFlaws[f] ?: 0) + 1 }
                t.verdict == "tie" -> tie++
                t.verdict == "both_bad" -> bad++
                else -> un++
            }
        }
        return Tally(takes.size, same, sb, db, tie, bad, failed, un, sortDesc(dayFlaws), sortDesc(shadowFlaws))
    }

    private fun sortDesc(m: Map<String, Int>): Map<String, Int> =
        m.entries.sortedByDescending { it.value }.associate { it.key to it.value }

    /** Судья сказал «same» по паре, которая различалась текстом, — считаем равноценной, а не совпавшей. */
    fun judgedVerdict(resolved: String): String = if (resolved == "same") "tie" else resolved

    /**
     * Сводка ночи по-русски: счёт, из-за чего кто проигрывал, деньги. Числа
     * считает приложение, судья только сравнивал пары.
     */
    fun summary(
        t: Tally,
        fromMs: Long,
        toMs: Long,
        dayLabel: String,
        shadowLabel: String,
        shadowEffort: String,
        judgeLabel: String,
        dayCostUsd: Double,
        shadowCostUsd: Double,
        judgeNotes: List<String>,
        first: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append("Тень за ${dayFmt.format(Date(fromMs))}–${dayFmt.format(Date(toMs))}")
        if (first) sb.append(" (первая ночь, большой кусок)")
        sb.append(": ${t.total} диктовок, вторая модель — $shadowLabel")
        if (shadowEffort.isNotBlank()) sb.append(" ($shadowEffort)")
        sb.append(".\n")
        sb.append("Совпало слово в слово: ${t.same}.")
        if (t.judged > 0) {
            sb.append(" Судья ($judgeLabel) по ${t.judged} различающимся: лучше $shadowLabel — ${t.shadowBetter}, лучше $dayLabel — ${t.dayBetter}, равноценно — ${t.tie}")
            if (t.bothBad > 0) sb.append(", оба плохо — ${t.bothBad}")
            sb.append('.')
        }
        if (t.failed > 0) sb.append(" Сбоев второй модели: ${t.failed}.")
        if (t.unjudged > 0) sb.append(" Без вердикта: ${t.unjudged}.")
        fun flaws(label: String, m: Map<String, Int>) {
            if (m.isEmpty()) return
            sb.append("\nПроигрывал $label из-за: ")
            sb.append(m.entries.take(5).joinToString(", ") { "${flawLabel(it.key)} ${it.value}" }).append('.')
        }
        flaws(dayLabel, t.dayFlaws)
        flaws(shadowLabel, t.shadowFlaws)
        if (dayCostUsd > 0 || shadowCostUsd > 0) {
            sb.append("\nДеньги: $dayLabel за эти диктовки — $").append(money(dayCostUsd))
            sb.append("; $shadowLabel за те же — $").append(money(shadowCostUsd))
            if (dayCostUsd > 0 && shadowCostUsd > 0) {
                sb.append(" (в ${"%.1f".format(Locale.US, shadowCostUsd / dayCostUsd)} раза ${if (shadowCostUsd >= dayCostUsd) "дороже" else "дешевле"})")
            }
            sb.append('.')
        }
        val notes = judgeNotes.filter { it.isNotBlank() }
        if (notes.isNotEmpty()) sb.append("\n\nСудья: ").append(notes.joinToString(" "))
        return sb.toString()
    }

    private fun money(v: Double) = "%.2f".format(Locale.US, v)

    /** Примеры для карточки: попеременно победы второй и дневной, потом «оба плохо». */
    fun examples(takes: List<Take>, dayLabel: String, shadowLabel: String, n: Int = EXAMPLES): List<Take> {
        val a = takes.filter { it.verdict == shadowLabel }
        val b = takes.filter { it.verdict == dayLabel }
        val c = takes.filter { it.verdict == "both_bad" }
        val out = ArrayList<Take>()
        var i = 0
        while (out.size < n && (i < a.size || i < b.size)) {
            if (i < a.size) out.add(a[i])
            if (out.size < n && i < b.size) out.add(b[i])
            i++
        }
        for (t in c) { if (out.size >= n) break; out.add(t) }
        return out
    }

    // ---- хранение в evidence прогона ----

    fun takesToJson(takes: List<Take>): String = JSONArray().apply {
        for (t in takes) put(
            JSONObject().apply {
                put("id", t.id); put("ts", t.tsMs); put("in", t.input); put("prep", t.prepared)
                put("day", t.day); put("cost", t.dayCostUsd); put("flip", t.flip)
                if (t.prose) put("prose", true)
                if (t.owner.isNotBlank()) put("own", t.owner)
                if (t.shadow.isNotBlank()) put("sh", t.shadow)
                if (t.failure.isNotBlank()) put("fail", t.failure)
                if (t.verdict.isNotBlank()) put("v", t.verdict)
                if (t.why.isNotBlank()) put("why", t.why)
                if (t.flaws.isNotEmpty()) put("flaws", JSONArray(t.flaws))
            }
        )
    }.toString()

    fun takesFromJson(raw: String?): List<Take> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Take>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val flaws = ArrayList<String>()
            o.optJSONArray("flaws")?.let { f -> for (j in 0 until f.length()) flaws.add(f.optString(j)) }
            out.add(
                Take(
                    id = o.optString("id"), tsMs = o.optLong("ts"), input = o.optString("in"),
                    prepared = o.optString("prep"), day = o.optString("day"), dayCostUsd = o.optDouble("cost", 0.0),
                    flip = o.optBoolean("flip"), prose = o.optBoolean("prose"), owner = o.optString("own"),
                    shadow = o.optString("sh"), failure = o.optString("fail"),
                    verdict = o.optString("v"), why = o.optString("why"), flaws = flaws,
                )
            )
        }
        return out
    }
}
