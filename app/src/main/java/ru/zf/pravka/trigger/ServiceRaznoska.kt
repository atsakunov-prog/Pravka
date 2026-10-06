package ru.zf.pravka.trigger
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.UndoStack
import ru.zf.pravka.data.Settings
import ru.zf.pravka.provider.GoogleSpeechSession
import ru.zf.pravka.target.AccessibilityTarget
import ru.zf.pravka.target.effectiveText
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// Разноска в службе: тап «Д», наговор -> дела, плашка с решением по каждому делу,
// отправка и отмена. Расширения PravkaAccessibilityService.

// ---- Разноска: тап -> наговор -> дела в Todoist или Дела (ни поля, ни ленты) ----

/** Кнопка «Д» (и вкладка «Дела»): старт наговора, второй тап — разбор. */
fun PravkaAccessibilityService.onRaznoskaTap() {
    if (expandButtons()) return
    if (isLockedIdle()) return
    if (rSession != null) { stopRaznoskaLive(); return }
    if (rWhisperRecording && DictationService.recording) {
        rButton?.setBusy(true)
        stopDictation()  // -> onRecordingSaved, routed by the flag
        return
    }
    // Один микрофон на все три кнопки: чужую запись эта не перехватывает.
    if (googleSession != null || zSession != null || zWhisperRecording ||
        eSession != null || eWhisperRecording || mSession != null || mWhisperRecording ||
        DictationService.recording
    ) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.r_busy))
        return
    }
    if (!hasMicPermission()) {
        micRequestForRaznoska = true
        requestMicPermission()
        return
    }
    startRaznoskaCapture()
}

/**
 * Причина отказа от предложения голосом (вкладка «Дела», «Новое»): тот же
 * движок и та же пилюля, что у «Д», но текст отдаётся [onText], а не в
 * разбор. false — не стартовало (микрофон занят, нет разрешения), причина
 * уже сказана тостом.
 */
fun PravkaAccessibilityService.listenForDelaReason(onText: (String) -> Unit): Boolean {
    if (rSession != null || rWhisperRecording || googleSession != null || zSession != null || zWhisperRecording ||
        eSession != null || eWhisperRecording || mSession != null || mWhisperRecording || DictationService.recording
    ) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.r_busy))
        return false
    }
    if (!hasMicPermission()) {
        requestMicPermission()
        return false
    }
    rTabSink = onText
    startRaznoskaCapture()
    if (rSession == null && !rWhisperRecording) {
        rTabSink = null
        return false
    }
    return true
}

/**
 * Вкладка сама остановила причину: «готово» — текст придёт, «отмена» — нет.
 * false — останавливать было нечего (запись уже кончилась сама или упала).
 */
fun PravkaAccessibilityService.finishDelaReason(keep: Boolean): Boolean {
    if (rSession == null && !(rWhisperRecording && DictationService.recording)) {
        rTabSink = null
        return false
    }
    if (!keep) {
        cancelRaznoskaTake()
        return true
    }
    if (rSession != null) stopRaznoskaLive() else {
        rButton?.setBusy(true)
        stopDictation()
    }
    return true
}

/** Приглашение говорить в бегущей строке «Д» — одно на оба движка. */
/** Надпись в пилюле «Д», пока слов нет. Тап посередине — по-прежнему набор. */
internal fun PravkaAccessibilityService.raznoskaTickerPrompt(): String = listenHint()

internal fun PravkaAccessibilityService.startRaznoskaCapture() {
    rButton?.hideInput()
    // Новая запись поверх ждущей плашки — это не «нет»: отмеченное уезжает.
    rButton?.settlePlate()
    rDiscard = false
    if (cachedEngine.startsWith("whisper")) {
        rWhisperRecording = true
        rButton?.setRecording(true)
        // У Whisper живых слов нет, но плашка нужна: это ещё и цель тапа
        // «набрать текстом».
        rButton?.showTicker()
        rButton?.hintTicker(raznoskaTickerPrompt())
        rButton?.showCancelBubble { cancelRaznoskaTake() }
        Haptics.start(this)
        startDictation()
    } else {
        startRaznoskaGoogle()
    }
    // Пока владелец говорит: греем сокет к API и подтягиваем каталог
    // проектов, чтобы к моменту «стоп» всё было под рукой.
    app.warmClaudeConnection()
    scope.launch { runCatching { app.raznoskaEngine.warmCatalog() } }
}

