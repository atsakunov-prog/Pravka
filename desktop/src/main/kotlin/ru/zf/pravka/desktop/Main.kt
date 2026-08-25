package ru.zf.pravka.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.MouseInfo
import java.awt.Toolkit
import ru.zf.pravka.desktop.input.Hotkeys
import ru.zf.pravka.desktop.input.Keyboard
import ru.zf.pravka.desktop.ui.ActionMenuContent
import ru.zf.pravka.desktop.ui.MainWindowContent
import ru.zf.pravka.desktop.ui.OverlayContent
import ru.zf.pravka.desktop.ui.PravkaIcon

// Точка входа. Программа живёт в трее: окно нужно только для словаря и
// настроек, работа идёт с горячих клавиш поверх чужих приложений.
fun main() = application {
    val controller = remember { Controller() }
    val state by controller.state.collectAsState()

    var windowOpen by remember { mutableStateOf(!Keyboard.available) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf(0 to 0) }
    var hotkeyError by remember { mutableStateOf<String?>(null) }
    var hotkeyEpoch by remember { mutableStateOf(0) }

    fun openMenuAtPointer() {
        val point = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        menuAt = (point?.x ?: 400) to (point?.y ?: 400)
        menuOpen = true
    }

    // Общий словарь: обмен при запуске, дальше раз в 12 часов. Без адреса
    // таблицы maybeSync молча ничего не делает.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { DesktopApp.sync.maybeSync() }
            kotlinx.coroutines.delay(30 * 60 * 1000L)
        }
    }

    LaunchedEffect(hotkeyEpoch) {
        DesktopApp.transcripts.load()
        hotkeyError = Hotkeys.start()
        Hotkeys.clear()
        val keys = DesktopApp.settings.hotkeysFlow.value
        Hotkeys.bind(keys.dictate, onPress = { controller.onDictatePress() }, onRelease = { controller.onDictateRelease() })
        Hotkeys.bind(keys.clean, onPress = { controller.clean() })
        Hotkeys.bind(keys.menu, onPress = { openMenuAtPointer() })
        Hotkeys.bind(keys.undo, onPress = { controller.undo() })
    }

    Tray(
        icon = PravkaIcon,
        tooltip = "Правка",
        menu = {
            Item("Диктовка") {
                // Из трея - режим "говорю долго": нажатие и сразу отпускание.
                controller.onDictatePress()
                controller.onDictateRelease()
            }
            Item("Причесать") { controller.clean() }
            Item("Меню правки") { openMenuAtPointer() }
            Item("Отменить") { controller.undo() }
            Separator()
            Item("Словарь и настройки") { windowOpen = true }
            Item("Сброс") { controller.reset() }
            Item("Выход") {
                Hotkeys.stop()
                exitApplication()
            }
        },
    )

    if (windowOpen) {
        Window(
            onCloseRequest = { windowOpen = false },
            title = "Правка",
            icon = PravkaIcon,
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            PravkaTheme {
                MainWindowContent(controller, onHotkeysChanged = { hotkeyEpoch++ })
            }
        }
    }

    // Плашка: появляется, только когда есть что показать, и фокус не забирает -
    // иначе курсор уйдёт из поля, куда мы собираемся писать.
    val overlayVisible = state.phase != Controller.Phase.IDLE || state.title.isNotBlank()
    if (overlayVisible) {
        val screen = Toolkit.getDefaultToolkit().screenSize
        Window(
            onCloseRequest = {},
            undecorated = true,
            transparent = true,
            resizable = false,
            focusable = false,
            alwaysOnTop = true,
            state = rememberWindowState(
                width = 560.dp, height = 140.dp,
                position = WindowPosition((screen.width / 2 - 280).dp, (screen.height - 260).dp),
            ),
        ) {
            PravkaTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    OverlayContent(state, controller.level)
                }
            }
        }
    }

    if (menuOpen) {
        Window(
            onCloseRequest = { menuOpen = false },
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            state = rememberWindowState(
                width = 520.dp, height = 130.dp,
                position = WindowPosition(menuAt.first.dp, menuAt.second.dp),
            ),
            onKeyEvent = { menuOpen = false; true },
        ) {
            PravkaTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    ActionMenuContent(controller) { menuOpen = false }
                }
            }
        }
    }

    if (hotkeyError != null && !windowOpen) {
        // Без горячих клавиш программа бесполезна: показываем окно, чтобы
        // владелец увидел причину, а не гадал, почему ничего не происходит.
        LaunchedEffect(hotkeyError) { windowOpen = true }
    }
}

@androidx.compose.runtime.Composable
private fun PravkaTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PravkaIcon.accent,
            background = Color(0xFF121212),
            surface = Color(0xFF1B1B1B),
        ),
        content = content,
    )
}
