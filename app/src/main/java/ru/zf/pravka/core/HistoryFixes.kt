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
 * - повторяем без вреда: упал посреди — следующий старт пройдёт его заново;
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

    /** Пройденный (или упавший) шаг — для экрана и чтобы не повторять. */
    class Done(
        val id: String,
        val title: String,
        val at: Long,
        val looked: Int,
        val changed: Int,
        val note: String,
        val error: String,
    ) {
        val ok: Boolean get() = error.isEmpty()
    }

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
                )
            }
        } ?: emptyList()
    }

    private fun save(context: Context, list: List<Done>) {
        val a = JSONArray()
        for (d in list) a.put(
            JSONObject().put("id", d.id).put("title", d.title).put("at", d.at).put("looked", d.looked)
                .put("changed", d.changed).put("note", d.note).put("error", d.error)
        )
        StoreFiles.writeAtomic(File(DataRoot.dir(context), FILE), a.toString(2))
    }

    /** Какие шаги ещё не пройдены (упавшие — тоже: их пробуем снова). Чистая — под тестом. */
    fun pending(steps: List<Step>, done: List<Done>): List<Step> {
        val ok = done.filter { it.ok }.map { it.id }.toSet()
        return steps.filter { it.id !in ok }
    }

    /** На старте процесса, на фоне: пройти всё непройденное по порядку. */
    suspend fun runPending(app: PravkaApp, log: (String) -> Unit) {
        // База недоступна — сторы пусты не потому, что пусты: переразбирать
        // нечего, и отметку «пройдено» ставить нельзя.
        if (DataRoot.where.value == DataRoot.Where.FOLDER_NO_ACCESS) return
        val todo = pending(STEPS, done(app))
        if (todo.isEmpty()) return
        for (step in todo) {
            snapshot(app, step)
            val now = System.currentTimeMillis()
            val record = runCatching { step.run(app) }.fold(
                onSuccess = { r ->
                    log("переразбор: ${step.title} — просмотрено ${r.looked}, поправлено ${r.changed}" +
                        if (r.note.isNotBlank()) ", ${r.note}" else "")
                    Done(step.id, step.title, now, r.looked, r.changed, r.note, "")
                },
                onFailure = { e ->
                    val why = "${e.javaClass.simpleName}: ${e.message}"
                    log("переразбор: ${step.title} — упал ($why), повторю на следующем запуске")
                    Done(step.id, step.title, now, 0, 0, "", why)
                },
            )
            // Прежняя запись о том же шаге (упавшая) заменяется свежей.
            save(app, done(app).filter { it.id != step.id } + record)
        }
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
