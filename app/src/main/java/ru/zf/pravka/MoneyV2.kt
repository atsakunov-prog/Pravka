package ru.zf.pravka

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyMerchants
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvBar
import ru.zf.pravka.ui.KvBarRow
import ru.zf.pravka.ui.KvColumn
import ru.zf.pravka.ui.KvColumns
import ru.zf.pravka.ui.KvGap
import ru.zf.pravka.ui.KvKpi
import ru.zf.pravka.ui.KvKpiRow
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.KvSeg
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard

// «Деньги» в виде v2 (Правка 5.0, этап 6, холст `Dengi.dc.html`): разделы
// вкладками, как в вебе Денег, — траты, разбор, ДДС, баланс, журнал; на
// «Тратах» — сколько ушло крупно, статьи строками с полосой, сегодняшние
// операции. Раскладка — в `MoneyTab.kt` (ветка v2); расчёты — те же
// `MoneyStats` и `MoneyEngine`, что у вкладки 4.0.

/**
 * Разделы Денег на телефоне — вкладками под шапкой. P&L, бюджета, доли партнёра и
 * «чего не видим» на телефоне нет: они — в вебе Денег, входом с «Трат» (`MoneyWebCardV2`).
 */
internal const val MONEY_SPEND = 0
internal const val MONEY_SORT = 1
internal const val MONEY_DDS = 2
internal const val MONEY_BALANCE = 3
internal const val MONEY_JOURNAL = 4

internal fun moneySections(waiting: Int): List<String> =
    listOf("Траты", if (waiting > 0) "Разобрать $waiting" else "Разобрать", "ДДС", "Баланс", "Журнал")

/** «Личное · ЗФ · Всё» — три положения тех же двух тумблеров, что в окне из шапки 4.0. */
internal val MONEY_SCOPES = listOf("Личное", "ЗФ", "Всё")

internal fun moneyScopeIndex(personal: Boolean, zf: Boolean): Int = when {
    personal && zf -> 2
    zf -> 1
    else -> 0
}

/** Сегмент «чьи деньги»: пишет те же настройки, что окно «Чьи деньги». */
@Composable
internal fun MoneyScopeSeg(app: PravkaApp, personal: Boolean, zf: Boolean, modifier: Modifier = Modifier) {
    KvSeg(
        MONEY_SCOPES,
        moneyScopeIndex(personal, zf),
        onSelect = { i ->
            app.appScope.launch { app.settings.setMScope(personal = i != 1, zf = i != 0) }
        },
        modifier = modifier,
    )
}

/** «октябрь, по 13-е» — период в подписи шапки; неделя — «5–11 октября». */
internal fun moneyPeriodWord(p: MoneyStats.Period, today: LocalDate): String {
    val last = p.firstDay.plusDays((p.days - 1).toLong())
    return if (p.kind == MoneyStats.Kind.MONTH) {
        val month = Fmt.monthTitle(p.firstDay.monthValue).lowercase()
        if (!today.isBefore(p.firstDay) && !today.isAfter(last)) "$month, по ${today.dayOfMonth}-е" else month
    } else {
        "${p.firstDay.dayOfMonth}–" + Fmt.dayMonth(last)
    }
}

/** Сколько ушло сегодня — из той же раскладки по дням, что у столбиков (0, если сегодня вне периода). */
internal fun moneyTodayKop(daily: List<Long>, p: MoneyStats.Period, today: LocalDate): Long {
    val i = ChronoUnit.DAYS.between(p.firstDay, today).toInt()
    return daily.getOrNull(i) ?: 0L
}

/**
 * Итог периода (холст: «120 000 ₽ за октябрь · 80 трат» и справа «сегодня»).
 * Под числами — пришло, сальдо и к прошлому периоду мелко; бюджет задан —
 * полоса «сколько осталось» (тот же бюджет, что у плитки «Сегодня»).
 */
