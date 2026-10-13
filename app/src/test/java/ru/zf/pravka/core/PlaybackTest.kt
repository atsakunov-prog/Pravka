package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test

// «Слушать» в экране «Правка» (доводка 14.10.2026): подпись времени и перемотка плеера тейков.
class PlaybackTest {

    @Test
    fun `время — минуты и секунды двумя знаками`() {
        assertEquals("0:00", Playback.clock(0))
        assertEquals("0:07", Playback.clock(7_000))
        assertEquals("0:07", Playback.clock(6_600)) // до ближайшей секунды, как у плееров
        assertEquals("1:05", Playback.clock(65_000))
        assertEquals("12:40", Playback.clock(760_000))
        assertEquals("0:00", Playback.clock(-500))
    }

    @Test
    fun `подпись — место из длины, длины нет — только место`() {
        assertEquals("0:07 / 0:42", Playback.label(7_000, 42_000))
        assertEquals("0:42 / 0:42", Playback.label(50_000, 42_000))
        assertEquals("0:03", Playback.label(3_000, 0))
    }

    @Test
    fun `доля полосы и перемотка`() {
        assertEquals(0.5f, Playback.fraction(21_000, 42_000), 0.0001f)
        assertEquals(1f, Playback.fraction(99_000, 42_000), 0.0001f)
        assertEquals(0f, Playback.fraction(5_000, 0), 0.0001f)
        assertEquals(21_000L, Playback.seekMs(0.5f, 42_000))
        assertEquals(0L, Playback.seekMs(-0.2f, 42_000))
        assertEquals(42_000L, Playback.seekMs(1.4f, 42_000))
        assertEquals(0L, Playback.seekMs(0.5f, 0))
    }

    @Test
    fun `дослушанное начинается с начала`() {
        assertEquals(0L, Playback.clampMs(42_000, 42_000))
        assertEquals(0L, Playback.clampMs(-1, 42_000))
        assertEquals(10_000L, Playback.clampMs(10_000, 42_000))
        assertEquals(10_000L, Playback.clampMs(10_000, 0))
    }
}
