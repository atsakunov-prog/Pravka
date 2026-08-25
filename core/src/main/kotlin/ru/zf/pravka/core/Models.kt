package ru.zf.pravka.core

// Идентификаторы моделей и движков распознавания. Лежат в ядре, потому что
// на них смотрят и телефон, и воркстанция, и таблица цен (Pricing).
object Models {

    const val SONNET = "claude-sonnet-5"
    const val OPUS = "claude-opus-5"   // redo chips only

    // Dictation engines.
    const val SPEECH_GOOGLE = "google"          // live streaming, Gboard's engine
    const val SPEECH_WHISPER_SMALL = "whisper-small"
    const val SPEECH_WHISPER_BASE = "whisper-base"

    // Воркстанция: локальный сервер Whisper (scripts/whisper).
    const val SPEECH_WHISPER_SERVER = "whisper-server"
}
