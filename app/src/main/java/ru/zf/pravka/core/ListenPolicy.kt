package ru.zf.pravka.core

/**
 * Когда тейк кончается сам, без кнопки, — решение без Android под рукой.
 *
 * Правило (владелец, 23.09.2026): «Правка прежде всего должна слушать то, что
 * я говорю. Если я замолк, то не надо убивать сессию». Тишина — не конец
 * тейка: человек читает, думает, отвечает кому-то в комнате. Раньше
 * непрерывная сессия распознавателя закрывалась после 30 секунд тишины
 * (`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` у segmented-режима —
 * это и есть её конец), а мы на `onEndOfSegmentedSession` честно отдавали
 * текст в чистку. В режиме перезапусков то же делала серия NO_MATCH /
 * SPEECH_TIMEOUT: сорок пауз подряд — и «микрофон мёртв».
 *
 * Теперь тейк кончает владелец. Единственная страховка — от случайного
 * нажатия: если [IDLE_CAP_MS] не пришло ни одного нового слова, запись
 * закрывается сама («чтобы не слушала там час, а минут через десять
 * замолкала»). Считается от последнего СЛОВА, а не от старта: длинная
 * диктовка с паузами не обрывается посреди мысли.
 */
object ListenPolicy {

    /** Десять минут без единого нового слова — нажали случайно. */
    const val IDLE_CAP_MS = 10L * 60 * 1000

    /** Как часто сторож тишины смотрит на часы: точность тут не нужна. */
    const val IDLE_CHECK_MS = 30_000L

    // Коды SpeechRecognizer: ERROR_SPEECH_TIMEOUT = 6, ERROR_NO_MATCH = 7.
    // Числа, а не константы Android, — чтобы решение жило под JVM-тестом.
    private const val SPEECH_TIMEOUT = 6
    private const val NO_MATCH = 7

    /**
     * Ошибка — это просто тишина (никто не говорил / сказанное не разобрали).
     * Такая ошибка не приближает «сдаться»: она не про сломанный микрофон.
     */
    fun isSilence(code: Int): Boolean = code == SPEECH_TIMEOUT || code == NO_MATCH

    /** Слов не было так долго, что запись пора закрыть самим. */
    fun idleExpired(lastWordsAtMs: Long, nowMs: Long): Boolean =
        nowMs - lastWordsAtMs >= IDLE_CAP_MS

    /**
     * Распознаватель закрыл сессию сам (тишина). Если владелец запись не
     * останавливал и страховка не сработала — слушаем дальше, а не отдаём
     * текст.
     */
    fun resumeAfterSessionEnd(active: Boolean, stopping: Boolean, lastWordsAtMs: Long, nowMs: Long): Boolean =
        active && !stopping && !idleExpired(lastWordsAtMs, nowMs)
}
