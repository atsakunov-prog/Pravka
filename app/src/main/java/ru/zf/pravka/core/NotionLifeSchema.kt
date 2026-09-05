package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore

/**
 * Структура жизни владельца в Notion — одним файлом, чистым Kotlin.
 *
 * Здесь ВСЁ, что синхронизатор знает о базах под хабом «Правка: разборы»:
 * названия баз, их колонки с типами и описаниями, устаревшие колонки, которые
 * надо убрать, и построители строк для каждой сущности. Код — источник правды
 * о структуре: приложение по этому описанию находит базы, создаёт недостающие,
 * достраивает колонки и пишет строки. Значит, «взять актуальную структуру
 * жизни из Notion» всегда можно — она там ровно такая, как описано здесь.
 *
 * Почему так, а не «одна база на всё». Первая версия складывала таймшит,
 * параллели, еду, тренировки, силовые, зарядку и комментарии в одну «Засечку»
 * с полем «Домен». Владелец: «захожу, а там какой-то мусор по времени, а еда и
 * тело — огрызками». Он прав: у еды и у тренировки нет ни одного общего поля с
 * делом ленты, кроме даты, и тридцать колонок, из которых у каждой строки
 * заполнено пять, читаются как свалка. Вторая версия добавила «Дни» — сутки
 * одной строкой на пятьдесят колонок — и «Справочник» со служебными строками
 * синка. Владелец второй раз: «куча лишних таблиц… давай сделаем там только
 * raw data из засечки, спорта, тела, но зато настоящие». Поэтому здесь ТОЛЬКО
 * сырые данные по доменам плюс справочник категорий; итоги по категориям за
 * неделю и месяц Notion складывает сам — графиками и группировками по полям
 * «Месяц» и «Неделя», которые ради этого стоят у каждой строки.
 *
 * Правила, которые здесь держатся:
 * - никаких агрегатов, которые владелец может получить представлением Notion:
 *   всё, что здесь лежит, — событие или суточный факт, а не наш пересчёт;
 * - у каждой базы есть служебный ключ (EntryId, MealId, …) — по нему
 *   синхронизатор узнаёт свою строку и не плодит дублей;
 * - «День» везде — сутки владельца датой без времени: SQL-слой Notion отдаёт
 *   «Дату» в UTC и режет сутки в 03:00 по Москве.
 *
 * Файл без Android: его проверяет JVM-тест — каждая строка использует только
 * колонки своей базы, иначе Notion ответит 400 и строка не доедет.
 */
object NotionLifeSchema {

    /** Хаб «Правка: разборы» — под ним живут все базы. */
    const val HUB_DEFAULT = "3cdc4ffca2d581568abad6839b74784c"

    /** Версия схемы; поднимается, когда меняется раскладка по базам. */
    const val VERSION = 3

    data class Column(
        val name: String,
        /** title | rich_text | number | select | checkbox | date | url */
        val type: String,
        val options: List<String> = emptyList(),
        val description: String = "",
    )

    data class Db(
        val name: String,
        /** Известный id — страховка, если хаб не открыт интеграции. Пусто — искать по названию. */
        val knownId: String,
        val description: String,
        val columns: List<Column>,
        /** Колонки прошлой раскладки: убираются один раз после переезда строк. */
        val retired: List<String> = emptyList(),
    ) {
        val title: Column get() = columns.first { it.type == "title" }
        fun has(name: String): Boolean = columns.any { it.name == name }
    }

    private fun titleCol(name: String, d: String = "") = Column(name, "title", description = d)
    private fun textCol(name: String, d: String = "") = Column(name, "rich_text", description = d)
    private fun numCol(name: String, d: String = "") = Column(name, "number", description = d)
    private fun selCol(name: String, options: List<String>, d: String = "") = Column(name, "select", options, d)
    private fun checkCol(name: String, d: String = "") = Column(name, "checkbox", description = d)
    private fun dateCol(name: String, d: String = "") = Column(name, "date", description = d)
    private fun urlCol(name: String, d: String = "") = Column(name, "url", description = d)

