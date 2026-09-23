package ru.zf.pravka.core

// Справочник получателей: «Иван П. — помощь по дому, дети», «ANTHROPIC —
// ИИ для работы», «MCC 5411 — продукты». Категориям банка верить нельзя —
// у Тинькова салон красоты лежит в «Медицине», у Альфы Золотое яблоко в
// «Продуктах», а половина денег семьи — «Переводы» живым людям. Поэтому
// раскладка идёт от получателя, а категория банка — последняя подсказка.
//
// Порядок, в котором ищем (первое попадание решает):
//  1. правила владельца — справочник на телефоне;
//  2. безличные правила по названию (сервисы, сети, банковские операции);
//  3. MCC;
//  4. категория банка.
//
// ПОЧЕМУ правил владельца нет в коде: репозиторий публичный, а справочник —
// это имена терапевтов, няни, родных и кто кому даёт в долг. Он живёт только
// на телефоне (`MoneyStore`), заводится вставкой текста и растёт от ответов
// на вопросы сверки. Здесь — только то, что можно показать кому угодно.
object MoneyRules {

    /**
     * Одно правило. [pattern] — кусок названия получателя, без регистра и
     * «ё»; «|» — или. [sign]: -1 — только списания, +1 — только поступления,
     * 0 — оба. [owner] — только на счетах этого человека (пусто — на всех).
     */
    data class Rule(
        val pattern: String,
        val category: String,
        val who: String = "",
        val sign: Int = 0,
        val owner: String = "",
        val comment: String = "",
    ) {
        private val parts: List<String> = pattern.split('|').map { norm(it) }.filter { it.isNotEmpty() }

        /** Длина самого длинного совпавшего куска: из двух правил побеждает более точное. */
        fun score(entry: MoneyEntry): Int {
            if (sign < 0 && entry.rubKop >= 0) return 0
            if (sign > 0 && entry.rubKop <= 0) return 0
            if (owner.isNotEmpty() && owner != entry.owner) return 0
            val hay = norm(entry.what)
            return parts.filter { hay.contains(it) }.maxOfOrNull { it.length } ?: 0
        }
    }

    data class Hit(val category: String, val who: String, val by: String)

    fun norm(s: String): String =
        s.lowercase().replace('ё', 'е').replace(Regex("[\\s\\u00A0]+"), " ").trim()

    // ---- 2. Безличные правила по названию ----

    val GENERIC: List<Rule> = listOf(
        // Движение, а не трата.
        Rule("между своими счетами|инвесткопилк|копилк|кубышк|вывод с инвест", "own"),
        Rule("плата по кредитке|погашение минимального платежа|погашение задолженности|погашение просроченной", "own"),
        Rule("плати по миру|platipomiru", "plati"),
        Rule("внесение наличных|снятие в банкомате|выдача наличных", "cash"),
        Rule("проценты по кредиту|комиссия за|штраф за неоплату|запрос остатка", "fees"),
        // Зарплата на счёт Марианны — её доход; на чужих счетах — просто поступление.
        Rule("заработная плата|аванс по заработной|отпускные|суточные|перечисление заработной|перечисление аванса|перечисление отпускных", "inc_marianna", sign = 1, owner = "marianna"),
        Rule("заработная плата|аванс по заработной|отпускные", "inc_other", sign = 1),
        Rule("выплаты многодетн|соцказначейств", "inc_other", sign = 1),
        Rule("возврат по операции", "inc_other", sign = 1),
        Rule("штрафы гибдд|штраф", "taxes", sign = -1),
        Rule("федеральная налоговая|фнс", "taxes", sign = -1),

        // Инструменты для работы — зарубежные сервисы, почти всё через «Плати по миру».
        Rule("anthropic|claude.ai|openai|chatgpt|zoom.com|todoist|notion labs|toggl|gamma.app|wispr|bindify|socialsight|ru-center|aeza|smartwriter|bukvitsa", "subs_work"),
        // Спортивные сервисы.
        Rule("zwift|strava|intervals.icu|icusync", "sport"),
        // Подписки дома.
        Rule("patreon|suno|deepstash|ebook applications|google play|okko|kaspersky|getcontact|vpn|telegram|smart reading|litres|яндекс плюс", "subs_home"),
        // Поездки.
        Rule("airalo|transavia|aeroflot|аэрофлот|e- visa|e-visa|vfs|ostrovok|booking|airbnb|uber|flyone|zvartnots|duty free", "travel"),
        // Сети и сервисы Москвы.
        Rule("вкусвилл|vkusvill|vv 1695|азбука вкуса|azbuka|azbukavkusa|перекрест|perekrestk|яндекс лавка|yandex lavka|самокат|ашан|магнит", "groceries"),
        Rule("яндекс такси|yandex go|самокаты|mos transport|главная дорога|парковк", "transport"),
        Rule("жку|мосэнергосбыт|билайн|мегафон|т-мобайл|t2 |мтс|интернет", "utilities"),
        Rule("помощь рядом|yandex help|детские деревни|благотвор", "gifts"),
        Rule("салон красоты|nails|gold apple|золотое яблоко|барбер|стрижк", "beauty"),
        Rule("netmonet|нетмонет|сберчаевые|tips", "cafe"),
        Rule("аптек|apteki|неофарм|эркафарм|invitro|инвитро|доктор слон|doctorslon", "health"),
        Rule("филармони|philarmonic|большой театр|bolshoi|яндекс афиша|yandex afisha|kassir|ticketland|intickets|зоопарк|музей|muzei|третьяковск", "leisure"),
        Rule("детский мир|мир кубиков", "kids_stuff"),
    )

