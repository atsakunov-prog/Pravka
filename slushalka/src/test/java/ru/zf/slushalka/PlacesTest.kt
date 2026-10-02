package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.BookState
import ru.zf.slushalka.data.PositionSync
import ru.zf.slushalka.data.mergeStates

/**
 * Места в книге: два места у каждой книги, у каждого своё время, слияние по
 * каждому отдельно. Здесь ломается то, что владельца бесит в любой читалке, -
 * сбитая точка, - поэтому проверяется на машине.
 */
class PlacesTest {

    private val id = "Books/Акунин - История"

    @Test
    fun readingElsewhereDoesNotEraseListeningHere() {
        // Здесь слушал до 2:00:00 в 10:00; на Boox читал в 11:00 - место в записи
        // там осталось старым (1:00:00, слушал в 9:00).
        val here = BookState(id, absMs = 7_200_000, listenAt = 10, readChar = 1000, readAt = 5, updatedAt = 10)
        val boox = BookState(id, absMs = 3_600_000, listenAt = 9, readChar = 90_000, readAt = 11, updatedAt = 11)
        val (merged, m) = mergeStates(here, boox)
        assertEquals(7_200_000L, merged.absMs)
        assertEquals(90_000, merged.readChar)
        assertFalse(m.listen)
        assertTrue(m.read)
        // Читал позже, чем слушал: читалка - на прочитанном, плеер подтянется к нему.
        assertFalse(merged.listenedLast)
    }

    @Test
    fun listeningLaterWinsForReader() {
        val here = BookState(id, absMs = 1_000_000, listenAt = 5, readChar = 50_000, readAt = 6, updatedAt = 6)
        val phone = BookState(id, absMs = 4_000_000, listenAt = 20, readChar = 50_000, readAt = 6, updatedAt = 20)
        val (merged, m) = mergeStates(here, phone)
        assertTrue(m.listen)
        assertEquals(4_000_000L, merged.absMs)
        // Дослушал после чтения - читалка откроется после прослушанного.
        assertTrue(merged.listenedLast)
    }

    @Test
    fun olderRemoteChangesNothing() {
        val here = BookState(id, absMs = 1_000, listenAt = 50, readChar = 10, readAt = 50, updatedAt = 50)
        val old = BookState(id, absMs = 999_000, listenAt = 40, readChar = 99_999, readAt = 40, updatedAt = 40)
        val (merged, m) = mergeStates(here, old)
        assertFalse(m.any)
        assertEquals(here, merged)
    }

    @Test
    fun placesFileRoundTrip() {
        val s = BookState(
            id, fileIndex = 3, posMs = 1234, absMs = 99_000, listenAt = 111,
            readChar = 4567, textChars = 900_000, readAt = 222, updatedAt = 222, finished = false,
        )
        val untouched = BookState("Books/Нетронутая")
        val r = PositionSync.parsePlaces(PositionSync.placesJson("Саша", "Boox", mapOf(id to s, untouched.bookId to untouched)))!!
        assertEquals("Саша", r.profile)
        assertEquals("Boox", r.device)
        // Нетронутая книга не пишется вовсе.
        assertEquals(setOf(id), r.states.keys)
        val got = r.states.getValue(id)
        assertEquals(99_000L, got.absMs)
        assertEquals(3, got.fileIndex)
        assertEquals(111L, got.listenAt)
        assertEquals(4567, got.readChar)
        assertEquals(900_000, got.textChars)
        assertEquals(222L, got.readAt)
    }

    @Test
    fun legacyFileFromNewCopyIsSkippedOldOneUnderstood() {
        val s = BookState(id, absMs = 5_000, readChar = 10, updatedAt = 77, listenAt = 70, readAt = 77)
        // Прежний файл, записанный новой копией, - мимо: правда в файлах мест.
        assertNull(PositionSync.parseRemote(PositionSync.positionsJson("Саша", mapOf(id to s))))
        // Файл старой копии: одно время на оба места.
        val old = """{"profile":"Марианна","at":1,"books":{"$id":{"file":0,"pos":5000,"abs":5000,"at":77,"read":10}}}"""
        val r = PositionSync.parseRemote(old)!!
        assertEquals(77L, r.states.getValue(id).listenAt)
        assertEquals(77L, r.states.getValue(id).readAt)
    }

    @Test
    fun oldPositionsKeepTheirTime() {
        val o = org.json.JSONObject("""{"abs": 5000, "at": 42, "read": 100}""")
        val s = BookState.fromJson(id, o)
        assertEquals(42L, s.listenAt)
        assertEquals(42L, s.readAt)
    }
}
