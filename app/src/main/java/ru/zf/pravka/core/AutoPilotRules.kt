package ru.zf.pravka.core

/**
 * Дело, которое место начинает само по приезду: у Летово это «Забираю
 * Серёжу» [Семья]. Владелец (08.09.2026): «вышел из машины и подсоединился к
 * Wi-Fi Летова — ставится „Летово, забираю Серёжу“, потому что скорее всего
 * это оно». Пустое название — у места дела нет, автопилот спрашивает.
 */
data class PlaceDeal(val title: String, val category: String)

/**
 * Решения автопилота Засечки — без Android, чтобы их можно было прогнать
 * JVM-тестом. Сам автопилот (`trigger/AutoPilot.kt`) только собирает сигналы
 * (сеть, эфир, Bluetooth) и показывает уведомления; ЧТО сказать и что
 * закрыть — решается здесь.
 *
 * Зачем выносить: автопилот молчал месяцами, и каждый раз причина находилась
 * на телефоне, глазами. Правила приезда и отъезда — единственное, что можно
 * проверить без телефона, вот их и проверяем.
 */
object AutoPilotRules {

    /**
     * Сколько сеть может пропадать, чтобы это ещё считалось миганием роутера,
     * а не отъездом и возвращением. Полчаса: поход в магазин за это время
     * укладывается, и вернувшись, владелец услышит «всё ещё „Работа“?» —
     * лента к тому моменту уже врёт.
     */
    const val BLINK_MS = 30 * 60_000L

    /**
     * Сколько может пройти между потерей сети места и подключением машины,
     * чтобы это была ОДНА дорога: вышел из дома, дошёл до машины, поехал.
     * Двадцать минут: до парковки у Летово идти дольше пяти, а через
     * полчаса это уже другая история. Владелец (08.09.2026): «через три
     * минуты подключаюсь к машине — логично предположить, что я вышел из
     * дома; включить машину и слепить с выходом из дома».
     */
    const val CAR_AFTER_LEAVE_MS = 20 * 60_000L

    /**
     * Короткий выход из дома — мусор, машина за вещью, курьер у подъезда — не
     * отъезд. Владелец (27.09.2026): «вышел из дома и вернулся менее чем через
     * десять минут — не надо прерывать текущее дело». Десять минут молчим
     * целиком: ни вопроса «уехал?», ни «всё ещё …?» по датчику движения;
     * вернулся раньше — вопросы снимаются, дело в ленте идёт как шло.
     */
    const val SHORT_EXIT_MS = 10 * 60_000L

    /**
     * Сколько ждать после короткого выхода, чтобы назвать движение ходьбой:
     * двадцать минут телефона в движении без машины и без нового места.
     * Владелец: «вышел из дома и телефон двигается минут двадцать — это
     * передвижение пешком».
     */
    const val WALK_AFTER_MS = 20 * 60_000L

    /** Сколько срабатываний датчика значимого движения — «телефон в движении». */
    const val WALK_MIN_MOTIONS = 4

    /**
     * Сколько срабатываний датчика за [SHORT_EXIT_MS] после первого — не поход
     * на кухню, а «встал и не сел»: тогда «всё ещё „Работа“?» имеет смысл.
     */
    const val STILL_MIN_MOTIONS = 3

    /**
     * Сколько живёт якорь времени из пуша. «Вышел из машины в 14:02 — что
     * теперь?» нажатое вечером не должно резать ленту на шесть часов назад:
     * за это время владелец наверняка уже наговорил день сам.
     */
    const val ANCHOR_MAX_AGE_MS = 6 * 3_600_000L

    /**
     * С какого момента начинать «Поездку на машине», когда подключился
     * Bluetooth. Если только что (в [CAR_AFTER_LEAVE_MS]) пропала сеть
     * места [leftPlace] — дорога началась ТАМ, у двери, а не у зажигания:
     * иначе три минуты до машины остаются на «Работе», а пуш «уехал из
     * дома?» висит рядом с уже идущей поездкой. Дело, начатое владельцем
     * ПОСЛЕ отъезда ([openStart] позже [leftAtMs]), — его слово: он знает,
     * что делал между дверью и машиной, и резать это задним числом нельзя;
     * тогда поездка стартует с подключения.
     */
    fun carTripStart(connectedAt: Long, leftPlace: String, leftAtMs: Long, openStart: Long?): Long =
        stitchedStart(connectedAt, leftPlace, leftAtMs, openStart)

