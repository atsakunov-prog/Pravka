package ru.zf.pravka.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.ArchiveEvents

/**
 * Архив на домашнем компе: каждый ввод — событием на сервер (docs/arkhiv.md).
 *
 * Владелец, 01.10.2026: «архив я бы обновлял с каждым новым вводом… И этот
 * архив должен быть всеобъемлющим». Телефон данные забывает (диктовки
 * ротируются, тренировки — 120 дней), архив — нет.
 *
 * Как устроено. Очереди на диске нет: её роль играет журнал квитанций
 * (`archive-ledger.json`) — что сервер уже принял, отпечатком на каждую
 * запись. Каждый проход собирает записи режима (`ArchiveEvents`), сравнивает
 * с квитанциями и шлёт только изменившееся. Нет сети — квитанций нет, и
 * следующий проход пошлёт то же самое заново; повтор сервер не удваивает.
 * Удаление — отдельным событием, и только у тех видов, где отсутствие
 * записи и правда значит «удалили» (лента по суткам, приёмы еды, силовые,
 * зарядка); срезанное по сроку и журналы удалением не считаются никогда.
 *
 * Журналы Правки (`transcriptions.jsonl`, `history.jsonl`) и журнал службы
 * (`dictation-events.log`) только дописываются — их читаем с места, где
 * остановились, а не целиком.
 *
 * Куда и с чем — адрес и токен из QR команды `pair` на компе, в закрытой
 * памяти (`DataRoot.secrets`): с базой не едут.
 */
