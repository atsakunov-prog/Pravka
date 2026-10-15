package ru.zf.pravka.data

import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaDebug

/**
 * Режим отладки Дел на телефоне (15.10.2026, docs/dela-phone-24.md): записка о баге — операцией `debug.add` в
 * очередь Дел (без сети ляжет потом; op_id и id записки защищают от повтора), список записок — видом `debug` сервера.
 * Правила снимка и операции — `core/DelaDebug.kt`, экран — `DelaDebugUi.kt`.
 *
 * Включён ли режим — поле `debug` синка ([onSync], слушатель `DelaSync`); последнее, что сказал сервер, лежит в
 * `dela-debug.json` папки базы: вкладка без сети тоже знает, рисовать ли строку. Файл расходный — пропал, следующий
 * синк скажет заново.
 */
internal class DelaDebugSync(private val app: PravkaApp, private val log: (String) -> Unit) {

    private val file: File get() = File(DataRoot.dir(app), FILE)

    private val _info = MutableStateFlow(read())
    /** Поле `debug` последнего синка; null — сервер его не присылал (старый сервер или не владелец). */
    val info: StateFlow<DelaDebug.Info?> = _info

    private fun read(): DelaDebug.Info? = runCatching {
        if (!file.isFile) null else DelaDebug.info(JSONObject().put("debug", JSONObject(file.readText())))
    }.getOrNull()

    /** Поле `debug` синка: нет поля — владелец ли сменился, сервер ли старый — строки нет. */
    suspend fun onSync(resp: JSONObject) {
        val i = DelaDebug.info(resp)
        if (i == _info.value) return
        _info.value = i
        withContext(Dispatchers.IO) {
            runCatching { if (i == null) file.delete() else StoreFiles.writeAtomic(file, DelaDebug.infoJson(i).toString()) }
                .onFailure { e -> log("отладка: ${file.name} не записался — ${e.javaClass.simpleName}") }
        }
    }

    /** Лист «Отладка» открыт: его открывают номер в строке и пункт боковины, рисует `DelaDebugHost` вкладки. */
    val listOpen = MutableStateFlow(false)

    /** Строку рисовать: режим включён и сервер знает флаг (поле приходит только владельцу). */
    fun on(snap: Dela.Snapshot): Boolean = _info.value?.on == true && DelaDebug.FEATURE in snap.features

    /**
     * Записать: в очередь и сразу на сервер (до 4 с). Ответ — что сказать тостом: «🐞 №12 записал», без связи — что
     * уйдёт потом, отказ сервера — его словами.
     */
    suspend fun send(op: JSONObject): String {
        val id = op.optJSONObject("note")?.optString("id").orEmpty()
        app.delaStore.enqueue(listOf(op))
        val sent = app.delaSync.pushNow(4_000L)
        app.delaStore.noticesFor(listOf(op)).firstOrNull { it.error }?.let { return it.text }
        if (!sent || app.delaStore.isQueued(op.optString("op_id"))) return "🐞 Записал — уйдёт на сервер, когда будет связь"
        // Номер даёт сервер: очередь ответ не хранит — спрашиваем записку по её id.
        val num = runCatching { app.delaSync.rawView("debug?id=$id").optJSONObject("note")?.optInt("num") ?: 0 }.getOrDefault(0)
        _info.value = _info.value?.let { it.copy(new = it.new + 1, last = maxOf(it.last, num)) }
        return if (num > 0) "🐞 №$num записал" else "🐞 Записал"
    }

    /** Список записок (`status`: open — «новое» и «разбираю», all — все) — свежие сверху. */
    suspend fun list(status: String): Result<List<DelaDebug.Note>> = runCatching {
        val q = if (status == "all") "debug?limit=200" else "debug?limit=200&status=$status"
        DelaDebug.notes(app.delaSync.rawView(q))
    }

    /** Статус записки — сразу на сервер (из очереди тоже уйдёт, если связи нет). */
    suspend fun setStatus(id: String, status: String): String =
        app.delaSend(listOf(DelaDebug.setOp(Dela.newId(), id, status = status)), "Статус: ${DelaDebug.STATUS[status] ?: status}")

    /** Включить или выключить строку (настройка `debug` владельца на сервере — общая с вебом). */
    suspend fun setOn(on: Boolean): String {
        _info.value = (_info.value ?: DelaDebug.Info(on)).copy(on = on)
        return app.delaSend(
            listOf(JSONObject().put("op_id", Dela.newId()).put("op", "user.settings").put("settings", JSONObject().put("debug", on))),
            if (on) "Режим отладки включён" else "Режим отладки выключен — записки целы",
        )
    }

    /** Последние строки журнала приложения — с диска, не на главном потоке. */
    suspend fun events(): List<String> = withContext(Dispatchers.IO) { app.eventLog.readLast(DelaDebug.EVENTS) }

    fun now(): OffsetDateTime = OffsetDateTime.now()

    companion object {
        const val FILE = "dela-debug.json"
    }
}
