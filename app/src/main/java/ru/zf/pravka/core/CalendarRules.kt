package ru.zf.pravka.core

/**
 * Событие календаря телефона, как его видит автопилот. Без Android: сюда
 * приходит уже прочитанное из `CalendarContract`.
 */
data class CalEvent(
    /** Экземпляр события (у повторяющихся — свой на каждый день). */
    val id: Long,
    val eventId: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    /** Владелец отклонил приглашение. */
    val declined: Boolean,
    /** Событие «свободен» — напоминание, не занятость. */
    val free: Boolean,
    /** Имя календаря для глаз («a.tsakunov@…», «Семья»). */
    val calendar: String,
    /** Основной календарь аккаунта — единственный, что смотрится с завода. */
    val primary: Boolean,
) {
    /** Ключ «это событие в этот день» — по нему помнится, что уже начато. */
    val key: String get() = "$eventId@$start"
}

/**
 * Запись ленты глазами автопилота календаря: только то, по чему он решает.
 * [source] — «calendar» у записей, которые завёл он сам.
 */
data class CalSeen(
    val id: Long,
    val start: Long,
    val title: String,
    val category: String,
    val client: String = "",
    val source: String = "voice",
)

/** Проект Дел для сопоставления с названием встречи: имя и алиасы. */
data class CalProject(val id: String, val name: String, val aliases: List<String> = emptyList())

/**
 * Встречи из календаря — в ленту как работа. Владелец (27.09.2026): «надо
 * брать мой календарь и ставить встречи как „работу“». Решения — здесь, без
 * Android, под JVM-тестами; читает календарь и пишет в ленту
 * `trigger/CalendarPilot.kt`.
 *
 * Правило одно, как у машины и у дела места по приезду: встреча по календарю
 * НАЧИНАЕТСЯ САМА с момента начала события и закрывает текущее дело; по концу
 * события закрывается и возвращает то, что шло до неё, — встреча посреди
 * работы, как туалет по метке, — перерыв, а не конец работы. Ошибка чинится
 * кнопками пуша, не вопросом заранее.
 *
 * Второй заход (05.10.2026; владелец: «если я сам уже написал… там с ПТИЦ, а у
 * меня идёт встреча ПТИЦ, то переписывать это не надо. И когда встреча
 * закончилась, то я могу сам её выключить. Либо, если я забыл переключиться,
 * то он пишет „закончилась“»). Встреча узнаёт СЕБЯ в словах владельца — по
 * названию и клиенту, а не только по часам ([sameMeeting]): сказанное
 * «Встречи в Птиц» — это и есть «ПТИЦ - ЗФ (регулярный синк)», второй записи
 * нет, а по концу события закрывается уже запись владельца ([Start.ADOPT]).
 * Сказал то же самое уже ПОСЛЕ того, как встреча началась сама, — его слова
 * забирают её начало ([absorbs]). БЖЖ в календаре — не созвон, а спорт с тем
 * же именем, что у часов ([Kind.SPORT]): три слоя (сказал, календарь, Garmin)
 * сходятся в одну запись.
 */
object CalendarRules {

    /** Длиннее шести часов — не встреча, а конференция или «занят весь день». */
    const val MAX_MEETING_MS = 6 * 3_600_000L

    /**
     * Событие, начавшееся больше получаса назад, задним числом не подхватываем:
     * служба спала — лента с тех пор жила своей жизнью.
     */
    const val LATE_MS = 30 * 60_000L

    /**
     * Владелец сказал что-то в десять минут до начала встречи — он в курсе,
     * что делает («созвон с Ильёй» в 13:58 к событию 14:00). Не дублируем.
     */
    const val OWNER_GRACE_MS = 10 * 60_000L

    /**
     * Своё про эту же встречу владелец сказал не раньше чем за 45 минут до
     * начала — это и есть встреча (выехал, приехал, сел ждать): по концу
     * события её закрывает автопилот. Раньше — это его собственный блок работы
     * про того же клиента («ПТИЦ: управленка» с девяти утра), встреча внутри
     * него не пишется вовсе.
     */
    const val ADOPT_BEFORE_MS = 45 * 60_000L

