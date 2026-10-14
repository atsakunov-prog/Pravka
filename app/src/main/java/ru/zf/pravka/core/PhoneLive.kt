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
 * После «да» (14.10.2026, третий заход): «телефон считает до момента, как я
 * выключил сам телефон. Если не выключил, то он раз в 5 минут проверяет и
 * подтверждается, что я именно то и делаю, что он считает. Но если нет, то
 * тогда он снова предлагает. Выключение телефона на 30 секунд и есть конец
 * дела (после него продолжается предыдущее дело без телефона). Если я в рамках
 * 30 секунд снова взял телефон, то тогда он смотрит опять каждую минуту, чем я
 * занят». И про деньги: «это будет дорого? там только текст берётся?» — да,
 * только текст, и Сонета зовём не на каждое подтверждение, а только когда на
 * экране поменялось приложение или предмет (заголовок чата, документа):
 * сравнение — здесь, на телефоне, бесплатно.
 *
 * Отсюда автомат: база — что решено про сидение (ничего, «да» — дело стоит в
 * ленте, «нет» — молчим), и поверх неё — проверка куска: снимки раз в минуту,
 * на третьем — Сонет, его догадка — вопросом. У стоящего дела («да») — своя
 * жизнь: экран погас на полминуты — дело кончилось в миг погасания; гас
 * меньше — поминутное наблюдение; горит — подтверждение раз в пять минут
 * одним снимком. Снимок то же приложение и тот же предмет — подтверждено без
 * запроса; другое — проверка куска, как после переключения.
 *
 * Без вечного молчания (14.10.2026, после жалобы «сижу в телефоне, а он мне
 * даже ничего не говорит»): из каждого состояния есть дорога назад к
 * проверке — тишина три минуты, другое приложение две минуты, а слово
 * владельца и охрана ленты — только про своё время: сидение от порога ПОСЛЕ
 * них — новая проверка ([State.freshFrom]). Проверка без снимков (служба не
 * смотрит) и Сонет без ответа — по часам ([STALE_MS], [THINK_MS]), а не
 * «пока сидение не кончится». Каждый конец проверки — строкой в журнал.
 *
 * Здесь — решения без Android, Claude и ленты: движок (`data/PhoneSitEngine.kt`)
 * кормит автомат тем, что видно по статистике использования и снимкам, и
 * делает то, что автомат велит. Удар движка целиком — [beat]: тот же путь
 * прогоняют тесты.
 */
object PhoneLive {

    /** Снимков до вопроса: начали смотреть на седьмой минуте — спросили на десятой. */
    const val ASK_SNAPS = 3

    /** Другое приложение впереди столько — это, может быть, другое занятие: проверить. */
    const val SWITCH_MS = 2 * 60_000L

    /**
     * Экран погас столько — дело из телефона кончилось, в миг погасания
     * (владелец, 14.10.2026: «выключение телефона на 30 секунд и есть конец
     * дела»). Короче — выключил и сразу взял: дело идёт, а смотрим каждую минуту.
     */
    const val OFF_MS = 30_000L

    /**
     * Запас на случай, когда экран не гаснет: телефон на подставке с
     * заставкой, оставлен на рабочем столе. Телефоном не пользуются пять минут —
     * дело кончилось в миг последнего касания (так закрывалось до 14.10).
     */
    const val PUTDOWN_MS = 5 * 60_000L

    /** Экран горит — подтверждение «то же ли он делает» раз в столько. */
    const val CONFIRM_MS = 5 * 60_000L

    /**
     * Поминутное наблюдение после короткого погасания: столько снимков подряд
     * «то же» — назад к пяти минутам (пять поминутных равны одному обычному
     * подтверждению, дальше смотреть чаще незачем).
     */
    const val MINUTE_RUN = 5

    /**
     * Молчание на вопрос столько — «да»: плашка уходит через полминуты, как
     * у «Всё ещё …?», и молчание в Правке — согласие (`core/AutoConfirm.kt`).
     */
    const val ANSWER_MS = 35_000L

    /**
     * Предмет экрана — первые куски видимого текста: обход окна идёт сверху,
     * и первым приходит заголовок (чат, документ, ролик, адрес). Двух хватает,
     * чтобы отличить один чат от другого; больше — и в предмет попадёт сама
     * переписка, которая меняется с каждым сообщением.
     */
    const val SUBJECT_PIECES = 2

