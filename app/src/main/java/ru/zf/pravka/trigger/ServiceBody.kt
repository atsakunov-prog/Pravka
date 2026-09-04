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

// Тело в службе: тап «Т» (еда, подходы, зарядка, вопрос), плашки еды и силовой,
// таймер отдыха, уведомления о приехавших тренировках. Расширения PravkaAccessibilityService.

/**
 * «Пробежка приехала: 5,2 км, пульс 152 против потолка 150» — как только
 * выгрузка увидела новую тренировку с часов. Кнопки 2/3/4 пишут feel прямо
 * в активность intervals; крайние 1 и 5 редки, за ними — во вкладку.
 */
internal suspend fun PravkaAccessibilityService.notifyArrivedWorkouts() {
    val arrived = app.icuSportSync.takeArrived()
    if (arrived.isEmpty()) return
    if (!runCatching { app.settings.sportNotify() }.getOrDefault(true)) return
    val rules = app.planStore.rulesFlow.value
    for (w in arrived.take(2)) {
        val name = ru.zf.pravka.core.SportCoach.sportName(w.type)
        val bits = mutableListOf<String>()
        if (w.km >= 0.1) bits.add(String.format(java.util.Locale.US, "%.1f км", w.km))
        if (w.minutes > 0) bits.add("${w.minutes} мин")
        if (w.avgHr > 0) bits.add("пульс ${w.avgHr}")
        val verdict = when {
            !w.type.equals("Run", true) || w.avgHr <= 0 || rules.runHrCeiling <= 0 -> ""
            w.avgHr <= rules.runHrCeiling -> "Под потолком ${rules.runHrCeiling} ✓."
            rules.greyZoneLow in 1..w.avgHr && w.avgHr <= rules.greyZoneHigh ->
                "Серая зона ${rules.greyZoneLow}–${rules.greyZoneHigh} — твоё же правило."
            else -> "Выше потолка ${rules.runHrCeiling}."
        }
        sportNotify(
            workoutId = w.id,
            title = "$name приехал${if (name.endsWith("а")) "а" else ""}: " + bits.joinToString(", "),
            text = (verdict + " Как самочувствие? 1 отлично … 5 развалина").trim(),
        )
    }
}

internal fun PravkaAccessibilityService.sportNotify(workoutId: String, title: String, text: String) {
    runCatching {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val channelId = "pravka-sport"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId, "Спорт: тренировка приехала",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        fun feelAction(feel: Int): android.app.Notification.Action {
            val intent = android.content.Intent(this, SportQuickActivity::class.java)
                .putExtra(SportQuickActivity.EXTRA_ACTIVITY_ID, workoutId)
                .putExtra(SportQuickActivity.EXTRA_FEEL, feel)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = android.app.PendingIntent.getActivity(
                this, 60 + feel + workoutId.hashCode() % 1000,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val label = when (feel) {
                2 -> "2 · хорошо"
                3 -> "3 · норм"
                else -> "4 · тяжело"
            }
            return android.app.Notification.Action.Builder(
                null as android.graphics.drawable.Icon?, label, pending,
            ).build()
        }
        val open = android.app.PendingIntent.getActivity(
            this, 59,
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_SPORT)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = android.app.Notification.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(open)
            .addAction(feelAction(2))
            .addAction(feelAction(3))
            .addAction(feelAction(4))
            .setAutoCancel(true)
            .build()
        // Id от тренировки: две приехавшие не съедают друг друга.
        nm.notify(4600 + kotlin.math.abs(workoutId.hashCode() % 100), notif)
    }
}

// ---- Еда: тап -> сказал, что съел -> КБЖУ -> дневник ----

/** Кнопка «Е»: старт наговора, второй тап — разбор по КБЖУ. */
fun PravkaAccessibilityService.onFoodTap() {
    if (expandButtons()) return
    if (isLockedIdle()) return
    if (eSession != null) { stopFoodLive(); return }
    if (eWhisperRecording && DictationService.recording) {
        eButton?.setBusy(true)
        stopDictation()  // -> onRecordingSaved, routed by the flag
        return
    }
    // Один микрофон на все четыре кнопки: чужую запись эта не перехватывает.
    if (googleSession != null || zSession != null || zWhisperRecording ||
        rSession != null || rWhisperRecording || DictationService.recording
    ) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.e_busy))
        return
    }
    if (!hasMicPermission()) {
        micRequestForFood = true
        requestMicPermission()
        return
    }
    startFoodCapture()
}

