package ru.zf.pravka.data

// Какая модель и с каким усилием работает в каждом месте, где приложение
// ходит в Claude. Раньше это были константы, зашитые в вызовы: Сонет для
// чистки диктовки, Опус для разборов, Fable для сверки паттернов. Владелец:
// «сделай в настройках выбор моделей и выбор усилий, отдельной графой» —
// поэтому каждая дорога названа здесь, а конкретный выбор живёт в DataStore
// (Settings.modelChoice). Заводские значения — ровно те, что были зашиты:
// поведение приложения без единого тапа в настройках не меняется.
//
// Файл без Android-зависимостей: JVM-тесты проверяют каталог и разбор
// сохранённого выбора тем же кодом, что и приложение.

/** Одна дорога к модели: за что отвечает и что стоит по умолчанию. */
enum class ModelRoute(
    /** Ключ в DataStore — не переименовывать, иначе выбор владельца пропадёт. */
    val key: String,
    /** Режим, к которому дорога относится: заголовок группы на экране. */
    val mode: String,
    val title: String,
    val hint: String,
    val defaultModel: String,
    /** Пусто — параметр усилия не передаётся, решает API (сейчас это high). */
    val defaultEffort: String = "",
) {
    PRAVKA(
        "pravka", "Правка", "Чистка диктовки",
        "Кнопка «П», ответы по тексту, поиск словаря по истории. Ходит десятки раз в день.",
        Settings.MODEL_SONNET,
    ),
    PRAVKA_STRONG(
        "pravka_strong", "Правка", "Кнопка «сильнее»",
        "Переделать посильнее: чипы после чистки и пункт меню «П».",
        Settings.MODEL_OPUS,
    ),
    PRAVKA_LEARN(
        "pravka_learn", "Правка", "Обучение",
        "Правила из твоих правок и их сведение в короткий набор.",
        Settings.MODEL_OPUS,
    ),
    ZASECHKA(
        "zasechka", "Засечка", "Разбор фразы",
        "«Созвон с Ивановым, последние полчаса» → запись ленты. Сонет здесь путал ретро-вставки и категории.",
        Settings.MODEL_OPUS,
    ),
    ZASECHKA_RULES(
        "zasechka_rules", "Засечка", "Правила из поправок",
        "Обучение Засечки: кнопкой сразу, ночью батчем.",
        Settings.MODEL_OPUS,
    ),
    RAZNOSKA(
        "raznoska", "Дела", "Разноска",
        "Наговор → дела в Todoist: что вообще дело, у кого мяч, в какой проект.",
        Settings.MODEL_OPUS,
    ),
    BODY(
        "body", "Тело и еда", "Разбор и тренер",
        "Кнопка «Т» (подходы, зарядка, еда, самочувствие), КБЖУ по словам и снимку, вопрос по тренировкам.",
        Settings.MODEL_OPUS,
    ),
    BODY_LIGHT(
        "body_light", "Тело и еда", "Подсказки",
        "Короткий вопрос про упражнение между подходами и правила блока из Notion раз в сутки.",
        Settings.MODEL_SONNET,
    ),
    PATTERNS(
        "patterns", "Разборы", "Поиск паттернов",
        "Ночная охота за повторами: батчем за полцены, кнопкой — сразу.",
        Settings.MODEL_OPUS,
        "high",
    ),
    PATTERNS_DUPES(
        "patterns_dupes", "Разборы", "Сверка паттернов",
        "Тот же это механизм или другой: ошибка склейки дороже её цены, поэтому заводская — самая сильная.",
        Settings.MODEL_FABLE,
        "medium",
    ),
}

/** Выбор владельца для одной дороги: модель и усилие (пусто — по умолчанию API). */
data class ModelChoice(val model: String, val effort: String) {
    fun isDefaultFor(route: ModelRoute): Boolean =
        model == route.defaultModel && effort == route.defaultEffort

    companion object {
        /**
         * Из сохранённого — с защитой: модель, которой в каталоге нет (снятая
         * с API, опечатка старой сборки), возвращается к заводской, а не
         * улетает в запрос и не ловит 404 на каждой диктовке.
         */
        fun of(route: ModelRoute, model: String?, effort: String?): ModelChoice = ModelChoice(
            model = model?.takeIf { it in Models.ALL } ?: route.defaultModel,
            effort = effort?.takeIf { it in Models.EFFORTS } ?: route.defaultEffort,
        )

        fun defaultOf(route: ModelRoute) = ModelChoice(route.defaultModel, route.defaultEffort)
    }
}

/** Каталог: что можно выбрать и как это называется на экране. */
object Models {
    val ALL: List<String> = listOf(Settings.MODEL_SONNET, Settings.MODEL_OPUS, Settings.MODEL_FABLE)

    fun label(model: String): String = when (model) {
        Settings.MODEL_SONNET -> "Сонет 5"
        Settings.MODEL_OPUS -> "Опус 5"
        Settings.MODEL_FABLE -> "Fable 5.1"
        else -> model
    }

    /** Цена за миллион токенов входа и выхода — подпись под выбором, чтобы решать с открытыми глазами. */
    fun priceLabel(model: String): String = when (model) {
        Settings.MODEL_SONNET -> "\$2 / \$10 за млн токенов входа и выхода"
        Settings.MODEL_OPUS -> "\$5 / \$25 за млн токенов входа и выхода"
        Settings.MODEL_FABLE -> "\$10 / \$50 за млн токенов входа и выхода"
        else -> ""
    }

    /**
     * Усилие — output_config.effort. Пустая строка — не передавать (API сам
     * ставит high). Порядок — от дешёвого к дорогому.
     */
    val EFFORTS: List<String> = listOf("", "low", "medium", "high", "xhigh", "max")

    fun effortLabel(effort: String): String = if (effort.isBlank()) "по умолчанию" else effort
}
