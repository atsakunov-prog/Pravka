package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import ru.zf.pravka.ui.glass
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaAsk
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule

// Правка дел словами (05.10.2026, docs/dela-phone-2.md, этап 1, пункт 3):
// микрофон у дела и строка «Claude» над экраном вида. Делает сервер
// (`/api/ask`) — так же, как веб, с моделью и глубиной из настроек человека;
// телефон собирает, что на экране, ждёт задание и показывает итог с «Вернуть
// всё». Без сети команда не уходит — и текст остаётся в поле.

/**
 * Разговор с Claude (09.10.2026, docs/dela-phone-9.md, п. 12; владелец: «он
 * регулярно просит ещё следующий вопрос — в его ответе тоже должен быть
 * текстбокс»): что видел экран в начале ([scope]), реплики, ждущая реплика
 * ([busy]), набранное в строке ответа и причина, если реплика не ушла.
 */
internal data class Talk(
    val scope: JSONObject,
    val turns: List<DelaAsk.Turn> = emptyList(),
    val busy: String = "",
    val text: String = "",
    val error: String = "",
)

/**
 * Что сейчас в окне (08.10.2026, docs/dela-phone-7.md, `pageScope` веба):
 * строки дел и плитки сделок отмечают, где они, — обрезанные окном списка
 * (`boundsInWindow`) и целиком. В миг отправки [visible] отдаёт те, от которых
 * видно хотя бы 60 % выше строки Claude. Не состояние Compose — просто записная
 * книжка: перерисовок она не вызывает. Ушедшая из списка строка себя стирает.
 */
internal class SeenRows {
    private val rows = HashMap<String, DelaAsk.Seen>()
    var top = 0f
    var bottom = Float.MAX_VALUE

    fun put(key: String, r: DelaAsk.Seen) { rows[key] = r }
    fun drop(key: String) { rows.remove(key) }

    /** Ключи с приставкой [prefix] («t:» — дела, «d:» — сделки), которые в окне, — без приставки. */
    fun visible(prefix: String): List<String> =
        DelaAsk.visible(rows.filterKeys { it.startsWith(prefix) }, top, bottom).map { it.removePrefix(prefix) }

    /** Все отмеченные с приставкой — сверху вниз (и те, что за краем окна, но ещё нарисованы). */
    fun all(prefix: String): List<String> =
        rows.entries.filter { it.key.startsWith(prefix) }.sortedWith(compareBy({ it.value.top }, { it.value.left })).map { it.key.removePrefix(prefix) }
}

internal val LocalSeenRows = androidx.compose.runtime.staticCompositionLocalOf<SeenRows?> { null }

/** Отметить строку или плитку в [SeenRows] под ключом [key] («t:<id>», «d:<id>»). */
@Composable
internal fun Modifier.seen(key: String): Modifier {
    val rows = LocalSeenRows.current ?: return this
    androidx.compose.runtime.DisposableEffect(key) { onDispose { rows.drop(key) } }
    return this.then(Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        rows.put(key, DelaAsk.Seen(b.top, b.bottom, b.left, b.right, c.size.height.toFloat(), c.size.width.toFloat()))
    })
}

/** Что сказал — пузырём справа. */
@Composable
private fun Said(text: String) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = ru.zf.pravka.ui.LocalPravkaType.current.body,
            color = ru.zf.pravka.ui.Ink.Text,
            modifier = Modifier.padding(start = 40.dp, top = 6.dp, bottom = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(mode.tint.copy(alpha = 0.18f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** «1 дело», «3 дела», «5 дел». */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}