internal fun PravkaAccessibilityService.startFoodCapture() {
    eButton?.hideInput()
    eButton?.hidePlate()
    if (cachedEngine.startsWith("whisper")) {
        eWhisperRecording = true
        eButton?.setRecording(true)
        eButton?.showTicker()
        eButton?.updateTicker("🎙 подходы, еда, зарядка… (тап сюда — набрать текстом)")
        Haptics.start(this)
        startDictation()
    } else {
        startFoodGoogle()
    }
    // Пока владелец говорит: греем сокет к API и подтягиваем всё, что
    // уезжает в промпт — справочники, план на сегодня, прошлый раз.
    app.warmClaudeConnection()
    scope.launch { runCatching { app.sportStore.load() } }
    scope.launch { runCatching { app.foodStore.load() } }
    scope.launch { runCatching { app.strengthStore.load() } }
    scope.launch { runCatching { app.planStore.load() } }
    scope.launch { runCatching { app.exerciseBook.load() } }
}

internal fun PravkaAccessibilityService.startFoodGoogle() {
    if (eSession != null) return
    if (!GoogleSpeechSession.isAvailable(this)) {
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.google_unavailable))
        return
    }
    val session = GoogleSpeechSession(
        this,
        // Гоблет, сплит, РДЛ, свинги, паллоф, лопаточные, творог, кускус —
        // распознаватель должен слышать ИХ, а не «гоблин» и «кус-кус».
        biasing = (cachedBiasing + bodyBiasing()).distinct(),
        formatting = cachedFormatting,
        segmentedSession = cachedSegmented,
    )
    eSession = session
    session.start(
        onReady = { Haptics.success(this) },
        onPartial = { live -> eButton?.updateTicker(live) },
        // Приём пищи — две фразы: черновик на диск не пишем, повторить
        // дешевле, чем чинить (то же решение, что у Разноски).
        onCheckpoint = { },
        onDone = { text -> onFoodLiveDone(text) },
        onError = { msg -> onFoodLiveError(msg) },
        onLog = { line -> app.eventLog.add("еда: $line") },
    )
    eButton?.setRecording(true)
    eButton?.showTicker()
    eButton?.updateTicker("🎙 подходы, еда, зарядка… (тап сюда — набрать текстом)")
    Haptics.start(this)
    runCatching { startMicHold() }
}

internal fun PravkaAccessibilityService.stopFoodLive() {
    val session = eSession ?: return
    eButton?.setRecording(false)
    eButton?.setBusy(true)
    session.stop()  // -> onFoodLiveDone
}

/** Тап по живой плашке: микрофон замолчал, дальше набираем текстом. */
internal fun PravkaAccessibilityService.onFoodTickerTap() {
    when {
        eSession != null -> {
            eTypeInstead = true
            stopFoodLive()
        }
        eWhisperRecording && DictationService.recording -> {
            eTypeInstead = true
            eButton?.setBusy(true)
            stopDictation()
        }
    }
}

internal fun PravkaAccessibilityService.openFoodTypeIn(prefill: String) {
    eButton?.showInput(prefill = prefill, hint = "Подходы, еда, зарядка") { typed ->
        val text = typed.trim()
        if (text.isNotEmpty()) onFoodText(text)
    }
}