    /**
     * Проверка идёт столько по часам, а трёх снимков нет (минуты экрана не
     * приходят: служба не смотрит, телефон то гас, то зажигался) — Сонету по
     * тому, что есть. Иначе проверка висела бы до конца сидения: снимки
     * считает только минута экрана, а пятиминутный тик их не прибавляет.
     */
    const val STALE_MS = 5 * 60_000L

    /**
     * Сонет думает дольше этого — ответа не ждём, проверка остаётся и через
     * три минуты спросит снова. Запрос с одним повтором — до двух раз по 90 с
     * (чтение у общего клиента), так что живой ответ сюда не попадает; а
     * зависший (или упавший мимо `failed`) больше не держит «думает» до конца
     * сидения.
     */
    const val THINK_MS = 4 * 60_000L

    /** Подтверждённых «приложение + предмет» у одного дела — не больше. */
    private const val MARKS_MAX = 12

    enum class Base { IDLE, ON, OFF }

    /** Догадка Сонета: какое дело и с какого мига. */
    data class Guess(val title: String, val category: String, val client: String, val from: Long)

    /**
     * Что впереди: приложение и предмет — ХЭШ первых строк текста экрана
     * (null — текст не читался: выключен тумблер, банк, сложенный экран).
     * Самого текста здесь нет: состояние автомата пишется на диск, а текст
     * экрана — никогда.
     */
    data class Mark(val pkg: String, val subject: Int?)

    data class State(
        val base: Base = Base.IDLE,
        /** ON и OFF: приложения занятия — пока впереди они, на экран не смотрим. */
        val apps: Set<String> = emptySet(),
        /** ON: запись ленты, которую поставили, её название и что шло до неё. */
        val entryId: Long = 0L,
        val title: String = "",
        val resumeId: Long = 0L,
        /** ON: погасание экрана с этого мига кончает дело (до «да» экран гас — это ещё не про дело). */
        val since: Long = 0L,
        /**
         * ON: что подтверждено как это дело — приложение и предмет. Пусто —
         * сверяем только по приложениям: после крестика («верно то, что в
         * ленте») или когда текст экрана не читается.
         */
        val marks: Set<Mark> = emptySet(),
        /** ON: когда следующее подтверждение (0 — на ближайшей минуте). */
        val confirmAt: Long = 0L,
        /** ON: поминутное наблюдение после короткого погасания и сколько минут подряд в нём «то же». */
        val everyMinute: Boolean = false,
        val sameRun: Int = 0,
        /** ON: конец последнего короткого погасания, на которое уже отозвались. */
        val litAt: Long = 0L,
        /** ON: когда последний раз видели подтверждённое — с этого мига смотрим кусок, если впереди другое. */
        val seenAt: Long = 0L,
        /** Проверяем кусок с этого мига (0 — не проверяем); снимков в нём. */
        val watchFrom: Long = 0L,
        val snaps: Int = 0,
        /** Когда проверка началась по часам ([watchFrom] — начало куска, бывает и раньше): для [STALE_MS]. */
        val watchAt: Long = 0L,
        /** Последний снимок: что впереди сейчас (приложение и предмет). */
        val lastMark: Mark? = null,
        /** Сонет думает над куском — второй запрос не шлём; с какого мига — для [THINK_MS]. */
        val thinking: Boolean = false,
        val thinkingAt: Long = 0L,
        /** Вопрос на плашке, с какого мига висит, приложения проверенного куска. */
        val guess: Guess? = null,
        val askedAt: Long = 0L,
        val segApps: Set<String> = emptySet(),
        /** Сидения до этого мига разобраны. */
        val handled: Long = 0L,
        /**
         * OFF: молчим из-за слова владельца или охраны ленты (сон, встреча,
         * дорога, тренировка, факт робота), а они — про своё время, до этого
         * мига. Сидение ПОСЛЕ него дотянуло до порога — новая проверка
         * (14.10.2026: «ты сам сказал «Завтрак»» — и двадцать минут в телефоне
         * тишина). 0 — молчим до конца сидения: крестик и «то же» Сонета —
         * ответ про это занятие, их договорённость прежняя.
         */
        val freshFrom: Long = 0L,
    ) {
        val watching: Boolean get() = watchFrom > 0L

        /** Снимок нужен для проверки: идёт проверка, вопрос ещё не задан и Сонет не думает. */
        val wantsSnap: Boolean get() = watching && !thinking && guess == null

        /**
         * Снимок нужен в эту минуту: проверка — или пора подтвердить стоящее
         * дело (раз в пять минут, после короткого погасания — каждую минуту).
         * Сверяем по приложениям (пустые [marks]) — снимок не нужен вовсе:
         * приложение впереди видно по статистике использования.
         */
        fun snapDue(now: Long): Boolean =
            wantsSnap || (base == Base.ON && !watching && !thinking && guess == null && marks.isNotEmpty() &&
                (everyMinute || now >= confirmAt))

        fun unwatched(): State =
            copy(
                watchFrom = 0L, snaps = 0, watchAt = 0L, lastMark = null, thinking = false, thinkingAt = 0L,
                guess = null, askedAt = 0L, segApps = emptySet(),
            )

        /**
         * С какого мига считать сидение: обычно — после разобранного; в OFF
         * после слова владельца — ещё и после него ([freshFrom]).
         */
        fun countFrom(): Long = if (base == Base.OFF && freshFrom > 0L) maxOf(handled, freshFrom) else handled
    }

