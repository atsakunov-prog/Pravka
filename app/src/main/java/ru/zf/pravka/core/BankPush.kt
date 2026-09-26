package ru.zf.pravka.core

// Пуши банка → записи журнала (владелец, 23.09.2026: «чтобы правка ловила
// пуши от Тинькова и вносила их»). Разбор — кодом, как у выписок: у пуша
// жёсткий шаблон, регулярка надёжнее модели и бесплатна.
//
// Форматы — с настоящих уведомлений владельца (23.09.2026):
//
//  Т-Банк, нынешнее приложение: заголовок — магазин или банк получателя,
//    «Покупка на 1 781,84 ₽, счет карты *1519\nДоступно 13 630,02 ₽»;
//    «Перевод на 1 500 ₽, от Марианна Ц., счет карты *0292. Диана Т.\nДоступно …»
//      — «от» здесь держатель карты (у Марианны своя карта *0292 на счёте
//      Саши), получатель — после последней точки;
//    «Оплата через СБП на 9 096,3 ₽, счет RUB» с заголовком «Плати по миру».
//  Т-Банк, старый вид: заголовок «Покупка», текст
//    «Карта *8958. 14.00 RUB. Остаток овердрафта: 1176.26 RUB. YANDEX*HELP»;
//    заголовок «Платежи»: «Отказ YANDEX*4121*TAXI. Карта *8958. Недостаточно
//    средств.» — денег не списали, записи нет.
//
// Чат «Плати по миру» в Телеграме разбирает `PlatiChat` — здесь только
// узнаётся, что уведомление оттуда.
//
// Пуш — черновик правды: выписка приходит позже и ЗАМЕНЯЕТ его
// (`MoneyMatch.linkPush`), унаследовав решённую категорию. Файл без Android.
object BankPush {

    /** Откуда уведомление: Т-Банк, чат «Плати по миру» или чужое. */
    enum class From { TBANK, PLATI_CHAT, OTHER }

    // «com.idamob.tinkoff» — и личное приложение, и Т-Бизнес (пуши карты ЗФ *8958 — старого вида).
    private val TBANK_PACKAGES = listOf("com.idamob.tinkoff", "ru.tinkoff", "ru.tbank", "com.tbank")
    private val TELEGRAM_PACKAGES = listOf("org.telegram", "org.thunderdog.challegram", "nekox", "tw.nekomimi")

    fun from(pkg: String, title: String): From = when {
        TBANK_PACKAGES.any { pkg.startsWith(it) } -> From.TBANK
        TELEGRAM_PACKAGES.any { pkg.startsWith(it) } && title.contains("Плати по", ignoreCase = true) -> From.PLATI_CHAT
        else -> From.OTHER
    }

    /** Разобранный пуш: сумма со знаком (минус — ушло), кто/что, карта, заметка. */
    data class Parsed(
        val rubKop: Long,
        val what: String,
        val card: String,
        val note: String,
        val kind: String,
        /** «Доступно 13 630,02 ₽» — остаток счёта после операции: якорь баланса. */
        val balanceKop: Long? = null,
    )

    /** Почему пуш не стал записью — для журнала событий и экрана «пойманные пуши». */
    sealed class Outcome {
        data class Money(val p: Parsed) : Outcome()
        data class Skip(val why: String) : Outcome()
    }

    // Сумма: «1 781,84», «9 096,3», «14.00». Пробел внутри — обычный или неразрывный.
    private const val N = """\d[\d \u00A0\u202F]*(?:[.,]\d{1,2})?"""
    private const val RUB = """(?:₽|RUB|руб\.?)"""

    // «Покупка на 1 781,84 ₽, счет карты *1519» / «Оплата через СБП на 9 096,3 ₽, счет RUB»
    private val VERB_ON = Regex("""^\s*([А-ЯЁа-яё][а-яё]+(?:\s+[А-ЯЁа-яёA-Z]+){0,3}?)\s+на\s+($N)\s*$RUB""")
    private val CARD = Regex("""карты\s*\*(\d{4})|Карта\s*\*(\d{4})""")
    // Не «\b»: у Java он кириллицу буквами не считает.
    private val FROM = Regex("""(?<![А-ЯЁа-яё])от\s+([^,]+),""")
    // Старый вид: «Карта *8958. 14.00 RUB. … YANDEX*HELP»
    private val AVAILABLE = Regex("""Доступно\s+(-?$N)\s*$RUB""")
    private val OLD = Regex("""Карта\s*\*(\d{4})\.\s*($N)\s*$RUB\.""")