    /** Конец встречи — с пятиминутным запасом: «ещё две минуты, дожму». */
    const val END_GRACE_MS = 5 * 60_000L

    /** Заводская категория встреч из календаря: созвоны по клиентам. */
    const val DEFAULT_CATEGORY = "Работа: звонки"

    /** Источник записей, которые завёл автопилот календаря. */
    const val SOURCE = "calendar"

    /**
     * Смотрим ли это событие вообще. [watched] — имена выбранных календарей;
     * null — не выбирали, тогда только основной календарь аккаунта: семейный
     * с «Боря — бассейн» работой быть не должен. Пустой набор — выключили
     * все, ничего не смотрим.
     */
    fun eligible(e: CalEvent, watched: Set<String>?): Boolean {
        if (e.allDay || e.declined || e.free) return false
        if (e.title.isBlank()) return false
        if (e.end <= e.start || e.end - e.start > MAX_MEETING_MS) return false
        return if (watched == null) e.primary else e.calendar in watched
    }

    /** Что за событие: встреча или занятие спортом со своим именем и категорией. */
    enum class Kind { MEETING, SPORT }

    /** «БЖЖ» в календаре (каждый пн, ср, чт) — BJJ, а не созвон. */
    fun kind(e: CalEvent): Kind = if (IcuFixes.saysBjj(e.title)) Kind.SPORT else Kind.MEETING

    enum class Start {
        /** Начать встречу с момента начала события. */
        START,
        /**
         * Встреча уже в ленте словами владельца — новой записи нет, по концу
         * события закрывается его запись.
         */
        ADOPT,
        /** Ещё не началась. */
        WAIT,
        /** Не начинать: поздно, владелец уже сказал своё, он в дороге. */
        SKIP,
    }

    /**
     * Начинать ли встречу [e] в [now]. [open] — что идёт в ленте;
     * [latestOwnerStart] — начало последней записи владельца, не считая
     * записей самого автопилота календаря: сказал что-то с [OWNER_GRACE_MS]
     * до начала события или позже — он в курсе, встреча не дублируется.
     * Открытая дорога — созвон из машины остаётся поездкой, пока владелец не
     * скажет иначе; к тренировке же владелец доехал, и БЖЖ начинается.
     */
    fun startVerdict(e: CalEvent, now: Long, open: CalSeen?, latestOwnerStart: Long): Start {
        if (now < e.start) return Start.WAIT
        if (now - e.start > LATE_MS) return Start.SKIP
        val travel = open != null && AutoPilotRules.travelish(open.title, open.category)
        if (open != null && !travel && sameMeeting(e, open)) {
            return if (open.start >= e.start - ADOPT_BEFORE_MS) Start.ADOPT else Start.SKIP
        }
        if (travel && kind(e) == Kind.MEETING) return Start.SKIP
        if (latestOwnerStart >= e.start - OWNER_GRACE_MS) return Start.SKIP
        return Start.START
    }

    /** Почему встреча не началась — словами в журнал. */
    fun skipWhy(e: CalEvent, now: Long, open: CalSeen?): String = when {
        now - e.start > LATE_MS -> "началась давно"
        open != null && !AutoPilotRules.travelish(open.title, open.category) && sameMeeting(e, open) ->
            "она уже в ленте твоим делом «${open.title}» с давних пор"
        open != null && AutoPilotRules.travelish(open.title, open.category) -> "в дороге"
        else -> "ты уже сказал, что делаешь («${open?.title ?: "—"}»)"
    }

    enum class End {
        /** Закрыть встречу концом события. */
        CLOSE,
        /** Событие ещё идёт (с запасом). */
        WAIT,
        /** В ленте уже не встреча — владелец сам всё сказал. */
        NONE,
    }

