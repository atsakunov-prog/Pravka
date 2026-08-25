package ru.zf.pravka.desktop.input

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

// Глобальные горячие клавиши: обычный слушатель Swing видит только своё окно,
// а нажатие надо поймать в чужом - там, где владелец печатает.
//
// Клавиша диктовки отдаёт и нажатие, и отпускание: короткий тап включает
// режим "говорю долго", а зажатая клавиша работает как рация - ровно так же,
// как тап и удержание кнопки "П" на телефоне.
object Hotkeys {

    class Combo(val keyCode: Int, val modifiers: Int)

    private const val MODIFIER_MASK =
        NativeKeyEvent.CTRL_MASK or NativeKeyEvent.ALT_MASK or
            NativeKeyEvent.SHIFT_MASK or NativeKeyEvent.META_MASK

    private class Binding(
        val combo: Combo,
        val onPress: () -> Unit,
        val onRelease: (() -> Unit)?,
    ) {
        var down = false
    }

    private val bindings = mutableListOf<Binding>()
    @Volatile private var started = false

    /** Разбирает "ctrl+alt+space". Вернёт null, если такую клавишу не знаем. */
    fun parse(spec: String): Combo? {
        var modifiers = 0
        var keyCode = -1
        for (raw in spec.lowercase().split("+")) {
            when (val part = raw.trim()) {
                "" -> continue
                "ctrl", "control" -> modifiers = modifiers or NativeKeyEvent.CTRL_MASK
                "alt" -> modifiers = modifiers or NativeKeyEvent.ALT_MASK
                "shift" -> modifiers = modifiers or NativeKeyEvent.SHIFT_MASK
                "win", "meta" -> modifiers = modifiers or NativeKeyEvent.META_MASK
                else -> keyCode = keyCode(part) ?: return null
            }
        }
        return if (keyCode >= 0) Combo(keyCode, modifiers) else null
    }

    private fun keyCode(name: String): Int? = when {
        name.length == 1 && name[0] in 'a'..'z' ->
            NativeKeyEvent.VC_A + (name[0] - 'a')
        name.length == 1 && name[0] in '0'..'9' ->
            NativeKeyEvent.VC_0 + (name[0] - '0')
        name == "space" -> NativeKeyEvent.VC_SPACE
        name == "enter" -> NativeKeyEvent.VC_ENTER
        name == "tab" -> NativeKeyEvent.VC_TAB
        name == "escape" || name == "esc" -> NativeKeyEvent.VC_ESCAPE
        name == "backquote" || name == "tilde" -> NativeKeyEvent.VC_BACKQUOTE
        name.startsWith("f") && name.drop(1).toIntOrNull() in 1..12 ->
            NativeKeyEvent.VC_F1 + (name.drop(1).toInt() - 1)
        else -> null
    }

    /** Человеческое имя комбинации для подсказки в интерфейсе. */
    fun describe(spec: String): String = spec.split("+").joinToString(" + ") { part ->
        when (part.trim().lowercase()) {
            "ctrl", "control" -> "Ctrl"
            "alt" -> "Alt"
            "shift" -> "Shift"
            "win", "meta" -> "Win"
            "space" -> "Пробел"
            else -> part.trim().uppercase()
        }
    }

    fun bind(spec: String, onPress: () -> Unit, onRelease: (() -> Unit)? = null): Boolean {
        val combo = parse(spec) ?: return false
        synchronized(bindings) { bindings.add(Binding(combo, onPress, onRelease)) }
        return true
    }

    fun clear() = synchronized(bindings) { bindings.clear() }

    /** Ставит системный хук. Возвращает текст ошибки, если не получилось. */
    fun start(): String? {
        if (started) return null
        // Библиотека по умолчанию сыплет в консоль на каждое нажатие.
        Logger.getLogger(GlobalScreen::class.java.getPackage().name).apply {
            level = Level.WARNING
            useParentHandlers = false
        }
        return try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(listener)
            started = true
            null
        } catch (e: Throwable) {
            "Горячие клавиши не работают: ${e.message}"
        }
    }

    fun stop() {
        if (!started) return
        runCatching { GlobalScreen.removeNativeKeyListener(listener) }
        runCatching { GlobalScreen.unregisterNativeHook() }
        started = false
    }

    private val listener = object : NativeKeyListener {

        override fun nativeKeyPressed(e: NativeKeyEvent) {
            val snapshot = synchronized(bindings) { bindings.toList() }
            for (b in snapshot) {
                if (b.down) continue
                if (e.keyCode != b.combo.keyCode) continue
                if ((e.modifiers and MODIFIER_MASK) != b.combo.modifiers) continue
                b.down = true
                // Обработчик уходит со своего потока: хук Windows держит
                // очередь ввода всей системы, и всё, что делается прямо здесь,
                // подтормаживает набор в чужом окне.
                fire(b.onPress)
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            val snapshot = synchronized(bindings) { bindings.toList() }
            for (b in snapshot) {
                if (!b.down) continue
                // Модификаторы к моменту отпускания часто уже отпущены,
                // поэтому здесь сверяем только саму клавишу.
                if (e.keyCode != b.combo.keyCode &&
                    e.keyCode !in setOf(
                        NativeKeyEvent.VC_CONTROL, NativeKeyEvent.VC_ALT,
                        NativeKeyEvent.VC_SHIFT, NativeKeyEvent.VC_META,
                    )
                ) continue
                if (e.keyCode != b.combo.keyCode) continue
                b.down = false
                b.onRelease?.let { fire(it) }
            }
        }

        override fun nativeKeyTyped(e: NativeKeyEvent) = Unit
    }

    private fun fire(block: () -> Unit) {
        Thread({ runCatching { block() } }, "pravka-hotkey").apply { isDaemon = true }.start()
    }
}
