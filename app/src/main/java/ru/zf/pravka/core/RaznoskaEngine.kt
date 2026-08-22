package ru.zf.pravka.core

import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.RaznoskaStore
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.TodoistStore
import ru.zf.pravka.data.TodoistSync
import ru.zf.pravka.provider.ClaudeProvider

// Разноска: наговор → дела в Todoist. Третий движок рядом с ProofreadEngine
// (текст в поле) и ZasechkaEngine (время в ленте).
//
// Два шага, и они нарочно раздельные: РАЗБОР (Опус превращает наговор в
// список дел) и ОТПРАВКА (дела уезжают в Todoist). Между ними стоит владелец:
// смотрит плашку, правит в приложении, жмёт ОК. Разобранное лежит на диске с
// первой секунды, поэтому между шагами можно потерять сеть, приложение или
// день - дела дождутся.
class RaznoskaEngine(
    private val claude: ClaudeProvider,
    private val dictionary: DictionaryApplier,
    private val dictionaryStore: DictionaryStore,
    private val store: RaznoskaStore,
    private val todoistStore: TodoistStore,
    private val todoistSync: TodoistSync,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    data class SendOutcome(val created: Int, val failed: Int, val error: String) {
        val ok: Boolean get() = failed == 0 && created > 0
    }

    /**
     * Наговор → разобранный черновик на диске. Каталог проектов обновляется
     * заранее (кнопка зовёт [warmCatalog] на старте записи), но и здесь есть
     * страховка: без проектов модель просто оставит поле пустым, а владелец
     * выберет руками - разбор из-за этого не срывается.
     */
    suspend fun split(rawTranscript: String): Result<RaznoskaStore.Draft> {
        val transcript = rawTranscript.trim()
        if (transcript.isBlank()) return Result.failure(IllegalArgumentException("Пустой наговор"))
        store.load()
        todoistStore.load()
        // Словарь чинит услышанные имена ДО модели (HARD) и подсказывает
        // остальное блоком {DICT} - те же правила, что у Правки.
        val prepared = dictionary.prepare(transcript)
        val result = claude.splitTasks(
            transcript = prepared.text,
            dictBlock = prepared.dictBlock,
            catalogBlock = todoistStore.catalogPromptBlock(),
            knownLabels = todoistStore.labelsFlow.value,
            resolveProject = { named ->
                todoistStore.resolveProject(named)?.let { p -> p.id to todoistStore.path(p) }
            },
        )
        val split = result.getOrElse { e ->
            eventLog.add("разноска: разбор не вышел — ${e.message}")
            return Result.failure(e)
        }
        runCatching { dictionaryStore.incrementHits(prepared.firedIds) }
        // Опус считается в те же счётчики, что и всё остальное.
        runCatching { stats.recordAux(split.costUsd, split.tokensIn, split.tokensOut) }
        val open = todoistStore.tasksFlow.value.map { it.content to it.projectId }
        val tasks = split.tasks.map { task ->
            val dup = TaskMatcher.findDuplicate(task, open)
            if (dup == null) task else task.copy(duplicateOf = dup)
        }
        val spent = String.format(java.util.Locale.US, "%.3f", split.costUsd)
        eventLog.add(
            "разноска: ${transcript.length} зн. → дел ${tasks.size}" +
                (if (split.notes.isNotBlank()) ", есть заметки" else "") +
                ", " + spent + " USD"
        )
        val draft = store.add(
            transcript = transcript,
            notes = split.notes,
            tasks = tasks,
            costUsd = split.costUsd,
            model = split.model,
        )
        return Result.success(draft)
    }

    /** Тот же наговор — заново на разбор (модель ошиблась, промпт поправлен). */
    suspend fun resplit(draftId: Long): Result<RaznoskaStore.Draft> {
        val draft = store.byId(draftId)
            ?: return Result.failure(IllegalStateException("Наговор не найден"))
        if (draft.transcript.isBlank()) {
            return Result.failure(IllegalStateException("Текста наговора нет — разбирать нечего"))
        }
        val fresh = split(draft.transcript).getOrElse { return Result.failure(it) }
        // Старый черновик уходит только когда новый уже на диске.
        if (!draft.anySent) store.delete(draft.id)
        return Result.success(fresh)
    }

    /**
     * Дела уезжают в Todoist по одному. Уже созданные пропускаются, а
     * X-Request-Id у каждого дела свой и постоянный, поэтому повтор после
     * таймаута не создаёт дублей.
     */
    suspend fun send(draftId: Long): SendOutcome {
        val draft = store.byId(draftId) ?: return SendOutcome(0, 0, "Наговор не найден")
        val queue = draft.live.filter { !it.sent }
        if (queue.isEmpty()) return SendOutcome(0, 0, "")
        var created = 0
        var failed = 0
        var error = ""
        for (task in queue) {
            val outcome = todoistSync.createTask(task, "razn-${draft.id}-${task.id}")
            outcome.onSuccess { id ->
                created++
                store.markSent(draft.id, task.id, id)
            }.onFailure { e ->
                failed++
                if (error.isBlank()) error = e.message ?: "не отправилось"
            }
        }
        store.setError(draft.id, error)
        if (created > 0) {
            // Свежесозданные дела должны появиться в списке вкладки, с
            // настоящими id - а не с нашими догадками.
            runCatching { todoistSync.refresh(force = true) }
        }
        eventLog.add("разноска: отправлено $created, не вышло $failed${if (error.isBlank()) "" else " ($error)"}")
        return SendOutcome(created, failed, error)
    }

    /** Пока владелец говорит, каталог проектов и меток успевает обновиться. */
    suspend fun warmCatalog() {
        runCatching { todoistStore.load() }
        runCatching { todoistSync.refresh(force = false) }
    }

    /** Проекты для выбора руками в редакторе. */
    fun projectPaths(): List<Pair<String, TodoistStore.Project>> = todoistStore.paths()
}
