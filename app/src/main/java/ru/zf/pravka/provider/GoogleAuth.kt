package ru.zf.pravka.provider

import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.zf.pravka.BuildConfig
import ru.zf.pravka.data.DataRoot
import ru.zf.pravka.data.StoreFiles

/**
 * Вход в семейный Google Drive (25.09.2026) — через браузер, без аккаунта на
 * телефоне. Владелец: «на Boox только один аккаунт можно» — поэтому не
 * системный выбор аккаунта, а обычный вход Google в браузере: Правка открывает
 * страницу входа, ждёт ответа на своём телефоне (127.0.0.1, случайный порт) и
 * меняет код на ключ. Клиент Google — «Desktop app»: только он пускает ответ
 * на 127.0.0.1; секрет такого клиента Google сам считает несекретным, в
 * репозитории его всё равно нет — сборка берёт его из секретов GitHub.
 *
 * Доступ — только к файлам самой Правки (`drive.file`): чужих документов в
 * Drive она не видит и испортить не может.
 *
 * Ключ (refresh token) хранится в закрытой памяти приложения
 * (`DataRoot.secrets`), не в папке базы: база копируется и уезжает в копии, а
 * вход в семейный Drive с ней ехать не должен. Перенесли базу — войти заново.
 */
class GoogleAuth(private val context: Context, private val http: OkHttpClient) {

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val FILE = "google-auth.json"
        private const val SECRET_FILE = "google-client-secret.txt"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        /** Сколько ждать ответа из браузера. */
        const val WAIT_MS = 5 * 60_000L
    }

    /** Кто вошёл: адрес аккаунта — чтобы видно было, что это семейный, а не личный. */
    data class Account(val email: String, val refresh: String, val scope: String, val at: Long)

    /** Ошибка входа словами; [relogin] — ключ отозван, нужно войти заново. */
    class AuthException(message: String, val relogin: Boolean = false) : Exception(message)

    private val dir: File get() = DataRoot.secrets(context)

    private val _account = MutableStateFlow(read())
    val account: StateFlow<Account?> = _account

    /** Идёт вход: ждём ответа из браузера. */
    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting

    @Volatile private var access = ""
    @Volatile private var accessUntil = 0L
    @Volatile private var server: ServerSocket? = null

    val clientId: String get() = BuildConfig.GOOGLE_CLIENT_ID

    /** Секрет клиента: из сборки, а если сборка без него — вписанный руками один раз. */
    val clientSecret: String
        get() = BuildConfig.GOOGLE_CLIENT_SECRET.ifBlank {
            runCatching { File(dir, SECRET_FILE).readText().trim() }.getOrDefault("")
        }

    /** Секрет пришёл со сборкой (из секретов GitHub), а не вписан руками. */
    val secretFromBuild: Boolean get() = BuildConfig.GOOGLE_CLIENT_SECRET.isNotBlank()

    fun saveSecret(secret: String) {
        StoreFiles.writeAtomic(File(dir, SECRET_FILE), secret.trim())
    }

    private fun read(): Account? = runCatching {
        val f = File(dir, FILE)
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        Account(o.optString("email"), o.getString("refresh"), o.optString("scope"), o.optLong("at"))
    }.getOrNull()

    private fun write(a: Account) {
        StoreFiles.writeAtomic(
            File(dir, FILE),
            JSONObject().put("email", a.email).put("refresh", a.refresh).put("scope", a.scope).put("at", a.at).toString(),
        )
    }

    /**
     * Вход. [open] открывает страницу Google в браузере (вызывается на главном
     * потоке). Ждёт ответа до [WAIT_MS]; [cancel] обрывает ожидание.
     */
    suspend fun signIn(open: (String) -> Unit): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            if (clientSecret.isBlank()) {
                throw AuthException("В сборке нет секрета клиента Google: положи GOOGLE_CLIENT_SECRET в секреты репозитория или впиши его здесь")
            }
            val verifier = GoogleOAuth.verifier()
            val state = GoogleOAuth.random(16)
            val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            server = socket
            _waiting.value = true
            try {
                socket.soTimeout = WAIT_MS.toInt()
                val redirect = "http://127.0.0.1:${socket.localPort}"
                val url = GoogleOAuth.authUrl(clientId, redirect, SCOPE, GoogleOAuth.challenge(verifier), state)
                withContext(Dispatchers.Main) { open(url) }
                val back = waitRedirect(socket, state)
                if (back.error.isNotEmpty()) {
                    throw AuthException(
                        if (back.error == "access_denied") "Вход отменён: в Google не нажали «Разрешить»"
                        else "Google ответил: ${back.error}"
                    )
                }
                val tokens = post(
                    FormBody.Builder()
                        .add("code", back.code)
                        .add("client_id", clientId)
                        .add("client_secret", clientSecret)
                        .add("redirect_uri", redirect)
                        .add("grant_type", "authorization_code")
                        .add("code_verifier", verifier)
                        .build()
                )
                val refresh = tokens.optString("refresh_token")
                if (refresh.isBlank()) throw AuthException("Google не выдал ключ на будущее (refresh_token) — войди ещё раз")
                val scope = tokens.optString("scope")
                // Google даёт снять галочку с доступа на странице входа: без неё
                // Правка вошла бы, но в Drive писать не смогла бы — и молчала.
                if (SCOPE !in scope.split(' ')) {
                    throw AuthException("На странице Google не отмечен доступ к файлам Drive — войди заново и оставь галочку")
                }
                access = tokens.optString("access_token")
                accessUntil = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000 - 60_000
                val email = runCatching { email(access) }.getOrDefault("")
                val acc = Account(email, refresh, scope, System.currentTimeMillis())
                write(acc)
                _account.value = acc
                acc
            } finally {
                _waiting.value = false
                server = null
                runCatching { socket.close() }
            }
        }
    }

    /** Оборвать ожидание ответа из браузера. */
    fun cancel() {
        runCatching { server?.close() }
    }

    /** Выйти: ключ стирается и отзывается у Google (если сеть есть). */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val refresh = _account.value?.refresh
        runCatching { File(dir, FILE).delete(); File(dir, "$FILE.prev").delete() }
        _account.value = null
        access = ""
        accessUntil = 0L
        if (refresh != null) runCatching {
            http.newCall(
                Request.Builder().url("https://oauth2.googleapis.com/revoke")
                    .post(FormBody.Builder().add("token", refresh).build()).build()
            ).execute().close()
        }
    }

    /** Ключ доступа на ближайший час; просроченный меняется сам. */
    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val acc = _account.value ?: throw AuthException("Google Drive не подключён", relogin = true)
        if (access.isNotEmpty() && System.currentTimeMillis() < accessUntil) return@withContext access
        val o = try {
            post(
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("refresh_token", acc.refresh)
                    .add("grant_type", "refresh_token")
                    .build()
            )
        } catch (e: AuthException) {
            if (e.relogin) {
                // Ключ отозван (вышли из аккаунта, сменили пароль, полгода без
                // дела): старый не поможет — стираем, экран позовёт войти.
                runCatching { File(dir, FILE).delete() }
                _account.value = null
            }
            throw e
        }
        access = o.optString("access_token")
        accessUntil = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000
        access
    }

    /** Drive ответил 401: ключ доступа протух раньше срока — следующий запрос возьмёт новый. */
    fun invalidate() {
        access = ""
        accessUntil = 0L
    }

    private fun post(body: FormBody): JSONObject {
        http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!resp.isSuccessful) {
                val err = o.optString("error")
                val why = listOf(err, o.optString("error_description")).filter { it.isNotBlank() }.joinToString(": ")
                    .ifBlank { text.take(300) }
                throw AuthException("Google, вход: HTTP ${resp.code} — $why", relogin = err == "invalid_grant")
            }
            return o
        }
    }

    private fun email(token: String): String {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)")
            .header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            return JSONObject(resp.body?.string().orEmpty()).optJSONObject("user")?.optString("emailAddress").orEmpty()
        }
    }

    /**
     * Ждать, пока браузер вернётся на 127.0.0.1 с кодом. Чужие запросы (значок
     * страницы, предзагрузка) отвечаются пустым и не мешают ждать дальше.
     */
    private fun waitRedirect(socket: ServerSocket, state: String): GoogleOAuth.Redirect {
        while (true) {
            val client = try {
                socket.accept()
            } catch (e: java.net.SocketTimeoutException) {
                throw AuthException("Ответа из браузера не было ${WAIT_MS / 60_000} минут — начни вход заново")
            } catch (e: java.net.SocketException) {
                throw AuthException("Вход отменён")
            }
            client.use { c ->
                c.soTimeout = 10_000
                val line = runCatching { c.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                val back = GoogleOAuth.parseRedirect(line)
                val out = c.getOutputStream()
                if (back == null || (back.code.isEmpty() && back.error.isEmpty())) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@use
                }
                val good = back.state == state && back.error.isEmpty()
                val page = GoogleOAuth.page(good, if (back.state != state) "ответ не от этого входа" else back.error)
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(page)
                out.flush()
                if (back.state != state) return@use
                return back
            }
        }
    }
}