@Composable
internal fun MoneyTotalsCardV2(
    period: MoneyStats.Period,
    totals: MoneyStats.Totals,
    todayKop: Long,
    budgetRub: Long,
    wide: Boolean,
) {
    val month = period.kind == MoneyStats.Kind.MONTH
    PaperCard {
        KvKpiRow {
            KvKpi(
                MoneyFormat.k(totals.spentKop),
                Modifier.weight(1f),
                unit = "₽",
                label = (if (month) "за " + Fmt.monthTitle(period.firstDay.monthValue).lowercase() else "за неделю") +
                    if (wide) "" else " · " + plural(totals.count, "трата", "траты", "трат"),
            )
            KvKpi(MoneyFormat.k(todayKop), Modifier.weight(if (wide) 1f else 0.7f), unit = "₽", label = "сегодня", end = !wide)
        }
        val tail = buildList {
            add("пришло " + MoneyFormat.k(totals.incomeKop, sign = true))
            add("сальдо " + MoneyFormat.k(totals.balanceKop, sign = true))
            totals.spentDelta?.let { d ->
                // Неразрывные пробелы: «▼ 44 %» не рвётся на две строки.
                add("к прошлому " + (if (month) "месяцу" else "неделе") + "\u00A0" + (if (d > 0) "▲" else "▼") + "\u00A0${kotlin.math.abs(Math.round(d * 100))}\u00A0%")
            }
        }
        KvGap()
        KvNote(tail.joinToString(" · "))
        if (month && budgetRub > 0) {
            val left = budgetRub * 100 - totals.spentKop
            KvBar(totals.spentKop.toFloat() / (budgetRub * 100), Modifier.padding(top = 10.dp, bottom = 6.dp), color = if (left < 0) Kv.warn else Kv.accent)
            KvNote(
                if (left >= 0) "бюджет ${MoneyFormat.k(budgetRub * 100)} — осталось ${MoneyFormat.k(left)}"
                else "бюджет ${MoneyFormat.k(budgetRub * 100)} — сверх него ${MoneyFormat.k(-left)}",
                color = if (left < 0) Kv.warn else Kv.faint,
            )
        }
    }
}

/**
 * «По статьям» (холст: статья, полоса от самой крупной, рубли справа). Семь
 * крупных и «Остальное»; тап по статье раскрывает её магазинами и
 * получателями. [trailing] — сегмент «чьи деньги» на сложенном.
 */
@Composable
internal fun MoneyCatsCardV2(cats: List<MoneyStats.Category>, trailing: (@Composable () -> Unit)?) {
    var open by remember { mutableStateOf(setOf<String>()) }
    PaperCard(label = "По статьям", trailing = trailing) {
        if (cats.isEmpty()) {
            KvNote("За этот период трат нет.")
            return@PaperCard
        }
        val top = cats.take(7)
        val rest = cats.drop(7)
        val peak = cats.maxOf { it.kop }.coerceAtLeast(1L)
        for (c in top) {
            val expanded = c.key in open
            KvBarRow(
                if (c.key.isBlank()) "Без категории" else MoneyCategories.title(c.key),
                c.kop.toFloat() / peak,
                MoneyFormat.k(c.kop),
                labelWidth = 128.dp,
                valueWidth = 72.dp,
                onClick = { open = if (expanded) open - c.key else open + c.key },
            )
            if (expanded) {
                for (m in c.merchants.take(12)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 1.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, style = LocalPravkaType.current.meta, color = Kv.muted, modifier = Modifier.weight(1f), maxLines = 1)
                        Text((if (m.count > 1) "${m.count}× " else "") + MoneyFormat.k(m.kop), style = LocalPravkaType.current.meta, color = Kv.muted)
                    }
                }
                if (c.merchants.size > 12) KvNote("и ещё ${c.merchants.size - 12}", Modifier.padding(start = 12.dp))
            }
        }
        if (rest.isNotEmpty()) {
            val sum = rest.sumOf { it.kop }
            KvBarRow("Остальное", sum.toFloat() / peak, MoneyFormat.k(sum), labelWidth = 128.dp, valueWidth = 72.dp)
        }
    }
}

private val hm = SimpleDateFormat("HH:mm", Locale.US)

/**
 * «Сегодня» (холст: время мелко, получатель, статья, сумма справа). Сегодня
 * ничего — последние операции периода, с датой вместо времени.
 */
@Composable
internal fun MoneyTodayCardV2(journal: List<MoneyEntry>, count: Int, onEntry: (MoneyEntry) -> Unit) {
    val todayStart = remember { MoneyStats.startOf(MoneyStats.dayOf(System.currentTimeMillis())) }
    val todays = journal.filter { it.ts >= todayStart }
    val list = (todays.ifEmpty { journal }).take(count)
    if (list.isEmpty()) return
    val isToday = todays.isNotEmpty()
    PaperCard(label = if (isToday) "Сегодня" else "Последние", note = if (todays.size > count) "$count последних" else null) {
        list.forEachIndexed { i, e ->
            val d = MoneyStats.dayOf(e.ts)
            KvListRow(
                title = MoneyMerchants.canonical(e.what).ifBlank { e.what },
                sub = if (e.category.isBlank()) "без категории" else MoneyCategories.title(e.category),
                lead = if (isToday) (if (e.timeKnown) hm.format(Date(e.ts)) else "—") else String.format(Locale.US, "%02d.%02d", d.dayOfMonth, d.monthValue),
                value = MoneyFormat.k(e.rubKop, sign = true),
                valueColor = if (e.rubKop > 0) Kv.ok else Kv.text,
                divider = i > 0,
                onClick = { onEntry(e) },
            )
        }
    }
}