    /**
     * Начать проверку куска с [from]: снимки с нуля (или с [snaps], если снимок
     * этой минуты уже в счёт), поминутная сверка дела кончается. Отсрочка после
     * слова владельца ([State.freshFrom]) тоже: дальше решает проверка — иначе
     * то же сидение после её «то же» снова дотягивало бы до порога, и Сонета
     * звали бы по кругу.
     */
    private fun watch(s: State, from: Long, now: Long, snaps: Int = 0, mark: Mark? = null): State =
        s.copy(
            watchFrom = from, snaps = snaps, watchAt = now, lastMark = mark, thinking = false, thinkingAt = 0L,
            everyMinute = false, sameRun = 0, freshFrom = 0L,
        )

    /** Экран погас в [from] и зажёгся в [to] (0 — ещё тёмный). */
    data class Off(val from: Long, val to: Long) {
        fun ms(now: Long): Long = (if (to == 0L) now else to) - from
    }

    /**
     * Что видно по статистике использования прямо сейчас: последнее сидение
     * после разобранного, конец последнего куска любого приложения, с какого
     * мига впереди подряд НЕ приложения занятия (0 — впереди они, или
     * телефон отложен) и когда гас экран.
     */
    data class View(
        val sitting: PhoneSit.Stretch?,
        val lastUse: Long,
        val outsideSince: Long,
        val offs: List<Off> = emptyList(),
    )

    sealed interface Do

    /** Сонету: кусок [from, to) — что это и то ли, что в ленте. */
    data class Classify(val from: Long, val to: Long) : Do

    /** Сидение кончилось до вопроса — как в первом заходе: врезкой задним числом и тихим пушем. */
    data class Behind(val from: Long, val to: Long) : Do

    /**
     * Закрыть поставленное дело [entryId] в [at] и вернуть то, что шло до него
     * ([resumeId]). [dark] — экран погас на полминуты; иначе — телефоном не
     * пользовались пять минут.
     */
    data class Close(val entryId: Long, val resumeId: Long, val at: Long, val dark: Boolean = false) : Do

    /**
     * Поставить дело [guess] с его начала: идущее закрывается там же. [silent] —
     * «да» молчанием. [since] — с какого мига погасание экрана кончает дело,
     * [mark] — что было впереди при ответе: первое подтверждённое.
     */
    data class Open(
        val guess: Guess,
        val apps: Set<String>,
        val silent: Boolean,
        val since: Long = 0L,
        val mark: Mark? = null,
    ) : Do

    /** Строка в журнал: шаг автомата, который ничего не меняет в ленте. */
    data class Note(val text: String) : Do

    data class Step(val state: State, val dos: List<Do> = emptyList())

