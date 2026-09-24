package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MoneyCashflow
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyScope
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.SheetAction

// ДДС и баланс во вкладке «Деньги» (владелец, 23.09.2026: «cashflow по месяцу
// классический и баланс»). Считает `core/MoneyCashflow.kt`; здесь — только
// таблица и карточка.

private val COL = 78.dp

/** Тонкая линия под строкой: «к каждой строке — аккуратная линия, иначе не видно, что к чему» (владелец). */
@Composable
internal fun RowLine() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

/**
 * Шеврон раскрытия у строки, которая разворачивается тапом (группа ДДС, счёт,
 * категория). Вместо «▾/▸» в конце текста (24.09.2026): одно раскрытие на
 * всё приложение. Вниз — свёрнуто, вверх — раскрыто, как у `SummaryLine`
 * набора; отдельного значка-шеврона в Kit.kt нет, поэтому он здесь.
 */
@Composable
internal fun FoldChevron(expanded: Boolean) {
    Icon(
        Glyphs.ChevronDown,
        contentDescription = if (expanded) "свернуть" else "раскрыть",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp).size(14.dp).rotate(if (expanded) 180f else 0f),
    )
}

/** Что показать во всплывающем окне: цифра и операции, из которых она сложилась. */
internal data class Breakdown(
    val title: String,
    val subtitle: String,
    val items: List<MoneyCashflow.Item>,
    val onEdit: (() -> Unit)? = null,
)

/**
 * Окно «из чего это состоит» (владелец, 23.09.2026: «нажимаю на цифру —
 * всплывает окно, и всё видно, из чего это состоит в конкретный месяц»):
 * итог, дальше по категориям с подытогом, в каждой — операции с датой.
 */
@Composable
internal fun BreakdownDialog(b: Breakdown, onDismiss: () -> Unit) {
    val ru = Locale.forLanguageTag("ru")
    val groups = remember(b) {
        b.items.groupBy { MoneyCategories.title(it.entry.category) }
            .map { (t, l) -> Triple(t, l.sumOf { it.kop }, l) }
            .sortedByDescending { kotlin.math.abs(it.second) }
    }
    // Лист вместо AlertDialog (24.09.2026) — и без потолка в 460 dp: лист сам
    // растёт до высоты экрана, а операций в ячейке бывает много. Строки —
    // ленивым списком: прокрутка листа выключена, крутит сам список.
    // «Закрыть» ушло в крестик шапки; «Вписать остаток» — главная внизу.
    val edit = b.onEdit
    PaperSheet(
        onDismiss = onDismiss,
        title = b.title,
        icon = Glyphs.ListLines,
        subtitle = b.subtitle,
        scroll = false,
        footer = if (edit != null) {
            {
                Spacer(Modifier.weight(1f))
                PaperButton("Вписать остаток", icon = Glyphs.Edit, primary = true, onClick = { onDismiss(); edit() })
            }
        } else null,
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text("Итого · ${b.items.size} опер.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(MoneyFormat.k(b.items.sumOf { it.kop }, sign = true) + " " + MoneyFormat.K, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                RowLine()
            }
            if (b.items.isEmpty()) item { PaperHint("Операций нет.") }
            for ((title, sum, list) in groups) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text(MoneyFormat.k(sum, sign = true), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(list.size) { i ->
                    val it = list[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(SimpleDateFormat("d MMM", ru).format(Date(it.entry.ts)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.entry.what, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val acc = it.entry.account.ifBlank { it.entry.source.title }
                            if (acc.isNotBlank()) PaperHint(acc)
                        }
                        Text(
                            MoneyFormat.k(it.kop, sign = true),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.kop < 0) spentColor() else incomeColor(),
                        )
                    }
                    RowLine()
                }
            }
        }
    }
}

/**
 * ДДС: три месяца столбцами (выбранный — последним и жирным), разделы
 * «операционная · финансовая · перемещения». Группы трат свёрнуты: тап —
 * раскрыть категории. Сверху и снизу — деньги на счетах с известным
 * остатком на начало и конец выбранного месяца.
 */
