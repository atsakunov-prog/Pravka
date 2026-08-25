package ru.zf.pravka.desktop.data

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.PromptSyncMeta

// Правки промптов на воркстанции: prompts.json. Заводские тексты приезжают из
// ядра, здесь лежат только переопределения - как и на телефоне, поэтому
// обновление программы не затирает отредактированный промпт.
class DesktopPromptStore(dir: File = Paths.dir) : PromptStore {

    private val store = JsonFile(File(dir, "prompts.json"))

    /** Время правки - для синхронизации с телефоном. */
    val syncMeta = PromptSyncMeta(dir)
    private val overrides = MutableStateFlow(
        store.keys().associateWith { store.string(it) }
    )

    override fun overrideFlow(id: PromptStore.PromptId): Flow<String?> =
        overrides.map { it[id.storageKey]?.takeIf { text -> text.isNotBlank() } }

    override suspend fun setOverride(id: PromptStore.PromptId, text: String) {
        store.put(id.storageKey, text)
        syncMeta.touch(id.storageKey)
        overrides.value = overrides.value + (id.storageKey to text)
    }

    override suspend fun resetToFactory(id: PromptStore.PromptId) {
        store.put(id.storageKey, null)
        syncMeta.touch(id.storageKey)
        overrides.value = overrides.value - id.storageKey
    }
}
