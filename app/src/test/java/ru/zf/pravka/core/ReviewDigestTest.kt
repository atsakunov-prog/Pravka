package ru.zf.pravka.core

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run

// Сводка «Разборов» и красная точка (12.10.2026): упавший разбор не выглядит
// «готово», выученное называется словами, точка горит только на новом.
class ReviewDigestTest {

    private fun at(month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply { clear(); set(2026, month - 1, day, hour, 0) }.timeInMillis

    private val day = 86_400_000L

    private fun weekly(start: Long, error: String = "", changes: List<Change> = emptyList(), summary: String = "") = Run(
        id = start, kind = "weekly", startedAt = start, fromMs = start - 7 * day, toMs = start, stage = "done",
        error = error, changes = changes, summary = summary, finishedAt = start + 1_200_000L, costUsd = 0.99,
    )

    @Test
    fun `упавший первый проход — сбой, а не «готово, применено 0»`() {
        val broken = weekly(at(10, 9, 3), error = "первый проход: ответ обрезан по длине (max_tokens)")
        assertTrue(NightBoard.failed(broken))
        val good = weekly(at(9, 18, 3), changes = listOf(Change("n-1", "dict_add", mode = "HARD", from = "ритейнер", to = "ретейнер", status = "applied")))
        val d = ReviewDigest.build(listOf(broken, good), null, reviewOn = true, tuneOn = true, hour = 3, lastTickMs = at(10, 12, 21), nowMs = at(10, 12, 21))
        assertTrue(d.bad)
        assertTrue(d.status, d.status.contains("не удался"))
        // Выученное — по последнему УДАЧНОМУ разбору, не по упавшему.
        assertEquals(good.id, d.lastReviewId)
        assertTrue(d.learned, d.learned.contains("1 новое слово"))
    }

    @Test
    fun `выученное словами и сводка модели без строки-итога`() {
        val run = weekly(
            at(10, 16, 3),
            changes = listOf(
                Change("n-1", "dict_add", mode = "HARD", from = "а", status = "applied"),
                Change("n-2", "dict_add", mode = "HINT", from = "б", status = "applied"),
                Change("n-3", "dict_disable", from = "в", status = "applied"),
                Change("n-4", "dict_add", from = "г", status = "skipped", statusNote = "не прошло проверку"),
                Change("n-5", "note", why = "модель выбрасывает «не»", status = "note"),
            ),
            summary = "Применено 3, не применено 1, идей 1.\n\nЗа неделю 300 чисток, 80 правок.",
        )
        val learned = ReviewDigest.learnedLine(run)
        assertTrue(learned, learned.startsWith("За неделю"))
        assertTrue(learned, learned.contains("2 новых слова, выключено 1"))
        assertTrue(learned, learned.contains("не применено 1"))
        assertEquals("За неделю 300 чисток, 80 правок.", ReviewDigest.modelSummary(run.summary))
        val d = ReviewDigest.build(listOf(run), null, true, true, 3, at(10, 16, 4), at(10, 16, 4))
        assertFalse(d.bad)
        assertEquals(listOf("модель выбрасывает «не»"), d.ideas)
    }

    @Test
    fun `красная точка — только на новом и значимом`() {
        val useful = weekly(at(10, 16, 3), changes = listOf(Change("n-1", "dict_add", from = "а", status = "applied")))
        val empty = weekly(at(10, 9, 3), changes = listOf(Change("n-1", "dict_add", from = "а", status = "skipped")))
        val broken = weekly(at(10, 2, 3), error = "первый проход: ответ обрезан по длине (max_tokens)")
        val cancelled = broken.copy(id = 7, stage = "failed", error = "отменено владельцем")
        assertTrue(ReviewDigest.newsworthy(useful))
        assertFalse(ReviewDigest.newsworthy(empty))
        assertTrue(ReviewDigest.newsworthy(broken))
        assertFalse(ReviewDigest.newsworthy(cancelled))
        // Видел после полезного — точки нет; свежее полезное — точка есть.
        assertFalse(ReviewDigest.hasNews(listOf(useful, empty), seenAt = useful.finishedAt))
        assertTrue(ReviewDigest.hasNews(listOf(useful, empty), seenAt = useful.finishedAt - 1))
        assertEquals(useful.finishedAt, ReviewDigest.seenMark(listOf(useful, empty, broken)))
    }

    @Test
    fun `исход правки промпта словами`() {
        val tune = Run(
            id = 1, kind = "tune", startedAt = at(10, 10, 3), fromMs = 0, toMs = 0, stage = "done", finishedAt = at(10, 10, 4),
            summary = "Промпт не меняю: предложение «Правило 6…» не прошло измерение.",
        )
        val d = ReviewDigest.build(listOf(tune), null, true, true, 3, at(10, 12, 21), at(10, 12, 21))
        assertTrue(d.prompt, d.prompt.contains("заводской"))
        assertTrue(d.prompt, d.prompt.contains("хуже прежней"))
        assertFalse(ReviewDigest.newsworthy(tune))
        assertTrue(ReviewDigest.newsworthy(tune.copy(summary = "ПРОМПТ ОБНОВЛЁН: правило 6.")))
    }
}
