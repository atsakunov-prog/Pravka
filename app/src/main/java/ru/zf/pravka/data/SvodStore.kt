package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Svod

/**
 * Свод на телефоне (06.10.2026, docs/svod-phone.md, часть 1): кэш записей
 * сервера (`svod.json` в `DataRoot`) и своя очередь правок поверх.
 *
 * Сервер держит, телефон кэширует: записи приходят синком Дел (массив
 * `svod`), телефон работает по кэшу и без сети. Своя правка уходит
 * операцией `svod.set` через очередь Дел и только с ответом сервера
 * становится записью кэша; до ответа она видна сразу (как своя правка дела).
 *
 * Знания, которые живут в своих сторах (словарь, правила, справочник
 * получателей, категории, выбор моделей), подключены зеркалами
 * ([Mirror]): правка в сторе → через тишину `svod.set` с `base_rev`; отказ
 * «на сервере уже версии N» или новая версия из синка при неотправленном
 * своём → трёхстороннее слияние и снова наверх. Новая версия при чистом
 * своём — просто ложится в стор.
 *
 * Записи на сервере не удаляются, и кэш только дописывается: полный синк
 * без записей поверх непустого кэша не ложится (железное правило 1).
 */
class SvodStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {

    companion object {
        const val FILE_NAME = "svod.json"
    }

    /**
     * Знание, которое живёт в своём сторе, а правда о нём — в Своде.
     * [export] — своё сейчас (JSON-текст), [replace] — положить серверное
     * (без отклика «поменялось»), [merge] — своё поверх серверного.
     */
    interface Mirror {
        val key: String
        /** Тишина перед отправкой: словарь правится очередями слов — 30 с. */
        val quietMs: Long get() = 10_000L
        suspend fun export(): String
        suspend fun replace(value: String)
        suspend fun merge(base: String?, server: String, local: String): String
    }

    /** Что видит экран «Свод»: записи, своя очередь, когда отдали своё. */
    data class View(
        val entries: List<Svod.Entry> = emptyList(),
        val pending: List<Svod.Pending> = emptyList(),
        val dirty: Set<String> = emptySet(),
        val seededAt: Long = 0,
        val given: Set<String> = emptySet(),
        val fetchedAt: Long = 0,
    )

    private val mutex = Mutex()
    private val file: File get() = File(DataRoot.dir(context), FILE_NAME)
    private var loaded = false

    private var entries: Map<String, Svod.Entry> = emptyMap()
    private val pending = LinkedHashMap<String, Svod.Pending>()   // op_id → правка
    /** Версия, которую зеркало уже приняло в свой стор, и её текст — база слияния. */
    private val applied = HashMap<String, Int>()
    private val bases = HashMap<String, String>()
    /** Зеркала с неотправленным своим. */
    private val dirty = HashSet<String>()
    /** Ключи, которые первое знакомство отдало (или застало уже на сервере). */
    private val given = HashSet<String>()
    private var seededAt = 0L
    /** Когда последний раз брали весь Свод видом (первое знакомство, «Взять заново»). */
    private var fetchedAt = 0L

    private val mirrors = LinkedHashMap<String, Mirror>()
    private val quietJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Отправка операций — очередь Дел; ставит приложение (`PravkaApp`). */
    @Volatile var sender: (suspend (List<JSONObject>) -> Unit)? = null

    /** Умеет ли сервер Свод (`features`) — без него ничего не шлём. */
    @Volatile var enabled: () -> Boolean = { false }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    fun register(m: Mirror) {
        mirrors[m.key] = m
    }

    suspend fun load() = mutex.withLock { ensureLoaded() }

    fun entry(key: String): Svod.Entry? = Svod.current[key]

    /** Нужен ли весь Свод видом: ещё не брали (сервер мог завести записи до того, как телефон узнал о Своде). */
    suspend fun needsFetch(): Boolean = mutex.withLock { ensureLoaded(); fetchedAt == 0L }

    // ------------------------------------------------------------ с сервера

