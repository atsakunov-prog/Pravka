package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
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
    // «Для ребёнка»: те же главы, но словами для семилетки - и сразу вслух.
    var kid by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val b = book
    val t = text

    fun keyFor(d: AskEngine.Depth) = "${b?.id}|${d.name}|${cutoffChar / 2000}|$kid"

    fun run(d: AskEngine.Depth) {
        if (b == null || t == null || busy) return
        cache[keyFor(d)]?.let {
            answer = it
            if (prefs.speakAnswers || kid) app.speaker.speak(it)
            return
        }
        busy = true
        error = null
        answer = ""
        scope.launch {
            val range = app.ask.recapRange(t, cutoffChar, d)
            val result = app.ask.recap(b, t, range, absMs, forKid = kid) { partial -> answer = partial }
            busy = false
            result.onSuccess {
                answer = it
                if (it.isNotBlank()) cache[keyFor(d)] = it
                if ((prefs.speakAnswers || kid) && it.isNotBlank()) app.speaker.speak(it)
            }.onFailure { error = it.message ?: "Не вышло напомнить" }
        }
    }

    // Первый заход считаем сразу: кнопку уже нажали, второй раз спрашивать
    // «а теперь точно?» незачем.
    LaunchedEffect(Unit) { run(depth) }

    fun close() {
        app.speaker.stop()
        app.ask.cancel()
        onClose()
    }

    PaperSheet(
        app = app,
        onClose = { close() },
        icon = Glyphs.History,
        title = "Напомнить, что было",
        subtitle = b?.title,
    ) {
        PaperLabel("Сколько вспомнить")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AskEngine.Depth.entries.forEach { d ->
                PaperChip(d.label, selected = depth == d, enabled = !busy) { depth = d; run(d) }
            }
            PaperChip("Для ребёнка", selected = kid, icon = Glyphs.ChildCare, enabled = !busy) { kid = !kid; run(depth) }
        }
        Spacer(Modifier.height(16.dp))
        when {
            error != null -> PaperError(error!!)
            answer.isBlank() && busy -> PaperBusy("Вспоминаю…")
            answer.isBlank() -> PaperNote("В этом куске текста слишком мало, чтобы пересказывать.")
            else -> {
                // Пересказ читают, как страницу: шрифтом и кеглем книги.
                Text(answer, style = bookBody())
                if (busy) PaperBusy("Пишу…")
            }
        }
        if (answer.isNotBlank() && remember(answer) { Love.rarely() }) {
            Spacer(Modifier.height(10.dp))
            LoveLine(alpha = 0.4f, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (answer.isNotBlank()) {
                MiniAction(Glyphs.VolumeUp, "Вслух") { app.speaker.speak(answer) }
                MiniAction(Glyphs.StopCircle, "Тише") { app.speaker.stop() }
            }
            Spacer(Modifier.weight(1f))
            PaperButton("Дальше", icon = if (b?.hasAudio == false) Glyphs.AutoStories else Glyphs.Headphones, primary = true) { close() }
        }
    }
}
