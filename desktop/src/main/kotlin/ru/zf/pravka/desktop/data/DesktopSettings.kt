package ru.zf.pravka.desktop.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.zf.pravka.core.Models
import ru.zf.pravka.data.PravkaSettings
import ru.zf.pravka.data.SyncSettings

// Настройки воркстанции: settings.json рядом со словарём. Реализует
// PravkaSettings, поэтому движок из :core не отличает эту машину от телефона.
class DesktopSettings(dir: File = Paths.dir) : PravkaSettings, SyncSettings {

    companion object {
        const val DEFAULT_WHISPER_URL = "http://127.0.0.1:8178/v1/audio/transcriptions"
        const val DEFAULT_WHISPER_MODEL = "large-v3-turbo"

        // Клавиши по умолчанию. Формат - как его понимает input/Hotkeys.kt:
        // модификаторы через плюс, последним - клавиша.
        const val DEFAULT_HOTKEY_DICTATE = "ctrl+alt+space"
        const val DEFAULT_HOTKEY_CLEAN = "ctrl+alt+c"
        const val DEFAULT_HOTKEY_MENU = "ctrl+alt+p"
        const val DEFAULT_HOTKEY_UNDO = "ctrl+alt+z"
    }

    private val store = JsonFile(File(dir, "settings.json"))

    // --- то, что нужно движку правки ---

    private val _apiKey = MutableStateFlow(store.string("anthropic_api_key"))
    val apiKeyFlow: StateFlow<String> = _apiKey
    override suspend fun apiKey(): String = _apiKey.value
    fun setApiKey(value: String) {
        _apiKey.value = value.trim()
        store.put("anthropic_api_key", value.trim())
    }

    private val _proseMode = MutableStateFlow(store.boolean("prose_mode", false))
    override val proseModeFlow: StateFlow<Boolean> = _proseMode
    fun setProseMode(value: Boolean) {
        _proseMode.value = value
        store.put("prose_mode", value)
    }

    private val _rulesInProse = MutableStateFlow(store.boolean("rules_in_prose", false))
    override val rulesInProseFlow: StateFlow<Boolean> = _rulesInProse
    fun setRulesInProse(value: Boolean) {
        _rulesInProse.value = value
        store.put("rules_in_prose", value)
    }

    // --- распознавание ---

    private val _whisperUrl = MutableStateFlow(store.string("whisper_url", DEFAULT_WHISPER_URL))
    val whisperUrlFlow: StateFlow<String> = _whisperUrl
    fun setWhisperUrl(value: String) {
        _whisperUrl.value = value.trim()
        store.put("whisper_url", value.trim())
    }

    private val _whisperModel = MutableStateFlow(store.string("whisper_model", DEFAULT_WHISPER_MODEL))
    val whisperModelFlow: StateFlow<String> = _whisperModel
    fun setWhisperModel(value: String) {
        _whisperModel.value = value.trim()
        store.put("whisper_model", value.trim())
    }

    /** Слова словаря уезжают в Whisper подсказкой: фамилии распознаются сразу. */
    private val _dictHint = MutableStateFlow(store.boolean("whisper_dict_hint", true))
    val dictHintFlow: StateFlow<Boolean> = _dictHint
    fun setDictHint(value: Boolean) {
        _dictHint.value = value
        store.put("whisper_dict_hint", value)
    }

    // --- поведение диктовки ---

    /** Причёсывать надиктованное сразу после вставки (тап по «П» на телефоне). */
    private val _autoClean = MutableStateFlow(store.boolean("auto_clean", true))
    val autoCleanFlow: StateFlow<Boolean> = _autoClean
    fun setAutoClean(value: Boolean) {
        _autoClean.value = value
        store.put("auto_clean", value)
    }

    /** Хранить ли WAV после успешного распознавания. */
    private val _keepAudio = MutableStateFlow(store.boolean("keep_audio", true))
    val keepAudioFlow: StateFlow<Boolean> = _keepAudio
    fun setKeepAudio(value: Boolean) {
        _keepAudio.value = value
        store.put("keep_audio", value)
    }

    // --- горячие клавиши ---

    private val _hotkeys = MutableStateFlow(
        Hotkeys(
            dictate = store.string("hotkey_dictate", DEFAULT_HOTKEY_DICTATE),
            clean = store.string("hotkey_clean", DEFAULT_HOTKEY_CLEAN),
            menu = store.string("hotkey_menu", DEFAULT_HOTKEY_MENU),
            undo = store.string("hotkey_undo", DEFAULT_HOTKEY_UNDO),
        )
    )
    val hotkeysFlow: StateFlow<Hotkeys> = _hotkeys
    fun setHotkeys(value: Hotkeys) {
        _hotkeys.value = value
        store.edit {
            it.put("hotkey_dictate", value.dictate)
            it.put("hotkey_clean", value.clean)
            it.put("hotkey_menu", value.menu)
            it.put("hotkey_undo", value.undo)
        }
    }

    data class Hotkeys(
        val dictate: String,
        val clean: String,
        val menu: String,
        val undo: String,
    )

    // --- общий словарь (docs/pravka-sync.md) ---

    private val _syncUrl = MutableStateFlow(store.string("sync_url", ""))
    val syncUrlFlow: StateFlow<String> = _syncUrl
    override suspend fun syncUrl(): String = _syncUrl.value
    fun setSyncUrl(value: String) {
        _syncUrl.value = value.trim()
        store.put("sync_url", value.trim())
    }

    private val _syncAt = MutableStateFlow(store.long("sync_last_at", 0))
    val syncAtFlow: StateFlow<Long> = _syncAt
    override suspend fun lastSyncAt(): Long = _syncAt.value
    override suspend fun setLastSyncAt(value: Long) {
        _syncAt.value = value
        store.put("sync_last_at", value)
    }

    /** Слать ли в таблицу сам текст расшифровок или только метрики. */
    private val _syncText = MutableStateFlow(store.boolean("sync_transcript_text", true))
    val syncTranscriptTextFlow: StateFlow<Boolean> = _syncText
    fun setSyncTranscriptText(value: Boolean) {
        _syncText.value = value
        store.put("sync_transcript_text", value)
    }

    // --- модель правки ---

    val model: String get() = Models.SONNET
    val strongModel: String get() = Models.OPUS
}