@Composable
internal fun CashflowCard(app: PravkaApp, entries: List<MoneyEntry>, month: YearMonth, ms: MoneyScope) {
    val months = remember(month) { listOf(month.minusMonths(2), month.minusMonths(1), month) }
    val data = rememberCalc("dds", Ref(entries), month, ms) {
        val anchors = app.moneyEngine.anchors()
        val rows = MoneyCashflow.build(entries, months, ms)
        val bounds = months.map { ym ->
            val p = MoneyStats.of(MoneyStats.Kind.MONTH, ym.atDay(1))
            fun known(at: Long) = MoneyCashflow.balances(entries, anchors, at, at).filter { ms.showsAccount(it.name) }
                .mapNotNull { it.kop }.takeIf { it.isNotEmpty() }?.sum()
            known(p.from - 1) to known(minOf(p.to - 1, System.currentTimeMillis()))
        }
        rows to bounds
    }
    if (data == null) {
        PaperCard(label = "ДДС · движение денег · " + MoneyFormat.K) { PaperHint("считаю…") }
        return
    }
    val (rows, bounds) = data
    var open by remember { mutableStateOf(setOf<String>()) }
    var shown by remember { mutableStateOf<Breakdown?>(null) }
    val ru = Locale.forLanguageTag("ru")
    fun monthName(m: YearMonth) = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", ru).format(m.atDay(1))
    fun cell(r: MoneyCashflow.Row): (Int) -> Unit = { i ->
        shown = Breakdown(r.title, monthName(months[i]), MoneyCashflow.cellItems(entries, months[i], ms, r))
    }

    shown?.let { BreakdownDialog(it) { shown = null } }
    PaperCard(
        label = "ДДС · движение денег · " + MoneyFormat.K,
        info = "Приток плюсом, отток минусом. Перемещения — не трата: между своими счетами, супругами, " +
            "пополнение «Плати по миру», наличные. Их нетто не ноль — часть денег ушла на счёт без выписки. " +
            "Группы трат свёрнуты — тап по строке раскрывает категории; тап по цифре — операции этой ячейки.",
    ) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            months.forEachIndexed { i, m ->
                Text(
                    java.time.format.DateTimeFormatter.ofPattern("LLL", ru).format(m.atDay(1)).trimEnd('.'),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == months.lastIndex) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(COL),
                )
            }
        }
        if (bounds.any { it.first != null }) {
            CashLine("На начало (счета с остатком)", bounds.map { it.first }, bold = false, hint = true)
        }
        var group = ""
        for (r in rows) {
            when (r.kind) {
                MoneyCashflow.Kind.SECTION -> {
                    Spacer(Modifier.height(8.dp))
                    Text(r.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    group = ""
                }
                MoneyCashflow.Kind.GROUP -> {
                    group = r.title
                    val expanded = r.title in open
                    CashLine(
                        r.title, r.values.map { it }, bold = true,
                        expanded = expanded,
                        onClick = { open = if (expanded) open - r.title else open + r.title },
                        onCell = cell(r),
                    )
                }
                MoneyCashflow.Kind.LINE -> if (group.isEmpty() || group in open) {
                    CashLine(r.title, r.values.map { it }, bold = false, indent = group.isNotEmpty(), onCell = cell(r))
                }
                MoneyCashflow.Kind.TOTAL -> {
                    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CashLine(r.title, r.values.map { it }, bold = true, colored = r.key != "moves", onCell = cell(r))
                    group = ""
                }
                MoneyCashflow.Kind.NOTE -> PaperHint(r.title)
            }
        }
        if (bounds.any { it.second != null }) {
            Spacer(Modifier.height(4.dp))
            CashLine("На конец (счета с остатком)", bounds.map { it.second }, bold = true, hint = false)
        }
    }
}

