package ru.zf.pravka.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.Knob
import ru.zf.pravka.PhoneKnobs
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.core.PhoneRemote

/**
 * Настройки телефона на сервере Дел (14.10.2026, docs/phone-settings-server.md): снимок туда, правки с
 * компьютера сюда. Правила — `core/PhoneRemote.kt`, какие настройки — `PhoneKnobs.kt`.
 *
 * Дорога — та же, что у Дел: вход по QR, `/api/ops` и синк. Правки с компьютера приходят полем `phone` синка
 * Дел ([onSync] — его слушатель в `DelaSync`); у кого Дела выключены в профиле, синка нет — тогда раз в пять
 * минут свой взгляд видом `phone`. Снимок уходит мимо очереди Дел (`rawOps`, без op_id): он повторяем, а копить
 * его без сети незачем — следующий всё равно свежее. Когда:
 * - сразу после правок с компьютера — с ответами на них («взял», «не встало», «на телефоне поменяли позже»);
 * - через 20 с тишины после правки настроек на самом телефоне (служба, тумблеры, веер);
 * - раз в 15 минут — пульс: на странице видно, когда телефон отзывался.
 *
 * Ставит правку ТОТ ЖЕ сеттер, что тумблер на экране ([Knob.write]); значение сначала проверяется по своей схеме
 * ([PhoneRemote.check]): сервер проверял по схеме, присланной раньше, а сборка могла смениться. Спор «правил и там
 * и там» решает время ([PhoneRemote.decide]): меняли на телефоне позже правки — осталось с телефона, в ответе
 * conflict. Что поставлено с компьютера — в [view]: экран «Телефон» пишет «поменяно с компьютера в …».
 *
 * Файл — `phone-remote.json` в папке базы (DataRoot): что видели в настройках, когда что меняли, какие правки
 * взяты и неотправленные ответы на них. Расходный: пропал — следующий снимок соберёт заново, а ответы на ждущие
 * правки телефон даст ещё раз (повтор безвреден — правка, которая уже стоит, ставится как «то же»).
 */
