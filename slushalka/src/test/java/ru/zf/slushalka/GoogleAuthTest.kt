package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.GoogleAuth

// Вход в семейный Google Drive браузером (25.09.2026): PKCE, ссылка входа с
// двумя доступами и разбор ответа, пришедшего в приложение по его схеме.
class GoogleAuthTest {

    @Test fun pkceMatchesRfcExample() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun authUrlAsksToReadAllAndWriteOwn() {
        val url = GoogleAuth.authUrl("id.apps.googleusercontent.com", "ch", "st")
        assertTrue(url.contains("redirect_uri=ru.zf.slushalka%3A%2Foauth2redirect"))
        assertTrue(url.contains("drive.readonly"))
        assertTrue(url.contains("drive.file"))
        assertTrue(url.contains("access_type=offline"))
    }

    @Test fun redirectQueryIsParsed() {
        assertEquals(GoogleAuth.Companion.Redirect("4/0AVG", "st", ""), GoogleAuth.parseQuery("state=st&code=4%2F0AVG&scope=x"))
        assertEquals("access_denied", GoogleAuth.parseQuery("error=access_denied&state=st").error)
    }
}
