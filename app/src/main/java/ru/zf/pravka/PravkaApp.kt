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
import ru.zf.pravka.provider.DictMiner
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
    val dictMiner by lazy { DictMiner(settings, httpClient) }
    val whisperProvider by lazy { WhisperProvider(this, settings) }
    val recordings by lazy { Recordings(this) }

    // The connection pool keeps sockets ~5 min; after a longer gap the CLEAN
    // request pays DNS+TCP+TLS (~300-800ms on LTE). A dictation lasts seconds,
    // so warming the connection when a take STARTS makes the stop->fix hop skip
    // the handshake entirely. Any response counts - only the socket matters.
    @Volatile private var lastWarmAt = 0L
    fun warmClaudeConnection() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWarmAt < 4 * 60 * 1000L) return  // pool still warm
        lastWarmAt = now
        val request = okhttp3.Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .head()
            .build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }

    // File-based transcription: for saved recordings and retries. The live
    // Google engine can't read a file, so saved files go through Whisper.
    // Every attempt is logged with metrics (engine, audio length, time, chars).
    suspend fun transcribeDictation(file: File): Result<String> {
        // Ask Whisper which model will really run, so the logged engine matches
        // the one that did the work.
        val fileEngine = whisperProvider.resolveEngine()
        val started = SystemClock.elapsedRealtime()
        val result = whisperProvider.transcribe(file, fileEngine)
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
            clipboardFallback = ClipboardTarget(this),
            stats = stats,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            history = historyLog,
        )
    }
}
