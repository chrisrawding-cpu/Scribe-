package com.scribe.app.ui.detail

import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

class AudioPlayer(private val scope: CoroutineScope) {
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var pendingSeek: Long? = null

    fun setLoading(loading: Boolean, error: String? = null) {
        _state.value = _state.value.copy(loading = loading, error = error)
    }

    fun load(file: File, autoPlay: Boolean) {
        release()
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMs = 0); ticker?.cancel() }
            mp = p
            _state.value = PlayerState(loaded = true, durationMs = p.duration.toLong(), speed = _state.value.speed)
            pendingSeek?.let { p.seekTo(it.toInt()); pendingSeek = null; _state.value = _state.value.copy(positionMs = p.currentPosition.toLong()) }
            if (autoPlay) play()
        } catch (e: Exception) {
            _state.value = PlayerState(error = "Can't play audio: ${e.message}")
        }
    }

    fun play() {
        val p = mp ?: return
        runCatching { p.playbackParams = PlaybackParams().setSpeed(_state.value.speed) }
        p.start()
        _state.value = _state.value.copy(playing = true)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                mp?.let { _state.value = _state.value.copy(positionMs = it.currentPosition.toLong()) }
                delay(200)
            }
        }
    }

    fun pause() {
        mp?.takeIf { it.isPlaying }?.pause()
        ticker?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun seekTo(ms: Long) {
        val p = mp
        if (p == null) { pendingSeek = ms; return }
        p.seekTo(ms.coerceIn(0, _state.value.durationMs).toInt())
        _state.value = _state.value.copy(positionMs = ms)
    }

    fun skip(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun cycleSpeed() {
        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        val next = speeds[(speeds.indexOf(_state.value.speed) + 1) % speeds.size]
        _state.value = _state.value.copy(speed = next)
        mp?.let { p -> if (p.isPlaying) runCatching { p.playbackParams = p.playbackParams.setSpeed(next) } }
    }

    fun release() {
        ticker?.cancel()
        mp?.release()
        mp = null
    }
}
