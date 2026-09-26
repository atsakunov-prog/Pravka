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
 * Лежат в закрытой памяти установки (`DataRoot.secrets`), как ключ Google:
 * базу копируют и переносят, пароль с ней ехать не должен. У каждого телефона
 * свой вход (sasha, marianna): потерялся телефон — на сервере убирается одна
 * строка, остальные работают.
 */
internal class HomeServer(private val context: Context, private val dav: WebDav) {

    data class Saved(val url: String, val user: String, val pass: String, val at: Long) {
        val config get() = WebDav.Config(url, user, pass)
    }

    private val file: File get() = File(DataRoot.secrets(context), FILE)

    private val _saved = MutableStateFlow(read())
    val saved: StateFlow<Saved?> = _saved

    val cloud: FamilyCloud = ru.zf.pravka.provider.HomeCloud(dav) { _saved.value?.config }

    private fun read(): Saved? = runCatching {
        if (!file.isFile) return null
        val o = JSONObject(file.readText())
        Saved(o.getString("url"), o.getString("user"), o.getString("pass"), o.optLong("at"))
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
        /** Корень Правки на сервере; Слушалка живёт рядом в своей «Слушалке». */
        const val ROOT = "Правка"

        /** Адрес как вписали: без схемы — https, без слэша в конце — со слэшем. */
        fun normalize(url: String): String {
            val u = url.trim()
            val withScheme = if ("://" in u) u else "https://$u"
            return withScheme.trimEnd('/') + "/"
        }
    }
}