    // ---- 3. MCC ----

    private val MCC: Map<String, String> = buildMap {
        listOf("5411", "5422", "5441", "5451", "5462", "5499").forEach { put(it, "groceries") }
        listOf("5812", "5813", "5814").forEach { put(it, "cafe") }
        listOf("4111", "4121", "4131", "4784", "5541", "5542", "7523", "7531", "7538", "7542", "5533").forEach { put(it, "transport") }
        listOf("5912", "5122", "8011", "8021", "8031", "8041", "8042", "8043", "8049", "8050", "8062", "8071", "8099").forEach { put(it, "health") }
        listOf("7230", "7298", "5977").forEach { put(it, "beauty") }
        listOf("5611", "5621", "5631", "5651", "5661", "5691", "5699").forEach { put(it, "clothes") }
        listOf("5641", "5945").forEach { put(it, "kids_stuff") }
        listOf("4511", "4722", "7011", "3000").forEach { put(it, "travel") }
        listOf("7832", "7922", "7991", "7996", "7998", "7999").forEach { put(it, "leisure") }
        put("7997", "sport"); put("5941", "sport")
        put("8211", "school"); put("8299", "clubs"); put("8220", "clubs")
        listOf("4814", "4900", "4899").forEach { put(it, "utilities") }
        listOf("5815", "5816", "5817", "5818").forEach { put(it, "subs_home") }
        listOf("8398", "5992").forEach { put(it, "gifts") }
        listOf("6010", "6011").forEach { put(it, "cash") }
        listOf("9222", "9311").forEach { put(it, "taxes") }
        put("5942", "leisure")
    }

    // ---- 4. Категория банка ----

    private val BANK: Map<String, String> = mapOf(
        "супермаркеты" to "groceries", "продукты" to "groceries",
        "фастфуд" to "cafe", "рестораны" to "cafe", "кафе и рестораны" to "cafe",
        "такси" to "transport", "транспорт" to "transport", "местный транспорт" to "transport",
        "заправки" to "transport", "азс" to "transport", "платные дороги" to "transport",
        "самокаты" to "transport", "автоуслуги" to "transport",
        "штрафы" to "taxes",
        "аптеки" to "health", "медицина" to "health", "медицинские услуги" to "health",
        "красота" to "beauty", "одежда и обувь" to "clothes",
        "авиабилеты" to "travel", "отели" to "travel", "путешествия" to "travel", "duty free" to "travel",
        "искусство" to "leisure", "развлечения" to "leisure", "культура и искусство" to "leisure",
        "книги" to "leisure", "хобби" to "leisure",
        "жкх" to "utilities", "мобильная связь" to "utilities", "связь" to "utilities", "интернет" to "utilities",
        "нко" to "gifts", "благотворительность" to "gifts", "цветы" to "gifts", "подарки и творчество" to "gifts",
        "цифровые товары" to "subs_home", "онлайн-кинотеатры" to "subs_home", "музыка" to "subs_home",
        "образование" to "clubs",
        "тренировки" to "sport", "активный отдых" to "sport", "спорттовары" to "sport",
        "детские товары" to "kids_stuff",
        "наличные" to "cash", "выдача наличных" to "cash",
        "услуги банка" to "fees",
        "проценты" to "inc_other", "бонусы" to "inc_other",
        "между своими счетами" to "own",
    )

