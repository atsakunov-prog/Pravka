package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.Prompts

// В папке базы (DataRoot): свои промпты владельца — часть базы.
private val Context.promptDataStore get() = DataRoot.preferences(this, "prompts")

// Owner-editable prompt overrides (spec section 7). Factory texts stay as
// constants in Prompts.kt; DataStore holds only the overrides, so an APK
// update can refresh factory texts without touching the owner's edits.
class PromptStore(private val context: Context) {

    enum class PromptId(val storageKey: String) {
        CLEAN_CLAUDE("clean_claude"),
        BUSINESS("business"),
        SOFTEN("soften"),
        PROSE("prose"),
        // Meeting transcripts (Whisper on the owner's computer) - used only
        // by the "copy full prompt" button, never sent from the app itself.
        MEETING("meeting"),
        // Разноска: наговор -> дела в Todoist (Опус).
        TASKS("tasks"),
        // Разноска для Дел на домашнем сервере: мяч, человек, проект из справочника.
        TASKS_DELA("tasks_dela"),
        // Засечка: развилка — лента, мысль к делу, еда или дела (Сонет).
        ZASECHKA_FORK("zasechka_fork"),
        // Еда: сказанное -> КБЖУ (Сонет).
        FOOD("food"),
        // Деньги: наговор -> траты и подсказки сверки выписок (Опус).
        MONEY("money"),
        MONEY_MATCH("money_match"),
        MONEY_ASK("money_ask"),
        MONEY_PATTERNS("money_patterns"),
        MONEY_ANSWER("money_answer"),
        // Спорт: вопрос по своим тренировкам (Опус).
        COACH("coach"),
        // Тренер-консультант: короткий вопрос про упражнение (Сонет).
        TRAINER("trainer"),
        // Тело: один микрофон на подходы, еду, зарядку и вопросы (Сонет).
        BODY("body"),
        // Правила блока: проза Notion -> числа (Сонет).
        RULES("rules"),
        // Паттерны: ночной поиск повторов по всему логу (Опус). Разборы
        // владелец делает сам в чате — здесь только охота за повторами.
        PATTERNS("patterns");

        companion object {
            fun of(mode: ProofreadMode): PromptId = when (mode) {
                ProofreadMode.CLEAN -> CLEAN_CLAUDE
                ProofreadMode.BUSINESS -> BUSINESS
                ProofreadMode.SOFTEN -> SOFTEN
            }
        }
    }

    fun factory(id: PromptId): String = when (id) {
        PromptId.CLEAN_CLAUDE -> Prompts.CLEAN_CLAUDE
        PromptId.BUSINESS -> Prompts.BUSINESS
        PromptId.SOFTEN -> Prompts.SOFTEN
        PromptId.PROSE -> Prompts.PROSE
        PromptId.MEETING -> Prompts.MEETING
        PromptId.TASKS -> Prompts.TASKS
        PromptId.TASKS_DELA -> Prompts.TASKS_DELA
        PromptId.ZASECHKA_FORK -> Prompts.ZASECHKA_FORK
        PromptId.FOOD -> Prompts.FOOD
        PromptId.MONEY -> Prompts.MONEY
        PromptId.MONEY_MATCH -> Prompts.MONEY_MATCH
        PromptId.MONEY_ASK -> Prompts.MONEY_ASK
        PromptId.MONEY_PATTERNS -> Prompts.MONEY_PATTERNS
        PromptId.MONEY_ANSWER -> Prompts.MONEY_ANSWER
        PromptId.COACH -> Prompts.COACH
        PromptId.TRAINER -> Prompts.TRAINER
        PromptId.BODY -> Prompts.BODY
        PromptId.RULES -> Prompts.RULES
        PromptId.PATTERNS -> Prompts.PATTERNS
    }

    // ---- Свод (06.10.2026, docs/svod-phone.md, 1.3–1.4) ----
    //
    // Правда о промптах — на сервере: запись Свода `prompt.<storageKey>`
    // (Разноска — `prompt.raznoska`, только её общие правила). Есть запись —
    // она и действует; нет — своя правка этой установки, потом заводской.
    // Правка пишется в Свод, когда сервер его знает, — для всех, а не
    // «своя копия»; без Свода — как раньше, своей правкой.

    /** Свод телефона и умеет ли его сервер — ставит приложение. */
    @Volatile var svod: SvodStore? = null
    @Volatile var svodOn: () -> Boolean = { false }

    /** Текст из Свода: для Разноски — общие правила плюс свой хвост (словарь, наговор). */
    private fun fromSvod(id: PromptId): String? {
        val body = ru.zf.pravka.core.Svod.current[svodKey(id)]?.body?.takeIf { it.isNotBlank() } ?: return null
        return if (id == PromptId.TASKS_DELA) ru.zf.pravka.core.prompts.PromptsRaznoska.withTail(body) else body
    }

    /** Версия записи в Своде (0 — записи нет): `base_rev` автоматов. */
    fun svodRev(id: PromptId): Int = ru.zf.pravka.core.Svod.current[svodKey(id)]?.rev ?: 0

