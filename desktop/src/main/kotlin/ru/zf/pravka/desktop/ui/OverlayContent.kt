package ru.zf.pravka.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import ru.zf.pravka.desktop.Controller

// Плашка поверх окон: то же, что плашка на телефоне. Показывает, что сейчас
// происходит, уровень микрофона и ответ модели по мере поступления.
//
// Окно не забирает фокус (focusable = false в вызывающем коде): иначе курсор
// уходит из поля, куда мы собираемся писать.
@Composable
fun OverlayContent(state: Controller.UiState, level: StateFlow<Float>) {
    val loudness by level.collectAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xE6141414), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.title.ifBlank { "Правка" },
                    color = if (state.error != null) Color(0xFFFF6B6B) else PravkaIcon.accent,
                    fontSize = 14.sp,
                )
                if (state.phase == Controller.Phase.RECORDING) {
                    Spacer(Modifier.width(12.dp))
                    LevelBar(loudness)
                }
            }
            val body = state.error ?: state.streamed
            if (body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    color = Color(0xFFE6E6E6),
                    fontSize = 13.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LevelBar(level: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        // Двенадцать делений: столбик виден и краем глаза, и не отвлекает.
        repeat(12) { index ->
            val lit = level * 12 > index
            Box(
                Modifier
                    .width(4.dp)
                    .height(if (lit) (6 + index).dp else 4.dp)
                    .background(
                        if (lit) PravkaIcon.accent else Color(0xFF3A3A3A),
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}
