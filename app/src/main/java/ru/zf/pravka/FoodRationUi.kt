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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MealItem
import ru.zf.pravka.core.Ration
import ru.zf.pravka.data.RationBook
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.SummaryLine

/**
 * «Мой рацион» — сворачиваемый список его еды: блюда («моя каша», «индейка с
 * кускусом») и продукты, где порция записывается тапом, а рацион правится
 * карандашом.
 *
 * Зачем он, если есть микрофон. Половина еды владельца повторяется каждый день
 * и уже посчитана с настоящих этикеток. Говорить про неё — это платить модели
 * за то, что и так известно точно. Голос рацион тоже знает (он уезжает в
 * промпт блюдами и тем, как владелец их называет), но тап надёжнее любого
 * промпта: он бесплатный, мгновенный и работает на даче без интернета.
 *
 * Сам рацион — запись Свода `food.ration` на сервере Дел (13.10.2026). Правят
 * его здесь (карандаш у строки, «+ продукт», «+ блюдо») и словами в claude.ai
 * (коннектор: `ration_product`, `ration_dish`); правка отсюда уходит целым
 * рационом и видна сразу. Внизу карточки — откуда он сейчас.
 */
@Composable
internal fun RationSection(app: PravkaApp, dayStart: Long) {
    var open by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(app.rationBook.loaded) }
    var asking by remember { mutableStateOf<Ration.Product?>(null) }
    // Лист правки: продукт или блюдо; `null` внутри — новый.
    var editProduct by remember { mutableStateOf<Pair<Boolean, Ration.Product?>>(false to null) }
    var editDish by remember { mutableStateOf<Pair<Boolean, Ration.Dish?>>(false to null) }
    // Новая версия рациона приезжает синком Свода (и своей правкой) — карточка перечитывает её.
    val svod by app.svodStore.view.collectAsState()
    val book = remember(svod, loaded) { app.rationBook.book }

    LaunchedEffect(open) {
        if (open && !loaded) {
            app.rationBook.load()
            loaded = app.rationBook.loaded
        }
    }

    /** Записать порции сразу подтверждённым приёмом: цифры с этикеток, спорить не о чем. */
    val put: (String, String, List<MealItem>) -> Unit = { title, kind, items ->
        if (items.isNotEmpty()) {
            app.appScope.launch {
                val parsed = app.foodEngine.record(
                    items = items,
                    kind = kind,
                    timeOfDay = "",
                    raw = "рацион: $title",
                    note = "",
                    source = "ration",
                    // Листаешь дневник назад — запись ложится в тот день, а не в
                    // сегодняшний.
                    at = mealStampFor(dayStart),
                )
                val outcome = app.foodEngine.confirm(parsed.meal.id)
                Feedback.toast(
                    app,
                    "Записал: ${parsed.meal.kcal} ккал · Б${parsed.meal.protein} " +
                        "Ж${parsed.meal.fat} У${parsed.meal.carbs}" +
                        (if (outcome.icuError.isNotBlank()) " (в intervals не уехало)" else ""),
                )
            }
        }
    }

    /** Рацион поправлен — целиком новой версией Свода; итог тостом. */
    val save: (Ration.Book, String) -> Unit = { next, reason ->
        app.appScope.launch {
            val ok = app.rationBook.save(next, reason)
            Feedback.toast(
                app,
                when {
                    !ok -> "Рацион не сохранился: Свода нет"
                    app.svodOn() -> "$reason — уйдёт на сервер Дел"
                    else -> "$reason — на телефоне; сервер Дел не подключён, отдам, когда появится"
                },
            )
        }
    }

    // Свёрнутый рацион — одна строка-сводка с шевроном (24.09.2026), а что это
    // и как им пользоваться — за «i»: абзац нужен раз, а стоял бы каждый день.
    PaperCard(
        label = "мой рацион",
        info = "Его постоянная еда: блюда — наборы продуктов («моя каша»), и " +
            "продукты с КБЖУ с этикеток. Тап по блюду — записать его целиком, " +
            "тап по продукту — его порцию, тап по граммам — другой вес, " +
            "карандаш — поправить рацион. Голосом рацион тоже понятен: «моя каша " +
            "без клубники», «индейка с кускусом». Живёт на сервере Дел (Свод, " +
            "food.ration); править можно и словами в claude.ai.",
    ) {
        SummaryLine(
            title = "Блюда и продукты",
            summary = if (loaded) "${book.dishes.size} бл. · ${book.products.size} прод." else "",
            expanded = open,
            onToggle = { open = !open },
        ) {
            if (!loaded) {
                PaperHint("Читаю рацион…")
                return@SummaryLine
            }
            // Своя колонка без шага: у набора шаг 8 между всеми детьми, а тут
            // строки рациона плотные и отбиваются только заголовки.
            Column {
                Spacer(Modifier.height(10.dp))
                Text("Блюда", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                for (d in book.dishes) {
                    DishRow(
                        dish = d,
                        book = book,
                        // Приём блюда — его обычный; у блюда без приёма движок возьмёт по часу.
                        onPut = { put(d.name, d.kind, book.items(d)) },
                        onEdit = { editDish = true to d },
                    )
                }
                PaperTextButton("Блюдо", onClick = { editDish = true to null }, icon = Glyphs.Plus)
                Spacer(Modifier.height(6.dp))
                Text("Продукты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                for (p in book.products) {
                    RationRow(
                        product = p,
                        onPut = { p.item().let { put(gramsTitle(it), "", listOf(it)) } },
                        onGrams = { asking = p },
                        onEdit = { editProduct = true to p },
                    )
                }
                PaperTextButton("Продукт", onClick = { editProduct = true to null }, icon = Glyphs.Plus)
                Spacer(Modifier.height(8.dp))
                PaperHint(
                    when {
                        app.rationBook.source == RationBook.Source.SVOD && app.rationBook.svodRev > 0 ->
                            "С сервера Дел · версия ${app.rationBook.svodRev}"
                        // Своя правка или засев в очереди Дел: запись видна сразу, версии у неё ещё нет.
                        app.rationBook.source == RationBook.Source.SVOD && app.svodOn() -> "Кладу на сервер Дел — уйдёт с синком"
                        app.rationBook.source == RationBook.Source.SVOD -> "Своя правка — сервер Дел не подключён, держу на телефоне"
                        app.rationBook.source == RationBook.Source.NONE -> "Рацион не прочитался"
                        !app.svodOn() -> "Из сборки — сервер Дел не подключён, рациону там негде жить"
                        else -> "Из сборки — на сервере Дел рациона пока нет: ляжет со следующим синком Дел"
                    }
                )
            }
        }
    }

    val ask = asking
    if (ask != null) {
        GramsDialog(
            product = ask,
            onClose = { asking = null },
            onPut = { grams ->
                asking = null
                ask.item(grams).let { put(gramsTitle(it), "", listOf(it)) }
            },
        )
    }

    if (editProduct.first) {
        val was = editProduct.second
        ProductSheet(
            product = was,
            book = book,
            onClose = { editProduct = false to null },
            onSave = { p ->
                editProduct = false to null
                save(Ration.withProduct(book, was?.id, p), (if (was == null) "Завёл" else "Поправил") + " «${p.name}»")
            },
            onDelete = was?.let { w ->
                {
                    editProduct = false to null
                    save(Ration.withoutProduct(book, w.id), "Убрал «${w.name}»")
                }
            },
        )
    }

    if (editDish.first) {
        val was = editDish.second
        DishSheet(
            dish = was,
            book = book,
            onClose = { editDish = false to null },
            onSave = { d ->
                editDish = false to null
                save(Ration.withDish(book, was?.id, d), (if (was == null) "Завёл" else "Поправил") + " «${d.name}»")
            },
            onDelete = was?.let { w ->
                {
                    editDish = false to null
                    save(Ration.withoutDish(book, w.id), "Убрал «${w.name}»")
                }
            },
        )
    }
}

