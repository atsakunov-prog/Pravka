package ru.zf.pravka

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.ui.GhostKey
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Hairline
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.Key
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalMode
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.glass
import ru.zf.pravka.ui.scrollFade
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

// Карточка дела правой половиной разворота (Правка 4.0, `screens/08`): на
// разложенном Fold дело открывается не листом поверх списка, а рядом с ним —
// номер и проект, название крупно, «Начать в Засечке» · «Поправить» · ✓,
// плашка «Срок · Оценка · Мяч · Источник», история и человек. Правка — тем же
// листом `DelaTaskSheet` («Поправить»), здесь только чтение и три действия.

/** Панель вида v2 (`.card` холста): белая, линия, радиус 10, поля 12/14. */
@Composable
private fun PanelV2(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(KvShape.Panel)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kv.panel)
            .border(1.dp, Kv.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}
