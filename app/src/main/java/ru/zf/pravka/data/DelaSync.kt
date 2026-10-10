package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Связь с домашним сервером Дел (docs/dela-server.md, контракт —
 * server/contract/dela.json): подключение по QR, синк «всё после seq» и
 * отправка очереди операций.
 *
 * Покрой — как у архива (`ArchiveSync`): адрес и токен из QR команды
 * `python -m pravka_dela pair` на компе (префикс `pravka-dela:`), токен на
 * устройство, хранится в закрытой памяти (`DataRoot.secrets`) и с базой не
 * едет. Очередь гонится на 10-секундной тишине после правки и на
 * пятиминутном тике службы; проход — сначала очередь, потом синк, чтобы
 * ответ синка уже содержал свои правки.
 *
 * Ошибка не затирается общей фразой (железное правило 6): HTTP-причина
 * доходит до владельца целой — строкой состояния во вкладке и в «Подключениях».
 */
class DelaSync(
    private val context: Context,
    private val http: OkHttpClient,
    private val store: DelaStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** Свод (docs/svod-phone.md): едет тем же синком — массив `svod` и операции `svod.set`. */
    private val svod: SvodStore? = null,
    /** Что телефон отдаёт Своду при первом знакомстве: ключ → (текст, JSON). */
    private val svodSeed: suspend () -> Map<String, Pair<String?, String?>> = { emptyMap() },
) {

    data class Link(val url: String, val token: String, val user: String, val name: String, val at: Long)

    data class Status(
        val running: Boolean = false,
        val lastOk: Long = 0L,
        val lastError: String = "",
        val lastErrorAt: Long = 0L,
        val note: String = "",
    )

    /**
     * Карточка дела с сервера: комментарии, журнал правок (журнал есть только
     * там) и `origin` — откуда дело (08.10.2026; у старого сервера его нет — null).
     */
    data class Card(val comments: List<JSONObject>, val history: List<JSONObject>, val origin: JSONObject? = null)

    private val linkFile: File get() = File(DataRoot.secrets(context), LINK_FILE)

    private val _link = MutableStateFlow(readLink())
    val link: StateFlow<Link?> = _link

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()
    private var pokeJob: Job? = null

    init {
        _link.value?.let { l -> scope.launch { runCatching { store.setMe(l.user) } } }
    }

    // ------------------------------------------------------------ вход

    private fun readLink(): Link? = runCatching {
        if (!linkFile.isFile) return null
        val o = JSONObject(linkFile.readText())
        Link(o.getString("url"), o.getString("token"), o.optString("user"), o.optString("name"), o.optLong("at"))
    }.getOrNull()

    /**
     * Проверить адрес и токен (`GET /api/me`) и запомнить. Не вышло — ничего
     * не сохраняется. Копия собирается заново полным синком: это может быть
     * другой сервер, и номера изменений у него свои.
     */
    suspend fun connect(url: String, token: String): Result<Link> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = normalize(url)
            val t = token.trim()
            require(clean.startsWith("https://")) { "Адрес Дел — с https://" }
            require(t.length >= 32) { "Токен короткий — отсканируй QR из python -m pravka_dela pair на компе" }
            val me = get(clean, t, "api/me")
            val user = me.optString("user").takeIf { it.isNotBlank() && it != "null" }
                ?: throw DelaException("Дела ответили без пользователя: ${me.toString().take(200)}")
            val l = Link(clean, t, user, me.optString("name").ifBlank { user }, System.currentTimeMillis())
            StoreFiles.writeAtomic(
                linkFile,
                JSONObject().put("url", l.url).put("token", l.token).put("user", l.user).put("name", l.name).put("at", l.at).toString(),
            )
            store.setMe(user)
            store.resetCopy()
            _link.value = l
            _status.value = Status()
            log("дела: подключены $clean как ${l.name}")
            l
        }
    }

    fun disconnect() {
        runCatching { linkFile.delete() }
        _link.value = null
        _status.value = Status()
        log("дела: отключены")
    }

    // ------------------------------------------------------------ когда

    /** Правка легла в очередь: отправить через десять секунд тишины, пачкой. */
    fun poke() {
        if (_link.value == null) return
        pokeJob?.cancel()
        pokeJob = scope.launch(Dispatchers.IO) {
            delay(POKE_QUIET_MS)
            runCatching { sync("правка") }
        }
    }

    /** Тик службы и открытие вкладки: очередь и всё новое с сервера. */
    suspend fun tick() {
        if (_link.value == null) return
        sync("тик")
    }

    /**
     * Отправить сейчас и подождать ответа не дольше [waitMs]. Проход идёт в
     * своей корутине: ожидание обрывается, а сам запрос — нет (OkHttp
     * блокирует поток, отменой его не прервать). true — очередь пуста.
     */
    suspend fun pushNow(waitMs: Long): Boolean {
        if (_link.value == null) return false
        pokeJob?.cancel()
        val job = scope.async(Dispatchers.IO) { runCatching { sync("сразу") } }
        withTimeoutOrNull(waitMs) { job.await() }
        return store.queued.value.isEmpty()
    }

    // ------------------------------------------------------------ проход

    /** Очередь — на сервер, потом синк. Идёт один проход: второй ждёт первого. */
    suspend fun sync(why: String): Boolean = withContext(Dispatchers.IO) {
        val l = _link.value ?: return@withContext false
        if (!mutex.tryLock()) return@withContext false
        try {
            _status.value = _status.value.copy(running = true)
            val problem = runCatching {
                flush(l)
                pull(l)
            }.exceptionOrNull()?.let { why(it) }
            val now = System.currentTimeMillis()
            if (problem == null) {
                _status.value = Status(lastOk = now)
                true
            } else {
                store.markTry(problem)
                _status.value = _status.value.copy(running = false, lastError = problem, lastErrorAt = now)
                log("дела: $why — $problem")
                false
            }
        } finally {
            _status.value = _status.value.copy(running = false)
            mutex.unlock()
        }
    }

    private suspend fun flush(l: Link) {
        while (true) {
            val queue = store.pending()
            if (queue.isEmpty()) return
            val chunk = queue.take(MAX_OPS)
            val body = JSONObject().put("ops", JSONArray().apply { chunk.forEach { put(it.op) } })
            val reply = post(l, "api/ops", body)
            val results = reply.optJSONArray("results") ?: throw DelaException("Дела ответили без results: ${reply.toString().take(200)}")
            val acked = store.ack(results)
            svod?.ack(chunk.map { it.op }, results)
            log("дела: отправлено ${chunk.size}, ответ на $acked")
            // Сервер ответил не на всё — повторять тот же кусок по кругу незачем:
            // неотвеченное уйдёт следующим проходом.
            if (acked < chunk.size || chunk.size < MAX_OPS) return
        }
    }

    private suspend fun pull(l: Link) {
        val since = store.seq()
        val resp = get(l.url, l.token, "api/sync?since=$since")
        val refused = store.applySync(resp, System.currentTimeMillis())
        if (refused.isNotBlank()) throw DelaException(refused)
        svod?.let { sv -> pullSvod(l, sv, resp) }
        // Фильтры Дел (10.10.2026) — в настройках человека на сервере, синк их не возит: берём из
        // `/api/me`. Сбой фильтров синк дел не роняет — он словами в журнал.
        if (ru.zf.pravka.core.Dela.FEATURE_GROUPS in store.view.value.features) {
            runCatching {
                val me = get(l.url, l.token, "api/me")
                val settings = me.optJSONObject("settings") ?: JSONObject()
                store.setServerFilters(settings.optJSONArray("filters"))
            }.onFailure { e -> log("дела: фильтры — ${why(e)}") }
        }
    }

    /**
     * Свод после синка: записи поверх кэша; впервые увидев умение — весь Свод
     * видом (записи «Денег» могли лечь раньше, чем телефон начал о нём
     * спрашивать, и в синк «после seq» они уже не попадут), потом первое
     * знакомство. Сбой Свода синк дел не роняет — он словами в журнал.
     */
    private suspend fun pullSvod(l: Link, sv: SvodStore, resp: JSONObject) {
        runCatching {
            sv.applySync(resp)
            if (ru.zf.pravka.core.Svod.FEATURE !in store.view.value.features) return
            if (sv.needsFetch()) fetchSvod(l, sv, fresh = false)
            sv.seedIfNeeded(ru.zf.pravka.BuildConfig.VERSION_CODE, svodSeed)
            sv.resend()
        }.onFailure { e -> log("свод: ${why(e)}") }
    }

    private suspend fun fetchSvod(l: Link, sv: SvodStore, fresh: Boolean) {
        val o = get(l.url, l.token, "api/view/svod")
        val a = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ru.zf.pravka.core.Svod::entry) }
        sv.applyAll(items, System.currentTimeMillis(), fresh)
        log("свод: с сервера ${items.size} записей" + if (fresh) " — взял заново" else "")
    }

    /** «Взять с сервера заново» (Настройки → Свод): весь Свод видом, своё неотправленное снимается. */
    suspend fun refetchSvod(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val l = _link.value ?: throw DelaException("Дела не подключены — Свод живёт на их сервере")
            val sv = svod ?: throw DelaException("Свода нет")
            fetchSvod(l, sv, fresh = true)
        }.recoverCatching { e -> throw DelaException(why(e)) }
    }

    // ------------------------------------------------------------ кто это

    private val whoCache = HashMap<String, Pair<Long, ru.zf.pravka.core.CallRules.Who>>()

    /**
     * Кто это (`GET /api/view/who`, контракт svod.json, `people.who`): одно
     * правило на всех — номер, потом имя с уменьшительными, падежами,
     * «Фамилия Имя» и компанией. Ответ по номеру помним сутки; без сети и
     * без умения `people` — null, и звонок узнаётся прежним точным
     * сопоставлением по синку.
     */
    suspend fun who(phone: String, name: String): ru.zf.pravka.core.CallRules.Who? = withContext(Dispatchers.IO) {
        val l = _link.value ?: return@withContext null
        if (ru.zf.pravka.core.Svod.FEATURE_PEOPLE !in store.view.value.features) return@withContext null
        val d = ru.zf.pravka.core.CallRules.digits(phone)
        val key = d + "|" + name.trim().lowercase()
        val now = System.currentTimeMillis()
        synchronized(whoCache) { whoCache[key]?.takeIf { now - it.first < WHO_TTL_MS }?.let { return@withContext it.second } }
        val q = buildList {
            if (d.length >= 7) add("phone=" + URLEncoder.encode(phone.trim(), "UTF-8"))
            if (name.isNotBlank()) add("q=" + URLEncoder.encode(name.trim(), "UTF-8"))
        }
        if (q.isEmpty()) return@withContext null
        // Свип звонков не ждёт сервер дольше десяти секунд: не ответил — точное сопоставление.
        val path = "api/view/who?" + q.joinToString("&")
        val job = scope.async(Dispatchers.IO) { runCatching { ru.zf.pravka.core.CallRules.parseWho(get(l.url, l.token, path)) } }
        (withTimeoutOrNull(WHO_WAIT_MS) { job.await() } ?: Result.failure(DelaException("не ответил за ${WHO_WAIT_MS / 1000} с")))
            .onFailure { e -> log("дела: кто это ($name) — ${why(e)}") }
            .getOrNull()
            ?.also { w -> synchronized(whoCache) { whoCache[key] = now to w } }
    }

    /** Комментарии и журнал дела — только с сервера, по запросу карточки. */
    suspend fun card(taskId: String): Result<Card> = withContext(Dispatchers.IO) {
        runCatching {
            val l = _link.value ?: throw DelaException("Дела не подключены")
            val o = get(l.url, l.token, "api/task/" + URLEncoder.encode(taskId, "UTF-8"))
            fun list(key: String): List<JSONObject> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }
            Card(list("comments"), list("history"), o.optJSONObject("origin"))
        }
    }

    // ------------------------------------------------------------ CRM и Claude

    /**
     * CRM-вид с сервера (`/api/view/<путь>`, контракт — dela-crm.json,
     * `views_crm`): ответ — в кэш на диске, экран открывается с ним и без сети.
     * Не вышло — кэш прежний, причина целиком (правило 6).
     */
    suspend fun crmView(path: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val l = _link.value ?: throw DelaException("Дела не подключены")
            val o = get(l.url, l.token, "api/view/$path")
            store.putView(path, o, System.currentTimeMillis())
            o
        }.onFailure { e -> log("дела: вид $path — ${why(e)}") }.recoverCatching { e -> throw DelaException(why(e)) }
    }

    /**
     * Правка словами через сервер (`POST /api/ask`, docs/dela-phone-2.md, этап 1,
     * пункт 3). Сначала — своя очередь на сервер: Claude правит то, что видит
     * владелец, а не то, что сервер знал до метро. Ответ модели бывает дольше
     * минуты, а роутер долгие запросы рвёт — поэтому задание и опрос: первые
     * 15 раз раз в секунду, потом реже. Правка приходит уже применённой: в
     * очередь она не ложится, после ответа — синк.
     *
     * Без сети команда не уходит — и это отказ словами, а не тишина: текст
     * остаётся в поле у вызывающего.
     */
    suspend fun ask(text: String, scope: JSONObject): Result<ru.zf.pravka.core.DelaAsk.Result> = withContext(Dispatchers.IO) {
        runCatching {
            val l = _link.value ?: throw DelaException("Дела не подключены — команда не ушла")
            val clean = text.trim()
            if (clean.isEmpty()) throw DelaException("Скажи, что сделать с делами")
            if (clean.length > ru.zf.pravka.core.DelaAsk.MAX_INPUT) {
                throw DelaException("Команда длиннее ${ru.zf.pravka.core.DelaAsk.MAX_INPUT} знаков — для длинной надиктовки «говори дела»")
            }
            runCatching { sync("перед Claude") }
            val start = post(l, "api/ask", ru.zf.pravka.core.DelaAsk.request(clean, scope))
            val job = start.optString("job").takeIf { it.isNotBlank() && it != "null" }
                ?: throw DelaException("Дела не дали номер задания: ${start.toString().take(200)}")
            log("дела: Claude, ${clean.length} знаков, дел на экране ${scope.optJSONArray("task_ids")?.length() ?: 0}")
            var misses = 0
            for (i in 0 until ru.zf.pravka.core.DelaAsk.MAX_POLLS) {
                delay(ru.zf.pravka.core.DelaAsk.pollDelayMs(i))
                val d = try {
                    get(l.url, l.token, "api/ask/" + URLEncoder.encode(job, "UTF-8"))
                } catch (e: DelaException) {
                    // 404 от самой службы на опросе — она перезапустилась и задание
                    // потеряла (задания живут в памяти). 404 посредника — путь закрыт: как есть.
                    if (e.code == 404 && e.ours) {
                        throw DelaException("Задание Claude потерялось — служба Дел перезапустилась. Скажи ещё раз: текст остался")
                    }
                    throw e
                } catch (e: java.io.IOException) {
                    // Связь моргнула посреди ожидания — задание на сервере идёт дальше, спросим ещё.
                    if (++misses > 3) throw e
                    continue
                }
                misses = 0
                if (d.optString("status") == "run") continue
                val r = ru.zf.pravka.core.DelaAsk.parse(d)
                log("дела: Claude ответил — ${r.route}, поправлено ${r.changed.size}, заведено ${r.tasks.size}, ошибок ${r.errors.size}")
                pullAfterClaude()
                return@runCatching r
            }
            throw DelaException("Claude думает дольше пяти минут — правка появится сама, когда он закончит")
        }.recoverCatching { e -> throw DelaException(why(e)) }
    }

    /**
     * Синк после правки Claude: она уже на сервере, телефон должен её увидеть.
     * Идёт чужой проход (тик) — он мог взять синк до правки, поэтому ждём его и
     * повторяем; не больше трёх раз.
     */
    private suspend fun pullAfterClaude() {
        repeat(3) {
            if (runCatching { sync("после Claude") }.getOrDefault(false)) return
            delay(1_500L)
        }
    }

    // ------------------------------------------------------------ сеть

    private fun get(base: String, token: String, path: String): JSONObject {
        val req = Request.Builder().url(base + path).header("Authorization", "Bearer $token").get().build()
        return call(req)
    }

    private fun post(l: Link, path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(l.url + path)
            .header("Authorization", "Bearer ${l.token}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return call(req)
    }

    private fun call(req: Request): JSONObject {
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // Свой ответ служба даёт JSON с error; 404 без него — путь не пропустил
                // посредник перед службой (закрытый список путей, 05.10.2026: так молча
                // падали /api/ask и /api/settings в вебе), а не сама служба.
                val ours = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                val msg = ours.ifBlank { text.take(300) }
                val path = req.url.encodedPath
                throw DelaException(
                    when {
                        r.code == 401 -> "Дела не приняли токен — отсканируй QR из python -m pravka_dela pair заново ($msg)"
                        r.code == 403 -> "Дела отказали: $msg"
                        r.code == 404 && ours.isNotBlank() -> "Дела ответили 404: $ours"
                        r.code == 404 -> "Сервер не знает адрес $path (404) — путь не пропускает посредник перед Делами " +
                            "(его надо добавить в список путей) или служба старая"
                        else -> "Дела ответили ${r.code}: $msg"
                    },
                    code = r.code,
                    ours = ours.isNotBlank(),
                )
            }
            val o = runCatching { JSONObject(text) }.getOrElse { throw DelaException("Дела ответили не JSON: ${text.take(200)}") }
            if (!o.optBoolean("ok", true)) throw DelaException("Дела: ${o.optString("error").ifBlank { text.take(200) }}")
            return o
        }
    }

    private val client: OkHttpClient by lazy {
        http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Ошибка словами; [code] — HTTP-код, [ours] — ответила сама служба (JSON с error), а не посредник. */
    class DelaException(message: String, val code: Int = 0, val ours: Boolean = false) : Exception(message)

    private fun why(e: Throwable): String = when (e) {
        is DelaException -> e.message.orEmpty()
        is java.net.UnknownHostException -> "Дела: нет сети или адрес не находится (${e.message})"
        is java.net.ConnectException -> "Дела не отвечают (${e.message})"
        is java.net.SocketTimeoutException -> "Дела не ответили вовремя (${e.message})"
        is javax.net.ssl.SSLException -> "Дела: не сложилось HTTPS-соединение (${e.message})"
        else -> "Дела: ${e.javaClass.simpleName}: ${e.message}"
    }

    companion object {
        const val LINK_FILE = "dela-link.json"
        const val PAIR_PREFIX = "pravka-dela:"
        private const val POKE_QUIET_MS = 10_000L
        /** Ответ «кто это» по номеру — на сутки (docs/svod-phone.md, 3.2). */
        private const val WHO_TTL_MS = 24 * 3_600_000L
        private const val WHO_WAIT_MS = 10_000L
        /** Потолок пачки у сервера — 2000 операций; берём с запасом. */
        private const val MAX_OPS = 500

        fun normalize(url: String): String {
            val u = url.trim()
            return "https://" + u.substringAfter("://", u).trimEnd('/') + "/"
        }

        /** QR из `pair`: «pravka-dela:{"url":…,"token":…}» → адрес и токен. */
        fun parsePairing(text: String): Pair<String, String>? = runCatching {
            val t = text.trim()
            if (!t.startsWith(PAIR_PREFIX)) return null
            val o = JSONObject(t.removePrefix(PAIR_PREFIX))
            val url = o.getString("url")
            val token = o.getString("token")
            if (url.isBlank() || token.isBlank()) null else url to token
        }.getOrNull()
    }
}