    /** Текст действует из Свода — экран так и пишет. */
    fun inSvod(id: PromptId): Boolean = fromSvod(id) != null

    fun overrideFlow(id: PromptId): Flow<String?> =
        context.promptDataStore.data.map { it[stringPreferencesKey(id.storageKey)] }

    suspend fun effective(id: PromptId): String =
        ru.zf.pravka.core.Svod.withTeam(raw(id), TEAM_FALLBACK)

    /** Текст как он хранится — с метками вроде `{TEAM}`: его показывает и правит редактор. */
    suspend fun raw(id: PromptId): String = fromSvod(id) ?: overrideFlow(id).first() ?: factory(id)

    suspend fun effective(mode: ProofreadMode): String =
        effective(PromptId.of(mode))

    /** Свой текст (без Свода) — то, что первое знакомство отдаст серверу. */
    suspend fun local(id: PromptId): String = overrideFlow(id).first() ?: factory(id)

    /**
     * Правка промпта. Свод есть — новая версия записи (`author`: phone —
     * редактор, tuner — недельная правка с [baseRev]); нет — своя правка.
     */
    suspend fun setOverride(id: PromptId, text: String, author: String = "phone", reason: String = "", baseRev: Int? = null) {
        val sv = svod
        if (sv != null && svodOn()) {
            val body = if (id == PromptId.TASKS_DELA) ru.zf.pravka.core.prompts.PromptsRaznoska.stripTail(text) else text
            sv.set(svodKey(id), body = body, author = author, reason = reason, baseRev = baseRev)
            return
        }
        context.promptDataStore.edit { it[stringPreferencesKey(id.storageKey)] = text }
    }

    /** Свои тексты владельца (переопределения заводских) — для набора промптов. */
    suspend fun overrides(): Map<PromptId, String> {
        val prefs = context.promptDataStore.data.first()
        return PromptId.entries.mapNotNull { id -> prefs[stringPreferencesKey(id.storageKey)]?.let { id to it } }.toMap()
    }

    /**
     * Принять набор с другой установки: каждый текст из него ложится своим
     * переопределением. Чего в наборе нет — остаётся как было (там у
     * отправителя заводской, а он у всех один — из APK). Возвращает, сколько
     * текстов принято.
     */
    suspend fun importSet(texts: Map<String, String>): Int {
        val known = texts.mapNotNull { (key, text) ->
            PromptId.entries.firstOrNull { it.storageKey == key }?.let { it to text }
        }
        if (known.isEmpty()) return 0
        context.promptDataStore.edit { p -> for ((id, text) in known) p[stringPreferencesKey(id.storageKey)] = text }
        return known.size
    }

    /**
     * "Вернуть заводской": removes the override, factory text applies again.
     * В Своде записи не удаляются — заводской текст ложится новой версией.
     */
    suspend fun resetToFactory(id: PromptId, author: String = "phone") {
        context.promptDataStore.edit { it.remove(stringPreferencesKey(id.storageKey)) }
        val sv = svod
        if (sv != null && svodOn() && ru.zf.pravka.core.Svod.current.containsKey(svodKey(id))) {
            setOverride(id, factory(id), author = author, reason = "вернуть заводской")
        }
    }

    companion object {
        /** Ключ Свода промпта: `prompt.<storageKey>`; Разноска — общий `prompt.raznoska`. */
        fun svodKey(id: PromptId): String =
            if (id == PromptId.TASKS_DELA) ru.zf.pravka.core.Svod.RAZNOSKA else "prompt." + id.storageKey

        /**
         * Команда в промпте Разноски без Свода (`{TEAM}`): заводская строка,
         * какой она была в тексте до Свода.
         */
        const val TEAM_FALLBACK = "Наташа, Алёна, Арина, Лена, папа Сергей"
    }
}

/**
 * Набор промптов одним файлом — чтобы отдать свои промпты другой установке
 * (владелец, 25.09.2026: Марианне «промпты надо дать мои»). Свои тексты
 * владельца — те, что он правил руками и что приняла недельная правка
 * промпта, — живут только в его базе; заводские у всех одни, из APK, поэтому
 * в набор не едут. Род и «без прозы» у получателя накладываются при сборке
 * запроса (`Prompts.forAuthor`, `Prompts.speakerNote`), а не в тексте.
 */
internal object PromptSet {
    const val FORMAT = "pravka-prompts"

    fun encode(from: String, texts: Map<String, String>, now: Long): String =
        org.json.JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("from", from)
            .put("exportedAt", now)
            .put("prompts", org.json.JSONObject().also { o -> texts.forEach { (k, v) -> o.put(k, v) } })
            .toString(2)

    /** Тексты набора: ключ промпта — текст. Не наш файл — исключение с понятным текстом. */
    fun decode(text: String): Pair<String, Map<String, String>> {
        val o = org.json.JSONObject(text)
        require(o.optString("format") == FORMAT) { "это не набор промптов Правки" }
        val p = o.optJSONObject("prompts") ?: org.json.JSONObject()
        val map = p.keys().asSequence().associateWith { p.getString(it) }
        return o.optString("from") to map
    }
}
