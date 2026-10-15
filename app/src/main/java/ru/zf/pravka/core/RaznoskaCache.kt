package ru.zf.pravka.core

import org.json.JSONObject

/**
 * Кэш головы разбора надиктовки — только когда он прочитается (аудит 15.10.2026, №67).
 *
 * Голова разбора (правила, справочник, места, дерево — всё до строки с датой) шла под часовым кэшем всегда. Разборы с
 * телефона идут поодиночке (7 за 14 дней): одиночный платит за запись часового кэша вдвое (×2 к цене головы) и никогда
 * его не читает. Правило: кэш ставится, только если прошлый разбор той же дороги был меньше [WINDOW_MS] назад и с той же
 * головой (хэш её текста) — значит, идёт серия: второй разбор пишет кэш, третий и дальше читают. 55 минут, а не 60 — с
 * запасом к часу жизни кэша у Anthropic.
 *
 * Память [Last] — маленький файл в базе (`data/RaznoskaCacheFile.kt`), пишется после каждого разбора. Чистый объект,
 * под тестом `RaznoskaCacheTest`.
 */
object RaznoskaCache {

    const val WINDOW_MS = 55 * 60_000L

    /** Прошлый разбор: когда ушёл запрос, хэш головы, ставили ли кэш. */
    data class Last(val at: Long, val head: String, val cached: Boolean)

    /** Где живёт [Last]: файл в базе телефона; в тестах — память. */
    interface Memory {
        fun read(): Last?
        fun write(last: Last)
    }

    /** Ставить ли `cache_control` на голову: прошлый разбор той же головы был меньше [WINDOW_MS] назад. */
    fun decide(nowMs: Long, last: Last?, headHash: String): Boolean =
        last != null && last.head == headHash && nowMs - last.at in 0 until WINDOW_MS

    /** Хэш головы: сравниваются только хэши, сам текст (имена людей) в файл не пишется. */
    fun hash(head: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(head.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

    fun toJson(l: Last): JSONObject = JSONObject().put("v", 1).put("at", l.at).put("head", l.head).put("cached", l.cached)

    fun fromJson(o: JSONObject?): Last? {
        o ?: return null
        val head = o.optString("head").takeIf { it.isNotBlank() } ?: return null
        return Last(o.optLong("at", 0L), head, o.optBoolean("cached", false))
    }

    /**
     * Где кончается голова: начало строки с `{TODAY}`. Дата меняется каждый день, а хвост (люди, названные в наговоре,
     * словарь, сам наговор) — каждый раз; голова от наговора не зависит. Граница — начало строки, а не сам `{TODAY}`:
     * блок «ЕЩЁ ЛЮДИ» встаёт в хвост перед датой целым абзацем. 0 — головы нет (`{TODAY}` в первой строке или нет его).
     */
    fun headCut(template: String): Int {
        val at = template.indexOf("{TODAY}")
        if (at <= 0) return 0
        return template.lastIndexOf('\n', at) + 1
    }
}
