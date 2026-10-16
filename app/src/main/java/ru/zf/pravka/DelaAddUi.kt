package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Capture
import ru.zf.pravka.core.DelaAdd
import ru.zf.pravka.core.SayFork
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.trigger.onRaznoskaText
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.Look
import ru.zf.pravka.ui.LocalMode
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.ThinkingLine

// «+» и микрофон в шапке Дел (отладка 16.10.2026 вечер, запись 32; `core/DelaAdd.kt`). Владелец: «новое дело должно быть
// где-то высоко — рядом с „Дела“ наверху плюс и микрофон. Плюс — накидать дела: открывается текстбокс посередине экрана
// (их может быть несколько, там должны распознаться люди, проекты и т. п.), микрофон — то же самое, но голосом». Дела
// уходят одной дверью захвата (`capture.say`, как строка «сказать» и «Д»): несколько дел, людей и проекты раскладывает
// сервер, строка «куда легло» и кнопки поправки — его. Ручная форма нового дела — долгим нажатием на «+» и «Руками…».

/**
 * «+» и микрофон сразу за названием «Дела» в шапке. Тап по «+» — лист «Добавь дела», долгое нажатие — ручная форма
 * (прежний «+» строки состояния); микрофон — тот же лист и сразу диктовка движком «Д».
 */
@Composable
internal fun RowScope.DelaAddKeys(onAdd: () -> Unit, onManual: () -> Unit, onMic: () -> Unit) {
    val v2 = Look.v2
    val mode = LocalMode.current
    val shape = if (v2) RoundedCornerShape(KvShape.Control) else CircleShape
    val size = if (v2) 40.dp else 44.dp
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "добавить дела",
                onLongClickLabel = "новое дело руками",
                onClick = onAdd,
                onLongClick = onManual,
            )
            .semantics { contentDescription = "добавить дела" },
        contentAlignment = Alignment.Center,
    ) {
        // Главное действие вкладки — акцентом, остальные значки шапки — тише.
        Icon(Glyphs.Plus, null, tint = if (v2) Kv.accent else mode.value, modifier = Modifier.size(if (v2) 22.dp else 24.dp))
    }
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "наговорить дела", onClick = onMic)
            .semantics { contentDescription = "наговорить дела" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyphs.Mic, null, tint = if (v2) Kv.icon else mode.label, modifier = Modifier.size(20.dp))
    }
}

/**
 * Дела из листа — одной дверью захвата. [spoken] — наговорено микрофоном (сервер видит `from: d`, как у «Д»), иначе
 * набрано (`from: say`). Строка — сервера; без захвата на сервере — прежняя Разноска телефона, итог в пилюле «Д».
 */
internal suspend fun PravkaApp.addDela(text: String, spoken: Boolean, onPending: (String) -> Unit): SayOutcome {
    val said = text.trim()
    val ready = runCatching { captureReady() }.getOrDefault(false)
    val way = DelaAdd.way(ready, delaStore.view.value.captureModelOn)
    eventLog.add("дела «+»: ${way.name.lowercase()} — «${said.take(80)}»")
    if (way != DelaAdd.Way.OLD) {
        val ref = Capture.newRef()
        val (prepared, hint) = capturePrepare(said)
        if (way == DelaAdd.Way.RULE) {
            val r = runCatching { capture.rule(prepared, ref, DelaAdd.from(spoken), hint) }.getOrNull()
            if (r != null && r.done) return captureOutcome(r, said)
        } else {
            val o = capture.say(prepared, ref, DelaAdd.from(spoken), hint, DelaAdd.LANE) { p -> onPending(Capture.line(p)) }
            return when {
                o.refused.isNotBlank() -> SayOutcome(SayFork.Kind.TASKS, o.line, ok = false, open = Tab.TODOIST, said = said)
                o.offline -> SayOutcome(SayFork.Kind.TASKS, o.line, ok = true, open = Tab.TODOIST, said = said)
                o.result != null -> captureOutcome(o.result, said)
                else -> SayOutcome(SayFork.Kind.TASKS, "Дела не ответили — фраза ушла, итог придёт уведомлением", ok = true, open = Tab.TODOIST, said = said)
            }
        }
    }
    // Прежняя дорога — как «Д» без захвата: Разноска телефона, итог — в пилюле «Д».
    val service = PravkaAccessibilityService.instance
        ?: return SayOutcome(SayFork.Kind.TASKS, getString(R.string.toast_no_service), ok = false, said = said)
    service.onRaznoskaText(said)
    return SayOutcome(SayFork.Kind.TASKS, "Разбираю — итог в пилюле «Д»", ok = true, open = Tab.TODOIST, said = said)
}

/**
 * Лист «Добавь дела» посередине экрана: многострочное поле, «Готово» — фраза уходит одной операцией захвата; под полем —
 * строка «куда легло» и кнопки поправки сервера (как у «сказать» и «Д»), поле снова пустое — можно накидать ещё.
 * Микрофон — та же диктовка, что у «Д» (движок и пилюля службы): нажал — говоришь, нажал ещё раз — сказанное уходит.
 * [listenFirst] — открыт микрофоном шапки: диктовка стартует сразу. «Руками…» — прежняя форма нового дела с набранным.
 */
