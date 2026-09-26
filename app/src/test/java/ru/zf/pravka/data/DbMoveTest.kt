package ru.zf.pravka.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Переезд базы (24.09.2026): в папку уезжают лента, дневник еды и журнал
// подходов — незаменимое. Каждая ветка закрепления проверяется здесь, на
// временных папках: докопирование изменившегося, уборка исчезнувшего,
// чужая база в папке, обрыв посреди закрепления и повторный старт.
class DbMoveTest {

    private val tmp: File = Files.createTempDirectory("dbmove").toFile()
    private val priv = File(tmp, "files").apply { mkdirs() }
    private val folder = File(tmp, "Documents/Pravka")

    @After fun cleanup() { tmp.deleteRecursively() }

    private val privateOnly = setOf("models", "recordings", "live_draft.txt", "db-location.json", "db-move-pending.json")
    private fun keep(top: String) = top in privateOnly || top.startsWith("before-move-")
    private fun skip(rel: String) = keep(rel.substringBefore('/'))

    private fun put(dir: File, rel: String, text: String, mtime: Long = 1_700_000_000_000L): File =
        File(dir, rel).apply {
            parentFile.mkdirs()
            writeText(text)
            setLastModified(mtime)
        }

    private fun pending(mode: String, token: String = "t-1", paths: List<String> = emptyList()) =
        DbMove.Pending(mode = mode, folder = folder.absolutePath, token = token, aside = "before-move-x", paths = paths)

    private fun commit(p: DbMove.Pending, moved: MutableList<File> = mutableListOf()): DbMove.Outcome =
        DbMove.commit(priv, p, ::skip, ::keep, device = "Fold", now = 42L, writeMoved = { moved.add(it) })

    @Test
    fun `зеркало копирует дерево без приватного и без tmp и держит время файла`() {
        put(priv, "zasechka.json", "лента")
        put(priv, "food/1.jpg", "снимок")
        put(priv, "datastore/settings.preferences_pb", "ключи")
        put(priv, "models/ggml-small.bin", "модель")
        put(priv, "recordings/rec_1.wav", "звук")
        put(priv, "phone.json.tmp", "недописанное")

        val copied = DbMove.mirror(priv, folder, ::skip)

        assertEquals(listOf("datastore/settings.preferences_pb", "food/1.jpg", "zasechka.json"), copied)
        assertEquals("лента", File(folder, "zasechka.json").readText())
        assertEquals(1_700_000_000_000L, File(folder, "zasechka.json").lastModified())
        assertFalse(File(folder, "models").exists())
        assertFalse(File(folder, "recordings").exists())
        assertFalse(File(folder, "phone.json.tmp").exists())
    }

    @Test
    fun `правка той же длины докопируется — время изменилось`() {
        put(priv, "zasechka.json", "12:30 почта")
        DbMove.mirror(priv, folder, ::skip)
        // «12:30» → «12:35»: длина та же, время другое.
        put(priv, "zasechka.json", "12:35 почта", mtime = 1_700_000_000_500L)

        DbMove.mirror(priv, folder, ::skip)

        assertEquals("12:35 почта", File(folder, "zasechka.json").readText())
    }

    @Test
    fun `неизменившийся файл второй проход не трогает`() {
        put(priv, "food.json", "омлет")
        DbMove.mirror(priv, folder, ::skip)
        // Подменяем копию той же длины и с тем же временем: если второй проход
        // её перепишет, значит он копирует всё подряд, а не только изменения.
        put(folder, "food.json", "ОМЛЕТ")

        DbMove.mirror(priv, folder, ::skip)

        assertEquals("ОМЛЕТ", File(folder, "food.json").readText())
    }

    @Test
    fun `закрепление докопирует, уберёт исчезнувшее, поставит паспорт и отложит прежнее`() {
        put(priv, "zasechka.json", "лента-1")
        put(priv, "backups/zasechka-2026-09-24-09.json", "копия")
        put(priv, "models/ggml.bin", "модель")
        put(priv, "live_draft.txt", "черновик")
        val paths = DbMove.mirror(priv, folder, ::skip)
        // Пока шёл перезапуск: лента изменилась, копию сняла уборка, появился новый стор.
        put(priv, "zasechka.json", "лента-2", mtime = 1_700_000_100_000L)
        File(priv, "backups/zasechka-2026-09-24-09.json").delete()
        put(priv, "money.json", "деньги")
        val moved = mutableListOf<File>()

        val out = commit(pending(DbMove.Pending.COPY, paths = paths), moved)

        assertEquals(DbMove.Outcome.MOVED, out)
        assertEquals("лента-2", File(folder, "zasechka.json").readText())
        assertEquals("деньги", File(folder, "money.json").readText())
        assertFalse(File(folder, "backups/zasechka-2026-09-24-09.json").exists())
        assertEquals("t-1", DbMove.readPassport(folder)?.token)
        assertEquals(listOf(folder), moved)
        // Прежнее не удалено — отложено; приватное осталось на месте.
        assertEquals("лента-2", File(priv, "before-move-x/zasechka.json").readText())
        assertFalse(File(priv, "zasechka.json").exists())
        assertTrue(File(priv, "models/ggml.bin").exists())
        assertTrue(File(priv, "live_draft.txt").exists())
    }

