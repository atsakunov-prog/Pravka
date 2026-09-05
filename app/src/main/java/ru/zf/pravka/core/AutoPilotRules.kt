package ru.zf.pravka.core

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
