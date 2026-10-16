package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay

// Пояснение к заголовку — по долгому нажатию (отладка 16.10.2026 вечер, запись 34; владелец: «я бы все пояснения
// всякие убрал из приложения»). Серые подписи под заголовками групп и блоков («отметил сам — сделать сегодня»,
// «срок прошёл — сделать, перенести…») сняты с экрана: заголовок и число остаются, а что это за группа — всплывает у
// заголовка, если подержать на нём палец, и уходит само через несколько секунд или по касанию мимо.

/** Сколько подсказка висит, если её не убрали касанием. */
const val LONG_HINT_MS = 5_000L

/** Подпись действия для чтения с экрана: «подержи — пояснение». */
const val LONG_HINT_LABEL = "пояснение"

/**
 * Всплывающая подсказка [text] под тем, внутри чего её позвали (над ним — если внизу не влезает). [onClose] — касание
 * мимо или [LONG_HINT_MS].
 */
@Composable
fun HintPopup(text: String, onClose: () -> Unit) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    val shape = RoundedCornerShape(if (Look.v2) KvShape.Control else 12.dp)
    Popup(popupPositionProvider = remember(gap) { BelowAnchor(gap) }, onDismissRequest = onClose) {
        Text(
            text,
            style = LocalPravkaType.current.meta.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
            color = if (Look.v2) Kv.text else Ink.Text,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .shadow(8.dp, shape)
                .background(if (Look.v2) Kv.panel else Ink.Bg, shape)
                .border(1.dp, if (Look.v2) Kv.line else Ink.Text.copy(alpha = 0.15f), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    LaunchedEffect(text) {
        delay(LONG_HINT_MS)
        onClose()
    }
}

/**
 * Заголовок с пояснением по долгому нажатию: [content] — сам заголовок; [text] пусто — просто заголовок. Касание
 * коротким тапом не занимает (у таких заголовков своего тапа нет); для заголовка с тапом — `combinedClickable` и
 * [HintPopup] рядом, как у блоков главной Дел.
 */
@Composable
fun LongHint(text: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (text.isNullOrBlank()) {
        Box(modifier) { content() }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .pointerInput(text) { detectTapGestures(onLongPress = { open = true }) }
            .semantics { onLongClick(label = LONG_HINT_LABEL) { open = true; true } },
    ) {
        content()
        if (open) HintPopup(text) { open = false }
    }
}

/** Под якорем от его левого края; не влезает вниз — над ним; по ширине — в пределах окна. */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