    private const val DAY_DESC = "сутки Саши (Europe/Moscow) датой без времени: складывать и группировать дни по этому полю"
    private const val MONTH_DESC = "YYYY-MM — для группировки и итогов по месяцам"
    private const val WEEK_DESC = "ISO-неделя YYYY-Wnn (понедельник—воскресенье) — для итогов по неделям"

    private fun monthCol() = selCol("Месяц", emptyList(), MONTH_DESC)
    private fun weekCol() = selCol("Неделя", emptyList(), WEEK_DESC)

    /** Как строка попала в ленту — словами владельца, а не кодами. */
    val RECORDED = listOf("голос", "текст", "правка руками", "из Todoist", "метка NFC", "автопилот", "авто: телефон или часы", "заполнитель")

    // ---- Засечка: только основной трек ----

    val ZASECHKA = Db(
        name = "Засечка",
        knownId = "5b11be1184494f0197b05ffffe357504",
        description = "Таймшит: строка на дело основного трека. Минуты складываются в сутки ровно в 1440.",
        columns = listOf(
            titleCol("Дело"),
            dateCol("Дата", "начало и конец дела; для порядка внутри дня"),
            dateCol("День", DAY_DESC),
            numCol("Минуты", "разность минут суток: сумма за День = 1440 (у сегодняшнего — сколько прошло)"),
            selCol("Категория", ZasechkaStore.DEFAULT_CATEGORIES.map { it.name }),
            textCol("Клиент"),
            numCol("Ценность часа", "ценность часа категории на шкале владельца, от −10 до +10"),
            numCol("Очки", "что дело дало дню: часы × ценность"),
            selCol("Источник", listOf("manual", "auto"), "manual — сказал или отметил сам; auto — телефон, часы или заполнитель неразмеченного"),
            selCol("Записано", RECORDED, "как именно строка попала в ленту"),
            numCol("Помидоры"),
            monthCol(),
            weekCol(),
            textCol("Надиктовано", "голос Саши как есть, с ошибками распознавания"),
            textCol("EntryId", "ключ синхронизатора"),
        ),
        retired = listOf(
            "Домен", "Бюджет", "Носитель ID", "Поверх", "Носитель", "Параллели", "Детали",
            "Полезность", "Приём", "Ккал", "Белок", "Жиры", "Углеводы", "Км", "Пульс", "Ватт",
            "Load", "Самочувствие", "Сон ч", "Сон счёт",
        ),
    )

    // ---- Еда ----

    val EDA = Db(
        name = "Еда",
        knownId = "93a2ddd886df43798e8b7b3bd5989e9b",
        description = "Дневник еды: строка на подтверждённый приём с КБЖУ и составом.",
        columns = listOf(
            titleCol("Приём"),
            dateCol("Дата", "когда съедено"),
            dateCol("День", DAY_DESC),
            selCol("Вид", listOf("завтрак", "обед", "ужин", "перекус")),
            numCol("Ккал"),
            numCol("Белок"),
            numCol("Жиры"),
            numCol("Углеводы"),
            numCol("Клетчатка"),
            numCol("Граммы"),
            numCol("Позиций"),
            textCol("Состав", "позиции с граммами и ккал"),
            selCol("Записано", listOf("голос", "текст", "фото", "штрихкод", "рацион")),
            monthCol(),
            textCol("Надиктовано"),
            textCol("Заметка модели", "чего не хватило для точного разбора"),
            textCol("MealId", "ключ синхронизатора"),
        ),
    )

    // ---- Тренировки с часов ----

