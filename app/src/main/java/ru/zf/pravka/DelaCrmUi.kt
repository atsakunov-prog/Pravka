package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaAsk
import ru.zf.pravka.core.DelaCrm
import ru.zf.pravka.data.DelaStore
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SummaryLine

// CRM на телефоне (05.10.2026, docs/dela-phone-2.md, этап 2): воронка,
// клиенты, связи, клиент, сделка, человек и хронология. Данные видов — с
// сервера (`/api/view/…`, логика общая с вебом), последний ответ каждого — на
// диске (`DelaStore.viewsFlow`): экран открывается без сети и обновляется.
// Правки — той же очередью с op_id, что у дел; своя неотправленная правка
// накладывается поверх ответа (`DelaCrm.overlay*`), чтобы касание было видно
// сразу. Деньги фирмы (гонорары, оплаты) — только кому открыты.

/** Состояние CRM-экранов, общее для их кусков и листов поверх вкладки. */
internal class CrmUiState {
    var staleOnly by mutableStateOf(false)
    var clientQuery by mutableStateOf("")
    var clientArchive by mutableStateOf(false)
    /** «Закрыть…» сделку: выбор итога и причина. */
    var closing by mutableStateOf<DelaCrm.Deal?>(null)
    /** «Счёт выставлен…» и «записать» у счёта: когда, кому и как — и какие виды обновить. */
    var invoicing by mutableStateOf<Pair<Dela.Payment, List<String>>?>(null)
    /** Долгое нажатие на проект Воронки — меню «Стадия» (перетаскивания на телефоне нет). */
    var stageFor by mutableStateOf<DelaCrm.Deal?>(null)
    /** Плашка человека в команде проекта: в листе «откуда он» — ещё «Ведёт проект». */
    var leadDeal by mutableStateOf("")
    /** Запись хронологии, раскрытая целиком, и хронология, показанная вся (`S.tlOpen`, `S.tlAll` веба). */
    var tlOpen by mutableStateOf("")
    var tlAll by mutableStateOf("")
    /** Фильтр «Поддержание отношений» у «Клиентов». */
    var clientRelations by mutableStateOf(false)
    /** Убрать запись хронологии — с подтверждением: строка останется в журнале. */
    var deleting by mutableStateOf<Pair<DelaCrm.Interaction, List<String>>?>(null)
    /** «Скрыть от команды» (аудит №9) — с подтверждением словами: вернуть нельзя. */
    var hiding by mutableStateOf<Pair<DelaCrm.Interaction, List<String>>?>(null)
    /** «Только тебе»: какой блок показывает свёрнутые «Мимо» и «Неверно» (ключ блока). */
    var notesOpen by mutableStateOf("")
    /** «сводка →» у записи из дайджеста Telegram — ref сводки, открытой листом (`DigestRefSheet`); пусто — закрыто. */
    var digestRef by mutableStateOf("")
    /** Тап по плашке человека — «откуда он» и должность (`personPop` веба). */
    var personFor by mutableStateOf<String?>(null)
    /** «+ человек»: к клиенту, в люди клиента или в команду сделки. */
    var addTo by mutableStateOf<AddTarget?>(null)
    /** Крестик у человека клиента — с подтверждением: карточка человека остаётся. */
    var leaving by mutableStateOf<Pair<Dela.Person, String>?>(null)
    /** Поиск в «Людях». */
    var peopleQuery by mutableStateOf("")
    /** «Переименовать» клиента — лист с новым именем (`prompt` веба); id проекта. */
    var renaming by mutableStateOf<String?>(null)
    /** Новое название проекта (сделки) — тап по названию на его странице (записка отладки 38); id сделки. */
    var renamingDeal by mutableStateOf<String?>(null)
    /** «+ дело» у проекта (записка отладки 37) — лист с названием нового дела; id сделки. */
    var newTaskInDeal by mutableStateOf<String?>(null)
    /** «+ проект» у клиента (16.10.2026 вечер, запись 27) — лист с названием «Клиент: тема»; id клиента. */
    var newDealFor by mutableStateOf<String?>(null)
}


/**
 * Куда «+ человек»: к клиенту ([clientId]: в его организацию) или в сделку
 * ([dealId] и поле `person_ids` / `team_ids`; нового человека в люди клиента —
 * сразу с организацией клиента [clientId]).
 */
internal data class AddTarget(
    val title: String,
    val exclude: Set<String>,
    val clientId: String = "",
    val dealId: String = "",
    val field: String = "",
    val ids: List<String> = emptyList(),
    /** Свои — кого показать до набора: команда ЗФ или люди этого клиента (`personSearchPop` веба). */
    val base: List<String> = emptyList(),
    /** В команду клиента: его живые проекты — один — в него, несколько — выбрать, в какой. */
    val teamDeals: List<String> = emptyList(),
    /** У проекта нет ведущего — первый в команде станет ведущим. */
    val leadEmpty: Boolean = false,
)


