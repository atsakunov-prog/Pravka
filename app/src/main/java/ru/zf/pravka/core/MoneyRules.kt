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
// Справочник владельца — два слоя: заводской `assets/money_payees.txt` (кто
// есть кто в его выписках, собран с ним 23.09.2026; репозиторий публичный, и
// это его решение: «никаких там нет секретов, я этому доверяю») и правила на
// телефоне (`MoneyStore`) — вписанные и запомненные ответами. Телефонные
// стоят впереди и перебивают заводские. Здесь, в коде, — безличное.
object MoneyRules {

    /**
     * Одно правило. [pattern] — кусок названия получателя, без регистра и
     * «ё»; «|» — или. [sign]: -1 — только списания, +1 — только поступления,
     * 0 — оба. [owner] — только на счетах этого человека (пусто — на всех).
     * [source] — только этот банк (`MoneyEntry.Source.key`), [mcc] — только
     * этот MCC, [amountKop] — только эта сумма (по модулю). Шаблон может быть
     * пустым, если задан MCC или сумма: банкомат МКБ в описании каждый раз
     * называется по-своему, а «MCC 6011, сумма 23 000» — это всегда зарплата
     * няни (владелец, 23.09.2026).
     */
    data class Rule(
        val pattern: String,
        val category: String,
        val who: String = "",
        val sign: Int = 0,
        val owner: String = "",
        val comment: String = "",
        val source: String = "",
        val mcc: String = "",
        val amountKop: Long = 0L,
    ) {
        private val parts: List<String> = pattern.split('|').map { norm(it) }.filter { it.isNotEmpty() }

        /**
         * Насколько правило подходит записи, 0 — не подходит. Длина самого
         * длинного совпавшего куска названия; MCC добавляет немного, точная
         * сумма — много: «MCC 6011, сумма 23 000» точнее, чем просто «MCC 6011».
         */
        fun score(entry: MoneyEntry): Int {
            if (sign < 0 && entry.rubKop >= 0) return 0
            if (sign > 0 && entry.rubKop <= 0) return 0
            if (owner.isNotEmpty() && owner != entry.owner) return 0
            if (source.isNotEmpty() && source != entry.source.key) return 0
            if (mcc.isNotEmpty() && mcc != entry.mcc.trim().removeSuffix(".0")) return 0
            if (amountKop > 0 && amountKop != kotlin.math.abs(entry.rubKop)) return 0
            val name = if (parts.isEmpty()) {
                if (mcc.isEmpty() && amountKop == 0L) return 0
                1
            } else {
                val hay = norm(entry.what)
                parts.filter { hay.contains(it) }.maxOfOrNull { it.length } ?: return 0
            }
            return name + (if (mcc.isNotEmpty()) 4 else 0) + (if (amountKop > 0) 50 else 0) +
                (if (source.isNotEmpty()) 2 else 0)
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
    //   Марианна: МКБ: MCC 6011, сумма 23000 = Помощь по дому   (MCC и сумма — без названия)
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
            // Приставки «Марианна:», «Саша:», «МКБ:», «Альфа:», «Тиньков:» — в любом порядке.
            var owner = ""
            var source = ""
            while (true) {
                val colon = left.indexOf(':')
                if (colon <= 0) break
                val head = left.substring(0, colon).trim()
                val o = MoneyCategories.findWho(head)
                val src = MoneyEntry.Source.entries.firstOrNull { it.title.equals(head, ignoreCase = true) || it.key == head.lowercase() }
                when {
                    (o == "sasha" || o == "marianna") && owner.isEmpty() -> owner = o
                    src != null && source.isEmpty() -> source = src.key
                    else -> break
                }
                left = left.substring(colon + 1).trim()
            }
            var mcc = ""
            MCC_TOKEN.find(left)?.let { mcc = it.groupValues[1]; left = left.replace(it.value, " ") }
            var amount = 0L
            AMOUNT_TOKEN.find(left)?.let { m ->
                amount = MoneyFormat.parseKop(m.groupValues[1].replace(" ", ""))?.let { kotlin.math.abs(it) } ?: 0L
                left = left.replace(m.value, " ")
            }
            left = left.trim().trim(',').trim()
            val cat = MoneyCategories.find(right.getOrNull(0).orEmpty())
            if (left.isEmpty() && mcc.isEmpty() && amount == 0L) { errors.add("строка ${i + 1}: пустой шаблон"); continue }
            if (cat == null) { errors.add("строка ${i + 1}: не знаю категорию «${right.getOrNull(0).orEmpty()}»"); continue }
            val who = right.getOrNull(1)?.let { MoneyCategories.findWho(it) }.orEmpty()
            rules.add(Rule(left, cat.key, who, sign, owner, comment, source, mcc, amount))
        }
        return ParseResult(rules, errors)
    }

    private val MCC_TOKEN = Regex("(?i)\\bMCC\\s*(\\d{4})\\b")
    private val AMOUNT_TOKEN = Regex("(?i)сумма\\s*([\\d][\\d\\s]*(?:[.,]\\d{1,2})?)")

    fun toText(rules: List<Rule>): String = rules.joinToString("\n") { r ->
        val sign = when (r.sign) { -1 -> "− "; 1 -> "+ "; else -> "" }
        val owner = if (r.owner.isNotEmpty()) MoneyCategories.whoTitle(r.owner) + ": " else ""
        val source = if (r.source.isNotEmpty()) MoneyEntry.Source.of(r.source).title + ": " else ""
        val filters = listOfNotNull(
            r.pattern.takeIf { it.isNotBlank() },
            r.mcc.takeIf { it.isNotEmpty() }?.let { "MCC $it" },
            r.amountKop.takeIf { it > 0 }?.let { "сумма " + (it / 100) + (if (it % 100 == 0L) "" else "." + (it % 100).toString().padStart(2, '0')) },
        ).joinToString(", ")
        val who = if (r.who.isNotEmpty()) " · " + MoneyCategories.whoTitle(r.who) else ""
        val comment = if (r.comment.isNotEmpty()) " # " + r.comment else ""
        "$sign$owner$source$filters = ${MoneyCategories.title(r.category)}$who$comment"
    }
}