internal fun PravkaAccessibilityService.onFoodLiveDone(text: String) {
    eSession = null
    runCatching { stopMicHold() }
    runCatching { eButton?.hideTicker() }
    eButton?.setRecording(false)
    if (eTypeInstead) {
        eTypeInstead = false
        eButton?.setBusy(false)
        openFoodTypeIn(text.trim())
        return
    }
    onFoodText(text)
}

internal fun PravkaAccessibilityService.onFoodLiveError(msg: String) {
    eSession = null
    runCatching { stopMicHold() }
    eButton?.hideTicker()
    eButton?.setRecording(false)
    eButton?.setBusy(false)
    Haptics.error(this)
    Feedback.toast(this, msg)
}

/**
 * Сказанное в руках. Кнопка — теперь ЕДА напрямую: без роутера намерений,
 * дешевле и однозначно; рацион в промпте имеет жёсткий приоритет — «мой
 * обычный завтрак» разворачивается в весь набор сам. Спорт и зарядка
 * голосом — пункт меню «Тело голосом» (прежний роутер).
 *
 * Неудача разбора не стоит владельцу его слов: фраза ложится
 * неразобранной (addRaw) и переигрывается из вкладки.
 */
internal fun PravkaAccessibilityService.onFoodText(raw: String) {
    val text = raw.trim()
    if (text.isBlank()) {
        eButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, getString(R.string.dictation_empty))
        return
    }
    if (!scope.isActive) return
    eButton?.setBusy(true)
    if (eRouteNext) {
        eRouteNext = false
        scope.launch {
            val result = runCatching { app.bodyEngine.hear(text, "voice") }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.eventLog.add("тело: hear бросил ${e.javaClass.simpleName}: ${e.message}")
                    Result.failure(e)
                }
            eButton?.setBusy(false)
            val outcome = result.getOrElse { e ->
                Haptics.error(this@onFoodText)
                Feedback.toast(
                    this@onFoodText,
                    (e.message ?: getString(R.string.e_parse_failed)) +
                        " Сказанное сохранено — можно разобрать заново.",
                    long = true,
                )
                return@launch
            }
            Haptics.success(this@onFoodText)
            showBodyPlate(outcome)
        }
        return
    }
    scope.launch {
        val result = runCatching { app.foodEngine.parse(text, source = "voice") }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        eButton?.setBusy(false)
        result.fold(
            onSuccess = { parsed ->
                Haptics.success(this@onFoodText)
                showFoodPlate(parsed.meal.id)
            },
            onFailure = { e ->
                // Слова не теряем: неразобранное ждёт во вкладке Спорта.
                runCatching { app.strengthStore.addRaw(text, "food") }
                Haptics.error(this@onFoodText)
                Feedback.toast(
                    this@onFoodText,
                    (e.message ?: "Еду не разобрал") +
                        " — сохранено; спорт голосом теперь в меню кнопки.",
                    long = true,
                )
            },
        )
    }
}

/** Плашка по разобранному: у каждого вида свой вид, кнопка одна. */
internal fun PravkaAccessibilityService.showBodyPlate(outcome: ru.zf.pravka.core.BodyEngine.Outcome) {
    when {
        outcome.strength != null -> showStrengthPlate(outcome.strength.session.id, outcome.strength)
        outcome.meal != null -> showFoodPlate(outcome.meal.id)
        outcome.gtg != null -> {
            val streak = app.strengthStore.streak(
                ru.zf.pravka.data.dayKey(System.currentTimeMillis())
            )
            eButton?.showNote(
                "✓ " + outcome.headline() + " · цепочка " + streak + " дн.",
                "Спорт",
                onAction = { openSportTab() },
            )
        }
        outcome.feel > 0 || outcome.knee.isNotBlank() ->
            eButton?.showNote("✓ " + outcome.headline(), null, onAction = null)
        outcome.question.isNotBlank() -> askCoach(outcome.question)
        else -> {
            Feedback.toast(
                this,
                (outcome.note.ifBlank { "Не понял, что это" }) +
                    " — сказанное сохранено, посмотри во «Спорте»",
                long = true,
            )
        }
    }
}

