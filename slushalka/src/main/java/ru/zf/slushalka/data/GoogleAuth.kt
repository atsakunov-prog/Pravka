package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.zf.slushalka.BuildConfig

/**
 * Вход в семейный Google Drive (25.09.2026) — браузером, без аккаунта на
 * устройстве: владелец, «на Boox только один аккаунт можно». Слушалка открывает
 * страницу входа Google, браузер возвращает код по адресу [REDIRECT] (его
 * ловит `GoogleAuthActivity`), код меняется на ключ по PKCE. Клиент Google —
 * Android-клиент Правки в проекте семейного аккаунта, с галочкой «Enable
 * custom URI scheme»: секрета у такого клиента нет; ответ на 127.0.0.1 Google
 * Android-клиентам закрыл.
 *
 * Доступ: «читать всё» (`drive.readonly`) — книги, закинутые в Drive с
 * компьютера, — и «свои файлы» (`drive.file`) — выгрузка книг с полки, позиции,
 * вопросы и пометки. Менять и удалять чужое Слушалка не может. Google даёт
 * снять галочку с «читать всё» — тогда видны только книги, выгруженные самой
 * Слушалкой ([Account.all] = false), и экран говорит об этом.
 *
 * Ключ (refresh token) — в закрытой памяти приложения, не в папке библиотеки:
 * её синхронизируют и копируют, вход с ней ехать не должен.
 */
class GoogleAuth(private val context: Context, private val http: OkHttpClient) {

    companion object {
        const val SCOPE_ALL = "https://www.googleapis.com/auth/drive.readonly"
        const val SCOPE_OWN = "https://www.googleapis.com/auth/drive.file"
        /**
         * Клиент Google — тот же, что у Правки (Android, пакет `ru.zf.pravka`):
         * Google принимает у Android-клиента любой путь в схеме его пакета, а
         * свой путь не даёт двум приложениям спутать ответ — у Правки
         * `/oauth2redirect`, у Слушалки `/slushalka` (владелец: «а к тому же
         * ключу нельзя подключить?», 25.09.2026).
         */
        const val REDIRECT = "ru.zf.pravka:/slushalka"
        private const val FILE = "google-auth.json"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val WAIT_MS = 5 * 60_000L

        @Volatile private var pending: CompletableDeferred<Redirect>? = null

        /** Браузер вернул в приложение. false — входа никто не ждёт (процесс выгружали). */
        fun onRedirect(query: String?): Boolean {
            val d = pending ?: return false
            return d.complete(parseQuery(query.orEmpty()))
        }

        // ---- Чистые куски ----

        private val rnd = SecureRandom()
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        fun random(n: Int): String = buildString { repeat(n) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun authUrl(clientId: String, challenge: String, state: String): String {
            fun e(s: String) = URLEncoder.encode(s, "UTF-8")
            return "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=${e(clientId)}" +
                "&redirect_uri=${e(REDIRECT)}" +
                "&response_type=code" +
                "&scope=${e("$SCOPE_ALL $SCOPE_OWN")}" +
                "&code_challenge=${e(challenge)}" +
                "&code_challenge_method=S256" +
                "&state=${e(state)}" +
                "&access_type=offline" +
                "&prompt=consent"
        }

        data class Redirect(val code: String, val state: String, val error: String)

        fun parseQuery(query: String): Redirect {
            val q = query.split('&').mapNotNull { kv ->
                val k = kv.substringBefore('=')
                if (k.isEmpty()) null else k to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            }.toMap()
            return Redirect(q["code"].orEmpty(), q["state"].orEmpty(), q["error"].orEmpty())
        }
    }

    data class Account(val email: String, val refresh: String, val all: Boolean, val at: Long)

    class AuthException(message: String, val relogin: Boolean = false) : Exception(message)

    private val file: File get() = File(context.filesDir, FILE)

    private val _account = MutableStateFlow(read())
    val account: StateFlow<Account?> = _account

    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting

    @Volatile private var access = ""
    @Volatile private var accessUntil = 0L

    val clientId: String get() = BuildConfig.GOOGLE_CLIENT_ID

    private fun read(): Account? = runCatching {
        if (!file.isFile) return null
        val o = JSONObject(file.readText())
        Account(o.optString("email"), o.getString("refresh"), o.optBoolean("all"), o.optLong("at"))
    }.getOrNull()

    private fun write(a: Account) {
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(JSONObject().put("email", a.email).put("refresh", a.refresh).put("all", a.all).put("at", a.at).toString())
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    /** Вход. [open] открывает страницу Google (главный поток). */
    suspend fun signIn(open: (String) -> Unit): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            if (clientId.isBlank()) throw AuthException("В этой сборке нет клиента Google — нужна сборка с его ID")
            val verifier = random(64)
            val state = random(16)
            val d = CompletableDeferred<Redirect>()
            pending = d
            _waiting.value = true
            try {
                withContext(Dispatchers.Main) { open(authUrl(clientId, challenge(verifier), state)) }
                val back = withTimeoutOrNull(WAIT_MS) { d.await() }
                    ?: throw AuthException("Ответа из браузера не было ${WAIT_MS / 60_000} минут — начни вход заново")
                if (back.error.isNotEmpty()) throw AuthException(
                    when (back.error) {
                        "access_denied" -> "Вход отменён: в Google не нажали «Разрешить»"
                        "cancelled" -> "Вход отменён"
                        else -> "Google ответил: ${back.error}"
                    }
                )
                if (back.state != state) throw AuthException("Ответ не от этого входа — начни вход заново")
                val tokens = post(
                    FormBody.Builder()
                        .add("code", back.code)
                        .add("client_id", clientId)
                        .add("redirect_uri", REDIRECT)
                        .add("grant_type", "authorization_code")
                        .add("code_verifier", verifier)
                        .build()
                )
                val refresh = tokens.optString("refresh_token")
                if (refresh.isBlank()) throw AuthException("Google не выдал ключ на будущее (refresh_token) — войди ещё раз")
                val scopes = tokens.optString("scope").split(' ')
                if (SCOPE_OWN !in scopes && SCOPE_ALL !in scopes) {
                    throw AuthException("На странице Google не отмечен доступ к Drive — войди заново и оставь галочки")
                }
                access = tokens.optString("access_token")
                accessUntil = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000 - 60_000
                val email = runCatching { email(access) }.getOrDefault("")
                val acc = Account(email.ifBlank { "аккаунт Google" }, refresh, SCOPE_ALL in scopes, System.currentTimeMillis())
                write(acc)
                _account.value = acc
                acc
            } finally {
                if (pending === d) pending = null
                _waiting.value = false
            }
        }
    }

    fun cancel() {
        pending?.complete(Redirect("", "", "cancelled"))
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val refresh = _account.value?.refresh
        runCatching { file.delete() }
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
                    .add("refresh_token", acc.refresh)
                    .add("grant_type", "refresh_token")
                    .build()
            )
        } catch (e: AuthException) {
            if (e.relogin) {
                runCatching { file.delete() }
                _account.value = null
            }
            throw e
        }
        access = o.optString("access_token")
        accessUntil = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000
        access
    }

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
}
