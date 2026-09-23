package ru.zf.pravka

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEngine
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyMatch
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.showMoneyPlate
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint

// Вкладка «Деньги» (23.09.2026). Сверху — неделя: сколько ушло и пришло, по
// категориям, с тумблером «+ ЗФ». Дальше то, что ждёт владельца: траты без
// «ОК» и вопросы сверки. Потом выписки (файл, буфер, «Поделиться»), журнал
// недели и справочник получателей. Всё тяжёлое — в `MoneyEngine`, здесь
// только показать и передать ответ.
//
// Справочник — текстом на экране, не в коде: репозиторий публичный, а в
// справочнике имена людей (см. `core/MoneyRules.kt`).

private val WEEK = 7L * 86_400_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoneyTab(app: PravkaApp) {
    val state by app.moneyStore.stateFlow.collectAsState()
    val withZf by app.settings.mWithZfFlow.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var weekStart by remember { mutableStateOf(app.moneyEngine.weekStart(System.currentTimeMillis())) }
    var busy by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MoneyEntry?>(null) }

    LaunchedEffect(Unit) { runCatching { app.moneyStore.load() } }

    // Выписка файлом: CSV Тинькова или Альфы. Несколько за раз — недельный пакет.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val files = uris.mapNotNull { u ->
            runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
        }
        busy = "загружаю…"
        scope.launch {
            val lines = app.moneyEngine.importFiles(files)
            busy = ""
            Feedback.toast(context, lines.joinToString("\n"), long = true)
        }
    }

    val from = weekStart
    val to = weekStart + WEEK
    val summary = remember(state, from, withZf) { MoneyMatch.summary(state.entries, from, to, withZf) }
    val drafts = remember(state) { state.entries.filter { it.draft && !it.dropped }.sortedByDescending { it.ts } }
    val questions = remember(state) { app.moneyEngine.questions() }
    val week = remember(state, from) {
        state.entries.filter { !it.draft && !it.dropped && it.ts in from until to }.sortedByDescending { it.ts }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- Неделя ----
        PaperCard(label = "неделя") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weekStart -= WEEK }) { Icon(Icons.Filled.KeyboardArrowLeft, "раньше") }
                Text(
                    weekTitle(from),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { weekStart += WEEK },
                    enabled = to <= System.currentTimeMillis() + WEEK,
                ) { Icon(Icons.Filled.KeyboardArrowRight, "позже") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ушло " + MoneyFormat.rub(-summary.expenseKop), style = MaterialTheme.typography.headlineSmall)
                    if (summary.incomeKop != 0L) PaperHint("пришло " + MoneyFormat.rub(summary.incomeKop))
                }
                FilterChip(
                    selected = withZf,
                    onClick = { scope.launch { app.settings.setMWithZf(!withZf) } },
                    label = { Text("+ ЗФ") },
                )
            }
            Spacer(Modifier.height(8.dp))
            for (line in summary.lines.take(12)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(MoneyCategories.title(line.category), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(MoneyFormat.rub(-line.kop), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (summary.lines.size > 12) PaperHint("и ещё ${summary.lines.size - 12} категорий — в книге")
            if (summary.unknownCount > 0) {
                Spacer(Modifier.height(6.dp))
                PaperHint("без категории: ${MoneyFormat.rub(-summary.unknownKop)} · ${summary.unknownCount} — ждут ответа ниже")
            }
            if (summary.cashOutKop != 0L || summary.cashSpentKop != 0L) {
                PaperHint("наличные: снято ${MoneyFormat.rub(-summary.cashOutKop)}, надиктовано трат ${MoneyFormat.rub(-summary.cashSpentKop)}")
            }
            if (summary.platiInKop != 0L || summary.platiSpentKop != 0L) {
                PaperHint("Плати по миру: пополнено ${MoneyFormat.rub(-summary.platiInKop)}, разобрано покупок ${MoneyFormat.rub(-summary.platiSpentKop)}")
            }
            if (state.entries.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                PaperHint("Пока пусто. Наговори трату кнопкой «₽» или загрузи выписку ниже.")
            }
        }

        // ---- Ждут «ОК» ----
        if (drafts.isNotEmpty()) {
            PaperCard(label = "ждут «ОК» · " + drafts.size) {
                for ((takeId, group) in drafts.groupBy { it.takeId }) {
                    for (e in group) EntryRow(e) { editing = e }
                    Row {
                        TextButton(onClick = {
                            val service = PravkaAccessibilityService.instance
                            if (service != null) service.showMoneyPlate(takeId)
                            else scope.launch { app.moneyEngine.confirm(takeId, group.map { it.id }) }
                        }) { Text(if (PravkaAccessibilityService.instance != null) "Показать плашку" else "ОК всем") }
                        TextButton(onClick = { scope.launch { app.moneyEngine.confirm(takeId, group.map { it.id }) } }) {
                            Text("ОК · " + group.size)
                        }
                    }
                }
            }
        }

        // ---- Вопросы сверки ----
        if (questions.isNotEmpty()) {
            PaperCard(
                label = "вопросы · " + questions.size,
                trailing = {
                    TextButton(
                        enabled = busy.isEmpty(),
                        onClick = {
                            busy = "Claude думает…"
                            scope.launch {
                                app.moneyEngine.hint().onSuccess { n ->
                                    Feedback.toast(context, if (n == 0) "Спрашивать не о чем" else "Подсказки по $n получателям")
                                }.onFailure { e -> Feedback.toast(context, "Не вышло: ${e.message}", long = true) }
                                busy = ""
                            }
                        },
                    ) { Text("Подсказать") }
                },
            ) {
                PaperHint("Ответ запоминается: тот же получатель впредь разложится сам.")
                for (q in questions.take(30)) {
                    QuestionRow(app, q)
                }
                if (questions.size > 30) PaperHint("и ещё ${questions.size - 30}")
            }
        }

        // ---- Выписки ----
        PaperCard(label = "выписки") {
            PaperHint(
                "Тиньков и Альфа — CSV, МКБ — .xlsx, можно несколько файлов разом; «Плати по миру» — текстом чата (скопируй и вставь). " +
                    "Можно и через «Поделиться» → «Деньги: выписка». Повторы не удваиваются."
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = busy.isEmpty(), onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }) {
                    Text("Файлы выписок")
                }
                OutlinedButton(enabled = busy.isEmpty(), onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isBlank()) { Feedback.toast(context, "В буфере пусто"); return@OutlinedButton }
                    busy = "загружаю…"
                    scope.launch {
                        app.moneyEngine.import(text)
                            .onSuccess { o -> Feedback.toast(context, "${o.kind}: ${o.rows}, новых ${o.added}" + if (o.note.isNotBlank()) "\n${o.note}" else "", long = true) }
                            .onFailure { e -> Feedback.toast(context, e.message ?: "не вышло", long = true) }
                        busy = ""
                    }
                }) { Text("Вставить из буфера") }
            }
            if (busy.isNotEmpty()) PaperHint(busy)
            val lastImports = state.imports.takeLast(4).reversed()
            for (i in lastImports) {
                PaperHint("${stamp(i.ts)} · ${i.kind}: ${i.rows} строк, новых ${i.added}" + if (i.note.isNotBlank()) " · ${i.note}" else "")
            }
        }

        // ---- Журнал недели ----
        PaperCard(label = "журнал недели · " + week.size) {
            if (week.isEmpty()) PaperHint("За эту неделю записей нет.")
            for (e in week.take(200)) EntryRow(e) { editing = e }
            if (week.size > 200) PaperHint("и ещё ${week.size - 200} — в книге")
        }

        // ---- Справочник ----
        PayeesCard(app)

        TextButton(onClick = {
            scope.launch {
                runCatching { app.moneyExport.shareIntent() }
                    .onSuccess { intent ->
                        runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Деньги (.xlsx)")) }
                    }
                    .onFailure { e -> Feedback.toast(context, "Не собралась: ${e.javaClass.simpleName}: ${e.message}", long = true) }
            }
        }) { Text("Выгрузить книгу «Деньги» (.xlsx)") }
    }

    editing?.let { e ->
        EntryDialog(app, e, onDismiss = { editing = null })
    }
}

