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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.AskEngine

/**
 * «Что там было» - пересказ последних глав до текущего места.
 *
 * Спойлеров тут не бывает по построению: дальше текущего места модели просто
 * нечего показать. Один и тот же пересказ второй раз не оплачивается - он
 * лежит в памяти до смены места.
 */
private val cache = HashMap<String, String>()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecapSheet(
    app: SlushalkaApp,
    cutoffChar: Int,
    absMs: Long,
    onClose: () -> Unit,
) {
    val book by app.state.current.collectAsState()
    val text by app.state.text.collectAsState()
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()

    var depth by remember { mutableStateOf(AskEngine.Depth.TWO) }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val b = book
    val t = text

    fun keyFor(d: AskEngine.Depth) = "${b?.id}|${d.name}|${cutoffChar / 2000}"

    fun run(d: AskEngine.Depth) {
        if (b == null || t == null || busy) return
        cache[keyFor(d)]?.let {
            answer = it
            if (prefs.speakAnswers) app.speaker.speak(it)
            return
        }
        busy = true
        error = null
        answer = ""
        scope.launch {
            val range = app.ask.recapRange(t, cutoffChar, d)
            val result = app.ask.recap(b, t, range, absMs) { partial -> answer = partial }
            busy = false
            result.onSuccess {
                answer = it
                if (it.isNotBlank()) cache[keyFor(d)] = it
                if (prefs.speakAnswers && it.isNotBlank()) app.speaker.speak(it)
            }.onFailure { error = it.message ?: "Не вышло напомнить" }
        }
    }

    // Первый заход считаем сразу: кнопку уже нажали, второй раз спрашивать
    // «а теперь точно?» незачем.
    LaunchedEffect(Unit) { run(depth) }

    AlertDialog(
        onDismissRequest = { app.speaker.stop(); app.ask.cancel(); onClose() },
        title = { Text("Напомнить содержание") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AskEngine.Depth.entries.forEach { d ->
                        FilterChip(
                            selected = depth == d,
                            enabled = !busy,
                            onClick = { depth = d; run(d) },
                            label = { Text(d.label) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        error != null -> Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                        answer.isBlank() && busy -> Text(
                            "Вспоминаю…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        answer.isBlank() -> Text(
                            "В этом куске текста слишком мало, чтобы пересказывать.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(answer, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (answer.isNotBlank() && remember(answer) { Love.rarely() }) {
                        Spacer(Modifier.height(10.dp))
                        LoveLine(alpha = 0.4f, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { app.speaker.stop(); app.ask.cancel(); onClose() }) {
                Text("Дальше")
            }
        },
        dismissButton = {
            if (answer.isNotBlank()) {
                Row {
                    TextButton(onClick = { app.speaker.speak(answer) }) { Text("Вслух") }
                    TextButton(onClick = { app.speaker.stop() }) { Text("Тише") }
                }
            }
        },
    )
}
