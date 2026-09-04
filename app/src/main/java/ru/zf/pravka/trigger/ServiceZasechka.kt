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

// Засечка в службе: тап «З», запись с локскрина, разбор фразы в ленту, плашка,
// помидоры и напоминания о дырах. Расширения PravkaAccessibilityService: сама служба
// остаётся хозяином микрофона, окон и тиков, режимная логика живёт здесь.

internal fun PravkaAccessibilityService.lockedDoubleTapArmed(now: Long): Boolean {
    val armed = now - lockArmedAt in 1..PravkaAccessibilityService.LOCK_DOUBLE_TAP_MS
    lockArmedAt = if (armed) 0L else now
    return armed
}

fun PravkaAccessibilityService.onZasechkaTap() {
    touched()
    if (isLockedIdle()) {
        if (!lockedDoubleTapArmed(System.currentTimeMillis())) {
            // Первый тап только взводит — и показывает это, иначе жест
            // неотличим от «кнопка сломалась».
            Haptics.start(this)
            zButton?.showNote("Ещё раз — и пишу", holdMs = PravkaAccessibilityService.LOCK_DOUBLE_TAP_MS)
            return
        }
        zButton?.hideNote()
        // Запись с локскрина живёт 40 секунд. Владелец: «через 40 секунд
        // вообще, потому что я больше и не говорю». Разблокировал —
        // потолок снимается, значит он тут и говорит сколько нужно.
        lockedTakeCapAt = System.currentTimeMillis() + PravkaAccessibilityService.LOCKED_TAKE_CAP_MS
    }
    if (zSession != null) { stopZasechkaLive(); return }
    if (zWhisperRecording && DictationService.recording) {
        zButton?.setBusy(true)
        stopDictation()  // -> onRecordingSaved, routed by the flag
        return
    }
    // One microphone: while a Правка, Разноска or Еда take runs, the "З"
    // tap only nags.
    if (rSession != null || rWhisperRecording) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.r_busy_pravka))
        return
    }
    if (eSession != null || eWhisperRecording) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.e_busy_pravka))
        return
    }
    if (googleSession != null || DictationService.recording) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.z_busy_zasechka))
        return
    }
    if (!hasMicPermission()) {
        micRequestForZasechka = true
        requestMicPermission()
        return
    }
    startZasechkaCapture()
}

internal fun PravkaAccessibilityService.startZasechkaCapture() {
    zButton?.hideInput()
    zButton?.hideAsk()
    if (cachedEngine.startsWith("whisper")) {
        zWhisperRecording = true
        zButton?.setRecording(true)
        // Whisper has no live words - the plate still shows, because it
        // is also the "type instead" tap target (confidential takes).
        zButton?.showTicker()
        zButton?.updateTicker("🎙 говори… (тап сюда — набрать текстом)")
        Haptics.start(this)
        startDictation()
    } else {
        startZasechkaGoogle()
    }
    // The categorizer call comes right after stop - skip its handshake.
    app.warmClaudeConnection()
}

internal fun PravkaAccessibilityService.startZasechkaGoogle() {
    if (zSession != null) return
    if (!GoogleSpeechSession.isAvailable(this)) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.google_unavailable))
        return
    }
    val session = GoogleSpeechSession(
        this,
        // Client and category names are exactly the words these takes are
        // full of - bias the recognizer toward them, same as the dictionary.
        biasing = (cachedBiasing + zClientsCached + zCategoriesCached).distinct(),
        formatting = cachedFormatting,
        segmentedSession = cachedSegmented,
    )
    zSession = session
    session.start(
        onReady = { Haptics.success(this) },
        onPartial = { live -> zButton?.updateTicker(live) },
        // No recovery draft here: a lost 5-second take is re-spoken in
        // seconds, unlike a lost dictation paragraph.
        onCheckpoint = { },
        onDone = { text -> onZasechkaLiveDone(text) },
        onError = { msg -> onZasechkaLiveError(msg) },
        onLog = { line -> app.eventLog.add("засечка: $line") },
    )
    zButton?.setRecording(true)
    zButton?.showTicker()
    zButton?.updateTicker("🎙 говори… (тап сюда — набрать текстом)")
    Haptics.start(this)
    runCatching { startMicHold() }
}

internal fun PravkaAccessibilityService.stopZasechkaLive() {
    val session = zSession ?: return
    zButton?.setRecording(false)
    zButton?.setBusy(true)
    session.stop()  // -> onZasechkaLiveDone
}

