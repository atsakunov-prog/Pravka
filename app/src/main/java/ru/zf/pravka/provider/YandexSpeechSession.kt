package ru.zf.pravka.provider

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

// Live streaming recognition via Yandex SpeechKit v3 (cloud, realtime). We own
// the microphone here (AudioRecord) and stream raw 16 kHz PCM to Yandex over a
// bidirectional gRPC call, carried on an OkHttp HTTP/2 duplex request - no
// grpc/protoc dependency, just hand-framed protobuf (see Proto). Recognized
// text comes back on the same stream; finals are stitched together and
// delivered when the owner stops.
class YandexSpeechSession(
    private val apiKey: String,
    private val folderId: String,
) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var capturing = false
    private var audioThread: Thread? = null
    private var call: okhttp3.Call? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val poison = ByteArray(0)

    private val finals = ArrayList<String>()
    @Volatile private var lastPartial = ""
    @Volatile private var delivered = false

    private var onPartial: (String) -> Unit = {}
    private var onCheckpoint: (String) -> Unit = {}
    private var onDone: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var onLog: (String) -> Unit = {}

    companion object {
        private const val URL =
            "https://stt.api.cloud.yandex.net/yandex.cloud.ai.stt.v3.Recognizer/RecognizeStreaming"
        private const val SAMPLE_RATE = 16_000

        // No read/write/call timeouts: a dictation can run for minutes. h2 ping
        // keeps the connection healthy.
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }

    fun start(
        onPartial: (String) -> Unit,
        onCheckpoint: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onLog: (String) -> Unit = {},
    ) {
        this.onPartial = onPartial
        this.onCheckpoint = onCheckpoint
        this.onDone = onDone
        this.onError = onError
        this.onLog = onLog
        if (apiKey.isBlank() || folderId.isBlank()) {
            postError("Не заданы API-ключ и Folder ID Яндекса (Настройки → Распознавание речи).")
            return
        }
        capturing = true
        onLog("yandex start")
        startCall()
        startAudio()
    }

    fun stop() {
        // Stop capture; the audio thread pushes a poison pill, writeTo returns
        // and half-closes the request, Yandex flushes its final results, then
        // the response loop ends and we deliver.
        capturing = false
        onLog("yandex stop requested")
        main.postDelayed({ if (!delivered) finish() }, 4000)  // safety net
    }

    // ---- audio capture ----

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked before we start
    private fun startAudio() {
        audioThread = thread(name = "pravka-yandex-mic") {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                queue.offer(poison)  // unblock the request writer
                postError("Не удалось открыть микрофон.")
                return@thread
            }
            recorder.startRecording()
            val buf = ByteArray(3200)  // ~100 ms at 16 kHz mono PCM16
            while (capturing) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) queue.offer(buf.copyOf(n))
            }
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            queue.offer(poison)  // signal writeTo to half-close
        }
    }

    // ---- gRPC call over OkHttp duplex ----

    private fun startCall() {
        val body = object : RequestBody() {
            override fun contentType() = "application/grpc".toMediaType()
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) {
                runCatching {
                    sink.write(grpcFrame(sessionOptions())); sink.flush()
                    while (true) {
                        val chunk = queue.take()
                        if (chunk === poison || chunk.isEmpty()) break
                        sink.write(grpcFrame(chunkRequest(chunk))); sink.flush()
                    }
                }
                // returning half-closes the request stream
            }
        }
        val request = Request.Builder()
            .url(URL)
            .addHeader("authorization", "Api-Key $apiKey")
            .addHeader("x-folder-id", folderId)
            .addHeader("te", "trailers")
            .post(body)
            .build()
        call = client.newCall(request).also {
            it.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    onLog("yandex http failure: ${e.message}")
                    if (finals.isEmpty()) postError("Сеть/Яндекс: ${e.message}") else finish()
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    readResponses(response)
                }
            })
        }
    }

    private fun readResponses(response: Response) {
        response.use { resp ->
            if (!resp.isSuccessful) {
                onLog("yandex http ${resp.code}")
                postError("Яндекс вернул HTTP ${resp.code}")
                return
            }
            val source = resp.body?.source()
            if (source == null) { postError("Пустой ответ Яндекса"); return }
            runCatching {
                while (true) {
                    if (source.exhausted()) break
                    source.readByte()                 // compression flag
                    val len = source.readInt().toLong()  // big-endian length
                    val msg = source.readByteArray(len)
                    handleResponseMessage(msg)
                }
            }.onFailure { onLog("yandex read end: ${it.message}") }
            // gRPC status lives in the trailers.
            val status = runCatching { resp.trailers()["grpc-status"] }.getOrNull()
            val gmsg = runCatching { resp.trailers()["grpc-message"] }.getOrNull()
            onLog("yandex done grpc-status=$status")
            if (status != null && status != "0" && finals.isEmpty()) {
                postError("Яндекс: ${gmsg ?: "код $status"}")
            } else {
                finish()
            }
        }
    }

    // StreamingResponse: field 4 partial, 5 final (AlternativeUpdate),
    // 7 final_refinement (FinalRefinement{ normalized_text=2 : AlternativeUpdate }).
    private fun handleResponseMessage(msg: ByteArray) {
        val r = Proto.Reader(msg)
        while (true) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 4 && wire == 2 -> {
                    lastPartial = textOfAlternativeUpdate(r.readLenBytes())
                    postPartial()
                }
                field == 5 && wire == 2 -> {
                    val t = textOfAlternativeUpdate(r.readLenBytes())
                    if (t.isNotBlank()) { finals.add(t); lastPartial = ""; postCheckpoint() }
                }
                field == 7 && wire == 2 -> {
                    val t = textOfFinalRefinement(r.readLenBytes())
                    if (t.isNotBlank()) {
                        if (finals.isNotEmpty()) finals[finals.size - 1] = t else finals.add(t)
                        postCheckpoint()
                    }
                }
                else -> r.skip(wire)
            }
        }
    }

    private fun textOfAlternativeUpdate(bytes: ByteArray): String {
        // AlternativeUpdate: repeated Alternative alternatives = 1.
        val r = Proto.Reader(bytes)
        val sb = StringBuilder()
        while (true) {
            val (field, wire) = r.readTag() ?: break
            if (field == 1 && wire == 2) {
                val t = textOfAlternative(r.readLenBytes())
                if (t.isNotBlank()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(t) }
            } else r.skip(wire)
        }
        return sb.toString()
    }

    private fun textOfAlternative(bytes: ByteArray): String {
        // Alternative: string text = 2.
        val r = Proto.Reader(bytes)
        while (true) {
            val (field, wire) = r.readTag() ?: break
            if (field == 2 && wire == 2) return r.readString()
            r.skip(wire)
        }
        return ""
    }

    private fun textOfFinalRefinement(bytes: ByteArray): String {
        // FinalRefinement: AlternativeUpdate normalized_text = 2.
        val r = Proto.Reader(bytes)
        while (true) {
            val (field, wire) = r.readTag() ?: break
            if (field == 2 && wire == 2) return textOfAlternativeUpdate(r.readLenBytes())
            r.skip(wire)
        }
        return ""
    }

    // ---- request message builders ----

    private fun sessionOptions(): ByteArray {
        val rawAudio = Proto.message {
            Proto.varintField(it, 1, 1)              // audio_encoding = LINEAR16_PCM
            Proto.varintField(it, 2, SAMPLE_RATE.toLong())
            Proto.varintField(it, 3, 1)              // channels
        }
        val audioFormat = Proto.message { Proto.messageField(it, 1, rawAudio) }  // raw_audio
        val textNorm = Proto.message { Proto.varintField(it, 1, 1) }             // TEXT_NORMALIZATION_ENABLED
        val langRestriction = Proto.message {
            Proto.varintField(it, 1, 1)              // restriction_type = WHITELIST
            Proto.stringField(it, 2, "ru-RU")        // language_code
        }
        val recognitionModel = Proto.message {
            Proto.stringField(it, 1, "general")      // model
            Proto.messageField(it, 2, audioFormat)
            Proto.messageField(it, 3, textNorm)
            Proto.messageField(it, 4, langRestriction)
            Proto.varintField(it, 5, 1)              // audio_processing_type = REAL_TIME
        }
        val streamingOptions = Proto.message { Proto.messageField(it, 1, recognitionModel) }
        // StreamingRequest { session_options = 1 }
        return Proto.message { Proto.messageField(it, 1, streamingOptions) }
    }

    private fun chunkRequest(pcm: ByteArray): ByteArray {
        val audioChunk = Proto.message { Proto.lenField(it, 1, pcm) }  // data
        // StreamingRequest { chunk = 2 }
        return Proto.message { Proto.messageField(it, 2, audioChunk) }
    }

    private fun grpcFrame(msg: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(5 + msg.size)
        out.write(0)                                 // no compression
        out.write((msg.size ushr 24) and 0xFF)
        out.write((msg.size ushr 16) and 0xFF)
        out.write((msg.size ushr 8) and 0xFF)
        out.write(msg.size and 0xFF)
        out.write(msg)
        return out.toByteArray()
    }

    // ---- delivery (all outward callbacks hop to the main thread) ----

    private fun joined(): String = finals.joinToString(" ").trim()

    private fun postPartial() {
        val live = (joined() + " " + lastPartial).trim()
        main.post { onPartial(live) }
    }

    private fun postCheckpoint() {
        val text = joined()
        main.post { onCheckpoint(text); onPartial(text) }
    }

    private fun postError(message: String) {
        capturing = false
        queue.offer(poison)
        runCatching { call?.cancel() }
        main.post { if (!delivered) { delivered = true; onError(message) } }
    }

    private fun finish() {
        capturing = false
        runCatching { call?.cancel() }
        val text = joined()
        main.post {
            if (delivered) return@post
            delivered = true
            onLog("yandex finish len=${text.length}")
            onDone(text)
        }
    }
}
