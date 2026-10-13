package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.ZasechkaWeb

/**
 * Команды Засечки из веба (14.10.2026; `docs/zasechka.md` «Команды из веба», контракт
 * `server/contract/dela-zasechka.json`): «Начать», «Стоп», «Поправить», нажатые в вебе Дел на компьютере. Сервер
 * отдаёт их полем `zasechka` синка Дел (`DelaSync.onZasechka`) — синк идёт на пятиминутном тике и после каждой
 * правки, отдельной дороги не нужно.
 *
 * По каждой команде: взять (`zasechka.take` — одна команда одному телефону: два телефона владельца не заведут одно
 * дело дважды), решить (`core/ZasechkaWeb.plan`), применить тем же путём, что кнопки телефона
 * (`ZasechkaStore.startEntry`, `closeOpen`, `update` — с шагом отмены и нормализацией), запомнить у себя и ответить
 * (`zasechka.done`: сделал или почему нет). Лента поменялась — архив получит день сам: на ленту подписан
 * `ArchiveSync` (`PravkaApp.watchArchive`), и веб увидит правку оттуда.
 *
 * Память — `zasechka-web.json` в папке базы (`DataRoot.dir`, железное правило 1): что уже применено и что ответили.
 * Обрыв связи между «применил» и «ответил» — следующий синк ответит из памяти, а не применит второй раз; память
 * пропала — второй раз не заведёт тоже: `ZasechkaWeb.plan` узнаёт уже сделанное по самой ленте (`Plan.Already`).
 * Имя телефона для `take` — своё, в закрытой памяти установки (`DataRoot.secrets`): с копией базы на другой
 * телефон оно не уезжает, иначе два телефона считали бы себя одним.
 */
internal class ZasechkaWebCommands(
    private val context: Context,
    private val store: ZasechkaStore,
    /** `POST /api/ops` мимо очереди (`DelaSync.rawOps`): ответ нужен сейчас, повтор безвреден. */
    private val ops: suspend (JSONArray) -> JSONArray,
    /** Засечка включена в профиле: без неё телефон команд не берёт — их возьмёт тот, у кого она есть. */
    private val enabled: suspend () -> Boolean,
    private val log: (String) -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    private val file: File get() = File(DataRoot.dir(context), FILE)
    private val deviceFile: File get() = File(DataRoot.secrets(context), DEVICE_FILE)

    /** Ответ по команде, который уже дан (или ещё не дошёл): id → ok, note, entry. */
    private data class Done(val ok: Boolean, val note: String, val entry: Long, val at: Long)

    private val done = LinkedHashMap<String, Done>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val o = StoreFiles.readOrQuarantine(file) { JSONObject(it) } ?: return
        val m = o.optJSONObject("done") ?: return
        for (k in m.keys()) {
            val d = m.optJSONObject(k) ?: continue
            done[k] = Done(d.optBoolean("ok"), d.optString("note"), d.optLong("entry"), d.optLong("at"))
        }
    }

    @Synchronized
    private fun remember(id: String, d: Done) {
        load()
        done[id] = d
        // Сервер держит команду полсуток — памяти на пару сотен хватает с запасом.
        while (done.size > KEEP) done.remove(done.keys.first())
        val m = JSONObject()
        for ((k, v) in done) m.put(k, JSONObject().put("ok", v.ok).put("note", v.note).put("entry", v.entry).put("at", v.at))
        StoreFiles.writeAtomic(file, JSONObject().put("v", 1).put("done", m).toString())
    }

    @Synchronized
    private fun known(id: String): Done? {
        load()
        return done[id]
    }

    /** Имя этого телефона для `take`: модель и шесть знаков, своё у установки. */
    fun device(): String {
        runCatching { deviceFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val model = android.os.Build.MODEL.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "phone" }
        val id = model.take(24) + "-" + java.util.UUID.randomUUID().toString().replace("-", "").take(6)
        runCatching { StoreFiles.writeAtomic(deviceFile, id) }
        return id
    }

    /** Поле `zasechka` синка Дел: команды по порядку нажатия. Ошибка одной не останавливает остальные. */
    suspend fun onSync(cmds: JSONArray) {
        val list = ZasechkaWeb.parse(cmds)
        if (list.isEmpty()) return
        if (!enabled()) return
        val me = device()
        for (c in list) {
            if (c.takenBy.isNotBlank() && c.takenBy != me) continue
            runCatching { handle(c, me) }.onFailure { e -> log("засечка из веба: ${c.kind} — ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private suspend fun handle(c: ZasechkaWeb.Cmd, me: String) {
        // Уже применена — только ответить ещё раз (прошлый ответ не дошёл).
        known(c.id)?.let { d -> answer(c, me, d); return }
        val taken = ops(JSONArray().put(JSONObject().put("op", "zasechka.take").put("id", c.id).put("device", me))).optJSONObject(0)
        if (taken == null || !taken.optBoolean("ok")) {
            log("засечка из веба: ${c.kind} не взята — ${taken?.optString("error").orEmpty()}")
            return
        }
        val now = System.currentTimeMillis()
        val z = zone()
        val plan = ZasechkaWeb.plan(c, store.all(), store.categories().map { it.name }, now, z)
        val entry = when (plan) {
            is ZasechkaWeb.Plan.Start -> store.startEntry(
                start = plan.at, raw = plan.title, title = plan.title, category = plan.category, client = plan.client,
                useful = 0, source = if (plan.task.isNotBlank()) "task" else "text", task = plan.task, project = plan.project,
            ).id
            is ZasechkaWeb.Plan.Stop -> store.closeOpen(plan.at)?.id ?: plan.entryId
            is ZasechkaWeb.Plan.Edit -> { store.update(plan.entry); plan.entry.id }
            is ZasechkaWeb.Plan.Already -> plan.entryId
            is ZasechkaWeb.Plan.Refuse -> 0L
        }
        val d = Done(ok = plan !is ZasechkaWeb.Plan.Refuse, note = ZasechkaWeb.note(plan, z), entry = entry, at = now)
        remember(c.id, d)
        log("засечка из веба: ${c.kind} — ${d.note}")
        answer(c, me, d)
    }

    private suspend fun answer(c: ZasechkaWeb.Cmd, me: String, d: Done) {
        val op = JSONObject().put("op", "zasechka.done").put("id", c.id).put("device", me).put("ok", d.ok).put("note", d.note)
        if (d.entry > 0) op.put("entry_id", d.entry)
        val r = ops(JSONArray().put(op)).optJSONObject(0)
        if (r != null && !r.optBoolean("ok")) log("засечка из веба: ответ не принят — ${r.optString("error")}")
    }

    companion object {
        const val FILE = "zasechka-web.json"
        const val DEVICE_FILE = "zasechka-web-device.txt"
        private const val KEEP = 300
    }
}