/**
 * Tap on the live plate (owner's request): the dictation stops and the
 * plate becomes a text box - some takes are typed, not said out loud.
 * Whatever the recognizer already heard prefills the box (Google live);
 * a Whisper take is discarded unheard - its audio never gets transcribed.
 */
internal fun PravkaAccessibilityService.onZasechkaPlateTap() {
    when {
        zSession != null -> {
            zTypeInstead = true
            stopZasechkaLive()   // -> onZasechkaLiveDone routes to the box
        }
        zWhisperRecording && DictationService.recording -> {
            zTypeInstead = true
            zButton?.setBusy(true)
            stopDictation()      // -> onRecordingSaved routes to the box
        }
    }
}

// Confidential type-in: same engine, source "text" - the ribbon and the
// toasts behave exactly like after a voice take.
internal fun PravkaAccessibilityService.openZasechkaTypeIn(prefill: String) {
    zButton?.showInput(prefill) { typed ->
        val text = typed.trim()
        if (text.isNotEmpty()) onZasechkaText(text, source = "text")
    }
}

/** The mic-hold notification's Stop: finalize whichever live take runs. */
fun PravkaAccessibilityService.stopAnyLive() {
    when {
        zSession != null -> stopZasechkaLive()
        rSession != null -> stopRaznoskaLive()
        else -> stopLiveDictation()
    }
}

internal fun PravkaAccessibilityService.onZasechkaLiveDone(text: String) {
    zSession = null
    runCatching { stopMicHold() }
    runCatching { zButton?.hideTicker() }
    zButton?.setRecording(false)
    if (zTypeInstead) {
        zTypeInstead = false
        zButton?.setBusy(false)
        openZasechkaTypeIn(text.trim())
        return
    }
    onZasechkaText(text)
}

internal fun PravkaAccessibilityService.onZasechkaLiveError(msg: String) {
    zSession = null
    runCatching { stopMicHold() }
    zButton?.hideTicker()
    zButton?.setRecording(false)
    zButton?.setBusy(false)
    Haptics.error(this)
    Feedback.toast(this, msg)
}

