package ru.zf.slushalka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp

/**
 * Текст вокруг того места, где мы сейчас, - и главная кнопка всего разбора:
 * **тап по абзацу, который читают прямо сейчас**.
 *
 * Одна такая отметка чинит привязку на всю оставшуюся книгу: дальше вопрос
 * попадает ровно в то место, которое человек слушает, а не «примерно туда».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextScreen(app: SlushalkaApp, onBack: () -> Unit) {
    val state = app.state
    val text by state.text.collectAsState()
    val alignment by state.alignment.collectAsState()
    val play by app.player.state.collectAsState()

    var center by remember { mutableIntStateOf(-1) }
    var pending by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    val t = text
    val align = alignment
    if (t == null || align == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Текста книги нет или он ещё разбирается",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val here = align.charAt(play.absMs)
    LaunchedEffect(here) { if (center < 0) center = here }
    val anchor = if (center >= 0) center else here

    // Окно вокруг места: восемь тысяч знаков - это минут двадцать звучания,
    // больше глазами и не надо.
    val from = (anchor - 4000).coerceAtLeast(0)
    val to = (anchor + 4000).coerceAtMost(t.length)
    val paragraphs = remember(from, to, t) {
        val chunk = t.plain.substring(from, to)
        val out = ArrayList<Pair<Int, String>>()
        var offset = from
        chunk.split('\n').forEach { line ->
            if (line.isNotBlank()) out.add(offset to line)
            offset += line.length + 1
        }
        out
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Текст книги") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(onClick = { center = here }) { Text("К месту") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    if (align.manualCount == 0)
                        "Привязка пока приблизительная. Тапни абзац, который читают " +
                            "прямо сейчас, - и дальше она будет точной."
                    else "Ручных отметок: ${align.manualCount}. Можно добавить ещё - " +
                        "чем ближе к текущему месту, тем точнее.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (align.manualCount > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { state.dropAnchors() }) { Text("Сбросить отметки") }
                    }
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(paragraphs, key = { it.first }) { (offset, line) ->
                    val current = offset <= here && here < offset + line.length + 1
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pending = offset }
                            .padding(vertical = 6.dp),
                    )
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }

    pending?.let { offset ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Сейчас читают это?") },
            text = {
                Text(
                    "Отметим, что на ${formatClock(play.absMs)} звучит этот абзац. " +
                        "Привязка текста к записи станет точной отсюда и дальше."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.addAnchor(play.absMs, offset)
                    pending = null
                }) { Text("Да, я тут") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Нет") } },
        )
    }
}
