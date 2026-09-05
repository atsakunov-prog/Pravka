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
 * заполнено пять, читаются как свалка. Теперь у каждого домена своя база со
 * своими колонками, а «Дни» связывают их суточными итогами.
 *
 * Правила, которые здесь держатся:
 * - строка «Дней» несёт и Сашины поля («Дети дома», «Марианна дома днём»,
 *   «Якорь утра», «Заметка дня») — их приложение НЕ пишет никогда, поэтому их
 *   и нет в [DNI]: чего нет в схеме, то построитель не тронет;
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
    const val VERSION = 2

    /** Полный день по правилу хаба: покрытие основного трека ≥ 1370 минут. */
    const val FULL_DAY_MIN = 1370L

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
            textCol("Надиктовано", "голос Саши как есть, с ошибками распознавания"),
            textCol("EntryId", "ключ синхронизатора"),
        ),
        retired = listOf(
            "Домен", "Бюджет", "Носитель ID", "Поверх", "Носитель", "Параллели", "Детали",
            "Полезность", "Приём", "Ккал", "Белок", "Жиры", "Углеводы", "Км", "Пульс", "Ватт",
            "Load", "Самочувствие", "Сон ч", "Сон счёт",
        ),
    )

    // ---- Дни: сутки одной строкой ----

    val DNI = Db(
        name = "Дни",
        knownId = "39a031d4ac724ed28d4876e79319e202",
        description = "Строка на сутки: итоги ленты, телефона, сна, еды и тела. Поля «Дети дома», «Марианна дома днём», «Якорь утра», «Заметка дня» — Сашины, приложение их не трогает.",
        columns = listOf(
            titleCol("День"),
            dateCol("Дата"),
            selCol("День недели", listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")),
            numCol("Покрытие мин", "сумма минут ленты за сутки"),
            checkCol("Полный день", "покрытие ≥ 1370 мин"),
            numCol("Балл дня", "сумма очков всех дел: часы × ценность часа"),
            numCol("Записей", "дел в ленте за сутки, без заполнителя"),
            numCol("Работа", "строго минуты категорий «Работа: *»"),
            numCol("Систематизация"),
            numCol("Семья"),
            numCol("Социальное"),
            numCol("Быт"),
            numCol("Еда мин"),
            numCol("Дорога"),
            numCol("Спорт"),
            numCol("Чтение"),
            numCol("Отдых"),
            numCol("Потери"),
            numCol("Не размечено"),
            numCol("Сон мин", "ночной сон цепочкой через полночь, тот, что кончился в этот день"),
            textCol("Отбой", "HH:MM начала ночного сна"),
            textCol("Подъём", "HH:MM конца ночного сна"),
            numCol("Первое действие через", "минут от подъёма до первого дела, которое не сон, не «не размечено», не потери, не еда и без слов «туалет»/«телефон»"),
            numCol("Туалет", "минуты дел со словом «туалет»"),
            numCol("Диван", "минуты дел со словом «диван»"),
            numCol("Соло", "минуты категории «Секс: соло»"),
            numCol("Контактов", "рабочих звонков и дел с названным клиентом"),
            numCol("YouTube", "минуты экрана по статистике телефона"),
            numCol("Telegram", "минуты экрана по статистике телефона"),
            numCol("Claude", "минуты экрана по статистике телефона"),
            numCol("Экран мин", "всё экранное время за сутки"),
            numCol("Подъёмов", "сколько раз брал телефон"),
            numCol("Отвлечений", "взял и убрал быстрее двух минут"),
            numCol("Звонки мин", "разговоры ≥ 1 мин по журналу звонков"),
            numCol("Звонков"),
            textCol("Звонки с", "с кем говорил, по журналу звонков"),
            numCol("Ккал"),
            numCol("Белок"),
            numCol("Жиры"),
            numCol("Углеводы"),
            numCol("Приёмов"),
            numCol("Тренировок", "тренировок с часов за сутки"),
            numCol("Тренировки мин"),
            checkCol("Силовая", "записана силовая голосом"),
            selCol("Зарядка", listOf("выполнена", "частично", "пропущена")),
            textCol("Заметки тела", "надиктованные комментарии к тренировкам за день, со временем"),
            numCol("Вес кг"),
            numCol("Пульс покоя"),
            numCol("HRV"),
            numCol("Шаги"),
            numCol("Сон Garmin ч"),
            numCol("Сон счёт"),
        ),
        retired = listOf("Экран параллель"),
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
            numCol("Км"),
            textCol("Темп", "мин/км у бега и ходьбы"),
            numCol("Пульс"),
            numCol("Пульс макс"),
            numCol("Ватт"),
            numCol("Ватт норм."),
            numCol("Каденс"),
            numCol("Набор м"),
            numCol("Load", "icu_training_load"),
            numCol("Интенсивность", "% от порога"),
            numCol("Калории"),
            numCol("Самочувствие", "feel 1–5, где 1 отлично"),
            numCol("RPE", "1–10"),
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
            textCol("Заметка"),
            textCol("GtgId", "ключ синхронизатора"),
        ),
    )

    // ---- Справочник: структура, а не события ----

    val SPRAVOCHNIK = Db(
        name = "Справочник",
        knownId = "5efe26af34ed476abb293aa598bb0951",
        description = "Структура жизни как она настроена в приложении: категории ленты с ценностью часа, приложения, которые считаются по дням, цели питания и состояние синхронизации. Обновляется само.",
        columns = listOf(
            titleCol("Название"),
            selCol("Раздел", listOf("Категория ленты", "Приложение", "Цель питания", "Синк")),
            textCol("Подсказка", "у категории — что сюда относится; у синка — состояние словами"),
            numCol("Ценность часа", "от −10 до +10"),
            numCol("Базовое время мин", "типичная длительность дела категории"),
            numCol("Значение", "у цели — норма в день; у приложения — минуты за сегодня"),
            textCol("Единица"),
            numCol("Порядок"),
            checkCol("Включено"),
            textCol("Пакет", "имя пакета Android у приложения"),
            dateCol("Обновлено"),
            textCol("Ключ", "ключ синхронизатора"),
        ),
    )

    val ALL: List<Db> = listOf(ZASECHKA, DNI, EDA, TRENIROVKI, SILOVYE, ZARYADKA, SPRAVOCHNIK)

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
            rich(m.items.joinToString("; ") { i -> "${i.name} ${i.grams} г · ${i.kcal} ккал" }),
        )
        put("Записано", select(mealRecorded(m.source)))
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
        if (w.km >= 0.1) put("Км", number(Math.round(w.km * 10.0) / 10.0))
        if (w.paceSecPerKm > 0) put("Темп", rich(pace(w.paceSecPerKm)))
        if (w.avgHr > 0) put("Пульс", number(w.avgHr))
        if (w.maxHr > 0) put("Пульс макс", number(w.maxHr))
        if (w.avgWatts > 0) put("Ватт", number(w.avgWatts))
        if (w.normWatts > 0) put("Ватт норм.", number(w.normWatts))
        if (w.cadence > 0) put("Каденс", number(w.cadence))
        if (w.elevationM > 0) put("Набор м", number(Math.round(w.elevationM)))
        if (w.load > 0) put("Load", number(w.load))
        if (w.intensity > 0) put("Интенсивность", number(w.intensity))
        if (w.calories > 0) put("Калории", number(w.calories))
        if (w.feel > 0) put("Самочувствие", number(w.feel))
        if (w.rpe > 0) put("RPE", number(w.rpe))
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
        put("Заметка", rich(g.note))
        put("GtgId", rich("g${g.date}"))
    }

    /** Строка категории ленты в «Справочнике». */
    fun categoryRow(c: ZasechkaStore.Category, order: Int, now: Long): JSONObject = JSONObject().apply {
        put("Название", title(c.name))
        put("Раздел", select("Категория ленты"))
        put("Подсказка", rich(c.hint))
        put("Ценность часа", number(c.value))
        put("Базовое время мин", number(c.baseMin))
        put("Порядок", number(order))
        put("Включено", checkbox(true))
        put("Обновлено", dateSingle(now))
        put("Ключ", rich("cat:" + c.name.trim().lowercase()))
    }

    /** Приложение, которое считается по дням: пакет, категория-подсказка, минуты сегодня. */
    fun appRow(pkg: String, label: String, category: String, tracked: Boolean, todayMin: Long, now: Long): JSONObject =
        JSONObject().apply {
            put("Название", title(label.ifBlank { pkg.substringAfterLast('.') }))
            put("Раздел", select("Приложение"))
            put("Подсказка", rich(if (category.isBlank()) "" else "категория: $category"))
            put("Значение", number(todayMin))
            put("Единица", rich("мин сегодня"))
            put("Включено", checkbox(tracked))
            put("Пакет", rich(pkg))
            put("Обновлено", dateSingle(now))
            put("Ключ", rich("app:$pkg"))
        }

    fun targetRow(name: String, key: String, value: Int, unit: String, order: Int, now: Long): JSONObject = JSONObject().apply {
        put("Название", title(name))
        put("Раздел", select("Цель питания"))
        put("Значение", number(value))
        put("Единица", rich(unit))
        put("Порядок", number(order))
        put("Включено", checkbox(true))
        put("Обновлено", dateSingle(now))
        put("Ключ", rich("target:$key"))
    }

    /** Состояние синка словами — чтобы молчаливая механика не читалась как поломка. */
    fun statusRow(state: String, now: Long): JSONObject = JSONObject().apply {
        put("Название", title("Синк Правки"))
        put("Раздел", select("Синк"))
        put("Подсказка", rich(state))
        put("Обновлено", dateSingle(now))
        put("Ключ", rich("sync:status"))
    }

    /** Что идёт прямо сейчас — единственная «живая» строка Notion. */
    fun nowRow(open: ZasechkaStore.Entry?, now: Long): JSONObject = JSONObject().apply {
        put("Название", title(if (open == null) "Сейчас: ничего не идёт" else "Сейчас: ${open.title.ifBlank { "без названия" }}"))
        put("Раздел", select("Синк"))
        put(
            "Подсказка",
            rich(
                if (open == null) ""
                else listOfNotNull(open.category.takeIf { it.isNotBlank() }, "с ${clock(open.start)}").joinToString(" · "),
            ),
        )
        put("Обновлено", dateSingle(now))
        put("Ключ", rich("sync:now"))
    }

    // ---- Дни ----

    /** Телефон за сутки — то, что считается по дням вместо параллельного трека. */
    data class PhoneDay(
        val screenMin: Long = 0,
        val pickups: Int = 0,
        val glances: Int = 0,
        val youtubeMin: Long = 0,
        val telegramMin: Long = 0,
        val claudeMin: Long = 0,
        val callsMin: Long = 0,
        val calls: Int = 0,
        val callers: String = "",
    ) {
        val any: Boolean get() = screenMin > 0 || pickups > 0 || callsMin > 0 || calls > 0
    }

    /** Тело за сутки: что приехало с часов, что надиктовано. */
    data class BodyDay(
        val workouts: Int = 0,
        val workoutMin: Long = 0,
        val strength: Boolean = false,
        val gtgStatus: String = "",
        val notes: String = "",
        val weightKg: Double = 0.0,
        val restingHr: Int = 0,
        val hrv: Int = 0,
        val steps: Int = 0,
        val sleepHours: Double = 0.0,
        val sleepScore: Int = 0,
    )

    /**
     * Сутки одной строкой. Возвращает null, когда за день нет ничего — ни
     * минуты ленты, ни приёма еды, ни телефона: строка из одних нулей — мусор
     * в базе разбора (нашёл разбор: «мусорная строка 19 августа»).
     *
     * [budgetOf] — минуты записи разностью минут суток (как в ленте), [worthOf]
     * — ценность часа категории. Всё остальное считается здесь, без Android.
     */
    fun dayRow(
        date: String,
        main: List<ZasechkaStore.Entry>,
        allClosed: List<ZasechkaStore.Entry>,
        meals: List<FoodStore.Meal>,
        phone: PhoneDay,
        body: BodyDay,
        budgetOf: (ZasechkaStore.Entry) -> Long,
        worthOf: (String) -> Int,
        now: Long,
    ): JSONObject? {
        if (main.isEmpty() && meals.isEmpty() && !phone.any && body.workouts == 0) return null
        val start = dayStartOf(date)
        val end = start + 86_400_000L
        fun mins(pred: (ZasechkaStore.Entry) -> Boolean): Long = main.filter(pred).sumOf(budgetOf)
        fun cat(prefix: String) = mins { it.category.startsWith(prefix, ignoreCase = true) }
        fun titled(vararg words: String) = mins { e -> words.any { e.title.contains(it, ignoreCase = true) } }

        val coverage = main.sumOf(budgetOf)
        val p = JSONObject()
        p.put("День", title(humanDay(date)))
        p.put("Дата", dateDay(date))
        p.put("День недели", select(weekday(date)))
        p.put("Покрытие мин", number(coverage))
        p.put("Полный день", checkbox(coverage >= FULL_DAY_MIN))
        val points = main.sumOf { worthOf(it.category) * it.durationMs(now) / 3_600_000.0 }
        p.put("Балл дня", number(Math.round(points)))
        p.put("Записей", number(main.count { it.source != "gap" }))
        p.put("Работа", number(cat("Работа")))
        p.put("Систематизация", number(cat("Систематизация")))
        p.put("Семья", number(cat("Семья")))
        p.put("Социальное", number(cat("Социальное")))
        p.put("Быт", number(cat("Быт")))
        p.put("Еда мин", number(cat("Еда")))
        p.put("Дорога", number(cat("Передвижение")))
        p.put("Спорт", number(cat("Спорт")))
        p.put("Чтение", number(cat("Чтение")))
        p.put("Отдых", number(cat("Отдых")))
        p.put("Потери", number(cat("Потери")))
        p.put("Не размечено", number(cat("Не размечено")))
        p.put("Туалет", number(titled("туалет")))
        p.put("Диван", number(titled("диван")))
        p.put("Соло", number(mins { it.category.equals("Секс: соло", ignoreCase = true) }))
        p.put(
            "Контактов",
            number(
                main.count {
                    it.category.equals("Работа: звонки", ignoreCase = true) ||
                        (it.category.startsWith("Работа", ignoreCase = true) && it.client.isNotBlank())
                },
            ),
        )

        // Ночной сон — тот, что КОНЧИЛСЯ в этот день; цепочка тянется назад
        // через полночь, потому что лента режет каждую ночь на два куска.
        val wake = allClosed
            .filter { it.category.equals("Сон", ignoreCase = true) && it.end in start until end && it.end - start < 14 * 3_600_000L }
            .maxByOrNull { it.end }
        if (wake != null) {
            var head: ZasechkaStore.Entry = wake
            var total = 0L
            var guard = 0
            while (guard++ < 6) {
                total += head.durationMin(now)
                val prev = allClosed.firstOrNull {
                    it.category.equals("Сон", ignoreCase = true) && kotlin.math.abs(it.end - head.start) < 60_000L
                } ?: break
                head = prev
            }
            p.put("Сон мин", number(total))
            p.put("Отбой", rich(clock(head.start)))
            p.put("Подъём", rich(clock(wake.end)))
            val first = main
                .filter { it.start >= wake.end }
                .sortedBy { it.start }
                .firstOrNull { e ->
                    val c = e.category.lowercase()
                    !(c == "сон" || c == "не размечено" || c == "потери" || c == "еда") &&
                        !e.title.contains("туалет", ignoreCase = true) &&
                        !e.title.contains("телефон", ignoreCase = true)
                }
            if (first != null) p.put("Первое действие через", number((first.start - wake.end) / 60_000L))
        }

        // Телефон по дням — то, ради чего убран параллельный трек: «просто
        // давай считать каждый день, сколько на Клод, телеграм, звонки, ютуб».
        p.put("YouTube", number(phone.youtubeMin))
        p.put("Telegram", number(phone.telegramMin))
        p.put("Claude", number(phone.claudeMin))
        p.put("Экран мин", number(phone.screenMin))
        p.put("Подъёмов", number(phone.pickups))
        p.put("Отвлечений", number(phone.glances))
        p.put("Звонки мин", number(phone.callsMin))
        p.put("Звонков", number(phone.calls))
        p.put("Звонки с", rich(phone.callers))

        if (meals.isNotEmpty()) {
            p.put("Ккал", number(meals.sumOf { it.kcal }))
            p.put("Белок", number(meals.sumOf { it.protein }))
            p.put("Жиры", number(meals.sumOf { it.fat }))
            p.put("Углеводы", number(meals.sumOf { it.carbs }))
            p.put("Приёмов", number(meals.size))
        }

        p.put("Тренировок", number(body.workouts))
        p.put("Тренировки мин", number(body.workoutMin))
        p.put("Силовая", checkbox(body.strength))
        if (body.gtgStatus.isNotBlank()) p.put("Зарядка", select(body.gtgStatus))
        p.put("Заметки тела", rich(body.notes))
        if (body.weightKg > 0) p.put("Вес кг", number(Math.round(body.weightKg * 10.0) / 10.0))
        if (body.restingHr > 0) p.put("Пульс покоя", number(body.restingHr))
        if (body.hrv > 0) p.put("HRV", number(body.hrv))
        if (body.steps > 0) p.put("Шаги", number(body.steps))
        if (body.sleepHours > 0) p.put("Сон Garmin ч", number(Math.round(body.sleepHours * 10.0) / 10.0))
        if (body.sleepScore > 0) p.put("Сон счёт", number(body.sleepScore))
        return p
    }
}