    /**
     * Минута подсмотра (экран открыт; снимок уже взят, если был нужен): [seen] —
     * что впереди сейчас (со снимка, иначе приложение из статистики, null —
     * впереди ничего, рабочий стол). Идёт проверка — снимок в счёт. Стоит дело и
     * пора подтвердить — сверка: то же приложение и тот же предмет —
     * подтверждено без запроса; другое — проверка куска с мига, когда своё
     * видели последний раз.
     */
    fun minute(s: State, seen: Mark?, now: Long): Step {
        if (s.watching) {
            return Step(if (s.wantsSnap) s.copy(snaps = s.snaps + 1, lastMark = seen ?: s.lastMark) else s)
        }
        if (s.base != Base.ON || s.guess != null || s.thinking || s.marks.isEmpty()) return Step(s)
        if (!s.everyMinute && now < s.confirmAt) return Step(s)
        if (seen == null) return Step(s)
        if (confirms(s, seen)) {
            if (s.everyMinute) {
                val run = s.sameRun + 1
                if (run >= MINUTE_RUN) {
                    return Step(
                        s.copy(everyMinute = false, sameRun = 0, seenAt = now, lastMark = seen, confirmAt = now + CONFIRM_MS),
                        listOf(Note("«${s.title}»: $MINUTE_RUN минут подряд то же — снова сверяю раз в пять минут")),
                    )
                }
                return Step(s.copy(sameRun = run, seenAt = now, lastMark = seen))
            }
            val what = if (seen.subject == null) "то же приложение" else "то же приложение и тот же экран"
            return Step(
                s.copy(seenAt = now, lastMark = seen, confirmAt = now + CONFIRM_MS),
                listOf(Note("«${s.title}»: подтверждаю сам — впереди $what, Сонета не зову")),
            )
        }
        val from = maxOf(s.seenAt, s.since).takeIf { it > 0L } ?: now
        return Step(
            watch(s, from, now, snaps = 1, mark = seen),
            listOf(Note("«${s.title}»: впереди другое — смотрю на экран с ${PhoneSit.hm(from)}")),
        )
    }

    /**
     * То ли впереди, что подтверждено: приложение и предмет среди
     * подтверждённых; без предмета (текст не читался) или после крестика —
     * по приложению.
     */
    fun confirms(s: State, m: Mark): Boolean =
        if (m.subject == null || s.marks.isEmpty()) m.pkg in s.apps || s.marks.any { it.pkg == m.pkg }
        else m in s.marks

    /**
     * Предмет экрана — хэш первых [SUBJECT_PIECES] кусков текста снимка (куски
     * разделены « · », как их склеивает `PhoneSit.clean`), без цифр и знаков:
     * «был(а) 5 минут назад» и «был(а) 7 минут назад» — один предмет. null —
     * текста нет.
     */
    fun subject(text: String): Int? {
        val head = text.split(" · ").asSequence()
            .map { it.lowercase().replace('ё', 'е').replace(NOT_LETTER, " ").replace(SPACES, " ").trim() }
            .filter { it.length >= 2 }
            .take(SUBJECT_PIECES)
            .toList()
        if (head.isEmpty()) return null
        return head.joinToString("|").hashCode()
    }

    private val NOT_LETTER = Regex("[^\\p{L} ]")
    private val SPACES = Regex("\\s+")

    /**
     * Погасания экрана из событий: от «погас» до «зажёгся». Конец — только
     * событием экрана: «приложение вышло» вокруг погасания бывает и при тёмном
     * экране (замок), и по нему двухминутная темнота выглядела бы морганием.
     */
    fun offs(events: List<PhoneSit.Ev>): List<Off> {
        val out = ArrayList<Off>()
        var darkAt = 0L
        for (e in events.sortedBy { it.at }) {
            when (e) {
                is PhoneSit.Dark -> if (darkAt == 0L) darkAt = e.at
                is PhoneSit.Lit -> if (darkAt > 0L) {
                    out += Off(darkAt, maxOf(darkAt, e.at))
                    darkAt = 0L
                }
                else -> {}
            }
        }
        if (darkAt > 0L) out += Off(darkAt, 0L)
        return out
    }

    /**
     * Экран зажёгся, а телефоном не пользовались (уведомление разбудило,
     * глянул время на замке) — темнота та же: соседние погасания без куска
     * приложения между ними — одно. Иначе дело закрывалось бы не в миг, когда
     * владелец выключил экран, а после чужого зажигания. [spans] — куски
     * приложений без мебели (замок, рабочий стол — мебель).
     */
    fun merge(offs: List<Off>, spans: List<PhoneSit.Span>): List<Off> {
        val out = ArrayList<Off>()
        for (o in offs.sortedBy { it.from }) {
            val prev = out.lastOrNull()
            val used = prev != null && spans.any { it.end > prev.to && it.start < o.from }
            if (prev != null && prev.to > 0L && !used) out[out.size - 1] = Off(prev.from, o.to) else out += o
        }
        return out
    }

