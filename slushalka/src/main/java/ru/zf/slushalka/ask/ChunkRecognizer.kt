package ru.zf.slushalka.ask

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.zf.slushalka.player.AudioChunk

/**
 * Распознавание **куска файла**, а не микрофона.
 *
 * Android 12+ умеет принимать звук через трубу вместо микрофона
 * (`EXTRA_AUDIO_SOURCE`), а с тринадцатой версии есть и полностью локальный
 * распознаватель. Этого хватает, чтобы расшифровать последние секунд десять
 * записи и найти это место в тексте книги - без скачивания моделей и без
 * единого запроса в сеть.
 *
 * Не выйдет - вернётся null, и переход просто останется приблизительным.
 */
class ChunkRecognizer(private val context: Context) {

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun recognize(pcm: AudioChunk.Pcm, timeoutMs: Long = 25_000): String? {
        if (!supported) return null
        return suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            main.post {
                var recognizer: SpeechRecognizer? = null
                val heard = StringBuilder()
                var finished = false

                val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
                val read = pipe?.get(0)
                val write = pipe?.get(1)

                fun done(text: String?) {
                    if (finished) return
                    finished = true
                    main.removeCallbacksAndMessages(null)
                    runCatching { recognizer?.destroy() }
                    // Закрытие читающего конца заодно расклинивает писателя,
                    // если распознаватель не дочитал кусок до конца.
                    runCatching { read?.close() }
                    if (cont.isActive) cont.resume(text?.takeIf { it.isNotBlank() })
                }

                if (read == null || write == null) return@post done(null)

                val r = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                }.getOrNull() ?: return@post done(null)
                recognizer = r

                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onSegmentResults(segmentResults: Bundle) {
                        first(segmentResults)?.let {
                            if (heard.isNotEmpty()) heard.append(' ')
                            heard.append(it)
                        }
                    }

                    override fun onEndOfSegmentedSession() = done(heard.toString())

                    override fun onResults(results: Bundle?) {
                        first(results)?.let {
                            if (heard.isNotEmpty()) heard.append(' ')
                            heard.append(it)
                        }
                        done(heard.toString())
                    }

                    override fun onError(error: Int) = done(heard.toString())

                    private fun first(b: Bundle?): String? =
                        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.takeIf { it.isNotBlank() }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
                    // Сессия кончается вместе со звуком в трубе - ровно то, что
                    // нужно для куска файла.
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }

                // Пишем в трубу с фоновой нити: труба маленькая, и до конца
                // куска писатель будет ждать распознаватель.
                Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                            out.write(pcm.bytes)
                            out.flush()
                        }
                    }
                }, "slushalka-pcm").apply { isDaemon = true }.start()

                runCatching { r.startListening(intent) }.onFailure { return@post done(null) }
                main.postDelayed({ done(heard.toString()) }, timeoutMs)

                cont.invokeOnCancellation { main.post { done(null) } }
            }
        }
    }
}
