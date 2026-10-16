package ru.zf.pravka.core

import org.json.JSONObject

/**
 * «+» и микрофон в шапке Дел (отладка 16.10.2026 вечер, запись 32). Владелец: «новое дело должно быть где-то высоко —
 * рядом с „Дела“ наверху плюс и микрофон. Плюс — накидать дела: открывается текстбокс посередине экрана (их может быть
 * несколько, там должны распознаться люди, проекты и т. п.), микрофон — то же самое, но голосом».
 *
 * Дорога — та же дверь захвата, что у строки «сказать» и кнопки «Д» (правило 29 сервера, `capture.say`): сервер сам
 * раскладывает несколько дел, узнаёт людей и проекты и отдаёт строку «куда легло» с кнопками поправки. Здесь — что не
 * знает ни про сеть, ни про Android: какой дорогой идти и какой операцией (под тестом `DelaAddTest`).
 */
object DelaAdd {

    /** Подсказка в пустом поле листа. */
    const val HINT = "Добавь дела — разложу сам"

    /** Захват уходит очередью: правило, а не узнало — модель на сервере (как «Д»). */
    const val LANE = Capture.LANE_AUTO

    /**
     * Дорога: [SERVER] — сервер знает захват и модель (`capture`, `capture_model`) — одна операция `capture.say`;
     * [RULE] — захват без модели: только правило сервера, не узнало — прежняя Разноска; [OLD] — сервер старый или Дела
     * не на своём сервере — прежняя Разноска телефона (как «Д» до захвата).
     */
    enum class Way { SERVER, RULE, OLD }

    fun way(ready: Boolean, modelOn: Boolean): Way = when {
        !ready -> Way.OLD
        modelOn -> Way.SERVER
        else -> Way.RULE
    }

    /** Откуда фраза для сервера: набрано в листе — как строка «сказать», наговорено — как «Д». */
    fun from(spoken: Boolean): String = if (spoken) Capture.FROM_D else Capture.FROM_SAY

    /** Операция листа — ровно та, что уходит очередью (`CaptureClient.say` с [LANE] и [from]). */
    fun op(text: String, ref: String, spoken: Boolean, saidAt: String, hint: String = "", opId: String = Dela.newId()): JSONObject =
        Capture.sayOp(text, ref, LANE, from(spoken), saidAt, hint, opId)

    /** Набранное и наговорённое — одной фразой: голос дописывает к тому, что уже в поле. */
    fun joined(typed: String, said: String): String = listOf(typed.trim(), said.trim()).filter { it.isNotBlank() }.joinToString(" ")
}
