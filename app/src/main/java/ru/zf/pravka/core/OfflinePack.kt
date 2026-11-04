package ru.zf.pravka.core

/**
 * Есть ли на телефоне русский офлайн-пакет распознавания — правдой, а не
 * «служба на устройстве есть» (04.11.2026).
 *
 * Офлайн-пакет — запас облака: облако замолчало (VPN без сети) — тейк
 * дослушивает пакет, и следующие пять минут той же сети тейки идут сразу им
 * (`ListenPolicy.cloudStillDead`). Готовность пакета Правка спрашивала у
 * `SpeechRecognizer.isOnDeviceRecognitionAvailable` — а это «служба
 * распознавания на устройстве есть», про русский язык он молчит. С 27.10.2026
 * русского пакета у владельца не стало (каждый тейк пакетом — пустой, ошибка 13
 * `ERROR_LANGUAGE_UNAVAILABLE` десятки раз), а Правка пять минут после каждого
 * сбоя облака слала все тейки туда: «Не слышит ничего… включаю, переключаю —
 * бесполезно… через 10 минут отмирает». И настройки писали «офлайн-пакет —
 * запас».
 *
 * Правда берётся двумя дорогами: проверкой поддержки у офлайн-распознавателя
 * (`checkRecognitionSupport`, Android 13+: установленные и качающиеся языки)
 * и ошибкой 12/13 в самом тейке. Что из этого следует — здесь, без Android,
 * под тестом.
 */
object OfflinePack {

    /** `SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED`. */
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12

    /** `SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE`: язык поддержан, но не скачан. */
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /** Ошибка распознавателя значит «пакета этого языка на телефоне нет». */
    fun missingCode(code: Int): Boolean =
        code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    enum class State {
        /** Не спрашивали или служба не ответила (Android старше 13, отказ проверки). */
        UNKNOWN,
        /** Пакет языка установлен — запас настоящий. */
        INSTALLED,
        /** Пакет качается — пока его нет. */
        PENDING,
        /** Пакета нет. */
        MISSING,
    }

    /**
     * Что сказала проверка поддержки: [installed] и [pending] — теги языков
     * (`ru-RU`, бывает и просто `ru`). Сравниваем по основному тегу: пакету
     * «ru» всё равно, просили его как ru-RU или ru.
     */
    fun fromSupport(language: String, installed: List<String>, pending: List<String>): State {
        val want = primary(language)
        return when {
            installed.any { primary(it) == want } -> State.INSTALLED
            pending.any { primary(it) == want } -> State.PENDING
            else -> State.MISSING
        }
    }

    private fun primary(tag: String): String = tag.trim().substringBefore('-').substringBefore('_').lowercase()

    /**
     * Что знаем о пакете: [state] — последний ответ проверки поддержки;
     * [missingAtMs] — когда тейк в последний раз получил ошибку «пакета нет»
     * (часы `elapsedRealtime`; 0 — не получал).
     */
    data class Info(val state: State = State.UNKNOWN, val missingAtMs: Long = 0L)

    /**
     * Ошибка «пакета нет» в самом тейке весит больше ответа проверки и
     * помнится столько: потом пакет пробуем снова — владелец мог скачать его
     * сам, а проверки на старых Android нет вовсе.
     */
    const val MISSING_TTL_MS = 30L * 60 * 1000

    /** Тейк недавно сам получил «пакета нет». */
    fun freshMiss(info: Info, nowMs: Long): Boolean =
        info.missingAtMs > 0L && nowMs - info.missingAtMs in 0 until MISSING_TTL_MS

    /**
     * Можно ли уходить на пакет. [service] — офлайн-служба на телефоне есть.
     * Неизвестное — можно, как было до 04.11: на старых Android проверить
     * нечем, а ошибка тейка всё равно вернёт его в облако. Свежая ошибка тейка
     * сильнее «установлен» от проверки: пакет, который отвечает 13, — не запас.
     */
    fun usable(service: Boolean, info: Info, nowMs: Long): Boolean {
        if (!service) return false
        if (info.state == State.MISSING || info.state == State.PENDING) return false
        return !freshMiss(info, nowMs)
    }

    /**
     * Ответ проверки [probed] поверх того, что знали. Пакет ПОЯВИЛСЯ (был «нет»
     * или «качается», стал «установлен») — это новость, и ошибку тейка она
     * снимает; «установлен» поверх свежей ошибки без такой смены — противоречие,
     * верим тейку до конца [MISSING_TTL_MS].
     */
    fun afterProbe(old: Info, probed: State): Info = when {
        probed == State.INSTALLED && (old.state == State.MISSING || old.state == State.PENDING) ->
            Info(State.INSTALLED, 0L)
        else -> old.copy(state = probed)
    }

    /** Тейк получил «пакета нет» в [nowMs]. */
    fun afterTakeMiss(old: Info, nowMs: Long): Info = old.copy(missingAtMs = nowMs)

    /** Проверку поддержки повторять не чаще — она будит службу, хоть и не трогает микрофон. */
    const val PROBE_EVERY_MS = 30L * 60 * 1000

    /** Пора спросить службу снова. [force] — после скачивания, ошибки тейка, экрана настроек. */
    fun probeDue(lastProbeMs: Long, nowMs: Long, force: Boolean): Boolean =
        force || lastProbeMs <= 0L || nowMs - lastProbeMs !in 0 until PROBE_EVERY_MS

    /**
     * Просить Android скачать пакет сам — не чаще. Скачивание на Pixel тихое
     * и ждёт Wi-Fi, но просьба посреди каждого тейка — шум в журнале и в
     * службе; раз в полдня хватит, кнопка «Подготовить русскую модель» —
     * всегда.
     */
    const val AUTO_DOWNLOAD_EVERY_MS = 12L * 60 * 60 * 1000

    fun downloadDue(lastAskMs: Long, nowMs: Long): Boolean =
        lastAskMs <= 0L || nowMs - lastAskMs !in 0 until AUTO_DOWNLOAD_EVERY_MS

    /** Состояние словами — для журнала. */
    fun word(state: State): String = when (state) {
        State.INSTALLED -> "русский установлен"
        State.PENDING -> "русский качается"
        State.MISSING -> "русского нет"
        State.UNKNOWN -> "служба не сказала"
    }

    /** Строка для настроек: что с запасом облака на деле. */
    fun label(service: Boolean, info: Info, nowMs: Long): String = when {
        !service -> "офлайн-распознавания на телефоне нет — без сети и при сбое облака слушать нечем"
        info.state == State.PENDING -> "русский офлайн-пакет качается — пока при сбое облака слушать нечем"
        info.state == State.MISSING || freshMiss(info, nowMs) ->
            "русского офлайн-пакета нет — при сбое облака слушать нечем; нажми «Подготовить русскую модель»"
        info.state == State.INSTALLED -> "русский офлайн-пакет установлен — запас облака"
        else -> "офлайн-пакет — запас облака (есть ли русский, служба не сказала)"
    }
}
