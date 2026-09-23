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

    private val TBANK_PACKAGES = listOf("com.idamob.tinkoff.android", "ru.tinkoff", "ru.tbank", "com.tbank")
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
    private val OLD = Regex("""Карта\s*\*(\d{4})\.\s*($N)\s*$RUB\.""")

    private val EXPENSE = listOf("покупка", "оплата", "перевод", "списание", "платеж", "платёж", "снятие", "выдача")
    private val INCOME = listOf("пополнение", "поступление", "зачисление", "возврат", "входящий")
    private val DECLINED = listOf("отказ", "отклон", "не прошла", "недостаточно средств")

    private fun kop(raw: String): Long? = MoneyFormat.parseKop(raw.replace(Regex("[ \u00A0\u202F]"), ""))

    /**
     * Мягкий перенос и склейки строк системной шторки — прочь: «овер-драфта»
     * в тексте пуша — это «овердрафта» с U+00AD, а не дефис.
     */
    private fun clean(s: String) = s.replace("\u00AD", "").replace("\r", "").trim()

    fun parse(title: String, text: String): Outcome {
        val t = clean(title)
        val body = clean(text)
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
            val what = when {
                isTransfer && tail.isNotBlank() -> tail
                else -> t.ifBlank { tail.ifBlank { m.groupValues[1] } }
            }
            val note = buildList {
                if (isTransfer && t.isNotBlank() && t != what) add("в $t")
                if (holder.isNotBlank()) add("картой: $holder")
                if (verb.contains("сбп")) add("СБП")
            }.joinToString(", ")
            return Outcome.Money(Parsed(sign * amount, what, card, note, verb))
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
    fun cardOf(account: String): String = Regex("""\*(\d{4})\b""").find(account)?.groupValues?.get(1).orEmpty()
}