@Composable
private fun CashLine(
    title: String,
    values: List<Long?>,
    bold: Boolean,
    indent: Boolean = false,
    colored: Boolean = false,
    hint: Boolean = false,
    // Не null — строка раскрывается тапом, у названия шеврон.
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onCell: ((Int) -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Row(base.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).padding(start = if (indent) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = if (hint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (expanded != null) FoldChevron(expanded)
        }
        values.forEachIndexed { i, v ->
            val color = when {
                v == null -> MaterialTheme.colorScheme.onSurfaceVariant
                colored && v < 0 -> spentColor()
                colored && v > 0 -> incomeColor()
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                v?.let { if (it == 0L) "—" else MoneyFormat.k(it) } ?: "?",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold || i == values.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
                textAlign = TextAlign.End,
                // Тап по цифре — окно с операциями этой ячейки.
                modifier = Modifier.width(COL).let { m -> if (onCell != null && v != null && v != 0L) m.clickable { onCell(i) } else m },
            )
        }
    }
    RowLine()
}

/**
 * Баланс сейчас: счета с остатком (активы), долги (кредитка, кредит,
 * «Долями», займы у людей по журналу) и чистые активы. Остаток — от
 * самого позднего якоря счёта; под суммой — откуда он. Тап по счёту —
 * вписать остаток руками. Счёт без якоря — «?», не ноль.
 */
@Composable
internal fun BalanceCard(app: PravkaApp, entries: List<MoneyEntry>, ms: MoneyScope) {
    val scope = app.appScope
    val now = System.currentTimeMillis()
    val balancesRef = Ref(app.moneyStore.stateFlow.value.balances)
    val data = rememberCalc("balance", Ref(entries), balancesRef, ms) {
        val anchors = app.moneyEngine.anchors()
        Triple(
            MoneyCashflow.balances(entries, anchors, now, now - 90L * 86_400_000L).filter { ms.showsAccount(it.name) },
            MoneyCashflow.loanDebt(entries, now),
            MoneyCashflow.loansByLender(entries, now),
        )
    }
    if (data == null) {
        PaperCard(label = "баланс · " + MoneyFormat.K) { PaperHint("считаю…") }
        return
    }
    val (accounts, loans, lenders) = data
    var editing by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf<Breakdown?>(null) }
    fun open(a: MoneyCashflow.Account) {
        val from = a.anchor?.ts?.plus(1) ?: (now - 90L * 86_400_000L)
        val sub = a.anchor?.let {
            "от остатка " + MoneyFormat.k(it.kop) + " на " + SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(it.ts)) + " (" + it.source + ")"
        } ?: "остаток неизвестен — движения за 90 дней"
        shown = Breakdown(a.name, sub, MoneyCashflow.accountItems(entries, a.name, from, now + 1)) { editing = a.name }
    }
    shown?.let { BreakdownDialog(it) { shown = null } }

    // Долговой счёт — в обязательствах и с нулём: «Займ от ЗФ 0» в активах читался как деньги.
    val assets = accounts.filter { it.kop != null && it.kop >= 0 && !MoneyCashflow.isDebtAccount(it.name) }
    val debts = accounts.filter { it.kop != null && (it.kop < 0 || MoneyCashflow.isDebtAccount(it.name)) }
    val unknown = accounts.filter { it.kop == null }
    val assetSum = assets.sumOf { it.kop ?: 0 }
    val debtSum = debts.sumOf { it.kop ?: 0 } - loans

    PaperCard(
        label = "баланс · сейчас · " + (if (ms.both) "всё" else if (ms.zf) "ЗФ" else "личное") + " · " + MoneyFormat.K,
        info = "Остаток счёта — от самого позднего якоря (снимок, вписанное, «Доступно» из пуша); " +
            "под суммой — откуда он. Тап по счёту — его операции и «Вписать остаток». Займы у людей — " +
            "по каждому, кто давал: получено минус возвращено, по журналу с первой выписки. Счёт без " +
            "якоря — «?», не ноль: в выписках остатков нет — тапни счёт и впиши, сколько на нём " +
            "сейчас; дальше посчитается по движениям.",
    ) {
        Text("Активы", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        for (a in assets) AccountRow(a) { open(a) }
        TotalRow("Итого активы", assetSum, incomeColor())
        Spacer(Modifier.height(8.dp))
        Text("Обязательства", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        for (a in debts) AccountRow(a) { open(a) }
        // Займы у людей — по каждому, кто давал: получено минус возвращено по журналу.
        for ((who, kop) in lenders.filter { it.second > 0 }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Займ: $who", style = MaterialTheme.typography.bodyMedium)
                    PaperHint("по журналу")
                }
                Text(MoneyFormat.k(-kop), style = MaterialTheme.typography.bodyMedium, color = spentColor())
            }
        }
        TotalRow("Итого долги", debtSum, spentColor())
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        TotalRow("Чистые активы", assetSum + debtSum, if (assetSum + debtSum < 0) spentColor() else incomeColor(), big = true)
        if (unknown.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Остаток неизвестен", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (a in unknown) AccountRow(a) { open(a) }
            PaperHint("тап по счёту — вписать остаток")
        }
    }

    editing?.let { name ->
        var text by remember(name) { mutableStateOf("") }
        // Лист вместо AlertDialog (24.09.2026); «Отмена» — крестиком и свайпом.
        PaperAlert(
            onDismiss = { editing = null },
            title = name,
            icon = Glyphs.Edit,
            subtitle = "остаток сейчас",
            confirm = SheetAction("Запомнить", icon = Glyphs.Check) {
                val kop = MoneyFormat.parseKop(text)
                if (kop != null) scope.launch { app.moneyEngine.setBalance(name, kop) }
                editing = null
            },
        ) {
            PaperHint("Сколько на счёте сейчас, в рублях. Долг — с минусом: −733000.")
            PaperField(
                value = text,
                onValueChange = { text = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
    }
}

@Composable
private fun AccountRow(a: MoneyCashflow.Account, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PaperHint(
                a.anchor?.let { "${it.source} · " + SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(it.ts)) }
                    ?: ("за 90 дней: " + MoneyFormat.k(a.flowKop, sign = true))
            )
        }
        Text(
            a.kop?.let { MoneyFormat.k(it) } ?: "?",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if ((a.kop ?: 0) < 0) spentColor() else MaterialTheme.colorScheme.onSurface,
        )
    }
    RowLine()
}