/** «Творог 5% 400 г» — сырьё записи рациона: что и сколько, как сказал бы владелец. */
private fun gramsTitle(i: MealItem): String = if (i.pill || i.grams <= 0) i.name else "${i.name} ${i.grams} г"

@Composable
private fun DishRow(
    dish: Ration.Dish,
    book: Ration.Book,
    onPut: () -> Unit,
    onEdit: () -> Unit,
) {
    val items = book.items(dish)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPut)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(dish.name, style = MaterialTheme.typography.bodyMedium)
            PaperHint(
                items.joinToString(", ") { if (it.pill) it.name else it.name + " " + it.grams } +
                    " · ${items.sumOf { it.kcal }} ккал"
            )
        }
        Spacer(Modifier.width(6.dp))
        GlyphButton(Glyphs.Edit, "поправить «${dish.name}»", onClick = onEdit)
        GlyphButton(Glyphs.Plus, "записать «${dish.name}»", onClick = onPut)
    }
}

@Composable
private fun RationRow(
    product: Ration.Product,
    onPut: () -> Unit,
    onGrams: () -> Unit,
    onEdit: () -> Unit,
) {
    val i = product.item()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPut)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(product.name, style = MaterialTheme.typography.bodyMedium)
            PaperHint(
                if (product.pill) "добавка" + (if (product.note.isNotBlank()) " · " + product.note else "")
                else "${i.kcal} ккал · Б${i.protein} Ж${i.fat} У${i.carbs}" +
                    (if (product.sure != "точно") " · ${product.sure}" else "")
            )
        }
        Spacer(Modifier.width(6.dp))
        GlyphButton(Glyphs.Edit, "поправить «${product.name}»", onClick = onEdit)
        if (!product.pill) {
            // Граммы — своя кнопка: порция меняется чаще, чем состав.
            PaperTextButton("${i.grams} г", onClick = onGrams)
        }
    }
}

