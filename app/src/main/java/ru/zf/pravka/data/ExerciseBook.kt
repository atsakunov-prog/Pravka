package ru.zf.pravka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Справочник упражнений: 42 движения владельца со схемами, техникой, ошибками,
// прогрессией и именем в Garmin Connect.
//
// Лежит СТАТИЧЕСКИМ файлом в assets (`exercises.json`, собран из базы Notion
// «Упражнения» скриптом `tools/gen_reference.py`). Причина простая: карточка
// тренировки открывается каждый день, в том числе в подвале на даче, где
// интернета нет, — а справочник меняется раз в месяц. Привязывать ежедневный
// экран к чужому API ради данных, которые не меняются, незачем.
//
// Главное здесь не хранение, а СОПОСТАВЛЕНИЕ. Список движений конечный и
// известен заранее, поэтому «гоблет четыре по десять шестнадцать» разбирается
// точно, а не как «гоблин» или «глобально». У каждого упражнения свои
// голосовые имена (`aliases`) — так, как владелец их правда произносит.
class ExerciseBook(private val context: Context) {

    companion object {
        private const val ASSET = "exercises.json"

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

    @Volatile private var items: List<Exercise> = emptyList()
    @Volatile private var byAlias: Map<String, Exercise> = emptyMap()
    @Volatile private var snapshot = ""

    val all: List<Exercise> get() = items
    val loaded: Boolean get() = items.isNotEmpty()
    fun snapshotDate(): String = snapshot

    suspend fun load(): List<Exercise> {
        if (items.isNotEmpty()) return items
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                parse(JSONObject(text))
            }.getOrNull()
        }
        if (parsed != null) {
            items = parsed.first
            snapshot = parsed.second
            byAlias = buildIndex(parsed.first)
        }
        return items
    }

    private fun parse(o: JSONObject): Pair<List<Exercise>, String> {
        val out = mutableListOf<Exercise>()
        val array = o.optJSONArray("items") ?: return emptyList<Exercise>() to ""
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
        return out to o.optString("snapshot")
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
            if (alias.length < 4) continue
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