/** Всё, что нужно кускам CRM: копия, кто я, кэш видов, очередь, видны ли деньги, переходы. */
internal class DelaCrmContext(
    val app: PravkaApp,
    val snap: Dela.Snapshot,
    val me: String,
    val today: String,
    val views: Map<String, DelaStore.CachedView>,
    val ops: List<JSONObject>,
    val money: Boolean,
    val actions: DelaActions,
    val push: (DelaPage) -> Unit,
    val back: () -> Unit,
    val ui: CrmUiState = CrmUiState(),
    /** Клиенты с раскрытыми проектами — в ☰ и в «Клиентах»; помнится в `dela-favs.json`. */
    val clientOpen: Set<String> = emptySet(),
    /** Правка, которую можно вернуть: слова и операции «как было» — полоской «Вернуть» наверху вкладки. */
    val offerUndo: (said: String, back: List<JSONObject>, refresh: List<String>) -> Unit = { _, _, _ -> },
    /** Пилюли карточки: пролистать список к разделу по ключу (`sec:about`, `sec:tasks`…). */
    val scrollTo: (String) -> Unit = {},
    /** Новое дело с проектом и сделкой (`quickAdd` вкладки: «!отбить», «!сейчас» понимает) — «+ дело» у проекта. */
    val addTask: (title: String, projectId: String, dealId: String) -> Unit = { _, _, _ -> },
) {
    fun data(path: String): JSONObject? = views[path]?.data

    /** Раскрыть или свернуть проекты клиентов (один или «все»). */
    fun setClientOpen(ids: Collection<String>, open: Boolean) {
        app.appScope.launch { app.delaStore.setClientOpen(ids, open) }
    }

    /**
     * Правка с «Вернуть» (крестик, «откуда он», «+ человек»): в очередь и на
     * сервер, задетые виды — заново, и полоска со словами и «Вернуть», если
     * вернуть есть что; иначе — просто слова тостом.
     */
    fun runUndoable(u: DelaCrm.Undoable, refresh: List<String>) {
        run(u.ops, refresh)
        if (u.back.isNotEmpty()) offerUndo(u.said, u.back, refresh) else Feedback.toast(app, u.said)
    }

    /**
     * Правка CRM: в очередь (на диск сразу), на сервер — сейчас, и виды,
     * которых она касается, — заново: их считает сервер. Без сети правка
     * дождётся в очереди, а поверх кэша видна сразу.
     */
    fun run(ops: List<JSONObject>, refresh: List<String>, done: String = "") {
        if (ops.isEmpty()) return
        app.appScope.launch {
            app.delaStore.enqueue(ops)
            val sent = app.delaSync.pushNow(4_000L)
            // Тост — после ответа сервера и его словами (правило 6 карты): отказ («склеивает людей только владелец»)
            // или заметка ответа (««Ваня» не дописал — так зовут Иван Петров», аудит №1) — вместо «Имя дописано».
            // Только заметки своих операций: свежая чужая (фоновая отправка той же минуты) — не ответ на эту правку.
            // Сказанное тостом в плашке «Сервер ответил» не повторяется (`tell`, отладка 16.10.2026).
            val said = app.delaStore.tell(ops, errorsOnly = false)?.text
            if (done.isNotBlank() || said != null) Feedback.toast(app, said ?: if (sent) done else "$done — уйдёт, когда будет связь")
            if (sent) for (p in refresh.distinct()) app.delaSync.crmView(p)
        }
    }

    /** Следующее дело сделки — тем же правилом, что сервер, из своей копии (`DelaCrm.nextFor`). */
    fun nextFor(d: DelaCrm.Deal): DelaCrm.NextTask? = DelaCrm.nextFor(snap, d.id, d.projectId, d.solo, d.nextTask)

    /** Владелец Дел: только ему — склейка людей («Это тот же, что…», аудит №23; сервер остальным откажет). */
    val owner: Boolean get() = DelaCrm.canMerge(snap, me)

    /** Сервер знает закон приватности (флаг `privacy`): замки, «скрыть от команды», «Только тебе». */
    val privacy: Boolean get() = snap.privacyOn

    fun personName(id: String): String = snap.people[id]?.label.orEmpty()
}

// ---------------------------------------------------------------- люди плашками

private fun ago(at: Long): String {
    val mins = ((System.currentTimeMillis() - at) / 60_000L).toInt()
    return when {
        mins < 1 -> "только что"
        mins < 120 -> "$mins мин назад"
        else -> java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(at))
    }
}

// ---------------------------------------------------------------- воронка