@Composable
private fun TotalRow(title: String, kop: Long, color: androidx.compose.ui.graphics.Color, big: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(MoneyFormat.k(kop), style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/**
 * Счета за период: куда пришло и откуда ушло (владелец, 23.09.2026: «счета,
 * на которые приходит всё это и откуда списывается — кеш, Тиньков, Альфа»).
 * На строке — остаток на конец (или «?»), под ним «было · пришло · ушло»;
 * тап раскрывает по категориям. Наличные и долг Наташе — такие же счета.
 */
@Composable
internal fun AccountsCard(app: PravkaApp, entries: List<MoneyEntry>, period: MoneyStats.Period, ms: MoneyScope) {
    val scope = app.appScope
    val flows = rememberCalc("accounts", Ref(entries), Ref(app.moneyStore.stateFlow.value.balances), period, ms) {
        MoneyCashflow.accountFlows(entries, app.moneyEngine.anchors(), period.from, period.to, System.currentTimeMillis())
            .filter { it.inKop != 0L || it.outKop != 0L || it.endKop != null }
            .filter { ms.showsAccount(it.name) }
    }
    if (flows == null) {
        PaperCard(label = "счета · " + MoneyFormat.K) { PaperHint("считаю…") }
        return
    }
    var open by remember { mutableStateOf(setOf<String>()) }
    var shown by remember { mutableStateOf<Breakdown?>(null) }
    val ru = Locale.forLanguageTag("ru")
    val span = SimpleDateFormat("d MMM", ru).format(Date(period.from)) + " — " + SimpleDateFormat("d MMM", ru).format(Date(period.to - 1))
    fun show(name: String, what: String, pred: (MoneyCashflow.Item) -> Boolean) {
        shown = Breakdown("$name · $what", span, MoneyCashflow.accountItems(entries, name, period.from, period.to).filter(pred))
    }
    shown?.let { BreakdownDialog(it) { shown = null } }
    PaperCard(
        label = "счета · откуда и куда · " + MoneyFormat.K,
        info = "На строке — остаток на конец периода, под ним «было · пришло · ушло». Тап по строке " +
            "раскрывает счёт по категориям, тап по цифре — из чего она сложилась. Остаток — от якоря " +
            "(снимок, вписанное, «Доступно» из пуша); «?» — впиши в «балансе» тапом по счёту.",
    ) {
        if (flows.isEmpty()) {
            PaperHint("За период движений нет.")
            return@PaperCard
        }
        for (f in flows) {
            val expanded = f.name in open
            Column(
                Modifier.fillMaxWidth()
                    .clickable { open = if (expanded) open - f.name else open + f.name }
                    .padding(vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        FoldChevron(expanded)
                    }
                    Text(
                        f.endKop?.let { MoneyFormat.k(it) } ?: "?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if ((f.endKop ?: 0) < 0) spentColor() else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable { show(f.name, "все движения") { true } },
                    )
                }
                Row {
                    PaperHint("было " + (f.startKop?.let { MoneyFormat.k(it) } ?: "?") + "   ")
                    // Тап по «пришло» или «ушло» — из чего это состоит за период.
                    if (f.inKop != 0L) Text(
                        "+" + MoneyFormat.k(f.inKop) + "   ", style = MaterialTheme.typography.bodySmall, color = incomeColor(),
                        modifier = Modifier.clickable { show(f.name, "пришло") { it.kop > 0 } },
                    )
                    if (f.outKop != 0L) Text(
                        MoneyFormat.k(f.outKop), style = MaterialTheme.typography.bodySmall, color = spentColor(),
                        modifier = Modifier.clickable { show(f.name, "ушло") { it.kop < 0 } },
                    )
                }
                if (expanded) {
                    // Чей счёт: отметка владельца решает, куда он встанет — в «Личное» или в «ЗФ».
                    if (f.name != MoneyCashflow.NATASHA_DEBT && f.name != MoneyCashflow.WALLET) {
                        val isZf = f.name in ms.zfAccounts
                        PaperTextButton(
                            if (isZf) "счёт ЗФ · сделать личным" else "личный счёт · это счёт ЗФ",
                            icon = Glyphs.Tag,
                            onClick = { scope.launch { app.moneyEngine.setZfAccount(f.name, !isZf) } },
                        )
                    }
                    for ((cat, kop) in f.byCategory.take(12)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { show(f.name, cat) { MoneyCategories.title(it.entry.category) == cat } }
                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(cat, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                MoneyFormat.k(kop, sign = true),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (kop < 0) spentColor() else incomeColor(),
                            )
                        }
                        RowLine()
                    }
                }
            }
            RowLine()
        }
    }
}
