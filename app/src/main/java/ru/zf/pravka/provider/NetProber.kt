package ru.zf.pravka.provider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.zf.pravka.core.NetProbe
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.HomeServer
import ru.zf.pravka.data.NetProbeStore
import ru.zf.pravka.data.Settings

/**
 * Проверка связи с облаками по расписанию (`core/NetProbe.kt` — что и как
 * судить). Зовёт тик службы раз в пять минут; сама решает, пора ли
 * (`NetProbe.due`, интервал — чипами в настройках). Все цели — разом, каждая
 * своим свежим соединением: пул выключен, иначе тёплое соединение прятало бы
 * как раз то, что ищем, — рукопожатие через VPN.
 *
 * Пишет в два места: замер — в `NetProbeStore` (картинка недели), строку — в
 * свой журнал `net.log` (одна на проверку и отдельная — на каждую перемену
 * «есть ↔ нет»). Живые сбои (тейк ушёл с молчащего облака, запрос к Claude
 * упёрся в мёртвое соединение) приходят сюда же через [live].
 */
internal class NetProber(
    private val context: Context,
    private val settings: Settings,
    private val store: NetProbeStore,
    private val homeServer: HomeServer,
    private val log: EventLog,
) {

    /** Свежее соединение на каждую проверку и короткие сроки: ждать дольше, чем ждёт тейк, незачем. */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
            .readTimeout(READ_S, TimeUnit.SECONDS)
            .callTimeout(CALL_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    private val running = AtomicBoolean(false)

    /** Было ли «есть» у цели в прошлый раз — журнал пишет перемены. */
    private val lastOk = ConcurrentHashMap<NetProbe.Target, Boolean>()

    /** Тик службы: включено и пора — проверить. Не на главном потоке. */
    suspend fun tick() {
        if (!settings.netProbeFlow.first()) return
        store.warm()
        val intervalMs = settings.netProbeIntervalFlow.first() * 60_000L
        if (!NetProbe.due(store.lastAt, System.currentTimeMillis(), intervalMs)) return
        probe("по расписанию")
    }

    /** Проверить сейчас — все цели разом. null — проверка уже идёт. */
    suspend fun probe(why: String): NetProbeStore.Probe? = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext null
        try {
            store.warm()
            if (lastOk.isEmpty()) store.last.value?.hits?.forEach { (t, h) -> lastOk[t] = h.ok }
            val at = System.currentTimeMillis()
            val net = netLabel()
            val key = runCatching { settings.apiKey() }.getOrDefault("")
            val cloudOn = homeServer.saved.value != null
            val targets = NetProbe.Target.entries.filter { it != NetProbe.Target.CLOUD || cloudOn }
            val hits = coroutineScope {
                targets.map { t -> async { t to hit(t, key) } }.awaitAll()
            }.toMap(LinkedHashMap())
            val p = NetProbeStore.Probe(at, net, hits)
            store.append(p)
            for ((t, h) in hits) {
                NetProbe.change(t, lastOk[t], h)?.let { line -> log.add(line) }
                lastOk[t] = h.ok
            }
            log.add(
                "$net · " + hits.entries.joinToString(" · ") { (t, h) ->
                    "${t.short} ${if (h.ok) "есть" else "НЕТ"} ${h.ms} мс" + (if (h.code > 0) " [${h.code}]" else "") +
                        (if (!h.ok) " (${h.why})" else "")
                } + if (why != "по расписанию") " — $why" else ""
            )
            p
        } finally {
            running.set(false)
        }
    }

    /**
     * Живой сбой — не по расписанию, а в деле: тейк ушёл с молчащего облака
     * на пакет, запрос к Claude не достучался. Картинка рисует его меткой на
     * полосе цели, журнал — строкой. Зовут с любого потока.
     */
    fun live(target: NetProbe.Target, text: String) {
        store.appendLive(NetProbeStore.Live(System.currentTimeMillis(), target, text))
        // Сеть спрашивается у системы — не на главном потоке службы (складывание Fold).
        ru.zf.pravka.data.DiskWriter.post { log.add("${target.title}, в деле: $text · ${netLabel()}") }
    }

    private suspend fun hit(target: NetProbe.Target, apiKey: String): NetProbe.Hit {
        val started = android.os.SystemClock.elapsedRealtime()
        fun spent() = android.os.SystemClock.elapsedRealtime() - started
        if (target == NetProbe.Target.CLOUD) {
            val r = runCatching { homeServer.check().getOrThrow() }
            return if (r.isSuccess) NetProbe.verdict(target, 200, null, spent())
            else NetProbe.verdict(target, 0, reason(r.exceptionOrNull()), spent())
        }
        val url = target.url ?: return NetProbe.Hit(false, 0, 0, "адреса нет")
        val req = Request.Builder().url(url).get().apply {
            header("Cache-Control", "no-cache")
            if (target == NetProbe.Target.CLAUDE && apiKey.isNotBlank()) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
            }
        }.build()
        return runCatching {
            client.newCall(req).execute().use { resp -> NetProbe.verdict(target, resp.code, null, spent()) }
        }.getOrElse { e -> NetProbe.verdict(target, 0, reason(e), spent()) }
    }

    /** Причина словами — неделю будет читать человек, а не стек. */
    private fun reason(e: Throwable?): String = when (e) {
        null -> "нет ответа"
        is java.net.UnknownHostException -> "имя сервера не нашлось (DNS)"
        is java.net.SocketTimeoutException -> "таймаут"
        is java.io.InterruptedIOException -> "таймаут ${CALL_S} с"
        is java.net.ConnectException -> "не соединился: ${e.message ?: ""}".trimEnd(' ', ':')
        is javax.net.ssl.SSLException -> "TLS: ${e.message ?: e.javaClass.simpleName}"
        is java.net.NoRouteToHostException -> "нет маршрута"
        else -> "${e.javaClass.simpleName}: ${e.message ?: ""}".trimEnd(' ', ':')
    }

    /** Сеть словами: «Wi-Fi + VPN», «сотовая», «нет сети»; «без интернета» — если система не подтвердила связь. */
    fun netLabel(): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching "?"
        val n = cm.activeNetwork ?: return@runCatching "нет сети"
        val c = cm.getNetworkCapabilities(n) ?: return@runCatching "сеть ?"
        val parts = ArrayList<String>()
        when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> parts += "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> parts += "сотовая"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> parts += "кабель"
        }
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // Под VPN активная сеть — сам туннель; через что он идёт, система
            // говорит у подлежащей сети, её и называем.
            if (parts.isEmpty()) {
                val under = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
                    .firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                when {
                    under == null -> {}
                    under.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> parts += "Wi-Fi"
                    under.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> parts += "сотовая"
                }
            }
            parts += "VPN"
        }
        val label = parts.joinToString(" + ").ifBlank { "сеть" }
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) label else "$label, без интернета по системе"
    }.getOrDefault("?")

    private companion object {
        const val CONNECT_S = 6L
        const val READ_S = 6L
        const val CALL_S = 10L
    }
}
