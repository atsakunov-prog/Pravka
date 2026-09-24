package ru.zf.pravka.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// База — одна папка (DataRoot, 24.09.2026): стор, открывший свой файл в
// `filesDir` мимо DataRoot, после переезда базы молча писал бы в приватную
// память, и в скопированной папке его данных бы не оказалось. Тест держит
// это правило для каждого нового стора: `filesDir` и заводской
// `preferencesDataStore` — только там, где это осознанно не база.
class DataRootGuardTest {

    /** Кому можно в приватную память и почему. */
    private val allowed = mapOf(
        "data/DataRoot.kt" to "сам решает, где база",
        "data/LiveDraft.kt" to "черновик тейка на лету — не база",
        "data/Recordings.kt" to "WAV на повтор пишется в реальном времени",
        "provider/WhisperProvider.kt" to "модели Whisper скачиваются заново",
        "ReviewsTab.kt" to "файл-выгрузка для «поделиться», не данные",
    )

    private val src = File("src/main/java/ru/zf/pravka")

    private fun code(f: File): List<Pair<Int, String>> =
        f.readLines().mapIndexed { i, line -> (i + 1) to line.substringBefore("//") }
            .filter { (_, line) -> line.trimStart().let { !it.startsWith("*") && !it.startsWith("/*") } }

    @Test
    fun `сторы открывают файлы в папке базы, а не в filesDir`() {
        assertTrue("нет исходников в ${src.absolutePath}", src.isDirectory)
        val offenders = src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(src).invariantSeparatorsPath !in allowed }
            .flatMap { f -> code(f).filter { (_, line) -> "filesDir" in line }.map { (n, _) -> "${f.relativeTo(src)}:$n" } }
            .toList()
        assertEquals("filesDir мимо DataRoot.dir: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `DataStore — только через DataRoot_preferences`() {
        val offenders = src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DataRoot.kt" }
            .flatMap { f -> code(f).filter { (_, line) -> "preferencesDataStore(" in line }.map { (n, _) -> "${f.relativeTo(src)}:$n" } }
            .toList()
        assertEquals("заводской preferencesDataStore пишет в filesDir: $offenders", emptyList<String>(), offenders)
    }
}