/** Одна строка журнала: что, когда, откуда, сумма; тап — правка. */
@Composable
private fun EntryRow(e: MoneyEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.what, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bits = mutableListOf(stamp(e.ts, e.timeKnown), e.source.title)
            bits.add(if (e.category.isBlank()) "без категории" else MoneyCategories.title(e.category))
            if (e.who.isNotBlank()) bits.add(MoneyCategories.whoTitle(e.who))
            if (e.matchId.isNotBlank()) bits.add("✓ сверено")
            if (e.account == MoneyEntry.CASH) bits.add("наличные")
            if (e.rubBasis == MoneyEntry.RubBasis.CBR_PRELIM) bits.add("курс предварительный")
            PaperHint(bits.joinToString(" · "))
            if (e.doubt.isNotBlank()) PaperHint("⚠ " + e.doubt, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                MoneyFormat.rub(e.rubKop, sign = true),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (e.currency != "RUB") PaperHint(MoneyFormat.orig(e.origMinor, e.currency))
        }
    }
}

/** Вопрос сверки: текст, догадка Claude (если есть) и ответ — категория и «для кого». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionRow(app: PravkaApp, q: MoneyEngine.Question) {
    val scope = rememberCoroutineScope()
    var category by remember(q.key) { mutableStateOf(q.hintCategory) }
    var who by remember(q.key) { mutableStateOf(q.hintWho) }
    val voice = q.entries.first().source == MoneyEntry.Source.VOICE
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(q.text, style = MaterialTheme.typography.bodyMedium)
        val e = q.entries.first()
        val detail = listOfNotNull(
            stamp(e.ts, e.timeKnown),
            e.note.takeIf { it.isNotBlank() }?.let { "«$it»" },
            e.bankCategory.takeIf { it.isNotBlank() }?.let { "банк: $it" },
        ).joinToString(" · ")
        PaperHint(detail)
        if (voice) {
            Row {
                TextButton(onClick = { scope.launch { app.moneyEngine.markCash(q.entries.map { it.id }) } }) { Text("Да, наличные") }
                TextButton(onClick = { scope.launch { app.moneyEngine.drop(q.entries.map { it.id }) } }) { Text("Вычеркнуть") }
            }
            return@Column
        }
        CategoryPicker(category) { category = it }
        WhoChips(who) { who = it }
        Row {
            Button(
                enabled = category.isNotBlank(),
                onClick = { scope.launch { app.moneyEngine.answer(q, category, who) } },
            ) { Text(if (q.entries.size > 1) "Запомнить · ${q.entries.size}" else "Запомнить") }
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = category.isNotBlank(),
                onClick = { scope.launch { app.moneyEngine.answer(q, category, who, remember = false) } },
            ) { Text("Только эти") }
        }
    }
}

/** Выбор категории: список по группам, как владелец читает бюджет. */
@Composable
internal fun CategoryPicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(if (current.isBlank()) "Категория…" else MoneyCategories.title(current))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = ""
            for (c in MoneyCategories.ALL) {
                if (c.group != lastGroup) {
                    lastGroup = c.group
                    DropdownMenuItem(
                        text = { Text(c.group.uppercase(Locale.forLanguageTag("ru")), style = MaterialTheme.typography.labelSmall) },
                        onClick = {},
                        enabled = false,
                    )
                }
                DropdownMenuItem(text = { Text(c.title) }, onClick = { open = false; onPick(c.key) })
            }
        }
    }
}

