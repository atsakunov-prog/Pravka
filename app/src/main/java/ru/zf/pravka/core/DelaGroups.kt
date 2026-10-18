package ru.zf.pravka.core

import java.text.Collator
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela.str

/**
 * Три группы, «Сегодня», уровни группировки и фильтры (10.10.2026,
 * docs/dela-phone-11.md). Владелец: «есть дела, которые ждут меня — люди что-то
 * спросили и ждут, они мега важные… второе — что я сам придумал… третье —
 * проверить, что мне люди должны… чёткий список на каждый день». Вечером назвал
 * группы — «Отбить», «Запустить», «Мониторить» — и попросил уровни: «первый
 * уровень группировки — проекты, второй — по категориям, третий — по времени».
 *
 * Эталон — веб (`server/pravka_dela/static/app.js`: `GRP`, `grpOf`, `grpSet`,
 * `inToday`, `whenDay`, `byTime`, `toggleNow`, `LEVELS`, `levelKey`,
 * `nested`, `branches`, `FILTERS_DEF`) и правила 17–18 сервера. Одно правило в
 * четырёх местах (сервер, вид базы, веб, телефон): поправил веб — поправь здесь
 * и тест `DelaGroupsTest`.
 */
object DelaGroups {

    // ------------------------------------------------------------ группы

    /** Группа дела; порядок — приоритет, везде этот. */
    enum class Grp(val key: String, val title: String, val sub: String) {
        OWED("owed", "Отбить", "люди ждут ответа или дела — первым делом"),
        SELF("self", "Запустить", "сам придумал"),
        CHECK("check", "Мониторить", "что должны мне — проверить, напомнить");

        companion object {
            fun of(key: String): Grp? = entries.firstOrNull { it.key == key }
        }
    }

    /** `grpOf` веба: «отбить» — флаг (или мяч «owed» из автоматики), «мониторить» — мяч у человека, остальное — «запустить». */
    fun grpOf(t: Dela.Task): Grp = when {
        t.owed || t.ball == Dela.OWED -> Grp.OWED
        t.ball == Dela.WAITING -> Grp.CHECK
        else -> Grp.SELF
    }

    /**
     * Дело в группе [g] (`grpSet` веба): повестка, ставшая «Запустить», остаётся
     * повесткой — это тоже «Запустить». Мяч ушёл к человеку — «отбить» гаснет.
     */
    fun withGrp(t: Dela.Task, g: Grp): Dela.Task = when (g) {
        Grp.OWED -> t.copy(ball = Dela.MINE, owed = true)
        Grp.CHECK -> t.copy(ball = Dela.WAITING, owed = false)
        Grp.SELF -> t.copy(ball = if (t.ball == Dela.AGENDA) Dela.AGENDA else Dela.MINE, owed = false)
    }

    /**
     * «В повестку» карточки (16.10.2026, запись отладки 19; правило 32 карты Дел, контракт dela.json «Повестка»): та же
     * группа «Запустить», мяч `agenda` — обсудить с человеком при встрече или звонке; ещё раз — обратно в своё (`mine`).
     * Повестки без человека не бывает: человек не выбран — карточка сперва открывает выбор человека.
     */
    fun toggleAgenda(t: Dela.Task): Dela.Task =
        if (t.ball == Dela.AGENDA) t.copy(ball = Dela.MINE) else t.copy(ball = Dela.AGENDA, owed = false)

    /** Группа и человек одной строкой (`grpWho` веба): «повестка · Иван», «мониторить Иван». */
    fun withWho(word: String, ball: String, who: String): String =
        word + if (who.isBlank()) "" else (if (ball == Dela.AGENDA) " · " else " ") + who

    /** Поля операции для группы — `grpSet` веба: `{ball, owed}`. */
    fun grpSet(t: Dela.Task, g: Grp): JSONObject = withGrp(t, g).let { JSONObject().put("ball", it.ball).put("owed", it.owed) }

    // ------------------------------------------------------------ «Сейчас», «Сегодня», просрочено

    /*
     * 13.10.2026 (этап 4 «Правки 5.0», docs/dela-phone-19.md), владелец: «В делах давай разделим: Сейчас (то,
     * что отметил) и Сегодня (с датой сегодня)»; порядок на экране — Сейчас → Новое → Сегодня; просроченное
     * само ни туда, ни туда не попадает — оно во «Всех делах», сверху. С 10.10 «Сегодня» было котлом (срок
     * сегодня или прошёл, «Сейчас», «отбить» без срока, «мониторить» с напоминанием) — в нём тонуло то, что
     * владелец сам взял на день. Три правила, одни на сервер (`store.NOW_IF`, `TODAY_IF`, `LATE_IF`), веб
     * (`isNow`, `inToday`, `isLate`) и телефон — здесь, под `DelaGroupsTest`.
     */