    /** Массив `svod` ответа синка поверх кэша; потом — зеркала. */
    suspend fun applySync(resp: JSONObject) {
        val got = Svod.fromSync(resp)
        mutex.withLock {
            ensureLoaded()
            if (got.isNotEmpty()) {
                entries = Svod.merge(entries, got)
                persist()
                publish()
            }
        }
        reconcile()
    }

    /**
     * Весь Свод видом (`/api/view/svod`): первое знакомство и «Взять с
     * сервера заново». [fresh] — зеркала берут серверное как есть, своё
     * неотправленное снимается (кнопка владельца так и обещает).
     */
    suspend fun applyAll(items: List<Svod.Entry>, now: Long, fresh: Boolean) {
        mutex.withLock {
            ensureLoaded()
            entries = Svod.merge(entries, items)
            fetchedAt = now
            if (fresh) {
                dirty.clear()
                applied.clear()
                bases.clear()
            }
            persist()
            publish()
        }
        reconcile()
    }

    // ------------------------------------------------------------ своё

    /**
     * Своя правка записи: в очередь и на сервер. [baseRev] — версия, поверх
     * которой правили (автоматы, переписывающие целое); null — последний
     * пишущий побеждает (правка владельца).
     */
    suspend fun set(key: String, body: String? = null, value: String? = null, author: String, reason: String = "", baseRev: Int? = null): Svod.Pending? {
        require(Svod.validKey(key)) { "ключ Свода «$key»" }
        val p = mutex.withLock {
            ensureLoaded()
            val have = entries[key]
            val queued = pending.values.lastOrNull { it.key == key }
            val same = if (body != null) (queued?.body ?: have?.body) == body
            else Svod.sameJson(queued?.value ?: have?.value, value)
            if (same) return@withLock null
            val p = Svod.Pending(key, body, value, author, reason, baseRev, UUID.randomUUID().toString(), System.currentTimeMillis())
            // Новая правка того же ключа заменяет неотправленную: наверх уходит последнее.
            pending.values.removeAll { it.key == key && it.author == author && it.baseRev == baseRev }
            pending[p.opId] = p
            persist()
            publish()
            p
        } ?: return null
        send(listOf(p))
        return p
    }

    private suspend fun send(list: List<Svod.Pending>) {
        if (list.isEmpty() || !enabled()) return
        val s = sender ?: return
        runCatching { s(list.map { Svod.op(it) }) }.onFailure { log("свод: в очередь не встало — ${it.message}") }
    }

    /** Зеркало поменялось у себя: через тишину — наверх. Своё не теряется и без сети. */
    fun touch(key: String) {
        val m = mirrors[key] ?: return
        scope.launch {
            mutex.withLock { ensureLoaded(); if (dirty.add(key)) { persist(); publish() } }
            quietJobs[key]?.cancel()
            quietJobs[key] = scope.launch(Dispatchers.IO) {
                delay(m.quietMs)
                runCatching { publishMirror(m) }.onFailure { log("свод: $key — ${it.message}") }
            }
        }
    }

    private suspend fun publishMirror(m: Mirror) {
        if (!enabled()) return
        val local = m.export()
        val (have, busy) = mutex.withLock { ensureLoaded(); entries[m.key] to pending.values.any { it.key == m.key } }
        if (busy) return   // ответ на прошлую придёт — сверка решит
        if (have != null && have.value != null && Svod.sameJson(local, have.value)) {
            mutex.withLock { dirty.remove(m.key); persist(); publish() }
            return
        }
        set(m.key, value = local, author = "phone", reason = "правка на телефоне", baseRev = have?.rev ?: 0)
    }

