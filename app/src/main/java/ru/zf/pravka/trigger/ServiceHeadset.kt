package ru.zf.pravka.trigger

import android.os.SystemClock
import ru.zf.pravka.core.HeadsetPress

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
        stopTakeFromHeadset()
        return
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
        if (accepted) headsetVoice.watchDrop { onHeadsetDrop() }
    }
}

/**
 * Канал гарнитуры закрылся посреди тейка: гарнитура закрыла распознавание
 * своей кнопкой (или села, ушла из зоны, пришёл звонок). Тейк кончается и
 * сказанное доставляется — ровно как по второму тапу.
 */
internal fun PravkaAccessibilityService.onHeadsetDrop() {
    headsetVoice.stop("канал закрылся")
    if (micBusy()) stopTakeFromHeadset()
}

/** Идущий тейк любой кнопки и любого движка — к концу, как её собственный стоп. */
internal fun PravkaAccessibilityService.stopTakeFromHeadset() {
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