@Composable
private fun GramsDialog(
    product: Ration.Product,
    onClose: () -> Unit,
    onPut: (Int) -> Unit,
) {
    var text by remember {
        mutableStateOf(product.grams.takeIf { it > 0 }?.toString() ?: "100")
    }
    val grams = text.toIntOrNull() ?: 0
    PaperAlert(
        onDismiss = onClose,
        title = product.name,
        icon = Glyphs.Food,
        subtitle = if (product.grams > 0) "обычная порция ${product.grams} г" else null,
        confirm = SheetAction("Записать", icon = Glyphs.Check, enabled = grams > 0) {
            if (grams > 0) onPut(grams)
        },
    ) {
        NumberField("Граммы", text, Modifier.fillMaxWidth()) { text = it }
        if (grams > 0) {
            val i = product.item(grams)
            Text(
                "${i.kcal} ккал · Б${i.protein} Ж${i.fat} У${i.carbs}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (product.note.isNotBlank()) PaperHint(product.note)
    }
}

// ---- Правка рациона (13.10.2026, владелец: «надо иметь возможность этот рацион редактировать») ----

/** Число с десятыми: «6,5» и «6.5» — одно и то же, этикетки пишут по-разному. */
@Composable
private fun DecimalField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    PaperField(
        value = value,
        onValueChange = { s ->
            val t = s.replace(',', '.').filter { it.isDigit() || it == '.' }
            onChange(if (t.count { it == '.' } > 1) value else t.take(6))
        },
        modifier = modifier,
        label = label,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun num(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

private fun words(s: String): List<String> = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Продукт: название, обычная порция, КБЖУ на 100 г, как владелец его называет,
 * «цифры с этикетки» и добавка. Вещества на 100 г здесь не правятся (двадцать
 * полей на телефоне никто заполнять не будет) — они остаются как были; их
 * правит Claude в коннекторе, с этикетки или по таблицам.
 */
@Composable
private fun ProductSheet(
    product: Ration.Product?,
    book: Ration.Book,
    onClose: () -> Unit,
    onSave: (Ration.Product) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(product?.name.orEmpty()) }
    var grams by remember { mutableStateOf(product?.grams?.takeIf { it > 0 }?.toString().orEmpty()) }
    var kcal by remember { mutableStateOf(product?.kcal100?.let(::num).orEmpty()) }
    var protein by remember { mutableStateOf(product?.protein100?.let(::num).orEmpty()) }
    var fat by remember { mutableStateOf(product?.fat100?.let(::num).orEmpty()) }
    var carbs by remember { mutableStateOf(product?.carbs100?.let(::num).orEmpty()) }
    var aliases by remember { mutableStateOf(product?.aliases?.joinToString(", ").orEmpty()) }
    var note by remember { mutableStateOf(product?.note.orEmpty()) }
    var label by remember { mutableStateOf((product?.sure ?: "точно") == "точно") }
    var pill by remember { mutableStateOf(product?.pill == true) }

    val k = kcal.toDoubleOrNull()
    val p = protein.toDoubleOrNull()
    val f = fat.toDoubleOrNull()
    val c = carbs.toDoubleOrNull()
    val g = grams.toIntOrNull() ?: 0
    val clash = book.products.any { it.id != product?.id && it.name.trim().equals(name.trim(), ignoreCase = true) }
    val ready = name.isNotBlank() && !clash &&
        (pill || (k != null && p != null && f != null && c != null && g > 0 && p + f + c <= 101))
    val used = product?.let { pr -> book.dishes.filter { d -> d.items.any { it.product == pr.id } }.map { it.name } }.orEmpty()

    PaperAlert(
        onDismiss = onClose,
        title = if (product == null) "Новый продукт" else product.name,
        icon = Glyphs.Food,
        subtitle = if (used.isNotEmpty()) "в блюдах: " + used.joinToString(", ") else null,
        confirm = SheetAction("Сохранить", icon = Glyphs.Check, enabled = ready) {
            onSave(
                Ration.Product(
                    id = product?.id.orEmpty(),
                    name = name.trim(),
                    aliases = words(aliases),
                    grams = if (pill) 0 else g,
                    kcal100 = if (pill) 0.0 else k ?: 0.0,
                    protein100 = if (pill) 0.0 else p ?: 0.0,
                    fat100 = if (pill) 0.0 else f ?: 0.0,
                    carbs100 = if (pill) 0.0 else c ?: 0.0,
                    sure = if (label) "точно" else "примерно",
                    pill = pill,
                    note = note.trim(),
                    micro100 = product?.micro100.orEmpty(),
                )
            )
        },
        destructive = onDelete?.let { SheetAction("Убрать из рациона", onClick = it) },
    ) {
        PaperField(name, { name = it }, label = "Название", isError = clash, supporting = if (clash) "такой продукт уже есть" else null)
        PaperToggle("Добавка", pill, { pill = it }, hint = "креатин, витамины — без калорий")
        if (!pill) {
            NumberField("Обычная порция, г", grams, Modifier.fillMaxWidth()) { grams = it }
            Row {
                DecimalField("Ккал на 100 г", kcal, Modifier.weight(1f)) { kcal = it }
                Spacer(Modifier.width(8.dp))
                DecimalField("Белки", protein, Modifier.weight(1f)) { protein = it }
            }
            Row {
                DecimalField("Жиры", fat, Modifier.weight(1f)) { fat = it }
                Spacer(Modifier.width(8.dp))
                DecimalField("Углеводы", carbs, Modifier.weight(1f)) { carbs = it }
            }
            if (k != null && p != null && f != null && c != null) {
                val macro = p * 4 + f * 9 + c * 4
                when {
                    p + f + c > 101 -> PaperHint("Белков, жиров и углеводов вместе больше 100 г на 100 г — проверь цифры")
                    k > 0 && kotlin.math.abs(macro - k) > maxOf(15.0, k * 0.15) ->
                        PaperHint("По макросам выходит ${Math.round(macro)} ккал на 100 г — сверь с этикеткой")
                    g > 0 -> {
                        val i = Ration.Product("", "", grams = g, kcal100 = k, protein100 = p, fat100 = f, carbs100 = c).item()
                        PaperHint("Порция: ${i.kcal} ккал · Б${i.protein} Ж${i.fat} У${i.carbs}")
                    }
                }
            }
            PaperToggle("Цифры с этикетки", label, { label = it }, hint = if (label) "точно" else "прикинуты — в дневнике «примерно»")
        }
        PaperField(aliases, { aliases = it }, label = "Как называю голосом", placeholder = "через запятую: индейка, индейка чили")
        PaperField(note, { note = it }, label = "Заметка", singleLine = false)
        if (used.isNotEmpty() && onDelete != null) PaperHint("Убрать продукт — уйдёт и из блюд: ${used.joinToString(", ")}")
    }
}

/**
 * Блюдо: название, обычный приём, как владелец его называет, состав с
 * граммами. Продукт в блюдо — только из рациона: нового нет — сначала «+
 * продукт», иначе блюду не на чем считать калории.
 */
@Composable
private fun DishSheet(
    dish: Ration.Dish?,
    book: Ration.Book,
    onClose: () -> Unit,
    onSave: (Ration.Dish) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(dish?.name.orEmpty()) }
    var kind by remember { mutableStateOf(dish?.kind.orEmpty()) }
    var aliases by remember { mutableStateOf(dish?.aliases?.joinToString(", ").orEmpty()) }
    var note by remember { mutableStateOf(dish?.note.orEmpty()) }
    // Состав: продукт и граммы строкой (поле ввода), порядок — как в блюде.
    val items = remember { mutableStateListOf<Pair<String, String>>().apply { dish?.items?.forEach { add(it.product to it.grams.toString()) } } }

    val byId = book.products.associateBy { it.id }
    val portions = items.mapNotNull { (id, g) ->
        byId[id]?.let { p -> Ration.Portion(id, if (p.pill) 0 else g.toIntOrNull() ?: 0) }
    }
    val clash = book.dishes.any { it.id != dish?.id && it.name.trim().equals(name.trim(), ignoreCase = true) }
    val ready = name.isNotBlank() && !clash && portions.isNotEmpty() &&
        portions.all { byId[it.product]?.pill == true || it.grams > 0 }
    val kcal = portions.sumOf { byId[it.product]?.item(it.grams)?.kcal ?: 0 }

    PaperAlert(
        onDismiss = onClose,
        title = if (dish == null) "Новое блюдо" else dish.name,
        icon = Glyphs.Food,
        subtitle = if (portions.isNotEmpty()) "$kcal ккал" else null,
        confirm = SheetAction("Сохранить", icon = Glyphs.Check, enabled = ready) {
            onSave(
                Ration.Dish(
                    id = dish?.id.orEmpty(),
                    name = name.trim(),
                    kind = kind,
                    aliases = words(aliases),
                    items = portions,
                    note = note.trim(),
                )
            )
        },
        destructive = onDelete?.let { SheetAction("Убрать блюдо", onClick = it) },
    ) {
        PaperField(name, { name = it }, label = "Название", isError = clash, supporting = if (clash) "такое блюдо уже есть" else null)
        Text("Обычный приём", style = MaterialTheme.typography.labelLarge)
        ChipRow {
            PaperChip("по времени", kind.isEmpty(), { kind = "" })
            for (k in listOf("завтрак", "обед", "ужин", "перекус")) PaperChip(k, kind == k, { kind = k })
        }
        PaperField(aliases, { aliases = it }, label = "Как называю голосом", placeholder = "через запятую: каша моя, обычная каша")
        Text("Состав", style = MaterialTheme.typography.labelLarge)
        items.forEachIndexed { i, (id, g) ->
            val p = byId[id] ?: return@forEachIndexed
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (!p.pill) {
                    NumberField("г", g, Modifier.width(96.dp)) { items[i] = id to it }
                }
                GlyphButton(Glyphs.Close, "убрать «${p.name}» из блюда", onClick = { items.removeAt(i) })
            }
        }
        val free = book.products.filter { p -> items.none { it.first == p.id } }
        if (free.isNotEmpty()) {
            PaperHint("Добавить в блюдо:")
            ChipRow {
                for (p in free) PaperChip(p.name, false, { items.add(p.id to (p.grams.takeIf { it > 0 } ?: 100).toString()) }, icon = Glyphs.Plus)
            }
        }
        PaperField(note, { note = it }, label = "Заметка", singleLine = false)
    }
}

/**
 * Метка времени для записи на показанный день: сегодня — сейчас, прошлый день —
 * его полдень. Полдень, а не начало суток: приём попадает внутрь дня при любом
 * часовом поясе и не съезжает на сутки назад в выгрузке.
 */
private fun mealStampFor(dayStart: Long): Long {
    val now = System.currentTimeMillis()
    return if (now - dayStart in 0 until 86_400_000L) now else dayStart + 12 * 3_600_000L
}
