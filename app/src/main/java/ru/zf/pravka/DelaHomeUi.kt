package ru.zf.pravka

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Asks
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaGroups
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.Look
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule
import kotlinx.coroutines.launch

// Дела в виде v2 (этап 4 «Правки 5.0», 13.10.2026, docs/dela-phone-19.md; лист «Дела» холста «Ковчег на
// телефоне», утверждён владельцем): главная «Сейчас» — блоки «Сейчас», «Новое», «Сегодня»; вкладки с чертой
// на сложенном и колонка разделов с числами на разложенном — как веб Дел (`renderNow`, боковая панель).
// Правила — в `core/DelaGroups.kt` и `core/DelaViews.kt`, здесь только как это выглядит.

/** Значок набора Ковчега по имени веба; нет такого — запасной штрих приложения. */
private fun kv(key: String, fallback: ImageVector): ImageVector = KvGlyphs.byKey(key) ?: fallback

/**
 * Строка «Сейчас на завтра: предложено 7 · отмечено 2 из 5 ›» (docs/dela-phone-27.md) — в «Сейчас» Дел под «Новым» и
 * сверху «Нового»: молния, слова, стрелка; тап — пачка кандидатов. Акцентом: это выбор на завтра, а не справка.
 */
@Composable
internal fun PlanLine(text: String, onClick: () -> Unit) = LinkLine(Glyphs.Bolt, text, onClick, accent = true)

/** Строка-ссылка блока: значок, слова переносом, стрелка; [accent] — краской акцента («Сейчас на завтра»). */
@Composable
private fun LinkLine(icon: ImageVector, text: String, onClick: () -> Unit, accent: Boolean = false) {
    val t = LocalPravkaType.current
    val v2 = Look.v2
    val ink = if (v2) (if (accent) Kv.accent else Kv.muted) else ru.zf.pravka.ui.LocalMode.current.label
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (v2) (if (accent) Kv.accent else Kv.icon) else ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        // v2 — переносом по словам целиком, без «…» (14.10.2026: «Всё «Новое»: добавлено за 3 дня…» на сложенном
        // обрезалось посреди счёта); «Вид 4.0» — как был.
        if (v2) Text(ru.zf.pravka.core.HeadLine.wrap(text), style = t.label, color = ink, modifier = Modifier.weight(1f))
        else Text(text, style = t.label, color = ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Glyphs.Forward, contentDescription = null, tint = if (v2) Kv.faint else ink, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------- колонка разделов
