package ru.zf.slushalka

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import ru.zf.slushalka.data.Saf
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.ui.AskSheet
import ru.zf.slushalka.ui.CatalogScreen
import ru.zf.slushalka.ui.LibraryScreen
import ru.zf.slushalka.ui.PlayerScreen
import ru.zf.slushalka.ui.ReaderScreen
import ru.zf.slushalka.ui.SettingsScreen
import ru.zf.slushalka.ui.SlushalkaTheme
import ru.zf.slushalka.ui.StatsScreen
import ru.zf.slushalka.ui.TalkSheet
import ru.zf.slushalka.widget.ContinueWidget

enum class Screen { LIBRARY, PLAYER, READER, SETTINGS, CATALOG, STATS, CLOUD }

class MainActivity : ComponentActivity() {

    private val app get() = application as SlushalkaApp

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { app.state.onTreePicked(it) }
    }

    /** Ещё одна папка библиотеки - к главной. */
    private val pickExtraTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { app.state.onExtraTreePicked(it) }
    }

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Тап по виджету «Продолжить»: открыть последнюю книгу, минуя полку. */
    private val continueAsked = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** «Спросить голосом» из шторки или с виджета. */
    private val voiceAsked = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun takeIntent(intent: Intent?) {
        when (intent?.action) {
            ContinueWidget.ACTION_CONTINUE -> continueAsked.value = true
            ru.zf.slushalka.player.Shade.ACTION_VOICE_ASK -> voiceAsked.value = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeIntent(intent)
        // Уведомление - это и есть плеер на экране блокировки; без него книга
        // играет вслепую.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            // Масштаб интерфейса - множитель к плотности экрана: всё, что
            // измерено в dp и sp, растёт разом. На читалке с большим экраном
            // система считает плотность малой, и без этого кнопки мелкие.
            val prefs by app.settings.flow.collectAsState()
            val base = LocalDensity.current
            val density = remember(base, prefs.uiScale) { Density(base.density * prefs.uiScale, base.fontScale) }
            CompositionLocalProvider(LocalDensity provides density) {
                SlushalkaTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Root(
                            // Пикер открывается сразу в Downloads: библиотека живёт в
                            // Downloads/Books, чтобы всё лежало в одном видном месте.
                            onPickTree = { pickTree.launch(Saf.downloadsUri()) },
                            onPickExtraTree = { pickExtraTree.launch(null) },
                            onNeedMic = { askPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            hasMic = {
                                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.state.syncPull()
        // Сам Updater не спрашивает чаще раза в полчаса.
        app.scope.launch { app.updater.check(manual = false) }
    }

    override fun onStop() {
        super.onStop()
        // Уход из приложения - такой же повод записать позицию, как пауза.
        app.player.saveNow()
        // И журнал подходов тоже: идущий подход лежит на диске не старше минуты.
        app.journal.saveNow()
        app.positions.flush()
        // Виджет - последней строкой: место уже записано, он покажет свежее.
        ContinueWidget.refresh(app)
    }

    @Composable
    private fun Root(
        onPickTree: () -> Unit,
        onPickExtraTree: () -> Unit,
        onNeedMic: () -> Unit,
        hasMic: () -> Boolean,
    ) {
        val state = app.state
        var screen by remember { mutableStateOf(Screen.LIBRARY) }
        var asking by remember { mutableStateOf(false) }
        var askAtChar by remember { mutableStateOf<Int?>(null) }
        var askPrefill by remember { mutableStateOf<String?>(null) }
        var askQuote by remember { mutableStateOf<String?>(null) }
        var askHandsFree by remember { mutableStateOf(false) }
        // Разговор о книге: поверх любого экрана, как вопрос.
        var talking by remember { mutableStateOf(false) }
        var talkAt by remember { mutableStateOf<Int?>(null) }
        var talkDone by remember { mutableStateOf<Boolean?>(null) }
        val current by state.current.collectAsState()

        // Куда возвращаться из читалки: книге без записи плеер не нужен,
        // из неё «назад» - сразу на полку.
        fun afterReader(): Screen = if (current?.hasAudio == false) Screen.LIBRARY else Screen.PLAYER

        // Книга со звуком открывается плеером, книга из каталога - сразу читалкой.
        val openBook: (Book) -> Unit = { book ->
            state.open(book)
            if (book.hasAudio) {
                startPlayback()
                screen = Screen.PLAYER
            } else {
                screen = Screen.READER
            }
        }

        // Виджет «Продолжить»: книга открывается там же, где открылась бы с
        // полки, - только без полки. Уже открытая не переоткрывается.
        val asked by continueAsked.collectAsState()
        val books by state.books.collectAsState()
        LaunchedEffect(asked, books) {
            if (!asked || books.isEmpty()) return@LaunchedEffect
            continueAsked.value = false
            val last = books.firstOrNull { it.id == app.positions.lastBook() } ?: return@LaunchedEffect
            if (current?.id == last.id) {
                screen = if (last.hasAudio) Screen.PLAYER else Screen.READER
            } else {
                openBook(last)
            }
        }

        // Вопрос голосом: книга та, что играет или была последней; вопрос - с
        // микрофоном сразу. Книгу без записи спрашиваем с места чтения.
        val voice by voiceAsked.collectAsState()
        LaunchedEffect(voice, books) {
            if (!voice || books.isEmpty()) return@LaunchedEffect
            voiceAsked.value = false
            val book = current ?: books.firstOrNull { it.id == app.positions.lastBook() } ?: return@LaunchedEffect
            if (current?.id != book.id) openBook(book)
            askAtChar = if (book.hasAudio) null else state.stateOf(book.id).readChar.coerceAtLeast(0)
            askPrefill = null
            askQuote = null
            askHandsFree = true
            asking = true
        }

        BackHandler(enabled = screen != Screen.LIBRARY || asking || talking) {
            when {
                talking -> talking = false
                asking -> asking = false
                screen == Screen.READER -> screen = afterReader()
                // В каталоге «назад» сперва снимает верхнюю ленту, и только с корня уходит.
                screen == Screen.CATALOG -> if (!app.catalog.back()) screen = Screen.LIBRARY
                else -> screen = Screen.LIBRARY
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(
                    app = app,
                    onPickTree = onPickTree,
                    onOpen = openBook,
                    onSettings = { screen = Screen.SETTINGS },
                    onCatalog = { screen = Screen.CATALOG },
                    onStats = { screen = Screen.STATS },
                    onCloud = { screen = Screen.CLOUD },
                    onTalk = { book ->
                        openBook(book)
                        talkAt = null
                        talkDone = null
                        talking = true
                    },
                )

                Screen.STATS -> StatsScreen(app = app, onBack = { screen = Screen.LIBRARY })

                Screen.CLOUD -> ru.zf.slushalka.ui.CloudScreen(
                    app = app,
                    onBack = { screen = Screen.LIBRARY },
                    onSettings = { screen = Screen.SETTINGS },
                )

                Screen.CATALOG -> CatalogScreen(
                    app = app,
                    hasMic = hasMic,
                    onNeedMic = onNeedMic,
                    onClose = { screen = Screen.LIBRARY },
                    onOpenBook = openBook,
                )

                Screen.PLAYER -> PlayerScreen(
                    app = app,
                    onBack = { screen = Screen.LIBRARY },
                    onAsk = { at, question ->
                        askAtChar = at
                        askPrefill = question
                        asking = true
                    },
                    onRead = {
                        // Перешёл читать - звук замолкает: слушать и читать
                        // одновременно всё равно не выходит.
                        app.player.pauseForAsking()
                        screen = Screen.READER
                    },
                    onSettings = { screen = Screen.SETTINGS },
                    onTalk = {
                        talkAt = null
                        talkDone = null
                        talking = true
                    },
                )

                Screen.READER -> ReaderScreen(
                    app = app,
                    onBack = { screen = afterReader() },
                    onListen = { screen = Screen.PLAYER },
                    onAsk = { at, question, quote ->
                        askAtChar = at
                        askPrefill = question
                        askQuote = quote
                        asking = true
                    },
                    hasMic = hasMic,
                    onNeedMic = onNeedMic,
                    onTalk = { at, done ->
                        talkAt = at
                        talkDone = done
                        talking = true
                    },
                )

                Screen.SETTINGS -> SettingsScreen(
                    app = app,
                    onBack = {
                        val c = current
                        screen = when {
                            c == null -> Screen.LIBRARY
                            c.hasAudio -> Screen.PLAYER
                            else -> Screen.READER
                        }
                    },
                    onPickTree = onPickTree,
                    onPickExtraTree = onPickExtraTree,
                )
            }

            if (talking) {
                TalkSheet(
                    app = app,
                    cutoff = talkAt,
                    finished = talkDone,
                    hasMic = hasMic,
                    onNeedMic = onNeedMic,
                    onClose = { talking = false },
                )
            }

            if (asking) {
                AskSheet(
                    app = app,
                    hasMic = hasMic,
                    onNeedMic = onNeedMic,
                    onClose = { asking = false; askAtChar = null; askPrefill = null; askQuote = null; askHandsFree = false },
                    atChar = askAtChar,
                    initialQuestion = askPrefill,
                    quote = askQuote,
                    handsFree = askHandsFree,
                )
            }
        }
    }

    private fun startPlayback() {
        // Служба поднимается из видимого экрана: дальше media3 сама переводит
        // её в передний план, когда книга зазвучит.
        runCatching {
            startService(Intent(this, ru.zf.slushalka.player.PlaybackService::class.java))
        }
    }
}
