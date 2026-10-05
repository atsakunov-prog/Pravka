package ru.zf.slushalka.text

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri

/**
 * Текст книги: разбирается один раз и ложится в кэш приложения. Разбор
 * восьмисотстраничного романа стоит секунду-другую, и платить её на каждый
 * вопрос не за что.
 *
 * Книга, открытая прямо с сервера библиотеки, своего файла текста не имеет:
 * он качается во временный кэш, разбирается и стирается - разобранное
 * остаётся, как у любой книги, а файл понадобится разве что для «разобрать
 * заново», и тогда скачается снова.
 */
class TextRepo(private val context: Context, private val cloud: ru.zf.slushalka.data.Cloud) {

    private val dir get() = File(context.filesDir, "text").apply { mkdirs() }
    private val coverDir get() = File(context.filesDir, "covers").apply { mkdirs() }
    private val memory = HashMap<String, BookText>()
    private val reports = HashMap<String, ParseReport>()

    /** Что разбор увидел в файле книги - показывается, когда картинок нет. */
    fun reportFor(bookId: String): ParseReport? = reports[bookId]

    /** Забыть разобранное: следующий заход прочитает файл книги заново. */
    fun forget(bookId: String) {
        memory.remove(bookId)
        reports.remove(bookId)
        val k = key(bookId)
        runCatching { File(dir, "$k.txt").delete() }
        runCatching { File(dir, "$k.json").delete() }
        runCatching { picturesDir(bookId).listFiles()?.forEach { it.delete() } }
    }

    fun coverFile(bookId: String): File = File(coverDir, key(bookId) + ".img")

    /**
     * Разобранный текст, обложка и картинки - под ключ сервера. Под новым
     * ключом своё уже есть - прежнее просто убирается: разобрать заново
     * недолго, а два кэша одной книги ни к чему.
     */
    @Synchronized
    fun rekey(old: String, new: String): Boolean {
        if (old == new) return false
        val ko = key(old)
        val kn = key(new)
        var any = false
        fun move(from: File, to: File) {
            if (!from.exists()) return
            any = true
            if (!to.exists()) from.renameTo(to) else from.deleteRecursively()
        }
        val pics = File(context.filesDir, "images")
        // Текст и его разметка - парой: половина пары под новым ключом бесполезна.
        if (File(dir, "$kn.txt").exists()) {
            move(File(dir, "$ko.txt"), File(dir, "$kn.txt"))
            move(File(dir, "$ko.json"), File(dir, "$kn.json"))
            move(File(pics, ko), File(pics, kn))
        } else {
            File(dir, "$kn.json").delete()
            File(pics, kn).deleteRecursively()
            move(File(dir, "$ko.txt"), File(dir, "$kn.txt"))
            move(File(dir, "$ko.json"), File(dir, "$kn.json"))
            move(File(pics, ko), File(pics, kn))
        }
        move(File(coverDir, "$ko.img"), File(coverDir, "$kn.img"))
        val hadNew = memory.containsKey(new)
        memory.remove(old)?.let { if (!hadNew) memory[new] = it }
        memorySrc.remove(old)?.let { if (!hadNew) memorySrc[new] = it }
        reports.remove(old)?.let { if (!reports.containsKey(new)) reports[new] = it }
        return any
    }

    /** Папка с картинками книги: карты, планы, портреты из fb2/epub. */
    fun picturesDir(bookId: String): File =
        File(File(context.filesDir, "images"), key(bookId)).apply { mkdirs() }

    fun pictureFile(bookId: String, name: String): File = File(picturesDir(bookId), name)

    /**
     * Все картинки, вынутые из файла книги, - в порядке появления.
     *
     * Показываются даже те, которым не нашлось места в тексте: карта нужна
     * читателю независимо от того, сумел ли разбор понять, к какому абзацу
     * она относится.
     */
    fun allPictures(bookId: String): List<File> =
        picturesDir(bookId).listFiles()?.sortedBy { it.lastModified() }.orEmpty()

    fun cached(bookId: String): BookText? = memory[bookId]

