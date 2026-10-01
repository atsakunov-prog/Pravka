package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import ru.zf.pravka.provider.FamilyCloud
import ru.zf.pravka.provider.WebDav

/**
 * Домашний сервер владельца — адрес, логин и пароль WebDAV (26.09.2026).
 * Лежат в закрытой памяти установки (`DataRoot.secrets`), не в базе:
 * базу копируют и переносят, пароль с ней ехать не должен. У каждого телефона
 * свой вход (sasha, marianna): потерялся телефон — на сервере убирается одна
 * строка, остальные работают.
 */
internal class HomeServer(private val context: Context, private val dav: WebDav) {

    data class Saved(val url: String, val user: String, val pass: String, val at: Long) {
        val config get() = WebDav.Config(url, user, pass)
    }

    private val file: File get() = File(DataRoot.secrets(context), FILE)

    init {
        // Вход в Google Drive снят 26.09 («Только с личным облаком»): его ключ
        // в закрытой памяти больше ничему не нужен.
        runCatching { File(DataRoot.secrets(context), "google-auth.json").delete() }
    }

    private val _saved = MutableStateFlow(read())
    val saved: StateFlow<Saved?> = _saved

    val cloud: FamilyCloud = ru.zf.pravka.provider.HomeCloud(dav) { _saved.value?.config }

    private fun read(): Saved? = runCatching {
        if (!file.isFile) return null
        val o = JSONObject(file.readText())
        val s = Saved(o.getString("url"), o.getString("user"), o.getString("pass"), o.optLong("at"))
        val to = moved(s.url) ?: return s
        // Сервер переехал на новый адрес — тот же rclone, те же входы: пишем
        // новый адрес на место старого, вход и пароль не трогаем. Запись — на
        // поток диска: read() зовут при первом обращении, и это может быть
        // главный поток.
        val next = s.copy(url = to)
        DiskWriter.post {
            StoreFiles.writeAtomic(
                file,
                JSONObject().put("url", next.url).put("user", next.user).put("pass", next.pass).put("at", next.at).toString(),
            )
        }
        runCatching { (context.applicationContext as? ru.zf.pravka.PravkaApp)?.eventLog?.add("облако семьи: адрес ${s.url} сменён на ${next.url} (сервер переехал на новый роутер)") }
        next
    }.getOrNull()

    /**
     * Проверить и запомнить: папка «Правка» на сервере есть или заводится.
     * Не прошло — ничего не сохраняется, ошибка целиком.
     */
    suspend fun connect(url: String, user: String, pass: String): Result<Saved> = runCatching {
        val clean = normalize(url)
        val c = WebDav.Config(clean, user.trim(), pass)
        dav.list(c, listOf(ROOT), create = true)
        val s = Saved(clean, c.user, pass, System.currentTimeMillis())
        StoreFiles.writeAtomic(
            file,
            JSONObject().put("url", s.url).put("user", s.user).put("pass", s.pass).put("at", s.at).toString(),
        )
        _saved.value = s
        s
    }

    /** Та же проверка для уже подключённого: что видно в «Правке». */
    suspend fun check(): Result<Int> = runCatching {
        val s = _saved.value ?: throw FamilyCloud.CloudException("Домашний сервер не задан")
        dav.list(s.config, listOf(ROOT), create = true).size
    }

    fun disconnect() {
        runCatching { file.delete() }
        _saved.value = null
    }

    companion object {
        private const val FILE = "home-server.json"

        /**
         * Куда переехало облако семьи. 01.10.2026 владелец сменил роутер, и
         * старое имя CrazeDNS (`pravka.netcraze.pro`) осталось за прежним
         * роутером: облако не отвечало, общие Деньги и ночные копии ждали на
         * телефонах. Владелец: «надо ещё адрес webdav туда» — вписывать новый
         * адрес руками на трёх устройствах не нужно, сборка меняет его сама.
         * Ключ — имя хоста целиком; rclone, входы и пароли те же.
         */
        private val MOVED = mapOf(
            "webdav.pravka.netcraze.pro" to "https://webdav.znakomiy.netcraze.pro:8443/",
        )

        /** Новый адрес, если вписан переехавший; иначе null. */
        fun moved(url: String): String? {
            val host = url.trim().substringAfter("://", url.trim())
                .substringBefore('/').substringBefore(':').lowercase()
            return MOVED[host]
        }
        /** Корень Правки на сервере; Слушалка живёт рядом в своей «Слушалке». */
        const val ROOT = "Правка"

        /**
         * Адрес как вписали — всегда https и со слэшем в конце. `http://`
         * здесь не заработает никогда: Android не пускает пароль открытым
         * текстом (cleartext запрещён), а снаружи сервер и так за HTTPS
         * роутера (владелец вписал http — «CLEARTEXT communication … not
         * permitted», 26.09.2026).
         */
        fun normalize(url: String): String {
            val u = url.trim()
            return "https://" + u.substringAfter("://", u).trimEnd('/') + "/"
        }
    }
}
