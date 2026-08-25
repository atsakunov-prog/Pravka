package ru.zf.pravka.desktop.input

import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent

// Единственный способ положить текст в поле чужого приложения на Windows -
// буфер обмена и Ctrl+V. Печатать посимвольно нельзя: на кириллице это
// зависит от раскладки в момент нажатия и ломается.
//
// Буфер владельца при этом священен: то, что в нём лежало, возвращается на
// место после вставки.
object Keyboard {

    private val robot: Robot? = runCatching { Robot() }.getOrNull()
    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    val available: Boolean get() = robot != null

    /** Текущее содержимое буфера или null, если там не текст. */
    fun clipboardText(): String? = runCatching {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        } else null
    }.getOrNull()

    fun setClipboard(text: String) {
        // Буфером в этот момент может владеть другое приложение - тогда
        // системный вызов бросает, и надо просто попробовать ещё раз.
        repeat(5) {
            if (runCatching { clipboard.setContents(StringSelection(text), null) }.isSuccess) return
            Thread.sleep(30)
        }
    }

    /**
     * Выделенный в чужом окне текст: шлём Ctrl+C и смотрим, изменился ли буфер.
     * Вернёт null, если выделения нет (буфер не тронулся за отведённое время).
     * Прежнее содержимое буфера восстанавливается в любом случае.
     */
    fun readSelection(): String? {
        val r = robot ?: return null
        val previous = clipboardText()
        val sentinel = " правка-нет-выделения "
        setClipboard(sentinel)

        tap(r, KeyEvent.VK_C, KeyEvent.VK_CONTROL)

        // Чужое приложение копирует не мгновенно; ждём короткими шагами, а не
        // одной большой паузой, чтобы не задерживать быстрые случаи.
        var captured: String? = null
        for (attempt in 0 until 20) {
            Thread.sleep(15)
            val now = clipboardText()
            if (now != null && now != sentinel) {
                captured = now
                break
            }
        }

        setClipboard(previous ?: "")
        return captured?.takeIf { it.isNotBlank() }
    }

    /** Кладёт текст в поле под курсором. */
    fun paste(text: String, restoreClipboard: Boolean = false): Boolean {
        val r = robot ?: return false
        val previous = if (restoreClipboard) clipboardText() else null
        setClipboard(text)
        Thread.sleep(40)   // дать буферу устояться до нажатия
        tap(r, KeyEvent.VK_V, KeyEvent.VK_CONTROL)
        if (previous != null) {
            Thread.sleep(300)  // вставка должна успеть прочитать буфер
            setClipboard(previous)
        }
        return true
    }

    private fun tap(r: Robot, key: Int, vararg modifiers: Int) {
        modifiers.forEach { r.keyPress(it) }
        r.keyPress(key)
        r.keyRelease(key)
        modifiers.reversed().forEach { r.keyRelease(it) }
    }
}