    val TRENIROVKI = Db(
        name = "Тренировки",
        knownId = "14959c11b0c24f84913458a9c3bd50a8",
        description = "Тренировки из intervals.icu (Garmin): строка на активность.",
        columns = listOf(
            titleCol("Тренировка"),
            dateCol("Дата"),
            dateCol("День", DAY_DESC),
            selCol("Вид", listOf("бег", "вело", "силовая", "ходьба", "прочее")),
            numCol("Минуты", "elapsed с потолком правдоподобия"),
            numCol("В движении мин"),
            numCol("Км"),
            textCol("Темп", "мин/км у бега и ходьбы"),
            textCol("Темп GAP", "темп с поправкой на рельеф"),
            numCol("Пульс"),
            numCol("Пульс макс"),
            numCol("Ватт"),
            numCol("Ватт норм."),
            numCol("Каденс"),
            numCol("Набор м"),
            numCol("Load", "icu_training_load"),
            numCol("Интенсивность", "% от порога"),
            numCol("Калории"),
            numCol("Decoupling %", "Pw:HR, расхождение мощности и пульса"),
            numCol("Efficiency", "efficiency factor"),
            textCol("Зоны", "минуты по пульсовым зонам z1…z7"),
            numCol("Самочувствие", "feel 1–5, где 1 отлично"),
            numCol("RPE", "1–10"),
            monthCol(),
            weekCol(),
            urlCol("Ссылка"),
            textCol("WorkoutId", "ключ синхронизатора"),
        ),
    )

    // ---- Силовые голосом ----

    val SILOVYE = Db(
        name = "Силовые",
        knownId = "d404dbc567a446a19eb9c513790b760f",
        description = "Журнал силовых, надиктованный владельцу: строка на сессию с подходами.",
        columns = listOf(
            titleCol("Сессия"),
            dateCol("Дата"),
            dateCol("День", DAY_DESC),
            textCol("Блок", "блок плана: «A · дом», «Турник»"),
            numCol("Минуты"),
            numCol("Самочувствие", "1–5, где 1 отлично"),
            checkCol("Сделано", "владелец закрыл сессию"),
            numCol("Упражнений"),
            textCol("Упражнения", "подходы: «Гоблет 3×12 @16; Свинги 2×15 @16»"),
            monthCol(),
            textCol("Надиктовано"),
            textCol("SessionId", "ключ синхронизатора"),
        ),
    )

    // ---- Зарядка (GTG) ----

    val ZARYADKA = Db(
        name = "Зарядка",
        knownId = "6d24bc844f9345d5a7a4f9dabe776dbb",
        description = "Зарядка и GTG-цепочка: строка на день.",
        columns = listOf(
            titleCol("Зарядка"),
            dateCol("Дата"),
            dateCol("День", DAY_DESC),
            selCol("Статус", listOf("выполнена", "частично", "пропущена")),
            checkCol("Сделана"),
            numCol("Вис сек"),
            numCol("Негативы"),
            numCol("Лопаточные"),
            numCol("Подтягивания"),
            selCol("Колено", listOf("зелёный", "жёлтый", "красный")),
            numCol("Самочувствие", "1–5, где 1 отлично"),
            textCol("Пункты", "отчёт по пунктам плана"),
            monthCol(),
            textCol("Заметка"),
            textCol("GtgId", "ключ синхронизатора"),
        ),
    )

    // ---- Категории: единственная справочная таблица ----

    val KATEGORII = Db(
        name = "Категории",
        knownId = "5efe26af34ed476abb293aa598bb0951",
        description = "Категории ленты как они настроены в приложении: подсказка, ценность часа (из неё считаются очки), базовое время.",
        columns = listOf(
            titleCol("Категория"),
            textCol("Подсказка", "что владелец относит к этой категории"),
            numCol("Ценность часа", "от −10 до +10; очки дела = часы × ценность"),
            numCol("Базовое время мин", "типичная длительность дела категории"),
            numCol("Порядок"),
            textCol("Ключ", "ключ синхронизатора"),
        ),
        retired = listOf("Раздел", "Значение", "Единица", "Включено", "Пакет", "Обновлено"),
    )

