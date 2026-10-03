package ru.zf.pravka.core

import ru.zf.pravka.data.DelaStore
import ru.zf.pravka.data.DelaSync
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.RaznoskaRoutes
import ru.zf.pravka.data.RaznoskaStore
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.TodoistStore
import ru.zf.pravka.data.TodoistSync
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.splitTasks
import ru.zf.pravka.provider.splitTasksDela

// Разноска: наговор → дела в Todoist или в Дела на домашнем сервере (выбор —
// «Подключения», settings.delaBackendFlow). Третий движок рядом с
// ProofreadEngine (текст в поле) и ZasechkaEngine (время в ленте).
//
// Дела на сервере (03.10.2026, docs/dela-server.md): отправка — это операция
// `task.create` в очередь Дел с id и op_id, выданными телефоном ещё при
// разборе; дальше очередь сама доводит её до сервера, повтор безвреден.
// «Не дела» уходят в хронологию заметками (`interaction.add`, kind = note).
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
    private val delaStore: DelaStore,
    private val delaSync: DelaSync,
    /** Режим «Дела» ходит в домашний сервер, а не в Todoist. */
    private val delaOn: suspend () -> Boolean,
) {

    companion object {
        // Сколько времени «↩︎ отменить» ещё имеет смысл: дальше владелец,
        // скорее всего, уже работал с задачей, и удалять её опасно.
        private const val UNDO_WINDOW_MS = 10 * 60_000L
        // Сколько ждать сервер Дел после отправки, прежде чем сказать «в очереди».
        private const val DELA_WAIT_MS = 4_000L
    }

    /**
     * Итог отправки. [to] — куда («Todoist», «Дела»); [queued] — дел, которые
     * легли в очередь Дел, но сервер их ещё не видел (нет связи): они уйдут
     * сами, и владелец должен это прочитать словами, а не гадать.
     */
    data class SendOutcome(val created: Int, val failed: Int, val error: String, val to: String = "Todoist", val queued: Int = 0) {
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
        if (delaOn()) return splitDela(transcript)
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
        runCatching { stats.recordAux(split.costUsd, split.tokensIn, split.tokensOut, route = ru.zf.pravka.data.ModelRoute.RAZNOSKA.key) }
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

    /**
     * Разбор для Дел: справочник — из синка (проекты с алиасами, люди,
     * метки), проект и человек — сразу id, у каждого дела свои id и op_id.
     */
    private suspend fun splitDela(transcript: String): Result<RaznoskaStore.Draft> {
        store.load()
        delaStore.load()
        routes.load()
        val snap = delaStore.view.value
        val prepared = dictionary.prepare(transcript)
        val result = claude.splitTasksDela(
            transcript = prepared.text,
            dictBlock = prepared.dictBlock,
            catalogBlock = listOf(Dela.promptCatalog(snap), routes.promptBlock())
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            snapshot = snap,
        )
        val split = result.getOrElse { e ->
            eventLog.add("разноска: разбор не вышел — ${e.message}")
            return Result.failure(e)
        }
        runCatching { dictionaryStore.incrementHits(prepared.firedIds) }
        runCatching { stats.recordAux(split.costUsd, split.tokensIn, split.tokensOut, route = ru.zf.pravka.data.ModelRoute.RAZNOSKA.key) }
        val open = snap.tasks.values.filter { it.open }.map { it.title to it.projectId }
        val tasks = split.tasks.map { task ->
            val dup = TaskMatcher.findDuplicate(task, open)
            if (dup == null) task else task.copy(duplicateOf = dup)
        }
        eventLog.add(
            "разноска → дела: ${transcript.length} зн. → дел ${tasks.size}, заметок ${split.noteItems.size}, " +
                String.format(java.util.Locale.US, "%.3f", split.costUsd) + " USD"
        )
        val draft = store.add(
            transcript = transcript,
            notes = split.notes,
            tasks = tasks,
            costUsd = split.costUsd,
            model = split.model,
            noteItems = split.noteItems,
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
        if (delaOn()) return sendDela(draftId, only)
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
     * Дела на сервере: дела — операциями `task.create` в очередь (на диск
     * сразу), заметки наговора — `interaction.add`. Отметка «отправлено»
     * ставится, как только очередь их взяла: дальше их доводит очередь, а
     * повтор с тем же op_id сервер не удваивает. Ждём ответа сервера
     * несколько секунд — чтобы честно сказать «записал» или «в очереди».
     */
    private suspend fun sendDela(draftId: Long, only: Set<Long>?): SendOutcome {
        store.load()
        delaStore.load()
        val draft = store.byId(draftId) ?: return SendOutcome(0, 0, "Наговор не найден", "Дела")
        val queue = draft.live.filter { !it.sent && (only == null || it.id in only) }
        val notes = if (draft.notesSent) emptyList() else draft.noteItems
        if (queue.isEmpty() && notes.isEmpty()) return SendOutcome(0, 0, "", "Дела")
        val ops = mutableListOf<org.json.JSONObject>()
        // Старый черновик (до Дел) ключей не имел — выдаём их здесь и запоминаем.
        val keyed = queue.map { t ->
            if (t.delaId.isNotBlank() && t.opId.isNotBlank()) t else t.copy(delaId = t.delaId.ifBlank { Dela.newId() }, opId = t.opId.ifBlank { Dela.newId() })
        }
        if (keyed != queue) store.replaceTasks(draftId, draft.tasks.map { t -> keyed.firstOrNull { it.id == t.id } ?: t })
        for (t in keyed) ops += Dela.createOp(asDela(t, draft.id), t.opId)
        val at = java.time.OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        for ((i, n) in notes.withIndex()) {
            ops += Dela.noteOp(
                id = n.id.ifBlank { Dela.newId() },
                text = n.text,
                atIso = at,
                projectId = n.projectId,
                personIds = n.personIds,
                sourceRef = "raznoska:${draft.id}:$i",
                opId = n.opId.ifBlank { Dela.stableId("razn-note-${draft.id}-$i") },
            )
        }
        delaStore.enqueue(ops)
        for (t in keyed) store.markSent(draft.id, t.id, t.delaId)
        if (notes.isNotEmpty()) store.markNotesSent(draft.id)
        store.setError(draft.id, "")
        if (keyed.isNotEmpty()) {
            val done = keyed.map { it.id }
            val previous = freshBatch()
            lastBatch = if (previous != null && previous.draftId == draftId) {
                Batch(draftId, previous.taskIds + done, System.currentTimeMillis())
            } else {
                Batch(draftId, done, System.currentTimeMillis())
            }
        }
        val delivered = runCatching { delaSync.pushNow(DELA_WAIT_MS) }.getOrDefault(false)
        val queued = if (delivered) 0 else keyed.count { delaStore.isQueued(it.opId) }
        eventLog.add(
            "разноска → дела: дел ${keyed.size}, заметок ${notes.size}" +
                if (queued > 0) ", в очереди $queued (${delaSync.status.value.lastError.ifBlank { "нет ответа" }})" else ", сервер принял"
        )
        return SendOutcome(keyed.size, 0, "", "Дела", queued)
    }

    /** Дело разбора — в дело Дел: то, что уедет в `task.create`. */
    private fun asDela(t: ParsedTask, draftId: Long): Dela.Task = Dela.Task(
        id = t.delaId,
        title = t.content.trim(),
        notes = t.description.trim(),
        projectId = t.projectId,
        ball = t.ball,
        personId = t.personId,
        dueDate = t.due,
        estimateMin = t.estimateMin,
        money = t.money,
        want = t.want,
        labels = t.labels,
        source = "voice",
        sourceRef = "raznoska:$draftId",
    )

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
        val dela = delaOn()
        for (taskId in batch.taskIds) {
            val task = draft.tasks.firstOrNull { it.id == taskId } ?: continue
            if (task.sentId.isBlank()) continue
            if (dela) {
                // Не дошло до сервера — просто снимаем из очереди; дошло —
                // отменяем операцией: строки на сервере не удаляются, дело
                // становится cancelled, и это видно в его журнале.
                if (!delaStore.dropQueued(task.opId)) {
                    delaStore.enqueue(listOf(Dela.statusOp("task.cancel", task.sentId, Dela.stableId("razn-undo-" + task.opId))))
                }
                deleted++
                // Новые ключи: повтор со старым op_id сервер ответил бы прежним
                // ответом — и дело осталось бы отменённым, а не создалось заново.
                store.load()
                store.byId(batch.draftId)?.let { cur ->
                    store.replaceTasks(batch.draftId, cur.tasks.map {
                        if (it.id == taskId) it.copy(sentId = "", delaId = Dela.newId(), opId = Dela.newId()) else it
                    })
                }
                continue
            }
            if (todoistSync.deleteTask(task.sentId)) {
                deleted++
                store.clearSent(batch.draftId, taskId)
            } else {
                failed++
            }
        }
        lastBatch = null
        if (dela && deleted > 0) delaSync.poke()
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
            before.priority != after.priority ||
            before.personId != after.personId || before.ball != after.ball
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
        if (delaOn()) {
            runCatching { delaStore.load() }
            runCatching { delaSync.tick() }
            return
        }
        runCatching { todoistStore.load() }
        runCatching { todoistSync.refresh(force = false) }
    }

    /** Куда сейчас уходят дела — словом для пилюли и тостов. */
    suspend fun target(): String = if (delaOn()) "Дела" else "Todoist"

    /** Проекты для выбора руками в редакторе. */
    fun projectPaths(): List<Pair<String, TodoistStore.Project>> = todoistStore.paths()
}