/**
 * По дням (столбики `.cols-ch`: прошедшие дни периода, сегодня — акцентом,
 * пунктир — средний день). Будущие дни месяца не рисуются: пустые столбики
 * без смысла.
 */
@Composable
internal fun MoneyDaysCardV2(daily: List<Long>, period: MoneyStats.Period, today: LocalDate, pace: MoneyStats.Pace) {
    val shown = daily.indices.filter { !period.firstDay.plusDays(it.toLong()).isAfter(today) }.ifEmpty { daily.indices.toList() }
    if (shown.isEmpty()) return
    val few = shown.size <= 10
    PaperCard(
        label = "По дням",
        note = if (pace.daysPassed > 0) "в среднем ${MoneyFormat.k(pace.perDayKop)} в день" else null,
        info = "Пунктир — средний день периода.",
    ) {
        KvColumns(
            shown.map { i ->
                val day = period.firstDay.plusDays(i.toLong())
                KvColumn(
                    daily[i] / 100f,
                    if (few || day.dayOfMonth % 5 == 1 || day == today) "${day.dayOfMonth}" else "",
                    accent = day == today,
                )
            },
            height = 96.dp,
            target = if (pace.daysPassed > 0) pace.perDayKop / 100f else null,
        )
        if (pace.forecastKop != null) {
            KvGap()
            KvNote("если тратить так же — за период ${MoneyFormat.k(pace.forecastKop)} ₽")
        }
    }
}

/** Регулярные платежи строками: что, статья и сколько месяцев из шести, в среднем за месяц. */
@Composable
internal fun MoneyRecurringCardV2(recurring: List<MoneyStats.Recurring>) {
    if (recurring.isEmpty()) return
    PaperCard(label = "Регулярные", note = MoneyFormat.k(recurring.sumOf { it.avgKop }) + " ₽ в месяц") {
        recurring.take(15).forEachIndexed { k, r ->
            KvListRow(
                title = r.name,
                sub = MoneyCategories.title(r.category) + " · ${r.months} мес. из 6",
                value = "−" + MoneyFormat.k(r.avgKop),
                divider = k > 0,
            )
        }
    }
}

/**
 * «В вебе Денег» (доводка 5.0, 14.10.2026; холст: вкладки «Бюджет», «P&L», «Доля» и
 * подсказка «Чего не видим»): строки-входы в разделы, которые считает сервис
 * «Деньги» на компе. Раздел открывается страницей сервиса внутри приложения тем же
 * путём, что пункт меню Ковчега ([onOpen] — `ServicePage`). Адреса сервиса в синке
 * нет (не владелец, старый сервер Дел) — плашки нет: входить некуда.
 */
@Composable
internal fun MoneyWebCardV2(service: String?, onOpen: (ru.zf.pravka.core.ServicePage) -> Unit) {
    if (service == null) return
    PaperCard(label = "В вебе Денег", note = "считает «Деньги» на компе") {
        ru.zf.pravka.core.MoneyWeb.SECTIONS.forEachIndexed { i, s ->
            KvListRow(
                title = s.title,
                sub = s.sub,
                icon = ru.zf.pravka.ui.KvGlyphs.byKey(s.icon) ?: Glyphs.Forward,
                divider = i > 0,
                onClick = { onOpen(ru.zf.pravka.core.ServicePage("Деньги", "wallet", ru.zf.pravka.core.MoneyWeb.url(service, s.tab))) },
                trailing = {
                    androidx.compose.material3.Icon(
                        Glyphs.Forward, null, tint = Kv.faint,
                        modifier = Modifier.padding(top = 1.dp).then(Modifier.size(18.dp)),
                    )
                },
            )
        }
    }
}

/**
 * Готовые вопросы Claude плашкой (на холсте их нет, а в 4.0 они были
 * чипами под сегментами): ряд кнопок, свой вопрос — строкой внизу экрана.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MoneyAskCardV2(ask: MoneyAsk) {
    if (ask.busy || ask.answer.isNotEmpty()) return
    PaperCard(label = "Спросить Claude", note = "или своё — строкой внизу") {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            MONEY_ASKS.forEachIndexed { i, q ->
                ru.zf.pravka.ui.KvSmallButton(q, { ask.send(q) }, soft = i == 0, icon = if (i == 0) Glyphs.Spark else null)
            }
        }
    }
}
