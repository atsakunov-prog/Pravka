package ru.zf.pravka.core

/**
 * Телефон в ленте вживую (13.10.2026, второй заход того же дня). Первый заход
 * разбирал сидение задним числом, когда телефон уже отложен, и смотрел на
 * экран раз в минуту всегда, пока он включён. Владелец: «если больше семи
 * минут — каждую минуту скрин, и на десятой говорит: кажется, это „дело“,
 * галка и крестик. Галка — ставит дело, пока не отложу телефон. Нажал „да“ —
 * перестаёт делать скрины и просто следит, когда приложение закроется на две
 * минуты. Крестик — перестаёт следить и понимает, что я делаю то, что в ленте.
 * А если после „да“ дальше другое приложение — снова раз в минуту скрин и
 * снова спрашивает. Это оберегает от того, что он будет кучу скринов
 * отправлять одного и того же».
 *
 * Отсюда автомат: база — что решено про сидение (ничего, «да» — дело стоит в
 * ленте, «нет» — молчим), и поверх неё — проверка куска: снимки раз в минуту,
 * на третьем — Сонет, его догадка — вопросом. На экран смотрим ТОЛЬКО во
 * время проверки: в обычном взгляде на телефон, после «да» и после «нет» —
 * ни одного снимка, пока впереди те же приложения.
 *
 * Здесь — решения без Android, Claude и ленты: движок (`data/PhoneSitEngine.kt`)
 * кормит автомат тем, что видно по статистике использования, и делает то, что
 * автомат велит.
 */
object PhoneLive {

    /** Снимков до вопроса: начали смотреть на седьмой минуте — спросили на десятой. */
    const val ASK_SNAPS = 3

    /** Другое приложение впереди столько — это, может быть, другое занятие: проверить. */
    const val SWITCH_MS = 2 * 60_000L

    /** Телефон отложен столько — подтверждённое дело кончилось (в миг, когда отложил). */
    const val PUTDOWN_MS = 5 * 60_000L

    /**
     * Молчание на вопрос столько — «да»: плашка уходит через полминуты, как
     * у «Всё ещё …?», и молчание в Правке — согласие (`core/AutoConfirm.kt`).
     */
    const val ANSWER_MS = 35_000L

    enum class Base { IDLE, ON, OFF }

    /** Догадка Сонета: какое дело и с какого мига. */
    data class Guess(val title: String, val category: String, val client: String, val from: Long)

    data class State(
        val base: Base = Base.IDLE,
        /** ON и OFF: приложения занятия — пока впереди они, на экран не смотрим. */
        val apps: Set<String> = emptySet(),
        /** ON: запись ленты, которую поставили, её название и что шло до неё. */
        val entryId: Long = 0L,
        val title: String = "",
        val resumeId: Long = 0L,
        /** Проверяем кусок с этого мига (0 — не проверяем); снимков в нём. */
        val watchFrom: Long = 0L,
        val snaps: Int = 0,
        /** Сонет думает над куском — второй запрос не шлём. */
        val thinking: Boolean = false,
        /** Вопрос на плашке, с какого мига висит, приложения проверенного куска. */
        val guess: Guess? = null,
        val askedAt: Long = 0L,
        val segApps: Set<String> = emptySet(),
        /** Сидения до этого мига разобраны. */
        val handled: Long = 0L,
    ) {
        val watching: Boolean get() = watchFrom > 0L
        /** Снимок нужен: идёт проверка, вопрос ещё не задан и Сонет не думает. */
        val wantsSnap: Boolean get() = watching && !thinking && guess == null

        fun unwatched(): State = copy(watchFrom = 0L, snaps = 0, thinking = false, guess = null, askedAt = 0L, segApps = emptySet())
    }

    /**
     * Что видно по статистике использования прямо сейчас: последнее сидение
     * после разобранного, конец последнего куска любого приложения и с какого
     * мига впереди подряд НЕ приложения занятия (0 — впереди они, или
     * телефон отложен).
     */
    data class View(val sitting: PhoneSit.Stretch?, val lastUse: Long, val outsideSince: Long)

    sealed interface Do

    /** Сонету: кусок [from, to) — что это и то ли, что в ленте. */
    data class Classify(val from: Long, val to: Long) : Do

    /** Сидение кончилось до вопроса — как в первом заходе: врезкой задним числом и тихим пушем. */
    data class Behind(val from: Long, val to: Long) : Do

    /** Закрыть поставленное дело [entryId] в [at] и вернуть то, что шло до него ([resumeId]). */
    data class Close(val entryId: Long, val resumeId: Long, val at: Long) : Do

    /** Поставить дело [guess] с его начала: идущее закрывается там же. [silent] — «да» молчанием. */
    data class Open(val guess: Guess, val apps: Set<String>, val silent: Boolean) : Do

    data class Step(val state: State, val dos: List<Do> = emptyList())

    /** Минута подсмотра: снимок взят (или текст не читается — минута всё равно прошла). */
    fun minute(s: State): State = if (s.wantsSnap) s.copy(snaps = s.snaps + 1) else s

