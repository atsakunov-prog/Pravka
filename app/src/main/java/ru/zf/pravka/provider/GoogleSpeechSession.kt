package ru.zf.pravka.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ru.zf.pravka.core.ListenPolicy
import ru.zf.pravka.core.HeadsetPress
import ru.zf.pravka.core.MicPlan
import ru.zf.pravka.core.TakeHealth

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
    // Тейк позван кнопкой гарнитуры: «чем нажал — тем и слушаем», гарнитура
    // при любом кружке микрофона (`core/HeadsetPress.phoneMic`).
    private val fromHeadset: Boolean = false,
    // Куда писать звук тейка (`data/TakeAudio.kt`, 30.09.2026): пишет своя
    // запись, так что без неё (выключена, путь не берёт наш звук) файла нет.
    private val tape: java.io.File? = null,
) {
    /** Звук тейка записывался своей записью в [tape]. */
    @Volatile private var taped = false

    /** Звук этого тейка в файле; null — своей записи не было, звук не сохранён. */
    val audioFile: java.io.File? get() = if (taped) tape else null

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
    // Подряд ошибок, которые НЕ тишина: только они ведут к «сдаться».
    // Тишина кончает тейк лишь через ListenPolicy.IDLE_CAP_MS без слов.
    private var hardErrorStreak = 0
    // Когда пришло последнее новое слово — от него считает сторож тишины.
    private var lastWordsAtMs = 0L

    /** Когда пришёл последний ЗДОРОВЫЙ кусок — откат метки [lastWordsAtMs] после почти пустого. */
    private var healthyWordsAtMs = 0L
    // Когда в последний раз звали startListening и сколько раз подряд сессия
    // кончалась, толком не начавшись. Тишина теперь поднимает сессию заново, и
    // это ровно та петля, которая в этом файле уже устраивала шторм: движок,
    // закрывающий сессию мгновенно (отобрали микрофон, служба не в себе), без
    // сторожа крутился бы так десять минут подряд.
    private var listenAtMs = 0L
    private var quickEnds = 0
    private val idleWatch = object : Runnable {
        override fun run() {
            if (!active || stopping) return
            if (ListenPolicy.idleExpired(lastWordsAtMs, android.os.SystemClock.elapsedRealtime())) {
                onLog("${ListenPolicy.IDLE_CAP_MS / 60_000} мин без слов — закрываю запись (случайное нажатие?)")
                stop()
            } else {
                main.postDelayed(this, ListenPolicy.IDLE_CHECK_MS)
            }
        }
    }
    private var restartPending = false
    private var producedAny = false   // did this session ever start recognizing?
    private var readyFired = false
    private var segmented = false     // segmented mode confirmed working
    private var startedAtMs = 0L      // для замера: сколько ждали готовности и первого слова
    private var firstPartialLogged = false
    private var routeOurs = false     // маршрут заказали мы — нам его и возвращать

    /**
     * Сколько тейк был глух и кто его слушал (`core/TakeHealth.kt`): итог — в
     * журнал на конце тейка и в запись «Расшифровок» (её пишет хозяин сессии
     * из onDone). Владелец, 28.09.2026: «много пропускает слов… хотя вроде
     * бы говорил нормально» — до этого окна глухоты не видел никто.
     */
    var health = TakeHealth(0L)
        private set

    /** Этот тейк слушает гарнитура (а не телефон) — решено на старте, меняет [switchMic]. */
    @Volatile var headsetMic = false
        private set

    /** После [switchMic] маршрут переезжает: кружок микрофона в пилюле тусклый, пока не встанет. */
    private var routeMoving = false
    private var routeWatch: Runnable? = null

    /**
     * Когда канал гарнитуры закрыла её кнопка ([headsetClosing]); 0 — не
     * закрывала. Смена входа на телефон сразу после — стоп на подходе, а не
     * «наушники отвалились» (`ListenPolicy.headsetStopPending`).
     */
    private var vrClosedAtMs = 0L

    /** Когда вход своей записи встал на наушники (0 — слушает не их). */
    private var headsetHeardAtMs = 0L

    /**
     * Стоп пришёл с кнопки гарнитуры — звук «принял» по музыкальному каналу:
     * канал гарнитуры она закрыла сама. Ставит служба перед [stop].
     */
    @Volatile var stopByHeadset = false

    /** Стоп без звука «принял»: вместо него служба сыграет свой («отвалились»). */
    @Volatile var stopQuiet = false

    /**
     * Бросить тейк сразу, без хвоста и дочитки: отмена («✕») и «набрать
     * текстом» (тап по пилюле) — сказанное либо не нужно, либо ложится в поле
     * набора тем, что уже видно. Владелец ждал поля набора почти три секунды и
     * жал ещё трижды (журнал 29.09, 21:49). Ставит служба перед [stop].
     */
    @Volatile var stopNow = false

    /**
     * Подряд «тишин» (не разобрал / никто не говорил), прилетевших сразу
     * после старта сессии, — только они и настоящие ошибки платят паузу
     * перед подъёмом (`ListenPolicy.restartDelayMs`).
     */
    private var quickSilences = 0

    /**
     * Своя запись Правки, из которой распознаватель получает звук
     * (`MicFeed`, 28.09.2026). null — распознаватель слушает сам (настройка
     * выключена, Android старше 13, путь наш звук не берёт, запись не пошла).
     */
    private var feed: MicFeed? = null
    /** Сколько байт ушло распознавателю к первому «готов» — отсюда видно, читает ли он. */
    private var feedWrittenAtReady = 0L
    /** Распознаватель этого тейка читает наш звук — проверено ([checkFeed]). */
    private var feedAccepted = false
    /** Сессия распознавателя сейчас идёт (между startListening и её концом или ошибкой). */
    private var sessionLive = false
    /** Сколько сессий распознавателя заведено за тейк: первая звук не повторяет. */
    private var sessionsStarted = 0

    /**
     * Путь этого тейка сейчас: облако подвело — дальше слушает офлайн-пакет
     * ([toOffline], `ListenPolicy.toOffline`). С него и начинается следующий
     * тейк — снова облаком: [network] не меняется.
     */
    private var networkNow = network

    // ---- Застрял (ListenPolicy.stuck): речь слышит, слов не отдаёт ----
    /** Когда началась текущая речь (beginSpeech); 0 — речи нет. */
    private var speechBeganAtMs = 0L
    /** Речь с прошлого куска, мс: уходит в [mutedSpeechMs], если кусок пуст. */
    private var speechSinceSegmentMs = 0L
    /** Речь подряд без единого слова, мс. */
    private var mutedSpeechMs = 0L
    /** Подъёмов подряд без слов между ними. */
    private var stuckRestarts = 0
    private var stuckNoticed = false

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

        /**
         * Кто слушает и слышит ли прямо сейчас — кружок микрофона в пилюле
         * (владелец, 28.09.2026). Общее поле по той же причине, что и
         * [levelSink]: микрофон один, живая сессия в любой миг одна. Туда же
         * пишет запись Whisper (`DictationService`). Главный поток.
         */
        @Volatile
        var micSink: ((MicPlan.Mic) -> Unit)? = null

        /**
         * Записка владельцу посреди тейка — когда слова пропадают не по его
         * вине и сам он этого не увидит (запись заглушена системой). Служба
         * показывает её тостом. Главный поток.
         */
        @Volatile
        var noticeSink: ((String) -> Unit)? = null

        /**
         * «Говори» — в тот же миг, что вибрация и «слушаю» в пилюле: всё
         * поднялось и слышит. [headset] — слушают наушники: туда и звенеть
         * (`provider/ChimePlayer.kt`). Главный поток.
         */
        @Volatile
        var readySink: ((headset: Boolean) -> Unit)? = null

        /**
         * Наушники отвалились или не дались — слушает телефон: звук
         * «отвалились» (`provider/ChimePlayer.kt`, `Kind.LOST`), чтобы и не
         * глядя на экран было понятно. Главный поток.
         */
        @Volatile
        var lostSink: (() -> Unit)? = null

        /**
         * Канал наушников закрыла их кнопка, а стек об этом не сказал (или
         * сказал позже, чем заметила запись): служба кончает тейк, как по
         * кнопке гарнитуры, — «принял» и сказанное уходит расшифровываться
         * (`ListenPolicy.headsetDrop`). Главный поток.
         */
        @Volatile
        var headsetStopSink: ((GoogleSpeechSession) -> Unit)? = null

        /**
         * Тейк будет слушать наушники (true) или больше их не слушает (false):
         * подтвердить стеку Bluetooth распознавание, чтобы кнопка гарнитуры
         * тейк останавливала, — или закрыть его (`HeadsetVoice`, у службы).
         * true зовётся ДО подъёма маршрута: поверх поднятого канала стек
         * подтверждение отвергает и канал роняет. Главный поток.
         */
        @Volatile
        var voiceSink: ((Boolean) -> Unit)? = null

        /**
         * Стоп принят — звук «принял» (владелец, 29.09.2026: «в конце, когда я
         * нажимаю, тоже должно быть, что, грубо говоря, он принял и
         * расшифровывает»). [headset] — канал наушников ещё поднят и слушают
         * они; [byHeadset] — стоп нажат их кнопкой. Главный поток.
         */
        @Volatile
        var stopSink: ((headset: Boolean, byHeadset: Boolean) -> Unit)? = null

        /**
         * Сказанное расшифровано — за миг до того, как текст уйдёт хозяину
         * сессии: служба закрывает распознавание у стека гарнитуры, когда
         * отзвучит «принял». Голос «Расшифровал» здесь жил до 30.09.2026 —
         * снят по просьбе владельца. Главный поток.
         */
        @Volatile
        var doneSink: ((GoogleSpeechSession, String) -> Unit)? = null

        /**
         * Сколько ждать, пока идущая запись распознавателя переедет на новый
         * вход после смены микрофона, мс. Не переехала — распознаватель
         * поднимается заново уже на новом маршруте.
         */
        private const val ROUTE_FOLLOW_MS = 2_500L

        /** Как часто смотреть, куда переехала запись. */
        private const val ROUTE_POLL_MS = 250L

        /**
         * Вход у записи уже гарнитурный, а звук по каналу идёт не сразу:
         * канал поднимается полсекунды-секунду. «Слышу» раньше — соврать.
         */
        private const val SCO_SETTLE_MS = 700L

        /** Переподнятый распознаватель сам не вернулся за столько — поднимаем руками. */
        private const val RELISTEN_GUARD_MS = 3_000L

        /** Кто слушает — смотреть у системы через столько после первого «готов». */
        private const val INPUT_CHECK_MS = 400L

        // ---- Свой микрофон (MicFeed) ----

        /**
         * Что путь (true — сеть, false — офлайн-пакет) показал о звуке Правки:
         * берёт или отказал — почему и когда (часы стены, для надписи и срока).
         */
        private data class FeedVerdict(val accepted: Boolean, val why: String, val atMs: Long)

        /**
         * Вердикты путей. Отказ — только доказанный (распознаватель открыл свой
         * микрофон, дважды подряд) и живёт [ListenPolicy.FEED_REFUSAL_TTL_MS],
         * а не до перезапуска: 29.09 один отказ выключил свою запись на весь
         * день, и наушники отваливались каждый тейк. «Не читает» отказом не
         * считается вовсе — через VPN облако начинает читать и позже.
         * «Перезагрузить микрофон» стирает всё.
         */
        private val feedVerdict = java.util.concurrent.ConcurrentHashMap<Boolean, FeedVerdict>()

        private fun feedRefused(network: Boolean): Boolean {
            val v = feedVerdict[network] ?: return false
            return !v.accepted && ListenPolicy.feedRefusalLive(v.atMs, System.currentTimeMillis())
        }

        private fun feedAcceptedBefore(network: Boolean): Boolean = feedVerdict[network]?.accepted == true

        private fun clock(ms: Long): String =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

        /** Что известно о пути — строкой для настроек. */
        fun feedLabel(network: Boolean): String {
            val v = feedVerdict[network] ?: return "ещё не пробовали в этот запуск"
            if (v.accepted) return "берёт звук Правки"
            val left = (ListenPolicy.FEED_REFUSAL_TTL_MS - (System.currentTimeMillis() - v.atMs)) / 60_000
            return if (left > 0) "звук Правки не взял в ${clock(v.atMs)}: ${v.why} — снова попробую через $left мин"
            else "звук Правки не взял в ${clock(v.atMs)}: ${v.why} — следующий тейк попробует снова"
        }

        /** Про тейк в наушниках офлайн-пакетом — сказать один раз, а не каждый тейк. */
        @Volatile private var headsetOfflineTold = false

        /** Проверить, читает ли распознаватель наш звук, — через столько после «готов». */
        private const val FEED_CHECK_MS = 700L
        private const val FEED_CHECK_AGAIN_MS = 1_500L

        /** Столько ушло распознавателю после «готов» — значит, читает (четверть секунды звука). */
        private const val FEED_READ_PROOF_BYTES = 8_000L

        /** Распознаватель не берёт звук столько, пока сессия идёт, — завис; поднять заново. */
        private const val FEED_STALL_MS = 4_000L

        /** После стопа: дописали хвост — ждём конца сессии столько, потом просим сами. */
        private const val FEED_EOF_WAIT_MS = 2_500L

        /** После хвоста в трубу не ушло ни куска столько — распознаватель её не читает. */
        private const val UNREAD_STOP_MS = 400L

        /** Смена микрофона своей записи: смотреть раз в столько, ждать не дольше. */
        private const val FEED_POLL_MS = 150L
        private const val FEED_ROUTE_MS = 4_000L


        /** Сторож микрофона (вход, отвалившиеся наушники, зависшая труба) — раз в столько. */
        private const val MIC_WATCH_MS = 1_000L

        /** После смены облака на пакет — старт через столько: пусть уляжется destroy прежнего. */
        private const val OFFLINE_START_MS = 300L

        // Only give up after a long run of pure errors with no speech at all
        // (a genuinely dead mic), never on a transient blip mid-dictation.
        private const val MAX_ERROR_STREAK = 40


        // In segmented mode this is what ends the RECOGNIZER's session. It no
        // longer ends the take: on onEndOfSegmentedSession we start listening
        // again (ListenPolicy, owner 23.09.2026: «если я замолк, не надо
        // убивать сессию»). Long, so a thinking pause costs no resume at all.
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


        /**
         * Общая часть интента распознавания: язык, гипотезы, форматирование,
         * подсказки словаря. Одна на живой тейк и на переразбор файла
         * (`SpeechReplay`): фраза, разобранная заново, разбирается так же.
         */
        internal fun recognizeIntent(language: String, biasing: List<String>, formatting: Boolean, offline: Boolean): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Офлайн-путь: только офлайн-движок. На сетевом флаг не ставим —
                // иначе это тот же офлайн-пакет под другим именем.
                if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

        internal fun newRecognizer(context: Context, network: Boolean): SpeechRecognizer? = runCatching {
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
            // И что знали о своём микрофоне: после обновления Google ответ мог измениться.
            feedVerdict.clear()
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
            // Наушники, а облако звук Правки не берёт: без своей записи канал
            // гарнитуры держится секунды — тейк сразу офлайн-пакетом, если он
            // наш звук не отвергал (`ListenPolicy.headsetToOffline`).
            val startOffline = headsetStartOffline()
            val r = createRecognizer()
            if (r == null) {
                onLog("start FAILED: no recognizer")
                onError("Распознавание недоступно на устройстве")
                return@onMain
            }
            recognizer = r
            r.setRecognitionListener(listener)
            // Музыку — на паузу сразу, с нажатием: владелец говорит, а не слушает (`TakeFocus`).
            TakeFocus.hold(context, onLog)
            active = true
            stopping = false
            errorStreak = 0
            hardErrorStreak = 0
            startedAtMs = android.os.SystemClock.elapsedRealtime()
            lastWordsAtMs = startedAtMs
            healthyWordsAtMs = startedAtMs
            health = TakeHealth(startedAtMs)
            startOffline?.let { health.toOffline(it) }
            quickSilences = 0
            main.removeCallbacks(idleWatch)
            main.postDelayed(idleWatch, ListenPolicy.IDLE_CHECK_MS)
            main.removeCallbacks(micWatch)
            main.postDelayed(micWatch, MIC_WATCH_MS)
            onLog(
                "start путь=${if (networkNow) "сеть" else "офлайн-пакет"} " +
                    "служба=${if (networkNow) networkServiceLabel(context) else "офлайн-пакет устройства"} " +
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
            val phone = HeadsetPress.phoneMic(
                ownerChosePhone = (context.applicationContext as? ru.zf.pravka.PravkaApp)?.phoneMicOnly != false,
                fromHeadset = fromHeadset,
            )
            if (fromHeadset) onLog("тейк с кнопки гарнитуры — слушаем гарнитуру")
            val headsetPresent = MicRouting.headsetMic(am) != null
            val inCall = MicRouting.callInProgress(am)
            val plan = MicPlan.choose(
                phoneMic = phone,
                headsetPresent = headsetPresent,
                btRouteUp = MicRouting.isScoUp(am),
                callInProgress = inCall,
            )
            // Что покажет кружок микрофона в пилюле: то, что слушает на деле,
            // а не то, что выбрано, — выбрана гарнитура, а её нет, значит телефон.
            headsetMic = when (plan) {
                MicPlan.Route.HEADSET -> true
                MicPlan.Route.BUILTIN -> false
                MicPlan.Route.AS_IS -> inCall && MicRouting.commIsHeadset(am)
            }
            publishMic()
            // Слушать будут наушники — сперва распознавание стеку, потом
            // маршрут: тогда кнопка гарнитуры остановит и тейк, начатый
            // касанием телефона (`HeadsetVoice.startNow`).
            if (plan == MicPlan.Route.HEADSET) holdVoice(true)
            // Свой микрофон: вход выбираем сами, маршрут связи распознавателю
            // больше не указ, и ждать его переезда незачем (`MicFeed`).
            if (tryFeed(am, plan, inCall)) return@onMain
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
        // Кто остановил — из стека вызова, пока он наш: 29.09 тейк Засечки с
        // кнопки гарнитуры кончился через 2,5 с, и по журналу было не понять,
        // чем (диском, пилюлей, касанием в кармане) — у «З» стоп без строки.
        val who = stopCaller()
        onMain {
            if (!active && recognizer == null) return@onMain
            if (stopping) {
                // Второй стоп поверх идущего — без второго хвоста и таймеров
                // (29.09: четыре тапа — четыре «пишу ещё 300 мс»); отмена
                // поверх стопа — бросить сразу.
                if (stopNow) finish()
                return@onMain
            }
            onLog("стоп: откуда — $who")
            stopping = true
            active = false
            // Музыка вернётся, когда отзвучит «принял», а не поверх него.
            TakeFocus.release(TakeFocus.STOP_RELEASE_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            // Глухое окно, если шло, кончилось стопом: дальше слушать и не надо.
            health.close(now)
            routeWatch?.let { main.removeCallbacks(it) }
            routeWatch = null
            // «Принял» — когда хвост записан: звук в наушники поверх
            // последнего слога попал бы в их же микрофон.
            val headsetUp = headsetMic && !stopByHeadset
            val byHeadset = stopByHeadset
            if (!stopQuiet) {
                main.postDelayed({
                    stopSink?.let { sink -> runCatching { sink(headsetUp, byHeadset) } }
                }, ListenPolicy.STOP_TAIL_MS)
            }
            if (stopNow) {
                onLog("стоп: бросаю сразу — ни хвоста, ни дочитки")
                finish()
                return@onMain
            }
            val tail = ListenPolicy.STOP_TAIL_MS
            val f = feed
            if (f != null) {
                // Своя запись: дописать хвост (последний слог под самый тап),
                // отдать распознавателю накопленное и закрыть трубу — он
                // дочитает сказанное перед самым «стопом» и сам кончит
                // сессию. stopListening сейчас оборвал бы недочитанное.
                val drainMs = f.backlogMs()
                f.finish(tailMs = tail)
                onLog("стоп: пишу ещё $tail мс, дописываю распознавателю $drainMs мс звука и закрываю трубу")
                if (!sessionLive) {
                    // Сессии нет (поднималась после ошибки) — дочитывать некому.
                    main.postDelayed({ if (recognizer != null) finish() }, 300)
                    return@onMain
                }
                // Распознаватель трубу не читает (короткий тейк: ещё не начал;
                // 29.09 — 2,7 с до итога) — конца трубы он не увидит, и ждать
                // его незачем: пусть отдаст, что слышал, сейчас.
                main.postDelayed({
                    if (recognizer != null && !finished && f.lastWriteAtMs < now) {
                        onLog("стоп: распознаватель трубу не читает — не жду её конца")
                        runCatching { recognizer?.stopListening() }
                    }
                }, tail + UNREAD_STOP_MS)
                main.postDelayed({
                    if (recognizer != null && !finished) runCatching { recognizer?.stopListening() }
                }, tail + drainMs + FEED_EOF_WAIT_MS)
                main.postDelayed({ if (recognizer != null) finish() }, tail + drainMs + FEED_EOF_WAIT_MS + 2500)
                return@onMain
            }
            // Распознаватель слушает сам: хвост — отложенным stopListening.
            main.postDelayed({
                if (recognizer != null && !finished) runCatching { recognizer?.stopListening() }
            }, tail)
            // Safety net: if no terminal callback lands, deliver anyway.
            main.postDelayed({ if (recognizer != null) finish() }, tail + 2500)
        }
    }

    /** Три-четыре звена нашего кода над [stop] — «Кнопка.тап ← Служба.стоп»; пусто — остановил сам движок. */
    private fun stopCaller(): String = runCatching {
        Throwable().stackTrace.asSequence()
            .filter { it.className.startsWith("ru.zf.pravka.") && !it.className.startsWith(GoogleSpeechSession::class.java.name) }
            .map { it.className.substringAfterLast('.').substringBefore('$').removeSuffix("Kt") to it.methodName.substringBefore('$') }
            // Кадры лямбд («invoke», «$r8$lambda…») — шум: имя места в них не видно.
            .filter { (_, method) -> method.isNotEmpty() && method != "invoke" }
            .map { (cls, method) -> "$cls.$method" }
            .distinct()
            .take(5)
            .joinToString(" ← ")
            .ifBlank { "сам движок (сторож тишины)" }
    }.getOrDefault("не понять")

    /**
     * Канал гарнитуры закрыла сама гарнитура — её кнопка (служба узнаёт это
     * от стека, `HeadsetVoice.watchDrop`): стоп на подходе. Запись заметит
     * смену входа раньше, чем стоп дойдёт, и не должна называть это «наушники
     * отвалились» (владелец, 29.09.2026: «они не отвалились, я просто кнопку
     * нажал»).
     */
    fun headsetClosing() {
        onMain {
            if (!active || stopping) return@onMain
            vrClosedAtMs = android.os.SystemClock.elapsedRealtime()
            onLog("канал закрыла гарнитура — жду стопа, «отвалились» не говорю")
        }
    }

    /** Распознавание у стека Bluetooth: держать ([on]) — кнопка гарнитуры остановит тейк, или отпустить. */
    private fun holdVoice(on: Boolean) {
        voiceSink?.let { sink -> runCatching { sink(on) } }
    }

    /** Тейк ушёл в ЯВНУЮ службу Google, а не в системную по умолчанию. */
    private var boundGoogle = false

    // Свой клиент на каждый тейк: тёплый (см. companion) только будит службу и
    // слушать не даётся — startListening поверх его проверки поддержки ловил бы
    // ERROR_RECOGNIZER_BUSY.
    private fun createRecognizer(): SpeechRecognizer? {
        boundGoogle = serviceFor(context, networkNow) != null
        return newRecognizer(context, networkNow)
    }

    // Invariant for the whole session (language and biasing never change), so
    // build it once. It used to be rebuilt per restart, copying the bias list
    // twice each time. Per session only the audio source differs ([intentFor]).
    private val cloudIntent: Intent by lazy { buildBaseIntent(offline = false) }
    private val offlineIntent: Intent by lazy { buildBaseIntent(offline = true) }
    private val baseIntent: Intent get() = if (networkNow) cloudIntent else offlineIntent

    private fun buildBaseIntent(offline: Boolean): Intent = recognizeIntent(language, biasing, formatting, offline)

    /**
     * Интент этой сессии: общее — из [baseIntent], своё — источник звука.
     * [source] — труба своей записи (`MicFeed.openSource`): распознаватель
     * читает её вместо микрофона, и непрерывная сессия длится, пока труба
     * открыта. null — слушает сам, и непрерывную сессию кончает тишина.
     */
    private fun intentFor(source: ParcelFileDescriptor?): Intent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return baseIntent
        return Intent(baseIntent).apply {
            if (source != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, MicFeed.CHANNELS)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, MicFeed.ENCODING)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicFeed.SAMPLE_RATE)
                if (segmentedSession) {
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }
            } else if (segmentedSession) {
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
        }
    }

    private fun startListening() {
        val r = recognizer ?: return
        // Второй старт поверх идущей сессии — это ERROR_RECOGNIZER_BUSY, с
        // которого здесь начинались штормы. Дороги подъёма теперь несколько
        // (конец сессии, ошибка, сторож, смена пути), и опоздавшая из них
        // просто уступает той, что уже подняла.
        if (sessionLive) {
            onLog("распознаватель уже слушает — второй старт пропускаю")
            return
        }
        listenAtMs = android.os.SystemClock.elapsedRealtime()
        val f = feed
        // Не первая сессия тейка — повторить ей звук с последнего слова: то, что
        // прежняя прочла и не разобрала (ошибка, застряла), разберётся заново.
        val replay = if (f != null && sessionsStarted > 0) {
            ListenPolicy.replayFrom(lastWordsAtMs, listenAtMs)
        } else 0L
        val source = f?.openSource(rewindSinceMs = replay)
        if (f != null && source == null) dropFeed("труба не создалась", relisten = false)
        if (f != null && source != null && f.lastRewindMs > 0) onLog("повторяю распознавателю ${f.lastRewindMs} мс звука с последнего слова")
        sessionsStarted++
        speechBeganAtMs = 0L
        speechSinceSegmentMs = 0L
        sessionLive = true
        runCatching { r.startListening(intentFor(source)) }.onFailure {
            sessionLive = false
            restartSoon(afterError = true)
        }
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
        // Clean segment end — and plain silence after a session that really
        // ran — resume on the very next looper message: any delay here is a
        // window where speech is not being heard (owner, 28.09.2026: «много
        // пропускает слов»). Back off only on real errors and on silences that
        // arrive right after a start (a hot loop otherwise) — ListenPolicy.
        val delay = ListenPolicy.restartDelayMs(hardErrorStreak, quickSilences)
        if (delay == 0L) main.post(resume) else main.postDelayed(resume, delay)
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
        hardErrorStreak = 0
        quickEnds = 0
        quickSilences = 0
        producedAny = true
        val before = head.length
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
        // Пара слов на много секунд речи — сессия оглохла (`ListenPolicy.fewWords`).
        // Метку «последнее слово» тогда откатываем к последнему здоровому куску:
        // повтор звука после подъёма разберёт проглоченную речь заново. Пара слов
        // этого куска может повториться — это дешевле потерянных секунд речи.
        val thin = text.isNotEmpty() && ListenPolicy.fewWords(text.length, speechSinceSegmentMs)
        if (text.isNotEmpty()) {
            if (thin) {
                lastWordsAtMs = healthyWordsAtMs
            } else {
                lastWordsAtMs = android.os.SystemClock.elapsedRealtime()
                healthyWordsAtMs = lastWordsAtMs
            }
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
        // Застрял ли: речь с прошлого куска ушла в пустоту или почти в пустоту —
        // копим; слова пошли как надо — счёт с нуля.
        val gained = head.length - before
        if (!ListenPolicy.fewWords(gained, speechSinceSegmentMs)) {
            mutedSpeechMs = 0
            stuckRestarts = 0
        } else {
            if (gained > 0) onLog("кусок почти пуст: $gained зн. за ${speechSinceSegmentMs / 1000} с речи — считаю как без слов")
            mutedSpeechMs += speechSinceSegmentMs
        }
        speechSinceSegmentMs = 0
        if (active && !stopping && ListenPolicy.stuck(mutedSpeechMs)) onStuck()
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
        TakeFocus.release(TakeFocus.END_RELEASE_MS)
        main.removeCallbacks(idleWatch)
        routeWatch?.let { main.removeCallbacks(it) }
        routeWatch = null
        main.removeCallbacks(micWatch)
        sessionLive = false
        feed?.abort()
        feed = null
        health.close(android.os.SystemClock.elapsedRealtime())
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
        onLog("finish len=${text.length} segmented=$segmented · ${health.summary()}")
        doneSink?.let { sink -> runCatching { sink(this, text) } }
        onDone(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            val now = android.os.SystemClock.elapsedRealtime()
            onLog("ready +${now - startedAtMs} ms")
            health.ready(now)
            // Fire the "you can speak now" cue once per session, not on every
            // restart (that vibrated repeatedly through a silent lead-in).
            if (!recognizerReadyOnce) {
                recognizerReadyOnce = true
                fireReady()
                val f = feed
                if (f != null) {
                    // Своя запись: читает ли распознаватель наш звук — видно по трубе.
                    feedWrittenAtReady = f.writtenBytes
                    feedReadyAtMs = now
                    main.postDelayed({ checkFeed(0) }, FEED_CHECK_MS)
                } else {
                    // Кто слушает на деле — у системы, а не по нашему заказу: запись
                    // к этому мигу открыта. Одна строка в журнал и в итог тейка.
                    main.postDelayed({ if (active && !stopping) noteInput("старт") }, INPUT_CHECK_MS)
                }
            }
            publishMic()
        }
        override fun onBeginningOfSpeech() {
            errorStreak = 0
            hardErrorStreak = 0
            quickEnds = 0
            quickSilences = 0
            producedAny = true
            speechBeganAtMs = android.os.SystemClock.elapsedRealtime()
            onLog("beginSpeech")
        }
        override fun onRmsChanged(rmsdB: Float) {
            // Своя запись меряет громкость сама — распознаватель её не перебивает.
            if (feed != null) return
            levelSink?.let { sink -> runCatching { sink(ru.zf.pravka.core.MicLevel.normalise(rmsdB)) } }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (speechBeganAtMs > 0L) {
                speechSinceSegmentMs += android.os.SystemClock.elapsedRealtime() - speechBeganAtMs
                speechBeganAtMs = 0L
            }
            onLog("endSpeech")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = firstResult(partialResults) ?: return
            if (!firstPartialLogged) {
                firstPartialLogged = true
                onLog("first partial +${android.os.SystemClock.elapsedRealtime() - startedAtMs} ms")
            }
            if (partial != lastPartial) {
                lastWordsAtMs = android.os.SystemClock.elapsedRealtime()
                // Слова пошли — не застрял.
                if (partial.isNotBlank()) mutedSpeechMs = 0
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

        // Распознаватель сам закрыл сессию после долгой тишины. Тейк от этого
        // не кончается (ListenPolicy): владелец не жал «стоп» — слушаем
        // дальше тем же клиентом, накопленный текст на месте.
        override fun onEndOfSegmentedSession() {
            sessionLive = false
            val now = android.os.SystemClock.elapsedRealtime()
            if (ListenPolicy.resumeAfterSessionEnd(active, stopping, lastWordsAtMs, now)) {
                // Сессия, кончившаяся не дослушав своей тишины, — это срыв, а
                // не пауза владельца. Поднимать её бесконечно значит устроить
                // тот же шторм, из-за которого в этом файле нельзя пересоздавать
                // распознаватель: пять срывов подряд без слов — отдаём, что есть.
                quickEnds = ListenPolicy.countCollapse(quickEnds, now - listenAtMs)
                if (ListenPolicy.giveUpOnCollapses(quickEnds)) {
                    onLog("endOfSegmentedSession $quickEnds раз подряд без слов — закрываю тейк")
                    finish()
                    return
                }
                onLog("endOfSegmentedSession — тишина, слушаю дальше")
                promoteOrphanedPartial("end of segmented session")
                // Своя запись копит звук, пока сессии нет, — глухоты это не стоит.
                if (feed == null) health.deaf(now)
                publishMic()
                // С паузой, а не следующим сообщением очереди: движку дают
                // закрыть своё, и петля срывов не крутится на полной скорости.
                main.postDelayed({ if (active && !stopping) startListening() }, ListenPolicy.RESUME_DELAY_MS)
                return
            }
            onLog("endOfSegmentedSession")
            finish()
        }

        override fun onResults(results: Bundle?) {
            commitSegment(results, "result")
            onPartial(head)
            // In segmented mode the session continues; otherwise this was the end
            // of one utterance and we restart to keep dictating.
            if (segmented) return
            if (active && !stopping) {
                // Фраза кончилась, до нового «готов» движок глух (режим перезапусков),
                // если только звук не копит своя запись.
                sessionLive = false
                if (feed == null) health.deaf(android.os.SystemClock.elapsedRealtime())
                restartSoon()
            } else {
                finish()
            }
        }

        override fun onError(error: Int) {
            onLog("error code=$error active=$active stopping=$stopping streak=$errorStreak")
            // If the user stopped, wrap up. Otherwise just restart (reusing the
            // same recognizer - NEVER destroy/recreate here; that was the storm)
            // and only surrender after a long run of pure errors with no speech
            // at all (a genuinely dead mic).
            if (stopping || !active) { finish(); return }
            val now = android.os.SystemClock.elapsedRealtime()
            sessionLive = false
            // Сессия упала — до нового «готов» тейк глух: это окно и считаем.
            // Со своей записью звук ждёт в очереди и глухоты не стоит.
            health.error(error)
            if (feed == null) health.deaf(now)
            publishMic()
            // Words the recognizer refused to finalize (NO_MATCH on rare words)
            // are still in lastPartial - rescue them before anything else.
            promoteOrphanedPartial("error $error")
            // Дорога до облака (сеть, сервер) — не ждать её, а дослушать пакетом.
            if (ListenPolicy.cloudLost(error) && toOffline("ошибка $error", cloudError = true)) return
            errorStreak++
            // Тишина (NO_MATCH / SPEECH_TIMEOUT) к «сдаться» не ведёт: сорок
            // пауз подряд — это человек думает, а не мёртвый микрофон. И паузы
            // перед подъёмом не платит, если сессия успела поработать.
            if (ListenPolicy.isSilence(error)) {
                quickSilences = ListenPolicy.countCollapse(quickSilences, now - listenAtMs)
            } else {
                hardErrorStreak++
            }
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
                main.removeCallbacks(idleWatch)
                releaseOnGiveUp()
                val r = recognizer; recognizer = null
                runCatching { r?.destroy() }
                onError("Системный распознаватель занят. Выключи и включи «Правку» в Спец. возможностях (или перезагрузи телефон) и попробуй снова.")
                return
            }
            if (hardErrorStreak >= MAX_ERROR_STREAK) {
                onLog("giveUp streak=$hardErrorStreak len=${head.length}")
                if (liveText().isBlank()) {
                    active = false
                    main.removeCallbacks(idleWatch)
                    releaseOnGiveUp()
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

    // ---- Кто слушает: кружок микрофона в пилюле ----

    /** Кто слушает и слышит ли прямо сейчас — для пилюли, которая только что встала. */
    fun micState(): MicPlan.Mic = MicPlan.Mic(
        headset = headsetMic,
        // Своя запись слышит с тапа и копит звук сквозь перезапуски распознавателя.
        hearing = (if (feed != null) true else health.hearing) && !routeMoving,
    )

    private fun publishMic() {
        val sink = micSink ?: return
        val state = micState()
        runCatching { sink(state) }
    }

    /** Что слушает запись распознавателя на деле — в журнал и в итог тейка. */
    private fun noteInput(why: String): MicRouting.Heard? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val heard = MicRouting.recognizerInput(am)
        if (heard == null) {
            onLog("$why: кто слушает — системе не видно")
            return null
        }
        health.heard(heard.label)
        onLog(
            "$why: слушает ${heard.label}" +
                if (heard.silenced) " — система отдала микрофон другому, распознаватель слышит тишину" else ""
        )
        if (heard.silenced) {
            noticeSink?.let { runCatching { it("Система отдала микрофон другому приложению — Правка может не слышать") } }
        }
        return heard
    }

    /**
     * Сменить микрофон посреди тейка — кружок в пилюле рядом с «отправить»
     * (владелец, 28.09.2026: «регулярно я нажимаю на телефоне, а потом хочу
     * просто на наушниках продолжить всё говорить и ходить по квартире»).
     *
     * Тейк не останавливается и сказанное остаётся на месте. Системному
     * распознавателю вход не укажешь (см. [start]), зато идущую запись система
     * переводит сама, когда меняется устройство связи. Переехала ли она на
     * самом деле, видно по её входу у системы ([followRoute]); не переехала за
     * [ROUTE_FOLLOW_MS] — распознаватель поднимается заново уже на новом
     * маршруте. Пока маршрут едет, кружок тусклый: это то окно, где сказанное
     * может не дойти, и молчать о нём нельзя.
     *
     * Возвращает записку для владельца, если менять нельзя или нечего; null —
     * смена пошла, кружок встанет сам ([micSink]). Главный поток.
     */
    fun switchMic(wantHeadset: Boolean): String? {
        if (!active || stopping || recognizer == null) return "Запись уже кончилась"
        feed?.let { return switchFeed(it, wantHeadset) }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val change = MicPlan.change(
            wantHeadset = wantHeadset,
            headsetPresent = MicRouting.headsetMic(am) != null,
            headsetRouted = MicRouting.commIsHeadset(am),
            btRouteUp = MicRouting.isScoUp(am),
            callInProgress = MicRouting.callInProgress(am),
        )
        onLog("микрофон посреди тейка: ${if (wantHeadset) "гарнитура" else "телефон"} — $change")
        when (change) {
            MicPlan.Change.CALL -> return "Идёт разговор — микрофон не переключаю"
            MicPlan.Change.NO_HEADSET -> return "Наушников не видно — слушает телефон"
            MicPlan.Change.NOTHING -> Unit
            MicPlan.Change.HEADSET -> {
                holdVoice(true)
                if (!MicRouting.toHeadset(am, onLog)) {
                    holdVoice(false)
                    return "Наушники не отозвались — слушает телефон"
                }
                routeOurs = true
            }
            MicPlan.Change.BUILTIN -> {
                if (MicRouting.forceBuiltin(am, onLog)) routeOurs = true
                holdVoice(false)
            }
        }
        headsetMic = wantHeadset
        routeMoving = true
        publishMic()
        followRoute(am, wantHeadset, android.os.SystemClock.elapsedRealtime(), relistened = false)
        return null
    }

    /**
     * Смотреть, куда переехала запись распознавателя, пока не встанет туда,
     * куда просили; не встала — поднять распознаватель заново (один раз) и
     * смотреть снова. Записи не видно вовсе — верим системе: ни перезапуска,
     * ни вечного тусклого кружка.
     */
    private fun followRoute(am: AudioManager, wantHeadset: Boolean, sinceMs: Long, relistened: Boolean) {
        routeWatch?.let { main.removeCallbacks(it) }
        val step = object : Runnable {
            override fun run() {
                if (!active || stopping || recognizer == null) return
                val now = android.os.SystemClock.elapsedRealtime()
                val heard = MicRouting.recognizerInput(am)
                val waited = now - sinceMs
                val arrived = heard != null && heard.headset == wantHeadset
                val settleMs = if (wantHeadset) SCO_SETTLE_MS else 0L
                when {
                    arrived && waited >= settleMs -> settleRoute(heard, waited)
                    waited < ROUTE_FOLLOW_MS -> main.postDelayed(this, ROUTE_POLL_MS)
                    heard == null -> settleRoute(null, waited)
                    heard.headset == wantHeadset -> settleRoute(heard, waited)
                    !relistened -> {
                        onLog("запись так и слушает ${heard.label} — поднимаю распознаватель на новом маршруте")
                        relisten()
                        followRoute(am, wantHeadset, now, relistened = true)
                    }
                    else -> {
                        onLog("и после подъёма слушает ${heard.label} — оставляю как есть")
                        settleRoute(heard, waited)
                    }
                }
            }
        }
        routeWatch = step
        main.postDelayed(step, ROUTE_POLL_MS)
    }

    private fun settleRoute(heard: MicRouting.Heard?, waitedMs: Long) {
        routeWatch = null
        routeMoving = false
        if (heard != null) {
            health.heard(heard.label)
            onLog("микрофон встал за $waitedMs мс: слушает ${heard.label}")
            // Кружок показывает, что слушает на деле, а не что заказали: до
            // 28.09 он говорил «наушники», пока распознаватель слушал телефон.
            if (heard.headset != headsetMic) {
                headsetMic = heard.headset
                noticeSink?.let {
                    runCatching { it(if (heard.headset) "Слушают наушники" else "Распознаватель слушает телефон — наушники ему не отдаются") }
                }
            }
        } else {
            onLog("микрофон: кто слушает — системе не видно, верю маршруту")
        }
        publishMic()
    }

    /**
     * Поднять распознаватель заново посреди тейка, не теряя сказанного:
     * stopListening — движок дописывает то, что слышал, и сам сообщает о
     * конце, а обычная дорога тейка (конец сессии, ошибка) поднимает его
     * снова. Не поднял за [RELISTEN_GUARD_MS] — поднимаем руками.
     */
    private fun relisten() {
        val r = recognizer ?: return
        if (feed == null) health.deaf(android.os.SystemClock.elapsedRealtime())
        publishMic()
        val at = listenAtMs
        runCatching { r.stopListening() }
        main.postDelayed({
            val now = recognizer
            if (active && !stopping && now != null && listenAtMs == at) {
                onLog("распознаватель сам не поднялся — поднимаю")
                promoteOrphanedPartial("relisten guard")
                // Прежняя сессия так и не кончилась — снять её, иначе новый старт был бы вторым.
                runCatching { now.cancel() }
                sessionLive = false
                startListening()
            }
        }, RELISTEN_GUARD_MS)
    }

    // ---- Свой микрофон (MicFeed) ----

    /**
     * Распознаватель сдался (занят, мёртв) и тейк кончается ошибкой, минуя
     * [finish]: своя запись не должна писать дальше в пустоту, а канал
     * гарнитуры, если поднимали мы, — держать музыку в наушниках немой.
     */
    private fun releaseOnGiveUp() {
        TakeFocus.release(TakeFocus.END_RELEASE_MS)
        routeWatch?.let { main.removeCallbacks(it) }
        routeWatch = null
        main.removeCallbacks(micWatch)
        sessionLive = false
        feed?.abort()
        feed = null
        if (routeOurs) {
            routeOurs = false
            runCatching { MicRouting.drop(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager, onLog) }
        }
        health.close(android.os.SystemClock.elapsedRealtime())
    }

    private var recognizerReadyOnce = false

    /**
     * До распознавателя: слушать велено наушники, а облако звук Правки не
     * берёт (отказ в силе) — тейк сразу офлайн-пакетом со своей записью.
     * Решение то же, что в [start] у MicPlan, только раньше: путь нужен до
     * того, как заведён распознаватель.
     */
    private fun headsetStartOffline(): String? {
        if (!networkNow || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val app = context.applicationContext as? ru.zf.pravka.PravkaApp
        if (app?.speechOwnMic == false) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wantHeadset = !HeadsetPress.phoneMic(ownerChosePhone = app?.phoneMicOnly != false, fromHeadset = fromHeadset) &&
            MicRouting.headsetMic(am) != null && !MicRouting.callInProgress(am)
        if (!ListenPolicy.headsetToOffline(wantHeadset, networkNow, feedRefused(true), onDeviceAvailable(context), feedRefused(false))) return null
        networkNow = false
        val v = feedVerdict[true]
        onLog("наушники: облако звук Правки не взяло в ${v?.atMs?.let { clock(it) }} (${v?.why}) — тейк слушает офлайн-пакет")
        if (!headsetOfflineTold) {
            headsetOfflineTold = true
            noticeSink?.let { runCatching { it("Облако не берёт звук из наушников — в наушниках слушает офлайн-пакет") } }
        }
        return "наушники, облако звук Правки не берёт"
    }

    /**
     * «Говори» после переезда на нужный вход: если путь наш звук уже брал —
     * сразу (сказанное ляжет в очередь), иначе — когда и распознаватель
     * готов; не готов — скажет его «готов».
     */
    private fun cueWhenHeard() {
        if (feedAcceptedBefore(networkNow) || recognizerReadyOnce || feed == null) fireReady()
    }

    /** «Говори» — один раз за тейк: с первого «готов» распознавателя или сразу, если звук копит своя запись. */
    private fun fireReady() {
        if (readyFired) return
        readyFired = true
        onReady()
        // Звук «говори» — туда, откуда слушаем.
        readySink?.let { sink -> runCatching { sink(headsetMic) } }
        onLog("говори: ${if (headsetMic) "наушники" else "телефон"}")
    }

    /**
     * Открыть свою запись и отдать распознавателю её звук (`MicFeed`): если
     * позволяет настройка, Android 13+, нет разговора и распознаватель этого
     * пути наш звук ещё не отверг. true — тейк пошёл этой дорогой.
     */
    private fun tryFeed(am: AudioManager, plan: MicPlan.Route, inCall: Boolean): Boolean {
        val app = context.applicationContext as? ru.zf.pravka.PravkaApp
        if (app?.speechOwnMic == false) {
            onLog("свой микрофон выключен настройкой — распознаватель слушает сам")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (feedRefused(networkNow)) {
            val v = feedVerdict[networkNow]
            onLog("свой микрофон: этот путь звук Правки не взял в ${v?.atMs?.let { clock(it) }} (${v?.why}) — распознаватель слушает сам")
            return false
        }
        if (inCall) {
            onLog("идёт разговор — свой микрофон не открываю")
            return false
        }
        val f = MicFeed.open(onLog) ?: return false
        val wantHeadset = plan == MicPlan.Route.HEADSET
        // Вход — сразу тот, что выбран: без ожиданий канала (владелец,
        // 29.09.2026: «я просто буду ждать несколько секунд и говорить…
        // нажал на кнопку на наушниках — и он слушает, без всяких секунд»).
        f.setDevice(if (wantHeadset) MicRouting.headsetMic(am) else MicRouting.builtinMic(am))
        f.onLevel = { level -> main.post { levelSink?.let { sink -> runCatching { sink(level) } } } }
        tape?.let { f.tapeTo(it) }
        if (!f.start()) return false
        taped = tape != null
        feed = f
        health.fed = true
        headsetMic = wantHeadset
        routeMoving = true
        onLog("свой микрофон: слушает Правка, распознаватель получает её звук — ${if (wantHeadset) "гарнитура" else "телефон"}")
        if (wantHeadset && !MicRouting.commIsHeadset(am) && MicRouting.toHeadset(am, onLog)) routeOurs = true
        followFeed(wantHeadset, sinceMs = android.os.SystemClock.elapsedRealtime())
        publishMic()
        // Путь наш звук уже брал — звать говорить можно с тапа: сказанное до
        // «готов» распознавателя ляжет в очередь и дойдёт до него целиком.
        if (feedAcceptedBefore(networkNow)) fireReady()
        startListening()
        return true
    }

    /**
     * Читает ли распознаватель наш звук — три взгляда: через 0,7, 2,2 и 5 с
     * после первого «готов». Берёт — если ушла четверть секунды звука или
     * уже есть слова при чистом списке записей. Открыл свой микрофон — и
     * так на двух взглядах подряд — отказ: запись закрываем, он дослушивает
     * сам, путь запоминается на полчаса; в наушниках — тейк перекидывается
     * на офлайн-пакет со своей записью. Не читает и своего не открыл за
     * [ListenPolicy.FEED_PROOF_WAIT_MS] — этот тейк без трубы, но отказом это
     * не считается. Система заглушила нашу запись — отдаём микрофон
     * распознавателю.
     */
    private fun checkFeed(look: Int) {
        val f = feed ?: return
        if (!active || stopping) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val foreign = MicRouting.foreignCapture(am, f.sessionId)
        val read = f.writtenBytes - feedWrittenAtReady
        val words = head.isNotEmpty() || lastPartial.isNotEmpty()
        val waited = android.os.SystemClock.elapsedRealtime() - feedReadyAtMs
        when {
            f.silenced() -> dropFeed("система заглушила запись Правки — микрофон у другого", relisten = true)
            foreign != null && look >= 1 -> refuseFeed("открыл свой микрофон (${foreign.label})")
            foreign != null -> {
                onLog("свой микрофон: рядом чужая запись (${foreign.label}) — посмотрю ещё раз")
                main.postDelayed({ checkFeed(look + 1) }, FEED_CHECK_AGAIN_MS)
            }
            read >= FEED_READ_PROOF_BYTES || words -> {
                val was = feedVerdict.put(networkNow, FeedVerdict(true, "берёт", System.currentTimeMillis()))
                if (was?.accepted != true) onLog("свой микрофон: распознаватель берёт звук Правки (за $waited мс, $read байт)")
                feedAccepted = true
            }
            waited < ListenPolicy.FEED_PROOF_WAIT_MS -> {
                val next = if (look == 0) FEED_CHECK_AGAIN_MS else ListenPolicy.FEED_PROOF_WAIT_MS - waited
                main.postDelayed({ checkFeed(look + 1) }, next.coerceAtLeast(250L))
            }
            else -> dropFeed("распознаватель $waited мс не читает звук Правки и своего микрофона не открыл (сеть?) — этот тейк без трубы", relisten = true)
        }
    }

    /** Когда пришло первое «готов» — от него считаются взгляды [checkFeed]. */
    private var feedReadyAtMs = 0L

    /**
     * Доказанный отказ пути: распознаватель открыл свой микрофон. Запомнить на
     * полчаса; в наушниках — не на прежнюю дорогу (там канал держится
     * секунды), а на офлайн-пакет со своей записью; иначе — закрыть запись.
     */
    private fun refuseFeed(why: String) {
        feedVerdict[networkNow] = FeedVerdict(false, why, System.currentTimeMillis())
        onLog("свой микрофон: путь ${if (networkNow) "сеть" else "офлайн-пакет"} звук Правки не берёт — $why")
        if (headsetMic && !feedRefused(false) && toOffline("облако не берёт звук Правки", headsetRefused = true)) return
        dropFeed(why, relisten = false)
    }

    /**
     * Своя запись больше не нужна или невозможна: закрыть, дальше слушает
     * распознаватель сам. Кружок показывает правду — что слушает теперь; если
     * просили наушники, а их больше некому слушать, — записка словами.
     */
    private fun dropFeed(why: String, relisten: Boolean) {
        val f = feed ?: return
        onLog("свой микрофон: $why — дальше распознаватель слушает сам")
        feed = null
        f.abort()
        feedAccepted = false
        routeWatch?.let { main.removeCallbacks(it) }
        routeWatch = null
        routeMoving = false
        health.fed = false
        val wanted = headsetMic
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val heard = MicRouting.recognizerInput(am)
        headsetMic = heard?.headset == true
        heard?.let { health.heard(it.label) }
        if (wanted && !headsetMic) {
            headsetLost("Распознаватель не берёт звук Правки — наушники недоступны, слушает телефон")
        }
        publishMic()
        // Ждали наушников для «говори», а своей записи больше нет — сказать по готовности распознавателя.
        if (recognizerReadyOnce) cueWhenHeard()
        if (relisten && active && !stopping) relisten()
    }

    /**
     * Сторож микрофона, раз в секунду на весь тейк. Своя запись: вход сменился
     * сам (наушники ушли из зоны, разрядились, отдали канал) — кружок и тост
     * говорят правду; сессия идёт, звук ждёт, а распознаватель не читает —
     * поднять его заново. Без своей записи: просили наушники, а запись
     * распознавателя вернулась на телефон — так Android снимает маршрут
     * связи, который держит не записывающее само приложение (журнал 28.09:
     * «слушает гарнитура» через 0,75 с, а через полминуты — телефон).
     */
    private var micTicks = 0
    private var headsetDropNoticed = false
    private val micWatch = object : Runnable {
        override fun run() {
            if (!active || stopping) return
            micTicks++
            val now = android.os.SystemClock.elapsedRealtime()
            val f = feed
            if (f != null) {
                if (!routeMoving) {
                    val routed = f.routed()
                    if (routed != null && MicRouting.isHeadsetDevice(routed) != headsetMic) {
                        headsetMic = MicRouting.isHeadsetDevice(routed)
                        health.heard(MicRouting.label(routed))
                        onLog("вход своей записи сменился сам: слушает ${MicRouting.label(routed)}")
                        publishMic()
                        if (headsetMic) {
                            headsetHeardAtMs = now
                        } else {
                            val heardFrom = headsetHeardAtMs
                            headsetHeardAtMs = 0L
                            headsetLeft(f, now, heardFrom)
                        }
                    }
                }
                if (feedAccepted && sessionLive && f.backlogMs() > 0 &&
                    now - f.lastWriteAtMs > FEED_STALL_MS && now - listenAtMs > FEED_STALL_MS
                ) {
                    onLog("свой микрофон: распознаватель ${(now - f.lastWriteAtMs) / 1000} с не читает — поднимаю заново, звук ждёт в очереди")
                    // Облако, переставшее читать, скорее всего потеряло сеть — сразу пакет.
                    if (!toOffline("облако не читает звук", stalled = true)) relisten()
                }
                watchCapture(f, now)
            } else if (headsetMic && !routeMoving && micTicks % 2 == 0) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val heard = MicRouting.recognizerInput(am)
                if (heard != null && !heard.headset) {
                    headsetMic = false
                    health.heard(heard.label)
                    onLog("запись распознавателя сама вернулась на ${heard.label}: маршрут связи Android держит лишь за записывающим приложением")
                    publishMic()
                    val ownMicOn = (context.applicationContext as? ru.zf.pravka.PravkaApp)?.speechOwnMic != false
                    headsetLost(
                        if (ownMicOn) "Наушники отвалились — распознаватель не берёт звук Правки и слушает телефон"
                        else "Наушники отвалились — распознаватель слушает телефон. Включи «Микрофон держит Правка»"
                    )
                }
            }
            main.postDelayed(this, MIC_WATCH_MS)
        }
    }

    private var lastKickAtMs = 0L
    private var deadAirKicked = false
    private var headsetZerosNoted = false

    /**
     * Своя запись жива и слышит ли: звук не идёт — разбудить (повисшее
     * чтение после смены входа), не оживает — отдать микрофон распознавателю;
     * телефон отдаёт ровные нули — открыть запись заново, не помогло — сказать
     * (так глушит микрофон выключатель доступа в шторке). До 28.09.2026 оба
     * случая были немыми: звук распознавателю просто не шёл, а кружок горел
     * «слышу».
     *
     * Нули с наушников — не поломка (29.09.2026): Shokz с шумодавом шлёт в
     * тишине ровные нули и живыми — ожидание их «живого звука» кончалось
     * сроком каждый тейк. Прежний сторож на пятой секунде тишины уводил такие
     * наушники на телефон со звуком «отвалились»: пауза подумать стоила
     * наушников. Теперь — строка в журнал, и только.
     */
    private fun watchCapture(f: MicFeed, now: Long) {
        val still = now - f.lastCaptureAtMs
        if (still >= ListenPolicy.CAPTURE_GIVE_UP_MS) {
            dropFeed("своя запись не отдаёт звук ${still / 1000} с", relisten = true)
            return
        }
        if (still >= ListenPolicy.CAPTURE_STALL_MS && now - lastKickAtMs >= ListenPolicy.CAPTURE_STALL_MS) {
            lastKickAtMs = now
            f.kick("звук не идёт $still мс")
            return
        }
        val zeros = f.zeroMs
        if (zeros == 0L) {
            deadAirKicked = false
            return
        }
        if (zeros < ListenPolicy.DEAD_AIR_MS || routeMoving) return
        when {
            headsetMic -> if (!headsetZerosNoted) {
                headsetZerosNoted = true
                onLog("наушники шлют ровные нули $zeros мс — так у них звучит тишина (шумодав), канал не трогаю")
            }
            !deadAirKicked -> {
                deadAirKicked = true
                f.kick("телефон отдаёт нули $zeros мс")
            }
            zeros >= ListenPolicy.DEAD_AIR_GIVE_UP_MS -> {
                // Заново открытая запись тоже отдаёт нули: так Android глушит
                // микрофон выключателем доступа в шторке — чинить тут нечего, надо сказать.
                noticeOnce("Микрофон телефона отдаёт тишину — не выключен ли доступ к микрофону в шторке?")
            }
        }
    }

    /**
     * Своя запись сама ушла с наушников на телефон ([heardFromMs] — когда
     * они встали). Наушники на связи — канал закрыла их кнопка: подождать,
     * не моргнул ли он, и кончить тейк, как по кнопке гарнитуры. До 30.09.2026
     * это читалось «наушники отвалились», и тейк дослушивал телефоном из
     * кармана — сказанное не уходило, пока владелец не доставал телефон.
     */
    private fun headsetLeft(f: MicFeed, now: Long, heardFromMs: Long) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val connected = MicRouting.headsetMic(am) != null
        when (ListenPolicy.headsetDrop(vrClosedAtMs, now, connected, heardFromMs)) {
            ListenPolicy.HeadsetDrop.STOP_PENDING ->
                onLog("вход ушёл на телефон: канал закрыла кнопка гарнитуры — это стоп, а не «отвалились»")
            ListenPolicy.HeadsetDrop.BUTTON_STOP -> {
                onLog("вход ушёл на телефон, а наушники на связи — канал закрыла их кнопка; не моргнул ли — ${ListenPolicy.HEADSET_DROP_CONFIRM_MS} мс")
                main.postDelayed({
                    if (feed !== f || !active || stopping) return@postDelayed
                    if (MicRouting.isHeadsetDevice(f.routed())) {
                        onLog("канал наушников вернулся — моргнул, слушаем дальше")
                        return@postDelayed
                    }
                    onLog("кнопка наушников закрыла канал — стоп, сказанное уходит расшифровываться")
                    stopByHeadset = true
                    headsetStopSink?.let { sink -> runCatching { sink(this) } } ?: stop()
                }, ListenPolicy.HEADSET_DROP_CONFIRM_MS)
            }
            ListenPolicy.HeadsetDrop.LOST -> headsetLost("Наушники отвалились — слушает телефон")
        }
    }

    /**
     * Наушники отвалились или не дались, и слушает телефон: тост словами и
     * звук «отвалились» — один раз, пока наушники снова не встанут
     * ([followFeed] сбрасывает): вернул их кружком и они упали снова — снова
     * звук.
     */
    private fun headsetLost(text: String) {
        if (ListenPolicy.headsetStopPending(vrClosedAtMs, android.os.SystemClock.elapsedRealtime())) {
            onLog("вход ушёл на телефон: канал закрыла кнопка гарнитуры — это стоп, а не «отвалились»")
            return
        }
        // Слушает телефон — распознавание у стека больше не наше: музыка в
        // наушниках вернётся, а кнопка гарнитуры остановит тейк и так (при
        // опущенном канале её просьба доходит до Правки, `HeadsetPress.STOP`).
        holdVoice(false)
        if (headsetDropNoticed) return
        headsetDropNoticed = true
        noticeSink?.let { runCatching { it(text) } }
        lostSink?.let { runCatching { it() } }
        onLog("наушники потеряны: $text")
    }

    private var micNoticed = false

    /** Записка про сам микрофон (не про наушники) — один раз за тейк, без звука. */
    private fun noticeOnce(text: String) {
        if (micNoticed) return
        micNoticed = true
        noticeSink?.let { runCatching { it(text) } }
    }

    /**
     * Застрял: речь слышит, куски отдаёт пустые ([ListenPolicy.stuck]).
     * Поднять заново; со своей записью новая сессия получит звук с последнего
     * слова, и застрявшие секунды разберутся снова. Подряд без слов — сразу
     * [ListenPolicy.STUCK_MAX_RESTARTS] раза, дальше реже; после второго — записка.
     */
    private fun onStuck() {
        val muted = mutedSpeechMs
        if (!ListenPolicy.mayRestartStuck(stuckRestarts, muted)) {
            // Копим дальше: следующий подъём — через полминуты речи без слов.
            return
        }
        mutedSpeechMs = 0
        stuckRestarts++
        health.stuck(muted)
        onLog(
            "распознаватель застрял: ${muted / 1000} с речи без единого слова — поднимаю заново" +
                if (feed != null) ", звук с последнего слова повторю" else ""
        )
        // Второй раз подряд на облаке — не тот же путь снова, а пакет.
        if (toOffline("застрял ${stuckRestarts} раз подряд")) return
        if (stuckRestarts >= ListenPolicy.STUCK_NOTICE_AFTER && !stuckNoticed) {
            stuckNoticed = true
            val who = if (headsetMic) "наушники" else "телефон"
            noticeSink?.let { runCatching { it("Не разбираю ни слова — слушает $who. Далеко от микрофона?") } }
        }
        relisten()
    }

    /**
     * Облако подвело — дослушать тейк офлайн-пакетом (`ListenPolicy.toOffline`,
     * владелец 28.09.2026: «можно ли вообще пробовать при застревании
     * перекидывать?»). Новый распознаватель — другого пути, не пересоздание
     * того же после ошибки (от которого здесь были штормы), и один раз за
     * тейк. Со своей записью первая его сессия получает звук с последнего
     * разобранного слова — то, что облако не разобрало, разбирает пакет.
     * true — перекинули.
     */
    private fun toOffline(
        why: String,
        cloudError: Boolean = false,
        stalled: Boolean = false,
        headsetRefused: Boolean = false,
    ): Boolean {
        if (!active || stopping) return false
        if (!ListenPolicy.toOffline(networkNow, onDeviceAvailable(context), cloudError, stuckRestarts, stalled, headsetRefused)) return false
        val fresh = newRecognizer(context, network = false) ?: return false
        onLog(
            "облако подвело ($why) — дослушиваю тейк офлайн-пакетом" +
                if (feed != null) ", звук с последнего слова повторю" else ""
        )
        val old = recognizer
        networkNow = false
        health.toOffline(why)
        fresh.setRecognitionListener(listener)
        recognizer = fresh
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
        sessionLive = false
        restartPending = false
        // Пакет — другой путь: берёт ли он наш звук, проверится на его «готов».
        recognizerReadyOnce = false
        feedAccepted = false
        stuckRestarts = 0
        mutedSpeechMs = 0
        noticeSink?.let {
            runCatching {
                it(
                    if (headsetRefused) "Облако не берёт звук из наушников — дослушиваю офлайн-пакетом"
                    else "Облако Google не отвечает — дослушиваю офлайн-пакетом"
                )
            }
        }
        main.postDelayed({ if (active && !stopping && recognizer === fresh) startListening() }, OFFLINE_START_MS)
        return true
    }

    /**
     * Смена микрофона своей записи: вход указывается прямо, распознаватель её
     * даже не замечает. Гарнитуре сперва поднимается канал ([MicRouting.toHeadset]),
     * и запись переезжает, когда по нему пошёл звук, — до того слушает телефон,
     * без дыры. Обратно — сразу на телефон, а канал, если поднимали мы,
     * опускается: музыка в наушниках молчит, пока он поднят.
     */
    private fun switchFeed(f: MicFeed, wantHeadset: Boolean): String? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (MicRouting.callInProgress(am)) return "Идёт разговор — микрофон не переключаю"
        if (wantHeadset) {
            if (MicRouting.headsetMic(am) == null) return "Наушников не видно — слушает телефон"
            // Сперва распознавание стеку, потом канал: кнопка гарнитуры остановит тейк.
            holdVoice(true)
            if (!MicRouting.commIsHeadset(am)) {
                if (!MicRouting.toHeadset(am, onLog)) {
                    holdVoice(false)
                    return "Наушники не отозвались — слушает телефон"
                }
                routeOurs = true
            }
        } else {
            f.setDevice(MicRouting.builtinMic(am))
            if (routeOurs) {
                routeOurs = false
                MicRouting.drop(am, onLog)
            }
            holdVoice(false)
        }
        onLog("микрофон посреди тейка (своя запись): ${if (wantHeadset) "гарнитура" else "телефон"}")
        headsetMic = wantHeadset
        routeMoving = true
        publishMic()
        followFeed(wantHeadset, android.os.SystemClock.elapsedRealtime())
        return null
    }

    /**
     * Довести свою запись до нужного входа и проверить, что она там
     * ([moveFeed]). Посреди тейка переезд на наушники звенит в них «говори».
     */
    private fun followFeed(wantHeadset: Boolean, sinceMs: Long) {
        routeWatch?.let { main.removeCallbacks(it) }
        val f = feed ?: return
        moveFeed(f, wantHeadset, sinceMs, cue = if (wantHeadset && readyFired) Cue.CHIME else Cue.READY)
    }

    /** Что сказать, когда запись встала на вход. */
    private enum class Cue {
        /** «Говори» тейка (один раз за тейк: [cueWhenHeard]). */
        READY,

        /** Посреди тейка: звон «говори» в наушники — «слушаю тут». */
        CHIME,

        /** Ничего. */
        NONE,
    }

    /**
     * Перевести свою запись на вход и убедиться, что она там: вход спрашивается
     * у самой записи (`routedDevice`). Гарнитура не отдала вход за
     * [FEED_ROUTE_MS] — обратно на телефон, и записка словами.
     */
    private fun moveFeed(f: MicFeed, wantHeadset: Boolean, sinceMs: Long, cue: Cue) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        f.setDevice(if (wantHeadset) MicRouting.headsetMic(am) else MicRouting.builtinMic(am))
        headsetMic = wantHeadset
        routeMoving = true
        publishMic()
        val step = object : Runnable {
            override fun run() {
                if (feed !== f || !active || stopping) return
                val waited = android.os.SystemClock.elapsedRealtime() - sinceMs
                val routed = f.routed()
                val arrived = routed != null && MicRouting.isHeadsetDevice(routed) == wantHeadset
                when {
                    arrived -> {
                        routeWatch = null
                        routeMoving = false
                        headsetMic = wantHeadset
                        if (wantHeadset) {
                            // Наушники встали — если снова отвалятся, об этом снова скажет звук.
                            headsetDropNoticed = false
                            headsetZerosNoted = false
                            headsetHeardAtMs = android.os.SystemClock.elapsedRealtime()
                        } else {
                            headsetHeardAtMs = 0L
                        }
                        health.heard(MicRouting.label(routed))
                        onLog("микрофон встал за $waited мс: слушает ${MicRouting.label(routed)}")
                        publishMic()
                        // Звук пошёл оттуда, откуда просили, — теперь «говори» правда.
                        when (cue) {
                            Cue.READY -> cueWhenHeard()
                            Cue.CHIME -> readySink?.let { sink -> runCatching { sink(true) } }
                            Cue.NONE -> Unit
                        }
                    }
                    waited < FEED_ROUTE_MS -> main.postDelayed(this, FEED_POLL_MS)
                    wantHeadset -> {
                        f.setDevice(MicRouting.builtinMic(am))
                        routeWatch = null
                        routeMoving = false
                        headsetMic = false
                        onLog("гарнитура так и не отдала вход (запись: ${MicRouting.label(routed)}) — слушает телефон")
                        if (routeOurs) {
                            routeOurs = false
                            MicRouting.drop(am, onLog)
                        }
                        health.heard("телефон")
                        headsetLost("Наушники не отдали микрофон — слушает телефон")
                        publishMic()
                        cueWhenHeard()
                    }
                    else -> {
                        routeWatch = null
                        routeMoving = false
                        headsetMic = MicRouting.isHeadsetDevice(routed)
                        health.heard(MicRouting.label(routed))
                        onLog("микрофон: запись слушает ${MicRouting.label(routed)}")
                        publishMic()
                        cueWhenHeard()
                    }
                }
            }
        }
        routeWatch = step
        main.post(step)
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