    /**
     * Тренировка с часов уже лежит в ленте — узнаём по КОНЦУ: начало у неё
     * бывает двояким (с кнопки часов или от двери, [stitchedStart]), а конец
     * один. Лента сама узнаёт повтор по имени и началу, и 22.09.2026 этого не
     * хватило: свип, прошедший без автопилота (служба перезапускалась), не
     * узнал BJJ, пришитую к двери в 12:40, и положил вторую с 12:58 — сутки
     * вышли на 1497 минут. [laid] — записи ленты от [CAR_AFTER_LEAVE_MS] до
     * начала тренировки по её конец.
     */
    fun watchLaid(laid: List<ru.zf.pravka.data.ZasechkaStore.Entry>, title: String, end: Long): Boolean {
        val t = title.trim()
        return laid.any { it.source == "auto" && !it.open && it.title == t && kotlin.math.abs(it.end - end) < 60_000L }
    }

    /**
     * То же правило для любого сигнала «дорога началась» в [signalAt]: машина
     * подключилась, тренировка с часов стартовала (вело от двери — владелец,
     * 27.09.2026: «вышел из дома, потом приехало Вело — это передвижение
     * вело»), телефон двадцать минут в движении. Сеть места пропала не
     * раньше [CAR_AFTER_LEAVE_MS] до сигнала — дорога с потери сети.
     */
    fun stitchedStart(signalAt: Long, leftPlace: String, leftAtMs: Long, openStart: Long?): Long {
        if (leftPlace.isBlank() || leftAtMs <= 0L) return signalAt
        val since = signalAt - leftAtMs
        if (since < 0L || since > CAR_AFTER_LEAVE_MS) return signalAt
        if (openStart != null && openStart > leftAtMs) return signalAt
        return leftAtMs
    }

    /**
     * Через сколько после потери сети в [lostAtMs] спрашивать «уехал?»,
     * если смотреть на часы в [now]: ровно так, чтобы вопрос пришёл через
     * [SHORT_EXIT_MS] после потери — и ни секундой раньше. Место «по
     * видимости» узнаёт отъезд с опозданием (пять минут без свежих сканов),
     * поэтому задержка считается от момента потери, а не от момента, когда
     * её заметили.
     */
    fun leaveAskDelay(lostAtMs: Long, now: Long): Long =
        (SHORT_EXIT_MS - (now - lostAtMs)).coerceAtLeast(0L)

    /**
     * Сказанное прямо перед выходом — ответ на «куда?». Владелец (01.10.2026):
     * «если я, например, написал: „Пошёл развозить детей“ — и отключается
     * домашний Wi-Fi, то… это просто развоз детей на машине, это транспорт.
     * Или пошёл поговорить с другом — это выход из дома, и понятно, что я
     * сейчас сделаю какую-то встречу». Дело, начатое владельцем за
     * [SAID_BEFORE_LEAVE_MS] до потери сети (или уже после неё), — его слово
     * о выходе: ни «уехал?», ни ходьбы, ни «Поездки на машине» поверх.
     */
    const val SAID_BEFORE_LEAVE_MS = 10 * 60_000L

    /**
     * Слова выхода («пошёл», «везу», «развожу», «к другу») растягивают окно
     * до [OUTING_BEFORE_LEAVE_MS]: «пошёл развозить детей» говорят, пока дети
     * обуваются, а не на пороге. Без таких слов десяти минут хватает — и
     * «завтракаю» за двадцать минут до выхода дорогу не отменяет.
     */
    const val OUTING_BEFORE_LEAVE_MS = 30 * 60_000L

    private val OUTING = Regex(
        "(пош[её]л|пойд[уё]|иду|ид[её]м|выхож|выйд|вышел|еду|ед[её]м|поехал|поед|выезжа|" +
            "везу|отвож|отвоз|отвез|развож|развоз|развез|забира|заберу|к друг|к подруг|" +
            "на встреч|встреча|встречу|в гости|гуля|прогул)",
    )

    /** Слова выхода из дома в названии или надиктовке дела. */
    fun outing(text: String): Boolean = OUTING.containsMatchIn(text.lowercase())