    private fun mine(me: String, t: Dela.Task) = me.isBlank() || t.ownerId == me

    /**
     * Своё открытое дело, не цель: цель не делают, к ней идут (дерево жизни) — её нет ни в одном списке дня. И не
     * напоминание (захват, 15.10.2026): оно стоит на «Сегодня» в свой час ([remindersToday]), а не в куче дел.
     */
    private fun deed(t: Dela.Task, me: String) = t.open && t.deed && mine(me, t)

    /**
     * Молния «Сейчас» у дела есть (строка, карточка): своё открытое, не цель — как `isMine(t) && !t.goal` веба.
     * У чужого дела (страница клиента) и у цели её нет: нажать — только тост «нельзя», а кнопка, которая
     * всегда отказывает, читается как поломка. Условие — ровно то, по которому [toggleNow] берёт дело.
     */
    fun canNow(t: Dela.Task, me: String): Boolean = deed(t, me)

    /** «Сейчас» (`isNow` веба, `store.NOW_IF`): владелец сам отметил дело на сегодня. Срок ни при чём. */
    fun isNow(t: Dela.Task, today: String): Boolean = t.focusOn == today

    /**
     * На сегодня (`inToday` веба, `store.TODAY_IF`): своё открытое дело со сроком ровно сегодня. Не сделал —
     * завтра его здесь нет: оно во «Всех делах», в «Просрочено». «Сейчас» без срока, «отбить» без срока и
     * «мониторить» с напоминанием сюда сами не встают.
     */
    fun inToday(t: Dela.Task, me: String, today: String): Boolean = deed(t, me) && t.dueDate == today

    /** Просрочено (`isLate` веба, `store.LATE_IF`): своё открытое, срок прошёл. Живёт во «Всех делах», сверху. */
    fun inLate(t: Dela.Task, me: String, today: String): Boolean = deed(t, me) && t.dueDate.isNotBlank() && t.dueDate < today

    /** Дело дня — в «Сейчас» или со сроком сегодня: то, за что браться сегодня (Засечка, день на «Сегодня»). */
    fun inDay(t: Dela.Task, me: String, today: String): Boolean = deed(t, me) && (isNow(t, today) || t.dueDate == today)