internal class PhoneRemoteSync(
    private val app: PravkaApp,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {

    data class View(
        /** Подключены ли Дела — без них настройки с компьютера не видны. */
        val linked: Boolean = false,
        /** Последний удачный снимок; 0 — не было. */
        val okAt: Long = 0L,
        val error: String = "",
        val errorAt: Long = 0L,
        /** Сервер ещё без настроек телефона («нет такой операции»): когда узнали; 0 — сервер новый или не спрашивали. */
        val oldServerAt: Long = 0L,
        /** Что поставлено с компьютера, свежие сверху. */
        val applied: List<PhoneRemote.Applied> = emptyList(),
        /** Сколько правок ждёт этот телефон на сервере (по ответу последнего снимка). */
        val pending: Int = 0,
    )

    private val file: File get() = File(DataRoot.dir(app), FILE)
    private val mutex = Mutex()
    private var loaded = false

    /** Что видели в настройках в последний раз — по этому узнаём правку на самом телефоне. */
    private var known = HashMap<String, Any?>()
    /** Когда настройку меняли на самом телефоне, мс. */
    private val changed = HashMap<String, Long>()
    /** Взятые правки с компьютера (id → ответ): повторно не ставим, ответ даём тот же. */
    private val taken = LinkedHashMap<String, PhoneRemote.Ack>()
    /** Ответы, ещё не дошедшие до сервера. */
    private val acks = LinkedHashMap<String, PhoneRemote.Ack>()
    /** Поставлено с компьютера, ждём это же значение от потока настроек — это не правка на телефоне. */
    private val expect = HashMap<String, Any?>()
    private var applied = mutableListOf<PhoneRemote.Applied>()
    /** Отпечаток схемы, которую сервер уже принял: другой — шлём её заново. */
    private var acceptedHash = ""
    private var sentAt = 0L
    /** Что ушло последним снимком (значения и строки групп) — не менялось и пульс не пора — молчим. */
    private var sentKey = ""
    private var lastSyncAt = 0L

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private var started = false
    private var pushJob: Job? = null

    // ------------------------------------------------------------ старт

    /** Следить за настройками и раз в пять минут смотреть, не ждут ли телефон правки. Повтор безвреден. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) { runCatching { mutex.withLock { ensureLoaded() } } }
        scope.launch(Dispatchers.Default) {
            app.delaSync.link.collect { l -> _view.value = _view.value.copy(linked = l != null) }
        }
        // Правка на самом телефоне: любой поток настройки (тумблер, веер, служба) — видим, когда и что.
        scope.launch(Dispatchers.Default) {
            app.profileStore.flow.map { knobs().filter { it.flow != null } }
                .flatMapLatest { ks ->
                    if (ks.isEmpty()) flowOf(emptyMap())
                    else combine(ks.map { k -> k.flow!!(app).map { k.item.key to norm(it) } }) { it.toMap() }
                }
                .distinctUntilChanged()
                .collect { now -> runCatching { noticed(now) }.onFailure { e -> log("телефон: настройки — ${e.javaClass.simpleName}: ${e.message}") } }
        }
        // Свой тик: пульс и, если синка Дел нет (режим выключен), — взгляд на ждущие правки видом.
        scope.launch(Dispatchers.IO) {
            delay(START_DELAY_MS)
            while (true) {
                runCatching { tick() }.onFailure { e -> log("телефон: тик — ${e.javaClass.simpleName}: ${e.message}") }
                delay(TICK_MS)
            }
        }
    }

    private fun knobs(): List<Knob> = PhoneKnobs.all(app.profileStore.owner) { m -> app.profileStore.has(m) }

    /** Значение в одном виде для сравнения: Set и List — отсортированным списком, Long — Int. */
    private fun norm(v: Any?): Any? = PhoneRemote.fromJson(PhoneRemote.toJson(v))

    private suspend fun noticed(now: Map<String, Any?>) {
        val push = mutex.withLock {
            ensureLoaded()
            val t = System.currentTimeMillis()
            var mine = false
            for ((k, v) in now) {
                val was = known[k]
                if (!known.containsKey(k)) { known[k] = v; continue }
                if (PhoneRemote.same(was, v)) continue
                known[k] = v
                val remote = expect.containsKey(k) && PhoneRemote.same(expect[k], v)
                expect.remove(k)
                if (remote) continue
                changed[k] = t
                mine = true
            }
            persist()
            mine
        }
        if (push) schedule()
    }

    /** Правка на телефоне — снимок через 20 с тишины: ползунок шлёт десяток значений, серверу нужно последнее. */
    private fun schedule() {
        pushJob?.cancel()
        pushJob = scope.launch(Dispatchers.IO) {
            delay(QUIET_MS)
            runCatching { push("правка на телефоне") }
        }
    }

    // ------------------------------------------------------------ правки с компьютера

    /** Слушатель синка Дел: ждущие правки этого телефона — поставить и сразу ответить снимком. */
    suspend fun onSync(resp: JSONObject) {
        lastSyncAt = System.currentTimeMillis()
        val edits = PhoneRemote.edits(resp, device())
        if (edits.isEmpty()) {
            if (due()) push("пульс")
            return
        }
        apply(edits)
        push("правки с компьютера")
    }

    /** Свой тик: Дел нет в синке — смотрим ждущие правки видом; пульс — если пора. */
    suspend fun tick() {
        val d = app.delaSync.takeIf { it.link.value != null } ?: return
        mutex.withLock { ensureLoaded() }
        if (oldServer()) return
        if (System.currentTimeMillis() - lastSyncAt > NO_SYNC_MS) {
            val v = runCatching { d.rawView("phone?device=" + device()) }
                .onFailure { e ->
                    val m = e.message.orEmpty()
                    mutex.withLock { if (m.contains("нет такого вида")) markOld() else note(m) }
                }.getOrNull()
            val edits = v?.let { PhoneRemote.pendingFromView(it, device()) }.orEmpty()
            if (edits.isNotEmpty()) {
                apply(edits)
                push("правки с компьютера")
                return
            }
        }
        if (due()) push("пульс")
    }

    /** Поставить правки тем же путём, что тумблер. Взятые раньше не ставятся — их ответ отдаётся ещё раз. */
    suspend fun apply(edits: List<PhoneRemote.Edit>) {
        val byKey = knobs().associateBy { it.item.key }
        mutex.withLock {
            ensureLoaded()
            for (e in edits) {
                val was = taken[e.id]
                if (was != null) {
                    // Уже брали: сервер не узнал ответа (снимок не дошёл) — тот же ответ ещё раз, без второй установки.
                    acks[e.id] = was
                    continue
                }
                val ack = applyOne(e, byKey[e.key])
                taken[e.id] = ack
                acks[e.id] = ack
            }
            while (taken.size > KEEP_TAKEN) taken.remove(taken.keys.first())
            persist()
        }
    }

    private suspend fun applyOne(e: PhoneRemote.Edit, k: Knob?): PhoneRemote.Ack {
        val now = System.currentTimeMillis()
        val cur = k?.let { runCatching { norm(it.read(app)) }.getOrNull() }
        return when (val o = PhoneRemote.resolve(e, k?.item, k?.write != null, cur, changed[e.key], now)) {
            is PhoneRemote.Outcome.Answer -> {
                if (!o.ack.ok) log("телефон: с компьютера «${k?.item?.title ?: e.key}» не поставлено — ${o.ack.note}")
                o.ack
            }
            is PhoneRemote.Outcome.Write -> runCatching {
                val knob = k!!
                // Поток настройки сейчас принесёт это значение — это правка с компьютера, а не с телефона.
                expect[e.key] = norm(o.value)
                knob.write!!(app, o.value)
                changed[e.key] = now
                val label = PhoneRemote.label(knob.item, o.value)
                applied.add(0, PhoneRemote.Applied(e.key, knob.group, knob.item.title, label, now))
                while (applied.size > KEEP_APPLIED) applied.removeAt(applied.lastIndex)
                log("телефон: с компьютера «${knob.item.title}»: $label")
                PhoneRemote.Ack(e.id, true)
            }.getOrElse { x ->
                expect.remove(e.key)
                PhoneRemote.Ack(e.id, false, note = "не встало: ${x.javaClass.simpleName}: ${x.message}")
            }
        }
    }

    // ------------------------------------------------------------ снимок

    private fun due(): Boolean = System.currentTimeMillis() - sentAt >= BEAT_MS || acks.isNotEmpty()

    /** Отправить снимок. Сервер не знает схему этой сборки — сразу второй, со схемой. */
    suspend fun push(why: String): Boolean = withContext(Dispatchers.IO) {
        val d = app.delaSync.takeIf { it.link.value != null } ?: return@withContext false
        if (oldServer()) return@withContext false
        val features = app.delaStore.view.value.features
        // Синк Дел уже сказал, что сервер старый, — не стучимся.
        if (features.isNotEmpty() && PhoneRemote.FEATURE !in features) {
            mutex.withLock { ensureLoaded(); markOld() }
            return@withContext false
        }
        var withSchema = false
        repeat(2) {
            val ks = knobs()
            val groups = PhoneKnobs.groups(ks)
            val hash = PhoneRemote.schemaHash(groups)
            val values = LinkedHashMap<String, Any?>()
            for (k in ks) values[k.item.key] = runCatching { k.read(app) }.getOrNull()
            val (snap, key, sending) = mutex.withLock {
                ensureLoaded()
                val schema = withSchema || hash != acceptedHash
                val status = PhoneKnobs.status(values)
                val key = hash + "|" + JSONObject(values.mapValues { PhoneRemote.toJson(it.value) }).toString() + "|" + status
                // Нечего сказать: значения те же, ответов нет, пульс не пора.
                if (!schema && key == sentKey && acks.isEmpty() && System.currentTimeMillis() - sentAt < BEAT_MS) return@withContext true
                val sending = acks.values.toList()
                Triple(
                    PhoneRemote.snapshot(device(), deviceName(), appVersion(), groups, values, HashMap(changed), status, sending, schema),
                    key, sending,
                )
            }
            val res = try {
                d.rawOps(JSONArray().put(snap)).optJSONObject(0) ?: JSONObject()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutex.withLock { note(e.message ?: e.javaClass.simpleName) }
                return@withContext false
            }
            if (!res.optBoolean("ok")) {
                val err = res.optString("error")
                mutex.withLock {
                    if (err.contains("нет такой операции")) markOld() else note("сервер не принял снимок: $err")
                }
                return@withContext false
            }
            val needSchema = res.optBoolean("need_schema")
            mutex.withLock {
                for (a in sending) acks.remove(a.id)
                if (!needSchema) {
                    acceptedHash = hash
                    sentKey = key
                }
                sentAt = System.currentTimeMillis()
                _view.value = _view.value.copy(okAt = sentAt, error = "", errorAt = 0L, oldServerAt = 0L, pending = res.optInt("pending"))
                persist()
            }
            if (!needSchema) {
                if (sending.isNotEmpty()) log("телефон: снимок ($why), ответов на правки: ${sending.size}")
                return@withContext true
            }
            withSchema = true
        }
        false
    }

    private fun oldServer(): Boolean = System.currentTimeMillis() - _view.value.oldServerAt < OLD_RETRY_MS

    private fun markOld() {
        if (_view.value.oldServerAt == 0L) log("телефон: сервер Дел ещё без настроек телефона — обнови его на компе (install-dela.ps1)")
        _view.value = _view.value.copy(oldServerAt = System.currentTimeMillis())
    }

    private fun note(error: String) {
        val t = System.currentTimeMillis()
        if (error != _view.value.error) log("телефон: настройки на сервер — $error")
        _view.value = _view.value.copy(error = error, errorAt = t)
    }

    private fun device(): String = app.moneyCloudSync.device
    private fun deviceName(): String = android.os.Build.MODEL.orEmpty().ifBlank { "телефон" }
    private fun appVersion(): String = "${ru.zf.pravka.BuildConfig.VERSION_NAME} (${ru.zf.pravka.BuildConfig.VERSION_CODE})"

    // ------------------------------------------------------------ файл

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val o = runCatching { StoreFiles.readOrQuarantine(file) { JSONObject(it) } }.getOrNull() ?: return
        o.optJSONObject("known")?.let { k -> for (key in k.keys()) known[key] = PhoneRemote.fromJson(k.opt(key)) }
        o.optJSONObject("changed")?.let { c -> for (key in c.keys()) c.optLong(key).takeIf { it > 0 }?.let { changed[key] = it } }
        o.optJSONArray("taken")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(PhoneRemote.Ack::of)?.let { taken[it.id] = it } }
        o.optJSONArray("acks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(PhoneRemote.Ack::of)?.let { acks[it.id] = it } }
        o.optJSONArray("applied")?.let { a ->
            applied = (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { x ->
                    PhoneRemote.Applied(x.optString("key"), x.optString("group"), x.optString("title"), x.optString("value"), x.optLong("at"))
                }
            }.toMutableList()
        }
        acceptedHash = o.optString("schemaHash")
        sentAt = o.optLong("sentAt")
        _view.value = _view.value.copy(okAt = o.optLong("okAt"), applied = applied.toList())
    }

    private fun persist() {
        val o = JSONObject().put("v", 1)
            .put("known", JSONObject().apply { for ((k, v) in known) put(k, PhoneRemote.toJson(v)) })
            .put("changed", JSONObject().apply { for ((k, v) in changed) put(k, v) })
            .put("taken", JSONArray().apply { taken.values.forEach { put(it.json()) } })
            .put("acks", JSONArray().apply { acks.values.forEach { put(it.json()) } })
            .put("applied", JSONArray().apply {
                applied.forEach { put(JSONObject().put("key", it.key).put("group", it.group).put("title", it.title).put("value", it.value).put("at", it.atMs)) }
            })
            .put("schemaHash", acceptedHash)
            .put("sentAt", sentAt)
            .put("okAt", _view.value.okAt)
        _view.value = _view.value.copy(applied = applied.toList())
        val text = o.toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, text) }
    }

    /** Снимки экрана: «поменяно с компьютера» без сервера (данные выдуманные). */
    fun seedForShots(list: List<PhoneRemote.Applied>, okAt: Long) {
        applied = list.toMutableList()
        _view.value = _view.value.copy(applied = list, okAt = okAt, linked = true)
    }

    companion object {
        const val FILE = "phone-remote.json"
        private const val QUIET_MS = 20_000L
        private const val TICK_MS = 5 * 60_000L
        private const val START_DELAY_MS = 45_000L
        /** Пульс: столько молчания — и снимок уходит, даже если ничего не менялось («отзывался в …»). */
        private const val BEAT_MS = 15 * 60_000L
        /** Синка Дел не было дольше — значит, его нет (режим выключен): правки смотрим своим тиком. */
        private const val NO_SYNC_MS = 7 * 60_000L
        /** Старый сервер спрашиваем снова не чаще раза в шесть часов. */
        private const val OLD_RETRY_MS = 6 * 3_600_000L
        private const val KEEP_TAKEN = 300
        private const val KEEP_APPLIED = 30
    }
}
