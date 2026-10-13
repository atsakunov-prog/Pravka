package ru.zf.pravka.core

import org.json.JSONObject
import ru.zf.pravka.data.ZasechkaStore

/**
 * Развилка строки «сказать» на «Сегодня» (Правка 5.0, этап 2; план §4): одна
 * строка на всё — засечка, мысль к делу, еда, дело, тело, деньги, вопрос.
 * Решает Сонет (`ClaudeZasechka.sayFork`, промпт `PromptsZasechka.SAY_FORK`,
 * дорога и модель — те же, что у развилки «З»), дальше фразу разбирает
 * дорога своего режима (`TodaySay.kt`).
 *
 * Своя, а не та, что у «З» (`ZasechkaIntent`): у кнопки «З» и гарнитуры четыре
 * дороги, и их правила живут в слое поверх приложений — здесь ещё тело, деньги
 * и вопрос, а сказанное с экрана, где всё видно, может идти куда угодно.
 * Сонет молчит или ответил не то — решают слова ([words]): развилка «З»
 * плюс сумма денег и вопросительный знак. Сказанное не теряется и не ждёт сети
 * второй раз.
 */
object SayFork {

    enum class Kind(val key: String, val word: String) {
        ENTRY("entry", "лента"),
        COMMENT("comment", "мысль"),
        FOOD("food", "еда"),
        TASKS("tasks", "дела"),
        BODY("body", "тело"),
        MONEY("money", "деньги"),
        ASK("ask", "вопрос"),
        ;

        companion object {
            fun of(key: String): Kind? = entries.firstOrNull { it.key == key.trim().lowercase() }
        }
    }

    /** Куда и что: [text] — сказанное без команды (у ленты — как сказано), [why] — пара слов модели для журнала. */
    data class Route(val kind: Kind, val text: String, val why: String = "")

    /** Ответ Сонета → дорога; не прочитался — null (тогда решают слова). */
    fun fromModel(reply: String, original: String): Route? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        val kind = Kind.of(o.optString("kind")) ?: return null
        val said = original.trim()
        // Лента получает фразу как сказана: там правила времени, пересказ теряет «последние полчаса».
        val text = if (kind == Kind.ENTRY) said else o.optString("text").trim().takeIf { it != "null" }.orEmpty().ifBlank { said }
        return Route(kind, text, o.optString("why").trim().takeIf { it != "null" }.orEmpty())
    }

    /** Сумма с рублями («640 р», «30 тысяч», «3200 ₽») или глагол денег рядом с числом. */
    private val MONEY = Regex(
        """(\d[\d\s]*(?:[.,]\d+)?\s*(?:₽|руб|р\.?(?:\s|$)|тыс))|((?:потратил|заплатил|оплатил|перевёл|перевел|скинул)\D{0,40}\d)""",
        RegexOption.IGNORE_CASE,
    )

    /** Запас без модели: вопрос — по знаку, деньги — по сумме, остальное — как у развилки «З». */
    fun words(text: String): Route {
        val t = text.trim()
        if (t.endsWith("?")) return Route(Kind.ASK, t)
        if (MONEY.containsMatchIn(t)) return Route(Kind.MONEY, t)
        val z = ZasechkaIntent.route(t)
        val kind = when (z.kind) {
            ZasechkaIntent.Kind.ENTRY -> Kind.ENTRY
            ZasechkaIntent.Kind.COMMENT -> Kind.COMMENT
            ZasechkaIntent.Kind.FOOD -> Kind.FOOD
            ZasechkaIntent.Kind.TASKS -> Kind.TASKS
        }
        return Route(kind, z.text)
    }

    /**
     * «Сейчас: 12:40. Идёт: «Созвон с Ильёй» с 12:05.» — Сонету, чтобы отличить
     * мысль к делу от нового дела (как у развилки «З»).
     */
    fun context(now: Long, open: ZasechkaStore.Entry?, last: ZasechkaStore.Entry?): String {
        val going = when {
            open != null -> "Идёт: «${open.title.ifBlank { "без названия" }}» с ${Fmt.hm(open.start)}."
            last != null -> "Сейчас ничего не идёт; последнее — «${last.title.ifBlank { "без названия" }}», закончилось в ${Fmt.hm(last.end)}."
            else -> "Сейчас ничего не идёт."
        }
        return "Сейчас: ${Fmt.hm(now)}. $going Сказано в строке «сказать» на главном экране."
    }
}
