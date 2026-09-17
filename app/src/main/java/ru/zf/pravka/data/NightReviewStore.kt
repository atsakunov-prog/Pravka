package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Прогоны ночного разбора (16.09.2026): что отправили, что нашли, что
 * применили, что владелец ответил. Файл под StoreFiles, последние 40
 * прогонов. Это память автомата, который сам правит словарь: без неё
 * нечего было бы вернуть обратно — поэтому у каждого применённого изменения
 * хранится `undo`, а прогон переживает смерть процесса на любой стадии.
 */
class NightReviewStore(private val context: Context) {

    /** Одно изменение, предложенное разбором. */
    data class Change(
        val id: String,
        /**
         * dict_add · dict_disable · dict_mode · rule_disable · rule_enable · note;
         * shadow — пример из тени второй модели (ShadowRun): from — надиктовано,
         * to — дневная модель, note — вторая, mode/toMode — их названия,
         * verdict — кто лучше (название модели, same, both_bad), verdictWhy — почему.
         */
        val kind: String,
        val mode: String = "",
        val toMode: String = "",
        val from: String = "",
        val to: String = "",
        val note: String = "",
        val ruleId: Long = 0L,
        val why: String = "",
        /** high — применяется само после подтверждения проверкой; low — предложение. */
        val confidence: String = "low",
        /** approve · reject · unsure · пусто (проверка не дошла). */
        val verdict: String = "",
        val verdictWhy: String = "",
        /** proposed · applied · rejected · reverted · failed · skipped · dropped (низкая уверенность) · note */
        val status: String = "proposed",
        /** JSON обратного действия для применённого изменения. */
        val undo: String = "",
        val statusNote: String = "",
    ) {
        /** Не действие: заметка или пример тени — применять и возвращать нечего. */
        val isNote: Boolean get() = kind == "note" || kind == "shadow"
        fun title(): String = when (kind) {
            "dict_add" -> "$mode: $from${if (to.isNotBlank()) " → $to" else ""}"
            "dict_disable" -> "выключить $mode: $from${if (to.isNotBlank()) " → $to" else ""}"
            "dict_mode" -> "$from: $mode → $toMode"
            "rule_disable" -> "выключить правило $ruleId"
            "rule_enable" -> "включить правило $ruleId"
            "shadow" -> "пример тени"
            else -> "заметка"
        }
    }

    data class Reply(val at: Long, val text: String, val result: String)

    data class Run(
        val id: Long,
        /** daily · weekly · shadow (тень второй модели, core/ShadowRun.kt) */
        val kind: String,
        val startedAt: Long,
        val fromMs: Long,
        val toMs: Long,
        /** analysis · check · audit · done · failed; у тени — shadow_clean · shadow_judge */
        val stage: String,
        val manual: Boolean = false,
        val analysisBatchId: String = "",
        val checkBatchId: String = "",
        val auditBatchId: String = "",
        /** Пакет свидетельств по измерениям — тот же уходит проверке; после done стирается. */
        val evidence: Map<String, String> = emptyMap(),
        val summary: String = "",
        val error: String = "",
        val costUsd: Double = 0.0,
        /** Вход всех проходов и сколько из него пришло из кэша — видно, окупается ли system под кэшем. */
        val inputTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val finishedAt: Long = 0L,
        val lastPollAt: Long = 0L,
        /** Что ответил последний опрос статуса батча — словами, для строки «идёт…» (владелец: «тень, кажется, застряла»). */
        val progress: String = "",
        val changes: List<Change> = emptyList(),
        val replies: List<Reply> = emptyList(),
    ) {
        val active: Boolean get() = stage in ACTIVE_STAGES
        val isShadow: Boolean get() = kind == "shadow"
        fun applied() = changes.count { it.status == "applied" }
        fun proposed() = changes.count { it.status == "proposed" }
        fun rejected() = changes.count { it.status == "rejected" }
    }

    companion object {
        private const val FILE_NAME = "night-review.json"
        private const val KEEP = 40
        val ACTIVE_STAGES = setOf("analysis", "check", "audit", "shadow_clean", "shadow_judge")
    }

    private val mutex = Mutex()
    private var loaded = false
    private var runs = mutableListOf<Run>()
    private val _runsFlow = MutableStateFlow<List<Run>>(emptyList())
    val runsFlow: StateFlow<List<Run>> = _runsFlow

