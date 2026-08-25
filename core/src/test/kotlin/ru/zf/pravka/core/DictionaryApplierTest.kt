package ru.zf.pravka.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ru.zf.pravka.data.DictionaryStore

class DictionaryApplierTest {

    private fun store(): DictionaryStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "pravka-test-" + System.nanoTime())
        dir.mkdirs()
        dir.deleteOnExit()
        return DictionaryStore(dir) { null }
    }

    @Test
    fun `hard replacement keeps the leading case`() = runBlocking {
        val store = store()
        store.add("цакунов", "Цакунов", DictMode.HARD, "")
        val prepared = DictionaryApplier(store).prepare("Цакунов и цакунов")
        assertEquals("Цакунов и Цакунов", prepared.text)
    }

    @Test
    fun `hard replacement stays out of other words`() = runBlocking {
        val store = store()
        store.add("осу", "ОСУ", DictMode.HARD, "")
        val prepared = DictionaryApplier(store).prepare("Он осудил осу")
        assertEquals("Он осудил ОСУ", prepared.text)
    }

    @Test
    fun `hint matches a case ending and lands in the block`() = runBlocking {
        val store = store()
        store.add("сейф", "", DictMode.HINT, "хранилище")
        val prepared = DictionaryApplier(store).prepare("Положили в сейфам, как договорились")
        assertTrue(prepared.dictBlock.contains("сейф"), prepared.dictBlock)
        assertTrue(prepared.dictBlock.contains("хранилище"), prepared.dictBlock)
        assertTrue(prepared.firedIds.isNotEmpty())
    }

    @Test
    fun `words absent from the text never reach the prompt`() = runBlocking {
        val store = store()
        store.add("сейф", "", DictMode.HINT, "хранилище")
        val prepared = DictionaryApplier(store).prepare("Совсем про другое")
        assertTrue(prepared.dictBlock.isEmpty())
        assertTrue(prepared.firedIds.isEmpty())
    }

    @Test
    fun `a disabled entry does not fire`() = runBlocking {
        val store = store()
        val entry = store.add("цакунов", "Цакунов", DictMode.HARD, "")
        store.update(entry.copy(enabled = false))
        val prepared = DictionaryApplier(store).prepare("тут цакунов")
        assertEquals("тут цакунов", prepared.text)
        assertFalse(prepared.firedIds.contains(entry.id))
    }
}
