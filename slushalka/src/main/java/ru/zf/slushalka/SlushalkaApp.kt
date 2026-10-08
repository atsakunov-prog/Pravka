package ru.zf.slushalka

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.zf.slushalka.ask.AskEngine
import ru.zf.slushalka.speech.ChunkRecognizer
import ru.zf.slushalka.ask.ClaudeClient
import ru.zf.slushalka.ask.GuideEngine
import ru.zf.slushalka.speech.Speaker
import ru.zf.slushalka.catalog.Advisor
import ru.zf.slushalka.catalog.CatalogState
import ru.zf.slushalka.data.AskLog
import ru.zf.slushalka.data.Bookmarks
import ru.zf.slushalka.data.GuideStore
import ru.zf.slushalka.stats.Journal
import ru.zf.slushalka.data.LibraryStore
import ru.zf.slushalka.data.Markup
import ru.zf.slushalka.data.Notes
import ru.zf.slushalka.data.PositionStore
import ru.zf.slushalka.data.PositionSync
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.update.Updater
import ru.zf.slushalka.player.PlayerHolder
import ru.zf.slushalka.speech.ReadAloud
import ru.zf.slushalka.text.TextRepo
import ru.zf.slushalka.ui.AppState

/** Сервис-локатор: всё хозяйство приложения в одном месте, как PravkaApp. */
class SlushalkaApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: Settings; private set
    lateinit var positions: PositionStore; private set
    lateinit var library: LibraryStore; private set
    /** Когда книга легла на полку - для порядка «Добавленные». */
    lateinit var added: ru.zf.slushalka.data.AddedStore; private set
    lateinit var texts: TextRepo; private set
    lateinit var bookmarks: Bookmarks; private set
    lateinit var askLog: AskLog; private set
    lateinit var notes: Notes; private set
    lateinit var journal: Journal; private set
    lateinit var sync: PositionSync; private set
    lateinit var cloud: ru.zf.slushalka.data.Cloud; private set
    lateinit var cloudBooks: ru.zf.slushalka.data.CloudBooks; private set
    /** Библиотека на домашнем сервере: оглавление `index.json` и обложки. */
    lateinit var server: ru.zf.slushalka.data.ServerLibrary; private set
    lateinit var markup: Markup; private set
    lateinit var updater: Updater; private set
    lateinit var player: PlayerHolder; private set
    /** Звук с сервера: кэш записи, подкачка, источник для распознавания. */
    lateinit var streaming: ru.zf.slushalka.player.Streaming; private set
    lateinit var ask: AskEngine; private set
    lateinit var guide: GuideEngine; private set
    /** Разбор книги сервером: лист «Разбор», «что было раньше», заказ. */
    lateinit var razbor: ru.zf.slushalka.ask.RazborEngine; private set
    /** Озвучка книги нейросетью на сервере: заказ, статус, пометка «озвучено нейросетью». */
    lateinit var nightVoice: ru.zf.slushalka.data.NightVoice; private set
    /** «Книга за 15 минут» - короткая запись с сервера мимо плеера книги. */
    lateinit var clip: ru.zf.slushalka.player.ClipPlayer; private set
    lateinit var search: ru.zf.slushalka.ask.MeaningSearch; private set
    lateinit var talk: ru.zf.slushalka.ask.BookTalk; private set
    lateinit var speaker: Speaker; private set
    lateinit var recognizer: ChunkRecognizer; private set
    lateinit var state: AppState; private set
    /** Сверка полки с сервером: какая своя папка - какая книга там, что доложить. */
    lateinit var shelf: ru.zf.slushalka.data.ShelfSync; private set
    lateinit var catalog: CatalogState; private set
    lateinit var readAloud: ReadAloud; private set
    lateinit var advisor: Advisor; private set

    override fun onCreate() {
        super.onCreate()
        // Вход в Google Drive снят 26.09.2026 (владелец: «Только с личным
        // облаком»): ключ прежнего входа больше ничему не нужен.
        runCatching { java.io.File(filesDir, "google-auth.json").delete() }
        settings = Settings(this, scope)
        positions = PositionStore(this)
        library = LibraryStore(this)
        added = ru.zf.slushalka.data.AddedStore(this)
        // Облако раньше текстов: книга с сервера качает текст через него.
        cloud = ru.zf.slushalka.data.Cloud(settings)
        texts = TextRepo(this, cloud)
        bookmarks = Bookmarks(this)
        askLog = AskLog(this)
        notes = Notes(this)
        journal = Journal(this)
        sync = PositionSync(this)
        server = ru.zf.slushalka.data.ServerLibrary(this, settings, cloud, scope)
        // Разметка и справочник - в папке книги, а у книги со звуком на сервере ещё и там.
        val bookDir = ru.zf.slushalka.data.BookDir(this, settings, cloud)
        // Есть ли книга на сервере - по оглавлению: своя копия книги из
        // библиотеки делит с ней справочник и разметку.
        bookDir.serverDirOf = { b ->
            b.remoteDir.ifBlank { null } ?: server.index.value?.let { idx -> idx.byFolder(b.folderName)?.let(idx::dirOf) }
        }
        markup = Markup(bookDir)
        nightVoice = ru.zf.slushalka.data.NightVoice(settings, bookDir, server)
        updater = Updater(this, settings)
        speaker = Speaker(this)
        recognizer = ChunkRecognizer(this)
        val claude = ClaudeClient(settings)
        ask = AskEngine(settings, claude, askLog)
        guide = GuideEngine(this, settings, claude, GuideStore(this), askLog, bookDir)
        razbor = ru.zf.slushalka.ask.RazborEngine(settings, ru.zf.slushalka.data.RazborStore(this), bookDir, guide)
        advisor = Advisor(this, claude)
        search = ru.zf.slushalka.ask.MeaningSearch(claude, settings, askLog)
        talk = ru.zf.slushalka.ask.BookTalk(claude, settings, askLog)
        streaming = ru.zf.slushalka.player.Streaming(this, settings, cloud)
        clip = ru.zf.slushalka.player.ClipPlayer(this, streaming, cloud, scope)
        player = PlayerHolder(this, settings, positions, journal, streaming) { bookId ->
            scope.launch { state.syncPush(bookId) }
        }
        shelf = ru.zf.slushalka.data.ShelfSync(this)
        state = AppState(this)
        cloudBooks = ru.zf.slushalka.data.CloudBooks(this)
        catalog = CatalogState(this)
        readAloud = ReadAloud(this)
        player.artworkFor = { bookId, absMs -> state.pictureUriAt(bookId, absMs) }
        // Полка поднимается последней: её пересборка зовёт сверку, а та - всё
        // хозяйство выше.
        state.start()
    }
}