/**
 * Подходы на плашке: у каждого упражнения дельта к прошлому разу — это и
 * есть весь смысл журнала. Подтверждать нечего: записано уже, «ОК» тут был
 * бы шансом потерять данные. Вместо него чипы отдыха.
 */
internal fun PravkaAccessibilityService.showStrengthPlate(
    sessionId: Long,
    logged: ru.zf.pravka.core.StrengthEngine.Logged?,
) {
    val session = app.strengthStore.sessionById(sessionId) ?: return
    if (session.exercises.isEmpty()) return
    val deltas = logged?.deltas?.associateBy { it.name }.orEmpty()
    val rows = session.exercises.mapIndexed { index, e ->
        val delta = deltas[e.name]
        BodyButtonController.PlateRow(
            index = index,
            title = e.name,
            meta = e.compact() + (if (e.note.isBlank()) "" else " · " + e.note),
            delta = delta?.let {
                if (it.previous.isBlank()) "первый раз"
                else it.text + " (было " + it.previous + ")"
            }.orEmpty(),
            deltaUp = delta?.up,
        )
    }
    val rest = cachedRestSec
    eButton?.showBody(
        header = session.title.uppercase(java.util.Locale("ru")),
        rows = rows,
        footer = "подходов ${session.setCount}",
        note = logged?.unknown?.takeIf { it.isNotEmpty() }
            ?.let { "не узнал: " + it.joinToString(", ") }.orEmpty(),
        onEditItem = { index -> editStrengthRow(sessionId, index) },
        onDropItem = { index -> dropStrengthRow(sessionId, index) },
        onOpen = { openSportTab() },
        onConfirm = null,
        chips = listOf(60, rest, 120).distinct().map { seconds ->
            BodyButtonController.Chip("⏱ $seconds") { startRest(seconds) }
        },
    )
}

/** «✎» у упражнения: поправить вес — КБЖУ подходов от него не зависит. */
internal fun PravkaAccessibilityService.editStrengthRow(sessionId: Long, index: Int) {
    val session = app.strengthStore.sessionById(sessionId) ?: return
    val exercise = session.exercises.getOrNull(index) ?: return
    eButton?.showInput(
        prefill = exercise.compact(),
        hint = exercise.name + " — подходы",
        onCancel = { showStrengthPlate(sessionId, null) },
    ) { typed ->
        scope.launch {
            val rows = parseSetsByHand(typed, exercise)
            if (rows == null) {
                Feedback.toast(
                    this@editStrengthRow,
                    "Не понял. Пиши как «4x10 16» или «10, 9, 8 @16»",
                    long = true,
                )
            } else {
                app.strengthEngine.editRows(sessionId, exercise.exerciseId, rows)
            }
            showStrengthPlate(sessionId, null)
        }
    }
}

/**
 * Руками подходы пишутся коротко: «4x10 16», «10, 9, 8 @16», «40».
 * Разбирается на телефоне — за правку веса платить моделью незачем.
 */
