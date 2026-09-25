package ru.zf.pravka.provider

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Распознавание по кнопке гарнитуры — разговор с Bluetooth-стеком телефона.
 *
 * Гарнитура просит «помощника» (команда AT+BVRA=1), стек открывает на неё
 * приложение голосовых команд (`ACTION_VOICE_COMMAND`, у нас —
 * `trigger/HeadsetButtonActivity.kt`) и ЖДЁТ пять секунд, что приложение
 * подтвердит: `BluetoothHeadset.startVoiceRecognition`. Не подтвердили —
 * гарнитура получает ошибку. Подтверждённое распознавание даёт две вещи:
 * стек сам поднимает канал к микрофону гарнитуры, а гарнитура знает, что
 * идёт распознавание, и её кнопка тогда его закрывает — канал падает. Это и
 * есть «стоп с головы», его ловит [watchDrop].
 *
 * Порядок важен. `HeadsetService.startVoiceRecognition` при уже поднятом
 * канале отказывает и сам же его ОПУСКАЕТ (`isAudioOn()` → `disconnectAudio`),
 * поэтому подтверждаем ДО того, как тейк поднимет свой маршрут
 * (`MicRouting.raise`). Связь со стеком держим открытой всё время службы:
 * получать её на нажатии — это десятки миллисекунд, а первые слова дороже.
 *
 * Закрыть распознавание — тоже наша забота: пока стек считает его открытым,
 * следующее нажатие гарнитуры он потратит на то, чтобы закрыть старое, и
 * кнопку придётся жать дважды. Тейки кончаются десятком дорог (кнопка,
 * «отмена», потолок локскрина, ошибка движка, десять минут без слов), и
 * вместо крючка в каждой служба на своём тике спрашивает [tick].
 */
