package ru.zf.pravka.trigger

import java.io.File
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.provider.GoogleSpeechSession
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// Деньги в службе: тап «₽», наговор → траты, плашка с отметками и «ОК».
// Расширения PravkaAccessibilityService — тот же путь, что у Разноски («Д»),
// и тот же контроллер кнопки: плашка-отметки, «✎» на месте, «ОК · N».
//
// До «ОК» ничего не идёт ни в итоги, ни в сверку, но разобранное уже на диске
// (`MoneyStore`): ушёл от плашки — черновики ждут во вкладке «Деньги».

/** Кнопка «₽»: старт наговора, второй тап — разбор. */
fun PravkaAccessibilityService.onMoneyTap() {
    if (expandButtons()) return
    if (isLockedIdle()) return
    if (mSession != null) { stopMoneyLive(); return }
    if (mWhisperRecording && DictationService.recording) {
        mButton?.setBusy(true)
        stopDictation()  // -> onRecordingSaved, уводит флаг
        return
    }
    // Один микрофон на все кнопки: чужую запись эта не перехватывает.
    if (googleSession != null || zSession != null || zWhisperRecording ||
        rSession != null || rWhisperRecording || eSession != null || eWhisperRecording ||
        DictationService.recording
    ) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.r_busy))
        return
    }
    if (!hasMicPermission()) {
        micRequestForMoney = true
        requestMicPermission()
        return
    }
    startMoneyCapture()
}

/** Приглашение говорить в бегущей строке «₽». */
internal fun PravkaAccessibilityService.moneyTickerPrompt(): String =
    if (mTabSink != null) "🎙 $mTabPrompt" else "🎙 наговори траты… (тап сюда — набрать текстом)"

/**
 * Наговор для вкладки «Деньги»: тот же движок и та же бегущая строка, что у
 * «₽», но текст отдаётся [onText], а не в разбор трат. Ход записи вкладка
 * видит через [MoneyTabVoice]. false — не стартовало (микрофон занят,
 * нет разрешения), причина уже сказана тостом.
 */
fun PravkaAccessibilityService.listenForMoneyTab(owner: String, prompt: String, onText: (String) -> Unit): Boolean {
    if (mSession != null || mWhisperRecording) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.r_busy))
        return false
    }
    if (googleSession != null || zSession != null || zWhisperRecording ||
        rSession != null || rWhisperRecording || eSession != null || eWhisperRecording ||
        DictationService.recording
    ) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.r_busy))
        return false
    }
    if (!hasMicPermission()) {
        requestMicPermission()
        return false
    }
    mTabSink = onText
    mTabPrompt = prompt
    MoneyTabVoice.live.value = MoneyTabVoice.Live(owner, "")
    startMoneyCapture()
    // Движок недоступен — startMoneyGoogle сказал тостом; заявка снимается.
    if (mSession == null && !mWhisperRecording) endMoneyTab()
    return mSession != null || mWhisperRecording
}

/** Вкладка сама остановила запись: «Готово» — текст придёт, «Отмена» — нет. */
fun PravkaAccessibilityService.finishMoneyTab(keep: Boolean) {
    if (!keep) cancelMoneyTake() else if (mSession != null) stopMoneyLive() else if (mWhisperRecording && DictationService.recording) {
        mButton?.setBusy(true)
        stopDictation()
    }
}

/** Заявка вкладки снята: запись кончилась так или иначе. */
internal fun PravkaAccessibilityService.endMoneyTab() {
    mTabSink = null
    mTabPrompt = ""
    MoneyTabVoice.live.value = null
}

internal fun PravkaAccessibilityService.startMoneyCapture() {
    mButton?.hideInput()
    mButton?.hidePlate()
    mDiscard = false
    if (cachedEngine.startsWith("whisper")) {
        mWhisperRecording = true
        mButton?.setRecording(true)
        mButton?.showTicker()
        mButton?.updateTicker(moneyTickerPrompt())
        mButton?.showCancelBubble { cancelMoneyTake() }
        Haptics.start(this)
        startDictation()
    } else {
        startMoneyGoogle()
    }
    // Пока владелец говорит — греем сокет к API и читаем журнал с диска.
    app.warmClaudeConnection()
    scope.launch { runCatching { app.moneyStore.load() } }
}

/**
 * Подсказки распознавателю: названия из справочника получателей. Имена
 * людей и мест («Даблби») должны слышаться как есть, а не созвучием.
 */
