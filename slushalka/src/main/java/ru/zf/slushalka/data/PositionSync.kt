package ru.zf.slushalka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.library.documentUri

/**
 * Позиции и вопросы рядом с книгами.
 *
 * В корне библиотеки заводится папка `_Слушалка`, и каждое устройство пишет в
 * неё **свой** файл `позиции-<имя>.json`: секунда записи и знак текста, где
 * остановились глаза, по каждой книге. Никаких слияний и конфликтов: у
 * дорожки один хозяин. Если папка библиотеки синхронизируется (Drive,
 * Syncthing, кабель) - начатое на телефоне продолжается на планшете, а на
 * карточке книги видно, докуда дошёл второй слушатель.
 *
 * Рядом лежит `вопросы-<имя>.json` - история вопросов и ответов той же
 * дорожки. Она, наоборот, сливается: два устройства одного человека
 * дописывают друг друга, и разговор с книгой один на оба. Так же сливаются
 * пометки на полях - `пометки-<имя>.json`.
 *
 * Файлы одни и те же для двух дорог: папка библиотеки (SAF, её возит
 * сторонняя синхронизация) и облако по WebDAV ([Cloud]). Поэтому тела файлов
 * собираются и разбираются в companion, а SAF здесь - только транспорт.
 *
 * С 03.10 места пишутся ещё и файлом `места-<имя>@<устройство>.json` - по
 * файлу на человека И устройство. Один файл на человека затирался: телефон и
 * Boox Саши писали его по очереди целиком, и каждое устройство возвращало
 * поверх свои устаревшие места других книг. Теперь у файла один писатель, а
 * читающий сливает все файлы человека по книге и по каждому месту отдельно:
 * место в записи - самое свежее по времени слушания, место чтения - по
 * времени чтения. Прежний `позиции-<имя>.json` пишется дальше - для копий
 * приложения, которые ещё не обновились, - а новые его, записанный новыми же,
 * не читают: правда - в файлах мест.
 */
class PositionSync(private val context: Context) {

    data class Remote(
        val profile: String,
        val states: Map<String, BookState>,
        val at: Long,
        /** Чьё устройство; пусто - прежний файл на человека, без устройства. */
        val device: String = "",
    )

    /** Свои места на этом устройстве - файлом мест рядом с прежним файлом позиций. */
    fun pushPlaces(treeUri: Uri, profile: String, device: String, states: Map<String, BookState>) {
        if (profile.isBlank()) return
        write(treeUri, placesFileName(profile, device), placesJson(profile, device, states))
    }

    fun push(treeUri: Uri, profile: String, states: Map<String, BookState>) {
        if (profile.isBlank()) return
        write(treeUri, fileName(PREFIX, profile), positionsJson(profile, states))
    }

    /** История вопросов этой дорожки - целиком, файл невелик (полсотни на книгу). */
    fun pushAsks(treeUri: Uri, profile: String, asks: Map<String, List<Ask>>) {
        if (profile.isBlank()) return
        write(treeUri, fileName(ASKS_PREFIX, profile), asksJson(profile, asks))
    }

    /** Вопросы своей дорожки, записанные другим устройством; null - файла нет. */
    fun pullAsks(treeUri: Uri, profile: String): Map<String, List<Ask>>? =
        if (profile.isBlank()) null else read(treeUri, fileName(ASKS_PREFIX, profile))?.let(::parseAsks)

    /** Пометки этой дорожки - с надгробиями удалённых, чтобы слияние их не воскресило. */
    fun pushNotes(treeUri: Uri, profile: String, notes: Map<String, List<Note>>) {
        if (profile.isBlank()) return
        write(treeUri, fileName(NOTES_PREFIX, profile), notesJson(profile, notes))
    }

    /** Пометки своей дорожки, записанные другим устройством; null - файла нет. */
    fun pullNotes(treeUri: Uri, profile: String): Map<String, List<Note>>? =
        if (profile.isBlank()) null else read(treeUri, fileName(NOTES_PREFIX, profile))?.let(::parseNotes)

