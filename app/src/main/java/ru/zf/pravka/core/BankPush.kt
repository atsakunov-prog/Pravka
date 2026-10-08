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
//    «Оплата через СБП на 9 096,3 ₽, счет RUB» с заголовком «Плати по миру»;
//    «Перевод на 1 000 ₽, накоп. счет.\nБаланс 0 ₽» — без карты и без
//    получателя.
//    Без карты пуш называет сам счёт ([Acct]): «счет RUB» — главный рублёвый,
//    «накоп. счет» — накопительный. Какой это счёт баланса — решает
//    `MoneyCashflow.pushAccount`.
//  Т-Банк, старый вид: заголовок «Покупка», текст
//    «Карта *8958. 14.00 RUB. Остаток овердрафта: 1176.26 RUB. YANDEX*HELP»;
//    заголовок «Платежи»: «Отказ YANDEX*4121*TAXI. Карта *8958. Недостаточно
//    средств.» — денег не списали, записи нет.
//
// Чат «Плати по миру» в Телеграме разбирает `PlatiChat` — здесь только
// узнаётся, что уведомление оттуда.
//
// Альфа и МКБ (08.10.2026, телефон Марианны) — пуш приложения банка или SMS
// от банка. Настоящих текстов у нас ещё нет, поэтому разбор у них общий и
// осторожный ([parseGeneric]): запись — только когда за словом операции в
// той же фразе стоит сумма в рублях; всё сомнительное — сырьём без записи.
// Сырьё ложится всегда и уезжает в архив (`money.push`): по нему напишется
// точный разбор, а шаг переразбора истории выведет записи заново.
//
// Пуш — черновик правды: выписка приходит позже и ЗАМЕНЯЕТ его
// (`MoneyMatch.linkPush`), унаследовав решённую категорию. Файл без Android.
object BankPush {

    /** Откуда уведомление: Т-Банк, Альфа, МКБ, чат «Плати по миру» или чужое. */
    enum class From { TBANK, ALFA, MKB, PLATI_CHAT, OTHER }

    // «com.idamob.tinkoff» — и личное приложение, и Т-Бизнес (пуши карты ЗФ *8958 — старого вида).
    private val TBANK_PACKAGES = listOf("com.idamob.tinkoff", "ru.tinkoff", "ru.tbank", "com.tbank")
    private val ALFA_PACKAGES = listOf("ru.alfabank")
    private val MKB_PACKAGES = listOf("ru.mkb")
    private val TELEGRAM_PACKAGES = listOf("org.telegram", "org.thunderdog.challegram", "nekox", "tw.nekomimi")

