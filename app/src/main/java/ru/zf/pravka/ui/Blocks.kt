package ru.zf.pravka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import ru.zf.pravka.core.Micronutrients

// Общие кирпичи бумажной вёрстки: заголовок раздела, карточка, подсказка,
// полоска «сколько от цели». Настройки Правки держат такие же у себя внутри
// (private в MainActivity.kt), но вкладкам «Спорт» и «Еда» они нужны обеим -
// а два экрана с чуть разными отступами выглядят как два приложения.

/**
 * Фаска по кромке плашки: светлая линия сверху, тёмная снизу, посередине её
 * нет. Один штрих с продольным градиентом — ровный кант по всему периметру
 * читался бы как рамка виджета, а разный сверху и снизу — как толщина
 * предмета. То же правило, что у `trigger/BubbleSkin.kt` на стекле: свет в
 * приложении и на кнопках должен падать с одной стороны, иначе это два
 * дизайна в одном экране.
 */
fun Modifier.bevel(shape: Shape? = null): Modifier = composed {
    // Вид v2: вместо фаски — ровная линия `--line`, как у всех панелей.
    if (Look.v2) return@composed border(1.dp, Kv.line, shape ?: MaterialTheme.shapes.medium)
    border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = CardLook.RIM_LIGHT),
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = CardLook.RIM_SHADE),
        ),
        shape = shape ?: MaterialTheme.shapes.medium,
    )
}

/**
 * Плашка с необязательным заголовком раздела над ней. Правка 4.0 (07.10.2026,
 * DESIGN §8): плашка — стекло в чернилах режима (на «Сегодня» — нейтральное,
 * на служебных экранах — в цвете Правки), радиус 22; заголовок — overline
 * над плашкой, справа «i» и [trailing]. [info] — пояснение за «i»: на
 * плашке — сама вещь, а не инструкция.
 */
@Composable
fun PaperCard(
    label: String? = null,
    labelColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    info: String? = null,
    /** Подпись рядом с заголовком мелко и бледно (`.sec small` веба): «12–19 октября», «2 приёма». Только v2. */
    note: String? = null,
    /**
     * Пояснение к заголовку — всплывает по долгому нажатию на него (`LongHint`, 16.10.2026 вечер, запись 34: серых
     * подписей-пояснений под заголовками больше нет).
     */
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val mode = LocalMode.current
    if (Look.v2) {
        PanelV2(label, labelColor, trailing, info, note, hint, content)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (label != null || trailing != null || info != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LongHint(hint, Modifier.weight(1f)) {
                    Text(
                        (label ?: "").uppercase(Locale.forLanguageTag("ru")),
                        style = LocalPravkaType.current.overline,
                        color = labelColor ?: mode.label,
                    )
                }
                if (info != null) InfoDot(label ?: "Пояснение", info)
                trailing?.invoke()
            }
        }
        val shape = RoundedCornerShape(PLATE_RADIUS)
        Box(Modifier.fillMaxWidth().glass(shape, mode.glass)) {
            CompositionLocalProvider(LocalContentColor provides Ink.Text) {
                Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
            }
        }
    }
}

/**
 * Плашка вида v2 — `.card` веба: панель, линия 1, радиус 10, поля 12/14;
 * заголовок раздела — ВНУТРИ панели первой строкой (`.sec`: 14,5/600 цветом
 * текста), а не надстрочником над ней, как в 4.0.
 */
@Composable
private fun PanelV2(
    label: String?,
    labelColor: Color?,
    trailing: (@Composable () -> Unit)?,
    info: String?,
    note: String?,
    hint: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(KvShape.Panel)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kv.panel)
            .border(1.dp, Kv.line, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Kv.text) {
            if (label != null || trailing != null || info != null) {
                Row(
                    // «i» и действие справа — со своей зоной касания 44: строка заголовка не растёт от них.
                    Modifier.fillMaxWidth().height(24.dp).padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LongHint(hint, if (note.isNullOrBlank()) Modifier.weight(1f) else Modifier) {
                        Text(
                            label.orEmpty().replaceFirstChar { it.uppercase() },
                            style = LocalPravkaType.current.bodyStrong,
                            color = labelColor ?: Kv.text,
                            maxLines = 1,
                        )
                    }
                    if (!note.isNullOrBlank()) {
                        // `.sec small`: сразу за заголовком, а не у правого края — справа место статусу и действию.
                        Text(
                            note,
                            style = LocalPravkaType.current.meta,
                            color = Kv.faint,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (info != null) InfoDot(label ?: "Пояснение", info)
                    // Действие справа — своей высоты, строка остаётся 24 dp (отладка 16.10.2026, записи 1–2): строка
                    // ужимала кнопку словом (минимум 40) до 22 dp, и «Понятно» над «Новым» на развороте читалось
                    // чёрточкой, «Показать» у «Сделано само» — так же.
                    if (trailing != null) Box(Modifier.wrapContentHeight(unbounded = true), contentAlignment = Alignment.Center) { trailing() }
                }
                Spacer(Modifier.height(4.dp))
            }
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** Радиус плашки-стекла (DESIGN §7: плашки 22–24). */
val PLATE_RADIUS = 22.dp

/** Мелкий серый текст под значением: «база 45 за две недели». */
@Composable
fun PaperHint(text: String, color: Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

val MicroOk = Color(0xFF16A34A)      // норма закрыта