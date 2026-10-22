package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Asks
import ru.zf.pravka.core.DayProfile
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.DigestNote
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.Lenta
import ru.zf.pravka.core.ShabbatTimes
import ru.zf.pravka.core.TodayAgenda
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.Counter
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.ModeDecor
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.SmallKey
import ru.zf.pravka.ui.twoPane
import java.time.LocalDate

// «Сегодня» вида v2 (Правка 5.0, этап 2; `docs/redesign-5-plan.md` §4, холст
// «Ковчег на телефоне», `Main.dc.html`). Сложенный — сверху вниз: «идёт
// сейчас» с горой дня, погода, «Сейчас», последний дайджест, «Новое» с «да» и
// «нет». Разложенный — «мини-компьютер»: слева плитки (Еда, Спорт, Деньги,
// Шаббат), «идёт сейчас» с горой и лента с вечера к утру; справа погода,
// «Сейчас», «Новое» и «Сегодня» (срок сегодня и встречи, завтра — строкой).
// Строка «сказать» — одна на всё, внизу (`TodaySay.kt`).
//
// Хроника 4.0 (`TodayFolded`, `TodayWide` в `TodayScreen.kt`) живёт для «Вида
// 4.0» как была: новая раскладка — только в v2.

/** Что видит «Сегодня» v2 сверх модели дня. */
internal class TodayV2Data(
    val model: TodayModel,
    val profile: DayProfile.Profile,
    val lenta: List<Lenta.Row>,
    /** Идёт сейчас; null — ничего. */
    val open: ZasechkaStore.Entry?,
    /** Последнее закрытое — когда ничего не идёт. */
    val last: ZasechkaStore.Entry?,
    val nowTasks: List<Dela.Task>,
    /**
     * «Подскажи» на «Сегодня» (`core/Asks.kt`, docs/dela-phone-28.md): с флагом `asks` у сервера — по фазе (до 15:00
     * строкой, с 15:00 — сгорающее, вечером — группами, с Пт 15:00 — тихо), без него — прежний список «Нового».
     */
    val queue: Asks.Queue,
    val todayRows: List<TodayAgenda.Row>,
    val tomorrow: String,
    val snap: Dela.Snapshot,
    val todayKey: String,
    val tiles: TodayTiles,
    /** Дела подключены: без них «Сейчас» и «Новое» не показываются. */
    val dela: Boolean,
    /** В ленте идёт или только что была «Еда», а в дневнике пусто (`MealGap`); null — подсказки нет. */
    val mealGap: ru.zf.pravka.core.MealGap.Gap? = null,
    /**
     * Напоминания в свой час и свои отметки календаря (захват, 15.10.2026, `TodayAgenda.reminderRows`): «12:00 ⏰
     * Отправить счёт в Контуре», пора — «✓ · +1 ч · завтра».
     */
    val reminders: List<TodayAgenda.RemindRow> = emptyList(),
    /** «Вернулось» — дела «потом», чей день настал (`TodayAgenda.backRows`). */
    val back: List<TodayAgenda.BackRow> = emptyList(),
    /** «Сейчас на завтра» (docs/dela-phone-27.md): строкой последней в «Новом»; null — строки нет. */
    val plan: DelaViews.Plan? = null,
)

/** Числа плиток разложенного: еда, спорт, деньги, Шаббат. */
internal class TodayTiles(
    val kcal: Int,
    val kcalGoal: Int,
    val sportValue: String?,
    val sportUnit: String?,
    val sportSub: String?,
    val moneyDayKop: Long,
    val moneyMonth: String?,
    val shabbat: ShabbatTimes.Times?,
)

/** Действия «Сегодня» v2: открыть дело, сделать, решить «Новое», остановить идущее. */
internal class TodayV2Actions(
    val done: (Dela.Task) -> Unit,
    val accept: (Dela.Suggestion) -> Unit,
    val reject: (Dela.Suggestion, String) -> Unit,
    val stop: () -> Unit,
    /** «Раньше» у дайджеста: сводки за двое суток без [currentId]; нет сети или старый сервер — исключение. */
    val earlier: suspend (currentId: String) -> List<DigestNote>,
    /** Подсказка «в ленте едим, а в дневнике пусто»: записать голосом или снимком — дороги Еды. */
    val mealVoice: () -> Unit,
    val mealPhoto: () -> Unit,
    val shabbat: () -> Unit,
    val lenta: (Lenta.Row) -> Unit,
    /** Ответ вариантом «Подскажи» («Не для CRM», «Никто»), где кнопок не видно, — `suggestion.decide` с `option`. */
    val answer: (Dela.Suggestion, DelaViews.Option) -> Unit = { sg, _ -> reject(sg, "") },
    /** Кнопка напоминания на «Сегодня» — та же, что в уведомлении: «Сделано», «Через час», «Завтра». */
    val remind: (taskId: String, ru.zf.pravka.trigger.Reminders.Act) -> Unit = { _, _ -> },
    /** «Вернулось»: «В „Сейчас“», «Сегодня», «Ещё потом», «Сделано». */
    val back: (taskId: String, BackTo) -> Unit = { _, _ -> },
)

