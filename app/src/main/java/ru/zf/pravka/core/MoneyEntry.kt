package ru.zf.pravka.core

// Одна запись журнала денег — надиктованная трата или строка выписки. Один тип
// на всё, чтобы сверка сравнивала сравнимое: «кофе 380» из голоса и
// «Surf Coffee −380,00» из Тинькова — две записи одного вида, связанные
// ссылкой [matchId], а не два мира со своими полями.
//
// Деньги — в копейках (Long), знак — направление: минус — ушло, плюс —
// пришло. Double здесь не годится: сумма тысяч строк с плавающей точкой
// расходится с банком на копейки, и сверка «до копейки» перестаёт быть правдой.
//
// Файл без Android: журнал, разбор выписок и сверку проверяют JVM-тесты.
data class MoneyEntry(
    /**
     * Постоянный номер. У строки выписки он выводится из самой строки
     * (банк + дата + сумма + описание), поэтому та же выписка, присланная
     * второй раз или внахлёст с прошлой неделей, ничего не удваивает. У
     * надиктованной — время тейка и номер строки.
     */
    val id: String,
    /** Чей счёт или чья надиктовка: sasha / marianna. Общая база двух телефонов (потом) сливается по [id]. */
    val owner: String,
    val source: Source,
    /** Когда: миллисекунды. [timeKnown] = false — известен только день (Альфа) или время выведено (Плати по миру). */
    val ts: Long,
    val timeKnown: Boolean = true,
    /** Сумма в рублях, копейки, со знаком. */
    val rubKop: Long,
    /** Сумма в валюте операции (минорные единицы) и сама валюта: RUB, EUR, USD, AMD… */
    val origMinor: Long = rubKop,
    val currency: String = "RUB",
    /** Откуда рубли: банк сам посчитал, курс пополнения, курс ЦБ (предварительно), сказано в рублях. */
    val rubBasis: RubBasis = RubBasis.BANK,
    /** Что это: «ВкусВилл», «Иван П.», «кофе в Даблби». */
    val what: String,
    /** Сообщение к переводу / комментарий банка / уточнение из голоса. */
    val note: String = "",
    val mcc: String = "",
    /** Категория банка как подсказка («Супермаркеты»): ей не верим, но слушаем. */
    val bankCategory: String = "",
    /** Счёт или карта: «Black Premium *0000», «Плати по миру *1234». */
    val account: String = "",
    /** Наш ключ категории (`MoneyCategories`), пусто — ещё не разложено. */
    val category: String = "",
    /** Для кого (`MoneyCategories.WHO`), пусто — не сказано. */
    val who: String = "",
    /** Кто поставил категорию: справочник, модель, владелец. Владельца ничто не перебивает. */
    val categoryBy: CategoryBy = CategoryBy.NONE,
    /** Надиктованная ждёт «ОК» на плашке: до него её нет ни в итогах, ни в сверке. */
    val draft: Boolean = false,
    /** Вычеркнута владельцем: остаётся в журнале, в итоги не идёт. */
    val dropped: Boolean = false,
    /**
     * Связь «голос ↔ выписка» или «Саша → Марианна ↔ Марианна ← Саша». У
     * связанной пары в итоги идёт одна сторона — выписка (она правда о
     * сумме), а голос отдаёт ей категорию и слова.
     */
    val matchId: String = "",
    /** Открытый вопрос сверки владельцу; пусто — вопросов нет. */
    val question: String = "",
    /** Номер тейка, из которого запись родилась (сырая надиктовка — в `MoneyStore`, не удаляется). */
    val takeId: Long = 0L,
    /** Сомнение модели в сумме («полтинник: 50 или 50 000?») — показывается на плашке до «ОК». */
    val doubt: String = "",
    /**
     * Пуш, который заменила строка выписки (её номер): выписка — правда о
     * сумме и времени, пуш остаётся в журнале следом, но в итоги не идёт.
     */
    val replacedBy: String = "",
) {
    companion object {
        /** Счёт надиктованной траты «наличными»: её не ищут в выписке и о ней не спрашивают. */
        const val CASH = "наличные"
    }

    enum class Source(val key: String, val title: String) {
        VOICE("voice", "голос"),
        TINKOFF("tinkoff", "Тиньков"),
        ALFA("alfa", "Альфа"),
        MKB("mkb", "МКБ"),
        /** Расчётный счёт ООО «Знакомый финансист» в Т-Бизнесе: сторона ЗФ. */
        TBIZ("tbiz", "ЗФ"),
        PLATI("plati", "Плати по миру"),
        /** Пуш Т-Банка: живая картина недели до выписки, выписка его потом заменяет. */
        PUSH("push", "пуш"),
        MANUAL("manual", "руками");

        companion object {
            fun of(key: String): Source = entries.firstOrNull { it.key == key } ?: MANUAL
        }
    }

    enum class RubBasis(val key: String) {
        BANK("bank"), TOPUP("topup"), CBR_PRELIM("cbr"), SAID("said");

        companion object {
            fun of(key: String): RubBasis = entries.firstOrNull { it.key == key } ?: BANK
        }
    }

    enum class CategoryBy(val key: String) {
        NONE(""), RULE("rule"), MODEL("model"), OWNER("owner");

        companion object {
            fun of(key: String): CategoryBy = entries.firstOrNull { it.key == key } ?: NONE
        }
    }

    val expense: Boolean get() = rubKop < 0
    val fromBank: Boolean get() = source == Source.TINKOFF || source == Source.ALFA || source == Source.MKB || source == Source.PLATI || source == Source.TBIZ ||
        source == Source.PUSH

    /**
     * Идёт ли запись в итоги: не черновик, не вычеркнута, голос, уже слитый с
     * выпиской, не считается второй раз, и пуш, заменённый выпиской, — тоже.
     */
    fun live(): Boolean = !draft && !dropped && replacedBy.isEmpty() && !(source == Source.VOICE && matchId.isNotBlank())
}

