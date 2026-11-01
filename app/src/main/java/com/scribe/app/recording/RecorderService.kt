package com.scribe.app.recording

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.scribe.app.MainActivity
import com.scribe.app.NOTIF_CHANNEL_RECORDING
import com.scribe.app.R
import com.scribe.app.app
import com.scribe.app.data.Bookmark
import com.scribe.app.util.formatClock
import com.scribe.app.work.Jobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class RecPhase { IDLE, RECORDING, PAUSED }

data class RecState(
    val phase: RecPhase = RecPhase.IDLE,
    val elapsedMs: Long = 0,
    val levels: List<Float> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val lastSavedId: String? = null,
    val error: String? = null,
)

object Recorder {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()
    internal fun set(block: (RecState) -> RecState) { _state.value = block(_state.value) }

    fun start(ctx: Context) = send(ctx, RecorderService.ACTION_START)
    fun pause(ctx: Context) = send(ctx, RecorderService.ACTION_PAUSE)
    fun resume(ctx: Context) = send(ctx, RecorderService.ACTION_RESUME)
    fun stop(ctx: Context) = send(ctx, RecorderService.ACTION_STOP)
    fun bookmark(ctx: Context) = send(ctx, RecorderService.ACTION_BOOKMARK)
    fun consumeSaved() = set { it.copy(lastSavedId = null) }

    private fun send(ctx: Context, action: String) {
        val i = Intent(ctx, RecorderService::class.java).setAction(action)
        if (action == RecorderService.ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
    }
}

/**
 * Foreground microphone service so recording continues with the screen off or the app in the background —
 * the phone equivalent of a dedicated recorder's one-press capture.
 */
class RecorderService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val ACTION_BOOKMARK = "bookmark"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var id: String? = null
    private var startedAt = 0L
    private var segmentStart = 0L
    private var accumulated = 0L
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun elapsed(): Long =
        accumulated + if (Recorder.state.value.phase == RecPhase.RECORDING) SystemClock.elapsedRealtime() - segmentStart else 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
            ACTION_BOOKMARK -> bookmark()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        val repo = app.container.repo
        val hq = app.container.settings.value.highQuality
        val (newId, f) = repo.newAudioFile("m4a")
        id = newId; file = f
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(if (hq) 48000 else 32000)
            r.setAudioEncodingBitRate(if (hq) 128_000 else 64_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) {
            Recorder.set { RecState(error = "Could not start microphone: ${e.message}") }
            stopSelf()
            return
        }
        startedAt = System.currentTimeMillis()
        segmentStart = SystemClock.elapsedRealtime()
        accumulated = 0
        Recorder.set { RecState(phase = RecPhase.RECORDING) }
        ticker = scope.launch {
            var lastNotif = 0L
            while (isActive) {
                val st = Recorder.state.value
                val level = if (st.phase == RecPhase.RECORDING) {
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    (kotlin.math.sqrt(amp / 32767f)).coerceIn(0f, 1f)
                } else 0f
                Recorder.set { it.copy(elapsedMs = elapsed(), levels = (it.levels + level).takeLast(120)) }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotif > 1000) { updateNotification(); lastNotif = now }
                delay(80)
            }
        }
    }

    private fun pause() {
        val r = recorder ?: return
        if (Recorder.state.value.phase != RecPhase.RECORDING) return
        runCatching { r.pause() }
        accumulated += SystemClock.elapsedRealtime() - segmentStart
        Recorder.set { it.copy(phase = RecPhase.PAUSED, elapsedMs = accumulated) }
        updateNotification()
    }

    private fun resume() {
        val r = recorder ?: return
        if (Recorder.state.value.phase != RecPhase.PAUSED) return
        runCatching { r.resume() }
        segmentStart = SystemClock.elapsedRealtime()
        Recorder.set { it.copy(phase = RecPhase.RECORDING) }
        updateNotification()
    }

    private fun bookmark() {
        if (recorder == null) return
        val t = elapsed()
        Recorder.set { it.copy(bookmarks = it.bookmarks + Bookmark(t, "")) }
    }

    private fun stop() {
        val r = recorder
        if (r == null) { stopSelf(); return }
        val duration = elapsed()
        ticker?.cancel()
        runCatching { r.stop() }
        r.release()
        recorder = null
        val f = file!!
        val recId = id!!
        val bookmarks = Recorder.state.value.bookmarks
        val c = app.container
        scope.launch {
            if (f.exists() && f.length() > 0) {
                c.repo.createRecording(recId, f, "audio/mp4", duration, bookmarks, createdAt = startedAt)
                if (c.settings.value.autoProcess && c.settings.value.isCloudConfigured) Jobs.process(applicationContext, recId)
                Recorder.set { RecState(lastSavedId = recId) }
            } else {
                Recorder.set { RecState(error = "Recording was empty") }
            }
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun action(action: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, RecorderService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val st = Recorder.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_RECORDER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = st.phase == RecPhase.PAUSED
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (paused) "Recording paused" else "Recording")
            .setContentText(formatClock(st.elapsedMs) + if (st.bookmarks.isNotEmpty()) " · ${st.bookmarks.size} flagged" else "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Flag moment", action(ACTION_BOOKMARK, 2))
            .addAction(0, "Stop & save", action(ACTION_STOP, 3))
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        super.onDestroy()
    }
}
