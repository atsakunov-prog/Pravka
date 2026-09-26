package ru.zf.pravka.core

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.data.DataRoot
import ru.zf.pravka.data.MoneyStore
import ru.zf.pravka.data.StoreFiles

/**
 * Переразбор истории новой логикой — каждый шаг один раз (25.09.2026).
 *
 * Владелец: «много будет у нас изменений, которые затрагивают то, что уже
 * есть. Новые изменения в первый свой запуск смотрят на историю такого же и
 * переделывают правильно». Поправили разбор пушей — пуш, пойманный вчера,
 * лежит в журнале вчерашним разбором (так 195 000 внесения через банкомат
 * оставались бы доходом). Поэтому правка, которая меняет вывод уже
 * накопленных данных, приходит с ШАГОМ: он в первый запуск сборки проходит по
 * истории своего вида и выводит её заново из сырья.
 *
 * Правила шага:
 * - выводит заново только из СЫРЬЯ (текст пуша, надиктовка, строка выписки) —
 *   нет сырья, нечего и переразбирать;
 * - решения владельца (его категория, вычеркнутость, ответы, связи) не
 *   трогает; записи не удаляет;
 * - повторяем без вреда: упал посреди — следующий старт пройдёт его заново,
 *   но не больше [MAX_ATTEMPTS] раз: отметка «начал, попытка N» пишется ДО
 *   шага, поэтому и шаг, который роняет само приложение, не крутится вечно
 *   (владелец: «чтобы новенькие не застревали и не перебирали каждый раз, а
 *   только один раз»). Исчерпал попытки — «отложен», ждёт кнопки «Повторить»;
 * - у шага [TIMEOUT_MS] на всё: завис — считается упавшим;
 * - до шага — копия его файлов в `history-fixes/<шаг>/` базы;
 * - итог — словами: сколько просмотрено, сколько поправлено («Обновления и
 *   служба», журнал событий).
 *
 * Что переразбирать не нужно: категории по справочнику и правилам сверка и
 * так пересчитывает при каждом проходе (кроме решений владельца), поэтому
 * новое правило ложится на старое само. Шаг нужен, когда меняется то, что
 * лежит в записи готовым, — поля разбора.
 *
 * Новый шаг — строкой в [STEPS]: номер с датой, название по-человечески,
 * файлы для копии и сама работа. Номер не меняется никогда: по нему видно,
 * что шаг пройден.
 */
internal object HistoryFixes {

    class Result(val looked: Int, val changed: Int, val note: String = "")

    class Step(
        val id: String,
        val title: String,
        /** Файлы базы, которые шаг может переписать: копия до шага. */
        val files: List<String>,
        val run: suspend (PravkaApp) -> Result,
    )

    /** Пройденный (или упавший, или оборванный) шаг — для экрана и чтобы не повторять. */
    data class Done(
        val id: String,
        val title: String,
        val at: Long,
        val looked: Int,
        val changed: Int,
        val note: String,
        val error: String,
        /** Сколько раз шаг начинали. */
        val attempts: Int = 1,
        /** Начат и не закончен: процесс умер посреди шага (или идёт прямо сейчас). */
        val running: Boolean = false,
    ) {
        val ok: Boolean get() = error.isEmpty() && !running
        /** Попытки кончились: сам больше не запустится, только кнопкой. */
        val gaveUp: Boolean get() = !ok && attempts >= MAX_ATTEMPTS
    }

    /** Сколько раз шаг пробуют сами, прежде чем отложить до кнопки. */
    const val MAX_ATTEMPTS = 3
    /** Сколько шаг может идти: переразбор пушей — секунды, пять минут — с запасом на тысячи записей. */
    const val TIMEOUT_MS = 5 * 60_000L

    /** Один проход на процесс: второй вызов, пока идёт первый, ничего не делает. */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    val STEPS: List<Step> = listOf(
        // Пуш о внесении через банкомат: место — второй строкой («Банкомат.»),
        // разбор читал только первую, и внесение ложилось доходом.
        Step(
            id = "2026-09-25-push-detail-line",
            title = "Пуши Т-Банка: место операции со второй строки (внесение через банкомат)",
            files = listOf(MoneyStore.FILE_NAME),
        ) { app ->
            val o = app.moneyEngine.reparsePushes()
            Result(o.looked, o.changed + o.added, if (o.added > 0) "добавлено записей: ${o.added}" else "")
        },
        // Тот же переразбор после второй правки: заголовок, повторяющий само
        // действие («Пополнение»), — не место операции. Первый шаг на телефоне
        // уже отмечен пройденным, поэтому правка приходит своим шагом.
        Step(
            id = "2026-09-25-push-generic-title",
            title = "Пуши Т-Банка: заголовок «Пополнение» — не место операции",
            files = listOf(MoneyStore.FILE_NAME),
        ) { app ->
            val o = app.moneyEngine.reparsePushes()
            Result(o.looked, o.changed + o.added, if (o.added > 0) "добавлено записей: ${o.added}" else "")
        },
        // Настоящая причина: неразрывный пробел после точки («RUB.\u00A0Банкомат.»,
        // «*0292.\u00A0Диана Т.», «Доступно\u00A0…») — терялись место, получатель
        // перевода и остаток. Разбор пробелы теперь нормализует — переразбор всего.
        Step(
            id = "2026-09-25-push-nbsp",
            title = "Пуши Т-Банка: неразрывные пробелы — место, получатель перевода, остаток",
            files = listOf(MoneyStore.FILE_NAME),
        ) { app ->
            val o = app.moneyEngine.reparsePushes()
            Result(o.looked, o.changed + o.added, if (o.added > 0) "добавлено записей: ${o.added}" else "")
        },
    )