    /**
     * Сказал ли владелец, куда идёт: открытое дело [openStart] его
     * ([openSource] — не робот и не заполнитель) и СКАЗАНО — есть надиктовка
     * [openRaw] (поездку, встречу из календаря и дело по подъёму автопилот
     * заводит владельческим источником, но без слов, и ответом на «куда?»
     * они не считаются); начато после потери сети [leftAtMs], за
     * [SAID_BEFORE_LEAVE_MS] до неё или — со словами выхода в названии
     * [openTitle] или надиктовке — за [OUTING_BEFORE_LEAVE_MS].
     */
    fun ownerToldLeave(openStart: Long?, openSource: String?, openTitle: String, openRaw: String, leftAtMs: Long): Boolean {
        if (openStart == null || openStart <= 0L || leftAtMs <= 0L) return false
        if (openSource == null || openSource == "auto" || openSource == "gap") return false
        if (openRaw.isBlank()) return false
        val before = leftAtMs - openStart
        if (before < 0L) return true
        if (before <= SAID_BEFORE_LEAVE_MS) return true
        return before <= OUTING_BEFORE_LEAVE_MS && outing("$openTitle $openRaw")
    }

    /** Что автопилот делает, когда телефон двадцать минут в движении после отъезда. */
    enum class Walk {
        /** Начать «Дорогу пешком» с момента отъезда. */
        START,
        /** Ещё рано — окно двадцати минут не вышло. */
        WAIT,
        /** Не ходьба: дорога уже идёт, владелец сказал своё, телефон лежал, окно прошло. */
        NONE,
    }

    /**
     * Ходьба после отъезда. [motions] — срабатываний датчика значимого движения
     * с момента отъезда [leftAtMs]; [openTitle]/[openCategory] — что открыто;
     * [latestOwnerStart] — начало последней записи владельца (сказал что-то
     * после отъезда — он в курсе, робот молчит). Машина и приезд в место
     * снимают вопрос раньше, сюда не доходят.
     */
    fun walkVerdict(
        motions: Int,
        leftAtMs: Long,
        now: Long,
        openTitle: String?,
        openCategory: String?,
        latestOwnerStart: Long,
        /** Владелец сказал, куда идёт, на выходе ([ownerToldLeave]) — его слово сильнее толчков. */
        ownerTold: Boolean = false,
    ): Walk {
        if (leftAtMs <= 0L || now < leftAtMs) return Walk.NONE
        if (now - leftAtMs < WALK_AFTER_MS) return Walk.WAIT
        // Окно прошло — решение устарело: через час это уже другая история.
        if (now - leftAtMs > WALK_AFTER_MS + SHORT_EXIT_MS) return Walk.NONE
        if (openTitle != null && travelish(openTitle, openCategory.orEmpty())) return Walk.NONE
        if (latestOwnerStart > leftAtMs) return Walk.NONE
        if (ownerTold) return Walk.NONE
        if (motions < WALK_MIN_MOTIONS) return Walk.NONE
        return Walk.START
    }

    /**
     * Спрашивать ли «всё ещё „Работа“?» через [SHORT_EXIT_MS] после первого
     * движения. Раньше вопрос летел в ту же секунду, когда владелец встал, —
     * и вынести мусор значило получить пуш. Теперь: сеть места пропала после
     * движения — это отъезд, и его спросит вопрос «уехал?» ([leaveAsked]);
     * если тот выключен, спрашиваем здесь. Сеть на месте — спрашиваем, только
     * если телефон продолжал двигаться ([motions] ≥ [STILL_MIN_MOTIONS]):
     * сходил на кухню и сел обратно — один-два толчка, не вопрос.
     */
    fun stillAsk(motions: Int, leftAfterMotion: Boolean, leaveAsked: Boolean): Boolean {
        if (leftAfterMotion) return !leaveAsked
        return motions >= STILL_MIN_MOTIONS
    }

    /**
     * Категория тренировки с часов, которая началась от двери. Вело, кончившееся
     * НЕ там, где началось (уехал из дома — сеть Летово увиделась после
     * финиша), — велосипед как транспорт, «Передвижение: вело»; круг от дома
     * до дома — тренировка, как и была. Бег остаётся бегом всегда: бегом не
     * ездят по делам.
     */
    fun activityCategory(default: String, type: String, arrivedElsewhere: Boolean): String {
        if (!arrivedElsewhere) return default
        return when (type) {
            "Ride", "GravelRide", "MountainBikeRide" -> "Передвижение: вело"
            else -> default
        }
    }

