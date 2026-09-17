package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.Settings

/**
 * Сравнение моделей (18.09.2026) — чистая политика без Android. Владелец, после
 * снятой тени: «вернём тень, но без этих 200 сообщений и без автостарта; я
 * буду сам запускать и смотреть; сравнивай всегда Сонет, Опус в обыкновенном
 * режиме и Опус на low; период будем выбирать». Три плеча чистят одни и те же
 * диктовки периода тем же промптом, что кнопка «П»; Fable судит слепо тройки
 * под перемешанными буквами; итоги, скорость и деньги считает приложение.
 * Движок — ModelCompare; сюда — раздача букв, разбор судьи, подсчёт, сводка.
 */
object ComparePolicy {
    const val KIND = "compare"
    const val STAGE_CLEAN = "compare_clean"
    const val STAGE_JUDGE = "compare_judge"

    /** Диктовок за один тик службы: три запроса на каждую, тик каждые пять минут. */
    const val CHUNK_ITEMS = 8
    const val EXAMPLES = 12
    const val TEXT_CAP = 700
    /** Оценка цены одной диктовки: три чистки плюс доля судьи — показывается до запуска. */
    const val USD_PER_ITEM = 0.10
    val CAPS = listOf(10, 20, 40)
    val DAYS = listOf(1, 3, 7)

    data class Arm(val key: String, val label: String, val model: String, val effort: String)

    /** Плечи фиксированы — владелец так и просил: «сравнивай всегда» эти три. */
    val ARMS = listOf(
        Arm("sonnet", "Сонет 5", Settings.MODEL_SONNET, ""),
        Arm("opus", "Опус 5", Settings.MODEL_OPUS, ""),
        Arm("opus_low", "Опус 5 low", Settings.MODEL_OPUS, "low"),
    )

    fun arm(key: String): Arm? = ARMS.firstOrNull { it.key == key }
    fun label(key: String): String = arm(key)?.label ?: key

    data class Result(val text: String = "", val costUsd: Double = 0.0, val ms: Long = 0L, val failure: String = "")

    data class Item(
        val id: String,
        val tsMs: Long,
        val input: String,
        val prose: Boolean = false,
        val results: Map<String, Result> = emptyMap(),
        /** Ключ плеча-победителя, «same» или пусто; и худшего. */
        val best: String = "",
        val worst: String = "",
        val why: String = "",
        val flaws: List<String> = emptyList(),
    ) {
        val pendingArms: List<Arm> get() = ARMS.filter { results[it.key] == null }
        val complete: Boolean get() = ARMS.all { results[it.key]?.text?.isNotBlank() == true }
        val failed: Boolean get() = ARMS.any { results[it.key]?.failure?.isNotBlank() == true }
        val allSame: Boolean get() = complete && ARMS.map { norm(results[it.key]!!.text) }.distinct().size == 1
        /** Судить есть что: все три ответили и хотя бы один отличается. */
        val judgeable: Boolean get() = complete && !allSame
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private val perms = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
    private val letters = listOf("A", "B", "C")

    /** Какое плечо стоит под какой буквой — детерминированно от id, все шесть порядков в ходу. */
    fun perm(id: String): List<Int> {
        var h = 7
        for (ch in id) h = h * 31 + ch.code
        return perms[Math.floorMod(h, perms.size)]
    }

    /** Тройка для судьи под слепыми буквами. */
    fun render(item: Item): String {
        val p = perm(item.id)
        val sb = StringBuilder("<тройка id=\"${item.id}\">\n<d>${item.input}</d>\n")
        for ((i, letter) in letters.withIndex()) {
            val arm = ARMS[p[i]]
            sb.append("<${letter.lowercase()}>").append(item.results[arm.key]?.text.orEmpty()).append("</${letter.lowercase()}>\n")
        }
        return sb.append("</тройка>").toString()
    }

    /** Буква → ключ плеча; «same» остаётся; непонятное — пусто. */
    fun resolve(letter: String, id: String): String {
        val l = letter.trim().uppercase()
        if (l == "SAME") return "same"
        val i = letters.indexOf(l)
        return if (i < 0) "" else ARMS[perm(id)[i]].key
    }

    data class Verdict(val best: String, val worst: String, val why: String, val flaws: List<String>)
    data class Judge(val summary: String, val verdicts: Map<String, Verdict>)

    /** {"summary", "verdicts":[{"id","best":"A|B|C|same","worst":"A|B|C|same","why","worst_flaws":[…]}]} */
    fun parseJudge(raw: String): Judge {
        val o = NightReviewPolicy.jsonObject(raw)
        val arr = o.optJSONArray("verdicts") ?: JSONArray()
        val map = LinkedHashMap<String, Verdict>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.optString("id").trim()
            if (id.isEmpty()) continue
            val flaws = ArrayList<String>()
            v.optJSONArray("worst_flaws")?.let { f -> for (j in 0 until f.length()) f.optString(j).trim().lowercase().takeIf { it.isNotEmpty() }?.let(flaws::add) }
            map[id] = Verdict(v.optString("best").trim(), v.optString("worst").trim(), v.optString("why").trim(), flaws)
        }
        return Judge(o.optString("summary").trim(), map)
    }

