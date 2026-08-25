package ru.zf.pravka.desktop.input

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.target.TextTarget

// Откуда брать текст и куда его возвращать на воркстанции.
//
// Выделение читается через Ctrl+C, результат уезжает через Ctrl+V. Службы
// доступности, как на Android, здесь нет, поэтому прочитать всё поле без
// выделения нельзя - и это осознанная граница первой версии: нет выделения,
// значит работаем с тем, что в буфере (так же ведут себя оранжевые действия
// на телефоне). Полное чтение поля добавит UI Automation, см.
// docs/workstation.md.
class WindowsTarget(
    /** Готовый текст вместо чтения из окна: надиктованное уже у нас в руках. */
    private val preset: String? = null,
) : TextTarget {

    private var fragment = false

    /** Что мы в итоге написали - на случай, если писать оказалось некуда. */
    var lastWritten: String? = null
        private set

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (preset != null) {
            fragment = true
            return@withContext preset
        }
        val selection = Keyboard.readSelection()
        if (selection != null) {
            fragment = true
            return@withContext selection
        }
        fragment = false
        Keyboard.clipboardText()
    }

    override suspend fun write(text: String): Boolean = withContext(Dispatchers.IO) {
        lastWritten = text
        // Буфер владельца НЕ восстанавливаем: на телефоне результат правки
        // тоже остаётся в буфере - это страховка на случай, если вставка
        // не долетела до поля.
        Keyboard.paste(text, restoreClipboard = false)
    }

    override fun isExplicitFragment(): Boolean = fragment
}
