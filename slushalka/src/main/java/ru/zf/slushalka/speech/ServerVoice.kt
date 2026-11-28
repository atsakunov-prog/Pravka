package ru.zf.slushalka.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.data.Settings
import java.io.IOException
import java.net.URI
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Голос сервера: книгу читает голос с полки озвучки (Авраам, Ира, Джейн…) на
 * домашней видеокарте, звук приходит потоком (28.11.2026).
 *
 * Владелец хотел: «нажимаю на абзац, "Озвучить" - быстро первое предложение,
 * потом всё остальное». Сервер (ZF-Night, `live.py`) начинает говорить через
 * ~1,3 секунды, если модель уже на карте, ~2,5 - если лежала в памяти, и
 * читает вдвое быстрее речи, так что пауз нет и на 1,5×.
 *
 * Протокол - дверь панели «Книги» `/api/golos/…`, вход тем же логином и
 * паролем, что у облака книг. Абзацы уходят на сервер кусками по ходу
 * чтения (`start`, потом `more`), звук забирается долгими запросами `pkt`:
 * сырые 16 бит 24 кГц моно, к каждому пакету - какой абзац и какие знаки в
 * нём звучат (`X-Map`). Звук ложится в [AudioTrack] - с ним темп меняется без
 * перезапуска, а позиция воспроизведения точно говорит, какой кусок слышно.
 *
 * Адрес двери - из адреса облака книг (`books.…` → `knigi.…`), в коде адресов
 * нет: репозиторий публичный. Другой адрес можно вписать в настройках.
 *
 * Одна сессия - один [start]; пауза и перемотка - новая сессия с нужного
 * места (сервер бросает старую сам через минуту тишины).
 */
class ServerVoice(private val settings: Settings) {

    interface Listener {
        /** Слышно абзац [item] (номер в отданном списке), знаки [from]..[to] внутри его текста. */
        fun onPlaying(item: Int, from: Int, to: Int)
        fun onFinished()
        /** [retry] - сбой связи: стоит начать заново с того же места; иначе - читать другим голосом. */
        fun onFailed(message: String, retry: Boolean)
        /** Сервер готовит голос (первое слово ещё не пришло) - для строки «Готовлю голос…». */
        fun onPreparing(on: Boolean)
    }

    /** Абзацы по запросу: null - книга кончилась. */
    fun interface Items {
        fun at(i: Int): String?
    }

    data class VoiceInfo(val id: String, val name: String)

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var run: Run? = null

    // --------------------------------------------------------------- адрес