    // ---- Отбой и подъём (владелец, 27.09.2026: «дело по подъёму в будни — сборы
    // детей с пн по пт; зарядку телефона, бездвижение и выключенный экран —
    // как отбой») ----

    /**
     * Отбой узнаётся по трём вещам сразу: телефон поставлен на зарядку
     * ВЕЧЕРОМ (сам факт подключения после этого часа — на тумбочке, а не на
     * столе с семи вечера), экран погашен и тишина датчика значимого
     * движения. С 01.10.2026 отбой не только закрывает день — с него
     * начинается сон ([sleepPlan]).
     */
    const val BEDTIME_FROM_HOUR = 22
    const val BEDTIME_TO_HOUR = 4

    /** Час подключения к зарядке, который считается вечерним: с 22:00 до 04:00. */
    fun bedtimeHour(hour: Int): Boolean = hour >= BEDTIME_FROM_HOUR || hour < BEDTIME_TO_HOUR

    // ---- Сон сам (владелец, 01.10.2026: «если я сам не включил сон через NFC
    // или через что-то ещё… то сон включается в момент, когда [подключается]
    // зарядка и плюс телефон неподвижен полчаса… плюс вечернее время, после
    // 11. И уведомление, что включается он, и давать мне возможность убрать.
    // И выключается он, когда телефон задвигался с утра») ----

    /** Как зовётся сон, начатый автопилотом: по названию его узнают Garmin и «Отменить». */
    const val SLEEP_TITLE = "Сон"

    /** Полчаса без единого толчка — телефон лежит, владелец спит. */
    const val SLEEP_STILL_MS = 30 * 60_000L

    /** Сон решается не раньше этого часа: «плюс вечернее время, после 11». */
    const val SLEEP_DECIDE_HOUR = 23

    /**
     * Толчок с этого часа кончает сон: утро. Ночью — «если он задвигался в
     * 4:00, я что-то взял, посмотрел, убрал, то это не прерывание сна».
     */
    const val SLEEP_WAKE_FROM_HOUR = 5

    /** Сон сам: с какого мига ([from]) и когда решать ([decideAt]). */
    data class SleepPlan(val from: Long, val decideAt: Long)

    /**
     * Кандидат в сон. Телефон на зарядке, подключённой ВЕЧЕРОМ ([bedtimeHour]
     * часа подключения: на тумбочке, а не на столе с семи), экран погашен.
     * Лёг — позднее из подключения, гашения экрана, последнего толчка
     * [lastMotionAt] и последнего слова владельца [ownerSaidAt]: что было
     * позже, то он и делал последним, бодрствуя. Решать — через
     * [SLEEP_STILL_MS] тишины, но не раньше [SLEEP_DECIDE_HOUR] той ночи.
     */
    fun sleepPlan(
        chargingSince: Long,
        screenOffSince: Long,
        lastMotionAt: Long,
        ownerSaidAt: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): SleepPlan? {
        if (chargingSince <= 0L || screenOffSince <= 0L) return null
        if (!bedtimeHour(hourOf(chargingSince, zone))) return null
        val from = maxOf(chargingSince, screenOffSince, lastMotionAt, ownerSaidAt)
        val c = java.util.Calendar.getInstance(zone)
        c.timeInMillis = from
        // 23:00 той ночи: после полуночи — вчерашние, они уже прошли.
        if (c.get(java.util.Calendar.HOUR_OF_DAY) < BEDTIME_TO_HOUR) c.add(java.util.Calendar.DAY_OF_MONTH, -1)
        c.set(java.util.Calendar.HOUR_OF_DAY, SLEEP_DECIDE_HOUR)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return SleepPlan(from, maxOf(from + SLEEP_STILL_MS, c.timeInMillis))
    }

    /** Толчок в этот час кончает сон: с пяти утра до десяти вечера. Ночью — взгляд, не подъём. */
    fun sleepWakeHour(hour: Int): Boolean = hour in SLEEP_WAKE_FROM_HOUR until BEDTIME_FROM_HOUR

    private fun hourOf(ms: Long, zone: java.util.TimeZone): Int {
        val c = java.util.Calendar.getInstance(zone)
        c.timeInMillis = ms
        return c.get(java.util.Calendar.HOUR_OF_DAY)
    }

