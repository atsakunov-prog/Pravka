package ru.zf.pravka.provider

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread
import ru.zf.pravka.data.TakeAudio

/**
 * Разобрать сохранённый звук тейка заново (владелец, 30.09.2026: «если на него
 * нажимаешь, то просто ещё раз разбирается эта фраза, и всё»).
 *
 * Тем же распознавателем Google и той же дорогой, что живой тейк со своей
 * записью (`MicFeed`): звук идёт ему трубой `EXTRA_AUDIO_SOURCE`, язык,
 * подсказки словаря и путь (сеть или офлайн-пакет) — те же
 * (`GoogleSpeechSession.recognizeIntent`). Сессия непрерывная до конца трубы:
 * файл кончился — труба закрыта — распознаватель дочитывает и отдаёт итог.
 *
 * Звук подаётся не быстрее [MAX_SPEED] реального времени: быстрее
 * распознаватель, живущий в реальном времени, может не переварить, а
 * двухминутный тейк разбирается так за полминуты. Сторож ([guardMs]) — на
 * случай, если распознаватель замолчал и не кончил сессию: отдаём, что есть.
 *
 * Всё с распознавателем — на главном потоке, как требует `SpeechRecognizer`;
 * чтение файла и запись в трубу — на своём.
 */
class SpeechReplay(
    private val context: Context,
    private val file: File,
    private val network: Boolean,
    private val biasing: List<String>,
    private val formatting: Boolean,
    private val log: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var readEnd: ParcelFileDescriptor? = null
    @Volatile private var stopped = false
    private var done = false
    private val text = StringBuilder()
    private var lastPartial = ""
    private var onProgress: (Float) -> Unit = {}
    private var onDone: (Result<String>) -> Unit = {}
    private var startedAt = 0L

    /** Длина звука в файле, мс. */
    val audioMs: Long = ru.zf.pravka.data.WavFile.durationMs(file)

    /** Сколько шёл разбор, мс. */
    val elapsedMs: Long get() = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    /**
     * Начать. [onProgress] — доля поданного звука 0..1; [onDone] — итог один
     * раз: текст (может быть пустым — «ничего не разобрал») или ошибка словами.
     * Оба — на главном потоке.
     */
    fun start(onProgress: (Float) -> Unit = {}, onDone: (Result<String>) -> Unit) {
        this.onProgress = onProgress
        this.onDone = onDone
        main.post { begin() }
    }

    /** Бросить: закрыть трубу и распознаватель, итога не будет. */
    fun cancel() {
        main.post {
            done = true
            teardown()
        }
    }

    private fun begin() {
        startedAt = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            fail("Разобрать заново можно с Android 13: раньше распознаватель не берёт звук из файла")
            return
        }
        if (audioMs <= 0L) {
            fail("В файле нет звука")
            return
        }
        val r = GoogleSpeechSession.newRecognizer(context.applicationContext, network)
        if (r == null) {
            fail("Распознавание недоступно на устройстве")
            return
        }
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
            runCatching { r.destroy() }
            fail("Труба к распознавателю не создалась: ${it.javaClass.simpleName}")
            return
        }
        recognizer = r
        readEnd = pipe[0]
        r.setRecognitionListener(listener)
        val intent = Intent(GoogleSpeechSession.recognizeIntent(LANGUAGE, biasing, formatting, offline = !network)).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, MicFeed.CHANNELS)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, MicFeed.ENCODING)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicFeed.SAMPLE_RATE)
            // Одна сессия до конца трубы — паузы в тейке её не кончают.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        log("переразбор: ${file.name}, ${audioMs / 1000} с звука, путь ${if (network) "сеть" else "офлайн-пакет"}")
        runCatching { r.startListening(intent) }.onFailure {
            runCatching { pipe[1].close() }
            fail("Распознаватель не запустился: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        pump(pipe[1])
        main.postDelayed(guard, guardMs())
    }

    /** Поток подачи: файл без заголовка — в трубу, не быстрее [MAX_SPEED] реального времени. */
    private fun pump(writeEnd: ParcelFileDescriptor) {
        val total = (file.length() - TakeAudio.WAV_HEADER).coerceAtLeast(1L)
        thread(name = "pravka-replay") {
            val t0 = SystemClock.elapsedRealtime()
            var sent = 0L
            var lastShown = 0f
            runCatching {
                FileInputStream(file).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        input.skip(TakeAudio.WAV_HEADER)
                        val buf = ByteArray(CHUNK_BYTES)
                        while (!stopped) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            sent += n
                            // Не обгонять реальное время больше чем в MAX_SPEED раз.
                            val audioAhead = sent / BYTES_PER_MS / MAX_SPEED - (SystemClock.elapsedRealtime() - t0)
                            if (audioAhead > 0) Thread.sleep(audioAhead)
                            val share = sent.toFloat() / total
                            if (share - lastShown >= 0.02f) {
                                lastShown = share
                                main.post { if (!done) onProgress(share.coerceAtMost(1f)) }
                            }
                        }
                    }
                }
            }.onFailure {
                // Распознаватель закрыл свой конец раньше — это не беда, итог придёт из его сессии.
                if (!stopped) main.post { log("переразбор: подача оборвалась — ${it.javaClass.simpleName}: ${it.message}") }
            }
            main.post { if (!done) onProgress(1f) }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            lastPartial = first(partialResults).orEmpty()
        }

        override fun onSegmentResults(segmentResults: Bundle) = commit(first(segmentResults))

        override fun onResults(results: Bundle?) {
            commit(first(results))
            finish()
        }

        override fun onEndOfSegmentedSession() = finish()

        override fun onError(error: Int) {
            // Конец трубы без слов в хвосте распознаватель называет «не разобрал» —
            // это конец, а не сбой, если слова уже есть.
            if (text.isNotEmpty() || lastPartial.isNotBlank()) {
                log("переразбор: распознаватель кончил кодом $error — отдаю разобранное")
                finish()
            } else {
                fail(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Распознаватель не разобрал ни слова"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети до облака Google (код $error)"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят — идёт другая диктовка? (код $error)"
                        else -> "Распознаватель ответил ошибкой $error"
                    }
                )
            }
        }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun commit(piece: String?) {
        val p = piece.orEmpty()
        if (p.isNotEmpty()) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(p)
        } else if (lastPartial.isNotBlank()) {
            // Пустой итог куска, а гипотеза была — берём её, как живой тейк.
            if (text.isNotEmpty()) text.append(' ')
            text.append(lastPartial.trim())
        }
        lastPartial = ""
    }

    private val guard = Runnable {
        if (done) return@Runnable
        log("переразбор: распознаватель не кончил за ${guardMs() / 1000} с — отдаю, что есть")
        finish()
    }

    /** Сторож: сколько ждать итога — подача плюс запас на дочитку. */
    private fun guardMs(): Long = audioMs / MAX_SPEED + GUARD_EXTRA_MS

    private fun finish() {
        if (done) return
        done = true
        if (lastPartial.isNotBlank()) commit(null)
        teardown()
        val result = text.toString().trim()
        log("переразбор: готово, ${result.length} зн. за ${elapsedMs / 1000} с")
        onDone(Result.success(result))
    }

    private fun fail(why: String) {
        if (done) return
        done = true
        teardown()
        log("переразбор не вышел: $why")
        onDone(Result.failure(IllegalStateException(why)))
    }

    private fun teardown() {
        stopped = true
        main.removeCallbacks(guard)
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
            // В счёт службы речи: переразбор — та же её работа, что тейк.
            GoogleSpeechSession.use(ru.zf.pravka.core.SpeechUse.Kind.REPLAY, elapsedMs)
        }
        recognizer = null
        readEnd?.let { runCatching { it.close() } }
        readEnd = null
    }

    private companion object {
        const val LANGUAGE = "ru-RU"
        /** Кусок подачи: 100 мс звука. */
        const val CHUNK_BYTES = MicFeed.SAMPLE_RATE * 2 / 10
        const val BYTES_PER_MS = MicFeed.SAMPLE_RATE * 2 / 1000L
        /** Во сколько раз быстрее реального времени подаём звук. */
        const val MAX_SPEED = 4L
        /** Запас сторожа сверх времени подачи. */
        const val GUARD_EXTRA_MS = 30_000L
    }
}