    /**
     * Пульс: раз в минуту, пока экран открыт, и пятиминутным тиком службы.
     * Стоящее дело: экран погас на полминуты — кончилось; гас меньше —
     * поминутное наблюдение. Сидение дотянуло до порога — смотрим; впереди
     * другое приложение две минуты — смотрим заново; три снимка — Сонету
     * (или без него, если впереди снова подтверждённое); вопрос без ответа — «да».
     */
    fun pulse(s0: State, v: View, now: Long, minMs: Long): Step {
        if (s0.guess != null && now - s0.askedAt >= ANSWER_MS) return answer(s0, yes = true, silent = true, now = now)
        var s = s0
        val notes = ArrayList<Do>()
        if (s.thinking && s.thinkingAt > 0L && now - s.thinkingAt >= THINK_MS) {
            notes += Note("Сонет молчит ${(now - s.thinkingAt) / 60_000L} мин — ответа не жду; ещё три минуты и спрошу снова")
            s = failed(s, now)
        }
        if (s.base == Base.ON && s.guess == null) {
            val off = v.offs.firstOrNull { it.from >= s.since && it.ms(now) >= OFF_MS }
            if (off != null) {
                // Шла проверка другого куска — его разберёт Сонет задним числом
                // (короче порога — не режет), а дело закрывается в миг погасания.
                val behind = if (s.watching && s.watchFrom < off.from) Behind(s.watchFrom, off.from) else null
                return Step(
                    State(handled = maxOf(s.handled, off.from)),
                    notes + listOfNotNull(Close(s.entryId, s.resumeId, off.from, dark = true), behind),
                )
            }
            if (v.lastUse > 0L && now - v.lastUse >= PUTDOWN_MS) {
                return Step(State(handled = maxOf(s.handled, v.lastUse)), notes + listOf(Close(s.entryId, s.resumeId, v.lastUse)))
            }
            val blink = v.offs.lastOrNull { it.to > 0L && it.from >= s.since && it.to > s.litAt }
            if (blink != null) {
                s = s.copy(litAt = blink.to, everyMinute = s.marks.isNotEmpty() && !s.watching, sameRun = 0)
                notes += Note(
                    "«${s.title}»: экран гас на ${(blink.ms(now) + 500L) / 1000L} с — дело идёт" +
                        (if (s.everyMinute) ", смотрю каждую минуту" else "")
                )
            }
        }
        val step = pulseOn(s, v, now, minMs)
        return if (notes.isEmpty()) step else Step(step.state, notes + step.dos)
    }

