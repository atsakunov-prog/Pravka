package ru.zf.pravka.core

/**
 * Проверка связи с облаками — числами, без Android, под тестом.
 *
 * Владелец (01.10.2026): «у меня есть проблемы с доступом к облаку… поставить
 * тумблер, чтобы включался пинг каждые 5 минут и пинговал два сервера: тот,
 * который расшифровывает, и тот, который принимает… ещё какие хочешь… плюс
 * отдельный лог, есть доступ или нет… нарисуем, когда есть доступ и когда нет…
 * мне надо где-то неделю это проверять». Повод — тейк, зависший на десять
 * минут без интернета под VPN (`ListenPolicy.cloudMute`): облако не падало
 * ошибкой, оно молчало, и снаружи было не понять, чья это беда.
 *
 * «Пинг» здесь — не ICMP (его режут и роутеры, и VPN, и Android не даёт его
 * приложению без root), а то, что делает сама Правка: HTTPS-запрос до того
 * же сервера через ту же сеть и тот же VPN. Доступ — это «соединились,
 * поговорили по TLS и получили ответ сервера», а не «сервер жив где-то».
 */
object NetProbe {

    /** Куда стучимся. [url] null — у цели своя проверка (облако семьи — WebDAV). */
    enum class Target(val key: String, val title: String, val short: String, val url: String?) {
        /**
         * Сам интернет: Google отдаёт пустой ответ 204 — так проверяет связь
         * и сам Android. 200 со страницей — это портал Wi-Fi или подмена, не
         * интернет. Нужен, чтобы отличить «нет сети» от «нет сервиса».
         */
        INTERNET("net", "Интернет", "Сеть", "https://www.google.com/generate_204"),

        /**
         * Облачное распознавание Google — та же входная дверь Google, через
         * которую ходит распознаватель. Любой ответ сервера (и 404 на голый
         * адрес) — дверь открыта.
         */
        GOOGLE("google", "Google: распознавание", "Google", "https://speech.googleapis.com/"),

        /**
         * Claude — тот, кто чистит и разбирает. Список моделей — бесплатный
         * запрос: с ключом ответ 200, без ключа 401 — оба «достучались»; 403
         * — Anthropic не пускает эту страну (VPN выключен или не тот).
         */
        CLAUDE("claude", "Claude: Anthropic", "Claude", "https://api.anthropic.com/v1/models"),

        /**
         * Домашний сервер семьи по WebDAV. Пока НЕ проверяется ([CLOUD_ON]):
         * владелец, 01.10.2026: «облако семьи не работает. Давай пока его
         * отключим в проверках» — его WebDAV ждёт соединения 20 с и ответа
         * 90 с, и «Проверить сейчас» показывало итог минуты спустя.
         */
        CLOUD("cloud", "Облако семьи", "Облако", null),
        ;

        companion object {
            fun fromKey(key: String): Target? = entries.firstOrNull { it.key == key }
        }
    }

    /** Облако семьи в проверках — выключено до починки сервера; вернуть — true. */
    const val CLOUD_ON = false

    /** Что проверяем сейчас: облако семьи — только если включено и задано. */
    fun targets(cloudConfigured: Boolean): List<Target> =
        Target.entries.filter { it != Target.CLOUD || (CLOUD_ON && cloudConfigured) }

    /** Как часто проверять, минут: ползунка нет — три положения чипами. */
    val INTERVALS_MIN = intArrayOf(5, 15, 30)

    /** С завода — раз в 15 минут (владелец, 01.10.2026: «давай по умолчанию раз в 15 минут»). */
    const val INTERVAL_DEFAULT_MIN = 15

    /** Ночью, с 00:00 до 08:00, — не чаще раза в полчаса («ночью 00:00–08:00 раз в полчаса»). */
    const val NIGHT_FROM_HOUR = 0
    const val NIGHT_TO_HOUR = 8
    const val NIGHT_MIN = 30

    /** Интервал в этот час: выбранный днём, ночью — не чаще [NIGHT_MIN]. */
    fun intervalMin(chosenMin: Int, hour: Int): Int =
        if (hour in NIGHT_FROM_HOUR until NIGHT_TO_HOUR) maxOf(chosenMin, NIGHT_MIN) else chosenMin

    /** Тик службы приходит раз в пять минут с дрожанием — срок считаем с запасом. */
    const val DUE_SLACK_MS = 30_000L

    /** Пора ли проверять: с прошлой проверки [lastAtMs] прошёл интервал [intervalMs] (с запасом). */
    fun due(lastAtMs: Long, nowMs: Long, intervalMs: Long): Boolean =
        lastAtMs <= 0L || nowMs - lastAtMs >= intervalMs - DUE_SLACK_MS

    /** Медленнее этого — доступ есть, но «еле-еле»: рисуется отдельным цветом. */
    const val SLOW_MS = 3_000L