    /**
     * Закрывать ли встречу [e], кончающуюся в [eventEnd], в [now]. Открыта
     * та запись, что автопилот начал или узнал ([meetingId]), — закрываем.
     * Открыто другое, но это та же встреча словами владельца, сказанная после
     * её начала (или хвост той же записи, разрезанной тренировкой с часов), —
     * тоже: «забыл переключиться — пишет „закончилась“». Владелец сам перешёл
     * к другому делу — его слово, конец не трогаем.
     */
    fun endVerdict(e: CalEvent, eventEnd: Long, now: Long, open: CalSeen?, meetingId: Long): End {
        if (now < eventEnd + END_GRACE_MS) return End.WAIT
        if (open == null) return End.NONE
        // Событие перенесли раньше начала записи — закрыть её значило бы стереть.
        if (open.start >= eventEnd) return End.NONE
        if (meetingId > 0L && open.id == meetingId) return End.CLOSE
        if (AutoPilotRules.travelish(open.title, open.category)) return End.NONE
        if (open.start in (e.start - ADOPT_BEFORE_MS) until eventEnd && sameMeeting(e, open)) return End.CLOSE
        return End.NONE
    }

    /**
     * Встреча началась сама ([guess] — её запись, закрытая следующей), а
     * владелец сказал ТО ЖЕ САМОЕ своими словами ([said], начатая там, где
     * кончилась догадка): его запись забирает начало встречи, догадка
     * уходит. Догадку, которую владелец правил (имя другое), не трогаем.
     */
    fun absorbs(e: CalEvent, guess: CalSeen, guessEnd: Long, said: CalSeen, eventEnd: Long): Boolean {
        if (guess.source != SOURCE || said.source == SOURCE) return false
        if (guess.title != entryTitle(e)) return false
        if (kotlin.math.abs(guessEnd - said.start) >= 60_000L) return false
        if (said.start >= eventEnd) return false
        return sameMeeting(e, said)
    }

    /**
     * Про эту ли встречу запись: общее значимое слово в названии встречи и в
     * названии или клиенте записи («Встречи в Птиц» — «ПТИЦ - ЗФ (регулярный
     * синк)», «Звонок с Додо Пиццей» — «(ДоДо — Знакомый Финансист)…»,
     * «Разговор с Рубриком» — «Рубрик - ЗФ Kick-off»). У БЖЖ — любое слово
     * борьбы («Занятие борьбой», «бжж»). Встреча из одних общих слов
     * («Встреча», «Созвон») себя ни в чём не узнаёт.
     */
    fun sameMeeting(e: CalEvent, s: CalSeen): Boolean {
        val text = s.title + " " + s.client
        if (kind(e) == Kind.SPORT) {
            return IcuFixes.saysBjj(text) || s.category.equals(IcuFixes.BJJ_CATEGORY, ignoreCase = true)
        }
        val mine = words(text)
        if (mine.isEmpty()) return false
        return words(e.title).any { w -> mine.any { stemEq(w, it) } }
    }

    /** Название записи: как в календаре, без своей стороны; у БЖЖ — имя часов. */
    fun entryTitle(e: CalEvent): String =
        if (kind(e) == Kind.SPORT) IcuFixes.BJJ_NAME else cleanTitle(e.title)

    /** Категория записи: у спорта — спорт, у встречи — выбор владельца. */
    fun entryCategory(e: CalEvent, meetingCategory: String): String =
        if (kind(e) == Kind.SPORT) IcuFixes.BJJ_CATEGORY else meetingCategory

    /**
     * Своя сторона во встречах владельца: фирма и он сам. В ленте она лишняя —
     * лента и так его («(ДоДо — Знакомый Финансист) Регулярная по сделке.» →
     * «ДоДо: Регулярная по сделке»).
     */
    private val OWN_SIDE = setOf("знакомый финансист", "зф", "александр цакунов", "цакунов", "саша цакунов")

    private fun ownSide(s: String): Boolean = s.trim().lowercase().replace('ё', 'е') in OWN_SIDE