// Имена проектов и меток (Todoist или справочник Дел с алиасами и людьми):
// распознаватель должен слышать «Стеллар» и «Мармакс», а не «стеллаж» и «Марк Макс».
internal fun PravkaAccessibilityService.raznBiasing(): List<String> = runCatching {
    if (app.delaServer.value) {
        val s = app.delaStore.view.value
        (s.liveProjects().flatMap { listOf(it.name) + it.aliases } +
            s.livePeople().flatMap { listOf(it.label, it.name) + it.aliases } + s.labels)
            .filter { it.length in 3..40 }.distinct().take(400)
    } else {
        app.todoistStore.projectsFlow.value.map { it.name } + app.todoistStore.labelsFlow.value
    }
}.getOrDefault(emptyList())

internal fun PravkaAccessibilityService.startRaznoskaGoogle() {
    if (rSession != null) return
    if (!GoogleSpeechSession.isAvailable(this)) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.google_unavailable))
        return
    }
    val session = GoogleSpeechSession(
        this,
        biasing = (cachedBiasing + zClientsCached + raznBiasing()).distinct(),
        formatting = cachedFormatting,
        segmentedSession = cachedSegmented,
        network = cachedNetwork,
    )
    rSession = session
    speechReady = false
    session.start(
        onReady = {
            // Движок услышал — только теперь приглашение говорить правда.
            speechReady = true
            rButton?.hintTicker(raznoskaTickerPrompt())
            Haptics.success(this)
        },
        onPartial = { live -> rButton?.updateTicker(live) },
        // Наговор длиннее засечки, но короче диктовки главы: черновик на
        // диск не пишем, повторить его дешевле, чем чинить.
        onCheckpoint = { },
        onDone = { text -> onRaznoskaLiveDone(text) },
        onError = { msg -> onRaznoskaLiveError(msg) },
        onLog = { line -> app.eventLog.add("разноска: $line") },
    )
    rButton?.setRecording(true)
    rButton?.showTicker()
    // Пока движок глух, строка говорит об этом, а не зовёт говорить в пустоту.
    if (!speechReady) rButton?.hintTicker(waitHint())
    rButton?.showCancelBubble { cancelRaznoskaTake() }
    Haptics.start(this)
    runCatching { startMicHold() }
}

/** Серая «отмена» у «Д»: наговор выбрасывается, ни одного дела в Todoist не уйдёт. */
internal fun PravkaAccessibilityService.cancelRaznoskaTake() {
    when {
        rSession != null -> {
            rDiscard = true
            rSession?.stopNow = true
            app.eventLog.add("разноска: отмена наговора")
            stopRaznoskaLive()
        }
        rWhisperRecording && DictationService.recording -> {
            rDiscard = true
            rButton?.setBusy(true)
            app.eventLog.add("разноска: отмена наговора")
            stopDictation()
        }
    }
}

internal fun PravkaAccessibilityService.stopRaznoskaLive() {
    val session = rSession ?: return
    rButton?.setRecording(false)
    rButton?.setBusy(true)
    session.stop()  // -> onRaznoskaLiveDone
}

/** Тап по живой плашке: микрофон замолчал, дальше набираем текстом. */
internal fun PravkaAccessibilityService.onRaznoskaTickerTap() {
    when {
        rSession != null -> {
            rTypeInstead = true
            rSession?.stopNow = true
            stopRaznoskaLive()
        }
        rWhisperRecording && DictationService.recording -> {
            rTypeInstead = true
            rButton?.setBusy(true)
            stopDictation()
        }
    }
}

