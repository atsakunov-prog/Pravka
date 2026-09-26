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
    fun carTripStart(connectedAt: Long, leftPlace: String, leftAtMs: Long, openStart: Long?): Long {
        if (leftPlace.isBlank() || leftAtMs <= 0L) return connectedAt
        val since = connectedAt - leftAtMs
        if (since < 0L || since > CAR_AFTER_LEAVE_MS) return connectedAt
        if (openStart != null && openStart > leftAtMs) return connectedAt
        return leftAtMs
    }

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
