package ru.zf.pravka.trigger

import android.os.SystemClock
import kotlinx.coroutines.launch
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.core.HeadsetPress
import ru.zf.pravka.core.ReadyChime
import ru.zf.pravka.provider.ChimePlayer
import ru.zf.pravka.provider.GoogleSpeechSession
import ru.zf.pravka.provider.MicRouting

// Кнопка гарнитуры в службе (docs/pravka.md, «Кнопка гарнитуры»): нажатие
// приходит трамплином `HeadsetButtonActivity`, решение — `core/HeadsetPress`,
// разговор со стеком Bluetooth — `provider/HeadsetVoice`. Сами тейки — те же,
// что у пальца: «П» и «З» не знают, чем их позвали, кроме одного — какой
// микрофон слушать («чем нажал — тем и слушаем»).

/**
 * Нажатие кнопки гарнитуры. Зовётся из onCreate трамплина, пока его окно не
 * встало: фокус ещё у поля, где стоял курсор, и «с полем или без» решается
 * по-настоящему, а не по пустому трамплину.
 */
fun PravkaAccessibilityService.onHeadsetButton() {
    touched()
    val running = micBusy()
    val locked = runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)
    val field = !running && !locked && headsetFieldFocused()
    val action = HeadsetPress.choose(
        takeRunning = running,
        locked = locked,
        fieldFocused = field,
        zasechkaOn = cachedZEnabled,
    )
    app.eventLog.add("гарнитура: кнопка — ${action.word}${if (locked) " (экран заблокирован)" else ""}")
    if (action == HeadsetPress.Action.STOP) {
        stopTakeFromHeadset(byHeadset = true)
        return
    }
    // Кнопкой наушников — значит, дальше слушают наушники, и следующий тейк
    // тоже, с какой бы кнопки он ни начался, пока владелец не переключит
    // кружком (владелец, 29.09.2026: «нажал на кнопку на наушниках — и он
    // слушает… а следующий раз будет слушать тоже наушник, пока я не
    // переключу»). Тот же выбор, что у кружка микрофона в веере.
    if (app.phoneMicOnly) {
        app.phoneMicOnly = false
        scope.launch { app.settings.setPhoneMicOnly(false) }
        app.eventLog.add("гарнитура: кружок микрофона — наушники (кнопкой наушников; до переключения)")
    }
    // Сперва подтверждаем стеку распознавание, потом тейк: подтверждение при
    // уже поднятом канале стек отвергает и канал роняет (см. HeadsetVoice).
    headsetVoice.start { accepted ->
        headsetTake = true
        // Whisper пишет своей службой, и она поднимается асинхронно — ей
        // метка «с гарнитуры» оставлена со сроком, а не флагом на этот вызов.
        if (cachedEngine.startsWith("whisper")) {
            DictationService.headsetUntil = SystemClock.elapsedRealtime() + HEADSET_MARK_MS
        }
        try {
            when (action) {
                HeadsetPress.Action.PRAVKA -> startForEngine()
                else -> onZasechkaTap(fromHeadset = true)
            }
        } finally {
            headsetTake = false
        }
        // Тейк не встал (микрофон без разрешения, движок недоступен) —
        // распознавание закроет тик: следить за каналом незачем.
        if (accepted) watchHeadsetDrop()
    }
}

/** Следить за каналом гарнитуры идущего тейка: её кнопка его закроет — тейк кончится. */
internal fun PravkaAccessibilityService.watchHeadsetDrop() {
    if (headsetVoice.watching) return
    headsetVoice.watchDrop(
        // Сразу на обрыве — пока запись не успела сказать «отвалились».
        onDown = { liveSession()?.headsetClosing() },
        onDrop = { gone -> onHeadsetDrop(gone) },
    )
}

/**
 * Тейк будет слушать наушники — подтвердить распознавание стеку (или
 * оставить уже подтверждённое кнопкой) и следить за каналом: тогда кнопка
 * гарнитуры останавливает любой тейк, как бы он ни начался (владелец,
 * 29.09.2026: «я всегда могу остановить одним из трёх способов: либо на
 * плашке нажать этот waveform, либо нажать стоп на диске, либо нажать кнопку
 * на наушниках»). [on] false — слушает уже телефон: распознавание
 * закрываем, музыка в наушниках возвращается, а кнопка гарнитуры при
 * опущенном канале доходит до Правки и так (`HeadsetPress.STOP`).
 */
internal fun PravkaAccessibilityService.holdHeadsetVoice(on: Boolean) {
    if (!on) {
        if (headsetVoice.active) headsetVoice.stop("слушает телефон")
        return
    }
    if (headsetVoice.active || headsetVoice.startNow()) watchHeadsetDrop()
}