    /** Заголовки, которые — сам банк, а не место операции. */
    private val BANK_TITLES = listOf("Т-Банк", "Т‑Банк", "Тинькофф", "Tinkoff", "T-Bank", "Т-Бизнес", "Т-Банк Бизнес")

    private val EXPENSE = listOf("покупка", "оплата", "перевод", "списание", "платеж", "платёж", "снятие", "выдача")
    private val INCOME = listOf("пополнение", "поступление", "зачисление", "возврат", "входящий")
    private val DECLINED = listOf("отказ", "отклон", "не прошла", "недостаточно средств")

    private fun kop(raw: String): Long? = MoneyFormat.parseKop(raw.replace(Regex("[ \u00A0\u202F]"), ""))

    /**
     * Мягкий перенос и склейки строк системной шторки — прочь: «овер-драфта»
     * в тексте пуша — это «овердрафта» с U+00AD, а не дефис.
     */
    private fun clean(s: String) = s.replace("\u00AD", "").replace("\r", "").trim()

    /**
     * Пробелы шторки — к обычному, ТОЛЬКО для разбора. Т-Банк ставит
     * неразрывные не только в суммах, но и после точки: «счет RUB.\u00A0Банкомат.»,
     * «*0292.\u00A0Марианна Ц.», «Доступно\u00A0232 483,72». Разбор искал «. » с
     * обычным пробелом — и на настоящих пушах терял место, получателя перевода и
     * остаток (владелец, 25.09.2026: внесение 195 000 и оба перевода — «без
     * категории»). Отпечаток пуша ([key]) считается по исходному тексту, как и
     * раньше, — номера уже сохранённых записей не меняются.
     */
    private val ODD_SPACE = Regex("[\\p{Zs}\\u00A0\\u2007\\u202F]")
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")
    private fun spaces(s: String) = s.replace(ZERO_WIDTH, "").replace(ODD_SPACE, " ")

    /**
     * Невидимые символы текста — словами, для окна «Пойманный пуш»: «U+00A0 ×4».
     * Неразрывный пробел выглядит как обычный, а разбор он ломал; теперь его видно.
     */
    fun invisibles(text: String): String =
        text.filter { it != ' ' && it != '\n' && (Character.isSpaceChar(it) || ZERO_WIDTH.matches(it.toString())) }
            .groupingBy { it }.eachCount()
            .entries.joinToString(", ") { (c, n) -> "U+%04X ×%d".format(c.code, n) }

