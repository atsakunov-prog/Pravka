package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Облако семьи переехало вместе с роутером (01.10.2026). */
class HomeServerMoveTest {

    private val now = "https://webdav.znakomiy.netcraze.pro:8443/"

    @Test fun oldRouterAddressMovesToTheNewOne() {
        assertEquals(now, HomeServer.moved("https://webdav.pravka.netcraze.pro:8443/"))
        // Как бы ни было вписано: без порта, большими буквами, с хвостом.
        assertEquals(now, HomeServer.moved("https://WebDAV.Pravka.Netcraze.pro/"))
        assertEquals(now, HomeServer.moved("https://webdav.pravka.netcraze.pro:8443/dav/"))
    }

    @Test fun anyOtherAddressStaysAsIs() {
        assertNull(HomeServer.moved(now))
        assertNull(HomeServer.moved("https://server.znakomiy.netcraze.pro:8443/"))
        // Чужое имя, в котором старое лишь часть, — не наше.
        assertNull(HomeServer.moved("https://webdav.pravka.netcraze.pro.example.com/"))
        assertNull(HomeServer.moved("https://my-webdav.pravka.netcraze.pro/"))
    }
}