    data class Tally(
        val total: Int,
        val same: Int,
        val failed: Int,
        val unjudged: Int,
        val tie: Int,
        val best: Map<String, Int>,
        val worst: Map<String, Int>,
        val cost: Map<String, Double>,
        val avgMs: Map<String, Long>,
        val flaws: Map<String, Map<String, Int>>,
    )

    fun tally(items: List<Item>): Tally {
        val best = LinkedHashMap<String, Int>(); val worst = LinkedHashMap<String, Int>()
        val cost = LinkedHashMap<String, Double>(); val msSum = LinkedHashMap<String, Long>(); val msN = LinkedHashMap<String, Int>()
        val flaws = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (a in ARMS) { best[a.key] = 0; worst[a.key] = 0; cost[a.key] = 0.0; msSum[a.key] = 0L; msN[a.key] = 0; flaws[a.key] = LinkedHashMap() }
        var same = 0; var failed = 0; var unjudged = 0; var tie = 0
        for (it in items) {
            for (a in ARMS) {
                val r = it.results[a.key] ?: continue
                cost[a.key] = cost[a.key]!! + r.costUsd
                if (r.failure.isBlank() && r.ms > 0) { msSum[a.key] = msSum[a.key]!! + r.ms; msN[a.key] = msN[a.key]!! + 1 }
            }
            when {
                it.failed -> failed++
                it.allSame -> same++
                it.best == "same" -> tie++
                it.best.isBlank() -> unjudged++
                else -> {
                    best[it.best] = (best[it.best] ?: 0) + 1
                    if (it.worst.isNotBlank() && it.worst != "same") {
                        worst[it.worst] = (worst[it.worst] ?: 0) + 1
                        val m = flaws.getOrPut(it.worst) { LinkedHashMap() }
                        for (f in it.flaws) m[f] = (m[f] ?: 0) + 1
                    }
                }
            }
        }
        val avg = ARMS.associate { a -> a.key to (if (msN[a.key]!! > 0) msSum[a.key]!! / msN[a.key]!! else 0L) }
        return Tally(items.size, same, failed, unjudged, tie, best, worst, cost, avg, flaws)
    }

    private val day = SimpleDateFormat("dd.MM", Locale.US)
    private fun money(v: Double) = "%.2f".format(Locale.US, v)
    private fun sec(ms: Long) = "%.1f с".format(Locale.US, ms / 1000.0)

