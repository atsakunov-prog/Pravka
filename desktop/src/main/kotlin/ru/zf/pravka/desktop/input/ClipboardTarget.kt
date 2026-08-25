package ru.zf.pravka.desktop.input

import ru.zf.pravka.target.TextTarget

// Запасной приёмник результата: то же, что ClipboardTarget на телефоне.
// Движок кладёт сюда каждый готовый текст - страховка на случай, если
// вставка в поле не долетела.
class ClipboardTarget : TextTarget {

    override suspend fun read(): String? = Keyboard.clipboardText()

    override suspend fun write(text: String): Boolean {
        Keyboard.setClipboard(text)
        return true
    }
}
