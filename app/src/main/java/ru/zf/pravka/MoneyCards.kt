package ru.zf.pravka

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint

// Вопросы сверки — карточками (владелец, 23.09.2026): на карточке сколько раз,
// какая сумма, кому, с какого счёта, и сам список операций с датами. Внизу —
// «Не знаю» (карточка остаётся, листаем дальше) и «Сказать»: наговорил, что
// это, — Claude сам понял категорию и запомнил получателя. Карточки листаются
// пальцем: смахнул — значит, пока не знаешь, она вернётся на своё место в
// стопке. Голос — системный распознаватель Google, тот же движок, что у кнопок.

/** Интент распознавания речи по-русски: системный диалог Google. */
internal fun speechIntent(prompt: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

internal fun spokenText(data: Intent?): String =
    data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()

@Composable
internal fun QuestionCards(app: PravkaApp, questions: List<MoneyEngine.Question>) {
    if (questions.isEmpty()) return
    // Крупные — первыми: вопрос на сто тысяч важнее десяти по триста.
    val ordered = remember(questions) { questions.sortedBy { q -> q.entries.sumOf { it.rubKop } } }
    val pager = rememberPagerState(pageCount = { ordered.size })
    val scope = rememberCoroutineScope()
    val total = ordered.sumOf { q -> q.entries.sumOf { it.rubKop } }
    PaperCard(label = "вопросы · ${ordered.size} · ${MoneyFormat.rub(total)}") {
        PaperHint("Листай пальцем. «Сказать» — наговори, что это, Claude разложит и запомнит.")
        Spacer(Modifier.height(8.dp))
        HorizontalPager(state = pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { page ->
            QuestionCard(
                app = app,
                q = ordered[page],
                position = "${page + 1} из ${ordered.size}",
                onSkip = {
                    scope.launch {
                        val next = if (page + 1 < ordered.size) page + 1 else 0
                        pager.animateScrollToPage(next)
                    }
                },
            )
        }
    }
}

@Composable
private fun QuestionCard(app: PravkaApp, q: MoneyEngine.Question, position: String, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember(q.key) { mutableStateOf(false) }
    var typing by remember(q.key) { mutableStateOf(false) }
    var typed by remember(q.key) { mutableStateOf("") }
    var result by remember(q.key) { mutableStateOf("") }
    val voice = q.entries.first().source == MoneyEntry.Source.VOICE
    val orphan = q.text == MoneyMatch.ORPHAN_PUSH

    fun submit(spoken: String) {
        if (spoken.isBlank()) return
        busy = true
        result = "«$spoken» — Claude разбирает…"
        scope.launch {
            app.moneyEngine.answerByVoice(q, spoken)
                .onSuccess { a ->
                    result = if (a.unsure) "Не понял, что это — скажи иначе или «Не знаю»."
                    else "✓ " + MoneyCategories.title(a.category) +
                        (if (a.who.isNotBlank()) " · " + MoneyCategories.whoTitle(a.who) else "") +
                        (if (a.remember) " — запомнил" else " — только эти")
                    if (!a.unsure) Feedback.toast(context, result)
                }
                .onFailure { e -> result = "Не вышло: ${e.message}" }
            busy = false
        }
    }

    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        submit(spokenText(r.data))
    }

    val first = q.entries.first()
    val sum = q.entries.sumOf { it.rubKop }
    val accounts = q.entries.map { it.account.ifBlank { it.source.title } }.distinct()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                first.what,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PaperHint(position)
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                MoneyFormat.rub(sum, sign = true),
                style = MaterialTheme.typography.headlineSmall,
                color = if (sum < 0) spentColor() else incomeColor(),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                PaperHint(if (q.entries.size > 1) "${q.entries.size} раз" else "один раз")
                PaperHint(MoneyCategories.whoTitle(first.owner) + " · " + accounts.joinToString(", "))
            }
        }
        if (first.bankCategory.isNotBlank()) PaperHint("банк считает: ${first.bankCategory}" + if (first.mcc.isNotBlank()) " · MCC ${first.mcc}" else "")
        Spacer(Modifier.height(6.dp))
        // Сами операции — по ним и узнают, что это: даты, суммы, сообщения к переводу.
        val shown = q.entries.sortedByDescending { it.ts }.take(8)
        for (e in shown) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(stampDay(e.ts), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                Text(
                    e.note.ifBlank { "" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(MoneyFormat.rub(e.rubKop, sign = true), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (q.entries.size > shown.size) PaperHint("и ещё ${q.entries.size - shown.size}")
        if (result.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(result, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        if (orphan) {
            // Пуш был, выписка пришла, а пары нет: обычно отмена или возврат день в день.
            PaperHint(q.text)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { app.moneyEngine.drop(q.entries.map { it.id }) } }) { Text("Отменили — вычеркнуть") }
                OutlinedButton(onClick = onSkip) { Text("Не знаю") }
            }
            return@Column
        }
        if (voice) {
            // Надиктовано, а в выписке не нашлось: тут вопрос один — наличные ли.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { app.moneyEngine.markCash(q.entries.map { it.id }) } }) { Text("Да, наличные") }
                OutlinedButton(onClick = onSkip) { Text("Не знаю") }
            }
            return@Column
        }
        if (typing) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Что это") },
                singleLine = false,
            )
            Row {
                TextButton(enabled = !busy && typed.isNotBlank(), onClick = { submit(typed.trim()); typing = false }) { Text("Готово") }
                TextButton(onClick = { typing = false }) { Text("Отмена") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = onSkip) { Text("Не знаю") }
            Button(
                enabled = !busy,
                onClick = {
                    runCatching { listen.launch(speechIntent("Что это: ${first.what}?")) }
                        .onFailure { typing = true; Feedback.toast(context, "Распознавание недоступно — напиши текстом") }
                },
            ) { Text(if (busy) "Разбираю…" else "🎙 Сказать") }
            TextButton(enabled = !busy, onClick = { typing = !typing }) { Text("текстом") }
        }
    }
}

private fun stampDay(ts: Long): String = SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(Date(ts))