internal fun PravkaAccessibilityService.parseSetsByHand(
    typed: String,
    exercise: ru.zf.pravka.data.StrengthStore.ExerciseLog,
): List<ru.zf.pravka.data.StrengthStore.SetRow>? {
    val text = typed.trim().lowercase().replace(',', ' ')
    if (text.isBlank()) return emptyList()   // пусто = убрать упражнение
    // Вес — то, что после «@» или последнее число, если есть «х»/«x».
    val weight = Regex("@\\s*(\\d+(?:[.,]\\d+)?)").find(text)
        ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    val body = text.substringBefore('@').trim()
    val cross = Regex("^(\\d+)\\s*[xх*]\\s*(\\d+)(?:\\s+(\\d+(?:[.,]\\d+)?))?$").find(body)
    if (cross != null) {
        val sets = cross.groupValues[1].toIntOrNull() ?: return null
        val amount = cross.groupValues[2].toIntOrNull() ?: return null
        val inline = cross.groupValues[3].replace(',', '.').toDoubleOrNull() ?: 0.0
        val kg = if (weight > 0) weight else inline
        if (sets !in 1..30 || amount !in 1..3000) return null
        return List(sets) {
            ru.zf.pravka.data.StrengthStore.SetRow(amount, kg, "")
        }
    }
    val numbers = Regex("\\d+").findAll(body).mapNotNull { it.value.toIntOrNull() }.toList()
    if (numbers.isEmpty()) return null
    return numbers.filter { it in 1..3000 }.map {
        ru.zf.pravka.data.StrengthStore.SetRow(it, weight, "")
    }.ifEmpty { null }
}

internal fun PravkaAccessibilityService.dropStrengthRow(sessionId: Long, index: Int) {
    val session = app.strengthStore.sessionById(sessionId) ?: return
    val exercise = session.exercises.getOrNull(index) ?: return
    scope.launch {
        app.strengthStore.dropExercise(sessionId, exercise.exerciseId)
        val left = app.strengthStore.sessionById(sessionId)
        if (left == null || left.exercises.isEmpty()) {
            eButton?.hidePlate()
            Feedback.toast(this@dropStrengthRow, "Упражнений не осталось")
        } else {
            showStrengthPlate(sessionId, null)
        }
    }
}

/** Отдых из карточки дня во вкладке: считает всё равно кнопка. */
fun PravkaAccessibilityService.startRestFromTab(seconds: Int) = startRest(seconds)

internal fun PravkaAccessibilityService.startRest(seconds: Int) {
    restHandler.removeCallbacks(restTick)
    restUntil = System.currentTimeMillis() + seconds * 1000L
    Haptics.start(this)
    restHandler.post(restTick)
}

internal fun PravkaAccessibilityService.stopRest() {
    restHandler.removeCallbacks(restTick)
    restUntil = 0L
    eButton?.setRest(0)
}

/** Вопрос голосом: Опус отвечает, ответ ложится запиской у кнопки. */
internal fun PravkaAccessibilityService.askCoach(question: String) {
    eButton?.setBusy(true)
    scope.launch {
        val answer = runCatching { app.sportCoach.ask(question) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                ru.zf.pravka.core.SportCoach.Answer("", 0.0, e.message ?: "не вышло")
            }
        eButton?.setBusy(false)
        if (answer.error.isNotBlank()) {
            Haptics.error(this@askCoach)
            Feedback.toast(this@askCoach, answer.error, long = true)
            return@launch
        }
        // Ответ бывает в несколько абзацев — на плашке он не поместится, и
        // растягивать её на пол-экрана незачем: первые строки здесь,
        // целиком во вкладке «Спорт».
        eButton?.showNote(
            answer.text.replace('\n', ' ').take(180),
            "Целиком",
            holdMs = 30_000,
            onAction = { openSportTab() },
        )
    }
}

/** Голосовые имена упражнений и продуктов для смещения распознавателя. */
internal fun PravkaAccessibilityService.bodyBiasing(): List<String> =
    runCatching { app.bodyEngine.biasing() }.getOrDefault(emptyList())

/**
 * Тарелка на плашке: позиции с граммами и КБЖУ, «✎» правит вес на месте,
 * «✕» убирает позицию, «✓ В дневник» записывает приём. До подтверждения
 * приём в сумму дня не идёт и наружу не уезжает — но на диске он уже есть.
 */