    /**
     * Пульс: раз в минуту, пока экран открыт, и пятиминутным тиком службы.
     * Отложил телефон — дело кончилось; сидение дотянуло до порога — смотрим;
     * впереди другое приложение две минуты — смотрим заново; три снимка —
     * Сонету; вопрос без ответа — «да».
     */
    fun pulse(s: State, v: View, now: Long, minMs: Long): Step {
        if (s.guess != null && now - s.askedAt >= ANSWER_MS) return answer(s, yes = true, silent = true)
        val quiet = v.lastUse == 0L || now - v.lastUse >= PhoneSit.QUIET_MS
        if (s.base == Base.ON && v.lastUse > 0L && now - v.lastUse >= PUTDOWN_MS) {
            return Step(State(handled = maxOf(s.handled, v.lastUse)), listOf(Close(s.entryId, s.resumeId, v.lastUse)))
        }
        if (s.watching && s.guess == null) {
            if (quiet) {
                return when (s.base) {
                    Base.IDLE -> {
                        val sit = v.sitting
                        val behind = if (sit != null && sit.useMs >= minMs) listOf(Behind(s.watchFrom, v.lastUse)) else emptyList()
                        Step(State(handled = maxOf(s.handled, v.lastUse)), behind)
                    }
                    Base.OFF -> Step(State(handled = maxOf(s.handled, v.lastUse)))
                    // Подтверждённое дело закроется, когда телефон пролежит пять минут.
                    Base.ON -> Step(s.unwatched())
                }
            }
            if (!s.thinking && s.snaps >= ASK_SNAPS) {
                return Step(s.copy(thinking = true), listOf(Classify(s.watchFrom, now)))
            }
            return Step(s)
        }
        if (s.watching) return Step(s)
        return when (s.base) {
            Base.OFF -> if (quiet) Step(State(handled = maxOf(s.handled, v.lastUse))) else switched(s, v, now)
            Base.ON -> switched(s, v, now)
            Base.IDLE -> {
                val sit = v.sitting ?: return Step(s)
                when {
                    // Кончилось, а пульсы его не видели (служба спала): по-старому, задним числом.
                    sit.finished -> Step(
                        s.copy(handled = maxOf(s.handled, sit.end)),
                        if (sit.useMs >= minMs) listOf(Behind(sit.start, sit.end)) else emptyList(),
                    )
                    sit.useMs >= minMs -> Step(s.copy(watchFrom = sit.start, snaps = 0))
                    else -> Step(s)
                }
            }
        }
    }

    private fun switched(s: State, v: View, now: Long): Step =
        if (v.outsideSince > 0L && now - v.outsideSince >= SWITCH_MS) Step(s.copy(watchFrom = v.outsideSince, snaps = 0))
        else Step(s)

    /**
     * Сонет ответил про кусок: [guess] null — то же, что в ленте (молчим, пока
     * впереди эти приложения), иначе — вопрос на плашку.
     */
    fun classified(s: State, guess: Guess?, segApps: Set<String>, now: Long): Step {
        if (!s.watching) return Step(s.copy(thinking = false))
        if (guess == null) {
            return Step(
                when (s.base) {
                    Base.IDLE -> s.unwatched().copy(base = Base.OFF, apps = segApps)
                    else -> s.unwatched().copy(apps = s.apps + segApps)
                }
            )
        }
        return Step(s.copy(thinking = false, guess = guess, askedAt = now, segApps = segApps))
    }

    /** Сонет не ответил (сеть): проверка остаётся, следующий пульс спросит снова. */
    fun failed(s: State): State = s.copy(thinking = false)

    /**
     * Ответ на «кажется, это …»: галочка (или молчание) — поставить дело,
     * крестик — то, что в ленте, верно: молчим, пока впереди эти приложения.
     */
    fun answer(s: State, yes: Boolean, silent: Boolean = false): Step {
        val g = s.guess ?: return Step(s)
        if (!yes) {
            return Step(
                when (s.base) {
                    Base.IDLE -> s.unwatched().copy(base = Base.OFF, apps = s.segApps)
                    else -> s.unwatched().copy(apps = s.apps + s.segApps)
                }
            )
        }
        return Step(s.unwatched(), listOf(Open(g, s.segApps, silent)))
    }

    /**
     * Дело поставлено: дальше на экран не смотрим, пока впереди его
     * приложения. Вернуть после — то, что шло до САМОГО первого дела этого
     * сидения: «Завтрак → Клод → Ютуб» после ютуба возвращает завтрак.
     */
    fun opened(s: State, entryId: Long, title: String, apps: Set<String>, resumeId: Long): State =
        State(
            base = Base.ON,
            apps = apps,
            entryId = entryId,
            title = title,
            resumeId = if (s.base == Base.ON) s.resumeId else resumeId,
            handled = s.handled,
        )

    /**
     * Владелец сам сказал в «З» (или убрал запись): его слово сильнее. До
     * конца сидения молчим о тех же приложениях, ничего не возвращаем.
     */
    fun ownerTook(s: State): State = State(base = Base.OFF, apps = s.apps, handled = s.handled)

    /**
     * С какого мига впереди подряд не [apps]: от конца назад по кускам, пока
     * они чужие и паузы между ними короче [PhoneSit.GAP_MS]. Последний кусок
     * должен идти сейчас — телефон отложен, значит переключения нет.
     */
    fun outsideSince(spans: List<PhoneSit.Span>, apps: Set<String>, now: Long): Long {
        val sorted = spans.sortedBy { it.start }
        val last = sorted.lastOrNull() ?: return 0L
        if (now - last.end > PhoneSit.GAP_MS || last.pkg in apps) return 0L
        var since = last.start
        var edge = last.start
        for (sp in sorted.dropLast(1).asReversed()) {
            if (edge - sp.end > PhoneSit.GAP_MS) break
            if (sp.pkg in apps) break
            since = sp.start
            edge = sp.start
        }
        return since
    }
}
