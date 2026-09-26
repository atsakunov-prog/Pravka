package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import ru.zf.pravka.trigger.MoneyTabVoice
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishMoneyTab
import ru.zf.pravka.trigger.listenForMoneyTab
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperIconButton
import ru.zf.pravka.ui.PaperTextButton

// Вопросы сверки — карточками (владелец, 23.09.2026): на карточке сколько раз,
// какая сумма, кому, с какого счёта, и сам список операций с датами. Внизу —
// «Не знаю» (карточка остаётся, листаем дальше) и «Сказать»: наговорил, что
// это, — Claude сам понял категорию и запомнил получателя. Карточки листаются
// пальцем: смахнул — значит, пока не знаешь, она вернётся на своё место в
// стопке. Главная кнопка на карточке одна — справа (24.09.2026): «Сказать»,
// а у пуша без пары и у надиктованного — «Да, …» на их единственный вопрос.
//
// Голос — НАШ движок, тот же, что у «П», «З», «Д» и «₽» (служба,
// `listenForMoneyTab`): со словарём, подсказками справочника, серой
// «отменой» и выбором микрофона. Не системный диалог Google — первая версия
// звала его, и владелец поймал это сразу (23.09.2026: «Ты что?!»).

/**
 * Попросить службу послушать для вкладки. [owner] — кто просит (карточка,
 * поле вопроса): по нему [MoneyVoiceBar] понимает, что слушают для него.
 */
internal fun startMoneyVoice(app: PravkaApp, owner: String, prompt: String, onText: (String) -> Unit): Boolean {
    val service = PravkaAccessibilityService.instance
    if (service == null) {
        Feedback.toast(app, app.getString(R.string.toast_no_service))
        return false
    }
    return service.listenForMoneyTab(owner, prompt, onText)
}

/**
 * «Слушаю…» во вкладке: живой текст и две кнопки — «Готово» (текст уходит
 * в разбор) и «Отмена». Видно и тогда, когда кнопки «₽» на экране нет.
 */
@Composable
internal fun MoneyVoiceBar(owner: String) {
    val live by MoneyTabVoice.live.collectAsState()
    val mine = live?.takeIf { it.owner == owner } ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Glyphs.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                mine.text.ifBlank { "слушаю…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PaperButton("Отмена", icon = Glyphs.Close, onClick = { PravkaAccessibilityService.instance?.finishMoneyTab(keep = false) })
            PaperButton("Готово", icon = Glyphs.Check, primary = true, onClick = { PravkaAccessibilityService.instance?.finishMoneyTab(keep = true) })
        }
    }
}

/**
 * Вопросы сверки стопкой карточек. [footer] — ручка под стопкой («Claude
 * разложит очевидное»): она про все вопросы разом, а не про одну карточку.
 */
@Composable
internal fun QuestionCards(
    app: PravkaApp,
    questions: List<MoneyEngine.Question>,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (questions.isEmpty()) return
    // Крупные — первыми: вопрос на сто тысяч важнее десяти по триста.
    val ordered = remember(questions) { questions.sortedBy { q -> q.entries.sumOf { it.rubKop } } }
    val pager = rememberPagerState(pageCount = { ordered.size })
    val scope = rememberCoroutineScope()
    val total = ordered.sumOf { q -> q.entries.sumOf { it.rubKop } }
    PaperCard(
        label = "вопросы · ${ordered.size} · ${MoneyFormat.k(total)} ${MoneyFormat.K}",
        info = "Листай пальцем. «Сказать» — наговори, что это, Claude разложит и запомнит. " +
            "«Не знаю» — карточка остаётся в стопке, листаем дальше.",
    ) {
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
        if (footer != null) {
            Spacer(Modifier.height(4.dp))
            footer()
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
    // Пока слушаем для этой карточки, главная — «Готово» в полосе «слушаю…»,
    // и «Сказать» на это время уступает ей заливку: две главных рядом — ни одной.
    val live by MoneyTabVoice.live.collectAsState()
    val listening = live?.owner == "card:" + q.key

    fun submit(spoken: String) {
        if (spoken.isBlank()) return
        busy = true
        result = "«$spoken» — Claude разбирает…"
        // Не scope карточки: её могли смахнуть, пока говорили, а ответ терять нельзя.
        app.appScope.launch {
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
            // Вопрос на карточке — «Операцию отменили?», поэтому ответ короткий:
            // «Отменили — вычеркнуть» рядом с «Не знаю» на внешнем экране Fold не встаёт.
            PaperHint(q.text)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PaperButton("Не знаю", onClick = onSkip)
                PaperButton(
                    "Да, вычеркнуть",
                    icon = Glyphs.Delete,
                    primary = true,
                    onClick = { scope.launch { app.moneyEngine.drop(q.entries.map { it.id }) } },
                )
            }
            return@Column
        }
        if (voice) {
            // Надиктовано, а в выписке не нашлось: тут вопрос один — наличные ли.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PaperButton("Не знаю", onClick = onSkip)
                PaperButton(
                    "Да, наличные",
                    icon = Glyphs.Check,
                    primary = true,
                    onClick = { scope.launch { app.moneyEngine.markCash(q.entries.map { it.id }) } },
                )
            }
            return@Column
        }
        if (typing) {
            PaperField(
                value = typed,
                onValueChange = { typed = it },
                label = "Что это",
                singleLine = false,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PaperTextButton("Отмена", onClick = { typing = false })
                PaperTextButton(
                    "Готово",
                    icon = Glyphs.Check,
                    enabled = !busy && typed.isNotBlank(),
                    onClick = { submit(typed.trim()); typing = false },
                )
            }
        }
        MoneyVoiceBar("card:" + q.key)
        // «Не знаю» — контуром слева, «текстом» — значком-карандашом (горит,
        // пока поле открыто), «Сказать» — главная справа.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PaperButton("Не знаю", enabled = !busy, onClick = onSkip)
            Spacer(Modifier.weight(1f))
            PaperIconButton(
                Glyphs.Edit,
                "текстом",
                enabled = !busy,
                active = typing,
                onClick = { typing = !typing },
            )
            Spacer(Modifier.width(8.dp))
            PaperButton(
                if (busy) "Разбираю…" else "Сказать",
                icon = Glyphs.Mic,
                primary = !listening,
                enabled = !busy,
                onClick = { startMoneyVoice(app, "card:" + q.key, "что это: ${first.what}?") { submit(it) } },
            )
        }
    }
}

private fun stampDay(ts: Long): String = SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(Date(ts))
