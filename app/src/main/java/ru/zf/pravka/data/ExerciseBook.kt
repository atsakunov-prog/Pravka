package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Справочник упражнений: движения владельца со схемами, техникой, ошибками,
// прогрессией и именем в Garmin Connect.
//
// Источник правды — база Notion «Упражнения», и читается она ЖИВОЙ
// (`NotionExerciseSync`): владелец правит там состав зарядки и дозы, и вкладка
// должна видеть новые движения («Суставы сверху вниз», «Осанка: …») в тот же
// день, а не после пересборки APK. Прочитанное лежит на диске
// (`exercises_live.json`) — карточка тренировки открывается каждый день, в том
// числе в подвале на даче без сети, и должна открываться из кэша.
//
// Статический `assets/exercises.json` (собран из той же базы скриптом
// `tools/gen_reference.py`) остался СЕМЕНЕМ: с него начинается жизнь после
// установки, он же — запас без токена Notion, и из него берутся голосовые имена
// (`aliases`) и единицы подхода — Notion про них не знает.
//
// Главное здесь не хранение, а СОПОСТАВЛЕНИЕ. Список движений конечный и
// известен заранее, поэтому «гоблет четыре по десять шестнадцать» разбирается
// точно, а не как «гоблин» или «глобально». У каждого упражнения свои
// голосовые имена (`aliases`) — так, как владелец их правда произносит.
class ExerciseBook(private val context: Context?) {

    companion object {
        private const val ASSET = "exercises.json"
        const val LIVE_FILE = "exercises_live.json"

        /** Единица подхода: повторы, секунды (вис), метры (переноска), минуты. */
        const val UNIT_REPS = "reps"
        const val UNIT_SEC = "sec"
        const val UNIT_M = "m"
        const val UNIT_MIN = "min"

        /**
         * Нормализация под сравнение: регистр, ё, дефисы и хвосты слов.
         *
         * Хвосты режутся до шести букв, потому что русский склоняет всё:
         * «свинги», «свингов», «свингами» — одно движение, и владелец скажет
         * любое из них. Шесть, а не пять как у TaskMatcher: здесь имена
         * короткие и близкие («жим» против «жиму»), и лишний символ спасает от
         * склейки разных упражнений.
         */
        fun normalize(text: String): String =
            text.lowercase()
                .replace('ё', 'е')
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ")

        fun stem(word: String): String = word.take(6)

        private fun stemmed(text: String): List<String> =
            normalize(text).split(' ').filter { it.isNotBlank() }.map { stem(it) }

        private val TRANSLIT = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        )

        /**
         * Стабильный id из названия — буква в букву как `slug()` в
         * `tools/gen_reference.py`: id попадает в журнал и в отчёты зарядки,
         * и живой справочник обязан давать те же id, что файл сборки, иначе
         * история движения рвётся при первом же чтении Notion.
         */
        fun slug(name: String): String {
            val sb = StringBuilder()
            for (ch in name.lowercase()) {
                val t = TRANSLIT[ch]
                when {
                    t != null -> sb.append(t)
                    ch.isLetterOrDigit() -> sb.append(ch)
                    else -> sb.append('-')
                }
            }
            return sb.toString().replace(Regex("-+"), "-").trim('-').take(48)
        }