    /** «Сегодня» — срок ровно сегодня, в сфере. */
    fun todayTasks(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> =
        s.tasks.values.filter { inToday(it, me, today) && Dela.inSphere(it, sphere) }

    /** «Сейчас» по времени (`nowTasks().sort(byTime)` веба): пять на день во всех сферах сразу, а не на сферу. */
    fun nowOrdered(s: Dela.Snapshot, me: String, today: String): List<Dela.Task> =
        s.tasks.values.filter { deed(it, me) && isNow(it, today) }.sortedWith(byTime(today))

    /** Просроченное по времени (`lateTasks` веба): число у «Всех дел», «просрочено N» в шапке, группа сверху. */
    fun lateTasks(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> =
        s.tasks.values.filter { inLate(it, me, today) && Dela.inSphere(it, sphere) }.sortedWith(byTime(today))

    /**
     * Когда дело делать (`whenDay` веба) — для уровня «время» и порядка: срок; «Сейчас» — сегодня (его взяли
     * на сегодня); «мониторить» — срок или день напоминания, что раньше. «Отбить» без срока с 13.10.2026 —
     * без срока: само оно больше не «сегодня». Сделанное и без срока — null.
     */
    fun whenDay(t: Dela.Task, today: String): String? {
        if (!t.open) return null
        return listOfNotNull(
            t.dueDate.ifBlank { null },
            today.takeIf { isNow(t, today) },
            t.nudgeOn.ifBlank { null }?.takeIf { t.ball == Dela.WAITING },
        ).minOrNull()
    }

    /**
     * По времени (`byTime` веба; владелец: «внутри уже сортировка по времени»):
     * когда делать, час, потом кто дольше ждёт (дело заведено раньше), потом номер.
     */
    fun byTime(today: String): Comparator<Dela.Task> =
        compareBy<Dela.Task>({ whenDay(it, today) ?: "9999" }, { it.dueTime.ifBlank { "99" } }, { it.createdAt }, { it.num })

    private fun byGroups(today: String): Comparator<Dela.Task> = compareBy<Dela.Task> { grpOf(it).ordinal }.then(byTime(today))

    /**
     * «Сегодня» одним списком в порядке групп — «отбить», «запустить», «мониторить» — без дел из [skip]
     * (на первом разделе — без уже взятого в «Сейчас»: оно там выше).
     */
    fun todayByGroups(s: Dela.Snapshot, me: String, today: String, sphere: String, skip: Set<String> = emptySet()): List<Dela.Task> =
        todayTasks(s, me, today, sphere).filter { it.id !in skip }.sortedWith(byGroups(today))

    /**
     * Дела дня одним списком: сначала «Сейчас» (что владелец взял сам), потом «Сегодня» по группам без
     * повторов. Для Засечки («взяться за дело»), дня на главном экране и правой половины разворота, пока
     * дело не выбрано. Просроченное сюда не входит — оно во «Всех делах».
     */
    fun dayOrdered(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> {
        val now = nowOrdered(s, me, today)
        return now + todayByGroups(s, me, today, sphere, now.map { it.id }.toSet())
    }

    /**
     * Старое имя (до 13.10.2026 «Сегодня» включало «Сейчас»): дела дня — [dayOrdered]. Его зовут день на
     * главном экране и Засечка; новое «Сегодня» без «Сейчас» — [todayByGroups].
     */
    fun todayOrdered(s: Dela.Snapshot, me: String, today: String, sphere: String): List<Dela.Task> = dayOrdered(s, me, today, sphere)

    /**
     * Быстрый ввод: «!отбить» (и старое «!ждут») — «Отбить», «!мониторить» (и
     * «!жду») — «Мониторить», «!запустить»; «!повестка» и «!сейчас» понимаются и
     * дальше, но в подсказке их нет. Метки вырезаются из названия.
     */
    data class Quick(val title: String, val grp: Grp? = null, val agenda: Boolean = false, val now: Boolean = false)

    fun quick(text: String): Quick {
        var q = Quick(text.trim())
        val words = text.trim().split(Regex("\\s+")).filter { w ->
            when (Dela.norm(w)) {
                "!отбить", "!ждут" -> { q = q.copy(grp = Grp.OWED); false }
                "!мониторить", "!жду" -> { q = q.copy(grp = Grp.CHECK); false }
                "!запустить" -> { q = q.copy(grp = Grp.SELF); false }
                "!повестка" -> { q = q.copy(agenda = true); false }
                "!сейчас" -> { q = q.copy(now = true); false }
                else -> true
            }
        }
        return q.copy(title = words.joinToString(" "))
    }

    /** Сделано сегодня (по Москве): «закрыто M» в шапке «Сейчас» и «Сегодня». */
    fun doneToday(s: Dela.Snapshot, me: String, today: String): Int =
        s.tasks.values.count { it.status == Dela.DONE && it.deed && mine(me, it) && it.completedAt.isNotBlank() && mskDay(it.completedAt) == today }

    // ------------------------------------------------------------ напоминания и «Вернулось» (захват, 15.10.2026)

    /*
     * Владелец 15.10.2026: «напоминание — отдельно от кучи дел: стоит на „Сегодня“ в своё время, приходит
     * уведомлением телефона… после срабатывания — „Сделано / Через час / Завтра“ и исчезает». Одно правило в трёх
     * местах: сервер (`capture.REMIND_TODAY_IF`, `BACK_IF`), веб (`isRemindToday`, `isBack` в app.js) и телефон — здесь,
     * под `DelaGroupsTest`.
     */

    /**
     * Напоминание на «Сегодня» (`isRemindToday` веба): открытое своё напоминание с мигом сегодня или вчера (вчерашнее
     * неотмеченное — «вчера 12:00 · не отмечено»), по месту без времени; плюс дело с напоминанием на сегодня, если его
     * срок не сегодня (иначе оно и так в «Сегодня»). Старше вчерашнего — во «Всех делах» группой «прошли — не отмечено»
     * ([isRemindStale]): смахнул уведомление — и строка висела бы на «Сегодня» бессрочно (проверка 16.10.2026).
     */
    fun isRemindToday(t: Dela.Task, me: String, today: String): Boolean {
        if (!t.open || !mine(me, t) || t.goal) return false
        if (t.remind) return if (t.remindAt.isNotBlank()) mskDay(t.remindAt) <= today && !isRemindStale(t, today) else t.remindPlace.isNotBlank()
        return t.remindAt.isNotBlank() && mskDay(t.remindAt) == today && t.dueDate != today
    }

    /** Напоминание прошло больше суток назад и не отмечено (`isRemindStale` веба, `capture.REMIND_STALE_IF` сервера). */
    fun isRemindStale(t: Dela.Task, today: String): Boolean =
        t.remind && t.remindAt.isNotBlank() &&
            mskDay(t.remindAt) < runCatching { java.time.LocalDate.parse(today).minusDays(1).toString() }.getOrDefault(today)

    /** Миг напоминания в мс; нет времени (по месту) — в конец списка. */
    fun remindMs(t: Dela.Task): Long =
        runCatching { OffsetDateTime.parse(t.remindAt).toInstant().toEpochMilli() }.getOrDefault(Long.MAX_VALUE)

    private val BY_REMIND: Comparator<Dela.Task> = compareBy<Dela.Task>({ remindMs(it) }, { it.num })

    /** Напоминания на «Сегодня» по времени, по месту — в конце (`remindersToday` веба). */
    fun remindersToday(s: Dela.Snapshot, me: String, today: String): List<Dela.Task> =
        s.tasks.values.filter { isRemindToday(it, me, today) }.sortedWith(BY_REMIND)

    /** «Все дела → Напоминания» (`remindersAll` веба, вид `reminders` сервера): все свои открытые напоминания. */
    fun remindersAll(s: Dela.Snapshot, me: String): List<Dela.Task> =
        s.tasks.values.filter { it.open && it.remind && mine(me, it) && !it.goal }.sortedWith(BY_REMIND)

    /**
     * «Вернулось» (`isBack` веба, `capture.BACK_IF`): своё открытое дело «потом» без срока, чей день возврата настал, не в
     * «Сейчас». «Позвонить Ивану на неделе» — в пятницу здесь, а не на дне «Запустить».
     */
    fun isBack(t: Dela.Task, me: String, today: String): Boolean =
        deed(t, me) && t.ball == Dela.MINE && !t.owed && t.dueDate.isBlank() && t.backOn.isNotBlank() &&
            t.backOn <= today && !isNow(t, today)

    /** «Вернулось» по дню возврата (`backTasks` веба). */
    fun backToday(s: Dela.Snapshot, me: String, today: String): List<Dela.Task> =
        s.tasks.values.filter { isBack(it, me, today) }.sortedWith(compareBy<Dela.Task>({ it.backOn }, { it.num }))

    private val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    /** День момента по Москве (`MSK_DAY` веба); не разобрался — первые десять знаков. */
    fun mskDay(iso: String): String =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(MSK).toLocalDate().toString() }.getOrDefault(iso.take(10))

    /*
     * Солнца «На сегодня» / «На завтра» (`toggleToday`, 10.10–14.10.2026) больше нет. Владелец 14.10.2026:
     * «Если нажимаю на „сейчас“, то дело пропадает и не ставится в сейчас… Надо оставить только сейчас».
     * Солнце у строки он принимал за «Сейчас»: у дела со сроком сегодня оно делало «На завтра» — срок уезжал
     * на завтра, «Сейчас» снималось, и дело пропадало с экрана (в журнале трёх дел утром — только `due_date`
     * с телефона). Теперь у строки и в карточке одна отметка — молния ниже; срок меняют «Перенести» и дата.
     */

    /**
     * Молния «Сейчас» (`toggleNow` веба, 13.10.2026: «Сейчас — то, что отметил»): в «Сейчас» — убрать
     * (`focus_on` = null); не в нём — взять на сегодня. В операции ТОЛЬКО `focus_on`: срок, напоминание и
     * группа не трогаются ни при взятии, ни при снятии — дело остаётся, где было (под `DelaGroupsTest`:
     * «молния не трогает срок»). Встаёт только своё открытое дело, не цель; шестое не встаёт
     * ([DelaViews.NOW_FULL], предел держит и сервер — `store._now_guard`). Ответ — поля операции (null —
     * нельзя) и слова для тоста.
     */
    fun toggleNow(t: Dela.Task, s: Dela.Snapshot, me: String, today: String): Pair<JSONObject?, String> = when {
        isNow(t, today) -> JSONObject().put("focus_on", JSONObject.NULL) to "Убрал из «Сейчас»"
        !deed(t, me) -> null to "В «Сейчас» встают только свои открытые дела"
        !DelaViews.nowRoom(s, me, today, listOf(t.id)) -> null to DelaViews.NOW_FULL
        else -> JSONObject().put("focus_on", today) to "В «Сейчас»"
    }

    /** Как было — для `was` правки и «Вернуть» в тосте: те же ключи, прежние значения. */
    fun was(t: Dela.Task, set: JSONObject): JSONObject = JSONObject().apply {
        for (k in set.keys()) put(k, Dela.field(t, k))
    }

    // ------------------------------------------------------------ строка

    /**
     * Вторая строка вместо «мяча» (`taskRow` веба): «Отбить» — «Гуркин ждёт 3 дн»
     * (дни — с того дня, как дело завели; без человека — «ждут»), «Мониторить» —
     * «жду Ольгу 5 дн» (с `waiting_since`), повестка — «повестка · Ольга» (словом Саши
     * с 16.10.2026, запись отладки 19; до того «при встрече с Ольгой»),
     * «Запустить» с человеком — «@Ольга» (если люди не названы заголовком).
     * [strong] — «отбить»: ярче остальных.
     */
    data class Wait(val text: String, val strong: Boolean)

    fun wait(t: Dela.Task, who: String, today: String, personGrouped: Boolean): Wait? = when {
        grpOf(t) == Grp.OWED -> {
            val d = if (t.createdAt.isNotBlank()) DelaViews.days(mskDay(t.createdAt), today) else 0L
            Wait((if (who.isNotBlank()) "$who ждёт" else "ждут") + if (d > 0) " $d дн" else "", strong = true)
        }
        t.ball == Dela.WAITING -> {
            val w = if (t.waitingSince.isNotBlank()) DelaViews.days(t.waitingSince, today) else 0L
            Wait("жду" + (if (who.isNotBlank()) " $who" else "") + if (w > 0) " $w дн" else "", strong = false)
        }
        t.ball == Dela.AGENDA -> Wait("повестка" + if (who.isNotBlank() && !personGrouped) " · $who" else "", strong = false)
        who.isNotBlank() && !personGrouped -> Wait("@$who", strong = false)
        else -> null
    }

    // ------------------------------------------------------------ уровни

    /** Уровень группировки (`LEVELS` веба) и подпись «Все …» кнопки «только одно» (`ALL_OF`). */
    enum class Level(val key: String, val title: String, val allOf: String) {
        PROJECT("project", "проекты", "Все проекты"),
        GRP("grp", "группы", "Все группы"),
        DATE("date", "время", "Всё время"),
        PERSON("person", "люди", "Все люди"),
        DEAL("deal", "сделки", "Все сделки"),
        /** Круг жизни (12.10.2026): первое направление дела; предлагается, только когда Круг включён. */
        DIR("dir", "направления", "Все направления");

        companion object {
            fun of(key: String): Level? = entries.firstOrNull { it.key == key }
        }
    }

    /** Уровней не больше трёх (владелец: «первый, второй, третий»). */
    const val MAX_LEVELS = 3

    /** С завода (`LEVELS_DEF` веба): «Сегодня» — группы, «Все дела» — проекты › группы › время, проект — группы › время. */
    val LEVELS_DEF: Map<String, List<Level>> = mapOf(
        "today" to listOf(Level.GRP),
        "all" to listOf(Level.PROJECT, Level.GRP, Level.DATE),
        "project" to listOf(Level.GRP, Level.DATE),
        "deal" to listOf(Level.GRP, Level.DATE),
    )

    fun levelsDef(view: String): List<Level> = LEVELS_DEF[view] ?: listOf(Level.GRP)

    /** «проекты › группы»; пусто — «без групп» (`levelsName` веба). */
    fun levelsName(levels: List<Level>): String = if (levels.isEmpty()) "без групп" else levels.joinToString(" › ") { it.title }

    /** Уровни строкой для памяти устройства и обратно: «project,grp,date». Пусто — «без групп», чужое — мимо. */
    fun levelsKey(levels: List<Level>): String = levels.joinToString(",") { it.key }.ifBlank { "-" }

    fun parseLevels(key: String?): List<Level>? {
        if (key.isNullOrBlank()) return null
        if (key == "-") return emptyList()
        return key.split(',').mapNotNull { Level.of(it.trim()) }.distinct().take(MAX_LEVELS)
    }

    /**
     * Выбор уровня [i] (`levelsBar` веба): null («—») убирает уровень и всё после
     * него; один уровень дважды не встаёт — повтор дальше по списку уходит.
     */
    fun setLevel(levels: List<Level>, i: Int, lv: Level?): List<Level> {
        val next = if (lv == null) levels.take(i) else levels.take(i) + lv + levels.drop(i + 1)
        return next.distinct().take(MAX_LEVELS)
    }

    /**
     * Ключ дела на уровне (`levelKey` веба): [order] — порядок веток, [title] —
     * заголовок, [id] — человек, проект или сделка (ветки по id: две «Наташи» —
     * две ветки), [full] — полное имя, когда коротких одинаковых больше одного,
     * [note] — подпись в списке «только одно», [find] — по чему его ищут.
     */
    data class Key(
        val order: String,
        val title: String,
        val id: String = "",
        val sub: String = "",
        val late: Boolean = false,
        val full: String = "",
        val note: String = "",
        val find: List<String> = emptyList(),
        val grp: Grp? = null,
        val projectId: String? = null,
        val personId: String? = null,
        val dealId: String? = null,
    )

    fun levelKey(t: Dela.Task, lv: Level, s: Dela.Snapshot, today: String): Key = when (lv) {
        Level.GRP -> grpOf(t).let { g -> Key((g.ordinal + 1).toString(), g.title, sub = g.sub, find = listOf(g.title, g.sub), grp = g) }
        Level.PROJECT -> s.projects[t.projectId]?.let { p ->
            Key("1" + p.name + "\u0001" + p.id, p.name, id = p.id, find = listOf(p.name) + p.aliases, projectId = p.id)
        } ?: Key("9", "Без проекта", find = listOf("без проекта"), projectId = "")
        Level.PERSON -> s.people[t.personId]?.let { p ->
            val org = DelaCrm.orgLabel(s, p.orgId)?.name.orEmpty()
            Key(
                // Ключ — по фамилии (записка 36), заголовок — как зовут.
                "1" + p.sortKey + "\u0001" + p.label + "\u0001" + p.id, p.label, id = p.id, full = p.name,
                note = listOf(p.name.takeIf { p.short.isNotBlank() && p.short != p.name }.orEmpty(), p.role, org).filter { it.isNotBlank() }.joinToString(" · "),
                find = (listOf(p.name, p.short) + p.aliases + listOf(p.role, org)).filter { it.isNotBlank() },
                personId = p.id,
            )
        } ?: Key("9", "Без человека", find = listOf("без человека"))
        Level.DEAL -> s.deals[t.dealId]?.let { d ->
            val pn = s.projects[d.projectId]?.name.orEmpty()
            Key("1" + d.name + "\u0001" + d.id, d.name, id = d.id, note = pn, find = listOf(d.name, pn).filter { it.isNotBlank() }, dealId = d.id)
        } ?: Key("9", "Без сделки", find = listOf("без сделки"))
        Level.DATE -> dateKey(t, today)
        // Направление — главное (у корня цепочки «Зачем»); по кругу: сверху Здоровье.
        Level.DIR -> DelaKrug.directions().let { dirs ->
            dirs.firstOrNull { it.key == DelaKrug.dirsOf(t, s).firstOrNull() }?.let { d ->
                Key((d.pos % 12).toString().padStart(2, '0') + d.key, d.name, sub = d.formula, find = listOf(d.name, d.short).filter { it.isNotBlank() })
            } ?: Key("99", "Без направления", find = listOf("без направления"))
        }
    }

    /** «Время» как уровень: Просрочено, Сегодня, Завтра, дни недели, Следующая неделя, Позже, Без срока, Сделано — по [whenDay]. */
    private fun dateKey(t: Dela.Task, today: String): Key {
        if (!t.open) return Key("9done", "Сделано")
        val d = whenDay(t, today) ?: return Key("8none", "Без срока")
        val n = DelaViews.days(today, d)
        return when {
            n < 0 -> Key("0late", "Просрочено", late = true)
            n == 0L -> Key("1today", "Сегодня", sub = DelaViews.longDate(today))
            n == 1L -> Key("2tomorrow", "Завтра", sub = DelaViews.longDate(d))
            n < 7 -> Key("3$d", DelaViews.longDate(d))
            n < 14 -> Key("5next", "Следующая неделя")
            else -> Key("6later", "Позже")
        }
    }

    private val collator: Collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }

    /**
     * Ветка дерева (`nested` веба): заголовок, [depth] — уровень (0 — раздел,
     * 1 и 2 — подзаголовки), дела ветки по времени и ветки глубже. У последнего
     * уровня [children] пусты, дела — строками.
     */
    data class Node(
        val key: String,
        val title: String,
        val depth: Int,
        val level: Level?,
        val items: List<Dela.Task>,
        val children: List<Node> = emptyList(),
        val sub: String = "",
        val late: Boolean = false,
        val grp: Grp? = null,
        val projectId: String? = null,
        val personId: String? = null,
        val dealId: String? = null,
    ) {
        val leaf: Boolean get() = children.isEmpty()
    }

    /**
     * Дела уровнями (`nested` веба). Без уровней — одна ветка без заголовка.
     * Внутри последнего — по времени. Пустых веток не бывает: они собираются из
     * самих дел. Ключ ветки — путь от корня, чтобы списку было за что держаться.
     * Дело внутри дела (15.10.2026) — в ветке своего верхнего предка из этого же списка
     * ([DelaSteps.topIn]), кроме уровня «время»: иначе «Отбить» и «Запустить» растащили бы
     * его от родителя, и «прямо под родителем» (`DelaSteps.stepsFirst`) не вышло бы.
     */
    fun nested(items: List<Dela.Task>, levels: List<Level>, s: Dela.Snapshot, today: String, depth: Int = 0, path: String = ""): List<Node> {
        val sorted = items.sortedWith(byTime(today))
        val lv = levels.getOrNull(depth)
            ?: return if (depth == 0 && sorted.isNotEmpty()) listOf(Node("all", "", 0, null, sorted)) else emptyList()
        data class B(val k: Key, val list: MutableList<Dela.Task>)
        val buckets = LinkedHashMap<String, B>()
        val top = if (lv == Level.DATE) null else DelaSteps.topIn(sorted)
        for (t in sorted) {
            val k = levelKey(top?.invoke(t) ?: t, lv, s, today)
            buckets.getOrPut(k.order) { B(k, mutableListOf()) }.list += t
        }
        val titles = buckets.values.groupingBy { it.k.title }.eachCount()
        return buckets.entries.sortedWith { a, b -> collator.compare(a.key, b.key) }.map { (order, b) ->
            val k = b.k
            val key = "$path/${lv.key}:$order"
            val deeper = if (depth + 1 < levels.size) nested(b.list, levels, s, today, depth + 1, key) else emptyList()
            Node(
                key = key,
                title = if ((titles[k.title] ?: 0) > 1 && k.full.isNotBlank()) k.full else k.title,
                depth = depth, level = lv, items = b.list.toList(), children = deeper,
                sub = k.sub, late = k.late, grp = k.grp, projectId = k.projectId, personId = k.personId, dealId = k.dealId,
            )
        }
    }

    /** Дела дерева в порядке показа — то, что видит Claude. */
    fun flat(nodes: List<Node>): List<Dela.Task> = nodes.flatMap { if (it.leaf) it.items else flat(it.children) }

    // ------------------------------------------------------------ «только одно»

    /**
     * Ветка первого уровня для списка «только одно» (`branches` веба): [title]
     * — полное имя, если короткие совпали; [n] — сколько дел.
     */
    data class Branch(val key: String, val title: String, val id: String, val n: Int, val note: String, val find: List<String>)

    /** Ветки первого уровня с числом дел: группы и время — по порядку, остальное — где больше дел. */
    fun branches(items: List<Dela.Task>, lv: Level, s: Dela.Snapshot, today: String): List<Branch> {
        val m = LinkedHashMap<String, Pair<Key, Int>>()
        for (t in items) {
            val k = levelKey(t, lv, s, today)
            m[k.order] = (m[k.order]?.first ?: k) to ((m[k.order]?.second ?: 0) + 1)
        }
        val titles = m.values.groupingBy { it.first.title }.eachCount()
        val list = m.map { (order, v) ->
            val k = v.first
            Branch(order, if ((titles[k.title] ?: 0) > 1 && k.full.isNotBlank()) k.full else k.title, k.id, v.second, k.note, listOf(k.title) + k.find)
        }
        return if (lv == Level.GRP || lv == Level.DATE || lv == Level.DIR) list.sortedWith { a, b -> collator.compare(a.key, b.key) }
        else list.sortedWith(compareByDescending<Branch> { it.n }.thenComparator { a, b -> collator.compare(a.title, b.title) })
    }

    /** Поиск в списке веток (`pickPop` веба): каждое слово — в имени, коротком, других именах, должности, компании. */
    fun findBranches(list: List<Branch>, q: String): List<Branch> {
        val words = (Dela.norm(q) ?: "").split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return list
        return list.filter { b ->
            val keys = b.find.mapNotNull { Dela.norm(it) }
            words.all { w -> keys.any { it.contains(w) } }
        }
    }

    /**
     * Выбранная ветка первого уровня (`pick` веба): человек, проект и сделка — по
     * [id] (переименуешь — фильтр не потеряет свою «Наташу»), остальное — по ключу.
     */
    data class Pick(val lv: Level, val key: String, val title: String, val id: String = "")

    fun pickOf(b: Branch, lv: Level): Pick = Pick(lv, b.key, b.title, b.id)

    /** Выбор действует, только пока первый уровень тот же (`pickOn` веба). */
    fun pickOn(pick: Pick?, levels: List<Level>): Boolean = pick != null && levels.firstOrNull() == pick.lv

    fun picked(items: List<Dela.Task>, levels: List<Level>, pick: Pick?, s: Dela.Snapshot, today: String): List<Dela.Task> {
        if (pick == null || !pickOn(pick, levels)) return items
        return items.filter { t ->
            val k = levelKey(t, pick.lv, s, today)
            if (pick.id.isNotBlank()) k.id == pick.id else k.order == pick.key
        }
    }

    fun pickJson(p: Pick): JSONObject = JSONObject().put("lv", p.lv.key).put("key", p.key).put("title", p.title)
        .apply { if (p.id.isNotBlank()) put("id", p.id) }

    fun pick(o: JSONObject?): Pick? {
        if (o == null) return null
        val lv = Level.of(o.str("lv")) ?: return null
        return Pick(lv, o.str("key"), o.str("title"), o.str("id"))
    }

    // ------------------------------------------------------------ фильтры

    /** Что показывает фильтр (`SCOPES` веба): сегодня, всё открытое или одна группа. */
    enum class Scope(val key: String, val title: String) {
        TODAY("today", "Сегодня"),
        ALL("all", "Все дела"),
        OWED("owed", "Отбить"),
        SELF("self", "Запустить"),
        CHECK("check", "Мониторить");

        companion object {
            fun of(key: String): Scope = entries.firstOrNull { it.key == key } ?: ALL
        }
    }

    /** Фильтр — что показать и уровни, с выбором «только одно» (`filters[]` в `users.settings`). */
    data class Filter(val id: String, val name: String, val scope: Scope, val levels: List<Level>, val pick: Pick? = null)

    /**
     * С завода — пока своих нет (`FILTERS_DEF` веба): сегодня по проектам, и кому
     * отбить и кого мониторить — по людям: перед звонком видно всё, что должен
     * человеку и что жду от него.
     */
    val FILTERS_DEF = listOf(
        Filter("f-today-proj", "Сегодня по проектам", Scope.TODAY, listOf(Level.PROJECT, Level.GRP)),
        Filter("f-owed-people", "Отбить — по людям", Scope.OWED, listOf(Level.PERSON)),
        Filter("f-check-people", "Мониторить — по людям", Scope.CHECK, listOf(Level.PERSON)),
    )

    /**
     * Куда ведёт «ждут от тебя без срока» и «жду больше недели» в шапке «Сейчас» (`scopeHref` веба, аудит №5):
     * заводской фильтр по id, иначе свой с той же группой и без «только одно»; нет — null («Все дела»).
     */
    fun scopeFilter(filters: List<Filter>, id: String, scope: Scope): Filter? =
        filters.firstOrNull { it.id == id } ?: filters.firstOrNull { it.scope == scope && it.pick == null }

    /** `settings.filters` из `/api/me`: нет поля (или не список) — заводские; незнакомое внутри — мимо. */
    fun filters(raw: Any?): List<Filter> {
        val a = raw as? JSONArray ?: return FILTERS_DEF
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id").ifBlank { return@mapNotNull null }
            val lv = o.optJSONArray("levels")
            Filter(
                id = id,
                name = o.str("name").ifBlank { "Фильтр" },
                scope = Scope.of(o.str("scope")),
                levels = if (lv == null) emptyList() else (0 until lv.length()).mapNotNull { Level.of(lv.optString(it)) }.distinct().take(MAX_LEVELS),
                pick = pick(o.optJSONObject("pick")),
            )
        }
    }