    fun parse(title: String, text: String): Outcome {
        val t = clean(title)
        val body = spaces(clean(text))
        val low = (t + " " + body).lowercase()
        if (DECLINED.any { low.contains(it) }) return Outcome.Skip("отказ — денег не списали")
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return Outcome.Skip("пустой текст")

        VERB_ON.find(first)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val amount = kop(m.groupValues[2]) ?: return Outcome.Skip("сумма не читается: ${m.groupValues[2]}")
            val sign = when {
                INCOME.any { verb.startsWith(it) } -> 1
                EXPENSE.any { verb.startsWith(it) } -> -1
                else -> return Outcome.Skip("незнакомое действие «${m.groupValues[1]}»")
            }
            val card = CARD.find(first)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()
            // Перевод: получатель — после последней точки строки («…*0292. Диана Т.»),
            // а заголовок — банк получателя; у покупки заголовок и есть магазин.
            // Точку не срезаем: «Диана Т.» — так же пишет и выписка, и справочник.
            val tail = first.substringAfterLast(". ", "").trim()
            val holder = FROM.find(first)?.groupValues?.get(1)?.trim().orEmpty()
            val isTransfer = verb.startsWith("перевод")
            // Строка-пояснение между первой и «Доступно»: «Банкомат.» у пополнения
            // наличными (пуш владельца, 25.09.2026: «Пополнение на 195 000 ₽, счет
            // RUB. / Банкомат. / Доступно 232 483,72 ₽» — без магазина в заголовке).
            // Когда заголовок пуст или это сам банк, место операции — оно: по нему
            // справочник узнаёт банкомат и ведёт сумму из кошелька.
            val detail = lines.drop(1).firstOrNull { !it.startsWith("Доступно", ignoreCase = true) }
                ?.trim()?.trimEnd('.')?.trim().orEmpty()
            // Место бывает и на той же строке, после точки: «Пополнение на 195 000 ₽,
            // счет RUB. Банкомат.» (настоящий пуш владельца, 25.09.2026).
            val place = detail.ifBlank { if (isTransfer) "" else tail.trimEnd('.').trim() }
            // Заголовок, который повторяет само действие («Пополнение» над
            // «Пополнение на 195 000 ₽…»), — тоже не место: как пустой.
            val bankTitle = t.isBlank() || BANK_TITLES.any { t.equals(it, ignoreCase = true) } ||
                t.equals(m.groupValues[1], ignoreCase = true) ||
                (INCOME + EXPENSE).any { t.lowercase().startsWith(it) && t.length <= it.length + 12 }
            val what = when {
                isTransfer && tail.isNotBlank() -> tail
                bankTitle && place.isNotBlank() -> place
                else -> t.ifBlank { tail.ifBlank { m.groupValues[1] } }
            }
            val note = buildList {
                if (place.isNotBlank() && place != what) add(place)
                if (isTransfer && t.isNotBlank() && t != what) add("в $t")
                if (holder.isNotBlank()) add("картой: $holder")
                if (verb.contains("сбп")) add("СБП")
            }.joinToString(", ")
            val available = AVAILABLE.find(body)?.let { kop(it.groupValues[1]) }
            return Outcome.Money(Parsed(sign * amount, what, card, note, verb, available))
        }

        OLD.find(body)?.let { m ->
            val amount = kop(m.groupValues[2]) ?: return Outcome.Skip("сумма не читается: ${m.groupValues[2]}")
            val verb = t.lowercase()
            val sign = when {
                INCOME.any { verb.startsWith(it) } -> 1
                EXPENSE.any { verb.startsWith(it) } -> -1
                else -> return Outcome.Skip("незнакомый заголовок «$t»")
            }
            // Магазин — последний кусок после точки: «… 1176.26 RUB. YANDEX*HELP».
            val what = body.split(Regex("""\.\s+""")).last().trim().trimEnd('.')
            return Outcome.Money(Parsed(sign * amount, what, m.groupValues[1], "", verb))
        }
        return Outcome.Skip("не денежный или незнакомый вид")
    }

    /**
     * Запись журнала из пуша. Номер — из самого пуша (текст целиком, с
     * остатком): тот же пуш, пойманный дважды (обновление уведомления,
     * повторный обход шторки), записи не удваивает.
     */
    fun entry(p: Parsed, ts: Long, owner: String, title: String, text: String): MoneyEntry = MoneyEntry(
        id = "push-" + key(title, text),
        owner = owner,
        source = MoneyEntry.Source.PUSH,
        ts = ts,
        rubKop = p.rubKop,
        what = p.what,
        note = p.note,
        account = if (p.card.isNotBlank()) "Т-Банк *${p.card}" else "Т-Банк",
    )

    /** Отпечаток пуша: заголовок и текст целиком (с остатком — две одинаковые покупки подряд различает он). */
    fun key(title: String, text: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest((clean(title) + "\n" + clean(text)).toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Последние четыре цифры карты из счёта записи: «Black Premium *1519» → «1519». */
    // Регулярка — одна на всё: её зовут на каждую из тысяч записей при каждом пересчёте вкладки.
    private val CARD_TAIL = Regex("""\*(\d{4})\b""")

    fun cardOf(account: String): String = CARD_TAIL.find(account)?.groupValues?.get(1).orEmpty()
}
