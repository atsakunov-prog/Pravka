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

// ---- Звуки тейка: «говори», «принял», «отвалились» и голос в конце ----

/**
 * Раздать движку распознавания, куда звучать и говорить (владелец,
 * 29.09.2026: «должны быть звуки по поводу всего: по поводу начала, по
 * поводу конца»). Звуки — `provider/ChimePlayer.kt`, голос — `Speaker.kt`,
 * когда звучать — одна настройка «Звуки диктовки» (`ReadyChime.Mode`).
 */
internal fun PravkaAccessibilityService.installTakeSounds() {
    // «Говори»: в наушники — когда слушают они (с завода), всегда или никогда.
    GoogleSpeechSession.readySink = { headset ->
        if (ReadyChime.shouldPlay(app.readyChime, headset)) {
            ChimePlayer.play(this, toHeadset = headset) { line -> app.eventLog.add(line) }
        }
    }
    // «Наушники отвалились» — тот же выбор: владелец в наушниках, звуки не выключены.
    GoogleSpeechSession.lostSink = {
        if (ReadyChime.shouldPlay(app.readyChime, headset = true)) {
            ChimePlayer.play(this, toHeadset = false, kind = ChimePlayer.Kind.LOST) { line -> app.eventLog.add(line) }
        }
    }
    GoogleSpeechSession.voiceSink = { on -> holdHeadsetVoice(on) }
    GoogleSpeechSession.stopSink = { headset, byHeadset -> onTakeStopSound(headset, byHeadset) }
    GoogleSpeechSession.doneSink = { session, text -> onTakeHeard(session, text) }
    // Поднят ли канал — у самого стека: липкая рассылка про канал под распознавание молчит.
    MicRouting.linkProbe = { headsetVoice.audioConnected() }
}

/** Тейк выбрасывают серой «отменой» — ни «принял», ни «Расшифровал». */
private fun PravkaAccessibilityService.takeDiscarding(): Boolean =
    discardTake || zDiscard || rDiscard || mDiscard || eDiscard

/** Когда отзвучит последний «принял» (elapsedRealtime): голос в конце его ждёт. */
private var chimeUntilMs = 0L

/**
 * «Принял»: стоп нажат, сказанное ушло расшифровываться. [headset] — канал
 * наушников поднят и слушают они: звук прямо в него. [byHeadset] — стоп
 * кнопкой гарнитуры: её канал она уже закрыла, звук — по музыкальному,
 * который возвращается не сразу.
 */
private fun PravkaAccessibilityService.onTakeStopSound(headset: Boolean, byHeadset: Boolean) {
    if (takeDiscarding()) return
    if (!ReadyChime.shouldPlay(app.readyChime, headset || byHeadset)) return
    val ms = ChimePlayer.play(
        this,
        toHeadset = headset && !byHeadset,
        kind = ChimePlayer.Kind.STOP,
        delayMs = if (byHeadset) HEADSET_STOP_CHIME_DELAY_MS else 0L,
    ) { line -> app.eventLog.add(line) }
    chimeUntilMs = SystemClock.elapsedRealtime() + ms
}

/**
 * Сказанное расшифровано: закрыть распознавание у стека (как только
 * отзвучит «принял» — канал гарнитуры оборвал бы его) и сказать в наушники
 * «Расшифровал» — голосом помощника, который беззвучный режим не глушит.
 * Молчит, если тейк выброшен, если его слушал телефон, если звуки
 * выключены и если итог скажет сам режим (Засечка с кнопки гарнитуры:
 * «записал коммент»).
 */
private fun PravkaAccessibilityService.onTakeHeard(session: GoogleSpeechSession, text: String) {
    val now = SystemClock.elapsedRealtime()
    val chimeLeft = (chimeUntilMs - now).coerceAtLeast(0L)
    val channelUp = headsetVoice.active
    if (channelUp) {
        chromeHandler.postDelayed({ if (!micBusy()) headsetVoice.stop("тейк кончился") }, chimeLeft)
    }
    if (takeDiscarding() || !session.spokeInHeadset) return
    if (app.readyChime == ReadyChime.Mode.OFF) return
    if (session === zSession && (zFromHeadset || zTypeInstead)) return
    val phrase = if (text.isBlank()) "Ничего не расслышал" else "Расшифровал"
    // Канал гарнитуры закрываем только что — музыкальный вернётся через полсекунды-секунду.
    val gap = if (channelUp) VOICE_AFTER_CHANNEL_MS else VOICE_AFTER_CHIME_MS
    chromeHandler.postDelayed({ say(phrase) }, chimeLeft + gap)
}

/** «Принял» после кнопки гарнитуры: её канал закрыт 0,7 с назад, музыкальный почти вернулся. */
private const val HEADSET_STOP_CHIME_DELAY_MS = 250L

/** Голос после закрытия канала гарнитуры: музыкальный канал возвращается не сразу. */
private const val VOICE_AFTER_CHANNEL_MS = 900L

/** Голос после «принял» без канала гарнитуры: просто не поверх звука. */
private const val VOICE_AFTER_CHIME_MS = 150L