    fun json(f: Filter): JSONObject = JSONObject().put("id", f.id).put("name", f.name).put("scope", f.scope.key)
        .put("levels", JSONArray().apply { f.levels.forEach { put(it.key) } })
        .apply { f.pick?.takeIf { pickOn(it, f.levels) }?.let { put("pick", pickJson(it)) } }

    fun json(list: List<Filter>): JSONArray = JSONArray().apply { list.forEach { put(json(it)) } }

    /** Операция сохранения — список целиком (`user.settings` сливает верхний уровень настроек). */
    fun saveOp(list: List<Filter>, opId: String = Dela.newId()): JSONObject =
        JSONObject().put("op", "user.settings").put("op_id", opId).put("settings", JSONObject().put("filters", json(list)))

    /** Имя по умолчанию (`addFilter` веба): «Сегодня: проекты › группы — Наташа». */
    fun suggestName(scope: Scope, levels: List<Level>, pick: Pick?): String =
        "${scope.title}: ${levelsName(levels)}" + (pick?.takeIf { pickOn(it, levels) }?.let { " — " + it.title } ?: "")

    /** Дела фильтра (`scopeItems` веба) — без целей: они в дереве, а не в списках дел. */
    fun scopeItems(s: Dela.Snapshot, me: String, today: String, sphere: String, scope: Scope): List<Dela.Task> = when (scope) {
        Scope.TODAY -> todayTasks(s, me, today, sphere)
        else -> s.tasks.values.filter {
            it.open && it.deed && mine(me, it) && Dela.inSphere(it, sphere) && (scope == Scope.ALL || grpOf(it).key == scope.key)

        }
    }
}