/** Transcript in hand (either engine, or typed): categorize, store, confirm. */
internal fun PravkaAccessibilityService.onZasechkaText(raw: String, source: String = "voice") {
    val text = raw.trim()
    if (text.isBlank()) {
        zButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.dictation_empty))
        return
    }
    if (!scope.isActive) return
    zButton?.setBusy(true)
    scope.launch {
        val outcome = runCatching { app.zasechkaEngine.record(text, source) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.eventLog.add("засечка: record threw ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        zButton?.setBusy(false)
        if (outcome == null) {
            Haptics.error(this@onZasechkaText)
            Feedback.toast(this@onZasechkaText, getString(R.string.z_record_failed))
            return@launch
        }
        zButton?.setRemind(false)
        val entry = outcome.entry
        // Записка на две секунды вместо тоста — владелец просил показывать
        // «что за дело записано и что за дело исправлено и как». Главное
        // здесь — ЧТО ИМЕННО и КУДА: без времени и категории строка «⏱
        // Работа» не говорит ничего, а по ней и надо ловить промахи
        // разбора, пока они свежие.
        val tail = listOf(entry.category, entry.client)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        when {
            outcome.action == "none" -> {
                Haptics.success(this@onZasechkaText)
                zButton?.showNote(
                    "🤷 Не записал: ${outcome.say.ifBlank { "это не про ленту" }}",
                    ok = false,
                )
            }
            outcome.action == "stop" -> {
                Haptics.success(this@onZasechkaText)
                zButton?.showNote(
                    "⏹ «${entry.title}» закрыто\n" +
                        "${zTime(entry.start)}–${zTime(entry.end)}, ${entry.durationMin()} мин"
                )
            }
            // «Параллельно слушаю Акунина»: текущее дело не тронуто,
            // запись легла вторым треком поверх него.
            outcome.action == "parallel" -> {
                Haptics.success(this@onZasechkaText)
                zButton?.showNote(
                    "∥ ${entry.title}\nпараллельно, с ${zTime(entry.start)}" +
                        (if (tail.isBlank()) "" else " · $tail")
                )
            }
            outcome.action == "insert" && outcome.error == null -> {
                Haptics.success(this@onZasechkaText)
                zButton?.showNote(
                    "⤵ Вставлено «${entry.title}»\n" +
                        "${zTime(entry.start)}–${zTime(entry.end)}" +
                        (if (tail.isBlank()) "" else " · $tail") +
                        "\nобрамляющее дело продолжено" +
                        (outcome.parallel?.let { "\n∥ ${it.title}" } ?: "")
                )
            }
            outcome.action == "edit" -> {
                Haptics.success(this@onZasechkaText)
                zButton?.showNote(
                    "✏️ Исправлено ${zTime(entry.start)}–${zTime(entry.end)}\n" +
                        "«${outcome.previousTitle}» → «${entry.title}»" +
                        (if (tail.isBlank()) "" else "\n$tail")
                )
            }
            outcome.action == "delete" -> {
                Haptics.success(this@onZasechkaText)
                zButton?.showNote("🗑 Удалено «${entry.title}» ${zTime(entry.start)}")
            }
            outcome.categorized -> {
                Haptics.success(this@onZasechkaText)
                zButton?.showNote(
                    "⏱ ${entry.title}\nс ${zTime(entry.start)}" +
                        (if (tail.isBlank()) "" else " · $tail") +
                        // «Готовил еду и параллельно смотрел ютуб» — одна
                        // фраза, две записи, и вторая тоже должна быть видна.
                        (outcome.parallel?.let { "\n∥ ${it.title}" } ?: "")
                )
            }
            else -> {
                // Saved raw: quieter success, the owner sorts it in the tab.
                Haptics.error(this@onZasechkaText)
                zButton?.showNote(
                    getString(R.string.z_saved_raw, outcome.error ?: ""),
                    ok = false,
                    holdMs = 4_000,
                )
            }
        }
    }
}

internal fun PravkaAccessibilityService.showZasechkaMenu() {
    touched()
    val goTab: () -> Unit = {
        startActivity(
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_ZASECHKA)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    val openTab = ZasechkaButtonController.MenuItem("Открыть Засечку", goTab)
    // The top pill answers "что сейчас считается?" without opening the
    // app: current дело and since when (owner's request). Tap -> the tab.
    scope.launch {
        val now = System.currentTimeMillis()
        val open = runCatching { app.zasechkaStore.openEntry() }.getOrNull()
        val alongside = runCatching { app.zasechkaStore.openParallel() }.getOrNull()
        val header = ZasechkaButtonController.MenuItem(
            when {
                open != null ->
                    "⏱ ${open.title.ifBlank { "без названия" }} — с ${zTime(open.start)}, " +
                        zDur(now - open.start) + (alongside?.let { " ∥ ${it.title}" } ?: "")
                alongside != null ->
                    "∥ ${alongside.title.ifBlank { "без названия" }} — с ${zTime(alongside.start)}"
                else -> "— сейчас ничего не идёт"
            },
            goTab,
        )
        // Владелец: «допом использую только 25 минут, 5 минут перерыв».
        // «50 минут» и «Отменить» убраны: отмена живёт в ленте, где видно,
        // что именно откатываешь, а полсотни минут он не ставил ни разу.
        val close = ZasechkaButtonController.MenuItem("Закрыть") { zButton?.hideMenu() }
        val items = if (pomodoroEndsAt > 0) {
            listOfNotNull(
                header,
                ZasechkaButtonController.MenuItem(
                    if (pomodoroIsBreak) "Стоп: перерыв" else "Стоп: помидор"
                ) { stopPomodoro(byUser = true) },
                openTab,
                close,
            )
        } else {
            listOfNotNull(
                header,
                ZasechkaButtonController.MenuItem("🍅 25 минут") { startPomodoro(25, isBreak = false) },
                ZasechkaButtonController.MenuItem("Перерыв 5") { startPomodoro(5, isBreak = true) },
                openTab,
                close,
            )
        }
        zButton?.showMenu(items)
    }
}

fun PravkaAccessibilityService.startPomodoro(minutes: Int, isBreak: Boolean) {
    pomodoroEndsAt = System.currentTimeMillis() + minutes * 60_000L
    pomodoroIsBreak = isBreak
    getSharedPreferences(PravkaAccessibilityService.PREFS_INTERNAL, android.content.Context.MODE_PRIVATE).edit()
        .putLong(PravkaAccessibilityService.KEY_Z_POMO_ENDS, pomodoroEndsAt)
        .putBoolean(PravkaAccessibilityService.KEY_Z_POMO_BREAK, isBreak)
        .apply()
    Haptics.start(this)
    Feedback.toast(this, if (isBreak) "Перерыв $minutes мин" else "🍅 $minutes мин — поехали")
    pomodoroHandler.removeCallbacks(pomodoroTicker)
    pomodoroTicker.run()
}

fun PravkaAccessibilityService.stopPomodoro(byUser: Boolean) {
    clearPomodoro()
    if (byUser) Feedback.toast(this, "Таймер остановлен")
}

internal fun PravkaAccessibilityService.clearPomodoro() {
    pomodoroEndsAt = 0
    pomodoroHandler.removeCallbacks(pomodoroTicker)
    getSharedPreferences(PravkaAccessibilityService.PREFS_INTERNAL, android.content.Context.MODE_PRIVATE).edit()
        .remove(PravkaAccessibilityService.KEY_Z_POMO_ENDS).remove(PravkaAccessibilityService.KEY_Z_POMO_BREAK).apply()
    zButton?.setPomodoro(null, null)
}

internal fun PravkaAccessibilityService.tickPomodoro() {
    if (pomodoroEndsAt <= 0) return
    val left = pomodoroEndsAt - System.currentTimeMillis()
    if (left <= 0) {
        completePomodoro()
        return
    }
    val minutesLeft = (left + 59_999) / 60_000
    zButton?.setPomodoro(
        minutesLeft.toString(),
        if (pomodoroIsBreak) ZasechkaButtonController.POMO_BREAK
        else ZasechkaButtonController.POMO_FOCUS,
    )
}

internal fun PravkaAccessibilityService.completePomodoro() {
    val wasBreak = pomodoroIsBreak
    clearPomodoro()
    Haptics.success(this)
    if (wasBreak) {
        zPomodoroNotify("Перерыв кончился", "Ещё помидор?")
        return
    }
    scope.launch {
        val open = app.zasechkaStore.openEntry()
        if (open != null) app.zasechkaStore.incrementPomodoro(open.id)
        val internal = getSharedPreferences(PravkaAccessibilityService.PREFS_INTERNAL, android.content.Context.MODE_PRIVATE)
        val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))
        val n = internal.getInt(PravkaAccessibilityService.KEY_Z_POMO_DAY_PREFIX + dayKey, 0) + 1
        internal.edit().putInt(PravkaAccessibilityService.KEY_Z_POMO_DAY_PREFIX + dayKey, n).apply()
        app.eventLog.add("помидор №$n готов" + (open?.let { " («${it.title}»)" } ?: ""))
        zPomodoroNotify(
            "Помидор №$n готов 🍅",
            open?.let { "«${it.title}» — сделано. Перерыв?" } ?: "Перерыв?",
        )
    }
}