internal fun PravkaAccessibilityService.moneyBiasing(): List<String> = runCatching {
    app.moneyEngine.allRules()
        .flatMap { it.pattern.split('|') }
        .map { it.trim() }
        .filter { it.length in 3..30 && it.any { c -> c.isLetter() } }
        .distinct()
        .take(20)
}.getOrDefault(emptyList())

internal fun PravkaAccessibilityService.startMoneyGoogle() {
    if (mSession != null) return
    if (!GoogleSpeechSession.isAvailable(this)) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.google_unavailable))
        return
    }
    val session = GoogleSpeechSession(
        this,
        biasing = (cachedBiasing + moneyBiasing()).distinct(),
        formatting = cachedFormatting,
        segmentedSession = cachedSegmented,
        network = cachedNetwork,
    )
    mSession = session
    speechReady = false
    session.start(
        onReady = {
            speechReady = true
            mButton?.updateTicker(moneyTickerPrompt(), force = true)
            Haptics.success(this)
        },
        onPartial = { live ->
            mButton?.updateTicker(live)
            MoneyTabVoice.live.value?.let { MoneyTabVoice.live.value = it.copy(text = live) }
        },
        onCheckpoint = { },
        onDone = { text -> onMoneyLiveDone(text) },
        onError = { msg -> onMoneyLiveError(msg) },
        onLog = { line -> app.eventLog.add("деньги: $line") },
    )
    mButton?.setRecording(true)
    mButton?.showTicker()
    if (!speechReady) mButton?.updateTicker(PravkaAccessibilityService.HINT_WAIT)
    mButton?.showCancelBubble { cancelMoneyTake() }
    Haptics.start(this)
    runCatching { startMicHold() }
}

/** Серая «отмена» у «₽»: наговор выбрасывается. */
internal fun PravkaAccessibilityService.cancelMoneyTake() {
    when {
        mSession != null -> {
            mDiscard = true
            app.eventLog.add("деньги: отмена наговора")
            stopMoneyLive()
        }
        mWhisperRecording && DictationService.recording -> {
            mDiscard = true
            mButton?.setBusy(true)
            app.eventLog.add("деньги: отмена наговора")
            stopDictation()
        }
    }
}

internal fun PravkaAccessibilityService.stopMoneyLive() {
    val session = mSession ?: return
    mButton?.setRecording(false)
    mButton?.setBusy(true)
    session.stop()  // -> onMoneyLiveDone
}

/** Тап по живой плашке: микрофон замолчал, дальше набираем текстом. */
internal fun PravkaAccessibilityService.onMoneyTickerTap() {
    when {
        mSession != null -> {
            mTypeInstead = true
            stopMoneyLive()
        }
        mWhisperRecording && DictationService.recording -> {
            mTypeInstead = true
            mButton?.setBusy(true)
            stopDictation()
        }
    }
}

internal fun PravkaAccessibilityService.openMoneyTypeIn(prefill: String) {
    val hint = if (mTabSink != null) mTabPrompt else "Траты текстом: «кофе 380, такси 700»"
    mButton?.showInput(prefill = prefill, hint = hint) { typed ->
        val text = typed.trim()
        if (text.isNotEmpty()) onMoneyText(text)
    }
}

internal fun PravkaAccessibilityService.onMoneyLiveDone(text: String) {
    mSession = null
    runCatching { stopMicHold() }
    runCatching { mButton?.hideTicker() }
    runCatching { mButton?.hideCancelBubble() }
    mButton?.setRecording(false)
    if (mDiscard) {
        mDiscard = false
        mTypeInstead = false
        mButton?.setBusy(false)
        endMoneyTab()
        app.eventLog.add("деньги: наговор отменён (${text.length} зн.)")
        Feedback.toast(this, "Отменено")
        return
    }
    // У заказа вкладки поле для текста — во вкладке: тап по строке просто
    // заканчивает запись, и услышанное уходит туда.
    if (mTypeInstead && mTabSink == null) {
        mTypeInstead = false
        mButton?.setBusy(false)
        openMoneyTypeIn(text.trim())
        return
    }
    mTypeInstead = false
    onMoneyText(text)
}

internal fun PravkaAccessibilityService.onMoneyLiveError(msg: String) {
    mSession = null
    endMoneyTab()
    mDiscard = false
    runCatching { stopMicHold() }
    mButton?.hideTicker()
    mButton?.hideCancelBubble()
    mButton?.setRecording(false)
    mButton?.setBusy(false)
    Haptics.error(this)
    Feedback.toast(this, msg)
}

