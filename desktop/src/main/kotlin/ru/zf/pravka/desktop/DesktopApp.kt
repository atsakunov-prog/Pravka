package ru.zf.pravka.desktop

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import ru.zf.pravka.core.DictionaryApplier
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.RulesStore
import ru.zf.pravka.desktop.audio.Recorder
import ru.zf.pravka.desktop.data.DesktopPromptStore
import ru.zf.pravka.desktop.data.DesktopSettings
import ru.zf.pravka.desktop.data.DesktopStats
import ru.zf.pravka.desktop.data.Paths
import ru.zf.pravka.desktop.data.TranscriptionJournal
import ru.zf.pravka.desktop.input.ClipboardTarget
import ru.zf.pravka.desktop.speech.WhisperServerClient
import ru.zf.pravka.provider.ClaudeProvider

// Сервис-локатор воркстанции - брат PravkaApp с телефона. Граф зависимостей
// маленький, DI-фреймворк был бы лишней зависимостью.
object DesktopApp {

    val settings by lazy { DesktopSettings() }
    val stats by lazy { DesktopStats() }
    val promptStore by lazy { DesktopPromptStore() }
    val rulesStore by lazy { RulesStore(Paths.dir) }
    val historyLog by lazy { HistoryLog(Paths.dir) }
    val transcripts by lazy { TranscriptionJournal(Paths.dir) }

    val dictionaryStore by lazy {
        DictionaryStore(Paths.dir) {
            // Заводской словарь один на оба устройства: файл берётся из
            // app/src/main/assets прямо на сборке (см. desktop/build.gradle.kts).
            runCatching {
                DesktopApp::class.java.classLoader
                    .getResourceAsStream(DictionaryStore.SEED_ASSET)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    val claude by lazy { ClaudeProvider(settings, promptStore, httpClient, rulesStore) }
    val whisper by lazy { WhisperServerClient(httpClient) }
    val recorder by lazy { Recorder() }

    val engine by lazy {
        ProofreadEngine(
            claude = claude,
            clipboardFallback = ClipboardTarget(),
            stats = stats,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            history = historyLog,
        )
    }

    /** Живёт столько же, сколько программа: работа не должна умирать с окном. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
