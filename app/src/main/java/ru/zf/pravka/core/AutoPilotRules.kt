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
    ): Walk {
        if (leftAtMs <= 0L || now < leftAtMs) return Walk.NONE
        if (now - leftAtMs < WALK_AFTER_MS) return Walk.WAIT
        // Окно прошло — решение устарело: через час это уже другая история.
        if (now - leftAtMs > WALK_AFTER_MS + SHORT_EXIT_MS) return Walk.NONE
        if (openTitle != null && travelish(openTitle, openCategory.orEmpty())) return Walk.NONE
        if (latestOwnerStart > leftAtMs) return Walk.NONE
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

    /** Сидячие дела — по ним «точно ещё …?» после значимого движения. */
    fun sedentary(category: String): Boolean = category.lowercase().let {
        it.startsWith("работа") || it == "систематизация" || it == "чтение"
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
}
