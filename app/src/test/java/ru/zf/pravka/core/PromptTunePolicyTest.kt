package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Недельная правка промпта: разбор предложения с маркерами, проверка формы
// нового текста, порог принятия и правило отката — без сети.
class PromptTunePolicyTest {

    // Промпт в жизни — двенадцать тысяч знаков; фикстура длинная настолько, чтобы одна фраза не была «третью».
    private val old = "Правила.\n" + "Правило про пунктуацию, падежи и словарь. ".repeat(12) +
        "\n\n<словарь>\n{DICT}\n</словарь>\n\n<текст>\n{INPUT}\n</текст>\n\nПроверка."

    @Test
    fun `предложение без изменений читается из одной JSON-шапки`() {
        val p = PromptTunePolicy.parseProposal("""{"change": false, "why": "идей за неделю мало", "summary": ""}""")
        assertFalse(p.change)
        assertEquals("идей за неделю мало", p.why)
        assertEquals("", p.prompt)
    }

    @Test
    fun `предложение с текстом — между маркерами, целиком`() {
        val raw = """{"change": true, "why": "5 × потеря «не»", "summary": "правило 3 усилено"}
<<<PROMPT>>>
Правила. Новое.

<словарь>
{DICT}
</словарь>

<текст>
{INPUT}
</текст>

Проверка.
<<<END>>>"""
        val p = PromptTunePolicy.parseProposal(raw)
        assertTrue(p.change)
        assertEquals("правило 3 усилено", p.summary)
        assertTrue(p.prompt.startsWith("Правила. Новое."))
        assertTrue(p.prompt.endsWith("Проверка."))
        val short = "Правила.\n\n<словарь>\n{DICT}\n</словарь>\n\n<текст>\n{INPUT}\n</текст>\n\nПроверка."
        assertNull(PromptTunePolicy.validate(short, p.prompt))
    }

    @Test
    fun `форма — метки, теги, длина, ограды`() {
        assertNotNull(PromptTunePolicy.validate(old, old))
        assertNotNull(PromptTunePolicy.validate(old, old.replace("{INPUT}", "")))
        assertNotNull(PromptTunePolicy.validate(old, old.replace("</словарь>", "")))
        assertNotNull(PromptTunePolicy.validate(old, old + "\n```"))
        assertNotNull(PromptTunePolicy.validate(old, old + "\n" + "х".repeat(old.length)))
        assertNotNull(PromptTunePolicy.validate(old, "{DICT} {INPUT} <словарь></словарь><текст></текст>"))
        assertNull(PromptTunePolicy.validate(old, old.replace("Правила.", "Правила. Ещё одно уточнение про «не».")))
    }

    @Test
    fun `принимается только заметный перевес судьи, и не в ущерб правкам владельца`() {
        assertFalse(PromptTunePolicy.decide(3, 1, 10, 0.0, 0.0, 0).adopt)          // мало решающих
        assertFalse(PromptTunePolicy.decide(6, 5, 2, 0.0, 0.0, 0).adopt)           // 6:5 — не перевес
        assertTrue(PromptTunePolicy.decide(10, 4, 3, 0.0, 0.0, 0).adopt)           // 10:4 — перевес
        assertTrue(PromptTunePolicy.decide(10, 4, 3, 0.80, 0.82, 12).adopt)        // и ближе к правкам
        assertFalse(PromptTunePolicy.decide(10, 4, 3, 0.85, 0.80, 12).adopt)       // судья за, правки против
        assertTrue(PromptTunePolicy.decide(10, 4, 3, 0.85, 0.80, 3).adopt)         // правок мало — не считаются
        assertFalse(PromptTunePolicy.decide(4, 6, 0, 0.0, 0.0, 0).adopt)
    }

    @Test
    fun `откат — когда правок руками стало заметно больше и их хотя бы десять`() {
        assertTrue(PromptTunePolicy.shouldRollback(0.20, 0.30, 15))
        assertFalse(PromptTunePolicy.shouldRollback(0.20, 0.30, 7))
        assertFalse(PromptTunePolicy.shouldRollback(0.20, 0.24, 20))
        assertFalse(PromptTunePolicy.shouldRollback(0.0, 0.0, 20))
        assertEquals(0.25, PromptTunePolicy.rate(5, 20), 1e-9)
        assertEquals(0.0, PromptTunePolicy.rate(5, 0), 1e-9)
    }
}
