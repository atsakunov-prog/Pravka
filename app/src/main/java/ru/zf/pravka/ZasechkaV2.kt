package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DayProfile
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaGroups
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.Lenta
import ru.zf.pravka.core.ZasechkaTasks
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvSetRow
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.SmallKey
import ru.zf.pravka.ui.Toggle
import ru.zf.pravka.ui.twoPane
import java.time.LocalDate

// Засечка вида v2 (Правка 5.0, этап 3; `docs/redesign-5-plan.md` §4, холст
// «Ковчег на телефоне», лист «Засечка»). Сложенный — сверху вниз: «Сейчас»
// крупно (что идёт, таймер, «Стоп», «Поправить», откуда записано) и «Дальше»
// (дела с ▶), профиль дня с легендой категорий, лента с вечера к утру
// (идущее и шесть последних отрезков; «Весь день» — целиком). Разложенный
// (≥ 600 dp): слева «Сейчас» с «Дальше», «Итог дня» по категориям и автопилот
// строками настройки; справа «Весь день» — гора и лента целиком. Внизу —
// строка «сказать» Засечки: тот же тап «З» и тот же разбор набранного.
//
// Логика не тронута — это перекладка экрана. Лента, листы записи, дыры, дела с
// ▶ и тумблеры автопилота — прежние (`ZasechkaTab.kt`, `ZasechkaEngine`,
// `Settings`). Гора — `ui/Mountain.kt`; лента строками — `core/Lenta.kt` из
// хроники дня «Сегодня» (`buildModel`: те же отметки еды, спорта, дел, денег
// и сна, второго сбора нет); цвет категории — «чей это» (`categoryTone`).
// «Вид 4.0» — прежняя вкладка (`Zasechka4Body` в `ZasechkaTab.kt`).

/** Значок набора по имени веба; нет — запасной. */
private fun kv(key: String, fallback: ImageVector): ImageVector = KvGlyphs.byKey(key) ?: fallback

/** Запись — заполнитель «не размечено» (живая дыра): её не останавливают, а размечают. */
private fun ZasechkaStore.Entry.isGap(): Boolean =
    source == "gap" || category.trim().equals(DayProfile.UNMARKED, ignoreCase = true)

// ---- Сейчас и Дальше ----