/**
 * The deadline lives on disk, so a running pomodoro rides through app
 * updates and service restarts: still ticking -> resume the countdown;
 * finished while we were dead -> credit it (entry + day counter) and,
 * if the finish was recent, still fire the "готов" notification - the
 * owner should not lose a pomodoro to an APK install.
 */
internal fun PravkaAccessibilityService.restorePomodoro() {
    val internal = getSharedPreferences(PravkaAccessibilityService.PREFS_INTERNAL, android.content.Context.MODE_PRIVATE)
    val ends = internal.getLong(PravkaAccessibilityService.KEY_Z_POMO_ENDS, 0L)
    if (ends <= 0) return
    pomodoroIsBreak = internal.getBoolean(PravkaAccessibilityService.KEY_Z_POMO_BREAK, false)
    val now = System.currentTimeMillis()
    if (ends > now) {
        pomodoroEndsAt = ends
        pomodoroHandler.removeCallbacks(pomodoroTicker)
        pomodoroTicker.run()
    } else {
        val wasBreak = pomodoroIsBreak
        val endedAgo = now - ends
        clearPomodoro()
        if (wasBreak) {
            if (endedAgo < 10 * 60_000L) zPomodoroNotify("Перерыв кончился", "Ещё помидор?")
            return
        }
        scope.launch {
            // The entry that was running when the bell should have rung.
            if (endedAgo < 30 * 60_000L) {
                app.zasechkaStore.openEntry()?.let { app.zasechkaStore.incrementPomodoro(it.id) }
            }
            val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(java.util.Date(ends))
            val n = internal.getInt(PravkaAccessibilityService.KEY_Z_POMO_DAY_PREFIX + dayKey, 0) + 1
            internal.edit().putInt(PravkaAccessibilityService.KEY_Z_POMO_DAY_PREFIX + dayKey, n).apply()
            app.eventLog.add("помидор №$n дозасчитан после перезапуска")
            if (endedAgo < 10 * 60_000L) {
                zPomodoroNotify("Помидор №$n готов 🍅", "Досчитал за время обновления. Перерыв?")
            }
        }
    }
}

