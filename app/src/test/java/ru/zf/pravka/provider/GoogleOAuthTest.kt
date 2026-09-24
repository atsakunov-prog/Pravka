package ru.zf.pravka.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Вход в семейный Google Drive через браузер (25.09.2026): PKCE, ссылка
// входа и разбор ответа браузера на 127.0.0.1.
class GoogleOAuthTest {

    @Test fun pkceChallengeMatchesRfcExample() {
        // RFC 7636, приложение B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val v = GoogleOAuth.verifier()
        assertEquals(64, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test fun authUrlAsksForOfflineKeyAndOnlyOwnFiles() {
        val url = GoogleOAuth.authUrl("id.apps.googleusercontent.com", "http://127.0.0.1:43123", GoogleAuth.SCOPE, "ch", "st")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A43123"))
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
    }

    @Test fun browserRedirectIsParsed() {
        val ok = GoogleOAuth.parseRedirect(
            "GET /?state=st&code=4%2F0AVG7fiQ&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file HTTP/1.1"
        )
        assertEquals(GoogleOAuth.Redirect("4/0AVG7fiQ", "st", ""), ok)
        assertEquals("access_denied", GoogleOAuth.parseRedirect("GET /?error=access_denied&state=st HTTP/1.1")?.error)
        // Значок страницы и прочее чужое — не ответ входа.
        assertNull(GoogleOAuth.parseRedirect("GET /favicon.ico HTTP/1.1"))
        assertNull(GoogleOAuth.parseRedirect(""))
        assertNull(GoogleOAuth.parseRedirect("POST /?code=x HTTP/1.1"))
    }

    @Test fun appRedirectQueryIsParsed() {
        // Android-клиент: браузер открывает ru.zf.pravka:/oauth2redirect?… — берётся строка запроса как есть.
        assertEquals(GoogleOAuth.Redirect("4/0AVG", "st", ""), GoogleOAuth.parseQuery("state=st&code=4%2F0AVG&scope=x"))
        assertEquals(GoogleOAuth.Redirect("", "", ""), GoogleOAuth.parseQuery(""))
        val url = GoogleOAuth.authUrl("id", GoogleAuth.REDIRECT_APP, GoogleAuth.SCOPE, "ch", "st")
        assertTrue(url.contains("redirect_uri=ru.zf.pravka%3A%2Foauth2redirect"))
    }

    @Test fun pageEscapesGoogleWords() {
        val page = String(GoogleOAuth.page(false, "<script>"), Charsets.UTF_8)
        assertTrue(page.contains("&lt;script>"))
        assertTrue(page.contains("pravka://google"))
    }
}
