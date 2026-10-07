package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Test

// «Настройки → Баги»: история починок сборками (07.10.2026).
class FeedbackHistoryTest {

    private fun item(num: Int, status: String, build: Int, at: Long = 0L) =
        FeedbackStore.Item(num = num, ts = 0L, origin = "П", raw = "№$num", status = status, note = "сделано $num", doneAt = at, build = build)

    @Test
    fun `сборки свежие сверху, внутри по номерам, руками - в конце, новые не входят`() {
        val h = FeedbackStore.history(
            listOf(
                item(3, FeedbackStore.DONE, 789, at = 5),
                item(1, FeedbackStore.DONE, 789, at = 7),
                item(2, FeedbackStore.SKIP, 0, at = 9),
                item(4, FeedbackStore.DONE, 790, at = 11),
                item(5, FeedbackStore.NEW, 0),
            )
        )
        assertEquals(listOf(790, 789, 0), h.map { it.build })
        assertEquals(listOf(1, 3), h[1].items.map { it.num })
        assertEquals(7L, h[1].at)
    }
}