/** Чистые куски входа — под JVM-тестом. */
object GoogleOAuth {

    private val rnd = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun random(n: Int): String = buildString { repeat(n) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

    /** PKCE: случайная строка 64 знака. */
    fun verifier(): String = random(64)

    /** PKCE S256: base64url(sha256(verifier)) без «=». */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authUrl(clientId: String, redirect: String, scope: String, challenge: String, state: String): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=${e(clientId)}" +
            "&redirect_uri=${e(redirect)}" +
            "&response_type=code" +
            "&scope=${e(scope)}" +
            "&code_challenge=${e(challenge)}" +
            "&code_challenge_method=S256" +
            "&state=${e(state)}" +
            // offline + consent: ключ на будущее выдаётся каждый раз, а не
            // только при самом первом входе этого аккаунта.
            "&access_type=offline" +
            "&prompt=consent"
    }

    data class Redirect(val code: String, val state: String, val error: String)

    /** Первая строка запроса браузера: `GET /?state=…&code=… HTTP/1.1`. Не наш запрос — null. */
    fun parseRedirect(requestLine: String): Redirect? {
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        val query = parts[1].substringAfter('?', "")
        if (query.isEmpty()) return null
        val q = query.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=')
            if (k.isEmpty()) null else k to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
        }.toMap()
        return Redirect(q["code"].orEmpty(), q["state"].orEmpty(), q["error"].orEmpty())
    }

    /** Страница в браузере после входа: что вышло и как вернуться. */
    fun page(ok: Boolean, why: String): ByteArray {
        val title = if (ok) "Правка подключена к Google Drive" else "Вход не вышел"
        val body = if (ok) "Можно вернуться в Правку — она уже знает." else "Google ответил: ${why.replace("<", "&lt;")}. Вернись в Правку и начни вход заново."
        return """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>body{font-family:sans-serif;margin:32px 20px;line-height:1.5;color:#222;background:#faf7f0}
h1{font-size:22px}a{display:inline-block;margin-top:16px;padding:12px 20px;border-radius:12px;background:#2f6d4f;color:#fff;text-decoration:none}</style>
</head><body><h1>$title</h1><p>$body</p><a href="pravka://google">Вернуться в Правку</a></body></html>""".toByteArray(Charsets.UTF_8)
    }
}
