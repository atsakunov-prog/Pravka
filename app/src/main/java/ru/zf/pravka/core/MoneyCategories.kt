package ru.zf.pravka.core

// Деньги: категории, полки и «для кого». Каталог собран с владельцем
// 23.09.2026 по его настоящей выписке Тинькова за девять месяцев и Альфе
// Марианны — не «стандартный бюджет из интернета», а то, на что у семьи
// правда уходят деньги. Ключи категорий хранятся в журнале: не переименовывать,
// название на экране менять можно.
//
// Три полки, потому что без них траты врут вдвое:
//  - FAMILY — траты и доходы семьи, из них и складываются итоги;
//  - ZF — бизнес, оплаченный со своих карт (Наташа, юрист, Workspace,
//    инструменты для работы). В итоги идёт тумблером «+ ЗФ»: «это мой доход
//    основной, надо впилить внутрь самих денег» (владелец, 23.09);
//  - SERVICE — движение, а не трата: между своими счетами, между Сашей и
//    Марианной, займы, пополнение «Плати по миру», снятые наличные. Без этой
//    полки перевод жене считался бы расходом, а её перевод обратно — доходом.
//
// Файл без Android: каталог проверяют JVM-тесты.
object MoneyCategories {

    enum class Shelf { FAMILY, ZF, SERVICE }

    data class Category(
        val key: String,
        val title: String,
        val group: String,
        val shelf: Shelf,
        /** Доход, а не расход: в сводке отдельной колонкой. */
        val income: Boolean = false,
    )

    // Группы — в том порядке, в каком владелец читает бюджет.
    const val G_KIDS = "Дети"
    const val G_HOME = "Дом"
    const val G_HEALTH = "Здоровье"
    const val G_LIFE = "Жизнь"
    const val G_INCOME = "Доходы"
    const val G_ZF = "ЗФ"
    const val G_SERVICE = "Не трата"

    val ALL: List<Category> = listOf(
        Category("school", "Школа и сад", G_KIDS, Shelf.FAMILY),
        Category("clubs", "Кружки", G_KIDS, Shelf.FAMILY),
        Category("summer", "Лето и лагеря", G_KIDS, Shelf.FAMILY),
        Category("kids_stuff", "Детские вещи и игрушки", G_KIDS, Shelf.FAMILY),
        Category("pocket", "Карманные", G_KIDS, Shelf.FAMILY),

        Category("help", "Помощь по дому", G_HOME, Shelf.FAMILY),
        Category("groceries", "Продукты", G_HOME, Shelf.FAMILY),
        Category("utilities", "ЖКХ и связь", G_HOME, Shelf.FAMILY),
        Category("dacha", "Дача", G_HOME, Shelf.FAMILY),
        // Быт — то, что няня снимает наличными на расходы по дому (владелец,
        // 23.09.2026), и хозяйственное вообще: не продукты и не ремонт дачи.
        Category("household", "Быт и хозяйство", G_HOME, Shelf.FAMILY),

        Category("therapy", "Терапия", G_HEALTH, Shelf.FAMILY),
        Category("health", "Медицина и аптеки", G_HEALTH, Shelf.FAMILY),

        Category("sport", "Спорт", G_LIFE, Shelf.FAMILY),
        Category("cafe", "Кафе и рестораны", G_LIFE, Shelf.FAMILY),
        Category("beauty", "Красота и стрижки", G_LIFE, Shelf.FAMILY),
        Category("clothes", "Одежда", G_LIFE, Shelf.FAMILY),
        Category("transport", "Машина и транспорт", G_LIFE, Shelf.FAMILY),
        Category("travel", "Путешествия", G_LIFE, Shelf.FAMILY),
        Category("leisure", "Досуг и культура", G_LIFE, Shelf.FAMILY),
        Category("subs_home", "Подписки: дом", G_LIFE, Shelf.FAMILY),
        Category("gifts", "Добро и подарки", G_LIFE, Shelf.FAMILY),
        Category("fees", "Банки и комиссии", G_LIFE, Shelf.FAMILY),
        Category("taxes", "Налоги и штрафы", G_LIFE, Shelf.FAMILY),
        Category("other", "Прочее", G_LIFE, Shelf.FAMILY),

        Category("inc_zf", "Доход от ЗФ", G_INCOME, Shelf.FAMILY, income = true),
        Category("inc_marianna", "Доход Марианны", G_INCOME, Shelf.FAMILY, income = true),
        Category("inc_other", "Прочие поступления", G_INCOME, Shelf.FAMILY, income = true),
        // Доля партнёра из выплаты ЗФ: не трата семьи, а меньше дохода —
        // «30 % после её расходов» (владелец, 23.09.2026). Стоит в доходах со
        // знаком минус; невыплаченное — долг в балансе (`MoneyCashflow`).
        Category("zf_share", "Доля Наташи (ЗФ)", G_INCOME, Shelf.FAMILY, income = true),

        // «ИИ и софт для работы» — на полке ЗФ: Anthropic, Zoom, Todoist,
        // Notion и прочее — это мастерская, а не семья (22.09 предложено,
        // владелец не возразил). Перенести в семью — сменить полку здесь.
        Category("zf", "ЗФ: расходы", G_ZF, Shelf.ZF),
        Category("subs_work", "ИИ и софт для работы", G_ZF, Shelf.ZF),
        // Со счёта ЗФ (выписка Т-Бизнеса, 23.09.2026): команда и подрядчики —
        // зарплаты по реестру, Наташа, Арина, Лена, Алёна, папа; налоги — АУСН, ЕНП.
        Category("zf_team", "ЗФ: команда и подрядчики", G_ZF, Shelf.ZF),
        Category("zf_tax", "ЗФ: налоги", G_ZF, Shelf.ZF),
        // Книги самой ЗФ (счета ЗФ в журнале): выручка от клиентов — доход ЗФ;
        // выплата владельцу — ВГО, парная «Доходу от ЗФ» на его счёте.
        Category("zf_revenue", "Выручка ЗФ", G_ZF, Shelf.ZF, income = true),

        Category("own", "Между своими", G_SERVICE, Shelf.SERVICE),
        Category("spouse", "Между нами", G_SERVICE, Shelf.SERVICE),
        Category("loan", "Займы", G_SERVICE, Shelf.SERVICE),
        Category("plati", "Плати по миру: пополнение", G_SERVICE, Shelf.SERVICE),
        Category("cash", "Наличные", G_SERVICE, Shelf.SERVICE),
        // Начисленный долг — не движение денег, а обязательство (доля Наташи
        // из выплаты ЗФ): в ДДС и итогах его нет, в балансе он растит долг.
        Category("owed", "Долг: начислено", G_SERVICE, Shelf.SERVICE),
        Category("zf_owner", "ЗФ: выплата владельцу", G_SERVICE, Shelf.SERVICE),
        // Займ ЗФ владельцу — настоящий займ (владелец, 23.09.2026: «займы это
        // реально займы мне»): у Саши долг, у ЗФ требование, вместе — ноль.
        Category("zf_loan", "Займ ЗФ ↔ владелец", G_SERVICE, Shelf.SERVICE),
    )

