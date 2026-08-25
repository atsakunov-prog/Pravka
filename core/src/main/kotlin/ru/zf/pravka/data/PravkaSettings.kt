package ru.zf.pravka.data

import kotlinx.coroutines.flow.Flow

// То немногое из настроек, что нужно самому движку правки. На телефоне это
// реализует DataStore-овый Settings, на воркстанции - файл settings.json:
// ядру всё равно, а разъехаться поведение уже не может.
interface PravkaSettings {

    suspend fun apiKey(): String

    /** Художественный режим: CLEAN получает директиву PROSE. */
    val proseModeFlow: Flow<Boolean>

    /** Класть ли выученные правила в запрос, когда включён художественный режим. */
    val rulesInProseFlow: Flow<Boolean>
}
