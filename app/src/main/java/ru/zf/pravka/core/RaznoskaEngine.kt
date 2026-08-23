package ru.zf.pravka.core

import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.RaznoskaRoutes
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
    private val routes: RaznoskaRoutes,
    private val todoistStore: TodoistStore,
    private val todoistSync: TodoistSync,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    companion object {
        // Сколько времени «↩︎ отменить» ещё имеет смысл: дальше владелец,
        // скорее всего, уже работал с задачей, и удалять её опасно.
        private const val UNDO_WINDOW_MS = 10 * 60_000L
    }

    data class SendOutcome(val created: Int, val failed: Int, val error: String) {
        val ok: Boolean get() = failed == 0 && created > 0
    }

    data class UndoOutcome(val deleted: Int, val failed: Int, val draftId: Long)

    // Последняя отправка - в памяти: если служба перезапустилась, отменять
    // уже поздно, а отметки «создано» на диске остаются честными.
    private class Batch(val draftId: Long, val taskIds: List<Long>, val at: Long)

    @Volatile private var lastBatch: Batch? = null

    private fun freshBatch(): Batch? =
        lastBatch?.takeIf { System.currentTimeMillis() - it.at < UNDO_WINDOW_MS && it.taskIds.isNotEmpty() }

    /** Есть ли что отменять прямо сейчас (для меню кнопки). */
    fun undoAvailable(): Boolean = freshBatch() != null

    fun undoCount(): Int = freshBatch()?.taskIds?.size ?: 0

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
        routes.load()
        // Словарь чинит услышанные имена ДО модели (HARD) и подсказывает
        // остальное блоком {DICT} - те же правила, что у Правки.
        val prepared = dictionary.prepare(transcript)
        val result = claude.splitTasks(
            transcript = prepared.text,
            dictBlock = prepared.dictBlock,
            // Каталог и поправки владельца едут одним куском: так они
            // попадают в промпт даже если он переписал шаблон и потерял
            // отдельный плейсхолдер.
            catalogBlock = listOf(todoistStore.catalogPromptBlock(), routes.promptBlock())
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
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
    suspend fun send(draftId: Long): SendOutcome = sendTasks(draftId, null)

    /** «ОК» на плашке: уезжают только отмеченные дела. */
    suspend fun sendOnly(draftId: Long, taskIds: Collection<Long>): SendOutcome =
        if (taskIds.isEmpty()) SendOutcome(0, 0, "") else sendTasks(draftId, taskIds.toSet())

    private suspend fun sendTasks(draftId: Long, only: Set<Long>?): SendOutcome {
        val draft = store.byId(draftId) ?: return SendOutcome(0, 0, "Наговор не найден")
        val queue = draft.live.filter { !it.sent && (only == null || it.id in only) }
        if (queue.isEmpty()) return SendOutcome(0, 0, "")
        var created = 0
        var failed = 0
        var error = ""
        val done = mutableListOf<Long>()
        for (task in queue) {
            val outcome = todoistSync.createTask(task, "razn-${draft.id}-${task.id}")
            outcome.onSuccess { id ->
                created++
                done.add(task.id)
                store.markSent(draft.id, task.id, id)
            }.onFailure { e ->
                failed++
                if (error.isBlank()) error = e.message ?: "не отправилось"
            }
        }
        // Одна отправка - одна отмена. Дела, отправленные по одному, тоже
        // копятся в один пакет, пока окно отмены не истекло.
        if (done.isNotEmpty()) {
            val previous = freshBatch()
            lastBatch = if (previous != null && previous.draftId == draftId) {
                Batch(draftId, previous.taskIds + done, System.currentTimeMillis())
            } else {
                Batch(draftId, done, System.currentTimeMillis())
            }
        }
        store.setError(draft.id, error)
        if (created > 0) {
            // Свежесозданные дела должны появиться в списке вкладки, с
            // настоящими id - а не с нашими догадками.
            runCatching { todoistSync.refresh(force = true) }
        }
        eventLog.add(
            "разноска: отправлено $created, не вышло $failed" +
                (if (error.isBlank()) "" else " ($error)")
        )
        return SendOutcome(created, failed, error)
    }

    /**
     * Правка формулировки прямо на плашке. Пустой текст = владелец вычеркнул
     * дело: оно остаётся в записи, но в Todoist не поедет.
     */
    suspend fun editText(draftId: Long, taskId: Long, text: String) {
        val draft = store.byId(draftId) ?: return
        val trimmed = text.trim()
        store.replaceTasks(
            draftId,
            draft.tasks.map { task ->
                when {
                    task.id != taskId -> task
                    trimmed.isEmpty() -> task.copy(dropped = true)
                    else -> task.copy(content = trimmed)
                }
            },
        )
    }

    /**
     * «↩︎ Отменить отправку»: только что созданные дела удаляются из Todoist
     * и снова ждут во вкладке. За окном отмены (10 минут) ничего не делаем -
     * задачу могли уже начать.
     */
    suspend fun undoLast(): UndoOutcome {
        val batch = freshBatch() ?: return UndoOutcome(0, 0, 0L)
        val draft = store.byId(batch.draftId) ?: run {
            lastBatch = null
            return UndoOutcome(0, 0, 0L)
        }
        var deleted = 0
        var failed = 0
        for (taskId in batch.taskIds) {
            val task = draft.tasks.firstOrNull { it.id == taskId } ?: continue
            if (task.sentId.isBlank()) continue
            if (todoistSync.deleteTask(task.sentId)) {
                deleted++
                store.clearSent(batch.draftId, taskId)
            } else {
                failed++
            }
        }
        lastBatch = null
        eventLog.add("разноска: отмена отправки — удалено $deleted, не вышло $failed")
        return UndoOutcome(deleted, failed, batch.draftId)
    }

    /**
     * Владелец переложил дело руками - запоминаем маршрут. Учимся только на
     * раскладке (проект, метки, приоритет): именно в ней модель ошибается
     * системно, а формулировку она берёт из его же слов.
     */
    suspend fun learnRoute(before: ParsedTask, after: ParsedTask) {
        val moved = before.projectId != after.projectId ||
            before.labels.toSet() != after.labels.toSet() ||
            before.priority != after.priority
        if (!moved) return
        routes.load()
        routes.remember(
            text = after.content,
            project = after.projectName,
            labels = after.labels,
            priority = after.priority,
        )
        eventLog.add("разноска: маршрут запомнен — «${after.content.take(60)}» → ${after.projectName}")
    }

    /** Пока владелец говорит, каталог проектов и меток успевает обновиться. */
    suspend fun warmCatalog() {
        runCatching { todoistStore.load() }
        runCatching { todoistSync.refresh(force = false) }
    }

    /** Проекты для выбора руками в редакторе. */
    fun projectPaths(): List<Pair<String, TodoistStore.Project>> = todoistStore.paths()
}
