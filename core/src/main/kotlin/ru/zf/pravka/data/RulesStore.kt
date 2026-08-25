package ru.zf.pravka.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Learned rules: short imperative instructions the owner APPROVED after a
// "Обучить" round (his edits vs our output, analyzed by Opus). Enabled rules
// ride into every CLEAN request as a permanent block in the uncached slot -
// this is how the model "remembers" the owner's systematic preferences.
class RulesStore(private val dir: File) {

    data class Rule(
        val id: Long,
        val text: String,
        val enabled: Boolean = true,
        val createdTs: Long = 0L,
        // Те же три поля, что у записи словаря: устойчивый ключ, время правки
        // и надгробие - без них правило, удалённое на телефоне, вернулось бы
        // с воркстанции при следующей синхронизации.
        val uid: String = "",
        val updatedAt: Long = 0L,
        val deleted: Boolean = false,
        // A mini before/after from the owner's actual edit: few-shot for the
        // model, and lets the owner judge what the rule really does.
        val exampleBefore: String = "",
        val exampleAfter: String = "",
    )

    private val mutex = Mutex()
    private var loaded = false
    private val rules = mutableListOf<Rule>()

    private fun file() = File(dir, "pravka-rules.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        // A corrupt file is quarantined, never silently replaced by the next
        // persist: these rules are the learning loop's whole memory.
        val parsed = StoreFiles.readOrQuarantine(file()) { text ->
            val array = JSONArray(text)
            val out = mutableListOf<Rule>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val t = o.optString("text").trim()
                if (t.isEmpty()) continue
                out.add(
                    Rule(
                        id = o.optLong("id"),
                        text = t,
                        enabled = o.optBoolean("enabled", true),
                        createdTs = o.optLong("created"),
                        uid = o.optString("uid", ""),
                        updatedAt = o.optLong("updatedAt", o.optLong("created")),
                        deleted = o.optBoolean("deleted", false),
                        exampleBefore = o.optString("before"),
                        exampleAfter = o.optString("after"),
                    )
                )
            }
            out
        }
        if (parsed != null) rules.addAll(parsed)

        // Устойчивые ключи для правил, живших до синхронизации, и уборка
        // старых надгробий - один раз при загрузке.
        var touched = false
        for (i in rules.indices) {
            if (rules[i].uid.isBlank()) {
                rules[i] = rules[i].copy(uid = UUID.randomUUID().toString())
                touched = true
            }
        }
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        if (rules.removeAll { it.deleted && it.updatedAt < cutoff }) touched = true
        if (touched) persist()
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            rules.forEach { r ->
                array.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("text", r.text)
                        put("enabled", r.enabled)
                        put("created", r.createdTs)
                        put("uid", r.uid)
                        put("updatedAt", r.updatedAt)
                        if (r.deleted) put("deleted", true)
                        put("before", r.exampleBefore)
                        put("after", r.exampleAfter)
                    }
                )
            }
            StoreFiles.writeAtomic(file(), array.toString())
        }
    }

    /** Живые правила: надгробия наружу не показываются. */
    suspend fun all(): List<Rule> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); rules.filter { !it.deleted } }
    }

    /** Всё, включая надгробия, - только для синхронизации. */
    suspend fun allForSync(): List<Rule> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); rules.toList() }
    }

    suspend fun add(text: String, exampleBefore: String = "", exampleAfter: String = "") =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val t = text.trim()
                if (t.isNotEmpty() && rules.none { !it.deleted && it.text.equals(t, ignoreCase = true) }) {
                    val id = (rules.maxOfOrNull { it.id } ?: 0L) + 1
                    val now = System.currentTimeMillis()
                    rules.add(
                        Rule(
                            id, t, enabled = true, createdTs = now,
                            uid = UUID.randomUUID().toString(), updatedAt = now,
                            exampleBefore = exampleBefore.take(160), exampleAfter = exampleAfter.take(160),
                        )
                    )
                    persist()
                }
            }
        }

    /** Wholesale swap after the owner confirmed an optimized set. */
    suspend fun replaceAll(newRules: List<Triple<String, String, String>>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            // Прежние правила не исчезают, а становятся надгробиями: иначе
            // второе устройство вернуло бы весь старый набор обратно.
            val now = System.currentTimeMillis()
            val buried = rules.filter { !it.deleted }
                .map { it.copy(deleted = true, updatedAt = now) }
            rules.clear()
            rules.addAll(buried)
            var id = (buried.maxOfOrNull { it.id } ?: 0L) + 1
            for ((text, before, after) in newRules) {
                val t = text.trim()
                if (t.isEmpty()) continue
                rules.add(Rule(id++, t, enabled = true, createdTs = now,
                    uid = UUID.randomUUID().toString(), updatedAt = now,
                    exampleBefore = before.take(160), exampleAfter = after.take(160)))
            }
            persist()
        }
    }

    suspend fun setEnabled(id: Long, on: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val i = rules.indexOfFirst { it.id == id }
            if (i >= 0) {
                rules[i] = rules[i].copy(enabled = on, updatedAt = System.currentTimeMillis())
                persist()
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val i = rules.indexOfFirst { it.id == id }
            if (i >= 0) {
                rules[i] = rules[i].copy(deleted = true, updatedAt = System.currentTimeMillis())
                persist()
            }
        }
    }

    /** Слияние с общей таблицей - по тем же правилам, что и словарь. */
    suspend fun mergeFromSync(incoming: List<Rule>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            var changed = 0
            for (remote in incoming) {
                if (remote.text.isBlank()) continue
                val index = rules.indexOfFirst {
                    (remote.uid.isNotBlank() && it.uid == remote.uid) ||
                        it.text.equals(remote.text, ignoreCase = true)
                }
                if (index < 0) {
                    if (remote.deleted) continue
                    val id = (rules.maxOfOrNull { it.id } ?: 0L) + 1
                    rules.add(remote.copy(id = id, uid = remote.uid.ifBlank { UUID.randomUUID().toString() }))
                    changed++
                    continue
                }
                val local = rules[index]
                if (remote.updatedAt <= local.updatedAt) continue
                rules[index] = remote.copy(id = local.id, uid = remote.uid.ifBlank { local.uid })
                changed++
            }
            if (changed > 0) persist()
            changed
        }
    }

    /**
     * The prompt block of enabled rules (empty string when none). Capped so a
     * long-lived collection can't crowd out the actual text.
     */
    suspend fun enabledBlock(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val active = rules.filter { it.enabled && !it.deleted }
            if (active.isEmpty()) return@withLock ""
            val sb = StringBuilder("Постоянные правила владельца (соблюдай):\n")
            var used = 0
            // Numbered (owner's request): the optimized core reads as a list -
            // "1., 2., 3. ..." - both here and on the Learning tab.
            for ((i, r) in active.withIndex()) {
                val cost = r.text.length + r.exampleBefore.length + r.exampleAfter.length
                if (used + cost > 2000) break
                sb.append(i + 1).append(". ").append(r.text).append('\n')
                if (r.exampleBefore.isNotBlank() && r.exampleAfter.isNotBlank()) {
                    sb.append("  Пример: «").append(r.exampleBefore).append("» → «")
                        .append(r.exampleAfter).append("»\n")
                }
                used += cost
            }
            sb.toString().trim()
        }
    }
}
