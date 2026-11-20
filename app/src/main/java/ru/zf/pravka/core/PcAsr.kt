package ru.zf.pravka.core

/**
 * Итог тейка «П» — с домашнего компа (20.11.2026). Владелец: «давай сделаем выбор в настройках.
 * Сейчас временно сделаем с компа, но если будет сильно застревать, то тогда перещёлкивает на
 * гугл».
 *
 * Как устроено: Google, как и раньше, ведёт живую строку в пилюле; своя запись (`MicFeed`) тот же
 * звук кусками шлёт архиву, архив — службе «Слух» на компе (GigaAM-v3 на процессоре, тот же, что у
 * «Диктовки» компа). Комп считает итог областями по ходу записи, и на «стоп» ему остаётся
 * последний кусок — доли секунды. После итога Google телефон ждёт ответа компа не дольше
 * [WAIT_MS]; не дождался — вставляет Google, и это «застрял». Два застревания подряд —
 * настройка сама переключается на Google ([Breaker]). Комп недоступен (нет связи с домом, служба
 * лежит, звук ушёл не весь) — Google сразу, без ожидания и без счёта: это не застревание, а «нет».
 *
 * Здесь — только решения, без Android: что вставить, что считать застреванием. Под тестом
 * (`PcAsrTest`).
 */
object PcAsr {

    /** Выбор в настройках: итог тейка — с компа или от Google. */
    const val FINAL_PC = "pc"
    const val FINAL_GOOGLE = "google"

    /** Метка тейка в «Расшифровках» и архиве, когда вставлен текст компа. */
    const val ENGINE = "gigaam-pc"

    /** Звук: 16 кГц, 16 бит, моно — 32 000 байт в секунду. */
    const val BYTES_PER_SEC = 32_000

    /** Кусок отправки — секунда звука: 32 КБ, запрос на секунду речи. */
    const val CHUNK_BYTES = BYTES_PER_SEC

    /**
     * Сколько ждать ответа компа после итога Google. Комп к этому мигу посчитал всё, кроме
     * последней области (замер 20.11: 0,2–0,5 с на неё), — 2,5 с хватает с запасом на сеть, а
     * дольше владелец ждать не должен: это уже «застревает».
     */
    const val WAIT_MS = 2_500L

    /**
     * Отправка отстала больше чем на столько звука — связь не тянет (2 МБ в минуту), ждать
     * бесполезно: тейк сразу Google, остальное не шлём.
     */
    const val BEHIND_MAX_BYTES = 10 * BYTES_PER_SEC

    /** Сколько застреваний подряд переключают настройку на Google. */
    const val STUCK_IN_A_ROW = 2

    /** Чем кончилась попытка компа для этого тейка. */
    enum class Outcome {
        /** Текст пришёл вовремя. */
        OK,

        /** Ждали [WAIT_MS] и не дождались — застрял. */
        STUCK,

        /** Компа нет: нет адреса архива, связь, служба, отказ — Google сразу, не застревание. */
        UNAVAILABLE,

        /** Звук ушёл не весь (своя запись закрылась раньше, отстала отправка) — Google, не застревание. */
        INCOMPLETE,

        /** Тейк отменён. */
        CANCELLED,
    }

    /** Ответ компа на тейк: текст (если пришёл), исход и почему — словами для журнала. */
    data class Result(val outcome: Outcome, val text: String = "", val note: String = "", val serverMs: Long = -1)

    /** Что вставлять: [text] от [engine]; [googleAlt] — текст Google, если вставлен текст компа. */
    data class Pick(val engine: String, val text: String, val googleAlt: String?, val note: String)

    /**
     * Текст компа или Google. Комп ответил вовремя — его текст, кроме двух случаев, где он
     * явно потерял звук, а Google нет: пустой ответ при словах у Google и втрое меньше слов при
     * Google от восьми слов (своя запись и распознаватель слышат один звук — такая разница
     * значит, что до компа дошло не всё).
     */
    fun choose(googleEngine: String, google: String, pc: Result): Pick {
        if (pc.outcome != Outcome.OK) return Pick(googleEngine, google, null, pc.note.ifBlank { pc.outcome.name.lowercase() })
        val pcWords = words(pc.text)
        val gWords = words(google)
        if (pcWords == 0 && gWords > 0) return Pick(googleEngine, google, null, "комп вернул пусто, у Google $gWords слов")
        if (gWords >= 8 && pcWords * 3 < gWords) {
            return Pick(googleEngine, google, null, "у компа $pcWords слов против $gWords у Google — звук дошёл не весь")
        }
        return Pick(ENGINE, pc.text.trim(), google, pc.note)
    }

    /** Слова без разбора языка: куски между пробелами. */
    fun words(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /**
     * Сторож застреваний: [STUCK_IN_A_ROW] подряд — пора переключать на Google. Успех сбрасывает
     * счёт; «компа нет» и неполный звук счёт не трогают — владелец в них не ждал.
     */
    class Breaker(private val limit: Int = STUCK_IN_A_ROW) {
        var stuckInRow = 0
            private set

        /** Учесть исход тейка; true — переключать на Google сейчас. */
        fun note(outcome: Outcome): Boolean {
            when (outcome) {
                Outcome.OK -> stuckInRow = 0
                Outcome.STUCK -> stuckInRow++
                else -> Unit
            }
            if (stuckInRow >= limit) {
                stuckInRow = 0
                return true
            }
            return false
        }
    }

    /** Имя тейка для компа: имя файла звука без «.wav» (`take_<мс>`) — по нему сводится с архивом. */
    fun takeId(audioName: String): String? =
        audioName.removeSuffix(".wav").takeIf { Regex("^[A-Za-z0-9_-]{1,80}$").matches(it) }
}