internal fun PravkaAccessibilityService.openRaznoskaTypeIn(prefill: String) {
    rButton?.showInput(prefill = prefill, hint = "Дела текстом") { typed ->
        val text = typed.trim()
        if (text.isNotEmpty()) onRaznoskaText(text)
    }
}

internal fun PravkaAccessibilityService.onRaznoskaLiveDone(text: String) {
    rSession = null
    runCatching { stopMicHold() }
    runCatching { rButton?.hideTicker() }
    runCatching { rButton?.hideCancelBubble() }
    rButton?.setRecording(false)
    if (rDiscard) {
        rDiscard = false
        rTypeInstead = false
        rTabSink = null
        rButton?.setBusy(false)
        app.eventLog.add("разноска: наговор отменён (${text.length} зн.)")
        Feedback.toast(this, "Отменено")
        return
    }
    if (rTypeInstead) {
        rTypeInstead = false
        rButton?.setBusy(false)
        openRaznoskaTypeIn(text.trim())
        return
    }
    onRaznoskaText(text)
}

internal fun PravkaAccessibilityService.onRaznoskaLiveError(msg: String) {
    rSession = null
    rDiscard = false
    rTabSink = null
    runCatching { stopMicHold() }
    rButton?.hideTicker()
    rButton?.hideCancelBubble()
    rButton?.setRecording(false)
    rButton?.setBusy(false)
    Haptics.error(this)
    Feedback.toast(this, msg)
}

/**
 * Текст наговора в руках: Опус разбирает, и дальше — по источнику.
 *
 * Голос и набор в пилюле ([review] = false): дела сразу уходят в Todoist, а
 * пилюля показывает, что записано, со «Отменить» (владелец, 26.09.2026:
 * «он просто показывает мне, что он записал… сама пропадает через секунд
 * 5»). Так уже жило «запиши дело» с «З»: сказанное вслух — решение.
 *
 * Чужой текст ([review] = true — дайджест, расшифровка встречи, выделение):
 * прежняя плашка с отметками и «ОК». Там дел бывает двадцать, и не все
 * твои — отправлять их не глядя значило бы засорять Todoist.
 */
internal fun PravkaAccessibilityService.onRaznoskaText(raw: String, review: Boolean = false) {
    val text = raw.trim()
    // Наговор заказала вкладка «Дела» (причина отказа) — текст ей, не в разбор.
    val sink = rTabSink
    if (sink != null) {
        rTabSink = null
        rButton?.setBusy(false)
        if (text.isNotEmpty()) sink(text)
        return
    }
    if (text.isBlank()) {
        rButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.dictation_empty))
        return
    }
    if (!scope.isActive) return
    rButton?.setBusy(true)
    scope.launch {
        val result = runCatching { app.raznoskaEngine.split(text) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.eventLog.add("разноска: split бросил ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(e)
            }
        rButton?.setBusy(false)
        val draft = result.getOrElse { e ->
            Haptics.error(this@onRaznoskaText)
            Feedback.toast(
                this@onRaznoskaText,
                e.message ?: getString(R.string.r_split_failed),
                long = true,
            )
            return@launch
        }
        Haptics.success(this@onRaznoskaText)
        if (draft.tasks.isEmpty() && draft.noteItems.isNotEmpty() && app.delaOnServer()) {
            // Дел нет, а заметки есть: на сервере Дел им место — хронология,
            // туда они и уходят, без плашки (сказанное вслух — решение).
            val sent = runCatching { app.raznoskaEngine.send(draft.id) }.getOrNull()
            rButton?.showResult(
                if (sent != null && sent.queued == 0 && app.delaStore.queued.value.isEmpty()) "Дел нет — заметки ушли в хронологию Дел"
                else "Дел нет — заметки в очереди Дел, уйдут сами",
                ok = true,
                onOpen = { openTodoistTab() },
            )
            return@launch
        }
        if (draft.tasks.isEmpty()) {
            // Дел не нашлось - наговор всё равно записан: заметки видно
            // во вкладке «Дела», ничего не пропало.
            rButton?.showResult(
                if (draft.notes.isBlank()) "Дел в наговоре не нашлось"
                else "Дел нет — записал в заметки «Дел»",
                ok = draft.notes.isNotBlank(),
                onOpen = { openTodoistTab() },
            )
            return@launch
        }
        if (review) showRaznoskaPlate(draft.id) else sendRaznoskaNow(draft.id)
    }
}

