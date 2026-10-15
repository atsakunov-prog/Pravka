package ru.zf.pravka.trigger

import kotlinx.coroutines.launch
import ru.zf.pravka.core.Capture
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// Захват в службе: «Д» и «З» — одна дверь в Дела (шаг 0 петли, 15.10.2026; задание — docs/dela-phone-22c.md, п. 2).
// Владелец: «одна точка входа — строка „сказать“ и кнопка „Д“… Саша не выбирает, куда класть; система раскладывает и за
// секунду подтверждает одной строкой, куда легло и когда вернётся; не угадала — поправка одним касанием». Фраза — на
// сервер (`data/CaptureClient.kt`), строка и кнопки — его; пилюля их только показывает: первая кнопка — в самой строке,
// остальные — в карточке по тапу. Расширения PravkaAccessibilityService, как остальные режимные куски.

/**
 * Последний захват кнопкой — для «Отменить последнее» в меню «Д» (10 минут, как раньше у Разноски). В памяти службы:
 * переживать перезапуск ему незачем — дальше отмена живёт в самой записи.
 */
internal object LastCapture {
    @Volatile var result: Capture.Result? = null
        private set
    @Volatile var at: Long = 0L
        private set

    fun set(r: Capture.Result) {
        if (r.items.isEmpty()) return
        result = r
        at = System.currentTimeMillis()
    }

    fun fresh(now: Long = System.currentTimeMillis()): Capture.Result? =
        result?.takeIf { now - at <= Capture.UNDO_WINDOW_MS && it.items.any { x -> x.bucket != "cancelled" } }

    fun clear() {
        result = null
        at = 0L
    }
}

/** Итог захвата держится дольше обычного: строку «куда легло» надо прочитать и, может, поправить. */
private const val CAPTURE_HOLD_MS = 12_000L

/** Пока модель сервера раскладывает — «Записал — раскладываю», до итога (тот встанет на её место). */
private const val CAPTURE_PENDING_HOLD_MS = 30_000L

/** Куда показывать: пилюля «Д» или «З» — у них одна пилюля, разные кнопки. */
internal enum class CaptureFace { D, Z }

private fun PravkaAccessibilityService.showOn(
    face: CaptureFace,
    summary: String,
    ok: Boolean = true,
    rows: List<DictationPill.ResultRow> = emptyList(),
    actions: List<DictationPill.ResultAction> = emptyList(),
    onOpen: (() -> Unit)? = null,
    holdMs: Long = ru.zf.pravka.core.PillLook.RESULT_HOLD_MS,
) {
    when (face) {
        CaptureFace.D -> rButton?.showResult(summary, rows = rows, onOpen = onOpen, ok = ok, holdMs = holdMs, actions = actions)
        CaptureFace.Z -> zButton?.showResult(summary, rows = rows, onOpen = onOpen, ok = ok, holdMs = holdMs, actions = actions)
    }
}

private fun PravkaAccessibilityService.setBusyOn(face: CaptureFace, busy: Boolean) {
    when (face) {
        CaptureFace.D -> rButton?.setBusy(busy)
        CaptureFace.Z -> zButton?.setBusy(busy)
    }
}

