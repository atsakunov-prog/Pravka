package ru.zf.pravka.core

// «Личное» и «ЗФ» — две кнопки наверху вкладки (владелец, 23.09.2026: «надо
// сделать отдельно баланс и кеш по ЗФ и отдельно по личным… если обе
// включены, то все операции, за исключением ВГО. Я с бизнес-карты тоже трачу
// личное регулярно»).
//
// У каждой операции два признака, и они часто расходятся:
//  - ЧЬИ ДЕНЬГИ (сторона): счёт ЗФ или личный. Счета ЗФ перечислены
//    владельцем (`zfAccounts`), остальные — личные;
//  - НА ЧТО (назначение): категория на полке ЗФ — дело ЗФ; остальное —
//    личное. «Не трата» (перемещения) назначения не имеет.
//
// Итоги, категории и графики вкладки — по НАЗНАЧЕНИЮ: кофе с бизнес-карты —
// личная трата, Anthropic с личной карты — трата ЗФ. ДДС и счета — по
// СТОРОНЕ, а расхождение сторон и назначения — это ВГО, внутригрупповой
// оборот: у ЗФ «оплачено за владельца», у владельца — трата и равное
// «оплачено ЗФ». Когда включены обе кнопки, ВГО взаимно исключаются, и
// остаются только операции с внешним миром.
//
// Файл без Android: проверяют JVM-тесты.
data class MoneyScope(
    val personal: Boolean,
    val zf: Boolean,
    /** Счета ЗФ по имени баланса: «Т-Банк · Счет для бизнеса». */
    val zfAccounts: Set<String> = emptySet(),
    /** Карта Т-Банка → счёт (для пушей), см. `MoneyCashflow.cardMap`. */
    val cards: Map<String, String> = emptyMap(),
    /**
     * Есть ли в журнале книги самой ЗФ — выплаты владельцу со стороны ЗФ
     * («ЗФ: выплата владельцу»). Тогда «Доход от ЗФ» на личном счёте — ВГО и
     * в общей картине исключается; пока книг нет, он — доход извне.
     */
    val zfBooks: Boolean = false,
) {
    val both: Boolean get() = personal && zf

    companion object {
        val PERSONAL = MoneyScope(personal = true, zf = false)
        val ZF = MoneyScope(personal = false, zf = true)
        val BOTH = MoneyScope(personal = true, zf = true)

        /** Прежний тумблер «+ ЗФ»: без него — личное, с ним — всё. */
        fun of(withZf: Boolean) = if (withZf) BOTH else PERSONAL

        /** Обе кнопки выключить нельзя: пустая вкладка читается как поломка — тогда личное. */
        fun of(personal: Boolean, zf: Boolean, entries: List<MoneyEntry>, zfAccounts: Set<String>) = MoneyScope(
            personal = personal || !zf,
            zf = zf,
            zfAccounts = zfAccounts,
            cards = MoneyCashflow.cardMap(entries),
            zfBooks = entries.any { it.category == "zf_owner" && it.live() },
        )
    }

    /** Сторона: деньги ЗФ? Кошелёк, голос и записи со слов владельца — личные. */
    fun entityZf(e: MoneyEntry): Boolean =
        zfAccounts.isNotEmpty() && MoneyCashflow.accountOf(e, cards)?.let { it in zfAccounts } == true

    /** Назначение: дело ЗФ? null — перемещение или ещё не разложено. */
    fun purposeZf(e: MoneyEntry): Boolean? {
        val c = MoneyCategories.of(e.category) ?: return null
        if (c.shelf == MoneyCategories.Shelf.SERVICE) return null
        return c.shelf == MoneyCategories.Shelf.ZF
    }

    /**
     * Идёт ли запись в итоги вкладки (по назначению). Неразложенная — по
     * стороне: непонятная трата с бизнес-карты скорее дело ЗФ.
     */
    fun countsStat(e: MoneyEntry): Boolean {
        if (e.category.isBlank()) return both || entityZf(e) == zf
        val purpose = purposeZf(e) ?: return false
        // Общая картина: выплата ЗФ владельцу, у которой нашлась пара на счёте ЗФ, — ВГО, не доход.
        if (both) return !(e.category == "inc_zf" && e.matchId.isNotBlank())
        return if (personal) !purpose else purpose
    }

    /** Показывать ли счёт баланса: долг Наташе — общий для обеих сторон. */
    fun showsAccount(name: String): Boolean = when {
        both -> true
        name == MoneyCashflow.NATASHA_DEBT -> true
        name in zfAccounts -> zf
        else -> personal
    }
}
