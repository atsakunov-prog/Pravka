package ru.zf.slushalka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.GuideEntry
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.data.Settings

/**
 * Справочник по книге: герои, места, словарь.
 *
 * Показывает только то, до чего читатель дошёл: статьи о героях, которые ещё
 * не появились, спрятаны, заметки о будущих главах - тоже. Тумблер «всё, со
 * спойлерами» снимает это руками. Пока справочника нет - кнопка заказать
 * (пакетом, вдвое дешевле, обычно в течение часа); пока считается - лист сам
 * проверяет раз в минуту.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GuideSheet(
    app: SlushalkaApp,
    /** Где читатель: по этому месту считается «текущая глава». */
    cutoffChar: Int,
    /** С чего начать поиск - имя героя из абзаца, например. */
    initialQuery: String = "",
    onAsk: (question: String) -> Unit,
    onClose: () -> Unit,
) {
    val book by app.state.current.collectAsState()
    val text by app.state.text.collectAsState()
    val prefs by app.state.prefs.collectAsState()
    val states by app.guide.states.collectAsState()
    val coroutine = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf(initialQuery) }
    var tab by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<GuideEntry?>(null) }

    val b = book
    val t = text

    // С диска состояние читается один раз - дальше живёт в потоке движка.
    LaunchedEffect(b?.id) { b?.let { app.guide.state(it.id) } }
    val st = b?.let { states[it.id] }

    // Пакет считается - проверяем раз в минуту, пока лист открыт.
    LaunchedEffect(st?.status, b?.id) {
        val bk = b ?: return@LaunchedEffect
        if (st?.status != GuideState.Status.PENDING) return@LaunchedEffect
        while (true) {
            app.guide.refresh(bk).onFailure { error = it.message }
            delay(60_000)
        }
    }

    val chapterNow = t?.let { it.chapterIndexAt(cutoffChar) + 1 } ?: 1
    val upTo = if (showAll) Int.MAX_VALUE else chapterNow

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        // Во весь рост - только когда есть что листать; предложение заказать
        // справочник во весь экран смотрелось бы пустым.
        val tall = st?.status == GuideState.Status.READY
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (tall) Modifier.fillMaxHeight(0.94f) else Modifier)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text("Справочник", style = MaterialTheme.typography.titleLarge)
            if (b == null || t == null) {
                Text("Текст книги ещё разбирается…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                "«${b.title}» · глава $chapterNow из ${t.chapters.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            when (st?.status) {
                null -> {
                    val est = remember(t, prefs.guideModel) { app.guide.estimate(t, prefs.guideModel) }
                    Text(
                        "Справочника по этой книге ещё нет. ${Settings.modelLabel(prefs.guideModel)} прочтёт её " +
                            "целиком - ${est.pages} стр." + (if (est.parts > 1) " в ${est.parts} частях" else "") +
                            " - и составит статьи о героях, местах и словах, каждую с привязкой к главе. " +
                            "Ты увидишь только то, до чего дочитал.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Считается пакетным запросом: вдвое дешевле обычного, готово обычно в течение часа, " +
                            "самое позднее к завтрашнему дню. Приложение можно закрыть - справочник " +
                            "заберётся при следующем открытии книги. Модель меняется в настройках, раздел «Модели».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            coroutine.launch {
                                app.guide.start(b, t).onFailure { error = it.message ?: "Не вышло заказать" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Отправляю книгу…" else "Составить справочник · ≈ %.2f $".format(est.usd)) }
                }

                GuideState.Status.PENDING -> {
                    Text("Готовится…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Заказан ${stamp(st.createdAt)}, проверено ${stamp(st.checkedAt)}. " +
                            "Обычно час; лист проверяет сам, пока открыт.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                coroutine.launch {
                                    app.guide.refresh(b).onFailure { error = it.message }
                                    busy = false
                                }
                            },
                        ) { Text("Проверить сейчас") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { app.guide.forget(b.id) }) { Text("Забыть заказ") }
                    }
                }

                GuideState.Status.FAILED -> {
                    Text("Не вышло", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(st.error.ifBlank { "Причина неизвестна" }, style = MaterialTheme.typography.bodySmall)
                    if (st.costUsd > 0) {
                        Text(
                            "Списано %.2f $".format(st.costUsd),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { app.guide.forget(b.id) }) { Text("Попробовать снова") }
                }

                GuideState.Status.READY -> {
                    val guide = st.guide ?: return@Column
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showAll) "Вся книга - со спойлерами" else "До главы $chapterNow, без спойлеров",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = showAll, onCheckedChange = { showAll = it })
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Имя, место, слово") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    val lists = listOf(guide.characters, guide.places, guide.terms)
                    val labels = listOf("Герои", "Места", "Словарь")
                    val visible = lists.map { list -> list.mapNotNull { it.visibleAt(upTo) }.filter { it.matches(query) } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEachIndexed { i, label ->
                            FilterChip(
                                selected = tab == i,
                                onClick = { tab = i },
                                label = { Text("$label · ${visible[i].size}") },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val shown = visible[tab]
                    if (shown.isEmpty()) {
                        Text(
                            if (query.isNotBlank()) "Ничего похожего до этого места книги."
                            else "До этого места здесь пока пусто.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(shown) { e ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { open = e }
                                    .padding(vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "гл. ${e.chapter}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (e.aliases.isNotEmpty()) {
                                    Text(
                                        e.aliases.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    e.role,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${Settings.modelLabel(st.model)} · ${stamp(st.createdAt)}" +
                                (if (st.costUsd > 0) " · %.2f $".format(st.costUsd) else "") +
                                (if (st.error.isNotBlank()) " · часть не разобралась" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { app.guide.forget(b.id) }) { Text("Пересобрать") }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    open?.let { e ->
        val visibleNotes = e.notes.filter { it.chapter <= upTo }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(e.name) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (e.aliases.isNotEmpty()) {
                        Text(
                            e.aliases.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(e.role, style = MaterialTheme.typography.bodyMedium)
                    visibleNotes.forEach { n ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Глава ${n.chapter}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!showAll && e.notes.size > visibleNotes.size) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Дальше в книге о нём ещё ${e.notes.size - visibleNotes.size} " +
                                plural(e.notes.size - visibleNotes.size, "запись", "записи", "записей") +
                                " - откроются по мере чтения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(onClick = {
                    open = null
                    onAsk("Расскажи про «${e.name}»: что о нём известно к этому месту книги и какую роль он тут играет?")
                }) { Text("Спросить") }
            },
        )
    }
}

private fun stamp(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("d.MM HH:mm", Locale("ru")).format(Date(ms))

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}
