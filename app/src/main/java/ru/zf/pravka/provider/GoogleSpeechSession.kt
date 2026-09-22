package ru.zf.pravka.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ru.zf.pravka.core.MicPlan

// Live, streaming speech recognition via Android's SpeechRecognizer - the same
// system engine (Speech Services by Google) that Gboard's voice typing uses.
// Realtime, never touches a file; the accepted tradeoff is that no WAV is saved
// during a live take.
//
// Two PATHS into that engine (owner's setting, 16.09.2026):
//  - offline pack (factory default): createOnDeviceSpeechRecognizer + PREFER_OFFLINE.
//    Works without network, audio never leaves the phone; a compact model.
//  - network: the plain system recognizer with network allowed. This is the
//    path Gboard takes for RUSSIAN (Pixel's Assistant voice typing has no
//    Russian), i.e. the "clearer than us" model the owner hears on the
//    keyboard; without network the system falls back to the pack by itself.
//
// Two MODES, in order of preference:
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
    // Recognizer's own punctuation/caps. Build 55 - the owner's "распознаёт
    // идеально" configuration - runs the RAW stream (no formatting), so that
    // is the default; the settings expose it for side-by-side comparison.
    private val formatting: Boolean = false,
    // One continuous session across pauses (Android 13+). The restart mode it
    // replaced went deaf on every pause and swallowed phrases mid-take; the
    // settings expose the choice for comparison, continuous is the default.
    private val segmentedSession: Boolean = true,
    // Сетевой путь (см. шапку): системный распознаватель с разрешённой сетью
    // вместо офлайн-пакета. Заводское — офлайн, как было.
    private val network: Boolean = false,
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
    private var startedAtMs = 0L      // для замера: сколько ждали готовности и первого слова
    private var firstPartialLogged = false
    private var routeOurs = false     // маршрут заказали мы — нам его и возвращать

    private var onReady: () -> Unit = {}
    private var onPartial: (String) -> Unit = {}
    private var onCheckpoint: (String) -> Unit = {}
    private var onDone: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var onLog: (String) -> Unit = {}

    companion object {

        /**
         * Куда уходит громкость микрофона: кнопка пульсирует в ритме звуков
         * (владелец, 20.09.2026). Поле общее, а не на каждой сессии, потому
         * что микрофон один: живая сессия в любой миг ровно одна, и
         * прокидывать колбэк через четыре места создания было бы четырьмя
         * копиями одного и того же. Зовётся с главного потока — колбэки
         * SpeechRecognizer приходят туда.
         */
        @Volatile
        var levelSink: ((Float) -> Unit)? = null

        // Only give up after a long run of pure errors with no speech at all
        // (a genuinely dead mic), never on a transient blip mid-dictation.
        private const val MAX_ERROR_STREAK = 40

        // In segmented mode this is what ends the whole session, so it must be
        // far longer than any thinking pause (the owner dictates while reading).
        // An explicit stop is the normal way a take ends; this is just a backstop.
        // MUST be an Int: the framework reads the extra with getInt(), and a
        // Long silently reads back as "not set" - that single character (30_000L)
        // is why segmented mode never engaged (112 takes, segmented=false on all)
        // and every pause cost a ~1.5s deaf restart gap.
        private const val SEGMENTED_SILENCE_MS = 30_000

        // Сколько ждать, пока гарнитура поднимет канал SCO, прежде чем
        // стартовать распознаватель: обычно полсекунды-секунда, дольше —
        // стартуем как есть, и в журнале видно почему.
        private const val SCO_WAIT_MS = 1_500L

        // ---- Прогрев: чтобы первые слова не терялись ----
        //
        // Между startListening() и onReadyForSpeech распознаватель ГЛУХ: пока
        // система будит процесс движка, привязывает службу и поднимает модель,
        // сказанное не слышит никто. Владелец (22.09.2026): «когда я начинаю
        // говорить, первые несколько слов он не слышит». Убрать это окно совсем
        // нельзя — микрофон не наш, — но самую дорогую его часть можно оплатить
        // заранее: разбудить и привязать службу распознавания ДО тейка, пока
        // палец ещё лежит на кнопке.
        //
        // Греет ОТДЕЛЬНЫЙ клиент, не тот, что потом слушает: проверка поддержки
        // идёт асинхронно, а startListening поверх неё — это
        // ERROR_RECOGNIZER_BUSY, с которого в этом файле начиналась целая сага.
        // Тёплый клиент микрофона не трогает и в «недавних» не светится, он
        // просто держит службу живой — и сам отпускает её через WARM_TTL_MS.
        private const val WARM_TTL_MS = 2 * 60_000L

        /** Греть заново после тейка — но не в тот же миг: пусть уляжется destroy. */
        private const val WARM_AFTER_TAKE_MS = 1_200L

        private val warmMain = Handler(Looper.getMainLooper())
        private var warmClient: SpeechRecognizer? = null
        private var warmNetwork = false
        private val warmDrop = Runnable { dropWarm() }

        // ---- Кто именно распознаёт ----
        //
        // `createSpeechRecognizer(context)` берёт службу, назначенную системой
        // по умолчанию (Secure.voice_recognition_service). На Pixel это Google,
        // а на Samsung там запросто стоит своя — и тогда «сетевой путь Google»
        // оказывается вовсе не Google, хотя в настройках написано «сеть».
        // Владелец (22.09.2026): «важно, чтобы облачный гугловский был главным,
        // а то чуть-чуть ухудшилось качество распознавания». Поэтому на сетевом
        // пути службу называем ЯВНО — тот самый пакет, которым распознаёт
        // голосовой ввод клавиатуры Google. Нет его на телефоне — работаем через
        // системную, как раньше, и пишем об этом в журнал.
        private const val GOOGLE_RECOGNIZER_PKG = "com.google.android.googlequicksearchbox"

        @Volatile private var googleServiceCached: ComponentName? = null
        @Volatile private var googleServiceProbed = false

        /** Явная служба подвела (не отвечает) — до перезагрузки движка не просим. */
        @Volatile private var googleServiceBad = false

        private fun googleService(context: Context): ComponentName? {
            if (googleServiceBad) return null
            if (googleServiceProbed) return googleServiceCached
            googleServiceProbed = true
            // Список служб виден благодаря <queries> в манифесте — без него
            // Android 11+ прячет чужие службы, и здесь был бы пустой список.
            googleServiceCached = runCatching {
                context.packageManager
                    .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                    .mapNotNull { it.serviceInfo }
                    .firstOrNull { it.packageName == GOOGLE_RECOGNIZER_PKG }
                    ?.let { ComponentName(it.packageName, it.name) }
            }.getOrNull()
            return googleServiceCached
        }

        /** Просить явную службу Google больше не будем — до перезагрузки движка. */
        private fun forgetGoogleService() {
            googleServiceBad = true
            googleServiceCached = null
        }

        /** Кого просим распознавать: явная служба Google на сетевом пути или системная. */
        private fun serviceFor(context: Context, network: Boolean): ComponentName? =
            if (network) googleService(context) else null

        /** Системная служба распознавания по умолчанию — короткой строкой. */
        private fun systemService(context: Context): String? = runCatching {
            android.provider.Settings.Secure
                .getString(context.contentResolver, "voice_recognition_service")
                ?.substringBefore('/')
        }.getOrNull()

        /**
         * Кто на самом деле распознаёт по сетевому пути. Нужна и настройкам, и
         * журналу: вопрос «а точно ли главный гугловский?» должен отвечаться
         * взглядом, а не верой.
         */
        fun networkServiceLabel(context: Context): String {
            val explicit = googleService(context)
            if (explicit != null) return "Google (${explicit.packageName})"
            val system = systemService(context)
            return if (system.isNullOrBlank()) "системная по умолчанию"
            else "системная по умолчанию — $system"
        }

        private fun newRecognizer(context: Context, network: Boolean): SpeechRecognizer? = runCatching {
            val explicit = serviceFor(context, network)
            when {
                // Сетевой путь: сначала явная служба Google, иначе системная —
                // даже когда офлайн доступен: сеть решает служба сама, пакет
                // остаётся её запасом.
                explicit != null -> SpeechRecognizer.createSpeechRecognizer(context, explicit)
                network && anyAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
                onDeviceAvailable(context) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                anyAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
                else -> null
            }
        }.getOrNull()

        /**
         * Разбудить движок заранее. Зовётся, когда палец ЛЁГ на кнопку (а не
         * когда тап состоялся), на подъёме службы и после каждого тейка.
         * Дёшево и идемпотентно: тёплый клиент того же пути просто продлевается.
         */
        fun warmUp(context: Context, network: Boolean, log: (String) -> Unit = {}) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                warmMain.post { warmUp(context, network, log) }
                return
            }
            if (warmClient != null && warmNetwork == network) {
                // Уже тёплый: просто продлеваем, не трогая привязку.
                warmMain.removeCallbacks(warmDrop)
                warmMain.postDelayed(warmDrop, WARM_TTL_MS)
                return
            }
            dropWarm()
            // Контекст приложения, а не службы: тёплый клиент живёт дольше
            // тейка и пережил бы службу доступности, утащив её за собой.
            val app = context.applicationContext
            val fresh = newRecognizer(app, network) ?: return
            warmClient = fresh
            warmNetwork = network
            warmMain.postDelayed(warmDrop, WARM_TTL_MS)
            // Служба привязывается на ПЕРВОМ вызове, а не при создании объекта:
            // checkRecognitionSupport будит процесс движка и микрофона не
            // трогает. До 33 такого вызова нет — остаётся хотя бы объект,
            // созданный не в тейке.
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    fresh.checkRecognitionSupport(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        },
                        app.mainExecutor,
                        object : android.speech.RecognitionSupportCallback {
                            override fun onSupportResult(support: android.speech.RecognitionSupport) {
                                log("прогрев: движок отозвался")
                            }

                            override fun onError(error: Int) {
                                // Отказ проверки — не беда: служба к этому мигу
                                // уже разбужена, ради неё всё и затевалось.
                                log("прогрев: движок отозвался кодом $error")
                            }
                        },
                    )
                }.onFailure { log("прогрев не вышел: ${it.javaClass.simpleName}") }
            }
        }

        /** Отпустить тёплого клиента: он держит службу распознавания живой. */
        fun dropWarm() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                warmMain.post { dropWarm() }
                return
            }
            warmMain.removeCallbacks(warmDrop)
            val client = warmClient ?: return
            warmClient = null
            runCatching { client.destroy() }
        }

        /**
         * «Перезагрузить микрофон» со стороны движка: забыть, что знали о
         * распознавателе (доступность спрашивается один раз за жизнь процесса,
         * а после падения службы ответ уже неверен), отпустить тёплого клиента
         * и завести нового.
         */
        fun reloadEngine(context: Context, network: Boolean, log: (String) -> Unit = {}) {
            dropWarm()
            onDeviceCached = null
            anyCached = null
            // И про службу тоже: её могли обновить, а мы — один раз отвернуться
            // от неё после сбоя и с тех пор ходить через системную.
            googleServiceCached = null
            googleServiceProbed = false
            googleServiceBad = false
            warmMain.postDelayed({ warmUp(context, network, log) }, 300)
        }

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
            onDeviceAvailable(context) || anyAvailable(context) || googleService(context) != null

        /** Работает ли распознавание на устройстве (тот же путь, что у клавиатуры Google). */
        fun isOnDevice(context: Context): Boolean = onDeviceAvailable(context)

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
            startedAtMs = android.os.SystemClock.elapsedRealtime()
            onLog(
                "start путь=${if (network) "сеть" else "офлайн-пакет"} " +
                    "служба=${if (network) networkServiceLabel(context) else "офлайн-пакет устройства"} " +
                    "onDevice=${onDeviceAvailable(context)} biasing=${biasing.size} " +
                    "formatting=$formatting segmentedRequested=$segmentedSession"
            )
            // Системному распознавателю входное устройство не укажешь: он
            // слушает то, куда смотрит общий маршрут связи. Куда смотреть,
            // решает владелец кружком в веере шестерёнки, а что для этого
            // сделать — `MicPlan` (там же причины). Три исхода:
            //  · AS_IS — трогать нечего, стартуем сразу (обычный случай, и он
            //    самый быстрый: любой переезд маршрута стоит миллисекунд);
            //  · BUILTIN — канал держит машина, а слушать велено телефон:
            //    забираем вход себе и ЖДЁМ переезда;
            //  · HEADSET — поднимаем канал гарнитуры и ждём его.
            // Ждём в обоих случаях по одной причине: стартовав раньше, первые
            // слова услышим прежним входом — салоном или карманом.
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val phone = (context.applicationContext as? ru.zf.pravka.PravkaApp)?.phoneMicOnly != false
            val headsetPresent = MicRouting.headsetMic(am) != null
            val inCall = MicRouting.callInProgress(am)
            val plan = MicPlan.choose(
                phoneMic = phone,
                headsetPresent = headsetPresent,
                btRouteUp = MicRouting.isScoUp(am),
                callInProgress = inCall,
            )
            when (plan) {
                MicPlan.Route.AS_IS -> {
                    // Молчать тут нельзя: «не слышит» и «слышит не то» снаружи
                    // одинаковы, а разбираться потом по журналу.
                    if (inCall) onLog("идёт разговор — маршрут не трогаем")
                    else if (!phone && !headsetPresent) {
                        onLog("выбрана гарнитура, но её нет среди входов — слушаем телефон")
                    }
                    startListening()
                }
                MicPlan.Route.BUILTIN -> {
                    routeOurs = MicRouting.forceBuiltin(am, onLog)
                    if (MicPlan.waitsForRoute(plan, routeOurs)) {
                        MicRouting.awaitBuiltin(am, main, MicRouting.BUILTIN_WAIT_MS, onLog) { startIfAlive() }
                    } else {
                        startListening()
                    }
                }
                MicPlan.Route.HEADSET -> {
                    routeOurs = MicRouting.raise(am, onLog)
                    MicRouting.awaitSco(context, am, main, SCO_WAIT_MS, onLog) { startIfAlive() }
                }
            }
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

    /** Тейк ушёл в ЯВНУЮ службу Google, а не в системную по умолчанию. */
    private var boundGoogle = false

    // Свой клиент на каждый тейк: тёплый (см. companion) только будит службу и
    // слушать не даётся — startListening поверх его проверки поддержки ловил бы
    // ERROR_RECOGNIZER_BUSY.
    private fun createRecognizer(): SpeechRecognizer? {
        boundGoogle = serviceFor(context, network) != null
        return newRecognizer(context, network)
    }

    // Invariant for the whole session (language and biasing never change), so
    // build it once. It used to be rebuilt per restart, copying the bias list
    // twice each time.
    private val intent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Офлайн-путь: только офлайн-движок. На сетевом флаг не ставим —
            // иначе это тот же офлайн-пакет под другим именем.
            if (!network) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (segmentedSession) {
                    // Continuous dictation: keep one session alive across pauses
                    // and receive finalized chunks via onSegmentResults(). The
                    // silence length is what ends the whole session, so both
                    // extras belong to this mode ONLY - in restart mode a 30s
                    // complete-silence would delay every utterance end.
                    putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        SEGMENTED_SILENCE_MS,
                    )
                }
                // The recognizer's own punctuation/caps: its word accuracy is
                // measurably better with the formatted pipeline, and CLEAN v1.9
                // distrusts source punctuation anyway (pause-periods rebuilt).
                if (formatting) {
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                        RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                    )
                }
                // A dictation tool must not censor: by default the recognizer
                // masks "offensive" words with asterisks.
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                // Подсказки из контекста устройства (контакты, личный словарь) —
                // часть того, чем клавиатура Google «понятливее». По документации
                // распознаватель вправе флаг игнорировать; стоит он ноль.
                putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
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

    /** Маршрут переехал — слушаем, если тейк к этому мигу ещё жив. */
    private fun startIfAlive() {
        if (recognizer != null && !stopping) startListening()
        else onLog("маршрут встал, но сессию уже остановили — не стартуем")
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

    // Case/punctuation-insensitive word stream, for comparing hypotheses that
    // differ only in the formatter's decisions.
    private fun normalizedWords(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()

    /** Appends a finalized chunk and publishes a durable checkpoint. */
    private fun commitSegment(bundle: Bundle?, tag: String) {
        errorStreak = 0
        producedAny = true
        var text = firstResult(bundle)?.trim().orEmpty()
        // The engine sometimes finalizes LESS than the partial the owner already
        // watched on the ticker (tail words cut mid-word, rare words dropped).
        // When the watched partial strictly extends the final, keep the partial -
        // the final committed nothing for that extra audio, so no duplication.
        val watched = lastPartial.trim()
        if (text.isNotEmpty() && watched.length > text.length &&
            normalizedWords(watched).startsWith(normalizedWords(text))
        ) {
            onLog("$tag final(${text.length}) shorter than watched partial(${watched.length}) - keeping partial")
            text = watched
        }
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

    private var finished = false

    private fun finish() {
        // Exactly one delivery per session: an error during stopping plus the
        // stop() safety net both funnel here, and a double onDone() logged the
        // take twice and inserted it twice (2026-07-29, twice in the journal).
        if (finished) return
        finished = true
        active = false
        // Include a partial that never got finalized, so the last utterance is
        // never silently dropped when the session ends mid-phrase.
        val text = liveText().trim()
        val r = recognizer
        recognizer = null
        runCatching { r?.destroy() }
        if (routeOurs) {
            routeOurs = false
            runCatching {
                MicRouting.drop(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager, onLog)
            }
        }
        // Следующий тейк начнётся с тёплого движка: служба распознавания уже
        // разбужена, и глухое окно на старте короче. Не сразу — сперва пусть
        // уляжется destroy только что отработавшего клиента.
        warmMain.postDelayed({ warmUp(context, network) }, WARM_AFTER_TAKE_MS)
        onLog("finish len=${text.length} segmented=$segmented")
        onDone(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onLog("ready +${android.os.SystemClock.elapsedRealtime() - startedAtMs} ms")
            // Fire the "you can speak now" cue once per session, not on every
            // restart (that vibrated repeatedly through a silent lead-in).
            if (!readyFired) { readyFired = true; onReady() }
        }
        override fun onBeginningOfSpeech() { errorStreak = 0; producedAny = true; onLog("beginSpeech") }
        override fun onRmsChanged(rmsdB: Float) {
            levelSink?.let { sink -> runCatching { sink(ru.zf.pravka.core.MicLevel.normalise(rmsdB)) } }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onLog("endSpeech") }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = firstResult(partialResults) ?: return
            if (!firstPartialLogged) {
                firstPartialLogged = true
                onLog("first partial +${android.os.SystemClock.elapsedRealtime() - startedAtMs} ms")
            }
            lastPartial = partial
            onPartial(liveText())
        }

        // Segmented mode (Android 13+): a chunk finalized but the session keeps
        // listening. No restart, no deaf gap.
        override fun onSegmentResults(segmentResults: Bundle) {
            // "Segmented session" is Google's API name for CONTINUOUS
            // dictation (one session, many segments) - i.e. the good v55
            // mode. Log it in the settings-toggle vocabulary so the owner
            // doesn't read it as the restart-per-phrase mode.
            if (!segmented) { segmented = true; onLog("непрерывная сессия активна (segmented API)") }
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
                // Мы сами назвали службу Google, и она не отвечает — дальше
                // ходим через системную по умолчанию, а не упираемся в стену
                // каждый тейк. Обратно всё вернёт «Перезагрузить микрофон».
                if (boundGoogle) {
                    forgetGoogleService()
                    onLog("явная служба Google не отвечает — следующий тейк пойдёт через системную")
                }
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
        // Облачный путь стал заводским (22.09.2026), и «нет сети» теперь не
        // абстракция, а лифт и подземный паркинг. Ошибка обязана называть
        // причину и выход, а не оставлять владельца с кодом на руках.
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Сеть не отвечает, а путь распознавания — облачный. " +
                "Настройки → Правка → «Путь распознавания» → офлайн-пакет."
        else -> "Ошибка распознавания ($code)"
    }
}