/** «Для кого» — пометка, не категория. Повторный тап снимает. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhoChips(current: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, title) in MoneyCategories.WHO) {
            FilterChip(selected = current == key, onClick = { onPick(if (current == key) "" else key) }, label = { Text(title) })
        }
    }
}

/** Правка одной записи: сумма, что это, категория, для кого, вычеркнуть. */
@Composable
private fun EntryDialog(app: PravkaApp, e: MoneyEntry, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf((kotlin.math.abs(e.rubKop) / 100.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(Locale.US, it) }) }
    var what by remember { mutableStateOf(e.what) }
    var category by remember { mutableStateOf(e.category) }
    var who by remember { mutableStateOf(e.who) }
    val bank = e.fromBank
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bank) e.source.title else "Трата") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bank) {
                    // Сумма и название из банка — правда; их не правим, только раскладываем.
                    Text(e.what, style = MaterialTheme.typography.bodyMedium)
                    Text(MoneyFormat.rub(e.rubKop, sign = true), style = MaterialTheme.typography.titleMedium)
                    if (e.note.isNotBlank()) PaperHint("«${e.note}»")
                    if (e.account.isNotBlank()) PaperHint(e.account)
                } else {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it },
                        label = { Text("Сумма, ₽") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(value = what, onValueChange = { what = it }, label = { Text("Что это") }, singleLine = true)
                    if (e.note.isNotBlank()) PaperHint(e.note)
                    if (e.doubt.isNotBlank()) PaperHint("⚠ " + e.doubt, color = MaterialTheme.colorScheme.error)
                }
                CategoryPicker(category) { category = it }
                WhoChips(who) { who = it }
                PaperHint(stamp(e.ts, e.timeKnown) + " · " + MoneyCategories.whoTitle(e.owner))
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val kop = if (bank) null else MoneyFormat.parseKop(amount.replace(" ", ""))
                    app.moneyEngine.edit(
                        e.id,
                        rubKop = kop,
                        what = if (bank) null else what,
                        category = category.takeIf { it != e.category },
                        who = who.takeIf { it != e.who },
                    )
                    onDismiss()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { scope.launch { app.moneyEngine.drop(listOf(e.id)); onDismiss() } }) { Text("Вычеркнуть") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}