    /** Всё, что лежит в папке синхронизации, включая чужие дорожки. */
    fun pull(treeUri: Uri): List<Remote> = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirId = findChild(treeUri, rootId, DIR) ?: return emptyList()
        children(treeUri, dirId)
            .filter { isPositions(it.second) || isPlaces(it.second) }
            .mapNotNull { (docId, name) ->
                context.contentResolver.openInputStream(documentUri(treeUri, docId))
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?.let { if (isPlaces(name)) parsePlaces(it) else parseRemote(it) }
            }
    }.getOrDefault(emptyList())

    private fun write(treeUri: Uri, name: String, body: String) {
        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirId = ensureDir(treeUri, rootId, DIR) ?: return
            val fileId = ensureFile(treeUri, dirId, name) ?: return
            context.contentResolver.openOutputStream(documentUri(treeUri, fileId), "wt")?.use {
                it.write(body.toByteArray())
            }
        }
    }

    private fun read(treeUri: Uri, name: String): String? = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirId = findChild(treeUri, rootId, DIR) ?: return null
        val docId = findChild(treeUri, dirId, name) ?: return null
        context.contentResolver.openInputStream(documentUri(treeUri, docId))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // ------------------------------------------------------------------ SAF

    private fun children(treeUri: Uri, parentId: String): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) out += c.getString(0) to (c.getString(1) ?: "")
        }
        return out
    }

    private fun findChild(treeUri: Uri, parentId: String, name: String): String? =
        children(treeUri, parentId).firstOrNull { it.second == name }?.first

    private fun ensureDir(treeUri: Uri, parentId: String, name: String): String? {
        findChild(treeUri, parentId, name)?.let { return it }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUri(treeUri, parentId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    private fun ensureFile(treeUri: Uri, parentId: String, name: String): String? {
        findChild(treeUri, parentId, name)?.let { return it }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUri(treeUri, parentId),
            "application/json",
            name,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    companion object {
        const val DIR = "_Слушалка"
        const val PREFIX = "позиции-"
        const val ASKS_PREFIX = "вопросы-"
        const val NOTES_PREFIX = "пометки-"

        fun fileName(prefix: String, profile: String): String {
            val safe = profile.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()
            return "$prefix${safe.ifBlank { "без-имени" }}.json"
        }

        fun isPositions(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(".json")

        /** Места одного человека на одном устройстве: «места-Саша@Pixel 9.json». */
        const val PLACES_PREFIX = "места-"

        fun placesFileName(profile: String, device: String): String {
            fun clean(s: String) = s.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()
            return PLACES_PREFIX + clean(profile).ifBlank { "без-имени" } + "@" + clean(device).ifBlank { "устройство" } + ".json"
        }

        fun isPlaces(name: String): Boolean = name.startsWith(PLACES_PREFIX) && name.endsWith(".json")

        /**
         * Места человека на устройстве - два места у каждой книги, у каждого своё
         * время: `listen` - где остановился звук и когда слушали, `read` - где
         * остановились глаза, сколько всего знаков и когда читали. Книги, которые
         * не слушали и не читали, не пишутся.
         */
        fun placesJson(profile: String, device: String, states: Map<String, BookState>): String = JSONObject().apply {
            put("v", 2)
            put("profile", profile)
            put("device", device)
            put("at", System.currentTimeMillis())
            put("books", JSONObject().apply {
                states.forEach { (id, s) ->
                    if (s.listenAt <= 0 && s.readAt <= 0 && !s.finished) return@forEach
                    put(id, JSONObject().apply {
                        if (s.listenAt > 0) {
                            put("listen", JSONObject()
                                .put("file", s.fileIndex).put("pos", s.posMs).put("abs", s.absMs).put("at", s.listenAt))
                        }
                        if (s.readChar >= 0 && s.readAt > 0) {
                            put("read", JSONObject().put("char", s.readChar).put("of", s.textChars).put("at", s.readAt))
                        }
                        put("finished", s.finished)
                        put("at", s.updatedAt)
                    })
                }
            })
        }.toString()

        fun parsePlaces(text: String): Remote? = runCatching {
            val o = JSONObject(text)
            val books = o.optJSONObject("books") ?: JSONObject()
            Remote(
                profile = o.optString("profile"),
                at = o.optLong("at"),
                device = o.optString("device"),
                states = books.keys().asSequence().associateWith { id ->
                    val b = books.getJSONObject(id)
                    val l = b.optJSONObject("listen")
                    val r = b.optJSONObject("read")
                    BookState(
                        bookId = id,
                        fileIndex = l?.optInt("file") ?: 0,
                        posMs = l?.optLong("pos") ?: 0L,
                        absMs = l?.optLong("abs") ?: 0L,
                        listenAt = l?.optLong("at") ?: 0L,
                        readChar = r?.optInt("char", -1) ?: -1,
                        textChars = r?.optInt("of") ?: 0,
                        readAt = r?.optLong("at") ?: 0L,
                        finished = b.optBoolean("finished"),
                        updatedAt = b.optLong("at"),
                    )
                }.toMap(),
            )
        }.getOrNull()

        fun positionsJson(profile: String, states: Map<String, BookState>): String = JSONObject().apply {
            put("profile", profile)
            put("at", System.currentTimeMillis())
            // Писан копией, которая пишет и файлы мест: новые копии читают их, а
            // этот оставляют прежним - время у него одно на оба места.
            put("places", true)
            put("books", JSONObject().apply {
                states.forEach { (id, s) ->
                    put(id, JSONObject()
                        .put("file", s.fileIndex).put("pos", s.posMs).put("abs", s.absMs)
                        .put("at", s.updatedAt).put("finished", s.finished)
                        // Место чтения - для книг без записи это единственная
                        // позиция; у аудиокниги оно и так подтягивает запись.
                        .put("read", s.readChar))
                }
            })
        }.toString()

        fun parseRemote(text: String): Remote? = runCatching {
            val o = JSONObject(text)
            // Его писала новая копия - её места есть и файлом мест, точнее.
            if (o.optBoolean("places")) return null
            val books = o.optJSONObject("books") ?: JSONObject()
            Remote(
                profile = o.optString("profile"),
                at = o.optLong("at"),
                states = books.keys().asSequence().associateWith { id ->
                    val b = books.getJSONObject(id)
                    BookState(
                        bookId = id,
                        fileIndex = b.optInt("file"),
                        posMs = b.optLong("pos"),
                        absMs = b.optLong("abs"),
                        updatedAt = b.optLong("at"),
                        finished = b.optBoolean("finished"),
                        readChar = b.optInt("read", -1),
                        // Прежний файл знает одно время на оба места.
                        listenAt = b.optLong("at"),
                        readAt = if (b.optInt("read", -1) >= 0) b.optLong("at") else 0L,
                    )
                }.toMap(),
            )
        }.getOrNull()

        fun asksJson(profile: String, asks: Map<String, List<Ask>>): String = JSONObject().apply {
            put("profile", profile)
            put("at", System.currentTimeMillis())
            put("books", JSONObject().apply {
                asks.forEach { (id, list) ->
                    put(id, JSONArray().apply {
                        list.forEach {
                            put(JSONObject().put("at", it.at).put("abs", it.absMs)
                                .put("q", it.question).put("a", it.answer).put("usd", it.costUsd))
                        }
                    })
                }
            })
        }.toString()

        fun parseAsks(text: String): Map<String, List<Ask>> = runCatching {
            val books = JSONObject(text).optJSONObject("books") ?: return emptyMap()
            books.keys().asSequence().associateWith { id ->
                val arr = books.getJSONArray(id)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Ask(o.optLong("at"), o.optLong("abs"), o.optString("q"), o.optString("a"), o.optDouble("usd"))
                }
            }
        }.getOrDefault(emptyMap())

        fun notesJson(profile: String, notes: Map<String, List<Note>>): String = JSONObject().apply {
            put("profile", profile)
            put("at", System.currentTimeMillis())
            put("books", JSONObject().apply { notes.forEach { (id, list) -> put(id, Notes.toJson(list)) } })
        }.toString()

        fun parseNotes(text: String): Map<String, List<Note>> = runCatching {
            val books = JSONObject(text).optJSONObject("books") ?: return emptyMap()
            books.keys().asSequence().associateWith { id -> Notes.fromJson(books.getJSONArray(id)) }
        }.getOrDefault(emptyMap())
    }
}