internal class ArchiveSync(
    private val context: Context,
    private val http: OkHttpClient,
    private val sources: Sources,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {

    /** Откуда брать записи: сторы приложения, без знания о них здесь. */
    interface Sources {
        val profile: String?
        val appVersion: String
        suspend fun units(domain: Domain, clock: ArchiveEvents.Clock): List<ArchiveEvents.Item>
    }

    enum class Domain(val kinds: List<String>) {
        ZASECHKA(listOf("zasechka.day", "zasechka.reference")),
        PHONE(listOf("phone.day")),
        FOOD(listOf("food.meal", "food.norms")),
        STRENGTH(listOf("strength.session", "strength.gtg", "strength.take")),
        MONEY(listOf("money.entry", "money.reference", "money.take", "money.push")),
        SPORT(listOf("sport.talk")),
        PRAVKA(listOf("pravka.take", "pravka.clean", "pravka.correction", JOURNAL_KIND)),
    }

    data class Link(val url: String, val token: String, val at: Long)

    data class Status(
        val running: Boolean = false,
        val lastOk: Long = 0L,
        val lastError: String = "",
        val lastErrorAt: Long = 0L,
        /** Сколько ждёт отправки по последнему проходу. */
        val pending: Int = 0,
        /** Сколько всего принято сервером с этого телефона. */
        val sent: Long = 0L,
        val note: String = "",
    )

    private val linkFile: File get() = File(DataRoot.secrets(context), LINK_FILE)
    private val deviceFile: File get() = File(DataRoot.secrets(context), DEVICE_FILE)
    private val ledgerFile: File get() = File(DataRoot.dir(context), LEDGER_FILE)

    private val _link = MutableStateFlow(readLink())
    val link: StateFlow<Link?> = _link

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()
    private var lastSeq = 0L
    private var pokeJob: Job? = null
    private val poked = mutableSetOf<Domain>()

    // ------------------------------------------------------------ вход

    private fun readLink(): Link? = runCatching {
        if (!linkFile.isFile) return null
        val o = JSONObject(linkFile.readText())
        Link(o.getString("url"), o.getString("token"), o.optLong("at"))
    }.getOrNull()

    /** Проверить адрес и токен пустой пачкой и запомнить. Не вышло — ничего не сохраняется. */
    suspend fun connect(url: String, token: String): Result<Link> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = normalize(url)
            val t = token.trim()
            require(clean.startsWith("https://")) { "Адрес архива — с https://" }
            require(t.length >= 32) { "Токен короткий — отсканируй QR из pair на компе" }
            post(clean, t, batch(emptyList()))
            val l = Link(clean, t, System.currentTimeMillis())
            StoreFiles.writeAtomic(linkFile, JSONObject().put("url", l.url).put("token", l.token).put("at", l.at).toString())
            _link.value = l
            log("архив: подключён $clean")
            l
        }
    }

    fun disconnect() {
        runCatching { linkFile.delete() }
        _link.value = null
        log("архив: отключён")
    }

    // ------------------------------------------------------------ когда

    /** Режим что-то записал: отправить через несколько секунд тишины, пачкой. */
    fun poke(domain: Domain) {
        if (_link.value == null) return
        synchronized(poked) { poked += domain }
        pokeJob?.cancel()
        pokeJob = scope.launch(Dispatchers.IO) {
            delay(POKE_QUIET_MS)
            val ds = synchronized(poked) { poked.toSet().also { poked.clear() } }
            runCatching { sync("правка", ds) }
        }
    }

    /** Тик службы: всё целиком — то, что не дошло раньше, и журналы Правки. */
    suspend fun tick() {
        if (_link.value == null) return
        sync("тик", Domain.entries.toSet())
    }

    /** «Отправить всё заново»: квитанции забыты, сервер сам отбросит то, что у него уже есть. */
    suspend fun resendAll() {
        mutex.withLock { withContext(Dispatchers.IO) { runCatching { ledgerFile.delete() } } }
        log("архив: отправляю всё заново")
        sync("всё заново", Domain.entries.toSet())
    }

    // ------------------------------------------------------------ проход

    suspend fun sync(why: String, domains: Set<Domain>): Unit = withContext(Dispatchers.IO) {
        val l = _link.value ?: return@withContext
        if (!mutex.tryLock()) return@withContext  // идёт проход — он заберёт и это
        try {
            _status.value = _status.value.copy(running = true)
            val ledger = Ledger.read(ledgerFile)
            val clock = ArchiveEvents.Clock()
            // Одно «когда» на проход: порядок внутри него держит seq.
            val at = clock.iso(System.currentTimeMillis())
            val events = mutableListOf<Pending>()
            for (d in domains) {
                val units = runCatching { sources.units(d, clock) }.getOrElse { e ->
                    log("архив: ${d.name.lowercase()} не собрался — ${e.javaClass.simpleName}: ${e.message}")
                    continue
                }
                events += diff(d, units, ledger, at)
            }
            // Метки журналов сдвигаются, только если сервер принял ВСЕ пачки прохода.
            val marks = mutableListOf<(Ledger) -> Unit>()
            if (Domain.PRAVKA in domains) events += pravkaLogs(clock, at, ledger, marks)

            var sent = 0
            // Куски журнала службы — в счёт, но не поводом для строки в журнал:
            // иначе каждый тик писал бы «отправлено 1» про собственную прошлую строку.
            var journalSent = 0
            var problem = ""
            for ((n, chunk) in chunks(events).withIndex()) {
                val reply = runCatching { post(l.url, l.token, batch(chunk.map { it.event })) }.getOrElse { e ->
                    problem = why(e)
                    break
                }
                val acked = reply.optJSONArray("acked")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
                val rejected = reply.optJSONArray("rejected")
                val rejectedIds = mutableSetOf<String>()
                if (rejected != null) for (i in 0 until rejected.length()) {
                    val r = rejected.optJSONObject(i) ?: continue
                    rejectedIds += r.optString("eid")
                    if (i < 3) log("архив: сервер не принял ${r.optString("eid")} — ${r.optString("why")}")
                }
                for (p in chunk) {
                    // Отвергнутое — тоже в квитанции: формат, повтор не поможет, а
                    // крутить его каждый проход значит спамить сервер и журнал.
                    if (p.event.optString("eid") in acked || p.event.optString("eid") in rejectedIds) p.commit(ledger)
                }
                sent += acked.size
                journalSent += chunk.count { it.event.optString("kind") == JOURNAL_KIND && it.event.optString("eid") in acked }
                ledger.sent += acked.size
                // Квитанции на диск не после каждой пачки: при первой заливке их
                // мегабайт, а оборвись проход — повтор сервер всё равно не удвоит.
                if (n % 5 == 4) ledger.write(ledgerFile)
            }
            val now = System.currentTimeMillis()
            if (problem.isEmpty()) {
                marks.forEach { it(ledger) }
                ledger.lastOk = now
                ledger.write(ledgerFile)
                _status.value = Status(lastOk = now, sent = ledger.sent, pending = 0,
                    note = if (sent > 0) "отправлено $sent" else "")
                if (sent > journalSent) log("архив: $why — отправлено $sent")
            } else {
                ledger.write(ledgerFile)
                _status.value = Status(lastOk = ledger.lastOk, lastError = problem, lastErrorAt = now,
                    sent = ledger.sent, pending = (events.size - sent).coerceAtLeast(0))
                log("архив: $why — $problem")
            }
        } finally {
            _status.value = _status.value.copy(running = false)
            mutex.unlock()
        }
    }

    /** Событие и то, что записать в квитанции, когда сервер его примет. */
    private class Pending(val event: JSONObject, val commit: (Ledger) -> Unit)

    private fun diff(d: Domain, units: List<ArchiveEvents.Item>, ledger: Ledger, at: String): List<Pending> {
        val out = mutableListOf<Pending>()
        val present = units.groupBy { it.kind }
        for (u in units) {
            val had = ledger.kinds[u.kind]?.get(u.key)
            if (had == u.hash) continue
            out += Pending(event(u.kind, u.key, "put", u.data, at)) { lg -> lg.kind(u.kind)[u.key] = u.hash }
        }
        for (kind in d.kinds) {
            if (kind !in DELETABLE) continue
            val known = ledger.kinds[kind] ?: continue
            val now = present[kind].orEmpty().map { it.key }.toSet()
            val gone = known.keys.filter { it !in now && known[it] != GONE }
            // Заслон, как у ленты: пустой стор или полстора поверх живых записей —
            // это поломка чтения, а не «владелец удалил всё». Удалений не шлём.
            if (gone.isEmpty()) continue
            if (now.isEmpty() || (gone.size > 20 && gone.size * 2 > known.size)) {
                log("архив: $kind — пропало ${gone.size} из ${known.size}, похоже на поломку чтения; удаления не отправляю")
                continue
            }
            for (key in gone) out += Pending(event(kind, key, "del", null, at)) { lg -> lg.kind(kind)[key] = GONE }
        }
        return out
    }

    /**
     * Журналы Правки: только новое с прошлого раза. Файл перевалил за 5 МБ —
     * он переименован в «.1» и начат заново; тогда дочитываем «.1» с прежней
     * метки и новый файл с начала. Квитанций по строкам нет — только метка:
     * журнал только дописывается, и тысячи отпечатков ему ни к чему.
     */
    private fun pravkaLogs(
        clock: ArchiveEvents.Clock,
        at: String,
        ledger: Ledger,
        marks: MutableList<(Ledger) -> Unit>,
    ): List<Pending> {
        val out = mutableListOf<Pending>()
        for ((name, build) in listOf<Pair<String, (JSONObject) -> ArchiveEvents.Item?>>(
            TranscriptionLog.FILE_NAME to { o -> ArchiveEvents.pravkaTake(o, clock) },
            HistoryLog.FILE_NAME to { o -> ArchiveEvents.pravkaClean(o, clock) },
        )) {
            for (line in tail(name, ledger, marks)) {
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val u = build(o) ?: continue
                out += Pending(event(u.kind, u.key, "put", u.data, at)) { }
            }
        }
        // Журнал службы — не JSON, а строки с меткой времени: кусками по
        // суткам (`ArchiveEvents.journal`). Нажатие кнопки гарнитуры, стоп и
        // чей он, отказ стека — всё, о чём иначе приходится спрашивать телефон.
        val journal = tail(EventLog.MAIN_FILE, ledger, marks)
        for (u in ArchiveEvents.journal(journal, clock, System.currentTimeMillis())) {
            out += Pending(event(u.kind, u.key, "put", u.data, at)) { }
        }
        return out
    }

    /**
     * Новые целые строки журнала [name] с прошлой метки (и хвост «.1», если
     * журнал с тех пор переложился). Метку сдвинет [marks], когда сервер
     * примет весь проход.
     */
    private fun tail(name: String, ledger: Ledger, marks: MutableList<(Ledger) -> Unit>): List<String> {
        val dir = DataRoot.dir(context)
        val main = File(dir, name)
        val rotated = File(dir, "$name.1")
        val from = ledger.offsets[name]
        val len = if (main.isFile) main.length() else 0L
        val parts = mutableListOf<Pair<File, Long>>()
        when {
            from == null -> { parts += rotated to 0L; parts += main to 0L }
            len < from -> { parts += rotated to from; parts += main to 0L }
            else -> parts += main to from
        }
        val out = mutableListOf<String>()
        var mark = from ?: 0L
        for ((f, startAt) in parts) {
            if (!f.isFile) continue
            val (lines, stop) = readLines(f, startAt)
            if (f == main) mark = stop
            out += lines
        }
        if (!main.isFile) mark = 0L
        marks += { lg -> lg.offsets[name] = mark }
        return out
    }

    /** Целые строки с [start] до конца; второе — где кончилась последняя целая. */
    private fun readLines(f: File, start: Long): Pair<List<String>, Long> {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (start >= len) return emptyList<String>() to len.coerceAtMost(start)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            val lastNl = bytes.lastIndexOf('\n'.code.toByte())
            if (lastNl < 0) return emptyList<String>() to start
            val text = String(bytes, 0, lastNl, Charsets.UTF_8)
            return text.split('\n').filter { it.isNotBlank() } to (start + lastNl + 1)
        }
    }

    // ------------------------------------------------------------ сеть

    private fun deviceId(): String {
        runCatching { deviceFile.readText().trim() }.getOrNull()?.takeIf { DEVICE_RE.matches(it) }?.let { return it }
        val base = (sources.profile ?: "user").lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "user" }
        val id = base.take(20) + "-" + java.util.UUID.randomUUID().toString().replace("-", "").take(6)
        StoreFiles.writeAtomic(deviceFile, id)
        return id
    }

    /**
     * Номер события растёт и через переустановку, и через восстановление базы
     * из копии: время в мс × 1000 плюс счётчик. Номер из счётчика в базе
     * после отката копии повторил бы уже принятые — и сервер счёл бы новые
     * события повтором.
     */
    @Synchronized
    private fun nextSeq(): Long {
        lastSeq = maxOf(lastSeq + 1, System.currentTimeMillis() * 1000)
        return lastSeq
    }

    private fun event(kind: String, key: String, op: String, data: JSONObject?, at: String): JSONObject {
        val seq = nextSeq()
        return JSONObject()
            .put("eid", "${deviceId()}:$seq")
            .put("seq", seq)
            .put("kind", kind)
            .put("key", key)
            .put("op", op)
            .put("at", at)
            .apply { if (data != null) put("data", data) }
    }

    private fun batch(events: List<JSONObject>): JSONObject = JSONObject()
        .put("device", deviceId())
        .put("profile", sources.profile ?: "")
        .put("app", sources.appVersion)
        .put("schema", 1)
        .put("events", JSONArray().apply { events.forEach { put(it) } })

    /** Пачки по счёту и по весу: первая заливка — тысячи записей. */
    private fun chunks(all: List<Pending>): List<List<Pending>> {
        val out = mutableListOf<List<Pending>>()
        var cur = mutableListOf<Pending>()
        var size = 0
        for (p in all) {
            val s = p.event.toString().length
            if (cur.isNotEmpty() && (cur.size >= BATCH_EVENTS || size + s > BATCH_BYTES)) {
                out += cur; cur = mutableListOf(); size = 0
            }
            cur += p; size += s
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun post(url: String, token: String, body: JSONObject): JSONObject {
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(raw) } }.toByteArray()
        val req = Request.Builder()
            .url(url + "ingest")
            .header("Authorization", "Bearer $token")
            .header("Content-Encoding", "gzip")
            .post(gz.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty().ifBlank { text.take(300) }
                throw ArchiveException(
                    when (r.code) {
                        401 -> "архив не принял токен — отсканируй QR из pair на компе заново"
                        403 -> "архив отказал: $msg"
                        else -> "архив ответил ${r.code}: $msg"
                    }
                )
            }
            return runCatching { JSONObject(text) }.getOrElse { throw ArchiveException("архив ответил не JSON: ${text.take(200)}") }
        }
    }

    private val client: OkHttpClient by lazy {
        http.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS).build()
    }

    class ArchiveException(message: String) : Exception(message)

    private fun why(e: Throwable): String = when (e) {
        is ArchiveException -> e.message.orEmpty()
        is java.net.UnknownHostException -> "архив: нет сети или адрес не находится (${e.message})"
        is java.net.ConnectException -> "архив не отвечает (${e.message})"
        is java.net.SocketTimeoutException -> "архив не ответил вовремя (${e.message})"
        is javax.net.ssl.SSLException -> "архив: не сложилось HTTPS-соединение (${e.message})"
        else -> "архив: ${e.javaClass.simpleName}: ${e.message}"
    }

    // ------------------------------------------------------------ квитанции

    /** Что сервер уже принял: вид → ключ → отпечаток снимка; журналы — докуда дочитаны. */
    internal class Ledger(
        val kinds: MutableMap<String, MutableMap<String, String>> = mutableMapOf(),
        val offsets: MutableMap<String, Long> = mutableMapOf(),
        var lastOk: Long = 0L,
        var sent: Long = 0L,
    ) {
        fun kind(k: String): MutableMap<String, String> = kinds.getOrPut(k) { mutableMapOf() }

        fun write(f: File) {
            val o = JSONObject().put("v", 1).put("lastOk", lastOk).put("sent", sent)
            o.put("kinds", JSONObject().apply { for ((k, m) in kinds) put(k, JSONObject(m as Map<*, *>)) })
            o.put("offsets", JSONObject().apply { for ((k, v) in offsets) put(k, v) })
            StoreFiles.writeAtomic(f, o.toString())
        }

        companion object {
            fun read(f: File): Ledger {
                if (!f.isFile) return Ledger()
                return runCatching {
                    val o = JSONObject(f.readText())
                    val l = Ledger(lastOk = o.optLong("lastOk"), sent = o.optLong("sent"))
                    o.optJSONObject("kinds")?.let { ks ->
                        for (k in ks.keys()) {
                            val m = ks.getJSONObject(k)
                            l.kinds[k] = m.keys().asSequence().associateWith { m.getString(it) }.toMutableMap()
                        }
                    }
                    o.optJSONObject("offsets")?.let { os -> for (k in os.keys()) l.offsets[k] = os.getLong(k) }
                    l
                }.getOrElse { Ledger() }  // не разобрался — пошлём всё заново, сервер не удвоит
            }
        }
    }

    companion object {
        const val LINK_FILE = "archive.json"
        const val DEVICE_FILE = "archive-device.txt"
        const val LEDGER_FILE = "archive-ledger.json"
        const val PAIR_PREFIX = "pravka-archive:"
        private const val GONE = "del"
        private const val JOURNAL_KIND = "pravka.journal"
        private const val POKE_QUIET_MS = 10_000L
        private const val BATCH_EVENTS = 400
        private const val BATCH_BYTES = 1_500_000
        private val DEVICE_RE = Regex("^[a-z0-9][a-z0-9-]{2,63}$")

        /** Виды, у которых пропажа записи — удаление. У остальных — срок хранения или журнал. */
        private val DELETABLE = setOf("zasechka.day", "food.meal", "strength.session", "strength.gtg")

        fun normalize(url: String): String {
            val u = url.trim()
            return "https://" + u.substringAfter("://", u).trimEnd('/') + "/"
        }

        /** QR из `pair`: «pravka-archive:{"url":…,"token":…}» → адрес и токен. */
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