    @Test
    fun `чужая база в папке не затирается — переезд отменён`() {
        put(priv, "zasechka.json", "моя лента")
        put(folder, "zasechka.json", "чужая лента")
        DbMove.writePassport(folder, DbMove.Passport(token = "другой", createdAt = 1L, device = "Pixel"))

        val out = commit(pending(DbMove.Pending.COPY))

        assertEquals(DbMove.Outcome.ABORTED_FOLDER_TAKEN, out)
        assertEquals("чужая лента", File(folder, "zasechka.json").readText())
        assertEquals("моя лента", File(priv, "zasechka.json").readText())
        assertFalse(File(priv, "before-move-x").exists())
    }

    @Test
    fun `обрыв после паспорта — повторный старт доделывает и ничего не стирает в папке`() {
        put(priv, "zasechka.json", "лента")
        put(priv, "food.json", "еда")
        val paths = DbMove.mirror(priv, folder, ::skip)
        DbMove.writePassport(folder, DbMove.Passport(token = "t-1", createdAt = 1L, device = "Fold"))
        // Процесс умер посреди откладывания: лента уже отложена, еда ещё нет.
        File(priv, "before-move-x").mkdirs()
        File(priv, "zasechka.json").renameTo(File(priv, "before-move-x/zasechka.json"))

        val out = commit(pending(DbMove.Pending.COPY, paths = paths))

        assertEquals(DbMove.Outcome.MOVED, out)
        // Лента, которой уже нет на прежнем месте, в папке осталась: шаг
        // «убрать исчезнувшее» при своём паспорте не повторяется.
        assertEquals("лента", File(folder, "zasechka.json").readText())
        assertEquals("еда", File(folder, "food.json").readText())
        assertEquals("еда", File(priv, "before-move-x/food.json").readText())
        assertFalse(File(priv, "food.json").exists())
    }

    @Test
    fun `открыть базу из папки без паспорта нельзя`() {
        put(priv, "zasechka.json", "лента")
        put(folder, "zasechka.json", "просто файл")

        val out = commit(pending(DbMove.Pending.ADOPT, token = ""))

        assertEquals(DbMove.Outcome.ABORTED_NO_DB, out)
        assertEquals("лента", File(priv, "zasechka.json").readText())
    }

    @Test
    fun `открыть базу из папки — папка как есть, прежнее отложено`() {
        put(priv, "zasechka.json", "пустая свежая установка")
        put(folder, "zasechka.json", "лента с другого телефона")
        DbMove.writePassport(folder, DbMove.Passport(token = "t-old", createdAt = 1L, device = "Fold"))
        val moved = mutableListOf<File>()

        val out = commit(pending(DbMove.Pending.ADOPT, token = ""), moved)

        assertEquals(DbMove.Outcome.MOVED, out)
        assertEquals("лента с другого телефона", File(folder, "zasechka.json").readText())
        assertEquals("t-old", DbMove.readPassport(folder)?.token)
        assertEquals("пустая свежая установка", File(priv, "before-move-x/zasechka.json").readText())
        assertEquals(listOf(folder), moved)
    }

    @Test
    fun `откладывание не перетирает уже отложенное`() {
        put(priv, "before-move-x/zasechka.json", "отложено первым заходом")
        put(priv, "zasechka.json", "другое")

        DbMove.setAside(priv, "before-move-x", ::keep)

        assertEquals("отложено первым заходом", File(priv, "before-move-x/zasechka.json").readText())
        assertTrue(File(priv, "zasechka.json").exists())
    }

    @Test
    fun `подготовленный переезд читается тем же, что записан`() {
        val file = File(priv, "db-move-pending.json")
        DbMove.writePending(file, pending(DbMove.Pending.COPY, paths = listOf("a.json", "food/1.jpg")))

        val back = DbMove.readPending(file)

        assertNotNull(back)
        assertEquals(DbMove.Pending.COPY, back!!.mode)
        assertEquals(listOf("a.json", "food/1.jpg"), back.paths)
        assertEquals(folder.absolutePath, back.folder)
        assertNull(DbMove.readPending(File(priv, "нет-такого.json")))
    }
}