    /** Подъём в будни считается в эти часы: раньше пяти — не подъём, после десяти — не сборы. */
    const val WAKE_DEAL_FROM_HOUR = 5
    const val WAKE_DEAL_TO_HOUR = 10

    /**
     * Начинать ли дело по подъёму. [dayOfWeek] — как в `Calendar` (1 —
     * воскресенье, 2 — понедельник … 7 — суббота): только пн–пт. [wakeHour] —
     * час подъёма. [latestOwnerStart] — начало последней записи владельца:
     * сказал что-то с подъёма — он в курсе, робот молчит.
     */
    fun wakeDealDue(dayOfWeek: Int, wakeHour: Int, wakeAt: Long, latestOwnerStart: Long): Boolean {
        if (dayOfWeek !in 2..6) return false
        if (wakeHour !in WAKE_DEAL_FROM_HOUR until WAKE_DEAL_TO_HOUR) return false
        if (latestOwnerStart >= wakeAt) return false
        return true
    }

    /** Тренировка на улице — та, что может начаться от двери: не станок и не зал. */
    fun outdoor(type: String): Boolean = type in setOf(
        "Ride", "GravelRide", "MountainBikeRide", "Run", "TrailRun", "Walk", "Hike",
    )

    /**
     * Годится ли якорь времени [anchor] (момент, от которого пуш или дыра в
     * ленте предложили считать сказанное) для записи, которую владелец
     * делает в [now]. Якорь в будущем, старше [ANCHOR_MAX_AGE_MS] или
     * перекрытый делом, начатым уже после него ([latestRealStart] позже
     * якоря), — не годится: лента с тех пор жила своей жизнью, и запись
     * начинается «сейчас», как обычно. Дело, начатое ровно В якорь (дело
     * места по приезду), якорь не ломает: сказанное его заменит.
     */
    fun anchoredStart(anchor: Long, now: Long, latestRealStart: Long): Long? {
        if (anchor <= 0L || anchor > now) return null
        if (now - anchor > ANCHOR_MAX_AGE_MS) return null
        if (latestRealStart > anchor) return null
        return anchor
    }

    /** Дело места по имени, без регистра: «Летово» и «летово» — одно место. */
    fun dealFor(place: String, deals: Map<String, PlaceDeal>): PlaceDeal? {
        if (place.isBlank()) return null
        val d = deals.entries.firstOrNull { it.key.trim().equals(place.trim(), ignoreCase = true) }?.value
        return d?.takeIf { it.title.isNotBlank() }
    }

    /** Дорога узнаётся по категории ИЛИ по названию: «Поездка домой». */
    fun travelish(title: String, category: String): Boolean {
        if (category.startsWith("Передвижение", ignoreCase = true)) return true
        val t = title.lowercase()
        return listOf("поездка", "поехал", "дорога", "едем", "еду ", "в пути", "такси")
            .any { t.contains(it) }
    }

    /** Тренировка: приехал домой — скорее всего закончил, но спросим. */
    fun sporty(category: String): Boolean = category.startsWith("Спорт", ignoreCase = true)

    /**
     * По каким делам «всё ещё …?», когда телефон задвигался. До 01.10.2026 —
     * только сидячие (работа, систематизация, чтение); владелец: «надо
     * использовать побольше… как только я взял телефон, то дело какое-то
     * другое началось». Теперь — все, кроме тех, где движение и есть дело:
     * дорога, спорт, быт (уборка, покупки), сон, заполнитель дыры.
     */
    fun stillAskable(title: String, category: String): Boolean {
        if (travelish(title, category) || sporty(category)) return false
        val c = category.trim().lowercase()
        return c != "сон" && c != "быт" && c != "не размечено"
    }

    /** Что автопилот делает, увидев место. */
    enum class Arrival {
        /** Открыта дорога — закрыть её и спросить, что теперь. */
        CLOSE_TRAVEL,
        /** Открыта тренировка — предложить закрыть, сам не трогать. */
        ASK_SPORT,
        /** Открыто обычное дело, а владелец явно перемещался — «всё ещё …?». */
        ASK_STILL,
        /** Ничего не открыто, а место сменилось — «что делаешь?». */
        ASK_WHAT,
        /** Роутер мигнул, служба перезапустилась — молчать. */
        SILENT,
    }

