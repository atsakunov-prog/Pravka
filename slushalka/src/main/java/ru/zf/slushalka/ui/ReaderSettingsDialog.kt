package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings

/** Шрифт, кегль, поля, интерлиньяж, цвет бумаги - обычный набор читалки. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsDialog(app: SlushalkaApp, onGallery: () -> Unit, onClose: () -> Unit) {
    val state = app.state
    val prefs by state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = state.settings

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Как читать") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {

                Label("Шрифт")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Settings.FONT_SERIF to "С засечками",
                        Settings.FONT_SANS to "Рубленый",
                        Settings.FONT_MONO to "Машинописный",
                    ).forEach { (id, title) ->
                        FilterChip(
                            selected = prefs.readerFont == id,
                            onClick = { scope.launch { s.setReaderFont(id) } },
                            label = { Text(title, style = TextStyle(fontFamily = fontOf(id))) },
                        )
                    }
                }

                Label("Кегль: ${prefs.readerSize}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { s.setReaderSize(prefs.readerSize - 1) } }) {
                        Text("А−", fontSize = 15.sp)
                    }
                    TextButton(onClick = { scope.launch { s.setReaderSize(prefs.readerSize + 1) } }) {
                        Text("А+", fontSize = 21.sp)
                    }
                }

                Label("Междустрочье")
                NumberRow(
                    values = listOf(1.2f, 1.4f, 1.5f, 1.7f, 2.0f),
                    selected = prefs.readerLineHeight,
                    format = { it.toString().replace('.', ',') },
                ) { scope.launch { s.setReaderLineHeight(it) } }

                Label("Поля")
                NumberRow(
                    values = listOf(0, 12, 20, 32, 48),
                    selected = prefs.readerMargin,
                    format = { "$it" },
                ) { scope.launch { s.setReaderMargin(it) } }

                val pics = state.text.collectAsState().value?.pictures?.size ?: 0
                val extracted = state.picturesOnDisk()
                Label("Картинки")
                Text(
                    when {
                        pics > 0 ->
                            "В книге их $pics. Стоят на своих местах в тексте; пока читаешь " +
                                "рядом, миниатюра висит в углу."
                        extracted > 0 ->
                            "Вынуто из файла: $extracted, но место в тексте для них не нашлось - " +
                                "смотреть их можно списком."
                        else -> "В этой книге картинок не нашлось."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pics == 0 || extracted > pics) {
                    // Голое «не нашлось» ничего не объясняет: показываем, что
                    // именно разбор увидел в файле - по этим числам сразу ясно,
                    // чего не хватило.
                    state.parseReport()?.let { r ->
                        Text(
                            "Разбор увидел: ${r.line()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (extracted > 0) {
                        TextButton(onClick = { onClose(); onGallery() }) { Text("Показать все") }
                    }
                    TextButton(onClick = { state.reparseText(); onClose() }) {
                        Text("Разобрать книгу заново")
                    }
                }

                Label("Бумага")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Settings.THEME_AUTO to "Как система",
                        Settings.THEME_PAPER to "Чёрным по белому",
                        Settings.THEME_SEPIA to "Сепия",
                        Settings.THEME_GREY to "Серая",
                        Settings.THEME_BLACK to "Белым по чёрному",
                    ).forEach { (id, title) ->
                        FilterChip(
                            selected = prefs.readerTheme == id,
                            onClick = { scope.launch { s.setReaderTheme(id) } },
                            label = { Text(title) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Toggle("Выключка по ширине", prefs.readerJustify) {
                    scope.launch { s.setReaderJustify(it) }
                }
                Toggle("Не гасить экран", prefs.readerKeepAwake) {
                    scope.launch { s.setReaderKeepAwake(it) }
                }

                Label("Переход со звука")
                val marked = state.isMarkedUp()
                Text(
                    when {
                        marked ->
                            "Книга размечена: место при переходе считается по карте мгновенно, " +
                                "ничего не распознаётся."
                        !app.recognizer.supported ->
                            "На этом устройстве распознавание файла недоступно - место берётся " +
                                "по карте, с точностью до страницы-другой."
                        else ->
                            "Книга не размечена. Разметка пройдёт по записи пробами, распознает " +
                                "их прямо на телефоне и найдёт в тексте - это ничего не стоит и " +
                                "не ходит в сеть. Карта ляжет файлом в папку книги, и другие " +
                                "устройства возьмут готовую."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.recognizer.supported) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { state.markupBook(); onClose() }) {
                            Text(if (marked) "Разметить ещё раз" else "Разметить книгу")
                        }
                        TextButton(onClick = { state.testProbe(); onClose() }) {
                            Text("Одна проба")
                        }
                    }
                    Toggle("Сверять место при переходе", prefs.refineOnSwitch) {
                        scope.launch { s.setRefineOnSwitch(it) }
                    }
                    Text(
                        "Про запас, для мест, до которых разметка не добралась: одна проба " +
                            "перед самым переходом.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
    )
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun <T> NumberRow(
    values: List<T>,
    selected: T,
    format: (T) -> String,
    onPick: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v ->
            FilterChip(
                selected = v == selected,
                onClick = { onPick(v) },
                label = { Text(format(v)) },
            )
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
