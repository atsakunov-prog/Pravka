package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.VoiceInput
import ru.zf.slushalka.catalog.Advisor
import ru.zf.slushalka.data.Settings

/**
 * Лист советника. Открывается с полки («что почитать?»), со страницы автора
 * («с чего начать?») и с книги («о чём она?», «что о ней говорят?»). Готовые
 * вопросы - чипами, свой - набрать или наговорить. Совет заканчивается
 * кнопками с книгами: тап - и каталог уже ищет.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdvisorSheet(
    app: SlushalkaApp,
    scope: Advisor.Scope,
    /** Вопрос, с которым пришли: уходит сам, без лишнего тапа. */
    initialQuestion: String?,
    initialWeb: Boolean,
    hasMic: () -> Boolean,
    onNeedMic: () -> Unit,
    onSearch: (author: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val advisor = app.advisor
    val prefs by app.state.prefs.collectAsState()
    val coroutine = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var turns by remember { mutableStateOf(listOf<Advisor.Turn>()) }
    var question by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var partial by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf(initialWeb) }
    var listening by remember { mutableStateOf(false) }
    val voice = remember { VoiceInput(app) }

    DisposableEffect(Unit) {
        onDispose {
            voice.cancel()
            advisor.cancel()
        }
    }

    fun send(q: String, useWeb: Boolean = web) {
        val text = q.trim()
        if (busy || text.isBlank()) return
        busy = true
        error = null
        pending = text
        partial = ""
        question = ""
        coroutine.launch {
            val result = advisor.ask(scope, turns, text, useWeb) { partial = it }
            busy = false
            result.onSuccess { answer ->
                turns = turns + Advisor.Turn(text, answer)
                pending = null
                partial = ""
            }.onFailure { e ->
                error = e.message ?: "Не вышло"
                pending = null
                partial = ""
            }
        }
    }

    LaunchedEffect(Unit) { initialQuestion?.let { send(it, initialWeb) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Советник", style = MaterialTheme.typography.titleLarge)
            Text(
                advisor.scopeLine(scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (turns.isEmpty() && pending == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    advisor.quickPrompts(scope).forEach { (text, needsWeb) ->
                        FilterChip(
                            selected = false,
                            enabled = !busy,
                            onClick = { send(text, web || needsWeb) },
                            label = { Text(text) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            turns.forEach { turn ->
                Question(turn.question)
                Text(turn.answer.text, style = MaterialTheme.typography.bodyMedium)
                if (turn.answer.suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Найти в каталоге:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        turn.answer.suggestions.forEach { s ->
                            FilterChip(
                                selected = false,
                                onClick = { onSearch(s.author, s.title) },
                                label = { Text(s.label) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            pending?.let { q ->
                Question(q)
                if (partial.isBlank()) {
                    Text(
                        if (web) "Думаю и смотрю в интернете…" else "Думаю…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(partial, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { advisor.cancel() }) { Text("Хватит") }
                Spacer(Modifier.height(8.dp))
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Искать в интернете", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = web, onCheckedChange = { web = it })
            }
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("Свой вопрос") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                trailingIcon = {
                    IconButton(onClick = { send(question) }, enabled = !busy && question.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Спросить")
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (listening) {
                            voice.stop()
                            listening = false
                        } else if (!hasMic()) {
                            onNeedMic()
                        } else {
                            voice.onText = { question = it }
                            voice.onEnd = { heard ->
                                listening = false
                                if (heard.isNotBlank()) send(heard)
                            }
                            voice.onError = { listening = false; error = it }
                            voice.start()
                            listening = true
                        }
                    },
                ) { Text(if (listening) "Стоп" else "Наговорить") }
                Spacer(Modifier.weight(1f))
                val spent = turns.sumOf { it.answer.costUsd }
                Text(
                    Settings.modelLabel(prefs.adviseModel) +
                        (if (spent > 0) " · %.2f $".format(spent) else "") +
                        (if (web) " · поиск 0,01 $ за запрос" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Question(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
    Spacer(Modifier.height(8.dp))
    Spacer(Modifier.width(0.dp))
}