    /**
     * Ответ `/api/ops` на свои `svod.set`: принятое — запись кэша с версией
     * сервера; «на сервере уже версии N» — правка снята, зеркало остаётся
     * «своё не отдано» и сольётся со свежей версией после синка.
     */
    suspend fun ack(ops: List<JSONObject>, results: JSONArray) {
        val res = HashMap<String, JSONObject>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            r.optString("op_id").takeIf { it.isNotBlank() }?.let { res[it] = r }
        }
        mutex.withLock {
            ensureLoaded()
            var changed = false
            for (op in ops) {
                if (op.optString("op") != "svod.set") continue
                val id = op.optString("op_id")
                val r = res[id] ?: continue
                val p = pending.remove(id) ?: continue
                changed = true
                if (r.optBoolean("ok", false)) {
                    val row = r.optJSONObject("row")
                    val e = Svod.Entry(
                        key = p.key, body = p.body, value = p.value,
                        rev = row?.optInt("rev") ?: ((entries[p.key]?.rev ?: 0) + 1),
                        seq = row?.optLong("seq") ?: 0L, author = p.author, reason = p.reason,
                        updatedAt = java.time.OffsetDateTime.now().withNano(0).toString(),
                    )
                    entries = Svod.merge(entries, listOf(e))
                    if (p.author == "seed") given += p.key
                    if (p.key in mirrors) {
                        applied[p.key] = e.rev
                        p.value?.let { bases[p.key] = it }
                        // Правили и после отправленного — тишина пошлёт ещё раз.
                        if (quietJobs[p.key]?.isActive != true) dirty.remove(p.key)
                    }
                } else {
                    val err = r.optString("error")
                    if (Svod.isStale(err)) {
                        if (p.author == "seed") given += p.key   // уже есть — сервер знает больше
                        if (p.key in mirrors) dirty += p.key
                        log("свод: ${p.key} — на сервере новее, сливаю со своим")
                    } else {
                        log("свод: сервер не принял ${p.key} — $err")
                    }
                }
            }
            if (changed) {
                persist()
                publish()
            }
        }
    }

    // ------------------------------------------------------------ зеркала

    /**
     * Свести зеркала со Сводом. Новая версия и своё чистое — в стор. Своё
     * неотправленное — слить с серверным и послать с его `base_rev`. Пока
     * своя правка в пути — ждать ответа.
     */
    suspend fun reconcile() {
        for (m in mirrors.values.toList()) runCatching { reconcileOne(m) }.onFailure { log("свод: ${m.key} — ${it.message}") }
    }

    private suspend fun reconcileOne(m: Mirror) {
        data class Look(val e: Svod.Entry?, val busy: Boolean, val isDirty: Boolean, val applied: Int, val base: String?, val seeded: Boolean)
        val l = mutex.withLock {
            ensureLoaded()
            Look(entries[m.key], pending.values.any { it.key == m.key }, m.key in dirty, applied[m.key] ?: 0, bases[m.key], seededAt > 0)
        }
        // До первого знакомства серверное в стор не кладём: словарь, выученный
        // телефоном за месяцы, сначала сливается со Сводом объединением.
        if (!l.seeded) return
        val e = l.e ?: return
        val server = e.value ?: return
        if (l.busy) return
        if (l.isDirty) {
            if (!enabled()) return
            val local = m.export()
            val merged = m.merge(l.base, server, local)
            if (!Svod.sameJson(merged, local)) m.replace(merged)
            mutex.withLock {
                applied[m.key] = e.rev
                bases[m.key] = server
                if (Svod.sameJson(merged, server)) dirty.remove(m.key)
                persist(); publish()
            }
            if (!Svod.sameJson(merged, server)) {
                set(m.key, value = merged, author = "phone", reason = "слил своё с версией ${e.rev}", baseRev = e.rev)
            }
            return
        }
        if (e.rev > l.applied) {
            m.replace(server)
            mutex.withLock { applied[m.key] = e.rev; bases[m.key] = server; persist(); publish() }
        }
    }

    // ------------------------------------------------------------ первое знакомство

    /**
     * Первое знакомство: увидев Свод, телефон один раз кладёт то, чего там
     * ещё нет (`base_rev: 0`, `author: "seed"`). Зеркала, которые на сервере
     * уже есть, сливаются со своим объединением (база неизвестна): выученное
     * телефоном за месяцы не пропадает, и чужое не затирается.
     */
    suspend fun seedIfNeeded(build: Int, mine: suspend () -> Map<String, Pair<String?, String?>>) {
        if (!enabled()) return
        val (done, have) = mutex.withLock { ensureLoaded(); (seededAt > 0) to entries.keys.toSet() }
        if (done) return
        val texts = mine()
        val plan = Svod.seedPlan(have, texts)
        val list = ArrayList<Svod.Pending>()
        mutex.withLock {
            for (key in plan) {
                val (body, value) = texts.getValue(key)
                val p = Svod.Pending(key, body, value, "seed", "сборка $build", 0, UUID.randomUUID().toString(), System.currentTimeMillis())
                pending[p.opId] = p
                list += p
            }
            // Есть на сервере — своё зеркало сольётся с ним объединением.
            for (key in mirrors.keys) if (key in have) { dirty += key; bases.remove(key); applied.remove(key); given += key }
            for (key in texts.keys) if (key in have) given += key
            seededAt = System.currentTimeMillis()
            persist()
            publish()
        }
        log("свод: первое знакомство — отдаю ${list.size}, на сервере уже ${have.size}")
        send(list)
        reconcile()
    }

    /** Операции Свода, которых сервер ещё не видел (после отключения или без умения) — снова в очередь. */
    suspend fun resend() {
        val list = mutex.withLock { ensureLoaded(); pending.values.toList() }
        send(list)
    }

    // ------------------------------------------------------------ диск

    private fun publish() {
        val withPending = LinkedHashMap(entries)
        for (p in pending.values) {
            val was = withPending[p.key]
            withPending[p.key] = Svod.Entry(p.key, p.body, p.value, was?.rev ?: 0, was?.seq ?: 0, p.author, p.reason, was?.updatedAt.orEmpty())
        }
        Svod.current = withPending
        _view.value = View(
            entries = entries.values.sortedBy { it.key },
            pending = pending.values.toList(),
            dirty = dirty.toSet(),
            seededAt = seededAt,
            given = given.toSet(),
            fetchedAt = fetchedAt,
        )
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { JSONObject(it) }?.let { o ->
                val es = LinkedHashMap<String, Svod.Entry>()
                o.optJSONArray("entries")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(Svod::entry)?.let { es[it.key] = it } }
                entries = es
                o.optJSONArray("pending")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(Svod::pending)?.let { pending[it.opId] = it } }
                o.optJSONObject("applied")?.let { a -> for (k in a.keys()) applied[k] = a.optInt(k) }
                o.optJSONObject("bases")?.let { a -> for (k in a.keys()) bases[k] = a.optString(k) }
                o.optJSONArray("dirty")?.let { a -> for (i in 0 until a.length()) dirty += a.optString(i) }
                o.optJSONArray("given")?.let { a -> for (i in 0 until a.length()) given += a.optString(i) }
                seededAt = o.optLong("seededAt")
                fetchedAt = o.optLong("fetchedAt")
            }
        }
        loaded = true
        publish()
    }

    /** Синхронно с мьютексом сериализуем, пишет общий писатель: порядок состояний = порядок записей. */
    private fun persist() {
        val o = JSONObject().put("v", 1)
            .put("entries", JSONArray().apply { entries.values.forEach { put(Svod.json(it)) } })
            .put("pending", JSONArray().apply { pending.values.forEach { put(Svod.json(it)) } })
            .put("applied", JSONObject().apply { applied.forEach { (k, v) -> put(k, v) } })
            .put("bases", JSONObject().apply { bases.forEach { (k, v) -> put(k, v) } })
            .put("dirty", JSONArray().apply { dirty.sorted().forEach { put(it) } })
            .put("given", JSONArray().apply { given.sorted().forEach { put(it) } })
            .put("seededAt", seededAt)
            .put("fetchedAt", fetchedAt)
        val text = o.toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, text) }
    }
}