/**
 * Канал гарнитуры закрылся посреди тейка: гарнитура закрыла распознавание
 * своей кнопкой (или села, ушла из зоны, пришёл звонок). Тейк кончается и
 * сказанное доставляется — ровно как по второму тапу. [gone] — гарнитура
 * отключилась совсем: вместо «принял» — «отвалились» и записка словами.
 */
internal fun PravkaAccessibilityService.onHeadsetDrop(gone: Boolean = false) {
    headsetVoice.stop("канал закрылся")
    if (!micBusy()) return
    if (gone) {
        Feedback.toast(this, "Наушники отключились — тейк закончен, сказанное сохранено")
        if (ReadyChime.shouldPlay(app.readyChime, headset = true)) {
            ChimePlayer.play(this, toHeadset = false, kind = ChimePlayer.Kind.LOST) { line -> app.eventLog.add(line) }
        }
    }
    stopTakeFromHeadset(byHeadset = true, quiet = gone)
}

/**
 * Идущий тейк любой кнопки и любого движка — к концу, как её собственный
 * стоп. [byHeadset] — стоп нажат кнопкой гарнитуры: «принял» звучит по
 * музыкальному каналу (свой канал гарнитура уже закрыла или не поднимала);
 * [quiet] — без «принял».
 */
internal fun PravkaAccessibilityService.stopTakeFromHeadset(byHeadset: Boolean = true, quiet: Boolean = false) {
    liveSession()?.let { session ->
        session.stopByHeadset = byHeadset
        session.stopQuiet = quiet
    }
    when {
        zSession != null -> stopZasechkaLive()
        rSession != null -> stopRaznoskaLive()
        mSession != null -> stopMoneyLive()
        eSession != null -> stopFoodLive()
        googleSession != null -> stopLiveDictation()
        DictationService.recording -> {
            when {
                zWhisperRecording -> zButton?.setBusy(true)
                rWhisperRecording -> rButton?.setBusy(true)
                mWhisperRecording -> mButton?.setBusy(true)
                eWhisperRecording -> eButton?.setBusy(true)
                else -> floatingButton?.setBusy(true)
            }
            stopDictation() // -> onRecordingSaved, разводит по флагам
        }
    }
}

/**
 * Поле ввода, в которое сейчас пойдёт диктовка «П», и владелец его видит.
 * Та же точка, что у тапа (живой фокус, иначе кэш), плюс видимость: кэш
 * мог остаться от приложения, которое уже ушло с экрана.
 */
private fun PravkaAccessibilityService.headsetFieldFocused(): Boolean =
    runCatching { focusedEditableNode()?.isVisibleToUser == true }.getOrDefault(false)

/** Срок метки «с гарнитуры» для записи Whisper: служба микрофона встаёт за доли секунды. */
private const val HEADSET_MARK_MS = 5_000L

// ---- Звуки тейка: «говори», «принял», «готово», «отвалились» ----

/**
 * Раздать движку распознавания, куда звучать (владелец, 29.09.2026: «должны
 * быть звуки по поводу всего: по поводу начала, по поводу конца»). Звуки —
 * `provider/ChimePlayer.kt`, когда звучать — одна настройка «Звуки диктовки»
 * (`ReadyChime.Mode`). Голоса «Расшифровал» больше нет (владелец, 30.09.2026:
 * «это ужасно, роботизированный голос, и он где-то всё время говорится,
 * где-то посередине. Просто можно оставить вот эти звуки»): стоп слышен по
 * «принял», а конец разбора — по «готово» (01.10.2026, [takeDone]).
 */
internal fun PravkaAccessibilityService.installTakeSounds() {
    // «Говори»: в наушники — когда слушают они (с завода), всегда или никогда.
    // В наушники — не раньше ползунка от начала тейка или от переключения на
    // них (01.10.2026, `ReadyChime.readyDelayMs`).
    GoogleSpeechSession.readySink = { headset, anchorMs, switched ->
        // Новый тейк — «готово» прошлого, так и не дождавшегося итога, уже ни к чему.
        if (!switched) doneArmedAt = 0L
        if (ReadyChime.shouldPlay(app.readyChime, headset)) {
            val after = when {
                !headset -> 0L
                switched -> app.chimeAfterSwitchMs
                else -> app.chimeAfterStartMs
            }
            val delay = ReadyChime.readyDelayMs(anchorMs, SystemClock.elapsedRealtime(), after)
            if (delay > 0) app.eventLog.add("звук «говори»: через $delay мс (ползунок ${after} мс)")
            ChimePlayer.play(this, toHeadset = headset, delayMs = delay) { line -> app.eventLog.add(line) }
        }
    }
    // «Наушники отвалились» — тот же выбор: владелец в наушниках, звуки не выключены.
    GoogleSpeechSession.lostSink = {
        if (ReadyChime.shouldPlay(app.readyChime, headset = true)) {
            ChimePlayer.play(this, toHeadset = false, kind = ChimePlayer.Kind.LOST) { line -> app.eventLog.add(line) }
        }
    }
    // Канал закрыла кнопка наушников, а стек промолчал или опоздал — тот же стоп, что у кнопки.
    GoogleSpeechSession.headsetStopSink = { session ->
        headsetVoice.stop("канал закрыла кнопка")
        if (liveSession() === session) stopTakeFromHeadset(byHeadset = true) else session.stop()
    }
    GoogleSpeechSession.voiceSink = { on -> holdHeadsetVoice(on) }
    GoogleSpeechSession.stopSink = { headset, byHeadset -> onTakeStopSound(headset, byHeadset) }
    GoogleSpeechSession.doneSink = { session, text -> onTakeHeard(session, text) }
    // Итог в пилюле (Засечка, Дела, Деньги, Еда) — конец тейка: «готово».
    DictationPill.resultSink = { ok, holdMs ->
        // Служебные реплики короче двух секунд («ещё раз, и пишу») — не итог.
        if (holdMs >= 2_000L) takeDone(ok)
    }
    // Поднят ли канал — у самого стека: липкая рассылка про канал под распознавание молчит.
    MicRouting.linkProbe = { headsetVoice.audioConnected() }
}

