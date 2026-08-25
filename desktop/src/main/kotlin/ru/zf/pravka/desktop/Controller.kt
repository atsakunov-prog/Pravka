package ru.zf.pravka.desktop

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DictMode
import ru.zf.pravka.core.DictionaryApplier
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.UndoStack
import ru.zf.pravka.core.VoiceCommands
import ru.zf.pravka.desktop.data.Paths
import ru.zf.pravka.desktop.input.Keyboard
import ru.zf.pravka.desktop.input.WindowsTarget
import ru.zf.pravka.target.PlainTextTarget

// Сценарии Правки на воркстанции: диктовка, причёсывание, ассистент, отмена.
// Всё, что видит владелец, - это состояние ниже: плашка показывает его, окно
// настроек читает ту же строку.
class Controller {

    enum class Phase { IDLE, RECORDING, TRANSCRIBING, WORKING }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        /** Короткая строка состояния: "Говори", "Распознаю", "Правлю". */
        val title: String = "",
        /** Ответ модели по мере поступления - его видно в плашке. */
        val streamed: String = "",
        val error: String? = null,
        /** Диктовка идёт до второго нажатия (короткий тап), а не пока держат. */
        val latched: Boolean = false,
    )

    private val app = DesktopApp

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Микрофонный уровень для полоски в плашке. */
    val level: StateFlow<Float> get() = app.recorder.level

    private companion object {
        /** Короче этого нажатие считается тапом: диктовка остаётся включённой. */
        const val HOLD_MS = 400L
        /** Совсем короткая запись - это промах по клавише, а не фраза. */
        const val MIN_TAKE_MS = 400L
        /** Подсказка распознавателю ограничена сверху: Whisper режет длинный prompt. */
        const val HINT_MAX_CHARS = 500
    }

    @Volatile private var pressedAt = 0L
    @Volatile private var latched = false
    @Volatile private var currentTake: File? = null

    // ---- диктовка ----

    fun onDictatePress() {
        pressedAt = System.currentTimeMillis()
        if (_state.value.phase == Phase.RECORDING) {
            // Второе нажатие в режиме "говорю долго" - это стоп.
            latched = false
            stopAndProcess()
            return
        }
        if (_state.value.phase != Phase.IDLE) return
        startRecording()
    }

    fun onDictateRelease() {
        if (_state.value.phase != Phase.RECORDING) return
        val held = System.currentTimeMillis() - pressedAt
        if (held >= HOLD_MS) {
            latched = false
            stopAndProcess()
        } else {
            // Короткий тап: клавишу отпустили, а запись продолжается.
            latched = true
            _state.value = _state.value.copy(latched = true, title = "Говори, тап - стоп")
        }
    }

    private fun startRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(Paths.recordings, "take-$stamp.wav")
        try {
            app.recorder.start(file)
        } catch (e: Exception) {
            fail(e.message ?: "Микрофон недоступен")
            return
        }
        currentTake = file
        _state.value = UiState(phase = Phase.RECORDING, title = "Говори")
    }

    private fun stopAndProcess() {
        val audioMs = app.recorder.stop()
        val file = currentTake
        currentTake = null
        if (file == null) {
            _state.value = UiState()
            return
        }
        if (audioMs < MIN_TAKE_MS) {
            file.delete()
            _state.value = UiState()
            return
        }
        _state.value = UiState(phase = Phase.TRANSCRIBING, title = "Распознаю")

        app.scope.launch {
            val model = app.settings.whisperModelFlow.value
            val started = System.currentTimeMillis()
            val hint = if (app.settings.dictHintFlow.value) dictionaryHint() else ""
            val result = app.whisper.transcribe(
                wav = file,
                url = app.settings.whisperUrlFlow.value,
                model = model,
                hint = hint,
            )
            val elapsed = System.currentTimeMillis() - started
            app.transcripts.append(
                engine = model,
                audioMs = audioMs,
                transcribeMs = elapsed,
                text = result.getOrNull().orEmpty(),
                error = result.exceptionOrNull()?.message,
            )
            if (!app.settings.keepAudioFlow.value && result.isSuccess) file.delete()

            val raw = result.getOrElse {
                fail(it.message ?: "Распознавание не удалось")
                return@launch
            }
            deliverDictated(VoiceCommands.apply(raw))
        }
    }

    /**
     * Надиктованное едет в поле один раз - уже причёсанным. На телефоне текст
     * сначала вставляется, а потом правится прямо в поле; здесь поле чужое и
     * переписать вставленное нечем, поэтому правка происходит ДО вставки.
     */
    private suspend fun deliverDictated(text: String) {
        if (!app.settings.autoCleanFlow.value) {
            // Без правки: хотя бы жёсткие замены словаря применяем сами.
            val prepared = DictionaryApplier(app.dictionaryStore).prepare(text)
            paste(prepared.text)
            return
        }
        _state.value = UiState(phase = Phase.WORKING, title = "Правлю")
        val target = PlainTextTarget(text)
        val outcome = app.engine.proofread(
            target = target,
            mode = ProofreadMode.CLEAN,
            onDelta = { partial -> _state.value = _state.value.copy(streamed = partial) },
        )
        when (outcome) {
            is ProofreadEngine.Outcome.Failed -> {
                // Правка не удалась - надиктованное всё равно должно попасть
                // в поле: терять сказанное из-за сети нельзя.
                paste(text)
                _state.value = UiState(title = "Вставлено без правки", error = outcome.message)
            }
            // Слишком короткая фраза для правки - вставляем как есть.
            ProofreadEngine.Outcome.Rejected -> paste(text)
            is ProofreadEngine.Outcome.Unchanged -> paste(outcome.result.text)
            else -> paste(target.result ?: text)
        }
    }

    // ---- правка того, что уже написано ----

    fun clean(directive: String = "", strong: Boolean = false) {
        if (_state.value.phase != Phase.IDLE) return
        _state.value = UiState(phase = Phase.WORKING, title = "Правлю")
        app.scope.launch {
            val target = WindowsTarget()
            val outcome = app.engine.proofread(
                target = target,
                mode = ProofreadMode.CLEAN,
                onDelta = { partial -> _state.value = _state.value.copy(streamed = partial) },
                directive = directive,
                modelOverride = if (strong) app.settings.strongModel else null,
            )
            when (outcome) {
                is ProofreadEngine.Outcome.Failed -> fail(outcome.message)
                ProofreadEngine.Outcome.Rejected ->
                    flash("Нечего править: выдели текст или скопируй его")
                is ProofreadEngine.Outcome.Unchanged -> flash("Текст и так чистый")
                else -> flash("Готово")
            }
        }
    }

    /** Оранжевые действия: работают по выделению, ответ кладётся в буфер. */
    fun assist(title: String, instruction: String) {
        if (_state.value.phase != Phase.IDLE) return
        _state.value = UiState(phase = Phase.WORKING, title = title)
        app.scope.launch {
            val content = WindowsTarget().read().orEmpty()
            if (content.isBlank()) {
                flash("Нечего обрабатывать: выдели текст или скопируй его")
                return@launch
            }
            val result = app.claude.assist(instruction, content) { partial ->
                _state.value = _state.value.copy(streamed = partial)
            }
            result.onSuccess {
                Keyboard.setClipboard(it.text)
                app.stats.recordAux(it.costUsd, it.inputTokens, it.outputTokens)
                flash("Ответ в буфере")
            }.onFailure { fail(it.message ?: "Не получилось") }
        }
    }

    /** Отмена: возвращает поле к тому, что было до последней правки. */
    fun undo() {
        app.scope.launch {
            val entry = UndoStack.matchByCurrentText(Keyboard.clipboardText())
            if (entry == null) {
                flash("Нечего отменять")
                return@launch
            }
            UndoStack.remove(entry)
            paste(entry.before)
            flash("Отменено")
        }
    }

    /** Сброс: убить всё, что висит, - зеркало кнопки "Сброс" на телефоне. */
    fun reset() {
        app.claude.cancelActive()
        if (app.recorder.recording) app.recorder.stop()
        currentTake = null
        latched = false
        _state.value = UiState()
    }

    // ---- мелочи ----

    private suspend fun dictionaryHint(): String {
        val entries = app.dictionaryStore.all()
            .filter { it.enabled && it.mode != DictMode.PROTECT }
            .sortedByDescending { it.hits }
        val words = LinkedHashSet<String>()
        for (e in entries) {
            words += e.to.ifBlank { e.from }
        }
        val hint = StringBuilder()
        for (word in words) {
            if (hint.length + word.length + 2 > HINT_MAX_CHARS) break
            if (hint.isNotEmpty()) hint.append(", ")
            hint.append(word)
        }
        return hint.toString()
    }

    private fun paste(text: String) {
        Keyboard.paste(text)
        _state.value = UiState(title = "Готово")
        clearSoon()
    }

    private fun flash(message: String) {
        _state.value = UiState(title = message)
        clearSoon()
    }

    private fun fail(message: String) {
        _state.value = UiState(title = "Не вышло", error = message)
        clearSoon(4_000)
    }

    private fun clearSoon(afterMs: Long = 1_500) {
        val stamp = System.currentTimeMillis()
        lastFlashAt = stamp
        app.scope.launch {
            kotlinx.coroutines.delay(afterMs)
            // Плашка гаснет, только если за это время ничего нового не началось.
            if (lastFlashAt == stamp && _state.value.phase == Phase.IDLE) {
                _state.value = UiState()
            }
        }
    }

    @Volatile private var lastFlashAt = 0L
}
