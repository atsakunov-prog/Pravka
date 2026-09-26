package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Подсказка пилюли (docs/pravka.md, «Пилюля диктовки»): «Саша, слушаю» —
// по имени из профиля, без эмодзи.
class PillHintTest {

    @Test
    fun `по имени из профиля`() {
        assertEquals("Саша, слушаю", PillHint.listening("Саша"))
        assertEquals("Марианна, слушаю", PillHint.listening("Марианна"))
        assertEquals("Саша, секунду…", PillHint.waiting("Саша"))
        assertEquals("Саша, слушаю мысль к делу", PillHint.thought("Саша"))
    }

    @Test
    fun `без имени - просто слово с большой буквы`() {
        assertEquals("Слушаю", PillHint.listening(null))
        assertEquals("Секунду…", PillHint.waiting("  "))
    }

    @Test
    fun `пробелы вокруг имени не попадают в строку`() {
        assertEquals("Серёжа, слушаю", PillHint.listening(" Серёжа "))
    }

    @Test
    fun `эмодзи нет ни в одной фазе`() {
        for (s in listOf(PillHint.listening("Саша"), PillHint.waiting("Саша"), PillHint.thought("Саша"))) {
            assertFalse(s, s.any { Character.isSurrogate(it) })
        }
    }
}