internal fun PravkaAccessibilityService.showFoodPlate(mealId: Long) {
    val meal = app.foodStore.byId(mealId) ?: return
    if (meal.items.isEmpty()) return
    val rows = meal.items.mapIndexed { index, item ->
        BodyButtonController.PlateRow(
            index = index,
            title = item.name,
            meta = listOfNotNull(
                if (item.grams > 0) "${item.grams} г" else null,
                "${item.kcal} ккал",
                "Б${item.protein} Ж${item.fat} У${item.carbs}",
                item.sureness.takeIf { it.isNotBlank() && it != "точно" },
            ).joinToString(" · "),
        )
    }
    eButton?.showBody(
        header = meal.kind.uppercase(java.util.Locale("ru")) +
            (if (meal.source == "barcode") " · ШТРИХКОД" else ""),
        rows = rows,
        footer = "${meal.kcal} ккал · Б${meal.protein} Ж${meal.fat} У${meal.carbs}",
        note = meal.note,
        onEditItem = { index -> editFoodItem(mealId, index) },
        onDropItem = { index -> dropFoodItem(mealId, index) },
        onOpen = { openFoodTab() },
        onConfirm = { confirmFood(mealId) },
        confirmLabel = "✓ В дневник",
    )
}

/** «✎» у позиции: правим вес, КБЖУ пересчитывается пропорционально. */
internal fun PravkaAccessibilityService.editFoodItem(mealId: Long, index: Int) {
    val meal = app.foodStore.byId(mealId) ?: return
    val item = meal.items.getOrNull(index) ?: return
    eButton?.showInput(
        prefill = if (item.grams > 0) item.grams.toString() else "",
        hint = "${item.name} — сколько граммов",
        onCancel = { showFoodPlate(mealId) },
    ) { typed ->
        val grams = typed.filter { it.isDigit() }.toIntOrNull()
        scope.launch {
            if (grams != null && grams > 0) {
                app.foodEngine.rescaleItem(mealId, index, grams)
            } else {
                Feedback.toast(
                    this@editFoodItem,
                    "Не понял вес — оставил как было",
                )
            }
            showFoodPlate(mealId)
        }
    }
}

internal fun PravkaAccessibilityService.dropFoodItem(mealId: Long, index: Int) {
    scope.launch {
        app.foodEngine.dropItem(mealId, index)
        if (app.foodStore.byId(mealId) == null) {
            Haptics.success(this@dropFoodItem)
            Feedback.toast(this@dropFoodItem, "Приём убран целиком")
            eButton?.hidePlate()
        } else {
            showFoodPlate(mealId)
        }
    }
}

/** «✓ В дневник»: приём в день, а оттуда в ленту и в intervals.icu. */
internal fun PravkaAccessibilityService.confirmFood(mealId: Long) {
    eButton?.setBusy(true)
    scope.launch {
        val outcome = runCatching { app.foodEngine.confirm(mealId) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                ru.zf.pravka.core.FoodEngine.ConfirmOutcome(null, "", e.message ?: "не вышло")
            }
        eButton?.setBusy(false)
        val meal = outcome.meal
        if (meal == null) {
            Haptics.error(this@confirmFood)
            Feedback.toast(this@confirmFood, "Приём не нашёлся")
            return@launch
        }
        Haptics.success(this@confirmFood)
        val day = app.foodStore.dayTotal(ru.zf.pravka.data.dayKey(meal.ts))
        val targets = runCatching { app.settings.foodTargets() }.getOrNull()
        val target = targets?.kcal ?: 0
        val tail = buildString {
            append(
                when {
                    target > 0 && day.kcal <= target -> "за день ${day.kcal} из $target ккал"
                    target > 0 -> "за день ${day.kcal} ккал, цель $target"
                    else -> "за день ${day.kcal} ккал"
                }
            )
            // Белок — его настоящий рычаг («накачаться впервые в жизни»),
            // и добирают его сознательно: остаток полезнее суммы.
            val proteinTarget = targets?.protein ?: 0
            if (proteinTarget > 0 && day.protein < proteinTarget) {
                append(" · Б ещё ").append(proteinTarget - day.protein)
            }
        }
        eButton?.showNote(
            "✓ ${meal.kcal} ккал · $tail",
            "↩︎",
            onAction = { undoFood(mealId) },
        )
        if (outcome.icuError.isNotBlank()) {
            app.eventLog.add("еда: в intervals.icu не уехало — ${outcome.icuError}")
        }
    }
}

