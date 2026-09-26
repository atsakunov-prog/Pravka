package ru.zf.pravka.core

import java.time.LocalDate
import java.time.YearMonth

// Выжимка журнала денег для модели — одна на «спросить Claude» и «паттерны».
// Модели нельзя отдать три тысячи строк целиком (дорого и шумно), и нельзя
// отдать только итоги (на «сколько я трачу на кофе» не ответить). Поэтому:
// месяцы × категории за год, доходы по месяцам, главные получатели по
// категориям за три месяца, регулярные платежи и строки последних 90 дней.
// Суммы — целыми рублями: копейки модели не нужны, а токены стоят денег.
//
// Файл без Android: размер и состав проверяют тесты.
object MoneyContext {

    private fun rub(kop: Long) = ((kop + if (kop >= 0) 50 else -50) / 100).toString()

    fun build(entries: List<MoneyEntry>, today: LocalDate, withZf: Boolean = true, maxLines: Int = 1500): String = buildString {
        val live = entries.filter { it.live() }
        val scope = MoneyScope.of(withZf)
        append("Сегодня: ").append(today).append('\n')
        append("Журнал: ").append(live.size).append(" записей, с ")
            .append(live.minOfOrNull { it.ts }?.let { MoneyStats.dayOf(it) } ?: "—").append('\n')
        append("Полки: «семья» — траты семьи; «ЗФ» — бизнес владельца со своих карт; «не трата» — переводы между своими и между супругами, займы, пополнение карты для зарубежных оплат, снятые наличные.\n\n")

        // Месяцы × категории.
        // Месяцы до первой записи — не нули, а «данных нет»: модель читала бы их как «не тратили».
        val firstMonth = live.minOfOrNull { it.ts }?.let { YearMonth.from(MoneyStats.dayOf(it)) }
        val months = (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
            .filter { firstMonth == null || it >= firstMonth }
        val byMonth = months.associateWith { ym ->
            val p = MoneyStats.of(MoneyStats.Kind.MONTH, ym.atDay(1))
            MoneyStats.categories(live, p, scope)
        }
        append("## Траты по месяцам и категориям, ₽ (семья").append(if (withZf) " и ЗФ" else "").append(")\n")
        append("категория | ").append(months.joinToString(" | ")).append('\n')
        val keys = byMonth.values.flatten().map { it.key }.distinct()
        for (k in keys) {
            val title = if (k.isBlank()) "без категории" else MoneyCategories.title(k)
            append(title).append(" | ")
            append(months.joinToString(" | ") { m -> rub(byMonth[m].orEmpty().firstOrNull { it.key == k }?.kop ?: 0L) })
            append('\n')
        }
        append("ИТОГО | ").append(months.joinToString(" | ") { m -> rub(byMonth[m].orEmpty().sumOf { it.kop }) }).append("\n\n")

        append("## Доходы по месяцам, ₽\n")
        for (m in MoneyStats.trend(live, today, 12, scope).filter { firstMonth == null || it.month >= firstMonth }) append(m.month).append(": ").append(rub(m.incomeKop)).append('\n')
        append('\n')

        // Главные получатели по категориям за три месяца.
        val three = MoneyStats.Period(
            MoneyStats.Kind.MONTH,
            MoneyStats.startOf(today.withDayOfMonth(1).minusMonths(2)),
            MoneyStats.startOf(today.plusDays(1)),
            today.withDayOfMonth(1).minusMonths(2),
            90,
        )
        append("## Главные получатели по категориям за три месяца\n")
        for (c in MoneyStats.categories(live, three, scope).take(25)) {
            append(if (c.key.isBlank()) "без категории" else MoneyCategories.title(c.key)).append(": ")
            append(c.merchants.take(6).joinToString("; ") { "${it.name} ${rub(it.kop)} (${it.count})" }).append('\n')
        }
        append('\n')

        val rec = MoneyStats.recurring(live, today, scope)
        if (rec.isNotEmpty()) {
            append("## Регулярные платежи (≥3 месяцев из 6)\n")
            for (r in rec.take(30)) append("${r.name}: в среднем ${rub(r.avgKop)} в месяц, ${r.months} мес., ${MoneyCategories.title(r.category)}\n")
            append('\n')
        }

        append("## Записи за 90 дней (дата | сумма ₽ | категория | что | чей счёт | для кого)\n")
        val from = MoneyStats.startOf(today.minusDays(90))
        live.filter { it.ts >= from }.sortedByDescending { it.ts }.take(maxLines).forEach { e ->
            append(MoneyStats.dayOf(e.ts)).append(" | ").append(rub(e.rubKop)).append(" | ")
            append(if (e.category.isBlank()) "без категории" else MoneyCategories.title(e.category)).append(" | ")
            append(e.what.take(60)).append(" | ").append(MoneyCategories.whoTitle(e.owner)).append(" | ")
            append(if (e.who.isBlank()) "" else MoneyCategories.whoTitle(e.who)).append('\n')
        }
    }

    /** Карточка вопроса текстом — для разбора голосового ответа владельца. */
    fun card(entries: List<MoneyEntry>): String = buildString {
        val first = entries.first()
        append("Получатель: ").append(first.what).append('\n')
        append("Счёт: ").append(entries.map { it.account.ifBlank { it.source.title } }.distinct().joinToString(", ")).append('\n')
        append("Чей счёт: ").append(MoneyCategories.whoTitle(first.owner)).append('\n')
        append("Раз: ").append(entries.size).append(", всего ").append(rub(entries.sumOf { it.rubKop })).append(" ₽\n")
        if (first.bankCategory.isNotBlank()) append("Категория банка: ").append(first.bankCategory).append('\n')
        append("Операции:\n")
        for (e in entries.sortedByDescending { it.ts }.take(20)) {
            append("  ").append(MoneyStats.dayOf(e.ts)).append(" ").append(rub(e.rubKop)).append(" ₽")
            if (e.note.isNotBlank()) append(" — «").append(e.note.take(80)).append("»")
            append('\n')
        }
    }
}