    /** Итог одной цели: [ok] — достучались; [ms] — сколько шло; [code] — HTTP (0 — до ответа не дошло). */
    data class Hit(val ok: Boolean, val ms: Long, val code: Int, val why: String)

    /**
     * Вердикт по ответу: [code] — HTTP-код (0 — ответа нет), [error] — что
     * сломалось до ответа (таймаут, нет соединения, TLS). Причина — словами,
     * целой: владелец неделю будет читать именно её.
     */
    fun verdict(target: Target, code: Int, error: String?, ms: Long): Hit {
        if (code <= 0) return Hit(false, ms, 0, error?.takeIf { it.isNotBlank() } ?: "нет ответа")
        return when (target) {
            Target.INTERNET -> when (code) {
                204 -> Hit(true, ms, code, "есть")
                in 200..399 -> Hit(false, ms, code, "ответ $code вместо 204 — портал Wi-Fi или подмена")
                else -> Hit(false, ms, code, "ответ $code")
            }
            Target.CLAUDE -> when (code) {
                200 -> Hit(true, ms, code, "есть")
                401 -> Hit(true, ms, code, "достучались (ключ не принят)")
                403 -> Hit(false, ms, code, "403 — Anthropic не пускает отсюда (VPN выключен?)")
                429 -> Hit(true, ms, code, "достучались (лимит запросов)")
                in 500..599 -> Hit(false, ms, code, "сервер Anthropic: $code")
                else -> Hit(true, ms, code, "достучались ($code)")
            }
            Target.GOOGLE, Target.CLOUD -> when (code) {
                in 500..599 -> Hit(false, ms, code, "сервер: $code")
                else -> Hit(true, ms, code, "есть")
            }
        }
    }

    /** Что рисовать на отрезке. */
    enum class Mark { OK, SLOW, FAIL, NONE }

    fun mark(hit: Hit?): Mark = when {
        hit == null -> Mark.NONE
        !hit.ok -> Mark.FAIL
        hit.ms >= SLOW_MS -> Mark.SLOW
        else -> Mark.OK
    }

    /** Отрезок полосы дня: [fromMs, toMs) одного цвета. */
    data class Segment(val fromMs: Long, val toMs: Long, val mark: Mark)

    /**
     * Полоса дня для одной цели: каждая проверка [points] (время и итог)
     * красит время до следующей, но не дальше [holdMs] — дальше «нет данных»
     * (телефон спал, проверка была выключена). Соседние одного цвета
     * сливаются. Точки до [fromMs] окрашивают начало окна — если ещё «держат».
     */
    fun segments(points: List<Pair<Long, Mark>>, fromMs: Long, toMs: Long, holdMs: Long): List<Segment> =
        segments(points, fromMs, toMs) { holdMs }

    /** То же, но удержание — своё у каждой точки: ночью проверки реже, и держат они дольше. */
    fun segments(points: List<Pair<Long, Mark>>, fromMs: Long, toMs: Long, holdMs: (Long) -> Long): List<Segment> {
        if (toMs <= fromMs) return emptyList()
        val sorted = points.sortedBy { it.first }
        val out = ArrayList<Segment>()
        fun add(a: Long, b: Long, m: Mark) {
            val s = maxOf(a, fromMs)
            val e = minOf(b, toMs)
            if (e <= s || m == Mark.NONE) return
            val last = out.lastOrNull()
            if (last != null && last.mark == m && last.toMs >= s) {
                out[out.size - 1] = last.copy(toMs = maxOf(last.toMs, e))
            } else {
                out += Segment(s, e, m)
            }
        }
        for (i in sorted.indices) {
            val (at, m) = sorted[i]
            if (at >= toMs) break
            val next = sorted.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
            add(at, minOf(next, at + holdMs(at)), m)
        }
        return out
    }

    /** Доля проверок с доступом: (с доступом, всего). Пусто — (0, 0). */
    fun share(marks: List<Mark>): Pair<Int, Int> {
        val real = marks.filter { it != Mark.NONE }
        return real.count { it == Mark.OK || it == Mark.SLOW } to real.size
    }

    /** Процент строкой: «97 %», без данных — «—». */
    fun percent(share: Pair<Int, Int>): String =
        if (share.second == 0) "—" else "${(share.first * 100.0 / share.second).toInt()} %"

    /**
     * Строка журнала — только о ПЕРЕМЕНЕ: «Claude: нет доступа…» и «Claude:
     * снова есть, 312 мс». Двести восемьдесят строк «есть» в сутки читать
     * никто не будет; пропажу и возврат — будут.
     */
    fun change(target: Target, was: Boolean?, hit: Hit): String? = when {
        was == hit.ok -> null
        hit.ok && was == false -> "${target.title}: снова есть, ${hit.ms} мс"
        hit.ok -> "${target.title}: есть, ${hit.ms} мс"
        else -> "${target.title}: НЕТ ДОСТУПА — ${hit.why} (${hit.ms} мс)"
    }
}