/**
 * «↩︎» на записке: приём выходит из дня, а разбор остаётся ждать — плашка
 * возвращается, чтобы поправить и записать заново. Совсем убрать приём
 * можно во вкладке «Еда».
 */
internal fun PravkaAccessibilityService.undoFood(mealId: Long) {
    scope.launch {
        val meal = app.foodEngine.unconfirm(mealId)
        Haptics.success(this@undoFood)
        if (meal == null) {
            Feedback.toast(this@undoFood, "Приём не нашёлся")
            return@launch
        }
        Feedback.toast(this@undoFood, "↩︎ Из дня убран, разбор ждёт")
        showFoodPlate(mealId)
    }
}

internal fun PravkaAccessibilityService.openFoodTab(action: String = "") {
    startActivity(
        android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
            .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_FOOD)
            .apply {
                if (action.isNotBlank()) {
                    putExtra(ru.zf.pravka.MainActivity.EXTRA_FOOD_ACTION, action)
                }
            }
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

internal fun PravkaAccessibilityService.openSportTab() {
    startActivity(
        android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
            .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_SPORT)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Меню кнопки «Е». Владелец: «куча всего, но на самом деле нужно только
 * записать еду и закрыть».
 *
 * План на сегодня, отдых, зарядка, подходы, фото тарелки, штрихкод и обе
 * дороги «Открыть» отсюда убраны — всё это есть во вкладках Тело (С) и
 * Тело (Е), где ещё и видно, что происходит. Меню на десять строк перед
 * одним действием — это налог на каждое нажатие.
 */
internal fun PravkaAccessibilityService.showFoodMenu() {
    if (expandButtons()) return
    scope.launch {
        runCatching { app.foodStore.load() }
        val today = ru.zf.pravka.data.dayKey(System.currentTimeMillis())
        val food = app.foodStore.dayTotal(today)
        val target = runCatching { app.settings.foodTargets().kcal }.getOrDefault(0)
        // Первой строкой — что сейчас: сколько съедено за день. Это
        // единственная цифра, ради которой он вообще держит кнопку.
        val head = if (food.empty) "— еды сегодня не записано"
        else "Сегодня: ${food.kcal}" + (if (target > 0) " из $target" else "") +
            " ккал · Б${food.protein}"
        eButton?.showMenu(
            listOf(
                BodyButtonController.MenuItem(head) { openFoodTab() },
                BodyButtonController.MenuItem("Записать еду") { onFoodTap() },
                BodyButtonController.MenuItem("Закрыть") { eButton?.hideMenu() },
            )
        )
    }
}

/** «Зарядка сделана»: одна отметка, ноль токенов, цепочка не рвётся. */
internal fun PravkaAccessibilityService.markCharged() {
    scope.launch {
        val day = app.bodyEngine.chargedToday()
        val streak = app.strengthStore.streak(day.date)
        Haptics.success(this@markCharged)
        eButton?.showNote("✓ Зарядка сделана · цепочка $streak дн.", null, onAction = null)
    }
}

internal fun PravkaAccessibilityService.reparseFood(mealId: Long) {
    eButton?.setBusy(true)
    scope.launch {
        val result = runCatching { app.foodEngine.reparse(mealId) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        eButton?.setBusy(false)
        result.onSuccess { parsed ->
            Haptics.success(this@reparseFood)
            showFoodPlate(parsed.meal.id)
        }.onFailure { e ->
            Haptics.error(this@reparseFood)
            Feedback.toast(
                this@reparseFood,
                e.message ?: "Разобрать заново не вышло",
                long = true,
            )
        }
    }
}
