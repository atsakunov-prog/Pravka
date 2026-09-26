package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Кто пользуется этой установкой и какие режимы ему нужны (25.09.2026).
 *
 * Владелец: «Правка должна спрашивать, кто юзер: Саша, Марианна, Серёжа или
 * ещё кто-то… кому-то Засечка не нужна, или дела, или спорт — отключать одним
 * тумблером». Приложение писалось для одного человека, и этот человек зашит в
 * него: род в промпте чистки («диктует мужчина»), наличные и счета владельца в
 * заводских файлах Денег, базы Notion по умолчанию. Профиль отвечает на два
 * вопроса — чей это телефон и что на нём включено.
 *
 * Лежит в папке базы (`profile.json`): переезжает вместе с базой и попадает в
 * суточную копию, открытая на новом телефоне база приходит со своим хозяином.
 *
 * Первый старт после обновления у владельца: профиля нет, а база есть — это
 * Саша, спрашивать нечего. Свежая установка: профиля нет и базы нет — пишется
 * «ждём ответа», и приложение спрашивает, пока не ответят; незаметно стать
 * Сашей, начав пользоваться без ответа, нельзя.
 */
internal data class Profile(
    /** Ключ латиницей: имя файла копии, хозяин трат в Деньгах. Не меняется. */
    val id: String,
    val name: String,
    /** Род авторской речи: «я сделала» или «я сделал». */
    val female: Boolean,
    /** Включённые режимы. Правка включена всегда и в список не входит. */
    val modes: Set<Mode>,
) {
    /**
     * Владелец приложения — тот, под кого написаны заводские данные: его
     * наличные и счета в Деньгах, его базы Notion, его формулировки в промпте.
     */
    val owner: Boolean get() = id == OWNER_ID

    fun has(mode: Mode): Boolean = mode in modes

    /** Режимы, которые профиль умеет выключать. Правки среди них нет. */
    enum class Mode(val key: String, val title: String, val hint: String) {
        ZASECHKA("zasechka", "Засечка", "лента дня, напоминания, автопилот, телефон по дням"),
        DELA("dela", "Дела", "Todoist и кнопка «Д»"),
        SPORT("sport", "Спорт", "тренировки, силовые, план, intervals.icu"),
        FOOD("food", "Еда", "КБЖУ, дневник еды, кнопка «Е»"),
        MONEY("money", "Деньги", "траты, выписки, пуши банка, кнопка «₽»"),
        ;

        companion object {
            fun of(key: String): Mode? = entries.firstOrNull { it.key == key }
        }
    }

    /** Знакомые люди — на экране выбора готовыми кнопками. */
    enum class Preset(val id: String, val title: String, val female: Boolean) {
        SASHA(OWNER_ID, "Саша", false),
        MARIANNA("marianna", "Марианна", true),
        SERYOZHA("seryozha", "Серёжа", false),
    }

    companion object {
        const val OWNER_ID = "sasha"

        fun of(preset: Preset, modes: Set<Mode> = Mode.entries.toSet()) =
            Profile(preset.id, preset.title, preset.female, modes)

        /** Ключ для нового человека: латиницей из имени, чтобы файлы копий читались. */
        fun idFor(name: String): String {
            val translit = name.trim().lowercase().map { c -> TRANSLIT[c] ?: c.toString() }.joinToString("")
            val clean = translit.map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-')
            val id = clean.ifBlank { "user" }
            // Чужое имя, совпавшее с ключом знакомого, не должно стать им: «Саша»
            // из «Другого» — это другой Саша, не владелец.
            return if (Preset.entries.any { it.id == id }) "$id-2" else id
        }

        private val TRANSLIT = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
            'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        )

        /**
         * Файлы, по которым видно, что база жила до профилей: ими пишет только
         * пользование приложением. Есть хоть один — это установка владельца.
         */
        val LEGACY_MARKERS = listOf(
            "zasechka.json", "history.jsonl", "dictionary.json", "strength.json",
            "food.json", "money.json", "corrections.json", "transcriptions.jsonl",
        )

        fun toJson(p: Profile): String = JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("female", p.female)
            .put("modes", JSONArray(p.modes.map { it.key }))
            .toString(2)

        /** null — файл «ждём ответа» или не читается. */
        fun fromJson(text: String): Profile? {
            val o = JSONObject(text)
            if (o.optBoolean("pending")) return null
            val id = o.optString("id").ifBlank { return null }
            val arr = o.optJSONArray("modes")
            val modes = if (arr == null) Mode.entries.toSet()
            else (0 until arr.length()).mapNotNull { Mode.of(arr.optString(it)) }.toSet()
            return Profile(id, o.optString("name").ifBlank { id }, o.optBoolean("female"), modes)
        }

        const val PENDING_JSON = "{\"pending\": true}"
    }
}

/**
 * Хранилище профиля. Читается синхронно при старте процесса (файл в сотню
 * байт, сразу за решением, где база): от профиля зависят уже первые шаги —
 * сеять ли наличные владельца, какие кнопки ставить на стекло.
 */
internal class ProfileStore(private val context: Context) {

    private val file: File get() = File(DataRoot.dir(context), FILE)
    private val state = MutableStateFlow<Profile?>(null)
    private val askState = MutableStateFlow(false)

    /** Профиль; null — ещё не выбран или база недоступна. */
    val flow: StateFlow<Profile?> get() = state
    /**
     * Спрашивать ли «кто пользуется»: только у свежей установки (или если
     * файл профиля не прочёлся). Недоступная база не спрашивает — ответ лежит
     * в ней, и сохранить новый туда всё равно нельзя.
     */
    val asking: StateFlow<Boolean> get() = askState
    val current: Profile? get() = state.value

    /** Включён ли режим. Пока профиль не выбран — всё включено, как было до профилей. */
    fun has(mode: Profile.Mode): Boolean = state.value?.has(mode) ?: true

    /** Владелец ли. Пока профиль не выбран — нет: заводское владельца не сеется наугад. */
    val owner: Boolean get() = state.value?.owner == true

    /** Решает профиль на старте. Возвращает строку для журнала или пусто. */
    fun init(): String {
        if (DataRoot.where.value == DataRoot.Where.FOLDER_NO_ACCESS) {
            return "профиль: база недоступна — прочтётся, когда вернётся доступ"
        }
        val f = file
        if (f.exists()) {
            val p = StoreFiles.readOrQuarantine(f) { Profile.fromJson(it) }
            state.value = p
            askState.value = p == null
            return ""
        }
        val root = DataRoot.dir(context)
        val legacy = Profile.LEGACY_MARKERS.any { File(root, it).exists() }
        return if (legacy) {
            val p = Profile.of(Profile.Preset.SASHA)
            runCatching { StoreFiles.writeAtomic(f, Profile.toJson(p)) }
            state.value = p
            "профиль: база жила до профилей — это Саша"
        } else {
            runCatching { StoreFiles.writeAtomic(f, Profile.PENDING_JSON) }
            askState.value = true
            "профиль: свежая установка — спрошу, кто пользуется"
        }
    }

    /** Записать профиль. Звать не на главном потоке. */
    fun save(p: Profile) {
        StoreFiles.writeAtomic(file, Profile.toJson(p))
        state.value = p
        askState.value = false
    }

    companion object {
        const val FILE = "profile.json"
    }
}
