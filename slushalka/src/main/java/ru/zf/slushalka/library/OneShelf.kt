package ru.zf.slushalka.library

import ru.zf.slushalka.data.ServerLibrary

/**
 * Одна полка: правда о книге - на сервере библиотеки.
 *
 * Владелец, 05.10: «не должно быть деления на телефон и сервер - это ужасно,
 * всё сбивает». До этого телефон и сервер узнавали книгу только по имени
 * папки, а на телефоне имена старые: Синдбад жил под тремя ключами, Джордж -
 * под двумя, и у каждой копии были свои места, а разбор сервера видела
 * только копия с серверным именем.
 *
 * Теперь своя папка на телефоне - копия книги сервера и живёт под её ключом
 * (`<имя главной папки>/<папка в Книги/>`), как бы ни называлась. Какой книге
 * сервера она копия - по порядку:
 * 1. ответ сверки полки ([ru.zf.slushalka.data.ShelfSync]) - псевдоним
 *    «ключ телефона → папка сервера»;
 * 2. то же имя папки (без регистра);
 * 3. прежнее имя книги в `index.json` (`former`) - его туда кладёт сверка
 *    любого устройства.
 * Название, автор, серия и обложка у узнанной копии - серверные. Несколько
 * копий одной книги - одна на полке (главная: больше своего звука, потом с
 * серверным именем), остальные лишние: их данные сливаются в общий ключ, а
 * файлы удаляются, когда сверка скажет, что у сервера есть всё, что в них.
 */
object OneShelf {

    data class Adopted(
        /** Книги с телефона по одной на ключ; узнанные - под ключом сервера. */
        val books: List<Book>,
        /** Лишние копии узнанных книг - под тем же ключом, что главная. */
        val extras: List<Book>,
        /** Ключ своей папки → ключ книги: по нему переносятся данные. */
        val keyOf: Map<String, String>,
    )

    /** Какой книге сервера эта папка - копия. null - сервер о ней не знает. */
    fun serverOf(
        b: Book,
        index: ServerLibrary.Index,
        aliases: Map<String, String>,
    ): ServerLibrary.ServerBook? =
        aliases[b.phoneKey]?.let(index::byFolder)
            ?: index.byFolder(b.phoneFolder)
            ?: index.byFormer(b.phoneKey)

    /** Ключ книги сервера: тот же, каким он был бы у скачанной. */
    fun idOf(rootName: String, sb: ServerLibrary.ServerBook): String = "$rootName/${sb.folder}"

    /**
     * Книги телефона ([raw] - как их нашёл сканер, ключ - путь папки) под
     * ключами сервера. Без оглавления или имени главной папки - как есть.
     */
    fun adopt(
        raw: List<Book>,
        index: ServerLibrary.Index?,
        rootName: String?,
        aliases: Map<String, String>,
    ): Adopted {
        if (index == null || rootName.isNullOrBlank()) {
            return Adopted(raw, emptyList(), raw.associate { it.phoneKey to it.id })
        }
        val groups = LinkedHashMap<String, MutableList<Book>>()
        val servers = HashMap<String, ServerLibrary.ServerBook>()
        val loose = ArrayList<Book>()
        for (b in raw) {
            val sb = serverOf(b, index, aliases)
            if (sb == null) {
                loose += b
                continue
            }
            val k = ServerLibrary.folderKey(sb.folder)
            servers[k] = sb
            groups.getOrPut(k) { mutableListOf() } += b
        }
        val books = ArrayList<Book>()
        val extras = ArrayList<Book>()
        val keyOf = HashMap<String, String>()
        for ((k, copies) in groups) {
            val sb = servers.getValue(k)
            val id = idOf(rootName, sb)
            copies.sortedWith(primaryOrder(sb)).forEachIndexed { i, b ->
                keyOf[b.phoneKey] = id
                val dressed = dress(b, sb, index, id)
                if (i == 0) books += dressed else extras += dressed
            }
        }
        val taken = books.mapTo(HashSet()) { it.id }
        for (b in loose) {
            // Своя папка, чей путь совпал с ключом узнанной книги, - двойник: на
            // полке двойных ключей быть не может.
            if (b.id in taken) {
                extras += b
                keyOf[b.phoneKey] = b.id
                continue
            }
            books += b
            keyOf[b.phoneKey] = b.id
        }
        return Adopted(books.sortedWith(compareBy(NaturalOrder) { it.id }), extras, keyOf)
    }

    /**
     * Главная копия: где больше своего звука (живую запись не меняют на
     * текст), потом - с серверным именем (её не надо переименовывать), потом
     * с текстом.
     */
    fun primaryOrder(sb: ServerLibrary.ServerBook): Comparator<Book> =
        compareByDescending<Book> { it.ownAudioBytes }
            .thenByDescending { ServerLibrary.folderKey(it.phoneFolder) == ServerLibrary.folderKey(sb.folder) }
            .thenByDescending { it.textDocId != null }
            .thenBy(NaturalOrder) { it.phoneKey }

    /**
     * Своя копия в одежде книги сервера: ключ, название, автор, серия,
     * обложка, папка там. Чего у копии своего нет - звука или текста, - то
     * берётся с сервера: звук играет потоком, текст качается во временный
     * кэш. Свой звук и свой текст главнее.
     */
    private fun dress(b: Book, sb: ServerLibrary.ServerBook, index: ServerLibrary.Index, id: String): Book {
        val dir = index.dirOf(sb)
        val serverSeries = sb.series?.takeIf { it.isNotBlank() }
        val ownAudio = b.files.any { !it.isRemote }
        val serverText = sb.mainText?.takeIf { b.textDocId == null }
        return b.copy(
            files = if (!ownAudio && sb.audio.isNotEmpty()) ServerLibrary.toBook(index, sb, "", "").files else b.files,
            textRemote = serverText?.let { "$dir/${it.path}" } ?: b.textRemote,
            textName = b.textName ?: serverText?.name,
            id = id,
            phoneId = if (b.phoneKey != id) b.phoneKey else "",
            title = sb.title,
            author = sb.author,
            series = serverSeries ?: b.series,
            seriesNum = if (serverSeries != null) sb.seriesNum else b.seriesNum,
            remoteDir = dir,
            coverRemote = sb.cover?.let { "$dir/$it" } ?: b.coverRemote,
        )
    }

    /**
     * Ключ книги для места, вопроса, пометки с другого устройства: там папка
     * могла называться по-старому, а главная папка - по-другому. Узнанное
     * сервером - под ключом сервера здесь, остальное как есть.
     */
    fun canonical(
        id: String,
        keyOf: Map<String, String>,
        index: ServerLibrary.Index?,
        rootName: String?,
        aliases: Map<String, String>,
    ): String {
        keyOf[id]?.let { return it }
        if (index == null || rootName.isNullOrBlank()) return id
        val folder = id.substringAfterLast('/')
        val sb = aliases[id]?.let(index::byFolder) ?: index.byFolder(folder) ?: index.byFormer(id) ?: return id
        return idOf(rootName, sb)
    }
}
