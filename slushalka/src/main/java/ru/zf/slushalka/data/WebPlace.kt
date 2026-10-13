package ru.zf.slushalka.data

import kotlin.math.abs

/**
 * Место из читалки в вебе «Книг» (панель домашней библиотеки в браузере, 14.10.2026).
 *
 * Владелец: «в вебе надо сделать простенькую читалку… Она должна синхрониться со Слушалкой по последнему месту».
 * Веб открывает книгу там, где остановились последним (он читает файлы мест Слушалки - только читает), а своё
 * место сервер кладёт отдельным файлом [path] - `_Веб/места-<имя>.json`, тем же форматом, что места устройств
 * (`v: 2`, устройство «веб», ключ - имя папки книги в `Книги/`, есть только `read`). Не в `_Слушалка`: туда
 * пишут только телефоны, и сервер её не трогает.
 *
 * Своё место с другого своего устройства Слушалка берёт молча (строка «Вернуть»), а место веба - нет: веб
 * открывают «чуть посмотреть», и молча перескочить туда значило бы сбить место - самое главное. Поэтому строкой
 * спрашивается «в вебе дочитал до стр. N - продолжить?»; на ответ (любой) запоминается время этого места, и
 * второй раз про него не спрашивают. Новое место веба - новый вопрос.
 */
object WebPlace {
    const val DIR = "_Веб"

    /** Путь файла мест веба от корня облака: `_Веб/места-Саша.json`. */
    fun path(profile: String): String = DIR + "/" + PositionSync.fileName(PositionSync.PLACES_PREFIX, profile)

    /** Что предложить: книга (ключ этой полки), знак в её тексте здесь, длина текста, время места веба. */
    data class Offer(val bookId: String, val char: Int, val textChars: Int, val at: Long)

    /**
     * Знак веба в тексте этой копии. Веб считает знаки тем же разбором Слушалки по тексту сервера; у своей копии
     * на телефоне издание может быть другим - тогда по доле, как сверка справочника с текстом (2 %).
     */
    fun charHere(web: BookState, localChars: Int): Int {
        val of = web.textChars
        if (localChars > 0 && of > 0 && abs(of - localChars) > localChars * FIT) {
            return (web.readChar.toLong() * localChars / of).toInt().coerceIn(0, localChars)
        }
        return if (localChars > 0) web.readChar.coerceIn(0, localChars) else web.readChar.coerceAtLeast(0)
    }

    /**
     * Спросить ли про это место веба:
     * - в вебе читали позже, чем здесь читали или слушали (своё свежее - вопроса нет);
     * - на это место ещё не отвечали ([seenAt] - время места, на которое ответили);
     * - и оно не рядом со своим местом чтения (страница-другая - не новость). Если здесь последним слушали, место
     *   в записи в знаках не известно без карты книги - тогда спрашиваем: веб всё равно свежее.
     */
    fun worth(local: BookState, web: BookState, seenAt: Long): Boolean {
        if (web.readChar < 0 || web.readAt <= 0) return false
        if (web.readAt <= seenAt) return false
        if (web.readAt <= maxOf(local.readAt, local.listenAt)) return false
        val near = local.readChar >= 0 && !local.listenedLast &&
            abs(charHere(web, local.textChars) - local.readChar) <= Settings.PAGE_CHARS
        return !near
    }

    /**
     * Какое место предложить: открытой книги ([current]), если про неё есть что сказать, иначе самое свежее.
     * Ключи файла - имена папок; [canonical] переводит их в ключи этой полки (как места других устройств),
     * [exists] - есть ли такая книга на полке или на сервере.
     */
    fun pick(
        web: Map<String, BookState>,
        canonical: (String) -> String,
        local: (String) -> BookState,
        seen: (String) -> Long,
        exists: (String) -> Boolean,
        current: String?,
    ): Offer? {
        val offers = web.mapNotNull { (key, st) ->
            val id = canonical(key)
            if (!exists(id)) return@mapNotNull null
            val here = local(id)
            if (!worth(here, st, seen(id))) return@mapNotNull null
            Offer(id, charHere(st, here.textChars), if (here.textChars > 0) here.textChars else st.textChars, st.readAt)
        }
        return offers.firstOrNull { it.bookId == current } ?: offers.maxByOrNull { it.at }
    }

    private const val FIT = 0.02
}
