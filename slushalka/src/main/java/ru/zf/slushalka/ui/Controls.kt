package ru.zf.slushalka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Кнопки плеера рисуются, а не берутся из набора иконок: в material-icons-core
 * нет ни паузы, ни «пятнадцати секунд назад», а тащить ради двух глифов
 * расширенный набор в APK незачем.
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    size: Dp = 76.dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(scheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) {
            val c = scheme.onPrimary
            if (playing) {
                val bar = this.size.width * 0.3f
                val gap = this.size.width * 0.4f
                drawRoundRect(
                    color = c,
                    topLeft = Offset(0f, 0f),
                    size = Size(bar, this.size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.25f),
                )
                drawRoundRect(
                    color = c,
                    topLeft = Offset(bar + gap, 0f),
                    size = Size(bar, this.size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.25f),
                )
            } else {
                val p = Path().apply {
                    moveTo(this@Canvas.size.width * 0.08f, 0f)
                    lineTo(this@Canvas.size.width, this@Canvas.size.height / 2f)
                    lineTo(this@Canvas.size.width * 0.08f, this@Canvas.size.height)
                    close()
                }
                drawPath(p, c)
            }
        }
    }
}

/**
 * Перемотка на N секунд. Кнопка крупная - в неё попадают на ходу и не глядя, -
 * а рисунок в ней простой: двойная стрелка и число. Круговая стрелка с цифрой
 * внутри выглядела наряднее, но читалась хуже.
 */
@Composable
fun SkipButton(
    seconds: Int,
    forward: Boolean,
    size: Dp = 68.dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = size * 0.46f, height = size * 0.24f)) {
                val c = scheme.onSurface
                val w = this.size.width
                val h = this.size.height
                val half = w * 0.46f
                fun triangle(left: Float) {
                    val p = Path().apply {
                        if (forward) {
                            moveTo(left, 0f)
                            lineTo(left + half, h / 2f)
                            lineTo(left, h)
                        } else {
                            moveTo(left + half, 0f)
                            lineTo(left, h / 2f)
                            lineTo(left + half, h)
                        }
                        close()
                    }
                    drawPath(p, c)
                }
                triangle(0f)
                triangle(w - half)
            }
            Text(
                text = "$seconds",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.23f).sp,
                ),
                color = scheme.onSurface,
            )
        }
    }
}

@Composable
fun SleepButton(leftMs: Long, size: Dp = 46.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val active = leftMs > 0
    val tint = if (active) scheme.primary else scheme.onSurfaceVariant
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(size * 0.44f)) {
                // Месяц - круг за вычетом сдвинутого круга: так у него ровные
                // рога, чего не даёт ни дуга, ни две накладки.
                val r = this.size.minDimension / 2f
                val full = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            Offset(this@Canvas.size.width / 2f, this@Canvas.size.height / 2f), r,
                        )
                    )
                }
                val bite = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            Offset(
                                this@Canvas.size.width / 2f + r * 0.52f,
                                this@Canvas.size.height / 2f - r * 0.14f,
                            ),
                            r * 0.92f,
                        )
                    )
                }
                drawPath(Path.combine(PathOperation.Difference, full, bite), tint)
            }
            if (active) {
                Text(
                    "${(leftMs / 60_000) + 1}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (size.value * 0.2f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = tint,
                )
            }
        }
    }
}

/** Скорость - справа от play, числом: цифра тут понятнее любой пиктограммы. */
@Composable
fun SpeedButton(speed: Float, size: Dp = 46.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val changed = kotlin.math.abs(speed - 1f) > 0.01f
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (changed) scheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            formatSpeed(speed),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = (size.value * 0.26f).sp,
                fontWeight = FontWeight.Bold,
            ),
            color = if (changed) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}