/** Whisper: файл записан — уводим его от поля и ленты, в разбор трат. */
internal fun PravkaAccessibilityService.onMoneyWhisperSaved(file: File?) {
    mWhisperRecording = false
    mButton?.setRecording(false)
    mButton?.hideCancelBubble()
    if (mDiscard) {
        mDiscard = false
        mTypeInstead = false
        file?.let { app.recordings.delete(it.name) }
        mButton?.hideTicker()
        mButton?.setBusy(false)
        endMoneyTab()
        app.eventLog.add("деньги: наговор отменён (whisper)")
        Feedback.toast(this, "Отменено")
        return
    }
    if (mTypeInstead && mTabSink == null) {
        mTypeInstead = false
        file?.let { app.recordings.delete(it.name) }
        mButton?.hideTicker()
        mButton?.setBusy(false)
        openMoneyTypeIn("")
        return
    }
    mTypeInstead = false
    mButton?.hideTicker()
    if (file == null) {
        mButton?.setBusy(false)
        endMoneyTab()
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.dictation_empty))
        return
    }
    mButton?.setBusy(true)
    scope.launch {
        app.transcribeDictation(file)
            .onSuccess { text ->
                app.recordings.delete(file.name)
                onMoneyText(text)
            }
            .onFailure { e ->
                mButton?.setBusy(false)
                endMoneyTab()
                // Аудио остаётся в «Записях», как у неудачной Правки.
                Haptics.error(this@onMoneyWhisperSaved)
                Feedback.toast(
                    this@onMoneyWhisperSaved,
                    getString(R.string.dictation_saved_for_retry, e.message ?: ""),
                )
            }
    }
}

/** Текст наговора в руках: Опус разбирает, плашка показывает траты. */
internal fun PravkaAccessibilityService.onMoneyText(raw: String) {
    val text = raw.trim()
    // Заказ вкладки: текст — ей, в разбор трат он не идёт.
    mTabSink?.let { sink ->
        mButton?.setBusy(false)
        endMoneyTab()
        if (text.isBlank()) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.dictation_empty))
        } else {
            Haptics.success(this)
            sink(text)
        }
        return
    }
    if (text.isBlank()) {
        mButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.dictation_empty))
        return
    }
    if (!scope.isActive) return
    mButton?.setBusy(true)
    scope.launch {
        val result = runCatching { app.moneyEngine.dictate(text) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.eventLog.add("деньги: dictate бросил ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(e)
            }
        mButton?.setBusy(false)
        val takeId = result.getOrElse { e ->
            Haptics.error(this@onMoneyText)
            // Причина целиком: «не вышло» без причины читается как поломка.
            Feedback.toast(this@onMoneyText, "Не разобрал (${e.message}). Наговор сохранён.", long = true)
            return@launch
        }
        if (app.moneyEngine.draftsOf(takeId).isEmpty()) {
            Haptics.error(this@onMoneyText)
            Feedback.toast(this@onMoneyText, "Трат в наговоре не нашлось — он сохранён во вкладке «Деньги»")
            return@launch
        }
        Haptics.success(this@onMoneyText)
        showMoneyPlate(takeId)
    }
}

/** «−380 ₽ · Кафе · Саша · 24,25 € · наличные» — всё, что нужно глазу до «ОК». */
internal fun moneyMeta(e: MoneyEntry): String {
    val parts = mutableListOf(MoneyFormat.rub(e.rubKop, sign = true))
    if (e.currency != "RUB") parts.add(MoneyFormat.orig(e.origMinor, e.currency) + " по ЦБ")
    parts.add(MoneyCategories.title(e.category))
    if (e.who.isNotBlank()) parts.add(MoneyCategories.whoTitle(e.who))
    if (e.account == MoneyEntry.CASH) parts.add("наличные")
    if (!e.timeKnown) parts.add(
        java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(java.util.Date(e.ts))
    )
    return parts.joinToString(" · ")
}

/**
 * Плашка разбора: кружок — отметка (всё отмечено), «✎» — поправить сумму
 * на месте, тап по строке — вкладка «Деньги», «ОК» — в журнал. Сомнение в
 * сумме — песочной строкой под тратой: его видно ДО «ОК».
 */
internal fun PravkaAccessibilityService.showMoneyPlate(takeId: Long) {
    val drafts = app.moneyEngine.draftsOf(takeId)
    if (drafts.isEmpty()) return
    val byRow = drafts.withIndex().associate { (i, e) -> (i + 1).toLong() to e.id }
    val rows = drafts.mapIndexed { i, e ->
        RaznoskaButtonController.PlateRow(
            id = (i + 1).toLong(),
            title = e.what,
            meta = moneyMeta(e),
            warn = if (e.doubt.isBlank()) "" else "⚠ " + e.doubt,
        )
    }
    val total = drafts.sumOf { it.rubKop }
    mButton?.showTasks(
        header = "ДЕНЬГИ · " + moneyCount(drafts.size) + " · " + MoneyFormat.rub(total, sign = true),
        rows = rows,
        onEdit = { row -> byRow[row]?.let { editMoneyDraft(takeId, it) } },
        onOpen = { openMoneyTab() },
        onSend = { chosen -> confirmMoney(takeId, chosen.mapNotNull { byRow[it] }) },
    )
}