/**
 * Справочник получателей текстом. Одна строка — одно правило:
 * «Иван П. = Помощь по дому · дети», «− Пётр С. = Лето и лагеря · Серёжа»,
 * «Марианна: Сидор С. = Между нами». Живёт только на телефоне.
 */
@Composable
private fun PayeesCard(app: PravkaApp) {
    val state by app.moneyStore.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var draft by remember(state.rules) { mutableStateOf(app.moneyEngine.rulesText()) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    PaperCard(
        label = "справочник получателей · " + state.rules.size,
        trailing = { TextButton(onClick = { open = !open }) { Text(if (open) "Свернуть" else "Открыть") } },
    ) {
        PaperHint("Кто есть кто в выписках. Хранится только на телефоне. Растёт сам от ответов на вопросы.")
        if (!open) return@PaperCard
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            label = { Text("шаблон = категория · для кого") },
        )
        PaperHint("«−» в начале — только списания, «+» — только поступления, «Марианна:» — только её счета, «|» — или.")
        for (err in errors) PaperHint(err, color = MaterialTheme.colorScheme.error)
        Row {
            Button(onClick = {
                scope.launch {
                    errors = app.moneyEngine.setRulesText(draft)
                    Feedback.toast(context, if (errors.isEmpty()) "Справочник сохранён, выписки переразложены" else "Сохранил, но есть строки с ошибкой")
                }
            }) { Text("Сохранить") }
        }
    }
}

/** Настройки Денег — группа за шестерёнкой в шапке вкладки. */
@Composable
internal fun MoneySettings(app: PravkaApp) {
    val on by app.settings.mEnabledFlow.collectAsState(initial = true)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Кнопка «₽» на экране", style = MaterialTheme.typography.bodyMedium)
                PaperHint("Четвёртая на диске: наговорил трату — плашка с суммой и «ОК».")
            }
            Switch(checked = on, onCheckedChange = { v -> app.appScope.launch { app.settings.setMEnabled(v) } })
        }
        Spacer(Modifier.height(8.dp))
        PaperHint("Модели — в «Моделях», дороги «Деньги»: траты голосом и подсказки сверки. Промпты — во вкладке «Промпты».")
    }
}

private fun weekTitle(start: Long): String {
    val f = SimpleDateFormat("d MMM", Locale.forLanguageTag("ru"))
    return f.format(Date(start)) + " — " + f.format(Date(start + WEEK - 1))
}

private fun stamp(ts: Long, withTime: Boolean = true): String =
    SimpleDateFormat(if (withTime) "d MMM, HH:mm" else "d MMM", Locale.forLanguageTag("ru")).format(Date(ts))
