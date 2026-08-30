package ru.zf.slushalka.ask

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Наговорить вопрос. Тот же движок, что у Правки (системный SpeechRecognizer -
 * гугловский, тот же, что в Gboard), и та же настройка: одна непрерывная
 * сессия, сырой поток слов без «умной» пунктуации.
 *
 * На Android 13+ работает сегментированная сессия: распознаватель не глохнет
 * на паузе, и вопрос можно задавать не торопясь. Ниже - классический режим с
 * перезапуском после каждой фразы.
 */
class VoiceInput(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val finalized = StringBuilder()
    private var lastPartial = ""
    private var stopping = false
    private var segmented = false
    private var errorStreak = 0

    var onText: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onEnd: (String) -> Unit = {}

    val isActive: Boolean get() = recognizer != null

    fun start() {
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Распознавание речи недоступно на этом устройстве")
            return
        }
        finalized.setLength(0)
        lastPartial = ""
        stopping = false
        errorStreak = 0
        launch()
    }

    fun stop() {
        stopping = true
        runCatching { recognizer?.stopListening() }
        // Распознаватель иногда не присылает финал после stopListening;
        // через полторы секунды забираем то, что есть, и закрываемся.
        main.postDelayed({ if (recognizer != null) finish() }, 1500)
    }

    fun cancel() {
        stopping = true
        teardown()
    }

    private fun launch() {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                segmented = true
            }
        }
        runCatching { r.startListening(intent) }.onFailure {
            onError("Микрофон занят")
            teardown()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstOf(partialResults) ?: return
            lastPartial = text
            onText(joined())
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = firstOf(segmentResults) ?: return
            appendSegment(text)
        }

        override fun onEndOfSegmentedSession() {
            finish()
        }

        override fun onResults(results: Bundle?) {
            firstOf(results)?.let { appendSegment(it) }
            if (segmented && !stopping) return
            if (stopping) finish() else relaunch()
        }

        override fun onError(error: Int) {
            errorStreak++
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (stopping) finish() else relaunch()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    this@VoiceInput.onError("Нет разрешения на микрофон")
                    teardown()
                }
                else -> {
                    if (errorStreak > 3 || stopping) {
                        if (finalized.isBlank() && lastPartial.isBlank()) {
                            this@VoiceInput.onError("Распознавание не отвечает")
                        }
                        finish()
                    } else {
                        relaunch()
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun appendSegment(text: String) {
        errorStreak = 0
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            if (finalized.isNotEmpty()) finalized.append(' ')
            finalized.append(clean)
        }
        lastPartial = ""
        onText(joined())
    }

    private fun relaunch() {
        if (stopping) return finish()
        teardownRecognizerOnly()
        main.postDelayed({ if (!stopping) launch() }, 120)
    }

    private fun joined(): String =
        (finalized.toString() + if (lastPartial.isBlank()) "" else " $lastPartial").trim()

    private fun finish() {
        val text = joined()
        teardown()
        onEnd(text)
    }

    private fun teardownRecognizerOnly() {
        val r = recognizer
        recognizer = null
        runCatching { r?.destroy() }
    }

    private fun teardown() {
        main.removeCallbacksAndMessages(null)
        teardownRecognizerOnly()
    }

    private fun firstOf(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.takeIf { it.isNotBlank() }
}
