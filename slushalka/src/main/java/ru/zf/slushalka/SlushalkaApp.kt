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
    lateinit var texts: TextRepo; private set
    lateinit var bookmarks: Bookmarks; private set
    lateinit var askLog: AskLog; private set
    lateinit var notes: Notes; private set
    lateinit var journal: Journal; private set
    lateinit var sync: PositionSync; private set
    lateinit var cloud: ru.zf.slushalka.data.Cloud; private set
    lateinit var cloudBooks: ru.zf.slushalka.data.CloudBooks; private set
    lateinit var markup: Markup; private set
    lateinit var updater: Updater; private set
    lateinit var player: PlayerHolder; private set
    lateinit var ask: AskEngine; private set
    lateinit var guide: GuideEngine; private set
    lateinit var search: ru.zf.slushalka.ask.MeaningSearch; private set
    lateinit var talk: ru.zf.slushalka.ask.BookTalk; private set
    lateinit var speaker: Speaker; private set
    lateinit var recognizer: ChunkRecognizer; private set
    lateinit var state: AppState; private set
    lateinit var catalog: CatalogState; private set
    lateinit var readAloud: ReadAloud; private set
    lateinit var advisor: Advisor; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this, scope)
        positions = PositionStore(this)
        library = LibraryStore(this)
        texts = TextRepo(this)
        bookmarks = Bookmarks(this)
        askLog = AskLog(this)
        notes = Notes(this)
        journal = Journal(this)
        sync = PositionSync(this)
        cloud = ru.zf.slushalka.data.Cloud(settings)
        markup = Markup(this)
        updater = Updater(this, settings)
        speaker = Speaker(this)
        recognizer = ChunkRecognizer(this)
        val claude = ClaudeClient(settings)
        ask = AskEngine(settings, claude, askLog)
        guide = GuideEngine(this, settings, claude, GuideStore(this), askLog)
        advisor = Advisor(this, claude)
        search = ru.zf.slushalka.ask.MeaningSearch(claude, settings, askLog)
        talk = ru.zf.slushalka.ask.BookTalk(claude, settings, askLog)
        player = PlayerHolder(this, settings, positions, journal) { bookId ->
            scope.launch { state.syncPush(bookId) }
        }
        state = AppState(this)
        cloudBooks = ru.zf.slushalka.data.CloudBooks(this)
        catalog = CatalogState(this)
        readAloud = ReadAloud(this)
        player.artworkFor = { bookId, absMs -> state.pictureUriAt(bookId, absMs) }
    }
}
