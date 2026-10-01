package ru.zf.slushalka.player

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.zf.slushalka.data.Cloud
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.BookFile

/**
 * Звук с сервера библиотеки: HTTP с входом, кэш на телефоне, подкачка вперёд.
 *
 * Плеер получает файл книги адресом на сервере, а не документом SAF, и читает
 * его через [CacheDataSource] поверх [SimpleCache]: сыгранное и подкачанное
 * лежит на телефоне, второй раз не качается и играет без сети. Перемотка -
 * запрос с `Range`, rclone их понимает. Пока играет один файл, следующий
 * качается целиком заранее ([prefetch]): метро, лифт, дача - книга идёт
 * дальше, а не замолкает на стыке глав.
 *
 * Тем же кэшем пользуется распознавание куска для карты «звук ↔ текст»
 * ([mediaSource]): последние секунды перед переходом в читалку только что
 * играли - они уже на телефоне, сеть для сверки не нужна.
 *
 * Кэш заводится при первом файле с сервера: у того, кто слушает только своё,
 * его нет вовсе.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Streaming(
    private val context: Context,
    private val settings: Settings,
    private val cloud: Cloud,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val evictor = SizedLruEvictor { CACHE_BYTES }

    /** Кэш записи. SimpleCache на папку один на процесс - отсюда lazy и один экземпляр. */
    private val cache: SimpleCache by lazy {
        SimpleCache(File(context.cacheDir, "stream"), evictor, StandaloneDatabaseProvider(context))
    }

    @Volatile
    private var cacheMade = false

    /** HTTP с входом: заголовок собирается на каждое соединение - логин могли сменить. */
    private val http = DataSource.Factory {
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to cloud.authHeader()))
            .createDataSource()
    }

    /** Кэш поверх HTTP: что уже на телефоне, читается с диска. */
    private val cached: CacheDataSource.Factory by lazy {
        cacheMade = true
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http)
    }

    /**
     * Источник для плеера: документы SAF и файлы читает как раньше, а всё по
     * HTTP - через кэш. Сам кэш заводится, только когда дело дошло до сети.
     */
    val dataSourceFactory: DataSource.Factory by lazy {
        DefaultDataSource.Factory(context) { Deferred { cached.createDataSource() } }
    }

    /** Адрес файла книги на сервере: тот же для плеера, подкачки и распознавания - по нему кэш узнаёт своё. */
    fun urlOf(file: BookFile): String = cloud.urlOf(file.remote)

    fun uriOf(file: BookFile): Uri = Uri.parse(urlOf(file))

    // ------------------------------------------------------------- подкачка

    private var prefetchJob: Job? = null
    private var prefetchUrl: String? = null

    /**
     * Подкачать файл целиком заранее - следующий за тем, что играет. null -
     * отменить: книга своя, кончилась или закрыта. Тот же файл второй раз не
     * заводится; другой отменяет прежний - качается всегда один.
     */
    fun prefetch(file: BookFile?) {
        val url = file?.takeIf { it.isRemote }?.let(::urlOf)
        if (url != null && url == prefetchUrl && prefetchJob?.isActive == true) return
        prefetchJob?.cancel()
        prefetchUrl = url
        if (url == null) return
        prefetchJob = scope.launch {
            val writer = CacheWriter(cached.createDataSource(), DataSpec(Uri.parse(url)), null, null)
            // Отмена корутины должна оборвать и закачку: CacheWriter блокирует поток.
            val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) writer.cancel() }
            // Обрыв сети, сервер, отмена - не беда: доиграет плеер сам, а
            // недокачанное он докачает или скажет, что сети нет.
            runCatching { writer.cache() }
            handle?.dispose()
        }
    }

    // --------------------------------------------------------- распознавание

    /**
     * Файл с сервера для [android.media.MediaExtractor]: читает через тот же
     * кэш, окнами по четверть мегабайта. Недостающее докачивается запросом с
     * `Range` и тоже остаётся в кэше.
     */
    fun mediaSource(file: BookFile): MediaDataSource = CachedMedia(cached, uriOf(file), file.size)

    private class CachedMedia(
        private val factory: CacheDataSource.Factory,
        private val uri: Uri,
        private val length: Long,
    ) : MediaDataSource() {

        private var window = ByteArray(0)
        private var windowStart = -1L

        override fun getSize(): Long = if (length > 0) length else -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (length > 0 && position >= length) return -1
            if (windowStart < 0 || position < windowStart || position >= windowStart + window.size) fill(position)
            val from = (position - windowStart).toInt()
            val n = minOf(size, window.size - from)
            if (n <= 0) return -1
            System.arraycopy(window, from, buffer, offset, n)
            return n
        }

        private fun fill(position: Long) {
            val want = if (length > 0) minOf(WINDOW.toLong(), length - position) else WINDOW.toLong()
            val ds = factory.createDataSource()
            try {
                ds.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(want).build())
                val out = ByteArray(want.toInt())
                var got = 0
                while (got < out.size) {
                    val r = ds.read(out, got, out.size - got)
                    if (r == C.RESULT_END_OF_INPUT) break
                    got += r
                }
                window = if (got == out.size) out else out.copyOf(got)
                windowStart = position
            } finally {
                runCatching { ds.close() }
            }
        }

        override fun close() {
            window = ByteArray(0)
            windowStart = -1L
        }
    }

    // --------------------------------------------------------------- ошибки

    /**
     * Ошибка плеера по-человечески, если дело в сервере: нет сети, не пустил,
     * нет файла. null - ошибка не про поток, пусть говорит плеер.
     */
    fun explain(error: PlaybackException): String? {
        val causes = generateSequence(error.cause as Throwable?) { it.cause }.toList()
        causes.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { e ->
            return when (e.responseCode) {
                401, 403 -> "Сервер библиотеки не пустил: проверь логин и пароль в настройках облака"
                404 -> "На сервере нет этого файла - книгу, похоже, переложили. Обнови библиотеку"
                else -> "Сервер библиотеки ответил ${e.responseCode}"
            }
        }
        val network = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causes.any { it is HttpDataSource.HttpDataSourceException }
        return if (network) {
            "Нет связи с сервером библиотеки. Сыграно то, что успело лечь на телефон; появится сеть - " +
                "нажми «слушать»"
        } else null
    }

    // ------------------------------------------------------------------ кэш

    /** Сколько записи лежит на телефоне. Кэш не заводили - ноль, и заводить ради ответа незачем. */
    fun cachedBytes(): Long = if (cacheMade) runCatching { cache.cacheSpace }.getOrDefault(0L) else 0L

    /**
     * Источник, который заводит настоящий только при открытии. DefaultDataSource
     * создаёт сетевой источник сразу, на каждую книгу, - и без этой прослойки
     * кэш заводился бы и у того, кто слушает только своё.
     */
    private class Deferred(private val make: () -> DataSource) : DataSource {
        private var real: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        private fun real(): DataSource = real ?: make().also { r ->
            listeners.forEach(r::addTransferListener)
            real = r
        }

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            real?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long = real().open(dataSpec)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = real().read(buffer, offset, length)

        override fun getUri(): Uri? = real?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = real?.responseHeaders ?: emptyMap()

        override fun close() {
            real?.close()
        }
    }

    /**
     * Вытеснение давно не слушанного, как LeastRecentlyUsedCacheEvictor, но с
     * пределом, который спрашивается каждый раз: размер кэша - настройка, и
     * менять её надо без пересоздания кэша (он один на процесс).
     */
    private class SizedLruEvictor(private val maxBytes: () -> Long) : CacheEvictor {
        private val spans = TreeSet<CacheSpan> { a, b ->
            val d = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
            if (d != 0) d else a.compareTo(b)
        }
        private var size = 0L

        override fun requiresCacheSpanTouches() = true

        override fun onCacheInitialized() {}

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            spans += span
            size += span.length
            evict(cache, 0)
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            spans -= span
            size -= span.length
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan)
            onSpanAdded(cache, newSpan)
        }

        fun evict(cache: Cache, needed: Long) {
            val limit = maxBytes()
            while (size + needed > limit && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
    }

    private companion object {
        /** Окно чтения для распознавания: шесть секунд mp3 - это сто килобайт, окна хватает с запасом. */
        const val WINDOW = 256 * 1024
        /** Предел кэша записи - два гигабайта, это часов тридцать пять mp3 на 128 кбит/с. */
        const val CACHE_BYTES = 2048L * 1024 * 1024
        const val USER_AGENT = "Slushalka"
    }
}