@Composable
internal fun DelaAddSheet(
    app: PravkaApp,
    listenFirst: Boolean,
    onDismiss: () -> Unit,
    onManual: (String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val t = LocalPravkaType.current
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("Разбираю") }
    var out by remember { mutableStateOf<SayOutcome?>(null) }
    var timeChip by remember { mutableStateOf<Pair<Int, Capture.Chip>?>(null) }
    var kuda by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val send: (String, Boolean) -> Unit = send@{ raw, spoken ->
        val said = raw.trim()
        if (said.isEmpty() || busy) return@send
        busy = true
        busyLabel = "Записал — раскладываю"
        out = null
        timeChip = null
        app.appScope.launch {
            val o = runCatching { app.addDela(said, spoken) { busyLabel = it } }
                .getOrElse { e -> SayOutcome(SayFork.Kind.TASKS, "Не вышло: ${e.message ?: e.javaClass.simpleName}", ok = false, said = said) }
            busy = false
            out = o
            // Не записалось — сказанное не теряется: снова в поле. Записалось — поле пустое: можно накидать ещё.
            if (!o.ok && text.isBlank()) text = said
        }
    }
    val move: (Int, Capture.Chip, Capture.Option?) -> Unit = { i, chip, opt ->
        val cur = out?.capture
        if (cur != null && !busy) {
            busy = true
            busyLabel = "Перекладываю"
            app.appScope.launch {
                val m = runCatching { app.capture.move(cur.ref, i, chip, opt) }.getOrNull()
                busy = false
                out = when {
                    m == null -> SayOutcome(SayFork.Kind.TASKS, "Не вышло переложить — нет ответа Дел", ok = false, said = out?.said.orEmpty())
                    m.refused.isNotBlank() -> SayOutcome(SayFork.Kind.TASKS, m.line, ok = false, said = out?.said.orEmpty())
                    m.offline -> SayOutcome(SayFork.Kind.TASKS, "Переложу, как появится связь с Делами", ok = true)
                    m.result != null -> captureOutcome(m.result, out?.said.orEmpty())
                    else -> out
                }
            }
        }
    }
    val mic: () -> Unit = {
        val service = PravkaAccessibilityService.instance
        when {
            service == null -> ru.zf.pravka.ui.Feedback.toast(app, app.getString(R.string.toast_no_service))
            listening -> if (!service.finishDelaReason(keep = true)) listening = false
            else -> {
                out = null
                listening = service.listenForDelaReason("Говори дела — нажми микрофон ещё раз, разложу сам") { said ->
                    listening = false
                    val full = DelaAdd.joined(text, said)
                    text = ""
                    send(full, true)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (listenFirst) mic() else runCatching { focus.requestFocus() }
    }
    // Лист закрыли посреди диктовки — тейк бросается, как «отмена» в пилюле.
    DisposableEffect(Unit) {
        onDispose { if (listening) PravkaAccessibilityService.instance?.finishDelaReason(keep = false) }
    }
    val o = out
    val canSend = text.isNotBlank() && !busy
    PaperSheet(
        onDismiss = onDismiss,
        title = "Добавить дела",
        icon = Glyphs.Plus,
        footer = {
            PaperTextButton("Руками…", onClick = { onManual(text.trim()) })
            Spacer(Modifier.weight(1f))
            ru.zf.pravka.ui.GlyphButton(
                if (listening) Glyphs.Stop else Glyphs.Mic,
                if (listening) "готово — разложу сказанное" else "наговорить дела",
                onClick = mic,
                tint = if (listening) (if (Look.v2) Kv.accent else LocalMode.current.value) else LocalMode.current.label,
                enabled = !busy,
            )
            Spacer(Modifier.width(4.dp))
            // Отправил и поле пустое — «Готово» закрывает лист; есть что отправить — отправляет.
            if (!canSend && !busy && o != null) PaperButton("Закрыть", onClick = onDismiss, primary = true)
            else PaperButton("Готово", icon = Glyphs.Check, primary = true, enabled = canSend, onClick = { val x = text; text = ""; send(x, false) })
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; if (out?.ok == true) { out = null; timeChip = null } },
            placeholder = { Text(DelaAdd.HINT) },
            minLines = 4,
            maxLines = 10,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(if (Look.v2) KvShape.Control else 14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        when {
            listening -> Text("Слушаю — говори дела, нажми микрофон ещё раз, когда закончишь", style = t.meta, color = if (Look.v2) Kv.accent else LocalMode.current.value)
            busy -> ThinkingLine(busyLabel)
            o != null && !o.ok -> Text(o.text, style = t.body, color = MaterialTheme.colorScheme.error)
            o != null -> Column {
                val cap = o.capture
                // Строка «куда легло» — тап открывает дело (одно) или Дела.
                Text(
                    o.text,
                    style = t.bodyStrong,
                    color = if (Look.v2) Kv.text else ru.zf.pravka.ui.Ink.Text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(KvShape.Tag))
                        .clickable(role = Role.Button) { cap?.let { Capture.firstTask(it) }?.takeIf { it.isNotBlank() }?.let(onOpenTask) }
                        .padding(vertical = 2.dp),
                )
                if (cap != null) for (line in Capture.rows(cap)) Text("· $line", style = t.meta, color = if (Look.v2) Kv.muted else LocalMode.current.meta)
                val chips = if (cap == null) emptyList() else captureChips(cap, timeChip, { timeChip = it }, move, { kuda = true })
                if (chips.isNotEmpty()) ChipRow(Modifier.padding(top = 6.dp)) {
                    for (c in chips) PaperChip(c.label, selected = false, onClick = c.onClick)
                }
            }
        }
    }
    if (kuda) KudaSheet { kuda = false }
}