    private fun pulseOn(s: State, v: View, now: Long, minMs: Long): Step {
        val quiet = v.lastUse == 0L || now - v.lastUse >= PhoneSit.QUIET_MS
        if (s.watching && s.guess == null) {
            if (quiet) {
                // Конец проверки — строкой в журнал: 14.10 «смотрю на экран три
                // минуты» и дальше ничего читалось как поломка, а телефон просто
                // отложили до третьего снимка.
                val ended = "проверка с ${PhoneSit.hm(s.watchFrom)} кончилась до вопроса — " +
                    "телефон отложен в ${PhoneSit.hm(v.lastUse)}, снимков ${s.snaps} из $ASK_SNAPS"
                return when (s.base) {
                    Base.IDLE -> {
                        val sit = v.sitting
                        val behind = sit != null && sit.useMs >= minMs
                        Step(
                            State(handled = maxOf(s.handled, v.lastUse)),
                            listOf(Note(ended + if (behind) "; сидение — Сонету задним числом" else "; короче порога — не режу")) +
                                (if (behind) listOf(Behind(s.watchFrom, v.lastUse)) else emptyList()),
                        )
                    }
                    Base.OFF -> Step(State(handled = maxOf(s.handled, v.lastUse)), listOf(Note(ended)))
                    // Подтверждённое дело закроется по погасанию экрана или пяти минутам без телефона.
                    Base.ON -> Step(s.unwatched(), listOf(Note("«${s.title}»: $ended")))
                }
            }
            if (!s.thinking && s.snaps >= ASK_SNAPS) {
                // Третий снимок — снова подтверждённое (глянул в другой чат и
                // вернулся): Сонет сказал бы «то же» — не зовём его вовсе.
                val back = s.base == Base.ON && s.marks.isNotEmpty() && s.lastMark?.let { confirms(s, it) } == true
                if (back) {
                    return Step(
                        s.unwatched().copy(seenAt = now, confirmAt = now + CONFIRM_MS),
                        listOf(Note("«${s.title}»: впереди снова своё — Сонета не зову")),
                    )
                }
                return Step(s.copy(thinking = true, thinkingAt = now), listOf(Classify(s.watchFrom, now)))
            }
            if (!s.thinking && s.watchAt > 0L && now - s.watchAt >= STALE_MS) {
                // Телефон в руках, а минуты экрана не приходят — снимков не будет.
                return Step(
                    s.copy(thinking = true, thinkingAt = now),
                    listOf(
                        Note(
                            "проверка с ${PhoneSit.hm(s.watchFrom)} идёт ${(now - s.watchAt) / 60_000L} мин, а снимков " +
                                "${s.snaps} из $ASK_SNAPS — служба на экран не смотрит; Сонету по приложениям"
                        ),
                        Classify(s.watchFrom, now),
                    ),
                )
            }
            return Step(s)
        }
        if (s.watching) return Step(s)
        return when (s.base) {
            Base.OFF -> {
                val sit = v.sitting
                when {
                    quiet -> Step(State(handled = maxOf(s.handled, v.lastUse)))
                    // Слово владельца (охрана ленты) — про своё время: после него
                    // снова сидение от порога — это уже новое сидение.
                    s.freshFrom > 0L && sit != null && sit.useMs >= minMs -> Step(
                        watch(s, sit.start, now),
                        listOf(
                            Note(
                                "после ${PhoneSit.hm(s.freshFrom)} снова ${PhoneSit.minutes(sit.useMs)} в телефоне — " +
                                    "новое сидение, смотрю на экран"
                            )
                        ),
                    )
                    else -> switched(s, v, now)
                }
            }
            Base.ON -> switched(s, v, now)
            Base.IDLE -> {
                val sit = v.sitting ?: return Step(s)
                when {
                    // Кончилось, а пульсы его не видели (служба спала): по-старому, задним числом.
                    sit.finished -> Step(
                        s.copy(handled = maxOf(s.handled, sit.end)),
                        if (sit.useMs >= minMs) listOf(Behind(sit.start, sit.end)) else emptyList(),
                    )
                    sit.useMs >= minMs -> Step(watch(s, sit.start, now))
                    else -> Step(s)
                }
            }
        }
    }

    private fun switched(s: State, v: View, now: Long): Step =
        if (v.outsideSince > 0L && now - v.outsideSince >= SWITCH_MS) {
            Step(watch(s, v.outsideSince, now))
        } else {
            Step(s)
        }

    /**
     * Время занято (сон, встреча, дорога, тренировка, факт робота) или
     * владелец сам сказал в «З» про это время — на экран не смотрим, молчим о
     * тех же приложениях. Но охрана — про своё время, до [now]: сидение от
     * порога после него — новая проверка ([State.freshFrom]). Стоящее дело из
     * телефона ведётся дальше — у него своя сверка.
     */
    fun guarded(s: State, apps: Set<String>, now: Long): State = when (s.base) {
        Base.IDLE -> s.unwatched().copy(base = Base.OFF, apps = apps, freshFrom = now)
        Base.OFF -> s.unwatched().copy(apps = s.apps + apps, freshFrom = now)
        Base.ON -> s.unwatched().copy(apps = s.apps + apps)
    }

    /**
     * Сонет ответил про кусок: [guess] null — то же, что в ленте (молчим, пока
     * впереди эти приложения; у стоящего дела увиденное становится
     * подтверждённым), иначе — вопрос на плашку. Ответа уже не ждали (проверка
     * кончилась или Сонет думал дольше [THINK_MS]) — не спрашиваем: вопрос без
     * проверки за ним галочкой ничего бы не поставил.
     */
    fun classified(s: State, guess: Guess?, segApps: Set<String>, now: Long): Step {
        if (!s.watching || !s.thinking) {
            return Step(s.copy(thinking = false, thinkingAt = 0L), listOf(Note("ответ Сонета пришёл, когда его уже не ждали, — не спрашиваю")))
        }
        if (guess == null) {
            return Step(
                when (s.base) {
                    Base.IDLE -> s.unwatched().copy(base = Base.OFF, apps = segApps)
                    Base.OFF -> s.unwatched().copy(apps = s.apps + segApps)
                    Base.ON -> s.unwatched().copy(
                        apps = s.apps + segApps,
                        marks = withMark(s.marks, s.lastMark),
                        seenAt = now,
                        confirmAt = now + CONFIRM_MS,
                    )
                }
            )
        }
        return Step(s.copy(thinking = false, thinkingAt = 0L, guess = guess, askedAt = now, segApps = segApps))
    }