/** Рубли для экрана: «1 782 ₽», «−380 ₽». */
object MoneyFormat {
    /**
     * Без копеек (владелец, 23.09.2026: «убираем копейки»): на экране рубли,
     * округлённые до целого. В журнале копейки остаются — сверка с банком
     * идёт до копейки, округляется только показ.
     */
    fun rub(kop: Long, sign: Boolean = false): String {
        val neg = kop < 0
        val whole = (kotlin.math.abs(kop) + 50) / 100
        val grouped = whole.toString().reversed().chunked(3).joinToString("\u00A0").reversed()
        val prefix = when {
            neg && whole > 0 -> "−"
            sign && kop > 0 && whole > 0 -> "+"
            else -> ""
        }
        return "$prefix$grouped\u00A0₽"
    }

    /**
     * Одна размерность на всю вкладку — тысячи рублей (владелец, 23.09.2026:
     * «где-то 000, где-то тыс. руб., где-то млн. Пускай везде будет 000
     * руб»). Число без единицы: «’000 руб» подписано один раз на карточке.
     * От 10 тысяч — целые тысячи («1 782», «48»), меньше — с десятой
     * («4,5», «0,4»): иначе кофе и такси превращались бы в нули.
     */
    const val K = "’000 руб"

    fun k(kop: Long, sign: Boolean = false): String {
        val rub = kotlin.math.abs(kop) / 100.0
        val th = rub / 1000
        val body = if (rub >= 10_000) {
            Math.round(th).toString().reversed().chunked(3).joinToString("\u00A0").reversed()
        } else {
            String.format(java.util.Locale("ru"), "%.1f", th)
        }
        val zero = body.trim('0', ',', '.').isEmpty()
        val prefix = when {
            zero -> ""
            kop < 0 -> "−"
            sign && kop > 0 -> "+"
            else -> ""
        }
        return prefix + body
    }

    /** Коротко для подписей графиков: «1,2 млн», «48 тыс», «900». */
    fun short(kop: Long): String {
        val r = kotlin.math.abs(kop) / 100.0
        val s = when {
            r >= 1_000_000 -> String.format(java.util.Locale("ru"), "%.1f млн", r / 1_000_000).replace(",0 ", " ")
            r >= 10_000 -> "${(r / 1000).toLong()} тыс"
            r >= 1_000 -> String.format(java.util.Locale("ru"), "%.1f тыс", r / 1000).replace(",0 ", " ")
            else -> r.toLong().toString()
        }
        return if (kop < 0) "−$s" else s
    }

    /** Валюта для экрана: «24,25 €», «120,25 $». */
    fun orig(minor: Long, currency: String): String {
        val symbol = when (currency) {
            "EUR" -> "€"; "USD" -> "$"; "RUB" -> "₽"; "AMD" -> "֏"; else -> currency
        }
        val abs = kotlin.math.abs(minor)
        val body = (abs / 100).toString() + "," + (abs % 100).toString().padStart(2, '0')
        return (if (minor < 0) "−" else "") + body + "\u00A0" + symbol
    }

    /**
     * Строку банка — в копейки: «−1781,84», «4345.20», «1 800,00». Без
     * Double: «0,1 + 0,2» на плавающей точке — это и есть расхождение на
     * копейку, которое потом ищут неделю.
     */
    fun parseKop(raw: String): Long? {
        val s = raw.trim().replace("\u00A0", "").replace(" ", "").replace("−", "-")
        if (s.isEmpty()) return null
        val neg = s.startsWith("-")
        val body = s.removePrefix("-").removePrefix("+")
        val sep = body.lastIndexOfAny(charArrayOf(',', '.'))
        val (intPart, fracPart) = if (sep >= 0) body.substring(0, sep) to body.substring(sep + 1) else body to ""
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        if (!intPart.all { it.isDigit() } || !fracPart.all { it.isDigit() }) return null
        if (fracPart.length > 2) return null
        val whole = intPart.ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = fracPart.padEnd(2, '0').ifEmpty { "00" }.toLong()
        val kop = whole * 100 + frac
        return if (neg) -kop else kop
    }
}
