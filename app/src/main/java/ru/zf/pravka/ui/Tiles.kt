package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Плашка режима на «Сегодня» (DESIGN §8 «Плашка режима», §11.3, `mockups/01`):
// значок и подпись, цифра дня, полоса прогресса. Четыре в ряд — Дела, Спорт,
// Еда, Деньги: вход в режим и его итог дня одним взглядом.

/** Что показывает плашка: обычное, пусто «—», «считаю…» или нет связи (цифра @ .55). */
enum class TileState { NORMAL, EMPTY, LOADING, OFFLINE }

/**
 * Плашка режима: основа — чернила @ .92, поверх градиент tint @ .29 сверху →
 * key @ .20 на 55 % → key @ .30 внизу, рамка 1.5 dp tint @ .55, внутренняя
 * линия сверху и тень 0/8/20. [progress] — доля для полосы 4 dp; null —
 * полосы нет (нет цели или лимита).
 */
@Composable
fun ModeTile(
    decor: ModeDecor,
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    state: TileState = TileState.NORMAL,
) {
    val m = Modes.of(decor)
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier
            .scale(if (pressed) 0.97f else 1f)
            .softShadow(shape, Color.Black.copy(alpha = 0.35f), 8.dp, 20.dp)
            .clip(shape)
            .background(m.ink.copy(alpha = 0.92f))
            .background(
                Brush.verticalGradient(
                    0f to m.tint.copy(alpha = if (pressed) 0.38f else 0.29f),
                    0.55f to m.key.copy(alpha = 0.20f),
                    1f to m.key.copy(alpha = 0.30f),
                )
            )
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.16f), topLeft = Offset(0f, 1.5.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            }
            .border(1.5.dp, m.tint.copy(alpha = 0.55f), shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label: $value" }
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = m.label, modifier = Modifier.size(16.dp))
            Text(label, style = t.label, color = m.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
            when (state) {
                TileState.LOADING -> Text("считаю…", style = t.caption, color = m.meta, maxLines = 1)
                TileState.EMPTY -> Text("—", style = t.valueL, color = m.value.copy(alpha = 0.7f), maxLines = 1)
                else -> FitText(
                    value,
                    style = t.valueL,
                    color = m.value.copy(alpha = if (state == TileState.OFFLINE) 0.55f else 1f),
                    minSize = 12f,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .drawBehind {
                    if (progress == null) return@drawBehind
                    val r = CornerRadius(2.dp.toPx())
                    drawRoundRect(m.tint.copy(alpha = 0.18f), cornerRadius = r)
                    val f = progress.coerceIn(0f, 1f)
                    if (f > 0f) drawRoundRect(lerp(m.tint, Color.White, 0.18f), size = Size(size.width * f, size.height), cornerRadius = r)
                },
        )
    }
}