    /**
     * SMS-приложения: Google, Samsung, MIUI и AOSP. У SMS заголовок
     * уведомления — отправитель («Alfa-Bank», «MKB»), по нему и узнаётся
     * банк. Только SMS: «МКБ» в заголовке мессенджера — чей-то чат.
     */
    private val SMS_PACKAGES = listOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging",
    )

    // Имя отправителя — одними буквами, строчными: «Alfa-Bank» и «AlfaBank» — одно.
    private val ALFA_SENDERS = setOf("alfabank", "альфабанк")
    private val MKB_SENDERS = setOf("mkb", "мкб", "московскийкредитныйбанк", "мкбмосковскийкредитныйбанк")

    // Пакет — первым: пуш Т-Банка о переводе в МКБ озаглавлен «МКБ (Московский
    // Кредитный Банк)», и это Т-Банк, а не МКБ.
    fun from(pkg: String, title: String): From = when {
        TBANK_PACKAGES.any { pkg.startsWith(it) } -> From.TBANK
        ALFA_PACKAGES.any { pkg.startsWith(it) } -> From.ALFA
        MKB_PACKAGES.any { pkg.startsWith(it) } -> From.MKB
        TELEGRAM_PACKAGES.any { pkg.startsWith(it) } && title.contains("Плати по", ignoreCase = true) -> From.PLATI_CHAT
        SMS_PACKAGES.any { pkg.startsWith(it) } -> sender(title)
        else -> From.OTHER
    }

    private fun sender(title: String): From {
        val k = title.lowercase().filter { it.isLetter() }
        return when (k) {
            in ALFA_SENDERS -> From.ALFA
            in MKB_SENDERS -> From.MKB
            else -> From.OTHER
        }
    }

    /** Начало счёта записи пуша: по нему видно, из какого банка пуш. */
    private const val ALFA_LABEL = "Альфа-Банк"
    private const val MKB_LABEL = "МКБ"

    /**
     * Банк записи пуша по её счёту: «Альфа-Банк *8625» — Альфа, «МКБ» — МКБ,
     * остальное — Т-Банк (так писались все пуши до 08.10.2026).
     */
    fun fromAccount(account: String): From = when {
        account.startsWith(ALFA_LABEL) -> From.ALFA
        account.startsWith(MKB_LABEL) -> From.MKB
        else -> From.TBANK
    }

    /**
     * Ловить ли пуши Т-Банка на этом телефоне. Не задано — только на телефоне
     * владельца (08.10.2026): Т-Банк Марианны (*0292) — карта на счёте Саши,
     * её пуши приходят и ему, а выписку грузит он. Пуш, пойманный у неё, лёг
     * бы второй записью с хозяином «marianna» — со строкой выписки хозяина
     * «sasha» он не склеится никогда, и трата посчиталась бы дважды молча.
     */
    fun catchTbank(setting: Boolean?, ownerPhone: Boolean): Boolean = setting ?: ownerPhone

    /** Разобранный пуш: сумма со знаком (минус — ушло), кто/что, карта, заметка. */
    data class Parsed(
        val rubKop: Long,
        val what: String,
        val card: String,
        val note: String,
        val kind: String,
        /** «Доступно 13 630,02 ₽» — остаток счёта после операции: якорь баланса. */
        val balanceKop: Long? = null,
        /** Счёт без карты, как его назвал пуш; с картой — null (карта точнее). */
        val acct: Acct? = null,
    )

    /**
     * Счёт пуша без карты — словами самого пуша. [label] ложится в счёт записи
     * («Т-Банк, счет RUB»): по нему баланс узнаёт счёт и у старых записей.
     */
    enum class Acct(val label: String) { RUB("счет RUB"), SAVINGS("накоп. счет") }

    // «…, счет RUB» и «…, накоп. счет.»; не «\b»: у Java он кириллицу буквами не считает.
    private val ACCT_RUB = Regex("""(?<![А-ЯЁа-яё])сч[её]т\s+RUB""", RegexOption.IGNORE_CASE)
    private val ACCT_SAVINGS = Regex("""(?<![А-ЯЁа-яё])накоп\.?\s*сч[её]т""", RegexOption.IGNORE_CASE)

    private fun acctIn(s: String): Acct? = when {
        ACCT_SAVINGS.containsMatchIn(s) -> Acct.SAVINGS
        ACCT_RUB.containsMatchIn(s) -> Acct.RUB
        else -> null
    }

    /** Счёт записи пуша без карты — обратно в [Acct]: «Т-Банк, счет RUB» → [Acct.RUB]. */
    fun acctOf(account: String): Acct? = if (cardOf(account).isNotEmpty()) null else acctIn(account)

    /** Почему пуш не стал записью — для журнала событий и экрана «пойманные пуши». */
    sealed class Outcome {
        data class Money(val p: Parsed) : Outcome()
        /** [secret] — код или вход: сырьё ложится с закрытыми цифрами ([mask]). */
        data class Skip(val why: String, val secret: Boolean = false) : Outcome()
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
            val acct = if (card.isEmpty()) acctIn(first) else null
            // Перевод: получатель — после последней точки строки («…*0292. Диана Т.»),
            // а заголовок — банк получателя; у покупки заголовок и есть магазин.
            // Точку не срезаем: «Диана Т.» — так же пишет и выписка, и справочник.
            // Точка внутри «накоп. счет.» — не граница получателя: иначе им
            // становилось «счет.» (05.10.2026).
            val tail = ACCT_SAVINGS.replace(first, "накоп счет").substringAfterLast(". ", "").trim()
            val holder = FROM.find(first)?.groupValues?.get(1)?.trim().orEmpty()
            val isTransfer = verb.startsWith("перевод")
            // Строка-пояснение между первой и «Доступно»: «Банкомат.» у пополнения
            // наличными (пуш владельца, 25.09.2026: «Пополнение на 195 000 ₽, счет
            // RUB. / Банкомат. / Доступно 232 483,72 ₽» — без магазина в заголовке).
            // Когда заголовок пуст или это сам банк, место операции — оно: по нему
            // справочник узнаёт банкомат и ведёт сумму из кошелька. «Баланс 0 ₽»
            // (так пишут копилка и возврат) — остаток, как «Доступно», не место.
            val detail = lines.drop(1).firstOrNull { !it.startsWith("Доступно", ignoreCase = true) && !it.startsWith("Баланс", ignoreCase = true) }
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
                // Перевод с копилки без получателя: «что» — сама копилка, а не
                // голое «Перевод», которое сгребло бы в один вопрос чужие переводы.
                acct == Acct.SAVINGS && bankTitle -> "Накопительный счет"
                else -> t.ifBlank { tail.ifBlank { m.groupValues[1] } }
            }
            val note = buildList {
                if (place.isNotBlank() && place != what) add(place)
                if (isTransfer && t.isNotBlank() && t != what) add("в $t")
                if (holder.isNotBlank()) add("картой: $holder")
                if (verb.contains("сбп")) add("СБП")
            }.joinToString(", ")
            val available = AVAILABLE.find(body)?.let { kop(it.groupValues[1]) }
            return Outcome.Money(Parsed(sign * amount, what, card, note, verb, available, acct))
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
    fun entry(p: Parsed, ts: Long, owner: String, title: String, text: String, from: From = From.TBANK): MoneyEntry = MoneyEntry(
        id = "push-" + key(title, text),
        owner = owner,
        source = MoneyEntry.Source.PUSH,
        ts = ts,
        rubKop = p.rubKop,
        what = p.what,
        note = p.note,
        // Банк — началом счёта ([fromAccount]): по нему сверка ищет пуше пару
        // в выписке своего банка, а баланс ставит его на счёт своего банка.
        account = when {
            from == From.ALFA || from == From.MKB ->
                (if (from == From.ALFA) ALFA_LABEL else MKB_LABEL) + if (p.card.isNotBlank()) " *${p.card}" else ""
            p.card.isNotBlank() -> "Т-Банк *${p.card}"
            p.acct != null -> "Т-Банк, ${p.acct.label}"
            else -> "Т-Банк"
        },
    )

    // ---- Альфа и МКБ: общий осторожный разбор ----

    /**
     * Слово операции. Знак — по нему: списание или зачисление. «Входящий
     * перевод» — раньше просто «перевода»: иначе он прочитался бы исходящим.
     */
    private val GEN_VERB = """входящ[а-яё]*\s+перевод[а-яё]*|покупк[а-яё]*|оплат[а-яё]*|списан[а-яё]*|сняти[а-яё]*|выдач[а-яё]*|плат[её]ж[а-яё]*|перевод[а-яё]*|поступ[а-яё]*|зачисл[а-яё]*|пополн[а-яё]*|возврат[а-яё]*"""
    private val GEN_CREDIT = listOf("входящ", "поступ", "зачисл", "пополн", "возврат")

    /**
     * Операция: слово, в той же фразе (без точки, «;», перевода строки; «:»
     * можно) не дальше 60 знаков — сумма в рублях. Тысячи — пробелом (после
     * [spaces] — обычным), копейки — через «,» или «.». Перед суммой не цифра
     * и не маска карты: «··8625 500 ₽» — это 500, а не 8 625 500.
     */
    private val GEN_OP = Regex(
        """(?<![А-Яа-яЁё])($GEN_VERB)([^.;!?\n]{0,60}?)(?<![\d*•·])(\d{1,3}(?: \d{3})+|\d+)(?:[.,](\d{1,2}))?(?!\d)\s?(₽|руб(?:л[а-яё]*|\.)?|р\.?|RUB|RUR)(?![А-Яа-яЁёA-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val GEN_VERB_AT_START = Regex("""^(?:$GEN_VERB)(?![А-Яа-яЁё])""", RegexOption.IGNORE_CASE)

    /** Сумма в рублях где угодно — для [keepRaw] и для слов отсева. */
    private val RUB_AMOUNT = Regex("""\d(?:[\d \u00A0\u202F]*\d)?(?:[.,]\d{1,2})?\s?(₽|руб(?:л[а-яё]*|\.)?|р\.?|RUB|RUR)(?![А-Яа-яЁёA-Za-z])""", RegexOption.IGNORE_CASE)

    /** Валюта не рубли: сумму в рублях скажет выписка, по курсу банка. */
    private val FOREIGN = Regex("""(?<![A-Za-z])(USD|EUR|CNY|GBP|KZT|AMD|TRY|AED|BYN|GEL|THB|CHF|JPY)(?![A-Za-z])|[$€£¥]""")

    /** Остаток, лимит, кэшбэк — сумма рядом с ними не сумма операции. */
    private val BALANCE_WORD = Regex("""(?<![А-Яа-яЁё])(баланс|доступн|остат|лимит|кэшб[эе]к|бонус)""", RegexOption.IGNORE_CASE)

    // Коды, пароли, вход, просьба подтвердить: не операция, и цифры в них — чужой секрет.
    // «вход» — словом: «входящий перевод» — операция.
    private val SECRET = Regex("""(?<![А-Яа-яЁёA-Za-z])(код[а-яё]{0,2}|парол[а-яё]{0,2}|вход|войти|вошли|подтверд[а-яё]*|code|otp)(?![А-Яа-яЁёA-Za-z])""", RegexOption.IGNORE_CASE)
    // «Код авторизации» операции — номер у банка (он же колонка выписки МКБ), не секрет и не вход.
    private val AUTH_CODE = Regex("""код\s+авторизации\s*:?\s*\d*""", RegexOption.IGNORE_CASE)
    private val GEN_DECLINED = listOf("отказ", "отклон", "не прошла", "не прошёл", "не прошел", "не выполнен", "недостаточно средств", "отмен")

    /** Реклама с суммой («частями до 50 000 ₽», «оформите»): не операция. */
    private val AD = Regex("""(?<![А-Яа-яЁё])(оформ[а-яё]*|получите|акци[а-яё]*|предлож[а-яё]*|одобрен[а-яё]*|до\s+\d)""", RegexOption.IGNORE_CASE)

    /** «Перевод … от Иван И.»: от кого — значит, пришло? или держатель карты, как у Т-Банка? Не ясно. */
    private val FROM_WORD = Regex("""(?<![А-Яа-яЁё])от(?![А-Яа-яЁё])""", RegexOption.IGNORE_CASE)

    /** «Пополнение телефона 500 ₽» — трата, а не доход: такое пополнение не гадаем. */
    private val TOPUP_OUT = Regex("""телефон|мобильн|номер|тройк|кошел""", RegexOption.IGNORE_CASE)

    // Карта: «*8625», «··8625», «•8625». Не после буквы и цифры: «YANDEX*4121*TAXI» — не карта.
    private val GEN_CARD = Regex("""(?<![A-Za-z0-9])[*•·]{1,2}(\d{4})(?!\d)""")
    // Чья карта: перед ней «на» у списания — карта получателя, «с»/«от» у зачисления — отправителя.
    private val CARD_PREP = Regex("""(?<![А-Яа-яЁё])(на|с|со|от)\s+(?:карт[а-яё]*|сч[её]т[а-яё]*)?\s*$""", RegexOption.IGNORE_CASE)
    private val CARD_PHRASE = Regex("""(?:(?<![А-Яа-яЁё])(?:на|с|со|по)\s+)?(?:(?<![А-Яа-яЁё])(?:карт[а-яё]*|сч[её]т[а-яё]*)\s*)?[*•·]{1,2}\d{4}(?!\d)""", RegexOption.IGNORE_CASE)
    private val LEAD_PREP = Regex("""^(?:в|во|на|у|от|к|для|с|со|по)\s+""", RegexOption.IGNORE_CASE)
    private val MERCHANT_STOP = Regex("""(?<![А-Яа-яЁё])(карт[а-яё]*|сч[её]т[а-яё]*|баланс|доступн|остат|комисси)""", RegexOption.IGNORE_CASE)

    // Имена банка одними строчными буквами: заголовок «Альфа-Банк» — сам банк, а не место операции.
    private val ALFA_NAMES = ALFA_SENDERS + setOf("альфа", "альфаонлайн", "alfa", "alfaonline")
    private val MKB_NAMES = MKB_SENDERS + setOf("мкбонлайн", "mkbonline", "creditbank")

    private fun isBankName(s: String, from: From): Boolean {
        val k = s.lowercase().filter { it.isLetter() }
        return k.isEmpty() || k in (if (from == From.MKB) MKB_NAMES else ALFA_NAMES)
    }

    /**
     * Пуш или SMS Альфы и МКБ → операция, ОСТОРОЖНО: настоящих текстов этих
     * банков у нас ещё нет (08.10.2026), и лучше пропустить операцию — её
     * принесёт выписка, а сырьё переразберёт точный разбор, — чем записать
     * неверную. Запись — только когда за словом операции в той же фразе стоит
     * сумма в рублях; отказ, код, вход, валюта, реклама, «ушло или пришло — не
     * ясно» — отсев с причиной словами. Остаток («Баланс», «Доступно») суммой
     * операции не бывает никогда.
     */
    fun parseGeneric(from: From, title: String, text: String): Outcome {
        val t = spaces(clean(title)).trim()
        val body = spaces(clean(text))
        val low = (t + "\n" + body).lowercase()
        if (SECRET.containsMatchIn(AUTH_CODE.replace(low, ""))) return Outcome.Skip("код, вход или подтверждение — не операция", secret = true)
        if (GEN_DECLINED.any { low.contains(it) }) return Outcome.Skip("отказ или отмена — денег не списали")
        if (AD.containsMatchIn(low)) return Outcome.Skip("похоже на рекламу")
        // Заголовок-действие («Покупка» над «1 500 ₽ в MAGNIT») — начало той же фразы.
        val titleVerb = t.length <= 30 && t.none { it.isDigit() } && GEN_VERB_AT_START.containsMatchIn(t)
        val hay = if (titleVerb) "$t $body" else body
        val op = GEN_OP.findAll(hay).firstOrNull { !BALANCE_WORD.containsMatchIn(it.groupValues[2]) }
            ?: return Outcome.Skip(
                when {
                    FOREIGN.containsMatchIn(hay) -> "не в рублях — рубли скажет выписка"
                    RUB_AMOUNT.containsMatchIn(hay) -> "сумма есть, а слова операции перед ней нет"
                    else -> "не денежный или незнакомый вид"
                }
            )
        val sentence = hay.substring(op.range.first).let { s -> s.substring(0, sentenceEnd(s)) }
        if (FOREIGN.containsMatchIn(sentence)) return Outcome.Skip("в валюте — рубли скажет выписка")
        val verbWords = op.groupValues[1]
        val verb = verbWords.lowercase()
        val credit = GEN_CREDIT.any { verb.startsWith(it) }
        if (!credit && GEN_CREDIT.any { sentence.lowercase().contains(it) }) return Outcome.Skip("не ясно, ушло или пришло: «${sentence.trim()}»")
        if (verb.startsWith("перевод") && FROM_WORD.containsMatchIn(sentence)) return Outcome.Skip("перевод «от» — не ясно, ушло или пришло")
        if (verb.startsWith("пополн") && TOPUP_OUT.containsMatchIn(sentence)) return Outcome.Skip("пополнение телефона или кошелька — не ясно, ушло или пришло")
        val amount = kop(op.groupValues[3] + (op.groupValues[4].takeIf { it.isNotEmpty() }?.let { ",$it" } ?: ""))
            ?.takeIf { it > 0 } ?: return Outcome.Skip("сумма не читается: ${op.groupValues[3]}")
        val card = ownCard(hay, credit)
        val merchant = merchantAfter(hay.substring(op.range.last + 1), from)
        val what = when {
            merchant.isNotBlank() -> merchant
            t.isNotBlank() && !titleVerb && !isBankName(t, from) -> t
            else -> verbWords.trim().replace(Regex("""\s+"""), " ").replaceFirstChar { it.uppercase() } +
                if (sentence.contains("СБП", ignoreCase = true) && !verb.contains("сбп")) " СБП" else ""
        }
        val note = if (sentence.contains("СБП", ignoreCase = true)) "СБП" else ""
        return Outcome.Money(Parsed(if (credit) amount else -amount, what, card, note, verb))
    }

    /** Конец фразы: точка с пробелом или в конце, «;», «!», «?», перевод строки. Точка в числе и «р.» — не конец. */
    private fun sentenceEnd(s: String): Int {
        for (i in s.indices) {
            val c = s[i]
            if (c == '\n' || c == ';' || c == '!' || c == '?') return i
            if (c == '.' && (i + 1 == s.length || s[i + 1].isWhitespace())) {
                val abbr = s.substring(0, i).let { it.endsWith(" р") || it.endsWith("р") && it.dropLast(1).lastOrNull()?.isDigit() == true || it.endsWith("руб") }
                if (!abbr) return i
            }
        }
        return s.length
    }

    /** Своя карта операции: у списания не та, что после «на», у зачисления — не та, что после «с»/«от». */
    private fun ownCard(hay: String, credit: Boolean): String =
        GEN_CARD.findAll(hay).firstOrNull { m ->
            val prep = CARD_PREP.find(hay.substring(maxOf(0, m.range.first - 16), m.range.first))?.groupValues?.get(1)?.lowercase()
            if (credit) prep !in setOf("с", "со", "от") else prep != "на"
        }?.groupValues?.get(1).orEmpty()

    /**
     * Кто или что — после суммы: до запятой, «;», конца строки или
     * предложения, без предлога и без карты; «Баланс…» — конец поиска. Пусто
     * — следующий кусок (у SMS магазин бывает отдельным предложением), но не
     * дальше третьего. Точка инициала («Иван И.») остаётся: так пишут
     * выписка и справочник.
     */
    private fun merchantAfter(after: String, from: From): String {
        val src = CARD_PHRASE.replace(after, ",")
        var i = 0
        var pieces = 0
        while (i < src.length && pieces < 3) {
            val piece = StringBuilder()
            while (i < src.length) {
                val c = src[i]
                if (c == '\n' || c == ';') break
                // Запятая между цифрами — копейки, не граница.
                if (c == ',' && !(i > 0 && src[i - 1].isDigit() && i + 1 < src.length && src[i + 1].isDigit())) break
                if (c == '.' && (i + 1 == src.length || src[i + 1].isWhitespace())) {
                    val initial = i >= 1 && src[i - 1].isUpperCase() && (i == 1 || src[i - 2] == ' ')
                    if (!initial) break
                }
                piece.append(c)
                i++
            }
            i++
            val raw = piece.toString().trim(' ', ':', '—', '–', '-', ',')
            if (raw.isEmpty()) continue
            pieces++
            if (BALANCE_WORD.find(raw)?.range?.first == 0) return ""
            val noPrep = LEAD_PREP.replace(raw, "")
            val cut = MERCHANT_STOP.find(noPrep)?.let { noPrep.substring(0, it.range.first) } ?: noPrep
            val name = cut.trim(' ', ':', '—', '–', '-', ',')
            if (name.count { it.isLetter() } >= 2 && !isBankName(name, from) && RUB_AMOUNT.find(name)?.range?.first != 0) return name
        }
        return ""
    }

    /**
     * Та же операция, уже пойманная ДРУГИМ приложением: Альфа и МКБ шлют и
     * пуш, и SMS, если у Марианны включено и то и другое. Одна покупка
     * легла бы двумя записями, а выписка заменила бы только одну — трата
     * посчиталась бы дважды. Повтор — тот же банк, та же сумма до копейки,
     * карты не спорят, пять минут. Из ОДНОГО приложения две одинаковые
     * подряд — это две покупки (обновлённое уведомление отсекает отпечаток).
     * [caught] — пакет и запись уже пойманных пушей.
     */
    fun twin(fresh: MoneyEntry, pkg: String, caught: List<Pair<String, MoneyEntry>>): MoneyEntry? {
        val bank = fromAccount(fresh.account)
        val card = cardOf(fresh.account)
        return caught.firstOrNull { (p, e) ->
            p != pkg && e.id != fresh.id && e.source == MoneyEntry.Source.PUSH && fromAccount(e.account) == bank &&
                e.rubKop == fresh.rubKop && kotlin.math.abs(e.ts - fresh.ts) <= TWIN_MS &&
                cardOf(e.account).let { c -> c.isEmpty() || card.isEmpty() || c == card }
        }?.second
    }

    const val TWIN_MS = 5 * 60_000L

    /** Цифры кода — закрыть: сырьё кода ложится формой, без самого кода. */
    fun mask(text: String): String = Regex("""(?<![*•·\d])\d{4,8}(?!\d)""").replace(text, "••••")

    /**
     * Сырьё Альфы и МКБ с суммой в рублях хранится всё, даже не ставшее
     * записью: по нему напишется точный разбор, и шаг переразбора истории
     * выведет записи (железное правило 13: нет сырья — сначала начать его
     * хранить). Прочие несостоявшиеся пуши — последние 300 (`MoneyStore.addPush`).
     */
    fun keepRaw(pkg: String, title: String, text: String): Boolean =
        from(pkg, title).let { it == From.ALFA || it == From.MKB } && RUB_AMOUNT.containsMatchIn(spaces(clean(text)))

    /** Разбор по банку пуша: Т-Банк — по его образцам, Альфа и МКБ — общий. */
    fun parseFrom(from: From, title: String, text: String): Outcome =
        if (from == From.ALFA || from == From.MKB) parseGeneric(from, title, text) else parse(title, text)

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
