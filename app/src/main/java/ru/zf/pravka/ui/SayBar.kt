package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Строка «сказать» Правки 4.0 (07.10.2026, DESIGN §11.4) — внизу каждого
// экрана, под большим пальцем: голос, текст, фото. Заменяет пилюлю «сказать
// режиму» первой строкой (версия 3). Вид — стекло режима (на «Сегодня» —
// нейтральное) и клавиша голоса справа внизу: главное действие экрана.
// Пилюля диктовки поверх приложений не меняется: это вид ВНУТРИ приложения.

/** Итог: одна строка «Записал в ленту · 2», тап — список с карандашами. */
class SayResult(val text: String, val onClick: () -> Unit)

/** Вопрос: одна строка и две клавиши — «да» и «сказать». */
class SayQuestion(val text: String, val onYes: () -> Unit, val onSay: () -> Unit)

/**
 * Строка «сказать». Пусто — подсказка режима; есть текст — клавиша голоса
 * становится «отправить» (стрелка вверх); [listening] — в клавише волна;
 * [busy] — заливка слева направо и «Причёсываю · ещё 6 с» ([busyLabel]);
 * [result] и [question] — одна строка вместо поля; [error] — плашка с
 * причиной над строкой. [above] — строка над подсказкой (в Засечке —
 * текущее занятие «Ужин с семьёй · с 18:30 · 21 м»), [leading] — слева
 * («стоп» в Засечке), [trailing] — значки перед клавишей (камера, галерея,
 * штрихкод, вставить). [cream] — кремовая клавиша «Сегодня».
 */
@Composable
fun SayBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onMic: (() -> Unit)? = null,
    listening: Boolean = false,
    sendEnabled: Boolean = value.isNotBlank(),
    enabled: Boolean = true,
    maxLines: Int = 4,
    busy: Boolean = false,
    busyLabel: String = "Разбираю",
    cream: Boolean = false,
    above: (@Composable () -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    result: SayResult? = null,
    question: SayQuestion? = null,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    sendIcon: ImageVector = Glyphs.ArrowUp,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    GlowBusy(busy)
    val progress by LocalClaudeProgress.current
    val seconds by LocalClaudeSeconds.current
    val tall = above != null
    val shape = RoundedCornerShape(if (tall) 32.dp else 31.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error != null) ErrorPlate(error, onRetry = onRetry)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (tall) 64.dp else 62.dp)
                .glass(shape, mode.glass)
                .then(
                    // В Засечке строка теплее и плотнее (`mockups/04`): налёт
                    // янтаря сверху и рамка ярче — она и есть пульт дня.
                    if (tall) Modifier.border(1.dp, mode.tint.copy(alpha = 0.45f), shape) else Modifier
                )
                .clip(shape)
                .drawBehind {
                    // Claude думает — заливка слева направо, как в пилюле диктовки.
                    val p = progress
                    if (busy) {
                        val f = (p ?: 1f).coerceIn(0f, 1f)
                        drawRect(mode.tint.copy(alpha = 0.25f), size = Size(size.width * f, size.height))
                    }
                }
                .padding(start = if (leading != null) 6.dp else if (trailing != null) 18.dp else 20.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                CompositionLocalProvider(LocalContentColor provides mode.label) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = leading)
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (above != null) above()
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyphs.Spark, null, tint = mode.label, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            busyLabel + (seconds?.let { " · ещё $it с" } ?: ""),
                            style = t.input.copy(fontSize = 15.sp),
                            color = Ink.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    question != null -> Text(
                        question.text,
                        style = t.input.copy(fontSize = 15.sp),
                        color = Ink.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    result != null -> Text(
                        result.text,
                        style = t.input.copy(fontSize = 15.sp),
                        color = Ink.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = result.onClick),
                    )
                    else -> BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        textStyle = (if (tall) t.input.copy(fontSize = 16.sp) else t.input).copy(color = Ink.Text),
                        cursorBrush = SolidColor(mode.tint),
                        maxLines = maxLines,
                        keyboardOptions = KeyboardOptions(imeAction = if (maxLines == 1) ImeAction.Send else ImeAction.Default),
                        keyboardActions = KeyboardActions(onSend = { if (sendEnabled && enabled) onSend() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) {
                                    Text(
                                        placeholder,
                                        style = if (tall) t.input.copy(fontSize = 16.sp) else t.input,
                                        color = mode.placeholder,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            if (question != null && !busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostKey("сказать", question.onSay, height = 40.dp)
                    PrimaryKey("да", question.onYes, height = 40.dp)
                }
                return@Row
            }
            if (trailing != null && !busy) {
                CompositionLocalProvider(LocalContentColor provides if (cream) Ink.PlanText else mode.label) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
                }
                Spacer(Modifier.width(4.dp))
            }
            val voice = onMic != null && value.isBlank() && result == null
            val active = when {
                busy -> false
                voice -> enabled || listening
                else -> sendEnabled && enabled
            }
            Key(
                icon = null,
                description = when {
                    busy -> "ждём ответа"
                    voice -> if (listening) "остановить" else "сказать голосом"
                    else -> "отправить"
                },
                onClick = { if (voice) onMic?.invoke() else if (sendEnabled && enabled) onSend() },
                size = if (tall) 52.dp else 50.dp,
                cream = cream,
                enabled = active || busy,
            ) {
                val ink = if (cream) Ink.NowInk else Ink.KeyIcon
                when {
                    busy -> {
                        val s = seconds
                        if (s != null) Text(s, style = t.mini.copy(fontSize = if (s.length > 3) 12.sp else 14.sp), color = ink)
                        else Icon(Glyphs.Spark, null, tint = ink, modifier = Modifier.size(20.dp))
                    }
                    voice && listening -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Волна громкости — ровная, без пульса: высоты стоят.
                        for (h in listOf(10, 18, 13, 16)) {
                            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(ink))
                        }
                    }
                    voice -> Icon(Glyphs.Mic, null, tint = ink, modifier = Modifier.size(24.dp))
                    else -> Icon(sendIcon, null, tint = ink, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** Значок в строке «сказать» — 40 dp без лица (камера, галерея, штрихкод, вставить). */
@Composable
fun SayIcon(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .size(width = 40.dp, height = 44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(22.dp))
    }
}

/**
 * «Стоп» слева в строке Засечки (`mockups/04`): круг 44 с рамкой и
 * квадратом со скруглением 3, залитым янтарём.
 */
@Composable
fun StopKey(onClick: () -> Unit, description: String = "остановить") {
    val mode = LocalMode.current
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFFFFC88C).copy(alpha = 0.10f))
            .border(1.dp, Color(0xFFFFBE78).copy(alpha = 0.30f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if (mode === Modes.Zasechka) Color(0xFFFFC261) else mode.value))
    }
}

/** Строка над подсказкой в Засечке: «Ужин с семьёй · с 18:30 · 21 м» (21 м — светло и жирно). */
@Composable
fun SayAbove(text: String, tail: String? = null) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Row {
        Text(text, style = t.meta.copy(lineHeight = 15.sp), color = Color(0xFFD9A06A).takeIf { mode === Modes.Zasechka } ?: mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (tail != null) Text(" · $tail", style = t.meta.copy(lineHeight = 15.sp, fontWeight = FontWeight.SemiBold), color = Ink.Now, maxLines = 1)
    }
}

/** Заглушка высоты под строкой «сказать» — чтобы последний пункт ленты не прятался за ней. */
@Composable
fun SayBarSpace() = Spacer(Modifier.height(96.dp))
