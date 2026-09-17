package ru.zf.pravka.core

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run

// Табло «что работает»: состояние каждого автомата из прогонов и настроек, без сети.
class NightBoardTest {

    private val hour = 3
    private val now = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 17, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun run(kind: String, stage: String, startedAt: Long, error: String = "", progress: String = "", changes: List<Change> = emptyList(), summary: String = "") =
        Run(id = startedAt, kind = kind, startedAt = startedAt, fromMs = startedAt - 86_400_000L, toMs = startedAt, stage = stage, error = error,
            progress = progress, lastPollAt = startedAt, changes = changes, summary = summary, costUsd = 0.5)

    @Test
    fun `пустое табло — служба не тикала, автоматов не было, эвала не было`() {
        val lines = NightBoard.build(emptyList(), true, true, hour, null, 0L, now)
        assertEquals(listOf("tick", "daily", "weekly", "tune", "eval"), lines.map { it.key })
        assertEquals("stale", lines[0].state)
        assertEquals("none", lines[1].state)
        assertEquals("завтра в 03:00", lines[1].next)
        assertTrue(lines[2].next, lines[2].next.startsWith("в ночь на пятницу 18.09"))
        assertTrue(lines[3].next, lines[3].next.startsWith("в ночь на субботу 19.09"))
        assertEquals("none", lines[4].state)
    }

    @Test
    fun `готово, сбой и застряло — три разных состояния`() {
        val done = run(NightReviewPolicy.DAILY, "done", now - 9 * 3600_000L,
            changes = listOf(Change(id = "a", kind = "dict_add", mode = "HARD", from = "x", to = "y", status = "applied"), Change(id = "b", kind = "note", why = "w", status = "note")))
        val failed = run(NightReviewPolicy.WEEKLY, "failed", now - 5 * 86_400_000L, error = "батч не завершился за сутки")
        val stuck = run(PromptTunePolicy.KIND, PromptTunePolicy.STAGE_PROPOSE, now - 7 * 3600_000L, progress = "батч in_progress").copy(lastPollAt = now - 3 * 3600_000L)
        val lines = NightBoard.build(listOf(done, failed, stuck), true, true, hour, NightBoard.EvalSummary(now - 86_400_000L, 0.912, 12, 30), now - 4 * 60_000L, now)
        val by = lines.associateBy { it.key }
        assertEquals("ok", by["tick"]!!.state)
        assertEquals("ok", by["daily"]!!.state)
        assertTrue(by["daily"]!!.text, by["daily"]!!.text.contains("применено 1, идей 1"))
        assertEquals("fail", by["weekly"]!!.state)
        assertTrue(by["weekly"]!!.text.contains("батч не завершился за сутки"))
        assertEquals("stale", by["tune"]!!.state)
        assertTrue(by["tune"]!!.text.contains("Проверить сейчас"))
        assertEquals("ok", by["eval"]!!.state)
        assertTrue(by["eval"]!!.text.contains("91.2%"))
    }

    @Test
    fun `служба без тика двадцать минут — сбой, выключенный тумблер — off`() {
        val lines = NightBoard.build(emptyList(), false, true, hour, null, now - 25 * 60_000L, now)
        assertEquals("fail", lines[0].state)
        assertEquals("off", lines[1].state)
        assertEquals("выключен тумблером", lines[1].next)
    }

    @Test
    fun `следующий дневной — сегодня до часа запуска, завтра после`() {
        val early = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 1) }.timeInMillis
        assertEquals("сегодня в 03:00", NightBoard.nextDaily(early, hour))
        assertEquals("завтра в 03:00", NightBoard.nextDaily(now, hour))
    }

    @Test
    fun `строки сбоя в журнале узнаются`() {
        assertTrue(NightBoard.isFailureLine("тень НЕ УДАЛАСЬ: сеть"))
        assertTrue(NightBoard.isFailureLine("ночной разбор: тик упал: x"))
        assertTrue(!NightBoard.isFailureLine("тень: почищено 25 за тик, осталось 17"))
        val text = NightBoard.render(NightBoard.build(emptyList(), true, true, hour, null, now, now))
        assertTrue(text, text.contains("- ✓ Служба:") && text.contains("- · Разбор суток: ещё не было · следующий завтра в 03:00"))
    }
}
