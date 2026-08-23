package ru.zf.pravka.data

import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Автогалочки в базу «Дневник» Notion.
//
// Дневник владелец завёл сам: строка на день, галочка зарядки, галочка
// тренировки, feel, колено, вес, еда. И сам же его забросил — последняя
// заполненная строка осталась в июле, потому что всё то же самое он уже
// наговаривает Правке, а тикать второй раз руками никто не будет. База при
// этом жива и нужна: её читает его Клод в чате.
//
// Поэтому единственное место, где приложение ПИШЕТ в Notion. Правила записи
// жёстче, чем у чтения, потому что база — его, а мы в ней гости:
//
//   - трогаем ТОЛЬКО свои колонки: Зарядка, Сделано, Feel, Колено, Вес, Еда,
//     Заметки. «План» и заголовок «День» — его текст, к ним не прикасаемся
//     (заголовок пишем только когда сами создаём строку);
//   - галочки только СТАВИМ и никогда не снимаем: снятая галочка может быть
//     его правкой, а спорить с хозяином базы нельзя;
//   - «Еда» и «Заметки» пишутся только В ПУСТУЮ ячейку: если он написал там
//     своё, наша арифметика не важнее его слов;
//   - строка на день обычно уже есть (он создаёт их заранее вместе с планом) —
//     тогда обновляем её; нет — создаём с заголовком «23.08 · Вс», как у него.
class NotionDiarySync(
    private val settings: Settings,
    private val strengthStore: StrengthStore,
    private val planStore: PlanStore,
    private val foodStore: FoodStore,
    private val sportStore: SportStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val API = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        // База «Дневник» — под хабом «Тело». Id зашит, как id хаба: база одна
        // и навсегда, а лишнее поле в настройках — лишний способ сломать.
        private const val DATABASE_ID = "8bfddd3d6a9f49628c9feddbe4abc737"
        // Раз в полчаса достаточно: галочка не скиснет, а Notion — чужой API.
        private const val PERIOD_MS = 30 * 60_000L

        private val FEEL_NAMES = mapOf(
            1 to "1 · отлично",
            2 to "2 · хорошо",
            3 to "3 · норм",
            4 to "4 · тяжело",
            5 to "5 · развалина",
        )
        private val WEEKDAYS = listOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
    }

    @Volatile private var lastRun = 0L
    @Volatile private var lastError = ""
    @Volatile private var lastPushed = ""
    // Что уже уехало по каждому дню: одинаковый снимок второй раз не шлём.
    private val pushedHash = HashMap<String, Int>()

    fun lastError(): String = lastError
    fun lastPushed(): String = lastPushed

    /**
     * Донести сегодняшний день (и вчерашний до полудня: вечерние подходы и
     * поздний ужин дописываются после полуночи). Сам себя дросселирует —
     * зовётся из пятиминутного тика службы.
     */
    suspend fun sync(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < PERIOD_MS) return false
        if (!settings.notionDiary()) return false
        val token = settings.notionToken().trim()
        if (token.isBlank()) return false
        lastRun = now

        strengthStore.load()
        planStore.load()
        foodStore.load()
        sportStore.load()

        val today = dayKey(now)
        val dates = buildList {
            add(today)
            if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12) add(dayBefore(today))
        }
        var pushedAny = false
        for (date in dates) {
            val snapshot = snapshotOf(date) ?: continue
            if (pushedHash[date] == snapshot.hash && !force) continue
            val ok = withContext(Dispatchers.IO) { push(date, snapshot, token) }
            if (ok) {
                pushedHash[date] = snapshot.hash
                lastPushed = date
                pushedAny = true
            } else {
                // Причина уже в lastError; дальше не долбим — тик повторит.
                break
            }
        }
        return pushedAny
    }

    // ---- Что писать ----

    private class Snapshot(
        val charged: Boolean,
        val done: Boolean,
        val feel: Int,
        val knee: String,        // имя опции Notion или ""
        val weightKg: Double,
        val food: String,        // "" = нечего писать
        val notes: String,       // "" = нечего писать
        val hash: Int,
    )

    /** null — за день нет ни одной нашей отметки, и ходить в сеть незачем. */
    private fun snapshotOf(date: String): Snapshot? {
        val gtg = strengthStore.gtgOn(date)
        val sessions = strengthStore.sessionsOn(date)
        val session = sessions.firstOrNull { !it.empty } ?: sessions.firstOrNull()
        val workouts = sportStore.workoutsFlow.value.filter { dayKey(it.start) == date }

        val charged = gtg?.charged == true
        val knee = when {
            gtg == null || gtg.knee.isBlank() -> ""
            gtg.knee.startsWith("зел", true) -> "Зелёный · молчит"
            gtg.knee.startsWith("жёл", true) || gtg.knee.startsWith("жел", true) -> "Жёлтый · ноет"
            gtg.knee.startsWith("красн", true) -> "Красный · отёк или блок"
            else -> ""
        }

        // «Сделано» — про главную сессию плана. Силовая закрыта журналом или
        // кнопкой; бег и вело — фактом активности нужного типа с часов.
        val planned = planStore.mainOf(date)
        val done = when {
            session?.done == true || session?.empty == false -> true
            planned == null -> false
            planned.strength -> false   // силовая без журнала — не сделана
            planned.type.equals("Run", true) ->
                workouts.any { it.type.equals("Run", true) }
            planned.type.equals("Ride", true) || planned.type.equals("VirtualRide", true) ->
                workouts.any {
                    it.type.equals("Ride", true) || it.type.equals("VirtualRide", true)
                }
            else -> workouts.any { it.type.equals(planned.type, true) }
        }

        val feel = session?.feel?.takeIf { it in 1..5 }
            ?: workouts.firstOrNull { it.feel in 1..5 }?.feel ?: 0

        val weight = sportStore.healthOn(date)?.weightKg ?: 0.0

        val total = foodStore.dayTotal(date)
        val food = if (total.empty) ""
        else "${total.kcal} ккал · Б${total.protein} Ж${total.fat} У${total.carbs}"

        val notes = buildString {
            session?.let { s ->
                append(s.exercises.joinToString("; ") { "${it.name} ${it.compact()}" })
            }
            if (gtg != null && (gtg.hangSec > 0 || gtg.negatives > 0 || gtg.pullups > 0)) {
                if (isNotEmpty()) append(" · ")
                val bits = mutableListOf<String>()
                if (gtg.pullups > 0) bits.add("подтягивания ${gtg.pullups}")
                if (gtg.hangSec > 0) bits.add("вис ${gtg.hangSec} сек")
                if (gtg.negatives > 0) bits.add("негативы ${gtg.negatives}")
                append(bits.joinToString(", "))
            }
        }.trim()

        if (!charged && !done && feel == 0 && knee.isBlank() && weight <= 0 &&
            food.isBlank() && notes.isBlank()
        ) {
            return null
        }
        val hash = listOf(charged, done, feel, knee, weight, food, notes).hashCode()
        return Snapshot(charged, done, feel, knee, weight, food, notes, hash)
    }

    // ---- Notion REST ----

    private fun push(date: String, s: Snapshot, token: String): Boolean {
        val existing = findRow(date, token)
        if (existing == null && lastError.isNotBlank()) return false

        val props = JSONObject()
        // Галочки только ставим: снятая может быть его правкой.
        if (s.charged) props.put("Зарядка", JSONObject().put("checkbox", true))
        if (s.done) props.put("Сделано", JSONObject().put("checkbox", true))
        if (s.feel in 1..5) {
            props.put("Feel", JSONObject().put("select", JSONObject().put("name", FEEL_NAMES[s.feel])))
        }
        if (s.knee.isNotBlank()) {
            props.put("Колено", JSONObject().put("select", JSONObject().put("name", s.knee)))
        }
        if (s.weightKg > 0) props.put("Вес, кг", JSONObject().put("number", s.weightKg))
        // Текстовые — только в пустую ячейку: его слова важнее нашей арифметики.
        if (s.food.isNotBlank() && (existing == null || existing.foodEmpty)) {
            props.put("Еда", richText(s.food))
        }
        if (s.notes.isNotBlank() && (existing == null || existing.notesEmpty)) {
            props.put("Заметки", richText(s.notes))
        }
        if (props.length() == 0) return true

        val ok = if (existing != null) {
            val payload = JSONObject().put("properties", props)
            call("$API/pages/${existing.pageId}", token, payload.toString(), patch = true) != null
        } else {
            // Строки на день нет — создаём, как создал бы он: «23.08 · Вс».
            props.put("День", JSONObject().put("title", textArray(titleOf(date))))
            props.put("Дата", JSONObject().put("date", JSONObject().put("start", date)))
            val payload = JSONObject().apply {
                put("parent", JSONObject().put("database_id", DATABASE_ID))
                put("properties", props)
            }
            call("$API/pages", token, payload.toString(), patch = false) != null
        }
        if (ok) {
            lastError = ""
            eventLog.add("дневник → Notion: $date уехал (${if (existing != null) "обновил" else "создал"})")
        }
        return ok
    }

    private class Row(val pageId: String, val foodEmpty: Boolean, val notesEmpty: Boolean)

    /** Строка дня в базе, если она есть. null и пустой lastError = строки нет. */
    private fun findRow(date: String, token: String): Row? {
        lastError = ""
        val payload = JSONObject().apply {
            put("filter", JSONObject().apply {
                put("property", "Дата")
                put("date", JSONObject().put("equals", date))
            })
            put("page_size", 1)
        }
        val body = call("$API/databases/$DATABASE_ID/query", token, payload.toString(), patch = false)
            ?: return null
        val page = runCatching { JSONObject(body).optJSONArray("results")?.optJSONObject(0) }
            .getOrNull() ?: return null
        val props = page.optJSONObject("properties")
        fun textEmpty(name: String): Boolean {
            val array = props?.optJSONObject(name)?.optJSONArray("rich_text") ?: return true
            return array.length() == 0
        }
        return Row(
            pageId = page.optString("id"),
            foodEmpty = textEmpty("Еда"),
            notesEmpty = textEmpty("Заметки"),
        )
    }

    private fun titleOf(date: String): String {
        val parsed = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)
        }.getOrNull() ?: return date
        val cal = Calendar.getInstance().apply { time = parsed }
        val dd = String.format(java.util.Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))
        val mm = String.format(java.util.Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
        return "$dd.$mm · ${WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
    }

    private fun richText(text: String): JSONObject =
        JSONObject().put("rich_text", textArray(text))

    private fun textArray(text: String): JSONArray = JSONArray().apply {
        put(JSONObject().put("text", JSONObject().put("content", text.take(1900))))
    }

    private fun call(url: String, token: String, body: String, patch: Boolean): String? {
        val requestBody = body.toRequestBody(JSON_TYPE)
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", VERSION)
            .header("Content-Type", "application/json")
        val request = (if (patch) builder.patch(requestBody) else builder.post(requestBody)).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val hint = runCatching {
                        JSONObject(response.body?.string().orEmpty()).optString("message")
                    }.getOrNull().orEmpty()
                    lastError = when (response.code) {
                        401 -> "Notion не принял токен"
                        403 -> "Notion: интеграции нужны права на ЗАПИСЬ — " +
                            "My integrations → Capabilities → Insert/Update content"
                        404 -> "Notion: база «Дневник» не найдена или интеграцию к ней не пустили"
                        else -> "Notion: HTTP ${response.code}" +
                            if (hint.isNotBlank()) " — ${hint.take(120)}" else ""
                    }
                    eventLog.add("дневник → Notion: $lastError")
                    return null
                }
                response.body?.string().orEmpty()
            }
        }.getOrElse { e ->
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }
}