/**
 * Сказанное — в Todoist сразу, итог — в пилюле: сколько ушло, по тапу —
 * список с карандашами (правка — во вкладке «Дела»), «Отменить» — всё
 * только что созданное уходит из Todoist, а разбор возвращается на плашку.
 */
internal fun PravkaAccessibilityService.sendRaznoskaNow(draftId: Long) {
    val draft = app.raznoskaStore.byId(draftId) ?: return
    val tasks = draft.live.filter { !it.sent }
    if (tasks.isEmpty()) return
    rButton?.setBusy(true)
    scope.launch {
        val sent = runCatching { app.raznoskaEngine.send(draftId) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                ru.zf.pravka.core.RaznoskaEngine.SendOutcome(0, tasks.size, e.message ?: "не отправилось")
            }
        rButton?.setBusy(false)
        val rows = tasks.map { task ->
            DictationPill.ResultRow(
                title = task.content,
                meta = raznMeta(task),
                warn = if (task.duplicateOf.isBlank()) "" else "похоже: " + task.duplicateOf,
                onEdit = { openTodoistTab() },
            )
        }
        when {
            sent.ok -> {
                Haptics.success(this@sendRaznoskaNow)
                rButton?.showResult(
                    // Очередь Дел без связи — правда словами: дела уйдут сами.
                    summary = (if (sent.queued > 0) "В очереди Дел (нет связи): " else "В ${sent.to}: ") + raznCount(sent.created) +
                        (if (tasks.size == 1) " — " + tasks[0].content else ""),
                    rows = rows,
                    action = DictationPill.ResultAction("Отменить") { undoRaznoska() },
                    onOpen = { openTodoistTab() },
                )
            }
            sent.created > 0 -> {
                Haptics.error(this@sendRaznoskaNow)
                rButton?.showResult(
                    "Записал ${sent.created} из ${sent.created + sent.failed}: ${sent.error} — остальные ждут во вкладке «Дела»",
                    ok = false,
                    rows = rows,
                    onOpen = { openTodoistTab() },
                )
            }
            else -> {
                Haptics.error(this@sendRaznoskaNow)
                rButton?.showResult(
                    "Дела не ушли (${sent.error.ifBlank { "без причины" }}) — ждут во вкладке «Дела»",
                    ok = false,
                    onOpen = { openTodoistTab() },
                )
            }
        }
    }
}

/** Проект, метки, срок и приоритет дела — строкой под названием. */
internal fun PravkaAccessibilityService.raznMeta(task: ru.zf.pravka.core.ParsedTask): String {
    val meta = mutableListOf<String>()
    if (task.projectName.isNotBlank()) meta.add("#" + task.projectName)
    // Дела: у кого мяч — словами, а не меткой.
    when (task.ball) {
        ru.zf.pravka.core.Dela.WAITING -> meta.add("жду: " + task.personName.ifBlank { "?" })
        ru.zf.pravka.core.Dela.AGENDA -> meta.add("при встрече: " + task.personName.ifBlank { "?" })
        else -> if (task.personName.isNotBlank()) meta.add("для: " + task.personName)
    }
    if (task.labels.isNotEmpty()) meta.add(task.labels.joinToString(" ") { "@" + it })
    if (task.repeat.isNotBlank()) meta.add(task.repeat)
    else if (task.due.isNotBlank()) meta.add(raznDate(task.due) + if (task.dueTime.isNotBlank()) " " + task.dueTime else "")
    // Напоминание в Telegram — видно сразу в итоге: «записал» должно быть правдой.
    // Сервер, который напоминаний не знает, получит их строкой в заметках — так и сказано.
    val zone = java.time.ZoneId.systemDefault()
    val later = if (app.delaStore.view.value.remindOn) "" else " — в заметке, сервер Дел пока не напоминает"
    ru.zf.pravka.core.DelaRemind.local(task.remindAt, zone)?.let { at ->
        meta.add("⏰ " + ru.zf.pravka.core.DelaRemind.whenWords(at, java.time.LocalDate.now(zone)) + later)
    } ?: run { if (task.remindPlace.isNotBlank()) meta.add("⏰ приеду: " + task.remindPlace + later) }
    if (task.priority != ru.zf.pravka.core.ParsedTask.P4) meta.add(task.priorityLabel)
    if (task.projectName.isBlank()) meta.add("проект не выбран")
    return meta.joinToString(" · ")
}

