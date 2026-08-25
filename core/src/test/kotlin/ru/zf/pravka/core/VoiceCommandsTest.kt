package ru.zf.pravka.core

import kotlin.test.Test
import kotlin.test.assertEquals

// Голосовые команды - единственное место, где регулярка идёт по кириллице на
// двух разных движках (ICU на телефоне, JVM на воркстанции). Именно здесь
// когда-то \b молча перестал считать границей начало русского слова.
class VoiceCommandsTest {

    @Test
    fun `new line command becomes a break`() {
        assertEquals(
            "Позвони Цакунову\nи напиши в ЗФ",
            VoiceCommands.apply("Позвони Цакунову с новой строки и напиши в ЗФ"),
        )
    }

    @Test
    fun `comma around the command is swallowed`() {
        assertEquals("Первое\nвторое", VoiceCommands.apply("Первое, новый абзац, второе"))
    }

    @Test
    fun `command at the end leaves no dangling break`() {
        assertEquals("Готово", VoiceCommands.apply("Готово с новой строки"))
    }

    @Test
    fun `similar word inside another is untouched`() {
        val text = "Мы обсудили новый абзацный отступ"
        assertEquals(text, VoiceCommands.apply(text))
    }

    @Test
    fun `text without commands is unchanged`() {
        val text = "Обычная фраза без команд"
        assertEquals(text, VoiceCommands.apply(text))
    }
}