internal fun PravkaAccessibilityService.zPomodoroNotify(title: String, text: String) {
    runCatching {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val channelId = "pravka-zasechka"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId, getString(R.string.z_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        fun quick(what: String, code: Int): android.app.PendingIntent =
            android.app.PendingIntent.getActivity(
                this, code,
                android.content.Intent(this, ZasechkaQuickActivity::class.java)
                    .putExtra(ZasechkaQuickActivity.EXTRA_WHAT, what)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notif = android.app.Notification.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tile)
            .addAction(
                android.app.Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Перерыв 5",
                    quick(ZasechkaQuickActivity.W_BREAK5, 7),
                ).build()
            )
            .addAction(
                android.app.Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "🍅 25",
                    quick(ZasechkaQuickActivity.W_POMO25, 8),
                ).build()
            )
            .setAutoCancel(true)
            .build()
        nm.notify(45, notif)
    }
}

internal fun PravkaAccessibilityService.zTime(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

internal fun PravkaAccessibilityService.zDur(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60} ч ${min % 60} м" else "$min м"
}

internal fun PravkaAccessibilityService.zasechkaReminderCheck() {
    val gapMin = cachedZGapMin
    scope.launch {
        // Reminders die with the toggle or with a zero interval.
        if (!cachedZEnabled || gapMin <= 0) {
            zButton?.setRemind(false)
            return@launch
        }
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        // Running LOSSES are not "busy": the amber pulse keeps nagging,
        // the evening nudge and the hourly wink stay for real дела only.
        val open = app.zasechkaStore.openEntry()?.takeIf { it.source != "gap" }
        val internal = getSharedPreferences(PravkaAccessibilityService.PREFS_INTERNAL, android.content.Context.MODE_PRIVATE)
        val todayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date(now))

        // Outside the active day the button never pulses; the one evening
        // nudge asks to close a still-running entry.
        if (hour >= cachedZDayEnd || hour < cachedZDayStart) {
            zButton?.setRemind(false)
            if (open != null && hour >= cachedZDayEnd &&
                internal.getString(PravkaAccessibilityService.KEY_Z_EVENING_DAY, "") != todayKey
            ) {
                internal.edit().putString(PravkaAccessibilityService.KEY_Z_EVENING_DAY, todayKey).apply()
                // The evening nudge doubles as the day's phone summary.
                val phoneDay = app.phoneStore.daysFlow.value[ru.zf.pravka.data.phoneDayKey(now)]
                val phoneLine = phoneDay?.let {
                    "\nЭкран: ${zDur(it.screenMs)} · подъёмов ${it.pickups} · отвлечений ${it.glances}"
                }.orEmpty()
                zNotify(
                    getString(R.string.z_notify_evening_title),
                    getString(R.string.z_notify_evening_text, open.title) + phoneLine,
                )
            }
            return@launch
        }
        if (open != null) {
            zButton?.setRemind(false)
            checkInOnOpenEntry(open, now, internal)
            // Hourly heartbeat (owner's request): the button winks once an
            // hour and says out loud what is being counted right now -
            // trust in the robot comes from glanceability, not silence.
            // A freshly started дело (<10 мин) doesn't need it: he just
            // dictated it himself.
            if (now - internal.getLong(PravkaAccessibilityService.KEY_Z_BEAT_AT, 0L) >= 60 * 60_000L) {
                internal.edit().putLong(PravkaAccessibilityService.KEY_Z_BEAT_AT, now).apply()
                if (now - open.start >= 10 * 60_000L) {
                    zButton?.blinkOnce()
                    Feedback.toast(
                        this@zasechkaReminderCheck,
                        "⏱ «${open.title.ifBlank { "без названия" }}» — идёт ${zDur(now - open.start)} (с ${zTime(open.start)})",
                    )
                }
            }
            return@launch
        }
        val todays = app.zasechkaStore.forRange(ru.zf.pravka.data.dayStartMs(now), now)
        if (todays.isEmpty()) {
            zButton?.setRemind(true)
            if (internal.getString(PravkaAccessibilityService.KEY_Z_MORNING_DAY, "") != todayKey) {
                internal.edit().putString(PravkaAccessibilityService.KEY_Z_MORNING_DAY, todayKey).apply()
                zNotify(
                    getString(R.string.z_notify_morning_title),
                    getString(R.string.z_notify_morning_text),
                )
            }
            return@launch
        }
        val lastEnd = todays.maxOf { it.end }
        val gapMs = now - lastEnd
        if (gapMs >= gapMin * 60_000L) {
            zButton?.setRemind(true)
            // One notification per distinct gap; the pulse keeps nagging.
            if (internal.getLong(PravkaAccessibilityService.KEY_Z_GAP_NOTIFIED, 0L) != lastEnd) {
                internal.edit().putLong(PravkaAccessibilityService.KEY_Z_GAP_NOTIFIED, lastEnd).apply()
                zNotify(
                    getString(R.string.z_notify_gap_title, zTime(lastEnd)),
                    getString(R.string.z_notify_gap_text, gapMs / 60_000L),
                )
            }
        } else {
            zButton?.setRemind(false)
        }
    }
}

