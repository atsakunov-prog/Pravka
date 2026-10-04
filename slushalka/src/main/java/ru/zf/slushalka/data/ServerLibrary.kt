package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.BookFile
import ru.zf.slushalka.library.NaturalOrder

/**
 * Библиотека на домашнем сервере - все книги, а не только те, что на телефоне.
 *
 * Сервер (rclone по WebDAV) держит в корне `index.json` - оглавление всей
 * библиотеки одним файлом: папка, автор, название, текст, обложка, аудио с
 * длительностями. Пересобирает его сам - после каждой новой книги и раз в
 * десять минут, если в `Книги/` что-то поменялось. Поэтому полка «В
 * библиотеке» - это один GET, а не обход сотни папок PROPFIND'ом, и
 * длительности не надо мерить: сервер уже намерил.
 *
 * Оглавление лежит копией на диске: полка открывается сразу и без сети, а
 * свежее подтягивается при открытии и потягиванием вниз. `If-None-Match`
 * rclone не понимает, но файл маленький - качается целиком.
 */
class ServerLibrary(
    private val context: Context,
    private val settings: Settings,
    private val cloud: Cloud,
    scope: CoroutineScope,
) {

    /** Файл книги на сервере: путь внутри её папки через «/», размер и длительность. */
    data class Entry(val path: String, val size: Long, val ms: Long = 0L) {
        val name: String get() = path.substringAfterLast('/')
    }

    /** Книга в оглавлении сервера. */
    data class ServerBook(
        /** Имя папки в `Книги/`: сервер его не меняет, по нему и узнаётся книга. */
        val folder: String,
        val author: String,
        val title: String,
        /** Когда папка менялась, секунды. */
        val modified: Long,
        val text: List<Entry>,
        /** Обложка - путь внутри папки; null - нет. */
        val cover: String?,
        val audio: List<Entry>,
        val audioMs: Long,
        val other: List<Entry>,
        /** Серия и номер в ней - если сервер их кладёт в оглавление (`series`, `series_index`). */
        val series: String? = null,
        val seriesNum: String? = null,
    ) {
        val audioBytes: Long get() = audio.sumOf { it.size }

        /** Текст, который возьмёт читалка: fb2 размечен точнее epub - как у сканера полки. */
        val mainText: Entry?
            get() = text.firstOrNull { it.path.endsWith(".fb2", true) }
                ?: text.firstOrNull { it.path.endsWith(".fb2.zip", true) }
                ?: text.firstOrNull { it.path.endsWith(".epub", true) }
                ?: text.firstOrNull()

        /**
         * Что ложится на полку «только текстом»: сам текст и файлы Слушалки
         * рядом с книгой - разметка, справочник. Обложка качается отдельно:
         * её размера оглавление не знает.
         */
        val textKit: List<Entry>
            get() = listOfNotNull(mainText) + other.filter { o ->
                '/' !in o.path && o.path.startsWith(OUR_PREFIX) && o.path.endsWith(".json") && o.path != MARKER
            }

        val textBytes: Long get() = mainText?.size ?: 0L

        /** Озвучена нейросетью на сервере: в папке лежит [MACHINE_MARK] (см. NightVoice). */
        val machineVoiced: Boolean get() = other.any { it.path == MACHINE_MARK }
    }

    data class Index(
        val generated: String,
        /** Папка книг от корня сервера - «Книги». */
        val booksDir: String,
        val books: List<ServerBook>,
        /** Когда оглавление пришло с сервера, по часам телефона. */
        val fetchedAt: Long,
    ) {
        /** Папка книги от корня облака. */
        fun dirOf(b: ServerBook): String = "$booksDir/${b.folder}"

        /** Книга по имени папки, без учёта регистра: так их сравнивают телефон и сервер. */
        fun byFolder(folder: String): ServerBook? = keyed[folderKey(folder)]

        private val keyed: Map<String, ServerBook> by lazy { books.associateBy { folderKey(it.folder) } }
    }

    sealed interface Status {
        /** Ничего не делаем: облака нет или оглавление свежее. */
        data object Idle : Status
        data object Loading : Status
        /** Сервер ответил, но оглавления в корне нет - это не домашняя библиотека. */
        data object Missing : Status
        data class Failed(val message: String) : Status
    }

    private val _index = MutableStateFlow<Index?>(null)
    val index: StateFlow<Index?> = _index

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val file = File(context.filesDir, "server-index.json")
    private val sourceFile = File(context.filesDir, "server-index.src")
    private val coverDir get() = File(context.cacheDir, "server-covers").apply { mkdirs() }
    /** Обложки качаются по нескольку разом, а не всей сеткой: серверу и сети так легче. */
    private val coverGate = Semaphore(4)

    init {
        scope.launch(Dispatchers.IO) {
            // Копия с диска годится, только если она с того же сервера: сменил
            // облако в настройках - чужое оглавление не показываем.
            val p = settings.flow.first { it.loaded }
            if (!p.cloudReady || _index.value != null) return@launch
            val src = runCatching { sourceFile.readText() }.getOrNull() ?: return@launch
            if (src.substringBefore('\n') != sourceOf(p)) return@launch
            val at = src.substringAfter('\n', "").toLongOrNull() ?: file.lastModified()
            val parsed = runCatching { parse(file.readText(), at) }.getOrNull() ?: return@launch
            if (_index.value == null) _index.value = parsed
        }
    }

    /** Чьё оглавление: адрес, папка, логин. Другой сервер - другая библиотека. */
    private fun sourceOf(p: Settings.Prefs) = p.cloudUrl.trimEnd('/') + "|" + p.cloudDir.trim('/') + "|" + p.cloudUser

    /** Подтянуть свежее, если прошлое старше [maxAgeMs]: так экран не дёргает сервер на каждый заход. */
    suspend fun refreshIfStale(maxAgeMs: Long = 60_000L) {
        val known = _index.value
        if (known != null && System.currentTimeMillis() - known.fetchedAt < maxAgeMs) return
        refresh()
    }

    /** Оглавление с сервера. Не вышло - остаётся прежнее: по нему можно и без сети. */
    suspend fun refresh(): Index? {
        val p = settings.now()
        if (!p.cloudReady) {
            _status.value = Status.Idle
            return null
        }
        if (_status.value == Status.Loading) return _index.value
        _status.value = Status.Loading
        try {
            val raw = cloud.getText(INDEX).getOrElse { e ->
                _status.value = Status.Failed(e.message ?: "Сервер не ответил")
                return _index.value
            }
            if (raw == null) {
                // 404: в корне облака оглавления нет - это Яндекс.Диск или папка не та.
                _index.value = null
                _status.value = Status.Missing
                return null
            }
            val now = System.currentTimeMillis()
            val parsed = withContext(Dispatchers.Default) { runCatching { parse(raw, now) } }.getOrElse { e ->
                _status.value = Status.Failed("Оглавление на сервере не разобралось: ${e.message}")
                return _index.value
            }
            _index.value = parsed
            _status.value = Status.Idle
            withContext(Dispatchers.IO) {
                runCatching {
                    Store.writeAtomic(file, raw)
                    Store.writeAtomic(sourceFile, sourceOf(p) + "\n" + now)
                }
            }
            return parsed
        } finally {
            // Отменили на полпути (ушли с экрана) - «читаю оглавление» не должно висеть вечно.
            if (_status.value == Status.Loading) _status.value = Status.Idle
        }
    }

    /** Облако сменили: прежнее оглавление больше не про этот сервер. */
    fun forget() {
        _index.value = null
        _status.value = Status.Idle
        runCatching { sourceFile.delete() }
    }

    // --------------------------------------------------------------- обложки

    /** Обложка с сервера - с диска, если уже качали, иначе GET и на диск. */
    suspend fun coverBytes(remotePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = coverFile(remotePath)
        if (f.length() > 0) return@withContext runCatching { f.readBytes() }.getOrNull()
        coverGate.withPermit {
            if (f.length() > 0) return@withPermit runCatching { f.readBytes() }.getOrNull()
            val tmp = File(f.parentFile, f.name + ".part")
            val ok = cloud.download(remotePath) { input, _ ->
                tmp.outputStream().use { input.copyTo(it) }
            }.isSuccess && tmp.length() > 0 && tmp.renameTo(f)
            if (!ok) {
                tmp.delete()
                null
            } else runCatching { f.readBytes() }.getOrNull()
        }
    }

    /** Обложка, уже лежащая на диске: шторке плеера нужен файл, а не байты. */
    fun cachedCover(remotePath: String): File? = coverFile(remotePath).takeIf { it.length() > 0 }

    private fun coverFile(remotePath: String) = File(coverDir, sha16(remotePath) + ".img")

    companion object {
        /** Оглавление в корне облака. */
        const val INDEX = "index.json"

        /** Файлы Слушалки рядом с книгой: разметка, справочник, метка звука. */
        const val OUR_PREFIX = "слушалка-"

        /**
         * Метка «звук этой книги на сервере» в её папке на телефоне. Книга,
         * взятая только текстом, без неё выглядела бы для сканера книгой без
         * записи; с ней - обычная аудиокнига, звук которой играет потоком.
         * Внутри - папка на сервере и файлы с длительностями: полка собирает
         * книгу и без сети.
         */
        const val MARKER = "слушалка-звук.json"

        /**
         * Признак машинной озвучки в папке книги: его кладёт служба озвучки
         * сервера вместе с главами и убирает, когда приходит живая запись.
         * Содержимое приложению не нужно - хватает самого факта.
         */
        const val MACHINE_MARK = "озвучка.json"

        fun folderKey(folder: String): String = folder.trim().lowercase()

        fun parse(text: String, fetchedAt: Long = 0L): Index {
            val o = JSONObject(text)
            val arr = o.optJSONArray("books") ?: JSONArray()
            val books = (0 until arr.length()).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                val folder = b.optString("folder").trim()
                if (folder.isBlank()) return@mapNotNull null
                ServerBook(
                    folder = folder,
                    author = b.optString("author").trim(),
                    title = b.optString("title").trim().ifBlank { folder },
                    modified = b.optLong("modified"),
                    text = entries(b.optJSONArray("text")),
                    cover = b.optString("cover").takeIf { !b.isNull("cover") && it.isNotBlank() },
                    audio = entries(b.optJSONArray("audio")).sortedWith(AudioOrder),
                    audioMs = b.optLong("audio_ms"),
                    other = entries(b.optJSONArray("other")),
                ).let { book -> seriesOf(b)?.let { (name, num) -> book.copy(series = name, seriesNum = num) } ?: book }
            }
            return Index(
                generated = o.optString("generated"),
                booksDir = o.optString("books_dir").trim('/').ifBlank { Cloud.BOOKS_DIR },
                books = books.distinctBy { folderKey(it.folder) },
                fetchedAt = fetchedAt,
            )
        }

        /**
         * Серия книги в оглавлении. Сервер может положить её строкой с номером
         * рядом (`series` + `series_index`), объектом (`{"name", "number"}`) или
         * списком таких объектов, как `<sequence>` в fb2, - берётся первая.
         */
        private fun seriesOf(b: JSONObject): Pair<String, String?>? {
            for (key in listOf("series", "sequence")) {
                if (!b.has(key) || b.isNull(key)) continue
                val obj = when (val v = b.opt(key)) {
                    is JSONObject -> v
                    is JSONArray -> v.optJSONObject(0)
                    else -> null
                }
                if (obj != null) {
                    val name = str(obj, "name", "title")?.takeIf { it.isNotBlank() } ?: continue
                    return name to number(obj, "number", "index", "num", "position")
                }
                val name = str(b, key)?.takeIf { it.isNotBlank() } ?: continue
                return name to number(b, "series_index", "series_number", "series_num", "sequence_number", "sequence_index")
            }
            return str(b, "series_name")?.takeIf { it.isNotBlank() }
                ?.let { it to number(b, "series_index", "series_number", "series_num") }
        }

        /** Строка из первого поля, что есть и не null: имя поля у сервера могли выбрать любое из похожих. */
        private fun str(o: JSONObject, vararg keys: String): String? =
            keys.firstOrNull { o.has(it) && !o.isNull(it) }?.let { o.optString(it).trim() }

        /** Номер в серии: сервер может прислать и числом, и строкой; «2.0» - это «2». */
        private fun number(o: JSONObject, vararg keys: String): String? {
            val key = keys.firstOrNull { o.has(it) && !o.isNull(it) } ?: return null
            val raw = o.opt(key)
            val d = (raw as? Number)?.toDouble() ?: raw.toString().trim().replace(',', '.').toDoubleOrNull()
                ?: return raw.toString().trim().takeIf { it.isNotBlank() }
            if (d <= 0.0) return null
            return if (d == Math.floor(d)) d.toLong().toString() else d.toString()
        }

        private fun entries(a: JSONArray?): List<Entry> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val path = e.optString("path").trim('/')
                if (path.isBlank()) null else Entry(path, e.optLong("size"), e.optLong("ms"))
            }
        }

        /**
         * Порядок слушания - как у сканера полки, а не как прислал сервер:
         * сперва файлы корня папки по-человечески («2» перед «10»), потом
         * подпапки по тем же правилам. Совпадает с тем, что увидит телефон,
         * когда книга скачается, - иначе позиция уехала бы в другой файл.
         */
        val AudioOrder = Comparator<Entry> { a, b ->
            val pa = a.path.split('/')
            val pb = b.path.split('/')
            var i = 0
            while (i < pa.size && i < pb.size) {
                val lastA = i == pa.lastIndex
                val lastB = i == pb.lastIndex
                // На одной ступени файл раньше папки: корень слушают первым.
                if (lastA != lastB) return@Comparator if (lastA) -1 else 1
                val c = NaturalOrder.compare(pa[i], pb[i])
                if (c != 0) return@Comparator c
                i++
            }
            pa.size - pb.size
        }

        /**
         * Книга сервера в виде книги полки. Ключ - такой, каким он станет после
         * скачивания: `<имя главной папки>/<папка>`. Тогда позиция, разметка,
         * справочник, вопросы и пометки переходят между «с сервера» и
         * «скачано» без потерь.
         */
        fun toBook(index: Index, b: ServerBook, rootName: String, tree: String): Book {
            val dir = index.dirOf(b)
            val text = b.mainText
            return Book(
                id = "$rootName/${b.folder}",
                folderDocId = "",
                tree = tree,
                title = b.title,
                author = b.author,
                files = b.audio.map { e ->
                    BookFile(
                        docId = "",
                        name = e.name,
                        relPath = e.path,
                        size = e.size,
                        durationMs = e.ms,
                        remote = "$dir/${e.path}",
                    )
                },
                textName = text?.name,
                remoteDir = dir,
                textRemote = text?.let { "$dir/${it.path}" },
                coverRemote = b.cover?.let { "$dir/$it" },
                series = b.series,
                seriesNum = b.seriesNum,
            )
        }

        private fun sha16(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
