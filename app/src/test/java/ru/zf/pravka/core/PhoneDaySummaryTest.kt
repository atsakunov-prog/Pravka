package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.PhoneStore

// Телефон по дням — то, что пришло на смену параллельному треку. Владелец:
// «просто давай считать каждый день, сколько на Клод, телеграм, звонки,
// сколько на ютуб». Сводка должна складывать оба пакета Телеграма в одну
// строку, узнавать YouTube и по пакету, и по имени, и звать звонки звонками.
class PhoneDaySummaryTest {

    private val m = 60_000L
    private val labels = mapOf(
        "com.google.android.youtube" to "YouTube",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "com.anthropic.claude" to "Claude",
        "ru.zf.slushalka" to "Слушалка",
    )
    private val tracked = mapOf(
        "com.google.android.youtube" to "Потери",
        "org.telegram.messenger" to "Социальное: внешнее",
        "org.telegram.messenger.web" to "Социальное: внешнее",
        "com.anthropic.claude" to "Систематизация",
    )
    private val day = PhoneStore.Day(
        screenMs = 192 * m,
        pickups = 41,
        glances = 12,
        apps = mapOf(
            "com.google.android.youtube" to 47 * m,
            "org.telegram.messenger" to 20 * m,
            "org.telegram.messenger.web" to 12 * m,
            "com.anthropic.claude" to 70 * m,
            "com.android.chrome" to 30 * m,
        ),
        callsMs = 18 * m,
        calls = 3,
        callers = mapOf("Мама" to 12 * m, "Петя" to 6 * m),
    )

    @Test
    fun `два пакета Телеграма складываются в одну строку`() {
        val s = PhoneDaySummary.of(day, tracked, labels)
        val tg = s.apps.first { it.label == "Telegram" }
        assertEquals(32L, tg.minutes)
        assertEquals(3, s.apps.size)
    }

    @Test
    fun `приложения идут по убыванию минут, неотмеченные не считаются`() {
        val s = PhoneDaySummary.of(day, tracked, labels)
        assertEquals(listOf("Claude", "YouTube", "Telegram"), s.apps.map { it.label })
        assertTrue(s.apps.none { it.pkg.contains("chrome") })
    }

    @Test
    fun `звонки считаются минутами, числом и собеседниками`() {
        val s = PhoneDaySummary.of(day, tracked, labels)
        assertEquals(18L, s.callsMin)
        assertEquals(3, s.calls)
        assertEquals(listOf("Мама", "Петя"), s.callers)
    }

    @Test
    fun `строка для вкладки читается как одна фраза`() {
        val line = PhoneDaySummary.line(PhoneDaySummary.of(day, tracked, labels))
        assertEquals(
            "Claude 1 ч 10 м · YouTube 47 м · Telegram 32 м · звонки 3 · 18 м (Мама, Петя) · экран 3 ч 12 м",
            line,
        )
    }

    @Test
    fun `ютуб узнаётся и по пакету, и по имени по-русски`() {
        assertEquals(47L, PhoneDaySummary.minutesOf(day, labels, "youtube"))
        assertTrue(PhoneDaySummary.isApp("ru.some.app", "Ютуб Кидс", "youtube"))
        assertEquals(32L, PhoneDaySummary.minutesOf(day, labels, "telegram"))
        assertEquals(70L, PhoneDaySummary.minutesOf(day, labels, "claude"))
    }

    @Test
    fun `пустой день - пустая сводка и пустая строка`() {
        val s = PhoneDaySummary.of(null, tracked, labels)
        assertTrue(s.empty)
        assertEquals("", PhoneDaySummary.line(s))
    }

    @Test
    fun `для Notion - три приложения, экран, звонки и собеседники строкой`() {
        val n = PhoneDaySummary.forNotion(day, labels, tracked)
        assertEquals(47L, n.youtubeMin)
        assertEquals(32L, n.telegramMin)
        assertEquals(70L, n.claudeMin)
        assertEquals(192L, n.screenMin)
        assertEquals(3, n.calls)
        assertEquals("Мама, Петя", n.callers)
        // Приложения — только отмеченные, по убыванию: Chrome не отмечен и не попадает.
        assertEquals("Claude 1 ч 10 м · YouTube 47 м · Telegram 32 м", n.apps)
        assertTrue(n.any)
    }
}
