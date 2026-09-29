package ru.zf.pravka.core

/**
 * Пауза в речи по громкости записи — миг, где можно переставить вход записи,
 * не разрезав слово: мост через телефон уступает наушникам только в паузе
 * (`ListenPolicy.bridgeStep`). Без Android, под тестом.
 *
 * Громкость — dBFS куска записи. Порог — не число, а шум места: в тихой
 * комнате пол около −60 дБ, в машине −40, в кармане своё, и тишиной
 * считается всё, что не выше пола на [SPEECH_OVER_FLOOR_DB]. Пол падает
 * сразу (тише — значит, это и есть тишина) и поднимается медленно: долгий
 * голос полом не становится. Ровные нули (наушники с шумодавом, спящий
 * канал) — тишина.
 */
class PauseDetector {

    private var floorDb = Double.NaN

    /** Сколько тишины подряд к последнему куску, мс. */
    var quietMs = 0L
        private set

    /** Кусок записи длиной [chunkMs] громкостью [dbfs]. Возвращает, тишина ли он. */
    fun feed(dbfs: Double, chunkMs: Long): Boolean {
        val db = if (dbfs.isNaN() || dbfs < FLOOR_MIN_DB) FLOOR_MIN_DB else dbfs
        floorDb = when {
            floorDb.isNaN() -> db
            db < floorDb -> db
            else -> floorDb + (db - floorDb) * FLOOR_RISE
        }
        val quiet = db < ABSOLUTE_QUIET_DB || db < floorDb + SPEECH_OVER_FLOOR_DB
        quietMs = if (quiet) quietMs + chunkMs else 0L
        return quiet
    }

    companion object {
        /** Голос — громче пола места на столько. */
        const val SPEECH_OVER_FLOOR_DB = 10.0

        /** Тише этого — тишина при любом поле: голоса такой громкости не бывает даже из кармана. */
        const val ABSOLUTE_QUIET_DB = -58.0

        /** Ниже — всё одно: ровные нули и шум младшего разряда. */
        const val FLOOR_MIN_DB = -90.0

        /**
         * Доля, на которую пол подтягивается к громкости за кусок (50 мс):
         * за секунду сплошного голоса — на двадцатую часть разницы, и восемь
         * секунд моста голос остаётся голосом. Настоящую тишину пол находит
         * сразу: в паузах между словами он падает до неё.
         */
        const val FLOOR_RISE = 0.003
    }
}