    /**
     * Приезд в [place]. Доказательством перемещения считается ЗАФИКСИРОВАННЫЙ
     * отъезд: сеть [leftPlace] пропала в [leftAtMs]. Без него любое
     * переподключение к домашнему роутеру или перезапуск службы выглядело бы
     * как приезд — и владелец получал бы «всё ещё „Работа“?» посреди работы
     * дома. Так автопилот и вёл себя раньше: молчал «на всякий случай» всегда,
     * поэтому переход из Летово домой с открытой «Встречей» тоже проходил
     * молча. Теперь молчим только там, где перемещения не доказать.
     */
    fun arrival(
        openTitle: String?,
        openCategory: String?,
        openStart: Long,
        place: String,
        leftPlace: String,
        leftAtMs: Long,
        now: Long,
    ): Arrival {
        if (openTitle != null) {
            val cat = openCategory.orEmpty()
            if (travelish(openTitle, cat)) return Arrival.CLOSE_TRAVEL
            if (sporty(cat)) return Arrival.ASK_SPORT
        }
        // Отъезд был раньше начала дела — значит, дело он начал уже здесь
        // (или в пути) и знает, что делает.
        val leftAfterOpen = leftAtMs > 0L && (openTitle == null || leftAtMs > openStart)
        if (!leftAfterOpen) return Arrival.SILENT
        val moved = leftPlace != place || now - leftAtMs >= BLINK_MS
        if (!moved) return Arrival.SILENT
        return if (openTitle == null) {
            // Без открытого дела спрашиваем только при СМЕНЕ места: мигание
            // домашнего роутера ночью, когда ничего не идёт, — не повод.
            if (leftPlace != place) Arrival.ASK_WHAT else Arrival.SILENT
        } else Arrival.ASK_STILL
    }

    /**
     * Машина ли это Bluetooth-устройство. Адрес надёжнее имени: имя система
     * отдаёт только с разрешением и только если успела его закэшировать, а
     * ACL-событие приходит и без имени. Имя сравниваем без регистра и
     * по началу: магнитола показывается как «Volvo» и «Volvo Media» — это
     * одна машина.
     */
    fun isCar(name: String?, address: String?, carName: String, carAddress: String): Boolean {
        val addr = address.orEmpty().trim()
        if (carAddress.isNotBlank() && addr.isNotBlank() &&
            addr.equals(carAddress.trim(), ignoreCase = true)
        ) return true
        val n = name.orEmpty().trim()
        val c = carName.trim()
        if (n.isBlank() || c.isBlank()) return false
        return n.equals(c, ignoreCase = true) || n.startsWith(c, ignoreCase = true)
    }
}

/** Шов дня: место и момент — отъезд («потерял дом в 10:02») или приезд. */
data class Leave(val place: String, val atMs: Long)

/**
 * Что автопилот знает о последних швах — для тех, кто пишет дорогу другим
 * сигналом. Тренировка с часов (`data/IcuSweeper.kt`) спрашивает здесь, не
 * началась ли она от двери, и снимает вопрос «уехал?», раз дорога уже есть.
 */
interface AutoWitness {
    fun lastLeave(): Leave?
    fun lastArrival(): Leave?
    /** Дорога началась другим сигналом ([by] — каким) — вопрос «уехал?» снят. */
    fun leaveAnswered(by: String)
    /** Телефон нашёл ночь [sleptFrom]–[wakeAt] и записал её: автопилот решает про дело по подъёму. */
    fun woke(sleptFrom: Long, wakeAt: Long)

    /**
     * Детектор ночи по экрану увидел подъём в [wakeAt], а сон, начатый
     * автопилотом, ещё идёт: true — автопилот закрыл его этим подъёмом
     * (и сам решил про дело по подъёму), ночь второй раз не пишется.
     */
    suspend fun nightSeen(wakeAt: Long): Boolean = false

    /**
     * Звонок лёг в ленту врезкой (06.10.2026, `CallRules`): автопилот говорит
     * это пушем с «Работа · Семья · Убрать». [sure] — собеседник узнан, и
     * пуш — тихая копия в шторку; не узнан — вопрос плашкой или громко.
     * [cut] — какое дело звонок разрезал (пусто — ничего не шло).
     */
    fun callInRibbon(entryId: Long, title: String, category: String, client: String, start: Long, end: Long, cut: String, sure: Boolean) {}
}
