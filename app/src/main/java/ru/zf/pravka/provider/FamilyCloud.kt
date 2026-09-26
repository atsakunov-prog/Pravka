package ru.zf.pravka.provider

import java.io.File

/**
 * Облако семьи — куда ездят общие Деньги и ночные копии базы (26.09.2026):
 * домашний сервер владельца по WebDAV ([HomeCloud]) или семейный Google
 * Drive ([DriveCloud]). Обмену и копиям всё равно, куда: им нужна папка по
 * пути, список, прочитать, записать, удалить. Какое облако сейчас — решает
 * `PravkaApp.familyCloud()`: задан домашний сервер — он, иначе Drive, если
 * вошли.
 */
interface FamilyCloud {

    /** Для журнала и экрана: «домашний сервер», «Google Drive». */
    val title: String

    /** Кто это облако: копия базы, уехавшая в одно, поедет и в другое. */
    val id: String

    /**
     * Файл в папке. [md5] есть у Drive; у домашнего сервера пусто — rclone
     * md5 не считает (книги по сотне МБ пересчитывались бы на каждом
     * списке), там версия — размер: журналы Денег только дописываются, а
     * заменяются на сервере целиком и разом (временное имя и перенос).
     */
    data class Item(val name: String, val size: Long, val md5: String)

    class CloudException(message: String) : Exception(message)

    /** Что лежит в папке [path] от корня облака; папки нет — заводится. */
    suspend fun list(path: List<String>): List<Item>

    suspend fun read(path: List<String>, name: String): ByteArray

    /** Записать файл целиком: нового нет — появится, есть — заменится. */
    suspend fun write(path: List<String>, name: String, bytes: ByteArray, mime: String)

    /** Большой файл — потоком с диска. */
    suspend fun upload(path: List<String>, name: String, file: File, mime: String): Item

    suspend fun delete(path: List<String>, name: String)

    companion object {
        /** Ошибка облака — словами для владельца, целиком (железное правило 6). */
        fun why(e: Throwable, title: String): String = when (e) {
            is GoogleAuth.AuthException, is GoogleDrive.DriveException, is WebDav.WebDavException, is CloudException ->
                e.message.orEmpty()
            is java.net.UnknownHostException -> "$title: нет сети или адрес не находится (${e.message})"
            is java.net.ConnectException -> "$title: не отвечает (${e.message})"
            is java.net.SocketTimeoutException -> "$title: не ответил вовремя (${e.message})"
            is javax.net.ssl.SSLException -> "$title: не сложилось HTTPS-соединение (${e.message})"
            else -> "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

/** Домашний сервер: WebDAV по адресу, логину и паролю из настроек. */
class HomeCloud(private val dav: WebDav, private val config: () -> WebDav.Config?) : FamilyCloud {

    override val title = "домашний сервер"

    override val id: String get() = "webdav:" + (config()?.url?.trimEnd('/') ?: "")

    private fun c(): WebDav.Config = config() ?: throw FamilyCloud.CloudException("Домашний сервер не задан")

    override suspend fun list(path: List<String>): List<FamilyCloud.Item> {
        val all = dav.list(c(), path, create = true)
        // Недописанные чужие и свои временные файлы: старше суток — мусор от
        // оборвавшейся записи (свежий может быть чужой записью прямо сейчас).
        val stale = System.currentTimeMillis() - 86_400_000L
        for (t in all) if (!t.folder && WebDav.Dav.isTemp(t.name) && t.modified in 1 until stale) {
            runCatching { dav.delete(c(), path, t.name) }
        }
        return all.filter { !it.folder && !WebDav.Dav.isTemp(it.name) }.map { FamilyCloud.Item(it.name, it.size, "") }
    }

    override suspend fun read(path: List<String>, name: String) = dav.read(c(), path, name)

    override suspend fun write(path: List<String>, name: String, bytes: ByteArray, mime: String) =
        dav.write(c(), path, name, bytes, mime)

    override suspend fun upload(path: List<String>, name: String, file: File, mime: String): FamilyCloud.Item {
        dav.upload(c(), path, name, file, mime)
        return FamilyCloud.Item(name, file.length(), "")
    }

    override suspend fun delete(path: List<String>, name: String) = dav.delete(c(), path, name)
}

/**
 * Семейный Google Drive: в нём нет путей, есть папки с номерами — номера
 * файлов берутся из последнего списка папки.
 */
class DriveCloud(private val drive: GoogleDrive, private val email: () -> String) : FamilyCloud {

    override val title = "Google Drive"

    override val id: String get() = "drive:" + email()

    /** Путь папки → (имя файла → номер) по последнему списку. */
    private val ids = HashMap<String, Map<String, String>>()

    private suspend fun idOf(path: List<String>, name: String): String? {
        val key = path.joinToString("/")
        // Папку уже видели — её список и есть ответ (нет в нём — файла нет).
        synchronized(ids) { ids[key] }?.let { return it[name] }
        list(path)
        return synchronized(ids) { ids[key]?.get(name) }
    }

    override suspend fun list(path: List<String>): List<FamilyCloud.Item> {
        val items = drive.list(drive.folder(path)).filter { !it.folder }
        synchronized(ids) { ids[path.joinToString("/")] = items.associate { it.name to it.id } }
        return items.map { FamilyCloud.Item(it.name, it.size, it.md5) }
    }

    override suspend fun read(path: List<String>, name: String): ByteArray {
        val id = idOf(path, name) ?: throw FamilyCloud.CloudException("Google Drive: нет файла ${path.joinToString("/")}/$name")
        return drive.download(id)
    }

    override suspend fun write(path: List<String>, name: String, bytes: ByteArray, mime: String) {
        val id = idOf(path, name)
        val item = if (id == null) drive.create(drive.folder(path), name, bytes, mime) else drive.update(id, bytes, mime)
        remember(path, item)
    }

    override suspend fun upload(path: List<String>, name: String, file: File, mime: String): FamilyCloud.Item {
        val item = drive.uploadFile(drive.folder(path), name, file, mime, existingId = idOf(path, name))
        remember(path, item)
        return FamilyCloud.Item(item.name, item.size, item.md5)
    }

    override suspend fun delete(path: List<String>, name: String) {
        val id = idOf(path, name) ?: return
        drive.delete(id)
        synchronized(ids) { ids[path.joinToString("/")]?.let { ids[path.joinToString("/")] = it - name } }
    }

    fun forget() {
        synchronized(ids) { ids.clear() }
        drive.forget()
    }

    private fun remember(path: List<String>, item: GoogleDrive.Item) = synchronized(ids) {
        val key = path.joinToString("/")
        ids[key] = (ids[key] ?: emptyMap()) + (item.name to item.id)
    }
}
