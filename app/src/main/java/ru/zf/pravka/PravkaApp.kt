package ru.zf.pravka

import android.app.Application
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import ru.zf.pravka.core.DictionaryApplier
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Recordings
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.LiveDraft
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.TranscriptionLog
import ru.zf.pravka.data.WavFile
import android.os.SystemClock
import java.io.File
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.NanoProvider
import ru.zf.pravka.provider.SpeechProvider
import ru.zf.pravka.provider.WhisperProvider
import ru.zf.pravka.target.ClipboardTarget

// Plain service locator - the dependency graph is small enough
// that a DI framework would be an unjustified dependency (spec section 14).
class PravkaApp : Application() {

    val settings by lazy { Settings(this) }
    val promptStore by lazy { PromptStore(this) }
    val stats by lazy { Stats(this) }
    val dictionaryStore by lazy { DictionaryStore(this) }
    val historyLog by lazy { HistoryLog(this) }
    val transcriptionLog by lazy { TranscriptionLog(this) }
    val liveDraft by lazy { LiveDraft(this) }
    val eventLog by lazy { EventLog(this) }

    val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // Spec 6.1 said 25s, sized for the proxy. Without streaming the
            // API returns the whole body only after generation completes, and
            // real long dictations (5000+ chars) already hit 25s.
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    val claudeProvider by lazy { ClaudeProvider(settings, promptStore, httpClient) }
    val nanoProvider by lazy { NanoProvider(this, promptStore) }
    val speechProvider by lazy { SpeechProvider(this, settings) }
    val whisperProvider by lazy { WhisperProvider(this, settings) }
    val recordings by lazy { Recordings(this) }

    // File-based transcription: for saved recordings and retries. The live
    // Google engine can't read a file, so under Google (and by default) saved
    // files go through Whisper; Nano uses its own path. Every attempt is
    // logged with metrics (engine, audio length, transcription time, chars).
    suspend fun transcribeDictation(file: File): Result<String> {
        val nano = settings.speechEngine() == Settings.SPEECH_NANO
        // Ask Whisper which model will really run, so the logged engine matches
        // the one that did the work.
        val fileEngine = if (nano) Settings.SPEECH_NANO else whisperProvider.resolveEngine()
        val started = SystemClock.elapsedRealtime()
        val result = if (nano) {
            speechProvider.transcribe(file)
        } else {
            whisperProvider.transcribe(file, fileEngine)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        transcriptionLog.append(
            engine = fileEngine,
            audioMs = WavFile.durationMs(file),
            transcribeMs = elapsed,
            text = result.getOrNull().orEmpty(),
            error = result.exceptionOrNull()?.message,
        )
        return result
    }

    val engine by lazy {
        ProofreadEngine(
            claude = claudeProvider,
            nano = nanoProvider,
            settings = settings,
            clipboardFallback = ClipboardTarget(this),
            stats = stats,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            history = historyLog,
        )
    }
}
