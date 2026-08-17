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
// the same fast engine Gboard uses. Realtime, never touches a file; the accepted
// tradeoff is that no WAV is saved during a live take.
//
// Two modes, in order of preference:
//
//  1. SEGMENTED SESSION (Android 13+, EXTRA_SEGMENTED_SESSION). The recognizer
//     stays listening across pauses and streams finalized chunks via
//     onSegmentResults(), ending only when we stop it. This is what Gboard-style
//     continuous dictation needs: ONE session, so there is no deaf gap between
//     utterances and no per-utterance re-initialization.
//  2. FALLBACK: the classic single-utterance behavior, where the recognizer ends
//     on a pause and we restart it. Restarting is what dropped words and (when
//     it was combined with destroy/recreate) caused a BUSY error storm, so it is
//     only used where segmented mode isn't honored.
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
    // finalized.toString() is rebuilt only when a segment lands, not on every
    // partial (partials arrive several times a second and the transcript grows
    // to thousands of chars - rebuilding it each time was pure GC churn).
    private var head = ""
    private var lastPartial = ""
    @Volatile private var active = false
    private var stopping = false
    private var errorStreak = 0
    private var restartPending = false
    private var producedAny = false   // did this session ever start recognizing?
    private var readyFired = false
    private var segmented = false     // segmented mode confirmed working

    private var onReady: () -> Unit = {}
    private var onPartial: (String) -> Unit = {}
    private var onCheckpoint: (String) -> Unit = {}
    private var onDone: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var onLog: (String) -> Unit = {}

    companion object {
        // Only give up after a long run of pure errors with no speech at all
        // (a genuinely dead mic), never on a transient blip mid-dictation.
        private const val MAX_ERROR_STREAK = 40

        // In segmented mode this is what ends the whole session, so it must be
        // far longer than any thinking pause (the owner dictates while reading).
        // An explicit stop is the normal way a take ends; this is just a backstop.
        private const val SEGMENTED_SILENCE_MS = 30_000L

        private fun onDeviceSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        // Availability is a binder round trip (and isRecognitionAvailable does a
        // PackageManager query); it cannot change while the app runs, so probe
        // once instead of on every tap and every recognizer creation.
        @Volatile private var onDeviceCached: Boolean? = null
        @Volatile private var anyCached: Boolean? = null

        private fun onDeviceAvailable(context: Context): Boolean =
            onDeviceCached ?: runCatching {
                onDeviceSupported() && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }.getOrDefault(false).also { onDeviceCached = it }

        private fun anyAvailable(context: Context): Boolean =
            anyCached ?: runCatching {
                SpeechRecognizer.isRecognitionAvailable(context)
            }.getOrDefault(false).also { anyCached = it }

        fun isAvailable(context: Context): Boolean =
            onDeviceAvailable(context) || anyAvailable(context)

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

    /** Runs now when already on the main thread, instead of costing a looper hop. */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    fun start(
        onReady: () -> Unit = {},
        onPartial: (String) -> Unit,
        onCheckpoint: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onLog: (String) -> Unit = {},
    ) {
        this.onReady = onReady
        this.onPartial = onPartial
        this.onCheckpoint = onCheckpoint
        this.onDone = onDone
        this.onError = onError
        this.onLog = onLog
        onMain {
            val r = createRecognizer()
            if (r == null) {
                onLog("start FAILED: no recognizer")
                onError("Распознавание недоступно на устройстве")
                return@onMain
            }
            recognizer = r
            r.setRecognitionListener(listener)
            active = true
            stopping = false
            errorStreak = 0
            onLog("start onDevice=${onDeviceAvailable(context)} biasing=${biasing.size} segmentedRequested=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU}")
            startListening()
        }
    }

    /** Ends the session; the accumulated text is delivered via onDone. */
    fun stop() {
        onMain {
            if (!active && recognizer == null) return@onMain
            stopping = true
            active = false
            runCatching { recognizer?.stopListening() }
            // Safety net: if no terminal callback lands, deliver anyway.
            main.postDelayed({ if (recognizer != null) finish() }, 2500)
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = runCatching {
        when {
            onDeviceAvailable(context) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            anyAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }.getOrNull()

    // Invariant for the whole session (language and biasing never change), so
    // build it once. It used to be rebuilt per restart, copying the bias list
    // twice each time.
    private val intent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Continuous dictation: keep one session alive across pauses and
                // receive finalized chunks via onSegmentResults(). The extra named
                // here must also be set, hence the silence length below.
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SEGMENTED_SILENCE_MS,
                )
                // Punctuation/capitalization tuned for LATENCY: the quality mode
                // is documented to increase latency, which is the wrong trade for
                // live dictation (CLEAN polishes the text afterwards anyway).
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
                // A dictation tool must not censor: by default the recognizer
                // masks "offensive" words with asterisks.
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                // Bias toward the owner's vocabulary (names, brands, terms).
                if (biasing.isNotEmpty()) {
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_BIASING_STRINGS,
                        ArrayList(biasing.take(100)),
                    )
                }
            }
        }
    }

    private fun startListening() {
        val r = recognizer ?: return
        runCatching { r.startListening(intent) }.onFailure { restartSoon(afterError = true) }
    }

    private fun restartSoon(afterError: Boolean = false) {
        if (!active) { finish(); return }
        // Segmented mode never needs a restart on the success path - the session
        // is continuous. But an ERROR kills that session: without a restart the
        // recognizer sat dead while the UI still showed "recording" and
        // everything said after the error was lost (the zombie-session bug).
        if (segmented && !afterError) return
        // ONE restart in flight at a time. Errors can fire in bursts, and
        // scheduling a restart per error (plus destroy/recreate) is exactly
        // what caused the busy/disconnected storm that ate speech and left the
        // recognizer poisoned for the next take. Reuse the same recognizer and
        // never recreate mid-session.
        if (restartPending) return
        restartPending = true
        val resume = {
            restartPending = false
            if (active) startListening()
        }
        // Clean segment end: resume on the very next looper message (any delay
        // here is a window where speech is not being heard). Back off only when
        // errors are actually piling up.
        if (errorStreak == 0) main.post(resume)
        else main.postDelayed(resume, (450L + errorStreak * 150L).coerceAtMost(1500L))
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** Appends a finalized chunk and publishes a durable checkpoint. */
    private fun commitSegment(bundle: Bundle?, tag: String) {
        errorStreak = 0
        producedAny = true
        val text = firstResult(bundle)?.trim().orEmpty()
        if (text.isNotEmpty()) {
            if (finalized.isNotEmpty()) finalized.append(' ')
            finalized.append(text)
            head = finalized.toString()
            lastPartial = ""
        } else {
            // A BLANK final (stop mid-word, endpointer gave up on rare words)
            // used to wipe lastPartial - words the owner had already watched on
            // the ticker vanished from the take. The engine committed nothing
            // for that audio, so promoting the partial cannot duplicate.
            promoteOrphanedPartial("blank final ($tag)")
        }
        onLog("$tag total=${head.length} active=$active stopping=$stopping")
        // One value, one write: onCheckpoint persists it and the caller mirrors
        // it to the ticker, so we don't also push it through onPartial.
        onCheckpoint(head)
    }

    // The recognizer refused to finalize an utterance (NO_MATCH on rare words,
    // timeouts). Its partials were real recognition output the owner watched on
    // the ticker - promote them to the transcript instead of letting the next
    // utterance overwrite them. This was the "2-3 words vanish mid-take on
    // uncommon words" bug.
    private fun promoteOrphanedPartial(reason: String) {
        val p = lastPartial.trim()
        if (p.isEmpty()) return
        if (finalized.isNotEmpty()) finalized.append(' ')
        finalized.append(p)
        head = finalized.toString()
        lastPartial = ""
        onLog("promoted partial (${p.length} ch) after $reason")
        onCheckpoint(head)
    }

    private fun liveText(): String =
        if (lastPartial.isEmpty()) head
        else if (head.isEmpty()) lastPartial
        else "$head $lastPartial"

    private fun finish() {
        active = false
        // Include a partial that never got finalized, so the last utterance is
        // never silently dropped when the session ends mid-phrase.
        val text = liveText().trim()
        val r = recognizer
        recognizer = null
        runCatching { r?.destroy() }
        onLog("finish len=${text.length} segmented=$segmented")
        onDone(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onLog("ready")
            // Fire the "you can speak now" cue once per session, not on every
            // restart (that vibrated repeatedly through a silent lead-in).
            if (!readyFired) { readyFired = true; onReady() }
        }
        override fun onBeginningOfSpeech() { errorStreak = 0; producedAny = true; onLog("beginSpeech") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onLog("endSpeech") }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = firstResult(partialResults) ?: return
            lastPartial = partial
            onPartial(liveText())
        }

        // Segmented mode (Android 13+): a chunk finalized but the session keeps
        // listening. No restart, no deaf gap.
        override fun onSegmentResults(segmentResults: Bundle) {
            if (!segmented) { segmented = true; onLog("segmented mode active") }
            commitSegment(segmentResults, "segment")
            onPartial(head)
        }

        override fun onEndOfSegmentedSession() {
            onLog("endOfSegmentedSession")
            finish()
        }

        override fun onResults(results: Bundle?) {
            commitSegment(results, "result")
            onPartial(head)
            // In segmented mode the session continues; otherwise this was the end
            // of one utterance and we restart to keep dictating.
            if (segmented) return
            if (active && !stopping) restartSoon() else finish()
        }

        override fun onError(error: Int) {
            onLog("error code=$error active=$active stopping=$stopping streak=$errorStreak")
            // If the user stopped, wrap up. Otherwise just restart (reusing the
            // same recognizer - NEVER destroy/recreate here; that was the storm)
            // and only surrender after a long run of pure errors with no speech
            // at all (a genuinely dead mic).
            if (stopping || !active) { finish(); return }
            // Words the recognizer refused to finalize (NO_MATCH on rare words)
            // are still in lastPartial - rescue them before anything else.
            promoteOrphanedPartial("error $error")
            errorStreak++
            // Wedged system recognizer (busy/client/disconnected) that never
            // starts: fail fast with an actionable message instead of churning
            // silently. Plain silence (NO_MATCH / SPEECH_TIMEOUT) is NOT this.
            val wedged = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
            if (!producedAny && wedged && errorStreak >= 4) {
                onLog("giveUp (never started, wedged) streak=$errorStreak code=$error")
                active = false
                val r = recognizer; recognizer = null
                runCatching { r?.destroy() }
                onError("Системный распознаватель занят. Выключи и включи «Правку» в Спец. возможностях (или перезагрузи телефон) и попробуй снова.")
                return
            }
            if (errorStreak >= MAX_ERROR_STREAK) {
                onLog("giveUp streak=$errorStreak len=${head.length}")
                if (liveText().isBlank()) {
                    active = false
                    val r = recognizer; recognizer = null
                    runCatching { r?.destroy() }
                    onError(errorText(error))
                } else {
                    finish()
                }
                return
            }
            restartSoon(afterError = true)
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
