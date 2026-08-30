package ru.zf.slushalka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Записки, спрятанные по приложению.
 *
 * Приложение делалось для двоих, и это его личная часть: где-то строчка стоит
 * всегда, где-то показывается изредка, а на экране плеера почти сливается с
 * фоном - её видно, только если приглядеться.
 */
object Love {

    const val LINE = "Мариаша, я тебя люблю"
    const val NIGHT = "Спокойной ночи, Мариаша"

    /** Раз в [chance] случаев. Постоянная строчка приелась бы, редкая - радует. */
    fun rarely(chance: Int = 4): Boolean = kotlin.random.Random.nextInt(chance) == 0
}

/** Строчка вполголоса: [alpha] задаёт, насколько её вообще видно. */
@Composable
fun LoveLine(
    modifier: Modifier = Modifier,
    text: String = Love.LINE,
    alpha: Float = 0.5f,
    size: Int = 11,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Text(
        text = text,
        color = (color ?: MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
        fontSize = size.sp,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