/**
 * Плашка разбора: кружок у дела - отметка (по умолчанию отмечено всё),
 * «✎» - правка формулировки на месте, тап по строке - дело целиком в
 * «Делах», «ОК» - добавить отмеченные. Ничего не уезжает до «ОК», но
 * молчание и есть «ОК» (владелец, 26.09.2026: «не нажал ничего =
 * подтвердил»): отсчёт внизу плашки, «нет» — это «✕».
 */
internal fun PravkaAccessibilityService.showRaznoskaPlate(draftId: Long, autoConfirm: Boolean = true) {
    val draft = app.raznoskaStore.byId(draftId) ?: return
    val tasks = draft.live
    val waiting = tasks.count { !it.sent }
    if (waiting == 0) return
    val rows = tasks.map { task ->
        RaznoskaButtonController.PlateRow(
            id = task.id,
            title = task.content,
            meta = raznMeta(task),
            warn = if (task.duplicateOf.isBlank()) "" else "⚠ похоже: " + task.duplicateOf,
            sent = task.sent,
        )
    }
    rButton?.showTasks(
        header = "РАЗНОСКА · " + raznCount(waiting),
        rows = rows,
        onEdit = { id -> editRaznoskaTask(draftId, id) },
        onOpen = { openTodoistTab() },
        onSend = { ids, quiet -> sendRaznoskaTasks(draftId, ids, quiet) },
        key = "razn:$draftId",
        autoConfirm = autoConfirm,
    )
}

/**
 * «ОК» на плашке: одной отправкой уезжают все отмеченные дела. [quiet] —
 * плашку сняли снаружи (новая запись, складывание): без спиннера на кнопке.
 */
internal fun PravkaAccessibilityService.sendRaznoskaTasks(draftId: Long, taskIds: List<Long>, quiet: Boolean = false) {
    if (taskIds.isEmpty()) return
    if (!quiet) rButton?.setBusy(true)
    scope.launch {
        val outcome = runCatching { app.raznoskaEngine.sendOnly(draftId, taskIds) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                ru.zf.pravka.core.RaznoskaEngine.SendOutcome(
                    0, taskIds.size, e.message ?: "не отправилось",
                )
            }
        if (!quiet) rButton?.setBusy(false)
        reportRaznoskaSend(outcome.created, outcome.failed, outcome.error, outcome.to, outcome.queued)
    }
}

/** ✎ на плашке: правка формулировки на месте. Пусто = вычеркнуть дело. */
internal fun PravkaAccessibilityService.editRaznoskaTask(draftId: Long, taskId: Long) {
    val task = app.raznoskaStore.byId(draftId)?.tasks?.firstOrNull { it.id == taskId } ?: return
    // Плашку на время ввода снимает сама кнопка: «да» при этом ждёт ответа.
    rButton?.showInput(
        prefill = task.content,
        hint = "Кто: что сделать",
        // Отмена не должна съедать разбор - плашка возвращается как была.
        onCancel = { showRaznoskaPlate(draftId) },
    ) { typed ->
        scope.launch {
            app.raznoskaEngine.editText(draftId, taskId, typed)
            showRaznoskaPlate(draftId)
        }
    }
}