    /**
     * Категория для строки выписки. [owned] — справочник владельца. Ничего не
     * нашлось — null: такую строку сверка спросит у владельца, а не положит
     * в «Прочее» молча.
     */
    fun classify(entry: MoneyEntry, owned: List<Rule>): Hit? {
        best(entry, owned)?.let { return Hit(it.category, it.who, "справочник") }
        best(entry, GENERIC)?.let { return Hit(it.category, it.who, "по названию") }
        MCC[entry.mcc.trim().removeSuffix(".0")]?.let { return Hit(it, "", "по MCC") }
        BANK[norm(entry.bankCategory)]?.let { return Hit(it, "", "по категории банка") }
        return null
    }

    private fun best(entry: MoneyEntry, rules: List<Rule>): Rule? =
        rules.map { it to it.score(entry) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first

    // ---- Текст справочника: так его вставляют и так он показывается ----
    //
    //   Иван П. = Помощь по дому · дети
    //   − Пётр С. = Лето и лагеря · Серёжа        (только списания)
    //   + Сидор | Сидоров С. = Займы           (только поступления)
    //   Марианна: Муж М. = Между нами         (только на счетах Марианны)
    //   # строка-комментарий
    //
    // Категорию можно назвать словами или ключом, «для кого» — именем.

    data class ParseResult(val rules: List<Rule>, val errors: List<String>)

    fun parseText(text: String): ParseResult {
        val rules = mutableListOf<Rule>()
        val errors = mutableListOf<String>()
        for ((i, raw) in text.lines().withIndex()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var comment = ""
            line.indexOf(" # ").takeIf { it > 0 }?.let { comment = line.substring(it + 3).trim(); line = line.substring(0, it).trim() }
            val eq = line.indexOf('=')
            if (eq <= 0) { errors.add("строка ${i + 1}: нет «=» — «${raw.trim()}»"); continue }
            var left = line.substring(0, eq).trim()
            val right = line.substring(eq + 1).split('·', ',').map { it.trim() }
            var sign = 0
            if (left.startsWith("−") || left.startsWith("-")) { sign = -1; left = left.drop(1).trim() }
            else if (left.startsWith("+")) { sign = 1; left = left.drop(1).trim() }
            var owner = ""
            val colon = left.indexOf(':')
            if (colon > 0) {
                val o = MoneyCategories.findWho(left.substring(0, colon))
                if (o == "sasha" || o == "marianna") { owner = o; left = left.substring(colon + 1).trim() }
            }
            val cat = MoneyCategories.find(right.getOrNull(0).orEmpty())
            if (left.isEmpty()) { errors.add("строка ${i + 1}: пустой шаблон"); continue }
            if (cat == null) { errors.add("строка ${i + 1}: не знаю категорию «${right.getOrNull(0).orEmpty()}»"); continue }
            val who = right.getOrNull(1)?.let { MoneyCategories.findWho(it) }.orEmpty()
            rules.add(Rule(left, cat.key, who, sign, owner, comment))
        }
        return ParseResult(rules, errors)
    }

    fun toText(rules: List<Rule>): String = rules.joinToString("\n") { r ->
        val sign = when (r.sign) { -1 -> "− "; 1 -> "+ "; else -> "" }
        val owner = if (r.owner.isNotEmpty()) MoneyCategories.whoTitle(r.owner) + ": " else ""
        val who = if (r.who.isNotEmpty()) " · " + MoneyCategories.whoTitle(r.who) else ""
        val comment = if (r.comment.isNotEmpty()) " # " + r.comment else ""
        "$sign$owner${r.pattern} = ${MoneyCategories.title(r.category)}$who$comment"
    }
}