    /**
     * Название встречи для ленты: подчёркивания — пробелы, «(Мы — Они) Тема» —
     * «Они: Тема», «ПТИЦ - ЗФ (синк)» — «ПТИЦ (синк)», точка в конце — прочь.
     * Ничего не осталось — исходное как есть.
     */
    fun cleanTitle(raw: String): String {
        var t = raw.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val m = Regex("^\\(([^)]*)\\)\\s*(.*)$").find(t)
        if (m != null) {
            val parties = m.groupValues[1].split(Regex("\\s+[—–-]\\s+|\\s*,\\s*"))
                .map { it.trim() }
                .filter { it.isNotBlank() && !ownSide(it) }
            val rest = m.groupValues[2].trim()
            t = when {
                parties.isEmpty() -> rest
                rest.isEmpty() -> parties.joinToString(", ")
                else -> parties.joinToString(", ") + ": " + rest
            }
        }
        t = t.replace(Regex("\\s+[—–-]\\s*(ЗФ|Знакомый Финансист)(?=[\\s(]|$)", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(ЗФ|Знакомый Финансист)\\s*[—–-]\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ', '-', '—', ':').trim()
        return t.ifBlank { raw.trim() }.take(80)
    }

    /**
     * Проект Дел, о котором встреча: имя или алиас проекта целиком среди слов
     * названия («Встреча_ДоДо_Экспо» — Додо, «ПТИЦ - ЗФ» — ПТИЦ). Два разных
     * проекта — не угадываем: клиент встречи без клиента лучше чужого.
     */
    fun projectOf(title: String, projects: List<CalProject>): CalProject? {
        val have = rawWords(title)
        if (have.isEmpty()) return null
        val hits = projects.filter { p ->
            (listOf(p.name) + p.aliases).any { name ->
                val need = rawWords(name)
                need.isNotEmpty() && need.all { w -> have.any { stemEq(w, it) } }
            }
        }
        return hits.singleOrNull()
    }

    /**
     * Дело, к которому вернуться после встречи: то, что кончилось ровно там,
     * где встреча началась (её и закрыл автопилот). Не заполнитель, не
     * авто-факт, не дорога (приехал — уже не едешь) и не сама встреча.
     */
    fun resumable(
        beforeTitle: String,
        beforeCategory: String,
        beforeSource: String,
        beforeEnd: Long,
        meetingStart: Long,
        meetingTitle: String,
    ): Boolean {
        if (kotlin.math.abs(beforeEnd - meetingStart) >= 60_000L) return false
        if (beforeSource == "auto" || beforeSource == "gap" || beforeSource == SOURCE) return false
        if (beforeCategory.equals("Не размечено", ignoreCase = true)) return false
        if (beforeCategory.equals("Потери", ignoreCase = true)) return false
        if (AutoPilotRules.travelish(beforeTitle, beforeCategory)) return false
        return !beforeTitle.equals(meetingTitle, ignoreCase = true)
    }

    // ---- слова ----

    /**
     * Слова, по которым встреча узнаёт себя: без общих слов встреч и своей
     * стороны — по «встреча», «созвон» или «ЗФ» совпало бы всё со всем.
     */
    private val NOISE = setOf(
        "встреча", "встречи", "встречу", "встречей", "звонок", "звонки", "звонка", "созвон", "созвона",
        "разговор", "переговоры", "синк", "регулярный", "регулярная", "регулярные", "регулярно", "сделке",
        "сделка", "обсуждение", "обсудить", "обсудим", "знакомство", "активы", "статус", "итоги", "проекта",
        "проект", "работа", "работе", "модель", "задачи", "план", "вдвоем", "позвонить", "kick", "off",
        "call", "meeting", "sync", "zoom", "зум", "поездка", "дорога", "занятие",
        "знакомый", "финансист", "цакунов", "александр", "для", "или", "про",
    )

    /** Значимые слова: от трёх знаков, без шума, ё как е. */
    fun words(text: String): Set<String> = rawWords(text).filter { it !in NOISE }.toSet()

    private fun rawWords(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase().replace('ё', 'е'))
            .map { it.value }
            // Двухбуквенные («ЗФ», «SR», «ДМ») совпадали бы со всем подряд.
            .filter { it.length >= 3 }
            .toList()

    /**
     * Одно слово в разных падежах: короткое (до четырёх знаков — «птиц»,
     * «додо», «бжж») — началом длинного целиком; длиннее — общим началом без
     * двух последних знаков, но не короче четырёх («Ольга» — «Ольгой»,
     * «Рубрик» — «Рубриком»; «семья» и «семинар» — разное).
     */
    fun stemEq(a: String, b: String): Boolean {
        val n = minOf(a.length, b.length)
        if (n < 2) return false
        var c = 0
        while (c < n && a[c] == b[c]) c++
        return if (n <= 4) c == n else c >= 4 && c >= n - 2
    }
}
