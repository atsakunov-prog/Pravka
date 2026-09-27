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
     * Владелец сказал что-то в десять минут до начала встречи — это и есть
     * встреча («созвон с Ильёй» в 13:58 к событию 14:00). Не дублируем.
     */
    const val OWNER_GRACE_MS = 10 * 60_000L

    /** Конец встречи — с пятиминутным запасом: «ещё две минуты, дожму». */
    const val END_GRACE_MS = 5 * 60_000L

    /** Заводская категория встреч из календаря: созвоны по клиентам. */
    const val DEFAULT_CATEGORY = "Работа: звонки"

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

    enum class Start {
        /** Начать встречу с момента начала события. */
        START,
        /** Ещё не началась. */
        WAIT,
        /** Не начинать: поздно, владелец уже сказал своё, он в дороге. */
        SKIP,
    }

    /**
     * Начинать ли встречу [e] в [now]. [latestOwnerStart] — начало последней
     * записи владельца в ленте: сказал что-то с [OWNER_GRACE_MS] до начала
     * события или позже — он в курсе, встреча не дублируется. Открытая дорога
     * ([openTitle]/[openCategory]) — молчим: созвон из машины остаётся
     * поездкой, пока владелец не скажет иначе.
     */
    fun startVerdict(
        e: CalEvent,
        now: Long,
        openTitle: String?,
        openCategory: String?,
        latestOwnerStart: Long,
    ): Start {
        if (now < e.start) return Start.WAIT
        if (now - e.start > LATE_MS) return Start.SKIP
        if (latestOwnerStart >= e.start - OWNER_GRACE_MS) return Start.SKIP
        if (openTitle != null && AutoPilotRules.travelish(openTitle, openCategory.orEmpty())) return Start.SKIP
        return Start.START
    }

    enum class End {
        /** Закрыть встречу концом события и вернуть прежнее дело. */
        CLOSE,
        /** Событие ещё идёт (с запасом). */
        WAIT,
        /** В ленте уже не встреча — владелец сам всё сказал. */
        NONE,
    }

    /**
     * Закрывать ли встречу, кончающуюся в [eventEnd], в [now]: только если
     * открыта именно она — та же запись, что автопилот начал ([openId] ==
     * [startedId]). Владелец переключил дело сам — его слово.
     */
    fun endVerdict(eventEnd: Long, now: Long, openId: Long?, startedId: Long): End {
        if (now < eventEnd + END_GRACE_MS) return End.WAIT
        if (openId == null || startedId <= 0L || openId != startedId) return End.NONE
        return End.CLOSE
    }

    /** Название записи — как в календаре, без хвостов. */
    fun entryTitle(e: CalEvent): String = e.title.trim().take(80)

    /**
     * Дело, к которому вернуться после встречи: то, что кончилось ровно там,
     * где встреча началась (её и закрыл автопилот). Не заполнитель, не
     * авто-факт и не сама встреча.
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
        if (beforeSource == "auto" || beforeSource == "gap") return false
        if (beforeCategory.equals("Не размечено", ignoreCase = true)) return false
        if (beforeCategory.equals("Потери", ignoreCase = true)) return false
        return !beforeTitle.equals(meetingTitle, ignoreCase = true)
    }
}