/** «Сегодня» — там напоминания и «Вернулось» в своё время. */
internal fun PravkaAccessibilityService.openTodayTab() {
    startActivity(
        android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
            .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_TODAY)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Куда открыть записанное: напоминание и «потом» видны на «Сегодня», остальное — в Делах. */
private fun PravkaAccessibilityService.openCaptured(r: Capture.Result) {
    val b = r.items.firstOrNull()?.bucket
    if (b == "remind" || b == "place" || b == "back" || b == "now" || b == "today") openTodayTab() else openTodoistTab()
}

/**
 * Фраза «Д» (голос или набор в пилюле) — одной дверью: сервер раскладывает сам. true — забрал захват (строка в
 * пилюле), false — сервер без модели захвата, а правило не узнало: решает прежняя Разноска телефона.
 */
internal suspend fun PravkaAccessibilityService.captureFromD(text: String): Boolean {
    val snap = app.delaStore.view.value
    val (prepared, hint) = app.capturePrepare(text)
    val ref = Capture.newRef()
    if (!snap.captureModelOn) {
        val r = app.capture.rule(prepared, ref, Capture.FROM_D, hint) ?: return false
        if (!r.done) return false
        setBusyOn(CaptureFace.D, false)
        Haptics.success(this)
        showCapture(CaptureFace.D, r)
        return true
    }
    val o = app.capture.say(prepared, ref, Capture.FROM_D, hint) { p ->
        showOn(CaptureFace.D, Capture.line(p), onOpen = { openTodoistTab() }, holdMs = CAPTURE_PENDING_HOLD_MS)
    }
    setBusyOn(CaptureFace.D, false)
    showOutcome(CaptureFace.D, o)
    return true
}

/** Итог отправки словами в пилюле: отказ — его словами, без связи — «записал, разложу», иначе — строка сервера. */
internal fun PravkaAccessibilityService.showOutcome(face: CaptureFace, o: ru.zf.pravka.data.CaptureClient.Outcome) {
    val r = o.result
    when {
        o.refused.isNotBlank() -> {
            Haptics.error(this)
            showOn(face, o.line, ok = false, onOpen = { openTodoistTab() }, holdMs = CAPTURE_HOLD_MS)
        }
        o.offline -> {
            Haptics.success(this)
            showOn(face, o.line, onOpen = { openTodoistTab() }, holdMs = CAPTURE_HOLD_MS)
        }
        r != null -> {
            if (r.status == Capture.Status.ERROR) Haptics.error(this) else Haptics.success(this)
            showCapture(face, r)
        }
        else -> showOn(face, "Дела не ответили — фраза ушла, итог придёт уведомлением", onOpen = { openTodoistTab() })
    }
}

/**
 * Строка сервера в пилюле: «Напомню сегодня в 12:00: отправить счёт в Контуре» и его кнопки поправки (первая — в
 * самой строке). Нажатие — `capture.move`, новая строка («Переложил: …») встаёт на то же место.
 */
internal fun PravkaAccessibilityService.showCapture(face: CaptureFace, r: Capture.Result) {
    LastCapture.set(r)
    val actions = r.lineChips.map { (i, chip) ->
        DictationPill.ResultAction(chip.label + if (chip.options.isNotEmpty()) " ▾" else "") { onCaptureChip(face, r, i, chip) }
    }
    val rows = Capture.rows(r).map { DictationPill.ResultRow(it, onEdit = { openCaptured(r) }) }
    showOn(
        face, Capture.line(r), ok = r.status != Capture.Status.ERROR, rows = rows, actions = actions,
        onOpen = { openCaptured(r) }, holdMs = if (actions.isEmpty()) ru.zf.pravka.core.PillLook.RESULT_HOLD_MS else CAPTURE_HOLD_MS,
    )
}

/** Кнопка поправки: у «Время» и «Какой Иван» — сперва варианты (той же пилюлей), остальное — сразу на сервер. */
private fun PravkaAccessibilityService.onCaptureChip(face: CaptureFace, r: Capture.Result, i: Int, chip: Capture.Chip) {
    if (chip.options.isNotEmpty()) {
        showOn(
            face, Capture.optionsQuestion(chip),
            actions = chip.options.map { opt -> DictationPill.ResultAction(opt.label) { captureMove(face, r, i, chip, opt) } },
            holdMs = CAPTURE_HOLD_MS,
        )
        return
    }
    captureMove(face, r, i, chip, null)
}

private fun PravkaAccessibilityService.captureMove(face: CaptureFace, r: Capture.Result, i: Int, chip: Capture.Chip, opt: Capture.Option?) {
    setBusyOn(face, true)
    scope.launch {
        val m = runCatching { app.capture.move(r.ref, i, chip, opt) }.getOrNull()
        setBusyOn(face, false)
        if (m == null) {
            Haptics.error(this@captureMove)
            showOn(face, "Не вышло переложить — нет ответа Дел", ok = false)
            return@launch
        }
        if (m.offline) {
            showOn(face, "Переложу, как появится связь с Делами", onOpen = { openTodoistTab() })
            return@launch
        }
        showOutcome(face, m)
    }
}

/**
 * «Отменить последнее» в меню «Д» (10 минут): каждая запись последнего захвата — «Отменить» сервера (дело отменено,
 * идея убрана, заметка стёрта, сделанное вернуто). Дальше отмена — в самой записи.
 */
internal fun PravkaAccessibilityService.undoLastCapture() {
    val r = LastCapture.fresh() ?: run {
        Feedback.toast(this, "Отменять нечего — прошло больше 10 минут")
        return
    }
    rButton?.setBusy(true)
    scope.launch {
        val o = runCatching { app.capture.cancelAll(r) }.getOrNull()
        rButton?.setBusy(false)
        LastCapture.clear()
        when {
            o == null -> Feedback.toast(this@undoLastCapture, "Не вышло отменить — нет ответа Дел", long = true)
            o.refused.isNotBlank() -> Feedback.toast(this@undoLastCapture, "Сервер не принял отмену: ${o.refused}", long = true)
            o.offline -> Feedback.toast(this@undoLastCapture, "Отменю, как появится связь с Делами")
            else -> {
                Haptics.success(this@undoLastCapture)
                Feedback.toast(this@undoLastCapture, o.result?.let { Capture.line(it) }?.takeIf { it.isNotBlank() } ?: "↩︎ Отменено")
            }
        }
    }
}

/**
 * «З» — правило захвата параллельно развилке Сонета (как у «сказать»): «напомни, когда приеду домой…» в гарнитуру даёт
 * то же, что в «Д». Узнало — строка в пилюле «З» и короткое слово в ухо. null — не узнало (или сервер старый): решает
 * развилка «З».
 */
internal suspend fun PravkaAccessibilityService.captureRuleZ(text: String, ref: String, prepared: String, hint: String): Capture.Result? {
    val r = app.capture.rule(prepared.ifBlank { text }, ref, Capture.FROM_Z, hint) ?: return null
    return r.takeIf { it.done }
}

/** Дорога «дела» у «З» — одной дверью с «Д» (тот же ref, что у правила: дважды не ляжет). */
internal suspend fun PravkaAccessibilityService.captureFromZ(prepared: String, ref: String, hint: String, spoken: Boolean) {
    val o = app.capture.say(prepared, ref, Capture.FROM_Z, hint) { p ->
        showOn(CaptureFace.Z, Capture.line(p), onOpen = { openTodoistTab() }, holdMs = CAPTURE_PENDING_HOLD_MS)
    }
    setBusyOn(CaptureFace.Z, false)
    showOutcome(CaptureFace.Z, o)
    if (spoken) say(o.result?.let { Capture.spoken(it) } ?: if (o.offline) "записал, разложу позже" else "не записал")
}