/** «3 дела» / «1 дело» — плашка и тосты говорят по-русски. */
internal fun PravkaAccessibilityService.raznCount(n: Int): String {
    val word = when {
        n % 10 == 1 && n % 100 != 11 -> "дело"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "дела"
        else -> "дел"
    }
    return "$n $word"
}

/** «2026-08-25» → «25 авг». */
internal fun PravkaAccessibilityService.raznDate(iso: String): String = runCatching {
    val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)
    if (parsed == null) iso
    else java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(parsed)
}.getOrDefault(iso)

internal fun PravkaAccessibilityService.openTodoistTab() {
    startActivity(
        android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
            .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_TODOIST)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Отправка в Todoist. Уже созданные дела пропускаются внутри движка, так
 * что повторное «ОК» после потери сети ничего не удваивает.
 */
internal fun PravkaAccessibilityService.sendRaznoska(draftIds: List<Long>) {
    if (draftIds.isEmpty()) return
    rButton?.setBusy(true)
    scope.launch {
        var created = 0
        var failed = 0
        var queued = 0
        var to = "Todoist"
        var error = ""
        for (id in draftIds) {
            val outcome = runCatching { app.raznoskaEngine.send(id) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ru.zf.pravka.core.RaznoskaEngine.SendOutcome(
                        0, 1, e.message ?: "не отправилось",
                    )
                }
            created += outcome.created
            failed += outcome.failed
            queued += outcome.queued
            to = outcome.to
            if (error.isBlank() && outcome.error.isNotBlank()) error = outcome.error
        }
        rButton?.setBusy(false)
        reportRaznoskaSend(created, failed, error, to, queued)
    }
}

/**
 * Итог отправки одним местом: успех - записка с ручкой отмены, частичный
 * успех и провал - тост, из которого понятно, где остались дела.
 */
internal fun PravkaAccessibilityService.reportRaznoskaSend(
    created: Int,
    failed: Int,
    error: String,
    to: String = "Todoist",
    queued: Int = 0,
) {
    when {
        failed == 0 && created > 0 && queued > 0 -> {
            Haptics.success(this)
            Feedback.toast(this, "✓ " + raznCount(created) + " в очереди Дел — нет связи, уйдут сами", long = true)
        }
        failed == 0 && created > 0 -> {
            Haptics.success(this)
            // Тост, а не записка. Владелец: «после того, как дело записано,
            // оно почему-то висит рядом баблом — то бесполезно и не нужно».
            // Он дело только что одобрил сам, подтверждать ему нечего.
            // Отмена не потеряна: она живёт во вкладке «Дела», где видно,
            // что именно откатываешь.
            Feedback.toast(this, "✓ " + raznCount(created) + " в " + to)
        }
        created > 0 -> {
            Haptics.error(this)
            Feedback.toast(
                this,
                "Отправлено $created, осталось $failed: $error",
                long = true,
            )
        }
        else -> {
            Haptics.error(this)
            Feedback.toast(
                this,
                "Не отправилось ($error). Дела ждут во вкладке «Дела».",
                long = true,
            )
        }
    }
}

internal fun PravkaAccessibilityService.resplitRaznoska(draftId: Long) {
    rButton?.setBusy(true)
    scope.launch {
        val result = runCatching { app.raznoskaEngine.resplit(draftId) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        rButton?.setBusy(false)
        result.onSuccess { draft ->
            Haptics.success(this@resplitRaznoska)
            if (draft.tasks.isEmpty()) {
                Feedback.toast(this@resplitRaznoska, "Дел так и не нашлось")
            } else {
                showRaznoskaPlate(draft.id)
            }
        }.onFailure { e ->
            Haptics.error(this@resplitRaznoska)
            Feedback.toast(
                this@resplitRaznoska,
                e.message ?: "Не вышло разобрать заново",
                long = true,
            )
        }
    }
}

/**
 * Разноска чужого текста: дайджест из чата, расшифровка встречи, письмо.
 * Тот же разбор, только наговор пришёл не голосом. Длинную стену режем -
 * иначе один разбор стоил бы как день работы.
 */
fun PravkaAccessibilityService.raznoskaFromText(raw: String) {
    val limit = 60_000
    val text = raw.trim().take(limit)
    if (text.isBlank()) {
        Haptics.error(this)
        Feedback.toast(this, "Нечего разбирать — текста нет")
        return
    }
    if (raw.trim().length > limit) {
        Feedback.toast(this, "Текст длинный: взял первые ${limit / 1000} тыс. знаков")
    }
    onRaznoskaText(text, review = true)
}

/** «Разобрать текст» в меню «Д»: выделение → поле → буфер обмена. */
internal fun PravkaAccessibilityService.raznoskaFromSelection() {
    scope.launch {
        val text = runCatching { assistContent() }.getOrDefault("")
        raznoskaFromText(text)
    }
}

/** «↩︎ Отменить отправку»: только что созданные дела уходят из Todoist. */
internal fun PravkaAccessibilityService.undoRaznoska() {
    rButton?.setBusy(true)
    scope.launch {
        val outcome = runCatching { app.raznoskaEngine.undoLast() }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                ru.zf.pravka.core.RaznoskaEngine.UndoOutcome(0, 1, 0L)
            }
        rButton?.setBusy(false)
        when {
            outcome.deleted == 0 -> {
                Haptics.error(this@undoRaznoska)
                Feedback.toast(this@undoRaznoska, "Отменять нечего")
            }
            outcome.failed == 0 -> {
                Haptics.success(this@undoRaznoska)
                Feedback.toast(
                    this@undoRaznoska,
                    "↩︎ " + raznCount(outcome.deleted) + " отменено",
                )
                // Дела снова ждут - показываем разбор, чтобы поправить и
                // отправить заново. Без «сам»: после «Отменить» тишина
                // значит «передумал», а не «отправь обратно».
                if (outcome.draftId != 0L) showRaznoskaPlate(outcome.draftId, autoConfirm = false)
            }
            else -> {
                Haptics.error(this@undoRaznoska)
                Feedback.toast(
                    this@undoRaznoska,
                    "Убрал ${outcome.deleted}, ${outcome.failed} не поддались — глянь во вкладке «Дела»",
                    long = true,
                )
            }
        }
    }
}

/**
 * Меню кнопки «Д». Владелец: «убери целиком все меню, ничего не
 * использую… там должно быть: открыть Дело, закрыть».
 *
 * Всё, что здесь было — отправить, показать разбор, разобрать заново,
 * отменить отправку, разобрать текст, набрать текстом, — живёт на плашке
 * разбора и во вкладке «Дела». Дублировать это в меню незачем: он
 * подтверждает дела на плашке, а меню держит только чтобы попасть внутрь.
 */
internal fun PravkaAccessibilityService.showRaznoskaMenu() {
    if (expandButtons()) return
    scope.launch {
        runCatching { app.raznoskaStore.load() }
        val waiting = app.raznoskaStore.pending().sumOf { it.pendingCount }
        // Первой строкой — что сейчас: сколько дел разобрано и ждёт
        // отправки. Тап по ней открывает плашку, где их и подтверждают.
        val head = if (waiting > 0) "✓ Ждут отправки: " + raznCount(waiting)
        else "— неотправленных дел нет"
        rButton?.showMenu(
            listOf(
                RaznoskaButtonController.MenuItem(head) {
                    val newest = app.raznoskaStore.pending().firstOrNull()
                    if (newest != null) showRaznoskaPlate(newest.id) else openTodoistTab()
                },
                RaznoskaButtonController.MenuItem("Открыть Дело") { openTodoistTab() },
                RaznoskaButtonController.MenuItem("Настройки") { openSettingsTab("DELA") },
                RaznoskaButtonController.MenuItem("Закрыть") { rButton?.hideMenu() },
            )
        )
    }
}