    /** Подтверждённые с новым: сверяли по приложениям (пусто) — так и остаётся. */
    private fun withMark(marks: Set<Mark>, m: Mark?): Set<Mark> {
        if (marks.isEmpty() || m == null || m in marks) return marks
        return (marks.toList() + m).takeLast(MARKS_MAX).toSet()
    }

    /**
     * Сонет не ответил (сеть) или думал дольше [THINK_MS]: проверка остаётся,
     * снимки — с нуля, и часы [STALE_MS] — тоже с [now]: иначе часы проверки
     * давно вышли, и каждый пульс без сети звал бы Сонета снова.
     */
    fun failed(s: State, now: Long): State = s.copy(thinking = false, thinkingAt = 0L, snaps = 0, watchAt = now)

    /**
     * Ответ на «кажется, это …»: галочка (или молчание) — поставить дело,
     * крестик — то, что в ленте, верно: молчим, пока впереди эти приложения.
     * Крестик посреди дела из телефона — его дело и верно: по предмету больше
     * не сверяем (владелец о крестике: «перестаёт следить»), только приложения
     * и погасание экрана. [now] — миг ответа: после явного «да» погасание
     * считается с него, после молчания — с вопроса (выключил экран, не
     * ответив, — это и есть конец дела).
     */
    fun answer(s: State, yes: Boolean, silent: Boolean = false, now: Long = s.askedAt): Step {
        val g = s.guess ?: return Step(s)
        if (!yes) {
            return Step(
                when (s.base) {
                    Base.IDLE -> s.unwatched().copy(base = Base.OFF, apps = s.segApps)
                    Base.OFF -> s.unwatched().copy(apps = s.apps + s.segApps)
                    Base.ON -> s.unwatched().copy(apps = s.apps + s.segApps, marks = emptySet(), everyMinute = false, sameRun = 0)
                }
            )
        }
        val since = if (silent) s.askedAt else now
        return Step(s.unwatched(), listOf(Open(g, s.segApps, silent, since, s.lastMark)))
    }

    /**
     * Дело поставлено: на экран смотрим раз в пять минут одним снимком, пока
     * экран горит. Вернуть после — то, что шло до САМОГО первого дела этого
     * сидения: «Завтрак → Клод → Ютуб» после ютуба возвращает завтрак.
     * [mark] — что было впереди при ответе: первое подтверждённое (нет — сверяем
     * по приложениям).
     */
    fun opened(
        s: State,
        entryId: Long,
        title: String,
        apps: Set<String>,
        resumeId: Long,
        since: Long = 0L,
        mark: Mark? = null,
        now: Long = since,
    ): State =
        State(
            base = Base.ON,
            apps = apps,
            entryId = entryId,
            title = title,
            resumeId = if (s.base == Base.ON) s.resumeId else resumeId,
            since = since,
            marks = setOfNotNull(mark),
            confirmAt = now + CONFIRM_MS,
            seenAt = now,
            handled = s.handled,
        )

    /**
     * Владелец сам сказал в «З» (или убрал запись) посреди дела из телефона:
     * его слово сильнее — дело больше не ведём и ничего не возвращаем, о тех
     * же приложениях молчим. Слово — про время до [now]: сидит дальше от
     * порога — новая проверка ([State.freshFrom]).
     */
    fun ownerTook(s: State, now: Long): State = State(base = Base.OFF, apps = s.apps, handled = s.handled, freshFrom = now)