    /** Дверь голоса сервера или null - облако не наше (не `books.…`) и адреса в настройках нет. */
    fun baseUrl(p: Settings.Prefs = settings.now()): String? {
        p.ttsServerUrl.trim().takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        if (!p.cloudReady) return null
        val u = runCatching { URI(p.cloudUrl.trim()) }.getOrNull() ?: return null
        val host = u.host ?: return null
        if (!host.startsWith("books.")) return null
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme ?: "https"}://knigi.${host.removePrefix("books.")}$port/api/golos"
    }

    fun available(p: Settings.Prefs = settings.now()): Boolean = p.ttsServer && baseUrl(p) != null

    private fun auth(p: Settings.Prefs): String =
        "Basic " + Base64.encodeToString("${p.cloudUser}:${p.cloudPass}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun request(path: String, p: Settings.Prefs, body: JSONObject? = null): Request {
        val b = Request.Builder().url(baseUrl(p) + path).header("Authorization", auth(p))
        if (body != null) b.post(body.toString().toRequestBody(JSON))
        return b.build()
    }

    // ---------------------------------------------------- голоса и прогрев

    /** Голоса полки сервера (для настроек). Пустой список - сервер не ответил. Звать не на главном потоке. */
    fun voices(): Pair<List<VoiceInfo>, String> {
        val p = settings.now()
        if (baseUrl(p) == null) return emptyList<VoiceInfo>() to ""
        return runCatching {
            http.newCall(request("/voices", p)).execute().use { r ->
                if (!r.isSuccessful) return emptyList<VoiceInfo>() to ""
                val o = JSONObject(r.body?.string().orEmpty())
                val arr = o.optJSONArray("voices") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { VoiceInfo(it.optString("id"), it.optString("имя", it.optString("id"))) } to
                    o.optString("default")
            }
        }.getOrDefault(emptyList<VoiceInfo>() to "")
    }

    /** Модель - в память сервера заранее (книга без записи открыта в читалке): первое нажатие без 20 секунд ожидания. */
    fun warm() {
        val p = settings.now()
        if (!available(p)) return
        Thread {
            runCatching {
                http.newCall(request("/warm", p, JSONObject().put("voice", p.ttsServerVoice))).execute().close()
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------- чтение

    fun start(items: Items, rate: Float, listener: Listener) {
        stop()
        val r = Run(items, rate, listener)
        run = r
        r.begin()
    }

    fun stop() {
        run?.end()
        run = null
    }

    fun setRate(rate: Float) {
        run?.changeRate(rate)
    }

    private class Packet(val pcm: ByteArray, val item: Int, val c0: Int, val c1: Int)

    private class Mark(val frame: Long, val item: Int, val c0: Int, val c1: Int)

    /** Одна сессия: поток сети, поток звука, тикер позиции на главном. */
    private inner class Run(val items: Items, var rate: Float, val listener: Listener) {
        val dead = AtomicBoolean(false)
        val queue = LinkedBlockingDeque<Packet>()
        val marks = ArrayList<Mark>()
        @Volatile var framesWritten = 0L
        @Volatile var endReceived = false
        @Volatile var writerDone = false
        @Volatile var queuedSec = 0.0
        var track: AudioTrack? = null
        var sid: String? = null
        var sent = 0                    // абзацев отдано серверу
        @Volatile var playingItem = 0
        var lastShown: Mark? = null
        var preparing = true

        fun begin() {
            listener.onPreparing(true)
            Thread({ network() }, "golos-net").apply { isDaemon = true }.start()
            main.postDelayed(ticker, TICK_MS)
        }

        fun end() {
            if (!dead.compareAndSet(false, true)) return
            main.removeCallbacks(ticker)
            val id = sid
            val p = settings.now()
            if (id != null) Thread {
                runCatching { http.newCall(request("/stop", p, JSONObject().put("id", id))).execute().close() }
            }.apply { isDaemon = true }.start()
            queue.clear()
            queue.offer(POISON)
            track?.let { t -> runCatching { t.pause(); t.flush(); t.release() } }
            track = null
        }

        fun changeRate(r: Float) {
            rate = r
            track?.let { t -> runCatching { t.playbackParams = PlaybackParams().setSpeed(r).setPitch(1f) } }
        }

        private fun fail(msg: String, retry: Boolean) {
            if (dead.get()) return
            main.post {
                if (dead.get()) return@post
                end()
                listener.onFailed(msg, retry)
            }
        }

        /** Следующие абзацы: не больше [maxItems] и примерно [maxChars] знаков, хотя бы один. */
        private fun batch(maxItems: Int, maxChars: Int): Pair<JSONArray, Boolean> {
            val arr = JSONArray()
            var chars = 0
            while (arr.length() < maxItems && (arr.length() == 0 || chars < maxChars)) {
                val t = items.at(sent) ?: return arr to true
                arr.put(t)
                chars += t.length
                sent++
            }
            return arr to (items.at(sent) == null)
        }

        private fun errorOf(body: String, code: Int): String =
            runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "сервер ответил $code"

        private fun network() {
            val p = settings.now()
            // первый раз - коротко: на сервере текст готовится перед первым словом
            val (first, last0) = batch(maxItems = 3, maxChars = 1200)
            val startBody = JSONObject().put("items", first).put("final", last0)
            if (p.ttsServerVoice.isNotBlank()) startBody.put("voice", p.ttsServerVoice)
            try {
                http.newCall(request("/start", p, startBody)).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        return fail(errorOf(body, r.code), retry = false)
                    }
                    sid = JSONObject(body).getString("id")
                }
            } catch (e: IOException) {
                return fail("нет связи с сервером", retry = false)
            }
            var finalSent = last0
            Thread({ writer() }, "golos-audio").apply { isDaemon = true }.start()
            var k = 0
            var misses = 0
            while (!dead.get()) {
                // Держим на сервере запас абзацев: чтобы он не ждал текста, но и не читал книгу впрок.
                if (!finalSent && sent - playingItem < AHEAD_ITEMS) {
                    val (more, last) = batch(maxItems = 12, maxChars = 6000)
                    try {
                        http.newCall(request("/more", p, JSONObject().put("id", sid).put("items", more).put("final", last)))
                            .execute().close()
                        finalSent = last
                    } catch (e: IOException) {
                        sent -= more.length()           // отдадим снова на следующем круге
                    }
                }
                // Своего запаса звука хватает - не тянем лишнего: сервер держит остальное.
                if (queuedSec > BUFFER_SEC) {
                    Thread.sleep(300)
                    continue
                }
                try {
                    http.newCall(request("/pkt?id=$sid&from=$k&wait=15", p)).execute().use { r ->
                        when (r.code) {
                            204 -> Unit
                            200 -> {
                                val bytes = r.body?.bytes() ?: ByteArray(0)
                                val map = r.header("X-Map").orEmpty()
                                var off = 0
                                if (map.isNotBlank()) for (part in map.split(';')) {
                                    val f = part.split(',').map { it.toIntOrNull() ?: 0 }
                                    if (f.size < 5) continue
                                    val n = f[4].coerceAtMost(bytes.size - off)
                                    if (n > 0) {
                                        queue.offer(Packet(bytes.copyOfRange(off, off + n), f[1], f[2], f[3]))
                                        queuedSec += n / BYTES_PER_SEC
                                    }
                                    off += n
                                    k++
                                }
                                if (r.header("X-End") == "1") {
                                    endReceived = true
                                    queue.offer(POISON)
                                    return
                                }
                            }
                            404 -> return fail("сеанс на сервере закрылся", retry = true)
                            else -> return fail(errorOf(r.body?.string().orEmpty(), r.code), retry = false)
                        }
                    }
                    misses = 0
                } catch (e: IOException) {
                    if (dead.get()) return
                    if (++misses >= 4) return fail("пропала связь с сервером", retry = true)
                    Thread.sleep(800L * misses)
                }
            }
        }

        private fun writer() {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf * 4, (BYTES_PER_SEC * 2).toInt()))
                    .build()
            }.getOrNull()
            if (t == null) return fail("телефон не открыл звук", retry = false)
            if (dead.get()) {
                runCatching { t.release() }
                return
            }
            track = t
            runCatching { t.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f) }
            var playing = false
            while (!dead.get()) {
                val pk = queue.take()
                if (pk === POISON) break
                queuedSec -= pk.pcm.size / BYTES_PER_SEC
                synchronized(marks) { marks.add(Mark(framesWritten, pk.item, pk.c0, pk.c1)) }
                var off = 0
                while (off < pk.pcm.size && !dead.get()) {
                    val n = t.write(pk.pcm, off, pk.pcm.size - off)
                    if (n <= 0) break
                    off += n
                    if (!playing) {
                        runCatching { t.play() }
                        playing = true
                    }
                }
                framesWritten += pk.pcm.size / 2
            }
            writerDone = true
            if (dead.get()) runCatching { t.release() }     // сеанс закрыли, пока звук только открывался
        }

        /** Что слышно сейчас: подсветка, место чтения, конец. */
        private val ticker: Runnable = object : Runnable {
            override fun run() {
                if (dead.get()) return
                val t = track
                val head = t?.let { runCatching { it.playbackHeadPosition.toLong() and 0xffffffffL }.getOrNull() } ?: 0L
                val m = synchronized(marks) {
                    // старое уже прозвучало - список не растёт бесконечно
                    while (marks.size > 2 && marks[1].frame <= head) marks.removeAt(0)
                    marks.lastOrNull { it.frame <= head }
                }
                if (m != null && head > 0) {
                    if (preparing) {
                        preparing = false
                        listener.onPreparing(false)
                    }
                    playingItem = m.item
                    if (m !== lastShown) {
                        lastShown = m
                        listener.onPlaying(m.item, m.c0, m.c1)
                    }
                }
                if (endReceived && writerDone && head >= framesWritten) {
                    end()
                    listener.onFinished()
                    return
                }
                main.postDelayed(this, TICK_MS)
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val POISON = Packet(ByteArray(0), -1, 0, 0)
        const val RATE = 24_000
        private const val BYTES_PER_SEC = RATE * 2.0
        private const val TICK_MS = 150L
        /** Своего звука впрок, секунд: дальше копит сервер (до двух минут). */
        private const val BUFFER_SEC = 45.0
        /** Абзацев на сервере впереди слышимого. */
        private const val AHEAD_ITEMS = 8
    }
}
