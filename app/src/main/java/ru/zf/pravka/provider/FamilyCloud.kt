package ru.zf.pravka.provider

import java.io.File

/**
 * Облако семьи — куда ездят общие Деньги и ночные копии базы: домашний
 * сервер владельца по WebDAV ([HomeCloud], 26.09.2026). Семейный Google Drive
 * был первым облаком и снят в тот же день: Google выключил клиент Правки, а
 * владелец решил: «Не надо с drive. Только с личным облаком». Обмену и копиям
 * нужна папка по пути, список, прочитать, записать, удалить — этим лицом они
 * и говорят с облаком; задан ли сервер — `PravkaApp.familyCloud()`.
 */
interface FamilyCloud {

    /** Для журнала и экрана: «домашний сервер». */
    val title: String

    /** Кто это облако (адрес сервера): сменили сервер — последняя копия базы поедет и на новый. */
    val id: String

    /**
     * Файл в папке. md5 сервер не считает (книги Слушалки по сотне МБ
     * пересчитывались бы на каждом списке) — версия журнала Денег видна по
     * размеру: журнал только дописывается, а на сервере заменяется целиком и
     * разом (временное имя и перенос).
     */
    data class Item(val name: String, val size: Long)

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
            is WebDav.WebDavException, is CloudException ->
                e.message.orEmpty()
            is java.net.UnknownHostException -> "$title: нет сети или адрес не находится (${e.message})"
            // Android не пускает запрос по http:// (cleartext запрещён).
            is java.net.UnknownServiceException -> "$title: по http:// Android пароль не отправляет — адрес должен начинаться с https:// (${e.message})"
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
        return all.filter { !it.folder && !WebDav.Dav.isTemp(it.name) }.map { FamilyCloud.Item(it.name, it.size) }
    }

    override suspend fun read(path: List<String>, name: String) = dav.read(c(), path, name)

    override suspend fun write(path: List<String>, name: String, bytes: ByteArray, mime: String) =
        dav.write(c(), path, name, bytes, mime)

    override suspend fun upload(path: List<String>, name: String, file: File, mime: String): FamilyCloud.Item {
        dav.upload(c(), path, name, file, mime)
        return FamilyCloud.Item(name, file.length())
    }

    override suspend fun delete(path: List<String>, name: String) = dav.delete(c(), path, name)
}
