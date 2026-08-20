package ru.zf.pravka.trigger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread
import ru.zf.pravka.R
import ru.zf.pravka.data.Recordings
import ru.zf.pravka.data.WavFile

// Records the microphone to a WAV file while the owner moves between apps
// (Wispr-style). A foreground service with type "microphone" is the only way
// Android lets an app keep the mic while it is not in the foreground; the
// notification is mandatory. Recording and transcription are decoupled - this
// service only captures audio to disk, so a recording survives even if the
// app dies before it is transcribed.
class DictationService : Service() {

    companion object {
        const val ACTION_START = "ru.zf.pravka.DICTATE_START"
        const val ACTION_STOP = "ru.zf.pravka.DICTATE_STOP"
        // Google-engine live dictation: we don't record a WAV, but we still
        // need a foreground "microphone" service so the system recognizer may
        // keep the mic while the owner switches apps. HOLD_START/STOP manage
        // that empty holder; USER_STOP is the notification's Stop button.
        const val ACTION_HOLD_START = "ru.zf.pravka.DICTATE_HOLD_START"
        const val ACTION_HOLD_STOP = "ru.zf.pravka.DICTATE_HOLD_STOP"
        const val ACTION_HOLD_USER_STOP = "ru.zf.pravka.DICTATE_HOLD_USER_STOP"
        private const val CHANNEL = "dictation"
        private const val NOTIF_ID = 42

        @Volatile var recording: Boolean = false
            private set

    }

    private var record: AudioRecord? = null
    private var writer: WavFile.Writer? = null
    private var worker: Thread? = null
    @Volatile private var active = false
    private lateinit var currentFile: File
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // A partial wake lock keeps the CPU running so dictation keeps recognizing
    // even if the screen turns off / the device tries to doze. Timeout is a
    // safety leak-guard; normal takes release it explicitly on stop.
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pravka:dictation").apply {
            setReferenceCounted(false)
            runCatching { acquire(15 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var holding = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_HOLD_START -> holdStart()
            ACTION_HOLD_STOP -> holdStop()
            ACTION_HOLD_USER_STOP -> {
                // The notification's Stop button during a Google take: let the
                // accessibility service finalize the session (it tears down the
                // hold afterwards via ACTION_HOLD_STOP).
                PravkaAccessibilityService.instance?.stopLiveDictation()
            }
            ACTION_STOP, null -> stop()
        }
        return START_NOT_STICKY
    }

    private fun holdStart() {
        if (holding) return
        holding = true
        startForegroundWithType(holdStop = true)
        acquireWakeLock()
    }

    private fun holdStop() {
        holding = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service is started
    private fun start() {
        if (active) return
        startForegroundWithType()
        acquireWakeLock()

        val recordings = Recordings(this)
        // Cheap directory sweep at the start of a take, never in its hot path.
        runCatching { recordings.prune() }
        currentFile = recordings.newFile()
        val minBuf = AudioRecord.getMinBufferSize(
            WavFile.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WavFile.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            stopSelf()
            return
        }
        val out = WavFile.Writer(currentFile)
        record = recorder
        writer = out
        active = true
        recording = true

        recorder.startRecording()
        worker = thread(name = "pravka-mic") {
            val buf = ByteArray(minBuf)
            // The write is guarded: if stop() outwaits the 500ms join while
            // this thread is blocked in read(), the writer may already be
            // closed when the read returns - an uncaught IOException on this
            // raw thread would kill the whole process mid-handoff.
            runCatching {
                while (active) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0 && active) out.write(buf, n)
                }
            }
        }
    }

    private fun stop() {
        releaseWakeLock()
        if (active) {
            active = false
            recording = false
            runCatching { worker?.join(500) }
            runCatching { record?.stop() }
            runCatching { record?.release() }
            writer?.close()
            record = null
            writer = null
            val saved = currentFile.takeIf { it.exists() && it.length() > 44 }
            // Hand the file to the accessibility service (same process) for
            // transcription + insertion. The file is the source of truth: if
            // this fails, it stays on disk for a later retry.
            android.os.Handler(mainLooper).post {
                PravkaAccessibilityService.instance?.onRecordingSaved(saved)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (active) stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundWithType(holdStop: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.dictation_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopAction = if (holdStop) ACTION_HOLD_USER_STOP else ACTION_STOP
        val stopIntent = PendingIntent.getService(
            this, if (holdStop) 1 else 0,
            Intent(this, DictationService::class.java).setAction(stopAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.dictation_recording))
            .setContentText(getString(R.string.dictation_recording_hint))
            .setSmallIcon(R.drawable.ic_tile)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    getString(R.string.dictation_stop),
                    stopIntent,
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}

fun Context.startDictation() {
    startForegroundService(
        Intent(this, DictationService::class.java).setAction(DictationService.ACTION_START)
    )
}

fun Context.stopDictation() {
    startService(
        Intent(this, DictationService::class.java).setAction(DictationService.ACTION_STOP)
    )
}

// Empty foreground-mic holder for the Google live engine (no WAV recorded).
fun Context.startMicHold() {
    startForegroundService(
        Intent(this, DictationService::class.java).setAction(DictationService.ACTION_HOLD_START)
    )
}

fun Context.stopMicHold() {
    startService(
        Intent(this, DictationService::class.java).setAction(DictationService.ACTION_HOLD_STOP)
    )
}