    /** Сводка по-русски: счёт, скорость, деньги, изъяны худших. Числа — приложения, судья только сравнивал. */
    fun summary(t: Tally, fromMs: Long, toMs: Long, judgeLabel: String, judgeCostUsd: Double, notes: List<String>): String {
        val sb = StringBuilder("Сравнение за ${day.format(Date(fromMs))}–${day.format(Date(toMs))}: ${t.total} диктовок, судья $judgeLabel.\n")
        sb.append("Все три совпали слово в слово: ${t.same}.")
        val judged = t.best.values.sum() + t.tie
        if (judged > 0) {
            sb.append(" Лучше всех: ").append(ARMS.sortedByDescending { t.best[it.key] ?: 0 }.joinToString(", ") { "${it.label} — ${t.best[it.key] ?: 0}" })
            if (t.tie > 0) sb.append(", равноценно — ${t.tie}")
            sb.append(". Хуже всех: ").append(ARMS.sortedByDescending { t.worst[it.key] ?: 0 }.joinToString(", ") { "${it.label} — ${t.worst[it.key] ?: 0}" }).append('.')
        }
        if (t.failed > 0) sb.append(" Сбоев: ${t.failed}.")
        if (t.unjudged > 0) sb.append(" Без вердикта: ${t.unjudged}.")
        sb.append("\nСкорость (среднее на диктовку): ").append(ARMS.joinToString(", ") { "${it.label} — ${sec(t.avgMs[it.key] ?: 0L)}" }).append('.')
        sb.append("\nДеньги за ${t.total} диктовок: ").append(ARMS.joinToString(", ") { "${it.label} — $${money(t.cost[it.key] ?: 0.0)}" })
        if (judgeCostUsd > 0) sb.append("; судья — $${money(judgeCostUsd)}")
        sb.append('.')
        for (a in ARMS) {
            val m = t.flaws[a.key].orEmpty()
            if (m.isNotEmpty()) sb.append("\nИзъяны ${a.label}, где он худший: ").append(m.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${ShadowPolicy.flawLabel(it.key)} ${it.value}" }).append('.')
        }
        val n = notes.filter { it.isNotBlank() }
        if (n.isNotEmpty()) sb.append("\n\nСудья: ").append(n.joinToString(" "))
        return sb.toString()
    }

    /** Примеры: где судья видел разницу, по кругу плеч-победителей. */
    fun examples(items: List<Item>, n: Int = EXAMPLES): List<Item> {
        val judged = items.filter { it.best.isNotBlank() && it.best != "same" }
        val byBest = ARMS.map { a -> judged.filter { it.best == a.key } }
        val out = ArrayList<Item>()
        var i = 0
        while (out.size < n && byBest.any { i < it.size }) {
            for (list in byBest) if (out.size < n && i < list.size) out.add(list[i])
            i++
        }
        return out
    }

    /** Тексты плеч из `Change.to` примера (JSON {название плеча: текст}) — в порядке плеч, пустые пропущены. */
    fun armTexts(toJson: String): List<Pair<String, String>> {
        val o = runCatching { JSONObject(toJson) }.getOrNull() ?: return emptyList()
        return ARMS.mapNotNull { a -> o.optString(a.label).takeIf { it.isNotBlank() }?.let { a.label to it } }
    }

    // ---- хранение ----

    fun toJson(items: List<Item>): String = JSONArray().apply {
        for (it in items) put(
            JSONObject().apply {
                put("id", it.id); put("ts", it.tsMs); put("in", it.input); if (it.prose) put("prose", true)
                put("res", JSONObject().apply {
                    for ((k, r) in it.results) put(k, JSONObject().put("t", r.text).put("c", r.costUsd).put("ms", r.ms).put("f", r.failure))
                })
                if (it.best.isNotBlank()) put("best", it.best)
                if (it.worst.isNotBlank()) put("worst", it.worst)
                if (it.why.isNotBlank()) put("why", it.why)
                if (it.flaws.isNotEmpty()) put("flaws", JSONArray(it.flaws))
            }
        )
    }.toString()

    fun fromJson(raw: String?): List<Item> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val res = LinkedHashMap<String, Result>()
            o.optJSONObject("res")?.let { r -> for (k in r.keys()) { val x = r.optJSONObject(k) ?: continue; res[k] = Result(x.optString("t"), x.optDouble("c", 0.0), x.optLong("ms"), x.optString("f")) } }
            val flaws = ArrayList<String>()
            o.optJSONArray("flaws")?.let { f -> for (j in 0 until f.length()) flaws.add(f.optString(j)) }
            out.add(Item(o.optString("id"), o.optLong("ts"), o.optString("in"), o.optBoolean("prose"), res, o.optString("best"), o.optString("worst"), o.optString("why"), flaws))
        }
        return out
    }
}
