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

    override fun onCreate() {
        super.onCreate()
        // Копии на диск: раз в час их снимает тик службы, но старт процесса -
        // после обновления APK или перезагрузки телефона - тоже хороший момент
        // (и единственный, если служба доступности почему-то выключена).
        ru.zf.pravka.data.Backups.tick(this) { line -> eventLog.add(line) }
    }

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

    // UI-independent scope: learning accept/reject must survive tab switches
    // and the settings screen closing (rememberCoroutineScope dies with them -
    // that was the "принял четыре правила, записалось одно" bug).
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )
    val learnLog by lazy { ru.zf.pravka.data.EventLog(this, "learning.log") }
    val rulesStore by lazy { ru.zf.pravka.data.RulesStore(this) }
    val learnStore by lazy { ru.zf.pravka.data.LearnStore(this) }
    val editWatch by lazy { ru.zf.pravka.data.EditWatchStore(this) }
    val evalStore by lazy { ru.zf.pravka.data.EvalStore(this) }
    val claudeProvider by lazy { ClaudeProvider(settings, promptStore, httpClient, rulesStore) }
    val dictMiner by lazy { DictMiner(settings, httpClient, stats) }
    val whisperProvider by lazy { WhisperProvider(this, settings) }
    val recordings by lazy { Recordings(this) }

    // Засечка (timesheet): store, the Sheets mirror, phrase -> entry pipeline.
    val zasechkaStore by lazy {
        ru.zf.pravka.data.ZasechkaStore(this).also { store ->
            store.logger = { line -> eventLog.add(line) }
        }
    }
    val zasechkaSync by lazy {
        ru.zf.pravka.data.ZasechkaSync(settings, zasechkaStore, httpClient, eventLog)
    }
    val zasechkaEngine by lazy {
        ru.zf.pravka.core.ZasechkaEngine(claudeProvider, zasechkaStore, stats, eventLog, zasechkaSync, appScope)
    }

    // Todoist: список дел владельца и обратная запись времени в задачу.
    val todoistStore by lazy { ru.zf.pravka.data.TodoistStore(this) }
    val todoistSync by lazy {
        ru.zf.pravka.data.TodoistSync(settings, todoistStore, zasechkaStore, httpClient, eventLog)
    }

    // Разноска: наговор -> дела в Todoist. Разобранное лежит на диске до
    // того, как Todoist его примет (raznoska.json).
    val raznoskaStore by lazy { ru.zf.pravka.data.RaznoskaStore(this) }
    val raznoskaRoutes by lazy { ru.zf.pravka.data.RaznoskaRoutes(this) }
    val raznoskaEngine by lazy {
        ru.zf.pravka.core.RaznoskaEngine(
            claude = claudeProvider,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            store = raznoskaStore,
            routes = raznoskaRoutes,
            todoistStore = todoistStore,
            todoistSync = todoistSync,
            stats = stats,
            eventLog = eventLog,
        )
    }

    // Спорт: кэш тренировочной жизни из intervals.icu и разбор своих
    // тренировок. Сама выгрузка - вторая дорога к тому же API, отдельная от
    // IcuSweeper: тот пишет в ленту, а этот в кэш, который можно потерять.
    val sportStore by lazy { ru.zf.pravka.data.SportStore(this) }
    val icuSportSync by lazy {
        ru.zf.pravka.data.IcuSportSync(settings, sportStore, httpClient, eventLog)
    }

    // Еда: дневник приёмов с КБЖУ. Незаменимые данные - как лента.
    val foodStore by lazy {
        ru.zf.pravka.data.FoodStore(this).also { store ->
            store.logger = { line -> eventLog.add(line) }
        }
    }
    val openFoodFacts by lazy { ru.zf.pravka.data.OpenFoodFacts(httpClient) }
    val foodEngine by lazy {
        ru.zf.pravka.core.FoodEngine(
            claude = claudeProvider,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            store = foodStore,
            sportStore = sportStore,
            icu = icuSportSync,
            zasechkaStore = zasechkaStore,
            offf = openFoodFacts,
            settings = settings,
            stats = stats,
            eventLog = eventLog,
        )
    }
    val sportCoach by lazy {
        ru.zf.pravka.core.SportCoach(
            claude = claudeProvider,
            store = sportStore,
            foodStore = foodStore,
            zasechkaStore = zasechkaStore,
            settings = settings,
            stats = stats,
            eventLog = eventLog,
        )
    }

    // The phone layer: app time, pickups, distractions; attention eaters and
    // calls cross into the ribbon via the sweeper.
    val phoneStore by lazy { ru.zf.pravka.data.PhoneStore(this) }
    val phoneSweeper by lazy {
        ru.zf.pravka.data.PhoneSweeper(this, phoneStore, zasechkaStore, settings, eventLog, zasechkaSync, appScope)
    }

    // intervals.icu: workouts land in the ribbon, Garmin sleep annotates it.
    val icuSweeper by lazy {
        ru.zf.pravka.data.IcuSweeper(settings, zasechkaStore, httpClient, eventLog, zasechkaSync, appScope)
    }

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
