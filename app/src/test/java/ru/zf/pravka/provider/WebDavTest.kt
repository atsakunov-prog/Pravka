package ru.zf.pravka.provider

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.zf.pravka.data.HomeServer
import ru.zf.pravka.provider.WebDav.Dav

class WebDavTest {

    /** Ответ rclone v1.75.1 (`serve webdav`) на PROPFIND Depth 1 — снят живьём. */
    private val rclone = """<?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/%D0%9F%D1%80%D0%B0%D0%B2%D0%BA%D0%B0/</D:href><D:propstat><D:prop><D:displayname>Правка</D:displayname><D:getlastmodified>Sat, 26 Sep 2026 18:07:27 GMT</D:getlastmodified><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/%D0%9F%D1%80%D0%B0%D0%B2%D0%BA%D0%B0/x.jsonl</D:href><D:propstat><D:prop><D:resourcetype></D:resourcetype><D:getcontenttype>application/octet-stream</D:getcontenttype><D:displayname>x.jsonl</D:displayname><D:getcontentlength>7</D:getcontentlength><D:getlastmodified>Sat, 26 Sep 2026 18:07:27 GMT</D:getlastmodified><D:getetag>"18d8f131952fcf447"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/%D0%9F%D1%80%D0%B0%D0%B2%D0%BA%D0%B0/%D0%94%D0%B5%D0%BD%D1%8C%D0%B3%D0%B8/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""

    @Test fun rcloneListingSkipsTheFolderItself() {
        val items = Dav.parse(rclone, "/Правка")
        assertEquals(listOf("x.jsonl", "Деньги"), items.map { it.name })
        val f = items[0]
        assertEquals(7L, f.size)
        assertEquals("18d8f131952fcf447", f.etag)
        assertFalse(f.folder)
        assertEquals(1790446047000L, f.modified)
        assertTrue(items[1].folder)
    }

    @Test fun absoluteHrefsAndForeignPrefixesAndFailedPropstats() {
        // Nextcloud и другие отдают href полным адресом и под своим путём;
        // свойства со статусом 404 не должны перебивать настоящие.
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>https://host.example:8443/dav/%D0%9F%D1%80%D0%B0%D0%B2%D0%BA%D0%B0/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>https://host.example:8443/dav/%D0%9F%D1%80%D0%B0%D0%B2%D0%BA%D0%B0/a+b%20c.zip</d:href>
              <d:propstat><d:prop><d:getcontentlength>1024</d:getcontentlength><d:getetag>W/"abc"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              <d:propstat><d:prop><d:getcontentlength>0</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
            </d:multistatus>"""
        val items = Dav.parse(xml, Dav.decodedPath("https://host.example:8443/dav/", listOf("Правка")))
        assertEquals(1, items.size)
        assertEquals("a+b c.zip", items[0].name)
        assertEquals(1024L, items[0].size)
        assertEquals("abc", items[0].etag)
    }

    @Test fun pathsAreEncodedAsPathsNotForms() {
        assertEquals("/%D0%9F%D1%80%D0%B0%D0%B2%D0%BA%D0%B0/%D0%9A%D0%BE%D0%BF%D0%B8%D0%B8%20%D0%B1%D0%B0%D0%B7%D1%8B/",
            Dav.encodedPath("https://h:8443/", listOf("Правка", "Копии базы"), null))
        assertEquals("/dav/a/b%2Bc.zip", Dav.encodedPath("https://h:8443/dav/", listOf("a"), "b+c.zip"))
        assertEquals("/", Dav.encodedPath("https://h:8443", emptyList(), null))
        assertEquals("/dav/", Dav.basePath("https://h:8443/dav/"))
        assertEquals("/", Dav.basePath("https://h:8443"))
        assertEquals("a+b", Dav.dec("a%2Bb"))
        assertEquals("a+b", Dav.dec("a+b"))
    }

    @Test fun tempNamesAreNeverJournalsOrBackups() {
        val t = Dav.tempName("sasha-3f9a2c.000001.jsonl")
        assertTrue(Dav.isTemp(t))
        assertTrue(ru.zf.pravka.core.MoneySync.parseChunk(t) == null)
        assertFalse(Dav.tempName("pravka-sasha-2026-09-26.zip").startsWith("pravka-"))
    }

    @Test fun codesAreExplainedAndShortTextBodiesKept() {
        assertTrue(Dav.meaning(401, "").contains("логин или пароль"))
        assertTrue(Dav.meaning(502, "upstream request failed").contains("upstream request failed"))
        assertEquals("сервер не объяснил", Dav.meaning(418, "<html>teapot</html>"))
    }

    @Test fun addressIsNormalized() {
        assertEquals("https://webdav.home.example:8443/", HomeServer.normalize(" webdav.home.example:8443 "))
        assertEquals("http://192.168.1.65:8088/", HomeServer.normalize("http://192.168.1.65:8088"))
    }

    /**
     * Живой прогон против настоящего сервера — только когда задан адрес:
     * `PRAVKA_WEBDAV_URL=http://127.0.0.1:8088 PRAVKA_WEBDAV_USER=… PRAVKA_WEBDAV_PASS=…`
     * (локальный `rclone serve webdav`). Без адреса пропускается.
     */
    @Test fun liveServerRoundTrip() { runBlocking { live() } }

    private suspend fun live() {
        val url = System.getenv("PRAVKA_WEBDAV_URL").orEmpty()
        assumeTrue("нет PRAVKA_WEBDAV_URL — живой прогон пропущен", url.isNotBlank())
        val c = WebDav.Config(url, System.getenv("PRAVKA_WEBDAV_USER").orEmpty(), System.getenv("PRAVKA_WEBDAV_PASS").orEmpty())
        val dav = WebDav(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build())
        val path = listOf("Правка-тест-" + System.currentTimeMillis(), "Копии базы")

        // Нет папки — пусто без create, с create — заводится вся цепочка.
        assertEquals(emptyList<WebDav.Item>(), dav.list(c, path, create = false))
        assertEquals(emptyList<WebDav.Item>(), dav.list(c, path, create = true))

        val journal = "sasha-3f9a2c.000001.jsonl"
        dav.write(c, path, journal, "первая строка\n".toByteArray(), "application/json")
        dav.write(c, path, journal, "первая строка\nвторая\n".toByteArray(), "application/json")
        assertEquals("первая строка\nвторая\n", String(dav.read(c, path, journal)))

        val big = File.createTempFile("pravka-", ".zip").apply { writeBytes(ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }) }
        dav.upload(c, path, "pravka-sasha-2026-09-26.zip", big, "application/zip")
        assertArrayEquals(big.readBytes(), dav.read(c, path, "pravka-sasha-2026-09-26.zip"))

        val items = dav.list(c, path, create = false).associateBy { it.name }
        // Временных имён после записи не остаётся: всё переехало на место.
        assertEquals(setOf(journal, "pravka-sasha-2026-09-26.zip"), items.keys)
        assertEquals("первая строка\nвторая\n".toByteArray().size.toLong(), items.getValue(journal).size)
        assertEquals(big.length(), items.getValue("pravka-sasha-2026-09-26.zip").size)

        // Облако семьи поверх того же сервера видит то же и чистит только своё.
        val cloud = HomeCloud(dav) { c }
        assertEquals(setOf(journal, "pravka-sasha-2026-09-26.zip"), cloud.list(path).map { it.name }.toSet())
        cloud.delete(path, "pravka-sasha-2026-09-26.zip")
        cloud.delete(path, "нет-такого.zip")
        assertEquals(listOf(journal), cloud.list(path).map { it.name })

        // Неверный пароль — 401 словами.
        val wrong = runCatching { dav.list(c.copy(pass = "не тот"), path, create = false) }.exceptionOrNull()
        assertTrue(wrong is WebDav.WebDavException && wrong.code == 401 && wrong.message!!.contains("логин или пароль"))

        cloud.delete(path, journal)
        big.delete()
    }
}
