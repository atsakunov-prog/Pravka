package ru.zf.pravka.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Сырьё выписок в imports/ базы (владелец, 25.09.2026: «складывай в imports»):
// файл — как есть, повторная загрузка копию не плодит.
class ImportArchiveTest {

    private val tmp: File = Files.createTempDirectory("imports").toFile()
    @After fun cleanup() { tmp.deleteRecursively() }

    private val archive = ImportArchive { tmp }

    @Test fun `выписка ложится как есть`() {
        val csv = "Дата операции;Сумма в валюте счёта\n24.09.2026;195000\n".toByteArray()
        val f = archive.keep(csv, now = 1_790_000_000_000L)
        assertTrue(f.name, f.name.endsWith(".csv"))
        assertEquals(File(tmp, ImportArchive.DIR), f.parentFile)
        assertTrue(csv.contentEquals(f.readBytes()))
    }

    @Test fun `та же выписка второй раз — тот же файл`() {
        val csv = "одна и та же выписка".toByteArray()
        val a = archive.keep(csv, now = 1L)
        val b = archive.keep(csv, now = 2_000_000L)
        assertEquals(a, b)
        assertEquals(1, archive.files().size)
        archive.keep("другая".toByteArray(), now = 3L)
        assertEquals(2, archive.files().size)
    }

    @Test fun `xlsx МКБ узнаётся по zip`() {
        val xlsx = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0)
        assertTrue(archive.keep(xlsx).name.endsWith(".xlsx"))
    }
}