    // ---- Телефон: суточные суммы статистики использования ----

    val TELEFON = Db(
        name = "Телефон",
        knownId = "b717035255364198bc563854f1dc3834",
        description = "Телефон по дням из статистики использования и журнала звонков: YouTube, Telegram, Claude, экран, подъёмы, отвлечения, звонки. Это минуты телефона, не записи ленты.",
        columns = listOf(
            titleCol("День"),
            dateCol("Дата"),
            monthCol(),
            weekCol(),
            numCol("YouTube", "минуты экрана"),
            numCol("Telegram", "минуты экрана"),
            numCol("Claude", "минуты экрана"),
            numCol("Слушалка", "минуты фоновой службы: книга в наушниках"),
            numCol("Экран мин", "всё экранное время за сутки"),
            numCol("Подъёмов", "сколько раз брал телефон"),
            numCol("Отвлечений", "взял и убрал быстрее двух минут"),
            numCol("Звонки мин", "разговоры ≥ 1 мин"),
            numCol("Звонков"),
            textCol("Звонки с", "собеседники по журналу"),
            textCol("Приложения", "все отмеченные приложения с минутами"),
            textCol("Ключ", "ключ синхронизатора"),
        ),
    )

    // ---- Форма: wellness intervals.icu по дням ----

    val FORMA = Db(
        name = "Форма",
        knownId = "e0dad71080344c098ac215fcb6b0eac9",
        description = "Тренированность и самочувствие по дням из intervals.icu (Garmin): CTL, ATL, TSB, пульс покоя, HRV, сон, вес, шаги.",
        columns = listOf(
            titleCol("День"),
            dateCol("Дата"),
            monthCol(),
            weekCol(),
            numCol("CTL", "тренированность (fitness)"),
            numCol("ATL", "усталость (fatigue)"),
            numCol("TSB", "форма = CTL − ATL"),
            numCol("Пульс покоя"),
            numCol("HRV"),
            numCol("Сон ч"),
            numCol("Сон счёт"),
            numCol("Качество сна", "1–4"),
            numCol("Шаги"),
            numCol("Вес кг"),
            numCol("VO2max"),
            numCol("Готовность"),
            numCol("Ккал съедено", "из дневника еды, если уехало в wellness"),
            numCol("Белок съедено"),
            textCol("Комментарий", "заметка дня в intervals"),
            textCol("Ключ", "ключ синхронизатора"),
        ),
    )

    val ALL: List<Db> = listOf(ZASECHKA, EDA, TRENIROVKI, SILOVYE, ZARYADKA, TELEFON, FORMA, KATEGORII)

    fun byName(name: String): Db? = ALL.firstOrNull { it.name == name }

    // ---- Схема → JSON Notion ----

    /** Описание одного свойства для create/update database. */
    fun propertyJson(c: Column): JSONObject = JSONObject().apply {
        when (c.type) {
            "title" -> put("title", JSONObject())
            "rich_text" -> put("rich_text", JSONObject())
            "number" -> put("number", JSONObject().put("format", "number"))
            "checkbox" -> put("checkbox", JSONObject())
            "date" -> put("date", JSONObject())
            "url" -> put("url", JSONObject())
            "select" -> put(
                "select",
                JSONObject().put(
                    "options",
                    JSONArray().apply { c.options.forEach { put(JSONObject().put("name", it.take(100))) } },
                ),
            )
        }
        if (c.description.isNotBlank()) put("description", c.description.take(500))
    }