    /**
     * Разбирает текст (или достаёт из кэша). null - текста рядом с аудио нет.
     * [onDownload] - доля скачанного, когда текст качается с сервера.
     */
    suspend fun textFor(treeUri: Uri, book: Book, onDownload: (Int) -> Unit = {}): BookText? = withContext(Dispatchers.IO) {
        val src = sourceOf(book)
        memory[book.id]?.takeIf { memorySrc[book.id].let { s -> s == null || s == src } }?.let { return@withContext it }
        val k = key(book.id)
        val txt = File(dir, "$k.txt")
        val meta = File(dir, "$k.json")
        if (txt.exists() && meta.exists()) {
            val cached = runCatching {
                val json = JSONObject(meta.readText())
                // Кэш прежней версии разбирали, когда картинки ещё не доставали.
                // Такой перечитываем заново, иначе они не появятся никогда.
                if (json.optInt("v") < BookText.CACHE_VERSION) null
                // Разобран из другого файла: под ключом сервера мог лежать текст
                // сервера, а своя копия - другое издание.
                else if (json.optString("src").let { it.isNotBlank() && it != src }) null
                else BookText.fromMeta(txt.readText(), json)
            }.getOrNull()
            if (cached != null) {
                memory[book.id] = cached
                memorySrc[book.id] = src
                reports[book.id] = runCatching {
                    ParseReport.fromJson(JSONObject(meta.readText()).optJSONObject("report"))
                }.getOrDefault(ParseReport())
                return@withContext cached
            }
        }
        // Свой файл - документом SAF; книга с сервера - скачанным во временный кэш.
        var downloaded: File? = null
        val source: Uri = book.textDocId?.let { documentUri(treeUri, it) }
            ?: book.textRemote?.let { remote -> download(book, remote, onDownload)?.also { downloaded = it }?.let(Uri::fromFile) }
            ?: return@withContext null
        // Картинки уезжают на диск прямо по ходу разбора: держать в памяти
        // десяток разворотов ни к чему.
        val pics = picturesDir(book.id)
        val refToFile = HashMap<String, String>()
        val parsed = runCatching {
            parse(source, book.textName.orEmpty()) { ref, bytes ->
                // Регистр ссылки и регистр id в книге совпадают не всегда.
                val norm = ref.lowercase()
                val name = key(norm) + ".img"
                runCatching { File(pics, name).writeBytes(bytes) }
                    .onSuccess { refToFile[norm] = name }
            }
        }.getOrNull()
        // Скачанное больше не нужно: разобранное ляжет в кэш ниже.
        downloaded?.delete()
        if (parsed == null || parsed.text.length < 200) return@withContext null
        // Картинка без файла на диске нарисовалась бы дырой - такие выбрасываем.
        val ready = if (parsed.text.pictures.isEmpty()) parsed.text else BookText(
            plain = parsed.text.plain,
            chapters = parsed.text.chapters,
            title = parsed.text.title,
            author = parsed.text.author,
            pictures = parsed.text.pictures.mapNotNull { pic ->
                refToFile[pic.ref.lowercase()]?.let { pic.copy(file = it) }
            },
        )
        val report = parsed.report.copy(
            written = refToFile.size,
            matched = ready.pictures.size,
        )
        reports[book.id] = report
        runCatching {
            txt.writeText(ready.plain)
            meta.writeText(ready.metaJson().put("report", report.toJson()).put("src", src).toString())
            parsed.cover?.let { bytes -> if (bytes.size > 1000) coverFile(book.id).writeBytes(bytes) }
        }
        memory[book.id] = ready
        memorySrc[book.id] = src
        ready
    }

    /** Из какого файла разобран текст: своя копия и книга сервера под одним ключом бывают разными изданиями. */
    private fun sourceOf(book: Book): String =
        book.textName ?: book.textRemote?.substringAfterLast('/') ?: ""

    private val memorySrc = HashMap<String, String>()

    /** Текст с сервера - во временный кэш, с долей скачанного. null - не скачался. */
    private suspend fun download(book: Book, remote: String, onProgress: (Int) -> Unit): File? {
        val dir = File(context.cacheDir, "server-text").apply { mkdirs() }
        val out = File(dir, key(book.id) + "." + remote.substringAfterLast('.', "bin").lowercase())
        val ok = cloud.download(remote) { input, total ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                var shown = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    got += n
                    val pct = if (total > 0) (got * 100 / total).toInt() else -1
                    if (pct != shown && pct >= 0) {
                        shown = pct
                        onProgress(pct)
                    }
                }
            }
        }.isSuccess
        if (!ok) out.delete()
        return out.takeIf { ok && it.length() > 0 }
    }

    private fun parse(
        uri: Uri,
        name: String,
        onImage: (String, ByteArray) -> Unit,
    ): ParsedBook {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".fb2") -> context.contentResolver.openInputStream(uri)!!
                .use { Fb2Parser.parse(it, onImage) }
            lower.endsWith(".epub") -> EpubParser.parse(copyToCache(uri, "book.epub"), onImage)
            else -> {
                // .fb2.zip и просто .zip: внутри почти всегда один fb2.
                val f = copyToCache(uri, "book.zip")
                val fb2 = ZipFile(f).use { zip ->
                    val e = zip.entries().asSequence()
                        .firstOrNull { it.name.endsWith(".fb2", true) }
                    if (e == null) null else zip.getInputStream(e).use { Fb2Parser.parse(it, onImage) }
                }
                fb2 ?: EpubParser.parse(f, onImage)
            }
        }
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun key(bookId: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(bookId.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(16)
    }
}
