package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyScope
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint

// ДДС и баланс во вкладке «Деньги» (владелец, 23.09.2026: «cashflow по месяцу
// классический и баланс»). Считает `core/MoneyCashflow.kt`; здесь — только
// таблица и карточка.

private val COL = 62.dp

/**
 * ДДС: три месяца столбцами (выбранный — последним и жирным), разделы
 * «операционная · финансовая · перемещения». Группы трат свёрнуты: тап —
 * раскрыть категории. Сверху и снизу — деньги на счетах с известным
 * остатком на начало и конец выбранного месяца.
 */
@Composable
internal fun CashflowCard(app: PravkaApp, entries: List<MoneyEntry>, month: YearMonth, ms: MoneyScope) {
    val months = remember(month) { listOf(month.minusMonths(2), month.minusMonths(1), month) }
    val rows = remember(entries, month, ms) { MoneyCashflow.build(entries, months, ms) }
    val anchors = remember(entries) { app.moneyEngine.anchors() }
    val bounds = remember(entries, month, anchors, ms) {
        months.map { ym ->
            val p = MoneyStats.of(MoneyStats.Kind.MONTH, ym.atDay(1))
            fun known(at: Long) = MoneyCashflow.balances(entries, anchors, at, at).filter { ms.showsAccount(it.name) }
                .mapNotNull { it.kop }.takeIf { it.isNotEmpty() }?.sum()
            known(p.from - 1) to known(minOf(p.to - 1, System.currentTimeMillis()))
        }
    }
    var open by remember { mutableStateOf(setOf<String>()) }
    val ru = Locale.forLanguageTag("ru")

    PaperCard(label = "ДДС · движение денег · " + MoneyFormat.K) {
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
                        r.title + if (expanded) "  ▾" else "  ▸", r.values.map { it }, bold = true,
                        onClick = { open = if (expanded) open - r.title else open + r.title },
                    )
                }
                MoneyCashflow.Kind.LINE -> if (group.isEmpty() || group in open) {
                    CashLine(r.title, r.values.map { it }, bold = false, indent = group.isNotEmpty())
                }
                MoneyCashflow.Kind.TOTAL -> {
                    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CashLine(r.title, r.values.map { it }, bold = true, colored = r.key != "moves")
                    group = ""
                }
                MoneyCashflow.Kind.NOTE -> PaperHint(r.title)
            }
        }
        if (bounds.any { it.second != null }) {
            Spacer(Modifier.height(4.dp))
            CashLine("На конец (счета с остатком)", bounds.map { it.second }, bold = true, hint = false)
        }
        Spacer(Modifier.height(6.dp))
        PaperHint(
            "Приток плюсом, отток минусом. Перемещения — не трата: между своими счетами, супругами, " +
                "пополнение «Плати по миру», наличные. Их нетто не ноль — часть денег ушла на счёт без выписки."
        )
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
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Row(base.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (hint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = if (indent) 12.dp else 0.dp),
        )
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
                modifier = Modifier.width(COL),
            )
        }
    }
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
    val anchors = remember(entries, app.moneyStore.stateFlow.value.balances) { app.moneyEngine.anchors() }
    val accounts = remember(entries, anchors, ms) {
        MoneyCashflow.balances(entries, anchors, now, now - 90L * 86_400_000L).filter { ms.showsAccount(it.name) }
    }
    val loans = remember(entries) { MoneyCashflow.loanDebt(entries, now) }
    var editing by remember { mutableStateOf<String?>(null) }

    val assets = accounts.filter { (it.kop ?: -1) >= 0 }
    val debts = accounts.filter { (it.kop ?: 0) < 0 }
    val unknown = accounts.filter { it.kop == null }
    val assetSum = assets.sumOf { it.kop ?: 0 }
    val debtSum = debts.sumOf { it.kop ?: 0 } - loans

    PaperCard(label = "баланс · сейчас · " + (if (ms.both) "всё" else if (ms.zf) "ЗФ" else "личное") + " · " + MoneyFormat.K) {
        Text("Активы", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        for (a in assets) AccountRow(a) { editing = a.name }
        TotalRow("Итого активы", assetSum, incomeColor())
        Spacer(Modifier.height(8.dp))
        Text("Обязательства", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        for (a in debts) AccountRow(a) { editing = a.name }
        if (loans > 0) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Займы у людей", style = MaterialTheme.typography.bodyMedium)
                    PaperHint("получено минус возвращено, по журналу с первой выписки")
                }
                Text(MoneyFormat.k(-loans), style = MaterialTheme.typography.bodyMedium, color = spentColor())
            }
        }
        TotalRow("Итого долги", debtSum, spentColor())
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        TotalRow("Чистые активы", assetSum + debtSum, if (assetSum + debtSum < 0) spentColor() else incomeColor(), big = true)
        if (unknown.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Остаток неизвестен", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (a in unknown) AccountRow(a) { editing = a.name }
            PaperHint("В выписках остатков нет — тапни счёт и впиши, сколько на нём сейчас; дальше посчитается по движениям.")
        }
    }

    editing?.let { name ->
        var text by remember(name) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(name) },
            text = {
                Column {
                    PaperHint("Сколько на счёте сейчас, в рублях. Долг — с минусом: −733000.")
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kop = MoneyFormat.parseKop(text)
                    if (kop != null) scope.launch { app.moneyEngine.setBalance(name, kop) }
                    editing = null
                }) { Text("Запомнить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
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
    val anchors = remember(entries, app.moneyStore.stateFlow.value.balances) { app.moneyEngine.anchors() }
    val flows = remember(entries, anchors, period, ms) {
        MoneyCashflow.accountFlows(entries, anchors, period.from, period.to, System.currentTimeMillis())
            .filter { it.inKop != 0L || it.outKop != 0L || it.endKop != null }
            .filter { ms.showsAccount(it.name) }
    }
    var open by remember { mutableStateOf(setOf<String>()) }
    PaperCard(label = "счета · откуда и куда · " + MoneyFormat.K) {
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
                    Text(
                        f.name + if (expanded) "  ▾" else "  ▸",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        f.endKop?.let { MoneyFormat.k(it) } ?: "?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if ((f.endKop ?: 0) < 0) spentColor() else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row {
                    PaperHint("было " + (f.startKop?.let { MoneyFormat.k(it) } ?: "?") + "   ")
                    if (f.inKop != 0L) Text("+" + MoneyFormat.k(f.inKop) + "   ", style = MaterialTheme.typography.bodySmall, color = incomeColor())
                    if (f.outKop != 0L) Text(MoneyFormat.k(f.outKop), style = MaterialTheme.typography.bodySmall, color = spentColor())
                }
                if (expanded) {
                    // Чей счёт: отметка владельца решает, куда он встанет — в «Личное» или в «ЗФ».
                    if (f.name != MoneyCashflow.NATASHA_DEBT && f.name != MoneyCashflow.WALLET) {
                        val isZf = f.name in ms.zfAccounts
                        TextButton(onClick = { scope.launch { app.moneyEngine.setZfAccount(f.name, !isZf) } }) {
                            Text(if (isZf) "счёт ЗФ · сделать личным" else "личный счёт · это счёт ЗФ")
                        }
                    }
                    for ((cat, kop) in f.byCategory.take(12)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp)) {
                            Text(cat, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                MoneyFormat.k(kop, sign = true),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (kop < 0) spentColor() else incomeColor(),
                            )
                        }
                    }
                }
            }
        }
        PaperHint("Остаток — от якоря (снимок, вписанное, «Доступно» из пуша); «?» — впиши в «балансе» тапом по счёту.")
    }
}