        /**
         * Голосовые имена для движения, которого в файле сборки ещё нет:
         * само название без номера, до двоеточия и скобок, содержимое скобок и
         * кавычек, части через « · » («Осанка: подбородок назад · скольжения
         * по стене» → «скольжения по стене»). Настоящие псевдонимы владельца
         * приедут со следующей пересборкой снимка.
         */
        fun derivedAliases(name: String): List<String> {
            val base = name.replace(Regex("^\\d+[.)]\\s*"), "").trim()
            val out = mutableListOf(base, base.substringBefore(":"), base.substringBefore("("))
            Regex("\\(([^)]+)\\)").findAll(base).forEach { out.add(it.groupValues[1]) }
            Regex("«([^»]+)»").findAll(base).forEach { out.add(it.groupValues[1]) }
            base.substringAfter(":", base).split(" · ", " + ", "/").forEach { out.add(it) }
            return out.map { it.trim() }.filter { normalize(it).length >= 3 }.distinct()
        }
    }

    /** Одно упражнение из справочника. */
    data class Exercise(
        val id: String,
        val order: Int,
        val name: String,
        val scheme: String,
        val blocks: List<String>,
        val gear: List<String>,
        val targets: List<String>,
        val how: String,
        val mistakes: String,
        val progression: String,
        val video: String,
        val garmin: String,
        val notion: String,
        val unit: String,
        val aliases: List<String>,
    ) {
        /** Как показать единицу подхода: «10», «40 сек», «20 м», «2 мин». */
        fun amount(value: Int): String = when (unit) {
            UNIT_SEC -> "$value сек"
            UNIT_M -> "$value м"
            UNIT_MIN -> "$value мин"
            else -> "$value"
        }

        /** Поисковый запрос видео — то, что владелец вбивает в ютуб. */
        val videoQuery: String
            get() = video.trim().trim('«', '»').substringBefore(" · ").ifBlank { name }
    }

    private class Snapshot(val items: List<Exercise>, val snapshot: String, val fetchedAt: Long)

    @Volatile private var items: List<Exercise> = emptyList()
    @Volatile private var byAlias: Map<String, Exercise> = emptyMap()
    @Volatile private var snapshot = ""
    /** Файл сборки как прочитан: псевдонимы и единицы для живого справочника. */
    @Volatile private var seed: List<Exercise> = emptyList()

    /** Когда справочник в последний раз приезжал из Notion; 0 — ещё ни разу. */
    @Volatile var fetchedAt: Long = 0L
        private set

    private val _versionFlow = MutableStateFlow(0)
    /** Растёт при каждой замене справочника — вкладка пересобирает списки. */
    val versionFlow: StateFlow<Int> = _versionFlow

    val all: List<Exercise> get() = items
    val loaded: Boolean get() = items.isNotEmpty()
    fun snapshotDate(): String = snapshot
    val fromNotion: Boolean get() = fetchedAt > 0L

    /** Кэш живого справочника; null без Android-контекста (JVM-тесты). */
    private val liveFile: File? get() = context?.let { File(it.filesDir, LIVE_FILE) }

    /**
     * Справочник из готового JSON (формат `exercises.json`) — без Android и
     * без диска. Нужен JVM-тестам разбора строк плана: они гоняют настоящие
     * описания из календаря против настоящего справочника.
     */
    fun loadFromJson(text: String) {
        val parsed = parse(JSONObject(text))
        seed = parsed.items
        items = mergeSeed(parsed.items)
        snapshot = parsed.snapshot
        fetchedAt = parsed.fetchedAt
        byAlias = buildIndex(items)
    }

    suspend fun load(): List<Exercise> {
        if (items.isNotEmpty()) return items
        val ctx = context ?: return items
        val file = liveFile ?: return items
        val (asset, live) = withContext(Dispatchers.IO) {
            val asset = runCatching {
                val text = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
                parse(JSONObject(text))
            }.getOrNull()
            val live = StoreFiles.readOrQuarantine(file) { text -> parse(JSONObject(text)) }
            asset to live
        }
        if (asset != null) seed = asset.items
        // Живой кэш побеждает семя, если он не пуст: пустой ответ Notion сюда
        // и не пишется (см. NotionExerciseSync), но и пустой файл не должен
        // оставить вкладку без справочника.
        val chosen = live?.takeIf { it.items.isNotEmpty() } ?: asset
        if (chosen != null) {
            items = mergeSeed(chosen.items)
            snapshot = chosen.snapshot
            fetchedAt = chosen.fetchedAt
            byAlias = buildIndex(items)
        }
        return items
    }

    /** Упражнение из файла сборки по id — псевдонимы и единица подхода. */
    fun seedById(id: String): Exercise? = seed.firstOrNull { it.id == id }

    /**
     * Заменить справочник тем, что прочиталось из Notion. Пустой список не
     * принимается — ответ без строк почти всегда значит «сеть или доступ
     * подвели», а не «упражнений больше нет».
     */
    suspend fun replace(list: List<Exercise>, fetchedAt: Long, snapshot: String) {
        if (list.isEmpty()) return
        load()
        val merged = mergeSeed(list)
        items = merged
        byAlias = buildIndex(merged)
        this.fetchedAt = fetchedAt
        this.snapshot = snapshot
        val json = serialize(merged, snapshot, fetchedAt).toString()
        liveFile?.let { file -> DiskWriter.post { StoreFiles.writeAtomic(file, json) } }
        _versionFlow.value = _versionFlow.value + 1
    }

    /** Псевдонимы и единица — из семени, если живая запись их не принесла. */
    private fun mergeSeed(list: List<Exercise>): List<Exercise> {
        if (seed.isEmpty()) return list
        return list.map { e ->
            val s = seed.firstOrNull { it.id == e.id } ?: return@map e
            e.copy(
                aliases = if (e.aliases.isNotEmpty()) e.aliases else s.aliases,
                unit = if (e.unit.isNotBlank() && e.unit != UNIT_REPS) e.unit else s.unit,
            )
        }
    }

    private fun parse(o: JSONObject): Snapshot {
        val out = mutableListOf<Exercise>()
        val array = o.optJSONArray("items") ?: return Snapshot(emptyList(), "", 0L)
        for (i in 0 until array.length()) {
            val e = array.optJSONObject(i) ?: continue
            fun list(key: String): List<String> {
                val a = e.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            out.add(
                Exercise(
                    id = e.optString("id"),
                    order = e.optInt("n"),
                    name = e.optString("name"),
                    scheme = e.optString("scheme"),
                    blocks = list("blocks"),
                    gear = list("gear"),
                    targets = list("targets"),
                    how = e.optString("how"),
                    mistakes = e.optString("mistakes"),
                    progression = e.optString("progression"),
                    video = e.optString("video"),
                    garmin = e.optString("garmin"),
                    notion = e.optString("notion"),
                    unit = e.optString("unit").ifBlank { UNIT_REPS },
                    aliases = list("aliases"),
                )
            )
        }
        return Snapshot(out, o.optString("snapshot"), o.optLong("fetchedAt", 0L))
    }

    private fun serialize(list: List<Exercise>, snapshot: String, fetchedAt: Long): JSONObject =
        JSONObject().apply {
            put("source", "notion:Упражнения")
            put("snapshot", snapshot)
            put("fetchedAt", fetchedAt)
            put(
                "items",
                JSONArray().apply {
                    for (e in list) put(
                        JSONObject().apply {
                            put("id", e.id)
                            put("n", e.order)
                            put("name", e.name)
                            put("scheme", e.scheme)
                            put("blocks", JSONArray().apply { e.blocks.forEach { put(it) } })
                            put("gear", JSONArray().apply { e.gear.forEach { put(it) } })
                            put("targets", JSONArray().apply { e.targets.forEach { put(it) } })
                            put("how", e.how)
                            put("mistakes", e.mistakes)
                            put("progression", e.progression)
                            put("video", e.video)
                            put("garmin", e.garmin)
                            put("notion", e.notion)
                            put("unit", e.unit)
                            put("aliases", JSONArray().apply { e.aliases.forEach { put(it) } })
                        }
                    )
                }
            )
        }

    /**
     * Индекс на точное совпадение. Длинные псевдонимы кладутся первыми и не
     * перетираются короткими: «гоблет присед» должен победить «присед», иначе
     * гоблет уедет в воздушные приседания.
     */
    private fun buildIndex(list: List<Exercise>): Map<String, Exercise> {
        val map = HashMap<String, Exercise>()
        for (e in list) {
            for (alias in (e.aliases + e.name).sortedByDescending { it.length }) {
                val key = normalize(alias)
                if (key.isNotBlank()) map.putIfAbsent(key, e)
            }
        }
        return map
    }

    fun byId(id: String): Exercise? = items.firstOrNull { it.id == id }

    /** Упражнения одного блока («A · дом», «Зарядка», «Турник») по порядку. */
    fun ofBlock(block: String): List<Exercise> =
        items.filter { ex -> ex.blocks.any { it.equals(block, ignoreCase = true) } }
            .sortedBy { it.order }

    /** Все блоки, какие есть в справочнике. */
    fun blocks(): List<String> = items.flatMap { it.blocks }.distinct()

    /**
     * Сказанное имя → упражнение. Три попытки по убыванию строгости:
     * точный псевдоним, псевдоним внутри фразы (самый длинный побеждает),
     * пересечение основ слов. Не нашли — null: выдумывать упражнение хуже,
     * чем показать «не понял, поправь руками».
     */
    fun match(spoken: String): Exercise? {
        val key = normalize(spoken)
        if (key.isBlank()) return null
        byAlias[key]?.let { return it }

        var best: Exercise? = null
        var bestLen = 0
        for ((alias, exercise) in byAlias) {
            // Пять символов, не четыре: «тяга» внутри «тяга под столом»
            // перехватывала строку в «тягу гири в наклоне» — на неделе
            // спина-протокола это ровно запрещённое движение.
            if (alias.length < 5) continue
            if (key.contains(alias) && alias.length > bestLen) {
                best = exercise
                bestLen = alias.length
            }
        }
        if (best != null) return best

        // Основы: «свингов пять по пятнадцать» → «свинг».
        val said = stemmed(key).toSet()
        if (said.isEmpty()) return null
        var bestScore = 0.0
        for (exercise in items) {
            for (candidate in exercise.aliases + exercise.name) {
                val theirs = stemmed(candidate).toSet()
                if (theirs.isEmpty()) continue
                // Однословный псевдоним против многословной фразы — не улика:
                // счёт 1/1 у «тяги» съедал любую «тягу чего угодно».
                if (theirs.size == 1 && said.size > 1) continue
                val shared = theirs.count { it in said }
                if (shared == 0) continue
                val score = shared.toDouble() / theirs.size
                if (score > bestScore) {
                    bestScore = score
                    best = exercise
                }
            }
        }
        // Половина основ названия должна найтись, иначе это не то упражнение.
        return if (bestScore >= 0.5) best else null
    }

    /**
     * Блок промпта: весь словарь движений одной строкой на упражнение. Именно
     * он превращает распознавание в выбор из списка, а не в угадывание.
     */
    fun promptBlock(block: String? = null): String {
        val list = if (block.isNullOrBlank()) items else ofBlock(block)
        if (list.isEmpty()) return ""
        return buildString {
            append("Упражнения владельца (name — как называть в ответе; в скобках — как он их произносит):\n")
            for (e in list.sortedBy { it.order }) {
                append("- ").append(e.name)
                if (e.aliases.isNotEmpty()) {
                    append(" (").append(e.aliases.joinToString(", ")).append(")")
                }
                if (e.unit != UNIT_REPS) {
                    append(" [считается в ")
                    append(
                        when (e.unit) {
                            UNIT_SEC -> "секундах"
                            UNIT_M -> "метрах"
                            UNIT_MIN -> "минутах"
                            else -> "повторах"
                        }
                    )
                    append("]")
                }
                if (e.scheme.isNotBlank()) append(" — обычно ").append(e.scheme)
                append('\n')
            }
        }
    }

    /** Голосовые имена для смещения распознавателя (bias) — как словарь Правки. */
    fun biasing(): List<String> =
        (items.map { it.name } + items.flatMap { it.aliases }).distinct()
}