    private fun file() = File(context.filesDir, FILE_NAME)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runs = (StoreFiles.readOrQuarantine(file()) { text -> parse(JSONArray(text)) } ?: mutableListOf())
        _runsFlow.value = runs.toList()
    }

    suspend fun all(): List<Run> = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(); runs.toList() } }

    suspend fun get(id: Long): Run? = all().firstOrNull { it.id == id }

    /** Добавить или заменить прогон по id; хранится последние KEEP. */
    suspend fun save(run: Run) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val i = runs.indexOfFirst { it.id == run.id }
            if (i >= 0) runs[i] = run else runs.add(run)
            runs.sortBy { it.startedAt }
            while (runs.size > KEEP) runs.removeAt(0)
            StoreFiles.writeAtomic(file(), toJson(runs).toString())
            _runsFlow.value = runs.toList()
        }
    }

    private fun parse(array: JSONArray): MutableList<Run> {
        val out = mutableListOf<Run>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ev = mutableMapOf<String, String>()
            o.optJSONObject("evidence")?.let { e -> for (k in e.keys()) ev[k] = e.optString(k) }
            val changes = mutableListOf<Change>()
            o.optJSONArray("changes")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val c = arr.optJSONObject(j) ?: continue
                    changes.add(
                        Change(
                            id = c.optString("id"), kind = c.optString("kind"), mode = c.optString("mode"),
                            toMode = c.optString("toMode"), from = c.optString("from"), to = c.optString("to"),
                            note = c.optString("note"), ruleId = c.optLong("ruleId"), why = c.optString("why"),
                            confidence = c.optString("confidence", "low"), verdict = c.optString("verdict"),
                            verdictWhy = c.optString("verdictWhy"), status = c.optString("status", "proposed"),
                            undo = c.optString("undo"), statusNote = c.optString("statusNote"),
                        )
                    )
                }
            }
            val replies = mutableListOf<Reply>()
            o.optJSONArray("replies")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val r = arr.optJSONObject(j) ?: continue
                    replies.add(Reply(r.optLong("at"), r.optString("text"), r.optString("result")))
                }
            }
            out.add(
                Run(
                    id = o.optLong("id"), kind = o.optString("kind", "daily"), startedAt = o.optLong("startedAt"),
                    fromMs = o.optLong("fromMs"), toMs = o.optLong("toMs"), stage = o.optString("stage", "failed"),
                    manual = o.optBoolean("manual"), analysisBatchId = o.optString("analysisBatchId"),
                    checkBatchId = o.optString("checkBatchId"), auditBatchId = o.optString("auditBatchId"),
                    evidence = ev, summary = o.optString("summary"),
                    error = o.optString("error"), costUsd = o.optDouble("costUsd", 0.0),
                    inputTokens = o.optInt("inputTokens"), cacheReadTokens = o.optInt("cacheReadTokens"),
                    finishedAt = o.optLong("finishedAt"), lastPollAt = o.optLong("lastPollAt"),
                    progress = o.optString("progress"), changes = changes, replies = replies,
                )
            )
        }
        return out
    }

    private fun toJson(list: List<Run>): JSONArray = JSONArray().apply {
        for (r in list) put(
            JSONObject().apply {
                put("id", r.id); put("kind", r.kind); put("startedAt", r.startedAt)
                put("fromMs", r.fromMs); put("toMs", r.toMs); put("stage", r.stage); put("manual", r.manual)
                put("analysisBatchId", r.analysisBatchId); put("checkBatchId", r.checkBatchId)
                put("auditBatchId", r.auditBatchId)
                put("evidence", JSONObject().apply { for ((k, v) in r.evidence) put(k, v) })
                put("summary", r.summary); put("error", r.error); put("costUsd", r.costUsd)
                put("inputTokens", r.inputTokens); put("cacheReadTokens", r.cacheReadTokens)
                put("finishedAt", r.finishedAt); put("lastPollAt", r.lastPollAt); put("progress", r.progress)
                put(
                    "changes",
                    JSONArray().apply {
                        for (c in r.changes) put(
                            JSONObject().apply {
                                put("id", c.id); put("kind", c.kind); put("mode", c.mode); put("toMode", c.toMode)
                                put("from", c.from); put("to", c.to); put("note", c.note); put("ruleId", c.ruleId)
                                put("why", c.why); put("confidence", c.confidence); put("verdict", c.verdict)
                                put("verdictWhy", c.verdictWhy); put("status", c.status); put("undo", c.undo)
                                put("statusNote", c.statusNote)
                            }
                        )
                    }
                )
                put(
                    "replies",
                    JSONArray().apply {
                        for (p in r.replies) put(JSONObject().put("at", p.at).put("text", p.text).put("result", p.result))
                    }
                )
            }
        )
    }
}