    /** Тело POST /v1/databases для новой базы под хабом. */
    fun createBody(db: Db, hubPageId: String): JSONObject = JSONObject().apply {
        put("parent", JSONObject().put("type", "page_id").put("page_id", hubPageId))
        put("title", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", db.name))))
        put("description", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", db.description))))
        put("properties", JSONObject().apply { db.columns.forEach { put(it.name, propertyJson(it)) } })
    }

    // ---- Кирпичи значений ----

    private val dateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val WEEKDAYS = listOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")

    fun title(text: String) = JSONObject().put("title", textArray(text))
    fun rich(text: String) = JSONObject().put("rich_text", textArray(text))
    fun select(name: String) = JSONObject().put("select", JSONObject().put("name", name.take(100)))
    fun number(v: Number) = JSONObject().put("number", v)
    fun checkbox(on: Boolean) = JSONObject().put("checkbox", on)
    fun link(u: String) = JSONObject().put("url", if (u.isBlank()) JSONObject.NULL else u)
    fun dateRange(start: Long, end: Long) = JSONObject().put(
        "date", JSONObject().put("start", dateTime.format(Date(start))).put("end", dateTime.format(Date(end))),
    )
    fun dateSingle(ms: Long) = JSONObject().put("date", JSONObject().put("start", dateTime.format(Date(ms))))
    fun dateDay(date: String) = JSONObject().put("date", JSONObject().put("start", date))

    private fun textArray(text: String): JSONArray =
        if (text.isBlank()) JSONArray()
        else JSONArray().put(JSONObject().put("text", JSONObject().put("content", text.take(1900))))

    /** «2026-09-05» для момента времени — сутки владельца. */
    fun dayKey(ms: Long): String = iso.format(Date(ms))

    /** «2026-09» — месяц суток, для группировок Notion. */
    fun monthKey(date: String): String = date.take(7)

    /** «2026-W36» — ISO-неделя (понедельник первый, ≥4 дня в году). */
    fun weekKey(date: String): String {
        val cal = Calendar.getInstance(Locale.GERMANY).apply { timeInMillis = dayStartOf(date) }
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.minimalDaysInFirstWeek = 4
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        // Неделя 1 в конце декабря и неделя 52/53 в начале января — год берётся у недели.
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR) + (if (week == 1 && month == Calendar.DECEMBER) 1 else if (week >= 52 && month == Calendar.JANUARY) -1 else 0)
        return String.format(Locale.US, "%d-W%02d", year, week)
    }

    fun dayStartOf(date: String): Long = runCatching {
        iso.parse(date)?.let { midnight(it.time) }
    }.getOrNull() ?: midnight(System.currentTimeMillis())

    private fun midnight(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun weekday(date: String): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStartOf(date) }
        return WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    /** «05.09 · пт» — заголовок дня, как владелец пишет сам. */
    fun humanDay(date: String): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStartOf(date) }
        val dd = String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))
        val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
        return "$dd.$mm · ${weekday(date)}"
    }

    fun clock(ms: Long): String = hm.format(Date(ms))

    /** «5:53» из секунд на километр. */
    fun pace(secPerKm: Int): String =
        if (secPerKm <= 0) "" else "${secPerKm / 60}:" + String.format(Locale.US, "%02d", secPerKm % 60)

    // ---- Слова для полей ----

    /** Как записано — из машинного source ленты. */
    fun recorded(source: String): String = when (source) {
        "voice" -> "голос"
        "text" -> "текст"
        "edit" -> "правка руками"
        "todoist" -> "из Todoist"
        "nfc" -> "метка NFC"
        "autopilot" -> "автопилот"
        "auto" -> "авто: телефон или часы"
        "gap" -> "заполнитель"
        else -> "голос"
    }

    fun sourceKind(source: String): String = if (source == "auto" || source == "gap") "auto" else "manual"

    fun mealRecorded(source: String): String = when (source) {
        "text" -> "текст"
        "photo" -> "фото"
        "barcode" -> "штрихкод"
        "ration" -> "рацион"
        else -> "голос"
    }

    /** Вид тренировки словом; категория ленты для того же — в [sportCategory]. */
    fun sportKind(type: String): String = when (type) {
        "Run", "TrailRun", "VirtualRun" -> "бег"
        "Ride", "VirtualRide", "GravelRide", "MountainBikeRide" -> "вело"
        "WeightTraining" -> "силовая"
        "Walk", "Hike" -> "ходьба"
        else -> "прочее"
    }

    fun sportCategory(type: String): String = when (sportKind(type)) {
        "бег" -> "Спорт: бег"
        "вело" -> "Спорт: вело"
        "силовая" -> "Спорт: силовая"
        "ходьба" -> "Передвижение: пешком"
        else -> "Спорт: прочее"
    }

    private fun cap(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }

    // ---- Построители строк ----

    /**
     * Строка ленты. [minutes] — разность минут суток, считает лента
     * (`ZasechkaStore.budgetMinutes`); [worth] — ценность часа категории.
     */
    fun ribbonRow(e: ZasechkaStore.Entry, minutes: Long, worth: Int, now: Long): JSONObject = JSONObject().apply {
        val end = if (e.open) now else e.end
        put("Дело", title(e.title.ifBlank { e.category.ifBlank { "без названия" } }))
        put("Дата", dateRange(e.start, end))
        put("День", dateDay(dayKey(e.start)))
        put("Минуты", number(minutes))
        if (e.category.isNotBlank()) put("Категория", select(e.category))
        put("Клиент", rich(e.client))
        put("Ценность часа", number(worth))
        put("Очки", number(Math.round(worth * e.durationMs(now) / 3_600_000.0 * 10.0) / 10.0))
        put("Источник", select(sourceKind(e.source)))
        put("Записано", select(recorded(e.source)))
        put("Помидоры", number(e.pomodoros))
        val day = dayKey(e.start)
        put("Месяц", select(monthKey(day)))
        put("Неделя", select(weekKey(day)))
        put("Надиктовано", rich(e.raw.substringBefore("\nКБЖУ:").trim()))
        put("EntryId", rich("t${e.id}"))
    }

    fun mealRow(m: FoodStore.Meal): JSONObject = JSONObject().apply {
        val kind = m.kind.trim().lowercase()
        put("Приём", title(cap(kind.ifBlank { "приём" }) + (if (m.shortList.isNotBlank()) " · ${m.shortList}" else "")))
        put("Дата", dateSingle(m.ts))
        put("День", dateDay(dayKey(m.ts)))
        if (kind in setOf("завтрак", "обед", "ужин", "перекус")) put("Вид", select(kind))
        put("Ккал", number(m.kcal))
        put("Белок", number(m.protein))
        put("Жиры", number(m.fat))
        put("Углеводы", number(m.carbs))
        put("Клетчатка", number(m.fiber))
        put("Граммы", number(m.grams))
        put("Позиций", number(m.items.size))
        put(
            "Состав",
            rich(
                m.items.joinToString("; ") { i ->
                    "${i.name} ${i.grams} г · ${i.kcal} ккал" +
                        (if (i.sureness.isNotBlank()) " (${i.sureness})" else "")
                },
            ),
        )
        put("Записано", select(mealRecorded(m.source)))
        put("Месяц", select(monthKey(dayKey(m.ts))))
        put("Надиктовано", rich(m.raw))
        put("Заметка модели", rich(m.note))
        put("MealId", rich("f${m.id}"))
    }

    fun workoutRow(w: SportStore.Workout): JSONObject = JSONObject().apply {
        val kindName = SportCoach.sportName(w.type)
        val name = kindName + (if (w.name.isNotBlank() && !w.name.equals(w.type, true) && !w.name.equals(kindName, true)) " · ${w.name}" else "")
        put("Тренировка", title(name))
        put("Дата", dateRange(w.start, w.start + w.seconds * 1000L))
        put("День", dateDay(dayKey(w.start)))
        put("Вид", select(sportKind(w.type)))
        put("Минуты", number(w.minutes))
        if (w.movingSeconds > 0) put("В движении мин", number((w.movingSeconds + 30) / 60))
        if (w.km >= 0.1) put("Км", number(Math.round(w.km * 10.0) / 10.0))
        if (w.paceSecPerKm > 0) put("Темп", rich(pace(w.paceSecPerKm)))
        if (w.gapSecPerKm > 0) put("Темп GAP", rich(pace(w.gapSecPerKm)))
        if (w.avgHr > 0) put("Пульс", number(w.avgHr))
        if (w.maxHr > 0) put("Пульс макс", number(w.maxHr))
        if (w.avgWatts > 0) put("Ватт", number(w.avgWatts))
        if (w.normWatts > 0) put("Ватт норм.", number(w.normWatts))
        if (w.cadence > 0) put("Каденс", number(w.cadence))
        if (w.elevationM > 0) put("Набор м", number(Math.round(w.elevationM)))
        if (w.load > 0) put("Load", number(w.load))
        if (w.intensity > 0) put("Интенсивность", number(w.intensity))
        if (w.calories > 0) put("Калории", number(w.calories))
        if (w.decoupling != 0.0) put("Decoupling %", number(Math.round(w.decoupling * 10.0) / 10.0))
        if (w.efficiency > 0) put("Efficiency", number(Math.round(w.efficiency * 100.0) / 100.0))
        if (w.zoneMinutes.any { it > 0 }) {
            put("Зоны", rich(w.zoneMinutes.mapIndexed { i, m -> "z${i + 1} $m" }.filter { !it.endsWith(" 0") }.joinToString(" · ")))
        }
        if (w.feel > 0) put("Самочувствие", number(w.feel))
        if (w.rpe > 0) put("RPE", number(w.rpe))
        val day = dayKey(w.start)
        put("Месяц", select(monthKey(day)))
        put("Неделя", select(weekKey(day)))
        if (w.icuUrl.isNotBlank()) put("Ссылка", link(w.icuUrl))
        put("WorkoutId", rich("w${w.id.ifBlank { w.start.toString() }}"))
    }

    fun sessionRow(s: StrengthStore.Session): JSONObject = JSONObject().apply {
        put("Сессия", title(s.title.ifBlank { s.block.ifBlank { "силовая" } }))
        put("Дата", dateDay(s.date))
        put("День", dateDay(s.date))
        put("Блок", rich(s.block))
        if (s.minutes > 0) put("Минуты", number(s.minutes))
        if (s.feel in 1..5) put("Самочувствие", number(s.feel))
        put("Сделано", checkbox(s.done))
        put("Упражнений", number(s.exercises.size))
        put("Упражнения", rich(s.exercises.joinToString("; ") { "${it.name} ${it.compact()}" }))
        put("Месяц", select(monthKey(s.date)))
        put("Надиктовано", rich(s.note))
        put("SessionId", rich("s${s.date}"))
    }

    fun gtgRow(g: StrengthStore.GtgDay): JSONObject = JSONObject().apply {
        put("Зарядка", title("${humanDay(g.date)} · ${g.status()}"))
        put("Дата", dateDay(g.date))
        put("День", dateDay(g.date))
        put("Статус", select(g.status()))
        put("Сделана", checkbox(g.charged))
        put("Вис сек", number(g.hangSec))
        put("Негативы", number(g.negatives))
        put("Лопаточные", number(g.scapular))
        put("Подтягивания", number(g.pullups))
        if (g.knee.isNotBlank()) put("Колено", select(g.knee.trim().lowercase()))
        if (g.feel in 1..5) put("Самочувствие", number(g.feel))
        put("Пункты", rich(g.items.joinToString("; ") { it.brief() }))
        put("Месяц", select(monthKey(g.date)))
        put("Заметка", rich(g.note))
        put("GtgId", rich("g${g.date}"))
    }

    /** Строка категории ленты в «Категориях». */
    fun categoryRow(c: ZasechkaStore.Category, order: Int): JSONObject = JSONObject().apply {
        put("Категория", title(c.name))
        put("Подсказка", rich(c.hint))
        put("Ценность часа", number(c.value))
        put("Базовое время мин", number(c.baseMin))
        put("Порядок", number(order))
        put("Ключ", rich("cat:" + c.name.trim().lowercase()))
    }

    /** Телефон за сутки — то, что считается по дням вместо параллельного трека. */
    data class PhoneDay(
        val screenMin: Long = 0,
        val pickups: Int = 0,
        val glances: Int = 0,
        val youtubeMin: Long = 0,
        val telegramMin: Long = 0,
        val claudeMin: Long = 0,
        val slushalkaMin: Long = 0,
        val callsMin: Long = 0,
        val calls: Int = 0,
        val callers: String = "",
        /** «Claude 70 м · YouTube 47 м · …» — все отмеченные приложения. */
        val apps: String = "",
    ) {
        val any: Boolean get() = screenMin > 0 || pickups > 0 || callsMin > 0 || calls > 0 || apps.isNotBlank()
    }

    /** Строка «Телефона»: сутки телефона. null — за день телефон ничего не видел. */
    fun phoneRow(date: String, p: PhoneDay): JSONObject? {
        if (!p.any) return null
        return JSONObject().apply {
            put("День", title(humanDay(date)))
            put("Дата", dateDay(date))
            put("Месяц", select(monthKey(date)))
            put("Неделя", select(weekKey(date)))
            put("YouTube", number(p.youtubeMin))
            put("Telegram", number(p.telegramMin))
            put("Claude", number(p.claudeMin))
            put("Слушалка", number(p.slushalkaMin))
            put("Экран мин", number(p.screenMin))
            put("Подъёмов", number(p.pickups))
            put("Отвлечений", number(p.glances))
            put("Звонки мин", number(p.callsMin))
            put("Звонков", number(p.calls))
            put("Звонки с", rich(p.callers))
            put("Приложения", rich(p.apps))
            put("Ключ", rich("p$date"))
        }
    }

    /** Строка «Формы»: wellness intervals за сутки. null — пустой день. */
    fun healthRow(h: SportStore.Health): JSONObject? {
        val any = h.ctl > 0 || h.atl > 0 || h.restingHr > 0 || h.hrv > 0 || h.sleepHours > 0 ||
            h.steps > 0 || h.weightKg > 0 || h.vo2max > 0 || h.readiness > 0
        if (!any) return null
        return JSONObject().apply {
            put("День", title(humanDay(h.date)))
            put("Дата", dateDay(h.date))
            put("Месяц", select(monthKey(h.date)))
            put("Неделя", select(weekKey(h.date)))
            if (h.ctl > 0) put("CTL", number(Math.round(h.ctl * 10.0) / 10.0))
            if (h.atl > 0) put("ATL", number(Math.round(h.atl * 10.0) / 10.0))
            if (h.ctl > 0 || h.atl > 0) put("TSB", number(Math.round(h.tsb * 10.0) / 10.0))
            if (h.restingHr > 0) put("Пульс покоя", number(h.restingHr))
            if (h.hrv > 0) put("HRV", number(h.hrv))
            if (h.sleepHours > 0) put("Сон ч", number(Math.round(h.sleepHours * 10.0) / 10.0))
            if (h.sleepScore > 0) put("Сон счёт", number(h.sleepScore))
            if (h.sleepQuality > 0) put("Качество сна", number(h.sleepQuality))
            if (h.steps > 0) put("Шаги", number(h.steps))
            if (h.weightKg > 0) put("Вес кг", number(Math.round(h.weightKg * 10.0) / 10.0))
            if (h.vo2max > 0) put("VO2max", number(Math.round(h.vo2max * 10.0) / 10.0))
            if (h.readiness > 0) put("Готовность", number(h.readiness))
            if (h.kcal > 0) put("Ккал съедено", number(h.kcal))
            if (h.protein > 0) put("Белок съедено", number(h.protein))
            put("Комментарий", rich(h.comments))
            put("Ключ", rich("h${h.date}"))
        }
    }
}