/**
 * «Всё ещё «Обед»?» - every category carries the owner's typical length
 * for it; when the running дело outlives that, the button winks and asks.
 * «Да» resets the clock (ask again after another base period), «Нет»
 * starts a new take on the spot. Never on a locked screen (the bubble
 * would sit unseen above the keyguard) and never for the auto-filled
 * «Потери» - losses are not a дело to confirm.
 */
internal suspend fun PravkaAccessibilityService.checkInOnOpenEntry(
    open: ru.zf.pravka.data.ZasechkaStore.Entry,
    now: Long,
    prefs: android.content.SharedPreferences,
) {
    if (!cachedZCheckins || open.source == "gap") return
    if (googleSession != null || zSession != null || DictationService.recording) return
    if (runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)) return
    val baseMin = app.zasechkaStore.categories()
        .firstOrNull { it.name.equals(open.category, ignoreCase = true) }
        ?.baseMin ?: 0
    if (baseMin <= 0) return
    val baseMs = baseMin * 60_000L
    // The clock runs from the last answer for THIS entry, or from its start.
    val since = if (prefs.getLong(PravkaAccessibilityService.KEY_Z_ASK_ID, 0L) == open.id) {
        prefs.getLong(PravkaAccessibilityService.KEY_Z_ASK_AT, open.start)
    } else open.start
    if (now - since < baseMs) return
    prefs.edit()
        .putLong(PravkaAccessibilityService.KEY_Z_ASK_ID, open.id)
        .putLong(PravkaAccessibilityService.KEY_Z_ASK_AT, now)
        .apply()
    val name = open.title.ifBlank { open.category.ifBlank { "дело" } }
    Haptics.start(this)
    zButton?.showAsk(
        question = "Всё ещё «$name»? Идёт ${zDur(now - open.start)}",
        onYes = {
            getSharedPreferences(PravkaAccessibilityService.PREFS_INTERNAL, android.content.Context.MODE_PRIVATE).edit()
                .putLong(PravkaAccessibilityService.KEY_Z_ASK_ID, open.id)
                .putLong(PravkaAccessibilityService.KEY_Z_ASK_AT, System.currentTimeMillis())
                .apply()
            Feedback.toast(this, "Ок, считаем дальше")
        },
        // Straight into a new take: the mic starts, and a tap on the live
        // plate switches to typing if the answer is private.
        onNo = { onZasechkaTap() },
    )
    app.eventLog.add("засечка: спросил «всё ещё $name?» (база $baseMin мин)")
}

internal fun PravkaAccessibilityService.zNotify(title: String, text: String) {
    runCatching {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val channelId = "pravka-zasechka"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId, getString(R.string.z_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val open = android.app.PendingIntent.getActivity(
            this, 5,
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_ZASECHKA)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val record = android.app.PendingIntent.getActivity(
            this, 6,
            android.content.Intent(this, ZasechkaQuickActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = android.app.Notification.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            // Evening summaries run to several lines.
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(open)
            .addAction(
                android.app.Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    getString(R.string.z_record_action),
                    record,
                ).build()
            )
            .setAutoCancel(true)
            .build()
        nm.notify(44, notif)
    }
}
