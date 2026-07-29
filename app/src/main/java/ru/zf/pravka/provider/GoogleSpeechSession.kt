package ru.zf.pravka.provider

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// Live, streaming on-device speech recognition via Android's SpeechRecognizer -
// the same fast engine Gboard uses. Unlike Whisper this is realtime and never
// touches a file; the accepted tradeoff is that no WAV is saved during a live
// take. Recognition runs inside the system RecognitionService (mic-owning
// process); we just drive it and stitch the finalized segments together.
//
// SpeechRecognizer is main-thread-only and single-utterance: it ends each
// segment on a pause. For continuous dictation (the owner reads a doc and
// dictates comments, with pauses) we restart listening after every segment and
// keep appending until the caller explicitly stops the session.
class GoogleSpeechSession(
    private val context: Context,
    private val language: String = "ru-RU",
    // Phrases to bias recognition toward (names, terms, brands from the
    // dictionary). Improves rare-word/English accuracy on supporting devices;
    // ignored where the extra isn't honored.
    private val biasing: List<String> = emptyList(),
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val finalized = StringBuilder()
    @Volatile private var active = false
    private var stopping = false
    private var errorStreak = 0

    private var onPartial: (String) -> Unit = {}
    private var onCheckpoint: (String) -> Unit = {}
    private var onDone: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var onLog: (String) -> Unit = {}

    companion object {
        // Only give up after a long run of pure errors with no speech at all
        // (a genuinely dead mic), never on a transient blip mid-dictation.
        private const val MAX_ERROR_STREAK = 40

        private fun onDeviceSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun isAvailable(context: Context): Boolean = runCatching {
            (onDeviceSupported() && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) ||
                SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)

        /** Asks the system to fetch the offline language pack, if that API exists. */
        fun triggerModelDownload(context: Context, language: String = "ru-RU") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            runCatching {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                }
                r.triggerModelDownload(intent)
                // Give the request a moment to register, then release.
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { r.destroy() } }, 2000)
            }
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
        main.post {
            val onDevice = onDeviceSupported() &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
            onLog("start onDevice=$onDevice biasing=${biasing.size}")
            val r = createRecognizer()
            if (r == null) {
                onLog("start FAILED: no recognizer")
                onError("Распознавание недоступно на устройстве")
                return@post
            }
            recognizer = r
            r.setRecognitionListener(listener)
            active = true
            stopping = false
            errorStreak = 0
            startListening()
        }
    }

    /** Ends the session; the accumulated text is delivered via onDone. */
    fun stop() {
        main.post {
            if (!active && recognizer == null) return@post
            stopping = true
            active = false
            runCatching { recognizer?.stopListening() }
            // Safety net: if neither onResults nor onError lands, deliver anyway.
            main.postDelayed({ if (recognizer != null) finish() }, 2500)
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = runCatching {
        when {
            onDeviceSupported() && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) ->
                SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }.getOrNull()

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Tolerate thinking pauses so a segment doesn't end the instant the
        // owner draws breath; we restart on segment end regardless.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Auto punctuation/capitalization, tuned for quality over latency -
            // cleaner raw text for CLEAN to work from.
            runCatching {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
            // Bias toward the owner's vocabulary (names, brands, terms).
            if (biasing.isNotEmpty()) runCatching {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing.take(100)))
            }
        }
    }

    private fun startListening() {
        val r = recognizer ?: return
        runCatching { r.startListening(buildIntent()) }.onFailure { restartSoon() }
    }

    private fun restartSoon() {
        if (!active) { finish(); return }
        // Gentle backoff so a burst of errors doesn't hot-loop, but stay quick
        // enough that a normal pause between phrases feels seamless.
        val delay = (250L + errorStreak * 100L).coerceAtMost(800L)
        main.postDelayed({ if (active) startListening() }, delay)
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun appendSegment(bundle: Bundle?) {
        val text = firstResult(bundle)?.trim().orEmpty()
        if (text.isNotEmpty()) {
            if (finalized.isNotEmpty()) finalized.append(' ')
            finalized.append(text)
        }
    }

    private fun finish() {
        active = false
        val text = finalized.toString().trim()
        val r = recognizer
        recognizer = null
        runCatching { r?.destroy() }
        onLog("finish len=${text.length}")
        onDone(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onLog("ready") }
        override fun onBeginningOfSpeech() { errorStreak = 0; onLog("beginSpeech") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onLog("endSpeech") }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = firstResult(partialResults) ?: return
            val head = finalized.toString()
            onPartial((if (head.isEmpty()) partial else "$head $partial").trim())
        }

        override fun onResults(results: Bundle?) {
            errorStreak = 0
            appendSegment(results)
            val checkpoint = finalized.toString()
            onLog("result total=${checkpoint.length} active=$active stopping=$stopping")
            onPartial(checkpoint)
            onCheckpoint(checkpoint)  // durable: persisted so a crash can't lose it
            if (active && !stopping) restartSoon() else finish()
        }

        override fun onError(error: Int) {
            onLog("error code=$error active=$active stopping=$stopping streak=$errorStreak")
            // If the user stopped, wrap up. Otherwise NEVER end the take on an
            // error - a mid-dictation blip must not silently cut it off. Just
            // keep restarting (with backoff); only surrender after a long run
            // of pure errors with no speech at all (a truly dead mic).
            when {
                stopping -> finish()
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && active -> main.postDelayed({
                    if (!active) { finish(); return@postDelayed }
                    onLog("recreate (busy)")
                    runCatching { recognizer?.destroy() }
                    recognizer = createRecognizer()?.also { it.setRecognitionListener(this) }
                    if (recognizer != null) startListening() else finish()
                }, 300)
                active -> {
                    errorStreak++
                    if (errorStreak >= MAX_ERROR_STREAK) {
                        val text = finalized.toString().trim()
                        onLog("giveUp streak=$errorStreak len=${text.length}")
                        if (text.isEmpty()) { active = false; recognizer = null; onError(errorText(error)) }
                        else finish()
                    } else {
                        restartSoon()
                    }
                }
                else -> finish()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Русский офлайн-пакет не установлен. Открой Правку → «Подготовить модель»."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
        else -> "Ошибка распознавания ($code)"
    }
}