    /**
     * Что видно по статистике для этого состояния: последнее сидение после
     * [State.countFrom] (обрезанное по нему), конец последнего куска, с какого
     * мига впереди не приложения занятия и погасания экрана. [spans] — куски
     * без мебели после разобранного, [events] — те же события статистики.
     */
    fun view(s: State, events: List<PhoneSit.Ev>, spans: List<PhoneSit.Span>, now: Long): View {
        val from = s.countFrom()
        val sittings = PhoneSit.stretches(spans.filter { it.end > from }, now)
            .map { if (it.start < from) it.clip(from, it.end) else it }
            .filter { it.spans.isNotEmpty() }
        return View(
            sitting = sittings.lastOrNull(),
            lastUse = spans.maxOfOrNull { it.end } ?: 0L,
            outsideSince = outsideSince(spans, s.apps, now),
            offs = merge(offs(events), spans),
        )
    }

    /**
     * Удар движка: минута экрана ([minute]; [seen] — что впереди, снимок уже
     * взят, если был нужен), потом пульс по статистике. Минута — только
     * строки журнала и сверка дела, поэтому их шаги идут раньше шагов пульса.
     * Здесь, а не в движке, чтобы тест гнал тот же путь, что телефон.
     */
    fun beat(
        s: State,
        events: List<PhoneSit.Ev>,
        spans: List<PhoneSit.Span>,
        seen: Mark?,
        minute: Boolean,
        now: Long,
        minMs: Long,
    ): Step {
        val m = if (minute) minute(s, seen, now) else Step(s)
        val p = pulse(m.state, view(m.state, events, spans, now), now, minMs)
        return Step(p.state, m.dos + p.dos)
    }

    // ------------------------------------------------------------ файл

    /**
     * Решённое — в `phone-sit.json` (без журнала решений: его пишет движок).
     * Проверка, снимки, вопрос и «думает» на диск не ложатся: после
     * перезапуска (обновление посреди сидения) сидение заново дотягивает до
     * порога и проверяется с начала. Текста экрана здесь нет — предмет хэшем.
     */
    fun toJson(s: State): org.json.JSONObject =
        org.json.JSONObject()
            .put("base", s.base.name)
            .put("apps", org.json.JSONArray(s.apps.toList()))
            .put("entry", s.entryId)
            .put("title", s.title)
            .put("resume", s.resumeId)
            .put("since", s.since)
            .put(
                "marks",
                org.json.JSONArray().apply {
                    s.marks.forEach { m ->
                        put(org.json.JSONObject().put("pkg", m.pkg).apply { m.subject?.let { put("s", it) } })
                    }
                },
            )
            .put("lit", s.litAt)
            .put("fresh", s.freshFrom)
            .put("handled", s.handled)

    /**
     * Состояние из файла любой сборки: второй заход (до 14.10) не знал
     * погасаний, предмета и отсрочки слова — погасания считаем с загрузки,
     * сверка по приложениям. [now] — миг загрузки.
     */
    fun fromJson(o: org.json.JSONObject, now: Long): State {
        val appsArr = o.optJSONArray("apps") ?: org.json.JSONArray()
        val marksArr = o.optJSONArray("marks") ?: org.json.JSONArray()
        val base = runCatching { Base.valueOf(o.optString("base", "IDLE")) }.getOrDefault(Base.IDLE)
        return State(
            base = base,
            apps = (0 until appsArr.length()).map { appsArr.optString(it) }.filter { it.isNotBlank() }.toSet(),
            entryId = o.optLong("entry", 0L),
            title = o.optString("title"),
            resumeId = o.optLong("resume", 0L),
            since = o.optLong("since", if (base == Base.ON) now else 0L),
            marks = (0 until marksArr.length()).mapNotNull { i ->
                marksArr.optJSONObject(i)?.let { m ->
                    val pkg = m.optString("pkg")
                    if (pkg.isBlank()) null else Mark(pkg, if (m.has("s")) m.optInt("s") else null)
                }
            }.toSet(),
            litAt = o.optLong("lit", 0L),
            // Своё видели при загрузке: другое на ближайшей сверке смотрится с неё, а не с «да» часы назад.
            seenAt = if (base == Base.ON) now else 0L,
            // Первый заход хранил «разобрано до» полем done.
            handled = o.optLong("handled", o.optLong("done", 0L)),
            // Файл сборки до 14.10 (вечер) отсрочки не знал: молчание оттуда не
            // переносим навечно — сидение от порога после загрузки проверяется.
            freshFrom = if (base == Base.OFF) o.optLong("fresh", now) else 0L,
        )
    }

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