/** Тейк выбрасывают серой «отменой» — без «принял». */
private fun PravkaAccessibilityService.takeDiscarding(): Boolean =
    discardTake || zDiscard || rDiscard || mDiscard || eDiscard

/**
 * Когда прозвучал «принял» тейка, чей итог ещё не пришёл (elapsedRealtime);
 * 0 — ждать нечего. «Готово» звучит один раз на тейк и только после
 * «принял»: тот же выбор «когда звенеть», те же наушники.
 */
private var doneArmedAt = 0L

/** Итог дольше этого после «принял» — уже не про тот тейк. */
private const val DONE_WINDOW_MS = 180_000L

/**
 * Тейк дошёл до конца: текст встал в поле, запись — в ленту, еда — в
 * дневник. [ok] — легло как надо: звук «готово» (владелец, 01.10.2026:
 * «когда всё принято — отдельный звук, и когда расшифровалась — второй
 * звук»); не легло — без звука, пилюля и вибрация скажут сами. Звучит
 * «помощником»: канал гарнитуры к этому мигу давно закрыт, а музыкальный
 * канал тех же наушников вернулся.
 */
internal fun PravkaAccessibilityService.takeDone(ok: Boolean) {
    val armed = doneArmedAt
    if (armed == 0L) return
    doneArmedAt = 0L
    val since = SystemClock.elapsedRealtime() - armed
    if (since > DONE_WINDOW_MS) return
    if (!ok) {
        app.eventLog.add("звук «готово»: итог с ошибкой — без звука")
        return
    }
    ChimePlayer.play(this, toHeadset = false, kind = ChimePlayer.Kind.DONE) { line -> app.eventLog.add(line) }
    app.eventLog.add("звук «готово»: через ${since / 100 / 10.0} с после «принял»")
}

/** Когда отзвучит последний «принял» (elapsedRealtime): распознавание у стека закрываем после него. */
private var chimeUntilMs = 0L

/**
 * «Принял»: стоп нажат, сказанное ушло расшифровываться. [headset] — канал
 * наушников поднят и слушают они: звук прямо в него. [byHeadset] — стоп
 * кнопкой гарнитуры: её канал она уже закрыла, звук — по музыкальному,
 * который возвращается не сразу.
 */
private fun PravkaAccessibilityService.onTakeStopSound(headset: Boolean, byHeadset: Boolean) {
    doneArmedAt = 0L
    if (takeDiscarding()) return
    if (!ReadyChime.shouldPlay(app.readyChime, headset || byHeadset)) return
    doneArmedAt = SystemClock.elapsedRealtime()
    val ms = ChimePlayer.play(
        this,
        toHeadset = headset && !byHeadset,
        kind = ChimePlayer.Kind.STOP,
        delayMs = if (byHeadset) HEADSET_STOP_CHIME_DELAY_MS else 0L,
    ) { line -> app.eventLog.add(line) }
    chimeUntilMs = SystemClock.elapsedRealtime() + ms
}

/**
 * Сказанное расшифровано: закрыть распознавание у стека — как только
 * отзвучит «принял» (канал гарнитуры оборвал бы его).
 */
@Suppress("UNUSED_PARAMETER") // текст раньше выбирал фразу голоса; контракт `doneSink` прежний
private fun PravkaAccessibilityService.onTakeHeard(session: GoogleSpeechSession, text: String) {
    if (!headsetVoice.active) return
    val chimeLeft = (chimeUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    chromeHandler.postDelayed({ if (!micBusy()) headsetVoice.stop("тейк кончился") }, chimeLeft)
}

/** «Принял» после кнопки гарнитуры: её канал закрыт 0,7 с назад, музыкальный почти вернулся. */
private const val HEADSET_STOP_CHIME_DELAY_MS = 250L