/** «ОК»: отмеченные — в журнал; снятые — вычеркнуты, но не удалены. */
internal fun PravkaAccessibilityService.confirmMoney(takeId: Long, ids: List<String>) {
    mButton?.setBusy(true)
    scope.launch {
        val kept = app.moneyEngine.draftsOf(takeId).filter { it.id in ids }
        runCatching { app.moneyEngine.confirm(takeId, ids) }
            .onSuccess {
                Haptics.success(this@confirmMoney)
                Feedback.toast(
                    this@confirmMoney,
                    "✓ " + moneyCount(kept.size) + " · " + MoneyFormat.rub(kept.sumOf { it.rubKop }, sign = true),
                )
            }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Haptics.error(this@confirmMoney)
                Feedback.toast(this@confirmMoney, "Не записалось: ${e.message}. Траты ждут во вкладке «Деньги».", long = true)
            }
        mButton?.setBusy(false)
    }
}

/**
 * ✎ на плашке: сумма (и, если хочется, новое название) на месте. «2200» —
 * только сумма; «2200 такси в аэропорт» — сумма и что это; пусто — вычеркнуть.
 */
internal fun PravkaAccessibilityService.editMoneyDraft(takeId: Long, entryId: String) {
    val e = app.moneyStore.byId(entryId) ?: return
    mButton?.hidePlate()
    val whole = kotlin.math.abs(e.rubKop) / 100
    mButton?.showInput(
        prefill = "$whole ${e.what}",
        hint = "Сумма в рублях и что это",
        onCancel = { showMoneyPlate(takeId) },
    ) { typed ->
        scope.launch {
            val t = typed.trim()
            if (t.isEmpty()) {
                app.moneyEngine.drop(listOf(entryId))
            } else {
                val first = t.substringBefore(' ')
                val kop = MoneyFormat.parseKop(first)
                val rest = t.substringAfter(' ', "").trim()
                if (kop != null) app.moneyEngine.edit(entryId, rubKop = kop, what = rest.ifEmpty { null })
                else app.moneyEngine.edit(entryId, what = t)
            }
            showMoneyPlate(takeId)
        }
    }
}

/** «1 трата» / «3 траты» / «5 трат». */
internal fun moneyCount(n: Int): String {
    val word = when {
        n % 10 == 1 && n % 100 != 11 -> "трата"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "траты"
        else -> "трат"
    }
    return "$n $word"
}

internal fun PravkaAccessibilityService.openMoneyTab() {
    startActivity(
        android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
            .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_MONEY)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Меню «₽» — коротко, как у «Д»: что ждёт «ОК», открыть Деньги, набрать
 * текстом, настройки. Выписки и сверка живут во вкладке: это раз в неделю,
 * а не на ходу.
 */
internal fun PravkaAccessibilityService.showMoneyMenu() {
    if (expandButtons()) return
    scope.launch {
        runCatching { app.moneyStore.load() }
        val pending = app.moneyEngine.pendingDrafts()
        val questions = runCatching { app.moneyEngine.questions().size }.getOrDefault(0)
        val head = if (pending.isNotEmpty()) "✓ Ждут «ОК»: " + moneyCount(pending.size)
        else "— неподтверждённых трат нет"
        val items = mutableListOf(
            RaznoskaButtonController.MenuItem(head) {
                val newest = pending.firstOrNull()
                if (newest != null) showMoneyPlate(newest.takeId) else openMoneyTab()
            },
        )
        if (questions > 0) items.add(RaznoskaButtonController.MenuItem("? Вопросов сверки: $questions") { openMoneyTab() })
        items.add(RaznoskaButtonController.MenuItem("Набрать текстом") { openMoneyTypeIn("") })
        items.add(RaznoskaButtonController.MenuItem("Открыть Деньги") { openMoneyTab() })
        items.add(RaznoskaButtonController.MenuItem("Настройки") { openSettingsTab() })
        items.add(RaznoskaButtonController.MenuItem("Закрыть") { mButton?.hideMenu() })
        mButton?.showMenu(items)
    }
}
