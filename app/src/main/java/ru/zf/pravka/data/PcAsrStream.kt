package ru.zf.pravka.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.zf.pravka.core.PcAsr
import ru.zf.pravka.core.PcAsr.Outcome
import ru.zf.pravka.provider.MicFeed
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Звук одного тейка «П» — на домашний комп по ходу записи (20.11.2026, `core/PcAsr.kt`,
 * договор — `server/contract/sluh.json`).
 *
 * Своя запись (`MicFeed`) отдаёт сюда те же куски, что пишет в файл тейка, на своём потоке —
 * поэтому [write] только кладёт в очередь. Отправляет свой поток: кусками по секунде
 * (`POST {архив}/asr/chunk?take=…&off=…`), по одному запросу за раз, с местом в звуке — повтор
 * после обрыва безвреден, а «у меня столько-то» (409) от сервера сдвигает отправку туда, откуда
 * ему нужно. Запись закрылась целой — досылается хвост и спрашивается итог (`/asr/end`).
 *
 * Чего здесь нет нарочно: ожидания на стороне записи и повторов дольше нескольких секунд. Комп —
 * не единственная дорога: всё, что не вышло, — сразу «Google» ([Outcome.UNAVAILABLE] или
 * [Outcome.INCOMPLETE]), и владелец ничего не ждёт. Ждёт он только сам итог — не дольше
 * [PcAsr.WAIT_MS] после итога Google ([await]).
 */
