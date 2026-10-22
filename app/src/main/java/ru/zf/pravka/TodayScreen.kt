package ru.zf.pravka

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.CalendarRules
import ru.zf.pravka.core.DayAssembler
import ru.zf.pravka.core.DayAssembler.DayItem
import ru.zf.pravka.core.DayReport
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.core.WeatherDay
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.trigger.CalendarPilot
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.forkZasechka
import ru.zf.pravka.trigger.onZasechkaTap
import ru.zf.pravka.ui.Coin
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.HeaderIcon
import ru.zf.pravka.ui.IconLabel
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.LocalGlowDim
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.MiniStat
import ru.zf.pravka.ui.ModeDecor
import ru.zf.pravka.ui.ModeFrame
import ru.zf.pravka.ui.Modes
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.fadingScroll
import androidx.compose.ui.unit.sp
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.ui.twoPane
import java.time.LocalDate
import java.time.ZoneId

// «Сегодня» — главный экран Правки 4.0 (07.10.2026, DESIGN §1, §12.1–12.2).
// Хроника одного дня: лента Засечки, к которой прикреплены отметки всех
// режимов в тот момент, когда они случились, а после линии «сейчас» —
// будущее до сна: свободные окна, календарь, план тренировки, дела на
// сегодня одно за другим и сон. Режимы — линзы на этот день: плашки под
// погодой, пилюля «+84», тап по отметке, «С» → Ещё.
//
// Правило сборки — `core/DayAssembler.kt` (под тестами); здесь — данные из
// сторов, шапка (развёрнута, пока «сейчас» видно или выше окна, сжимается,
// когда листаем в прошлое — §11.2) и строка «сказать» внизу.

/** Куда «Сегодня» уводит: режим, Ещё, статистика, Засечка на записи, Еда на приёме. */
internal class TodayNav(
    val mode: (Tab) -> Unit,
    val more: () -> Unit,
    val stats: () -> Unit,
    val zasechkaEntry: (Long) -> Unit,
    val foodAction: (String) -> Unit,
    val delaTask: (String) -> Unit,
    /** «Новое» Дел одной пачкой (`batch_ref`): дайджест → «Открыть «Новое»» — к предложениям этой сводки. */
    val delaBatch: (String) -> Unit = { mode(Tab.TODOIST) },
    /** Страница сервиса Ковчега — внутри приложения или браузером, по настройке (`ServiceWebScreen.kt`). */
    val web: (ru.zf.pravka.core.ServicePage) -> Unit,
    /** «Claude: траты» — значок денег справа в шапке (14.10.2026, `ClaudeSpendScreen.kt`). */
    val cost: () -> Unit = {},
)

/** Всё, что «Сегодня» знает о дне. */
internal class TodayModel(
    val day: LocalDate,
    val dayStart: Long,
    val result: DayAssembler.Result,
    val facts: DayFacts,
    val delaDone: Int,
    val delaTotal: Int,
    val sportDone: Int,
    val sportTotal: Int,
    val kcal: Int,
    val kcalGoal: Int,
    val spentKop: Long,
    val limitKop: Long,
    val tasksById: Map<String, Dela.Task>,
    val entries: Map<Long, ru.zf.pravka.data.ZasechkaStore.Entry>,
)

/** Пять мини-плашек сжатой шапки: Засечка, Дела, Спорт, Еда, Деньги. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.CompactMinis(model: TodayModel, nav: TodayNav) {
    MiniStat(Fmt.points(model.result.score), Modes.Zasechka, "Засечка ${Fmt.points(model.result.score)}", { nav.mode(Tab.ZASECHKA) })
    MiniStat("${model.delaDone}/${model.delaTotal}", Modes.Dela, "Дела", { nav.mode(Tab.TODOIST) })
    MiniStat("${model.sportDone}/${model.sportTotal}", Modes.Sport, "Спорт", { nav.mode(Tab.SPORT) })
    MiniStat(Fmt.num(model.kcal), Modes.Food, "Еда", { nav.mode(Tab.FOOD) })
    MiniStat(Fmt.rub(model.spentKop), Modes.Money, "Деньги", { nav.mode(Tab.MONEY) })
}

// ---------------------------------------------------------------------------
// Разворот (DESIGN §12.2, `screens/05`)
// ---------------------------------------------------------------------------