class HeadsetVoice(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var proxy: BluetoothHeadset? = null
    private var opening = false
    private val waiting = ArrayList<(BluetoothHeadset?) -> Unit>()

    /** Распознавание подтвердили мы: на конце тейка его закрываем мы же. */
    var active = false
        private set
    private var device: BluetoothDevice? = null
    private var startedAt = 0L

    private var receiver: BroadcastReceiver? = null
    private var sawAudio = false
    private var onDrop: (() -> Unit)? = null

    /** «Устройства поблизости» (Android 12+) — без него стек с приложением не говорит. */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Держать связь со стеком наготове (подъём службы). Без разрешения — молча ничего. */
    fun open() = withProxy { }

    fun close() {
        stop("служба закрылась")
        val p = proxy
        proxy = null
        if (p != null) runCatching { adapter()?.closeProfileProxy(BluetoothProfile.HEADSET, p) }
    }

    /**
     * Подтвердить стеку распознавание. [onDone] зовётся ровно один раз, на
     * главном потоке: true — стек принял и сам поднимает канал к гарнитуре.
     * false — тейк всё равно идёт, просто маршрут поднимет он сам.
     */
    @SuppressLint("MissingPermission") // разрешение проверяет withProxy: без него proxy == null
    fun start(onDone: (Boolean) -> Unit) {
        if (active) stop("новое нажатие")
        withProxy { p ->
            if (p == null) {
                log(
                    if (hasPermission()) "стек гарнитуры недоступен — слушаем без подтверждения"
                    else "нет разрешения «Устройства поблизости» — стек не узнает, что Правка слушает"
                )
                onDone(false)
                return@withProxy
            }
            // Кто именно нажал, стек знает сам: при ждущей просьбе гарнитуры он
            // подставляет её вместо переданной («fall back to requesting device»).
            val dev = runCatching { p.connectedDevices }.getOrDefault(emptyList()).firstOrNull()
            if (dev == null) {
                log("подключённых гарнитур нет — команда пришла не с гарнитуры")
                onDone(false)
                return@withProxy
            }
            val ok = runCatching { p.startVoiceRecognition(dev) }.getOrElse {
                log("startVoiceRecognition: ${it.javaClass.simpleName}: ${it.message}")
                false
            }
            if (ok) {
                active = true
                device = dev
                startedAt = SystemClock.elapsedRealtime()
                log("распознавание подтверждено стеку — «${name(dev)}»")
            } else {
                log("стек отказал в распознавании («${name(dev)}») — маршрут поднимет тейк")
            }
            onDone(ok)
        }
    }

    /**
     * Следить, не закрылся ли канал гарнитуры посреди тейка: гарнитура
     * закрыла распознавание своей кнопкой, села, ушла из зоны, пришёл звонок.
     * Во всех случаях тейку пора кончаться — иначе он дослушивал бы
     * телефоном из кармана. Короткий «моргнувший» канал (обрыв и тут же
     * подъём, пока маршрут делят стек и тейк) стопом не считается.
     */
    fun watchDrop(onDrop: () -> Unit) {
        unwatch()
        this.onDrop = onDrop
        sawAudio = false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Липкое последнее состояние канала — из прошлого, не про этот тейк.
                if (isInitialStickyBroadcast) return
                val up = when (intent.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED ->
                        when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> true
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> false
                            else -> null
                        }
                    BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED ->
                        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                            BluetoothHeadset.STATE_AUDIO_CONNECTED -> true
                            BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> false
                            else -> null
                        }
                    else -> null
                } ?: return
                main.post { onAudio(up) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(r, filter)
            }
        }.isSuccess
        receiver = if (ok) r else null
        if (!ok) {
            this.onDrop = null
            log("не вышло следить за каналом — стоп только кнопкой на экране")
        }
    }

    /** Тик службы (раз в две секунды): тейк кончился любой дорогой — закрываем распознавание. */
    fun tick(takeRunning: Boolean) {
        if (!active || takeRunning) return
        // Запись Whisper встаёт не мгновенно: служба микрофона поднимается
        // асинхронно, и первый тик после нажатия может её ещё не видеть.
        if (SystemClock.elapsedRealtime() - startedAt < START_GRACE_MS) return
        stop("тейк кончился")
    }

    @SuppressLint("MissingPermission") // устройство получено через тот же proxy, с разрешением
    fun stop(why: String) {
        unwatch()
        if (!active) return
        active = false
        val p = proxy
        val d = device
        device = null
        val ok = runCatching { p != null && d != null && p.stopVoiceRecognition(d) }.getOrDefault(false)
        log("распознавание закрыто ($why)${if (ok) "" else " — стек уже закрыл его сам"}")
    }

    private fun onAudio(up: Boolean) {
        if (receiver == null) return
        if (up) {
            if (!sawAudio) log("канал гарнитуры поднят")
            sawAudio = true
            main.removeCallbacks(dropCheck)
            return
        }
        // Обрыв до подъёма — это ещё старт, не стоп.
        if (!sawAudio) return
        main.removeCallbacks(dropCheck)
        main.postDelayed(dropCheck, DROP_DEBOUNCE_MS)
    }

    private val dropCheck = Runnable {
        val cb = onDrop ?: return@Runnable
        unwatch()
        log("канал гарнитуры закрылся — тейк кончается")
        cb()
    }

    private fun unwatch() {
        main.removeCallbacks(dropCheck)
        onDrop = null
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null
    }

    private fun adapter() =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    private fun withProxy(block: (BluetoothHeadset?) -> Unit) {
        proxy?.let { block(it); return }
        if (!hasPermission()) { block(null); return }
        waiting += block
        if (opening) return
        opening = runCatching {
            adapter()?.getProfileProxy(context, listener, BluetoothProfile.HEADSET) == true
        }.getOrDefault(false)
        if (!opening) {
            flush(null)
            return
        }
        main.postDelayed({
            if (opening) {
                opening = false
                log("стек гарнитуры не ответил за $PROXY_WAIT_MS мс")
                flush(null)
            }
        }, PROXY_WAIT_MS)
    }

    private fun flush(p: BluetoothHeadset?) {
        val pending = ArrayList(waiting)
        waiting.clear()
        pending.forEach { it(p) }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            main.post {
                // Bluetooth был выключен: заявка ждала его включения, а на
                // нажатии ушла вторая — лишнюю связь закрываем, одной хватает.
                val had = proxy
                if (had != null && had !== p) {
                    runCatching { adapter()?.closeProfileProxy(BluetoothProfile.HEADSET, p) }
                    return@post
                }
                proxy = p as? BluetoothHeadset
                opening = false
                flush(proxy)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            main.post { proxy = null }
        }
    }

    @SuppressLint("MissingPermission")
    private fun name(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address

    private companion object {
        /** Сколько ждать связи со стеком на нажатии: из пяти секунд стека — с запасом. */
        const val PROXY_WAIT_MS = 1_500L

        /** Обрыв канала короче этого — «моргнул», а не закрылся. */
        const val DROP_DEBOUNCE_MS = 700L

        /** Сколько тик ждёт, пока тейк встанет, прежде чем счесть его несостоявшимся. */
        const val START_GRACE_MS = 4_000L
    }
}