    private val byKey = ALL.associateBy { it.key }

    fun of(key: String): Category? = byKey[key]

    fun title(key: String): String = byKey[key]?.title ?: if (key.isBlank()) "без категории" else key

    fun shelf(key: String): Shelf = byKey[key]?.shelf ?: Shelf.FAMILY

    /** Считается ли запись с этой категорией в итоги при текущем тумблере «+ ЗФ». */
    fun counts(key: String, withZf: Boolean): Boolean = when (shelf(key)) {
        Shelf.FAMILY -> true
        Shelf.ZF -> withZf
        Shelf.SERVICE -> false
    }

    /**
     * Узнать категорию по тому, как её назвал человек или модель: ключ,
     * название целиком или его начало («продукты», «кружки», «ЗФ»). Модель
     * отвечает ключом, владелец в справочнике пишет словами — оба пути здесь.
     */
    fun find(text: String): Category? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        byKey[t]?.let { return it }
        ALL.firstOrNull { it.title.lowercase() == t }?.let { return it }
        return ALL.firstOrNull { it.title.lowercase().startsWith(t) }
            ?: ALL.firstOrNull { t.length >= 4 && it.title.lowercase().contains(t) }
    }

    /** Для кого трата: одна пометка, не категория (спорт один, а людей пятеро). */
    val WHO: List<Pair<String, String>> = listOf(
        "sasha" to "Саша",
        "marianna" to "Марианна",
        "seryozha" to "Серёжа",
        "borya" to "Боря",
        "roma" to "Рома",
        "kids" to "дети",
        "all" to "все",
    )

    fun whoTitle(key: String): String = WHO.firstOrNull { it.first == key }?.second ?: key

    fun findWho(text: String): String {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return ""
        WHO.firstOrNull { it.first == t || it.second.lowercase() == t }?.let { return it.first }
        return when {
            t.startsWith("сер") -> "seryozha"
            t.startsWith("бор") -> "borya"
            t.startsWith("ром") -> "roma"
            t.startsWith("мар") -> "marianna"
            t.startsWith("саш") || t == "я" -> "sasha"
            t.startsWith("дет") -> "kids"
            t.startsWith("все") || t.startsWith("семь") -> "all"
            else -> ""
        }
    }
}
