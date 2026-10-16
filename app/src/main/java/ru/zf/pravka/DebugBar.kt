package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.OffsetDateTime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaDebug
import ru.zf.pravka.core.ScreenDebug
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType

// Строка «Что не так? — запишу с экраном» — одна на всё приложение (16.10.2026, владелец: «можешь сделать на телефоне
// тоже дебаг строку такую же как на вебе? Чтобы отправлялось вместе с тем же, что и на вебе»). Вид, поле, микрофон,
// «Enter — записать», тост «🐞 №N записал» — здесь; что было на экране, знает тот, кто её поставил: вкладка Дел и
// карточка — снимок Дел (`DelaDebugBar`, DelaDebugUi.kt), остальные экраны — общий снимок (`ScreenDebugBar`,
// ScreenDebugUi.kt). Записка — та же операция `debug.add`, docs/dela-phone-24.md.

/**
 * Снимок к записке. Собирается в миг, когда человек взялся за строку (`capture` строки: фокус поля или микрофон), —
 * до клавиатуры, которая сдвинула бы список; [build] достраивает его при отправке: снимок данными и последние действия.
 */
internal fun interface DebugCapture {
    suspend fun build(version: String, events: List<String>, now: OffsetDateTime): Pair<JSONObject, JSONArray>
}

/** Что было на экране, когда человек взялся за строку, и картинка экрана того же мига. */
private class Held(val at: Long, val capture: DebugCapture) {
    var shot: String? = null
}

private const val HOLD_MS = 5 * 60_000L

/** Снимок не собрался — записка уходит всё равно, с причиной вместо экрана. */
private fun failed(e: Throwable): DebugCapture = DebugCapture { version, events, now ->
    JSONObject().put("v", 1).put("route", "?").put("kind", "phone").put("title", "снимок не собрался: ${e.javaClass.simpleName}")
        .put("build", version) to DelaDebug.actions(DelaDebug.recentOps(), events, now, DelaDebug.recentNotes() + ScreenDebug.recentNav())
}

/**
 * Строка «Что не так? — запишу с экраном»: поле, микрофон (движок «Д» с заказом текста — `listenForDelaReason`, как
 * причина отказа и «🐞»), «записать» и номер последней записки — тапом список «Отладка». [capture] — что на экране
 * сейчас. Режим выключен или сервер старый — строки нет (`ScreenDebug.show`).
 */
@Composable
internal fun DebugBar(app: PravkaApp, capture: () -> DebugCapture, modifier: Modifier = Modifier) {
    val snap by app.delaStore.view.collectAsState()
    val info by app.delaDebug.info.collectAsState()
    if (!ScreenDebug.show(info, snap.features)) return
    val view = LocalView.current
    val conf = LocalConfiguration.current
    val wide = ru.zf.pravka.ui.twoPane()
    val t = LocalPravkaType.current
    var text by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Held?>(null) }

    fun hold() {
        val now = System.currentTimeMillis()
        if (held?.let { now - it.at < HOLD_MS } == true) return
        val h = Held(now, runCatching(capture).getOrElse(::failed))
        held = h
        app.appScope.launch { h.shot = DelaShot.take(view) }
    }

    fun send(said: String) {
        val body = said.trim()
        if (body.isEmpty() || sending) return
        sending = true
        val h = held?.takeIf { System.currentTimeMillis() - it.at < HOLD_MS }
            ?: Held(System.currentTimeMillis(), runCatching(capture).getOrElse(::failed))
        val version = "${BuildConfig.VERSION_CODE} ${BuildConfig.VERSION_NAME}".trim()
        val size = "${conf.screenWidthDp}×${conf.screenHeightDp} dp " + if (wide) "разворот" else "сложен"
        app.appScope.launch {
            val shot = h.shot ?: DelaShot.take(view)
            val events = runCatching { app.delaDebug.events() }.getOrDefault(emptyList())
            val now = OffsetDateTime.now()
            val (snapJson, actions) = runCatching { h.capture.build(version, events, now) }
                .getOrElse { e -> failed(e).build(version, events, now) }
            val op = DelaDebug.addOp(Dela.newId(), Dela.newId(), body, version, size, now.toString(), snapJson, actions, shot)
            val words = runCatching { app.delaDebug.send(op) }.getOrElse { "Не записал: ${it.message}" }
            // Отказ сервера — текст остаётся в поле: дописать и отправить ещё раз. Легло в очередь — поле пустое.
            if (!words.startsWith(ru.zf.pravka.core.DelaNotices.REFUSED) && !words.startsWith("Не записал")) {
                text = ""
                held = null
            }
            sending = false
            Feedback.toast(app, words)
        }
    }

    fun mic() {
        val service = PravkaAccessibilityService.instance
        when {
            service == null -> Feedback.toast(app, app.getString(R.string.toast_no_service))
            listening -> if (!service.finishDelaReason(keep = true)) listening = false
            else -> {
                hold()
                val base = text.trim()
                // «Стоп» — текст в поле, записывает владелец стрелкой (16.10.2026, запись 12 отладки: «иногда хочу
                // что-то подправить» — записки 11–13 ушли с ослышками, никем не увиденные).
                listening = service.listenForDelaReason("Говори, что не так — нажми ещё раз, текст встанет в поле") { said ->
                    listening = false
                    text = ru.zf.pravka.core.DelaAsk.voiced(base, said)
                }
            }
        }
    }

    val shape = RoundedCornerShape(KvShape.Control)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            // Тег — чтобы общий снимок экрана не писал саму строку (`ScreenDebug.BAR_TAG`).
            .testTag(ScreenDebug.BAR_TAG)
            .semantics { contentDescription = "Режим отладки: что не так — запишу вместе с тем, что на экране" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Glyphs.Bug, contentDescription = null, tint = Kv.muted, modifier = Modifier.size(18.dp))
        Box(
            Modifier.weight(1f).height(36.dp).clip(shape).background(Kv.panel).border(1.dp, Kv.line, shape).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Что не так? — запишу с экраном", style = t.body.copy(fontSize = 14.sp), color = Kv.faint, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !sending,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(text) }),
                textStyle = t.body.copy(fontSize = 14.sp, color = Kv.text),
                cursorBrush = SolidColor(Kv.accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) hold() },
            )
        }
        GlyphButton(
            if (listening) Glyphs.Stop else Glyphs.Mic,
            if (listening) "хватит слушать — текст в поле" else "сказать голосом",
            onClick = { mic() }, tint = if (listening) Kv.accent else Kv.icon, enabled = !sending, size = 36.dp,
        )
        if (text.isNotBlank() && !listening) GlyphButton(Glyphs.ArrowUp, "записать", onClick = { send(text) }, tint = Kv.accent, enabled = !sending, size = 36.dp)
        val i = info
        Text(
            if (i != null && i.last > 0) "№${i.last}" + if (i.new > 0 && wide) " · новых ${i.new}" else "" else "записки",
            style = t.meta,
            color = if ((i?.new ?: 0) > 0) Kv.accent else Kv.muted,
            maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(KvShape.Tag)).clickable { app.delaDebug.listOpen.value = true }.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}
