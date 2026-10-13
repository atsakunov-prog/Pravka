package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Последний дайджест Telegram для «Сегодня» (Правка 5.0, этап 2;
 * `docs/digest-phone.md`, контракт `server/contract/dela-digest.json`). Сводку
 * пишет служба дайджеста на компе и кладёт копию в Дела; телефон берёт её
 * полем `digest` синка Дел (`DelaStore.digest`), только владелец и только
 * при флаге `digest` в `features`. Нет — карточки нет.
 *
 * [at] — конец окна сводки, [title] — подпись окна («вт 13.10, 20:00»), [text] —
 * текст строками для разбора [blocks] (строка с «# » — раздел, «Кто: что» —
 * имя жирным); у сводки с сервера разделы собраны сразу из пунктов
 * (`summary`) — [parsed], чтобы «Кто (чат)» с двоеточиями не путался с текстом.
 */
data class DigestNote(
    val at: Long,
    val text: String,
    val title: String = "",
    val id: String = "",
    val rev: Int = 0,
    val ref: String = "",
    /** Чатов и сообщений в окне; null — служба не сказала. */
    val chats: Int? = null,
    val msgs: Int? = null,
    /** «В Дела: поставлено 2, вопросов 1.» — что ушло в Дела из окна; пусто — Дела не ответили. */
    val dela: String = "",
    /** Пачка «Нового» этого окна (`batch_ref`). */
    val batch: String = "",
    /** Готовые разделы (сводка с сервера); null — разбирать [text]. */
    val parsed: List<Block>? = null,
) {
    /** Кусок сводки: заголовок раздела или абзац с необязательным жирным началом. */
    data class Block(val heading: Boolean, val text: String, val lead: String = "")

    fun blocks(): List<Block> = parsed ?: text.lines().mapNotNull { raw ->
        val line = raw.trim().removePrefix("- ").removePrefix("• ").trim()
        when {
            line.isBlank() -> null
            line.startsWith("#") -> Block(true, line.trimStart('#').trim())
            else -> {
                // «Марианна: погасить Озон…» — имя до двоеточия жирным, если оно короткое.
                val cut = line.indexOf(": ")
                if (cut in 1..LEAD_MAX) Block(false, line.substring(cut + 2), line.substring(0, cut + 1))
                else Block(false, line)
            }
        }
    }

    /**
     * Первые слова сводки — свёрнутая строка «Раньше»: из пунктов, без заголовков
     * разделов («Ждут тебя» в каждой второй сводке — не то, по чему их различают).
     */
    fun preview(max: Int = PREVIEW_MAX): String {
        val line = blocks().filter { !it.heading }.joinToString(" · ") { b -> listOf(b.lead, b.text).filter { it.isNotBlank() }.joinToString(" ") }
        return if (line.length <= max) line else line.take(max).trimEnd().trimEnd(',', ';', ':', '·').trimEnd() + "…"
    }

    /** «чатов 12 · сообщений 148» — чего нет, то не пишется. */
    val counts: String
        get() = listOfNotNull(chats?.let { "чатов $it" }, msgs?.let { "сообщений $it" }).joinToString(" · ")

    companion object {
        /** Жирное начало — не длиннее этого: дальше двоеточие уже внутри фразы. */
        const val LEAD_MAX = 32

        /** Флаг сервера: дайджест он хранит (`features`). */
        const val FEATURE = "digest"

        /** «Раньше» у раскрытой карточки — вид сервера за двое суток (`docs/digest-phone.md`, п. 2). */
        const val EARLIER_PATH = "digest?days=2&limit=20"

        /** Сколько знаков первых слов у свёрнутой строки «Раньше». */
        const val PREVIEW_MAX = 90

        /**
         * Ответ вида `digest` (`{"last": …, "items": [свежие сверху], "days": 2}`) → «Раньше»:
         * сводки окна без той, что уже на карточке ([currentId]); кривые — мимо. Порядок —
         * как у сервера (свежие сверху), на всякий случай ещё и по концу окна.
         */
        fun earlier(resp: JSONObject, currentId: String): List<DigestNote> {
            val items = resp.optJSONArray("items") ?: JSONArray()
            return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::parse) }
                .filter { it.id != currentId }
                .distinctBy { it.id }
                .sortedByDescending { it.at }
        }

        /**
         * «Кто (чат)» — по тому же правилу, что сервер (`digest.where_of`): чат не
         * повторяем, если он пуст или одно название содержит другое.
         */
        fun whereOf(who: String, chat: String): String {
            val w = who.trim()
            val c = chat.trim()
            if (c.isEmpty() || w.lowercase().contains(c.lowercase()) || (w.isNotEmpty() && c.lowercase().contains(w.lowercase()))) {
                return w.ifEmpty { c }
            }
            return if (w.isNotEmpty()) "$w ($c)" else c
        }

        /** Строка «В Дела» теми же словами, что под сводкой в Telegram (`digest.dela_line`). */
        fun delaLine(d: JSONObject?): String {
            if (d == null) return ""
            fun n(k: String) = d.optInt(k, 0)
            val bits = listOfNotNull(
                n("created").takeIf { it > 0 }?.let { "поставлено $it" },
                n("asks").takeIf { it > 0 }?.let { "вопросов $it" },
                n("close").takeIf { it > 0 }?.let { "закрыть $it" },
                n("update").takeIf { it > 0 }?.let { "уточнить $it" },
                n("closed").takeIf { it > 0 }?.let { "закрыто само $it" },
                n("updated").takeIf { it > 0 }?.let { "уточнено само $it" },
            )
            return "В Дела: " + (if (bits.isEmpty()) "новых дел нет" else bits.joinToString(", ")) + "."
        }

        /**
         * Разделы из пунктов сводки (`summary`): «Ждут тебя» первым — это главное,
         * потом «По работе», «Дом», «Прочее». Пустой раздел не рисуется; всё пусто —
         * «Ничего, что ждёт тебя.» (как `text` сервера).
         */
        fun blocksOf(summary: JSONObject?): List<Block> {
            val s = summary ?: JSONObject()
            val out = ArrayList<Block>()
            fun arr(k: String): JSONArray = s.optJSONArray(k) ?: JSONArray()
            val waiting = arr("waiting")
            if (waiting.length() > 0) {
                out += Block(true, "Ждут тебя")
                for (i in 0 until waiting.length()) {
                    val w = waiting.optJSONObject(i) ?: continue
                    val where = whereOf(w.str("who"), w.str("chat"))
                    val what = w.str("what")
                    out += if (what.isNotEmpty()) Block(false, what, "$where:") else Block(false, where)
                }
            }
            val work = arr("work")
            if (work.length() > 0) {
                out += Block(true, "По работе")
                for (i in 0 until work.length()) {
                    val w = work.optJSONObject(i) ?: continue
                    val topic = w.str("topic")
                    val text = w.str("text")
                    out += if (topic.isNotEmpty() && text.isNotEmpty()) Block(false, text, "$topic:") else Block(false, topic.ifEmpty { text })
                }
            }
            val family = arr("family")
            if (family.length() > 0) {
                out += Block(true, "Дом")
                for (i in 0 until family.length()) family.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { out += Block(false, it) }
            }
            val other = s.str("other")
            if (other.isNotEmpty()) {
                out += Block(true, "Прочее")
                out += Block(false, other)
            }
            if (out.isEmpty()) out += Block(false, "Ничего, что ждёт тебя.")
            return out
        }

        /** Сводка из синка или вида Дел (`digest_object`); без `id` или конца окна — null. */
        fun parse(o: JSONObject): DigestNote? {
            val id = o.str("id")
            val ended = o.str("ended_at")
            if (id.isEmpty() || ended.isEmpty()) return null
            val at = runCatching { java.time.OffsetDateTime.parse(ended).toInstant().toEpochMilli() }.getOrNull() ?: return null
            val dela = o.optJSONObject("dela")
            return DigestNote(
                at = at,
                text = o.str("text"),
                title = o.str("label"),
                id = id,
                rev = o.optInt("rev", 0),
                ref = o.str("ref"),
                chats = o.intOrNull("chats"),
                msgs = o.intOrNull("msgs"),
                dela = delaLine(dela),
                batch = dela?.str("batch").orEmpty(),
                parsed = blocksOf(o.optJSONObject("summary")),
            )
        }

        private fun JSONObject.str(k: String): String =
            if (!has(k) || isNull(k)) "" else optString(k).trim()

        private fun JSONObject.intOrNull(k: String): Int? =
            if (!has(k) || isNull(k)) null else optInt(k, -1).takeIf { it >= 0 }
    }
}
