package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Звук тейков (30.09.2026, владелец: «последние 300 МБ… WAV-файлов»): что
// уходит, когда место кончилось.
class TakeAudioTest {

    private fun item(name: String, mb: Long, at: Long) = TakeAudio.Policy.Item(name, mb * 1024 * 1024, at)

    @Test
    fun `в лимите — ничего не трогаем`() {
        val items = listOf(item("a", 100, 1), item("b", 100, 2), item("c", 99, 3))
        assertEquals(emptyList<String>(), TakeAudio.Policy.drop(items, TakeAudio.MAX_BYTES))
    }

    @Test
    fun `сверх лимита — уходят самые старые, пока не влезет`() {
        val items = listOf(item("new", 150, 30), item("old", 100, 10), item("mid", 100, 20))
        // 350 МБ при лимите 300: уходит только самый старый.
        assertEquals(listOf("old"), TakeAudio.Policy.drop(items, TakeAudio.MAX_BYTES))
    }

    @Test
    fun `пустые — всегда, свежайший — никогда`() {
        val empty = TakeAudio.Policy.Item("empty", TakeAudio.WAV_HEADER, 5)
        val huge = item("huge", 400, 9)
        // Один тейк больше лимита — остаётся: это только что сказанное.
        assertEquals(listOf("empty"), TakeAudio.Policy.drop(listOf(empty, huge), TakeAudio.MAX_BYTES))
    }

    @Test
    fun `300 МБ — это около двух с половиной часов записи`() {
        val minutes = TakeAudio.MAX_BYTES / TakeAudio.BYTES_PER_MINUTE
        assertTrue("$minutes мин", minutes in 150..170)
    }
}
