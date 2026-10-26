package ru.zf.pravka.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Лента Засечки на сервере — проводка телефона (26.10.2026, `docs/server-truth.md`, контракт
 * `server/contract/dela-zas.json`). Владелец: «основная база всё равно на сервере… переносим всё».
 *
 * Три дела, и все — через уже живую дорогу Дел (очередь `DelaStore`, проход `DelaSync`), своей сети здесь нет:
 * 1. Передача. Синк Дел говорит, что сервер умеет ленту (флаг `zas`, поле `zas.mode = phone`), а лента ещё здесь —
 *    `ZasechkaStore.handOver` кладёт её целиком в очередь операцией `zas.handover` и переходит в режим server.
 * 2. Правки. В режиме server каждая правка ленты (`ZasechkaStore.sendLocked`) встаёт в очередь операцией `zas.op` —
 *    с номером записи, временем на телефоне и `was` (как запись выглядела): сервер применит то же самое своим движком.
 * 3. Окно. Ответ на операции и синк Дел приносят окно ленты сервера (двое суток и идущее) — оно ложится в ленту, но
 *    только когда в очереди не осталось своих операций ленты: иначе окно на миг спрятало бы то, что сервер ещё не
 *    получил. Свежесть окна — версия ленты `lrev`: синк просит окно, когда она сменилась (`DelaSync.zasRev`).
 *
 * Только владельцу: не владельцу поля `zas` в синке нет, и ничего не происходит. Засечки нет в профиле — тоже.
 */
internal class ZasechkaServer(
    private val dela: DelaStore,
    /** Отправить очередь вскоре (`DelaSync.poke`). */
    private val poke: () -> Unit,
    /** Имя этого телефона — то же, что у команд из веба (`ZasechkaWebCommands.device`). */
    private val device: () -> String,
    /** Засечка включена в профиле. */
    private val enabled: suspend () -> Boolean,
    private val log: (String) -> Unit,
) {

    private lateinit var store: ZasechkaStore

    /** Крючок очереди — с рождения ленты: правка в режиме server, сделанная до первого синка Дел, не потеряется. */
    fun attach(s: ZasechkaStore) {
        store = s
        s.remote = { kind, args ->
            enqueue(JSONObject().put("op", "zas.op").put("kind", kind).put("args", args)
                .put("at", System.currentTimeMillis()).put("device", device()))
        }
    }

    /** Синк Дел: версия ленты в запросе, окно и передача в ответе, ответы на отправленное. Зовётся после [attach]. */
    fun install(sync: DelaSync) {
        sync.zasRev = { if (store.serverMode) store.serverRev else null }
        sync.listen { resp -> onSync(resp) }
        sync.onAck = { ops, results -> onAck(ops, results) }
    }

    private suspend fun enqueue(op: JSONObject) {
        op.put("op_id", java.util.UUID.randomUUID().toString())
        dela.enqueue(listOf(op))
        poke()
    }

    /** Поле `zas` синка Дел. */
    suspend fun onSync(resp: JSONObject) {
        if (!::store.isInitialized) return
        val z = resp.optJSONObject("zas") ?: return
        if (!enabled()) return
        val features = resp.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        when (z.optString("mode")) {
            "phone" -> when {
                !store.serverMode -> if (FEATURE in features) handOver()
                // Режим server здесь, а сервер говорит phone, и своих операций ленты в очереди нет (передача не в пути):
                // на сервере правду вернули телефону (откат руками) — лента снова живёт здесь и шлётся в архив сама.
                !zasPending() -> store.leaveServer("сервер вернул правду ленты телефону")
            }
            "server" -> if (z.has("entries")) applyWindow(z.optJSONArray("entries"), z.optLong("from_ms"), z.optLong("lrev", -1L))
        }
    }

    /** Последний отказ сервера в передаче: чаще раза в сутки не повторяем — лента весит сотни килобайт. */
    @Volatile private var refusedAt = 0L

    private suspend fun handOver() {
        if (System.currentTimeMillis() - refusedAt < RETRY_MS) return
        val n = store.handOver { payload -> enqueue(payload.put("op", "zas.handover").put("device", device())) }
        if (n > 0) log("лента: передаю серверу $n записей")
    }

    private suspend fun zasPending(): Boolean = dela.pending().any { it.op.optString("op").startsWith("zas.") }

    /** Ответы сервера на отправленное (`DelaSync.flush`): передача и правки ленты. */
    suspend fun onAck(ops: List<JSONObject>, results: JSONArray) {
        if (!::store.isInitialized) return
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until results.length()) results.optJSONObject(i)?.let { r -> byId[r.optString("op_id")] = r }
        var window: JSONObject? = null
        for (op in ops) {
            val name = op.optString("op")
            if (!name.startsWith("zas.")) continue
            val r = byId[op.optString("op_id")] ?: continue
            if (!r.optBoolean("ok")) {
                val err = r.optString("error")
                if (name == "zas.handover") {
                    // Другой телефон владельца уже передал — правда у сервера, окно привезёт следующий синк. Иначе
                    // сервер ленту не взял (пустая, кривая) — она остаётся правдой здесь.
                    if ("уже на сервере" !in err) {
                        refusedAt = System.currentTimeMillis()
                        store.leaveServer(err)
                    }
                } else {
                    log("лента: сервер не принял правку «${op.optString("kind")}» — $err")
                }
                continue
            }
            r.optJSONObject("zas")?.takeIf { it.has("window") }?.let { window = it }
        }
        window?.let { w -> applyWindow(w.optJSONArray("window"), w.optLong("from_ms"), w.optLong("lrev", -1L)) }
    }

    private suspend fun applyWindow(arr: JSONArray?, fromMs: Long, rev: Long) {
        if (arr == null || fromMs <= 0) return
        // Свои правки ленты ещё в очереди — окно подождёт следующего прохода: сервер их ещё не видел.
        if (zasPending()) return
        val entries = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(ZasechkaStore::fromServer) }
        store.applyServer(entries, fromMs, rev)
    }

    companion object {
        const val FEATURE = "zas"
        private const val RETRY_MS = 24 * 3_600_000L
    }
}