    private const val FILE = "history-fixes.json"
    private const val SNAP_DIR = "history-fixes"

    fun done(context: Context): List<Done> {
        val f = File(DataRoot.dir(context), FILE)
        return StoreFiles.readOrQuarantine(f) { text ->
            val a = JSONArray(text)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Done(
                    o.optString("id"), o.optString("title"), o.optLong("at"), o.optInt("looked"),
                    o.optInt("changed"), o.optString("note"), o.optString("error"),
                    // Записи первой версии (без попыток) — одна попытка, законченная.
                    o.optInt("attempts", 1), o.optBoolean("running", false),
                )
            }
        } ?: emptyList()
    }

    private fun save(context: Context, list: List<Done>) {
        val a = JSONArray()
        for (d in list) a.put(
            JSONObject().put("id", d.id).put("title", d.title).put("at", d.at).put("looked", d.looked)
                .put("changed", d.changed).put("note", d.note).put("error", d.error)
                .put("attempts", d.attempts).put("running", d.running)
        )
        StoreFiles.writeAtomic(File(DataRoot.dir(context), FILE), a.toString(2))
    }

    /**
     * Какие шаги запустить: не пройденные и с оставшимися попытками. Пройденный
     * — никогда больше; упавший или оборванный — пока попыток меньше
     * [MAX_ATTEMPTS]. Чистая — под тестом.
     */
    fun pending(steps: List<Step>, done: List<Done>): List<Step> {
        val byId = done.associateBy { it.id }
        return steps.filter { s -> byId[s.id].let { d -> d == null || (!d.ok && !d.gaveUp) } }
    }

    /** Отложенные: попытки кончились, ждут кнопки «Повторить». */
    fun gaveUp(steps: List<Step>, done: List<Done>): List<Step> {
        val byId = done.associateBy { it.id }
        return steps.filter { s -> byId[s.id]?.gaveUp == true }
    }

    private fun put(context: Context, record: Done) {
        save(context, done(context).filter { it.id != record.id } + record)
    }

    /** На старте процесса, на фоне: пройти всё непройденное по порядку. */
    suspend fun runPending(app: PravkaApp, log: (String) -> Unit) {
        // База недоступна — сторы пусты не потому, что пусты: переразбирать
        // нечего, и отметку «пройдено» ставить нельзя.
        if (DataRoot.where.value == DataRoot.Where.FOLDER_NO_ACCESS) return
        if (!busy.compareAndSet(false, true)) return
        try {
            val todo = pending(STEPS, done(app))
            for (step in todo) runOne(app, step, log)
        } finally {
            busy.set(false)
        }
    }

    private suspend fun runOne(app: PravkaApp, step: Step, log: (String) -> Unit) {
        val prev = done(app).firstOrNull { it.id == step.id }
        if (prev?.running == true) {
            log("переразбор: ${step.title} — прошлая попытка оборвалась посреди шага")
        }
        val attempt = (prev?.attempts ?: 0) + 1
        val now = System.currentTimeMillis()
        // Отметка ДО шага. Не записалась (нет места, нет доступа) — шаг не идёт
        // вовсе: без отметки его нельзя было бы остановить, если он роняет
        // приложение.
        put(app, Done(step.id, step.title, now, 0, 0, "", "", attempt, running = true))
        snapshot(app, step)
        val record = try {
            val r = kotlinx.coroutines.withTimeout(TIMEOUT_MS) { step.run(app) }
            log("переразбор: ${step.title} — просмотрено ${r.looked}, поправлено ${r.changed}" +
                if (r.note.isNotBlank()) ", ${r.note}" else "")
            Done(step.id, step.title, now, r.looked, r.changed, r.note, "", attempt)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            failed(step, now, attempt, "не уложился в ${TIMEOUT_MS / 60_000} минут", log)
        } catch (e: Throwable) {
            failed(step, now, attempt, "${e.javaClass.simpleName}: ${e.message}", log)
        }
        put(app, record)
    }

    private fun failed(step: Step, at: Long, attempt: Int, why: String, log: (String) -> Unit): Done {
        log(
            "переразбор: ${step.title} — упал ($why), " +
                if (attempt >= MAX_ATTEMPTS) "попытки кончились — отложен до кнопки «Повторить»"
                else "попытка $attempt из $MAX_ATTEMPTS, повторю на следующем запуске"
        )
        return Done(step.id, step.title, at, 0, 0, "", why, attempt)
    }

    /** Кнопка «Повторить» у отложенного шага: попытки с нуля — и сразу в работу. */
    suspend fun retry(app: PravkaApp, id: String, log: (String) -> Unit) {
        val d = done(app).firstOrNull { it.id == id } ?: return
        put(app, d.copy(attempts = 0, running = false))
        runPending(app, log)
    }

    /** Копия файлов шага до него — прежнее состояние всегда можно достать. */
    private fun snapshot(context: Context, step: Step) {
        val root = DataRoot.dir(context)
        val dir = File(root, "$SNAP_DIR/${step.id}")
        for (name in step.files) {
            val src = File(root, name)
            if (!src.isFile) continue
            runCatching {
                dir.mkdirs()
                val dst = File(dir, name)
                if (!dst.exists()) src.copyTo(dst)
            }
        }
    }
}
