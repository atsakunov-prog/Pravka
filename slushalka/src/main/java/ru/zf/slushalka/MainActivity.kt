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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import ru.zf.slushalka.ui.AskSheet
import ru.zf.slushalka.ui.LibraryScreen
import ru.zf.slushalka.ui.PlayerScreen
import ru.zf.slushalka.ui.ReaderScreen
import ru.zf.slushalka.ui.SettingsScreen
import ru.zf.slushalka.ui.SlushalkaTheme

enum class Screen { LIBRARY, PLAYER, READER, SETTINGS }

class MainActivity : ComponentActivity() {

    private val app get() = application as SlushalkaApp

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { app.state.onTreePicked(it) }
    }

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Уведомление - это и есть плеер на экране блокировки; без него книга
        // играет вслепую.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SlushalkaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Root(
                        onPickTree = { pickTree.launch(null) },
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

    override fun onStart() {
        super.onStart()
        app.state.syncPull()
    }

    override fun onStop() {
        super.onStop()
        // Уход из приложения - такой же повод записать позицию, как пауза.
        app.player.saveNow()
        app.positions.flush()
    }

    @Composable
    private fun Root(onPickTree: () -> Unit, onNeedMic: () -> Unit, hasMic: () -> Boolean) {
        val state = app.state
        var screen by remember { mutableStateOf(Screen.LIBRARY) }
        var asking by remember { mutableStateOf(false) }
        var askAtChar by remember { mutableStateOf<Int?>(null) }
        val current by state.current.collectAsState()

        BackHandler(enabled = screen != Screen.LIBRARY || asking) {
            when {
                asking -> asking = false
                screen == Screen.READER -> screen = Screen.PLAYER
                else -> screen = Screen.LIBRARY
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(
                    app = app,
                    onPickTree = onPickTree,
                    onOpen = { book, play ->
                        state.open(book, autoPlay = play)
                        startPlayback()
                        screen = Screen.PLAYER
                    },
                    onSettings = { screen = Screen.SETTINGS },
                )

                Screen.PLAYER -> PlayerScreen(
                    app = app,
                    onBack = { screen = Screen.LIBRARY },
                    onAsk = { asking = true },
                    onRead = {
                        // Перешёл читать - звук замолкает: слушать и читать
                        // одновременно всё равно не выходит.
                        app.player.pauseForAsking()
                        screen = Screen.READER
                    },
                    onSettings = { screen = Screen.SETTINGS },
                )

                Screen.READER -> ReaderScreen(
                    app = app,
                    onBack = { screen = Screen.PLAYER },
                    onListen = { screen = Screen.PLAYER },
                    onAsk = { at -> askAtChar = at; asking = true },
                )

                Screen.SETTINGS -> SettingsScreen(
                    app = app,
                    onBack = { screen = if (current != null) Screen.PLAYER else Screen.LIBRARY },
                    onPickTree = onPickTree,
                )
            }

            if (asking) {
                AskSheet(
                    app = app,
                    hasMic = hasMic,
                    onNeedMic = onNeedMic,
                    onClose = { asking = false; askAtChar = null },
                    atChar = askAtChar,
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