class PcAsrStream(
    http: OkHttpClient,
    archiveUrl: String,
    private val token: String,
    val takeId: String,
    private val log: (String) -> Unit,
    // Часы замера (`elapsedRealtime` телефона); в тестах — свои.
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : MicFeed.Tee {

    /**
     * Строка компа из ответа на кусок (20.11.2026): что пришло, когда ([Long] — по [clock]) и когда
     * прозвучал звук, до которого она досчитана. Зовётся на потоке отправки — не ждать в нём.
     */
    @Volatile var onLive: ((live: PcAsr.Live, arrivedAt: Long, audioAt: Long) -> Unit)? = null

    /** Замер тейка (`core/TakeTiming.kt`): каждый кусок — круг и счёт компа. */
    @Volatile var timing: ru.zf.pravka.core.TakeTiming? = null

    /** Когда пришёл первый звук своей записи — нулевая секунда звука тейка по [clock]. */
    @Volatile private var firstWriteAt = 0L

    /** Комп хоть раз принял кусок: дорога есть (строка, что отстала, — это «застряла», а не «нет дома»). */
    val reachedHome: Boolean get() = synchronized(lock) { reached && broken?.outcome != Outcome.UNAVAILABLE }

    /** Отправка ещё жива: не бросила тейк. false — строки компа больше не будет, показывать Google. */
    val alive: Boolean get() = synchronized(lock) { broken == null }

    private val base = archiveUrl.trimEnd('/') + "/"
    private val client = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // end ждёт на сервере недостающий кусок до 5 с и считает последнюю область.
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val lock = Object()
    /** Ещё не принятое сервером: байты с места [pendingFrom] в звуке тейка. */
    private var pending = ByteArray(64 * 1024)
    private var pendingLen = 0
    private var pendingFrom = 0L
    /** Сколько звука отдала запись всего. */
    private var written = 0L
    private var closed = false
    private var complete = false
    /** Отправка бросила тейк: почему (словами) и чем это считать. */
    private var broken: PcAsr.Result? = null
    /** Сервер хоть раз принял кусок — дорога до компа есть. */
    private var reached = false
    private var sender: Thread? = null

    private val result = CompletableDeferred<PcAsr.Result>()

    /** Получал ли поток звук вообще: нет — своей записи не было, и компу нечего считать. */
    val attached: Boolean get() = synchronized(lock) { written > 0 || closed }

    // ------------------------------------------------------------ сторона записи

    override fun write(pcm: ByteArray, n: Int) {
        if (n <= 0) return
        if (firstWriteAt == 0L) firstWriteAt = clock()
        synchronized(lock) {
            if (closed || broken != null) return
            if (pendingLen + n > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + n))
            System.arraycopy(pcm, 0, pending, pendingLen, n)
            pendingLen += n
            written += n
            if (pendingLen > PcAsr.BEHIND_MAX_BYTES) {
                breakWith(Outcome.INCOMPLETE, "отправка отстала на ${pendingLen / PcAsr.BYTES_PER_SEC} с звука — связь не тянет")
            }
            lock.notifyAll()
            if (sender == null) sender = thread(name = "pravka-pc-asr", isDaemon = true) { sendLoop() }
        }
    }

    override fun close(complete: Boolean) {
        synchronized(lock) {
            if (closed) return
            closed = true
            this.complete = complete
            if (!complete && broken == null) breakWith(Outcome.INCOMPLETE, "своя запись закрылась раньше стопа — звук неполный")
            if (written == 0L && broken == null) breakWith(Outcome.UNAVAILABLE, "своя запись не дала звука")
            lock.notifyAll()
            if (sender == null && broken == null) sender = thread(name = "pravka-pc-asr", isDaemon = true) { sendLoop() }
        }
    }

    /** Отмена тейка: итог не нужен, звук на компе — тоже. */
    fun cancel() {
        synchronized(lock) {
            if (broken == null) breakWith(Outcome.CANCELLED, "тейк отменён")
            lock.notifyAll()
        }
        thread(name = "pravka-pc-asr-drop", isDaemon = true) {
            runCatching { post("asr/drop?take=$takeId", ByteArray(0)) }
        }
    }

    /**
     * Итог компа: ждать не дольше [budgetMs]. Не дождался — [Outcome.STUCK]; своей записи не
     * было — [Outcome.UNAVAILABLE] сразу.
     */
    suspend fun await(budgetMs: Long = PcAsr.WAIT_MS): PcAsr.Result {
        if (!attached) return PcAsr.Result(Outcome.UNAVAILABLE, note = "своей записи не было — звука для компа нет")
        return withTimeoutOrNull(budgetMs) { result.await() }
            ?: PcAsr.Result(Outcome.STUCK, note = "комп не ответил за ${budgetMs / 1000.0} с".replace('.', ','))
    }

    // ------------------------------------------------------------ отправка

    private fun breakWith(outcome: Outcome, why: String) {
        // Зовётся под lock.
        if (broken != null) return
        val r = PcAsr.Result(outcome, note = why)
        broken = r
        pendingLen = 0
        result.complete(r)
    }

    private fun sendLoop() {
        try {
            while (true) {
                val off: Long
                val piece: ByteArray
                synchronized(lock) {
                    while (broken == null && !closed && pendingLen < PcAsr.CHUNK_BYTES) lock.wait()
                    if (broken != null) return
                    if (pendingLen == 0) {
                        // Всё отправлено и запись закрыта целой — пора за итогом.
                        off = -1
                        piece = ByteArray(0)
                    } else {
                        // Отстали — шлём больше секунды разом (до 2 МБ договора — с запасом).
                        val take = minOf(pendingLen, PcAsr.CHUNK_BYTES * 4)
                        off = pendingFrom
                        piece = pending.copyOf(take)
                    }
                }
                if (off < 0) {
                    end()
                    return
                }
                val have = sendChunk(off, piece) ?: return
                synchronized(lock) {
                    reached = true
                    // Сервер держит [have] байт: всё до этого места из очереди уходит.
                    val drop = (have - pendingFrom).coerceIn(0L, pendingLen.toLong()).toInt()
                    if (have < pendingFrom) {
                        breakWith(Outcome.INCOMPLETE, "комп потерял звук тейка (у него $have байт из $pendingFrom)")
                        return
                    }
                    if (drop > 0) {
                        System.arraycopy(pending, drop, pending, 0, pendingLen - drop)
                        pendingLen -= drop
                        pendingFrom += drop
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            synchronized(lock) { breakWith(Outcome.UNAVAILABLE, "отправка упала: ${e.javaClass.simpleName}") }
        }
    }

    /** Кусок на комп с повтором при обрыве; ответ — сколько звука у сервера, null — тейк брошен. */
    private fun sendChunk(off: Long, piece: ByteArray): Long? {
        var lastWhy = ""
        for (attempt in 0 until RETRIES) {
            if (attempt > 0) {
                timing?.retry()
                Thread.sleep(BACKOFF_MS[attempt - 1])
            }
            synchronized(lock) { if (broken != null) return null }
            try {
                val sentAt = clock()
                val (code, body) = post("asr/chunk?take=$takeId&off=$off", piece)
                val now = clock()
                if (code == 200) {
                    timing?.chunk(now - sentAt, body.optLong("ms", -1))
                    body.optJSONObject("live")?.let { l -> deliverLive(l, now) }
                }
                when (code) {
                    200, 409 -> return body.optLong("have", -1).takeIf { it >= 0 }
                        ?: run { give(Outcome.UNAVAILABLE, "комп ответил $code без have"); null }
                    503 -> lastWhy = body.optString("error").ifBlank { "Слух не отвечает" }
                    else -> {
                        give(Outcome.UNAVAILABLE, "комп отказал ($code): ${body.optString("error").take(120)}")
                        return null
                    }
                }
            } catch (e: IOException) {
                lastWhy = "нет связи с домом: ${e.javaClass.simpleName}"
            }
        }
        give(if (reachedNow()) Outcome.INCOMPLETE else Outcome.UNAVAILABLE, lastWhy)
        return null
    }

    private fun reachedNow(): Boolean = synchronized(lock) { reached }

    private fun deliverLive(l: JSONObject, arrivedAt: Long) {
        val live = PcAsr.Live(l.optString("firm"), l.optString("tent"), l.optDouble("at", 0.0), l.optLong("ms", -1))
        val audioAt = if (firstWriteAt > 0L) firstWriteAt + (live.audioSec * 1000).toLong() else 0L
        synchronized(lock) { if (broken != null) return }
        onLive?.let { sink -> runCatching { sink(live, arrivedAt, audioAt) } }
    }

    private fun give(outcome: Outcome, why: String) {
        synchronized(lock) { breakWith(outcome, why) }
    }

    /** Итог: «у меня весь звук, считай» — текст или почему нет. */
    private fun end() {
        val total = synchronized(lock) { written }
        val t0 = System.currentTimeMillis()
        for (attempt in 0 until RETRIES) {
            if (attempt > 0) Thread.sleep(BACKOFF_MS[attempt - 1])
            try {
                val (code, body) = post("asr/end?take=$takeId&total=$total", ByteArray(0))
                when (code) {
                    200 -> {
                        val ms = System.currentTimeMillis() - t0
                        val r = PcAsr.Result(
                            Outcome.OK,
                            text = body.optString("text"),
                            note = "комп: ${body.optDouble("sec", 0.0).let { "%.1f".format(it) }} с звука, " +
                                "итог $ms мс (считал ${body.optLong("ms", -1)} мс)",
                            serverMs = body.optLong("ms", -1),
                        )
                        result.complete(r)
                        return
                    }
                    409 -> {
                        give(Outcome.INCOMPLETE, "до компа дошло ${body.optLong("have")} байт из $total")
                        return
                    }
                    503 -> Unit
                    else -> {
                        give(Outcome.UNAVAILABLE, "комп отказал в итоге ($code): ${body.optString("error").take(120)}")
                        return
                    }
                }
            } catch (_: IOException) {
            }
        }
        give(Outcome.UNAVAILABLE, "итог с компа не пришёл — нет связи")
    }

    private fun post(path: String, body: ByteArray): Pair<Int, JSONObject> {
        val req = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(OCTET))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return r.code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        }
    }

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private const val RETRIES = 3
        private val BACKOFF_MS = longArrayOf(300, 800)
    }
}