/** Куда деть вернувшееся «потом» (`backBox` веба). */
internal enum class BackTo(val label: String) { NOW("В «Сейчас»"), TODAY("Сегодня"), LATER("Ещё потом"), DONE("Сделано") }

/** Карточки «Сегодня» — общие для обеих раскладок. */
private class TodayCards(
    val data: TodayV2Data,
    val weather: ru.zf.pravka.data.WeatherStore.Result?,
    val weatherOpen: Boolean,
    val onWeather: () -> Unit,
    val digest: DigestNote?,
    val digestOpen: Boolean,
    val onDigest: () -> Unit,
    val now: Long,
    val today: Boolean,
    val nav: TodayNav,
    val act: TodayV2Actions,
    val onReject: (Dela.Suggestion) -> Unit,
    /** «Подскажи» раскрыто тапом в этой фазе (`Asks.Queue.openKey`); null — нет. */
    val asksOpen: String?,
    val onAsksOpen: (String) -> Unit,
)

// ---------------------------------------------------------------------------
// Раскладки
// ---------------------------------------------------------------------------

/** Значок набора по имени веба; нет — запасной. */
private fun kv(key: String, fallback: ImageVector): ImageVector = KvGlyphs.byKey(key) ?: fallback

/** «0:08» — сколько идёт дело: часы и минуты. */
private fun timer(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0L)
    return "${m / 60}:" + "%02d".format(m % 60)
}

// ---- Сейчас ----

/** Один вопрос «Подскажи»: что предложено, почему или откуда; «Да» (или «Ответить» в Делах) и «Нет». */
@Composable
private fun AskRow(c: TodayCards, sg: Dela.Suggestion, wide: Boolean) {
    val t = LocalPravkaType.current
    val txt = DelaViews.sugText(sg, c.data.snap, c.data.todayKey)
    val why = DelaViews.askWhy(sg, c.data.snap) ?: DelaViews.heldLine(sg, null)?.takeIf { DelaViews.choiceOnly(sg) }
    val from = sg.batchTitle.ifBlank { DelaViews.mark(sg.source).word }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(txt.title, style = t.body.copy(fontSize = if (wide) 14.sp else 14.5.sp), color = Kv.text, maxLines = if (wide) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(
                why ?: listOf(DelaViews.askYes(sg.kind).lowercase().let { if (sg.kind == "create") "новое дело" else it }, txt.hint, from)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = t.meta,
                color = if (why != null) Kv.warn else Kv.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        // Вопрос со своими ответами или выбором человека (аудит №1, №25): «Да» сервер не примет — ответ в Делах,
        // где варианты кнопками. «Нет» — вариантом «нет» вопроса («Никто» и т. п.), у остального — лист причины.
        // У вопроса о человеке — всегда лист: «нет» там — навсегда (служба больше не спросит), а маленький «✕»
        // легко задеть, и ушедший ответ из приложения не вернуть (проверка 15.10.2026).
        if (DelaViews.plainYes(sg, c.data.snap)) {
            SmallKey(kv("check", Glyphs.Check), if (wide) "Да" else null, "Да: ${txt.title}", { c.act.accept(sg) }, soft = true)
        } else {
            SmallKey(kv("arrow-right", Glyphs.Forward), "Ответить", "Ответить в Делах: ${txt.title}", {
                if (sg.batchRef.isNotBlank()) c.nav.delaBatch(sg.batchRef) else c.nav.mode(Tab.TODOIST)
            }, soft = true)
        }
        val no = DelaViews.rejectOption(sg, c.data.snap)
        SmallKey(kv("x", Glyphs.Close), null, "Нет: ${txt.title}", {
            when {
                sg.kind == "person" -> c.onReject(sg)
                no != null -> c.act.answer(sg, no)
                else -> c.onReject(sg)
            }
        })
    }
}

/**
 * «Сейчас на завтра: предложено 7 · отмечено 2 из 5 · Открыть» — одна строка в «Новом» (docs/dela-phone-27.md):
 * тап — пачка кандидатов `plan:<день>` во вкладке Дел, там «☆ На завтра · ✓ Уже сделал · Не надо» у каждого.
 */
@Composable
private fun PlanRow(c: TodayCards, plan: DelaViews.Plan) {
    val t = LocalPravkaType.current
    val open = { c.nav.delaBatch(plan.batch) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(onClickLabel = "открыть кандидатов", onClick = open)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.Bolt, null, tint = Kv.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(ru.zf.pravka.core.HeadLine.wrap(DelaViews.planLine(plan)), style = t.meta, color = Kv.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        SmallKey(kv("arrow-right", Glyphs.Forward), "Открыть", "Открыть «Сейчас ${plan.label}»", open, soft = true)
    }
}

// ---- Сегодня (разложенный) ----
