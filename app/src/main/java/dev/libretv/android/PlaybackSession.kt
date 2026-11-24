package dev.libretv.android

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val CONTROL_HLS_URL = "asset:///control/index.m3u8"
private const val PLAYBACK_TAG = "LibreTVPlayback"
private val sessionIds = AtomicLong()

internal enum class PlaybackPhase(val label: String) {
    WAITING("等待首帧"), BUFFERING("缓冲中"), PLAYING("播放中"), PAUSED("已暂停"),
    INTERRUPTED("音频焦点中断"), ENDED("已播完"), FAILED("播放失败")
}

internal enum class PlaybackTracks { PENDING, AUDIO_ONLY, VIDEO_SELECTED, VIDEO_UNSUPPORTED }

internal fun classifyPlaybackTracks(
    hasVideo: Boolean, videoSelected: Boolean, videoSupported: Boolean, audioSelected: Boolean,
): PlaybackTracks = when {
    videoSelected -> PlaybackTracks.VIDEO_SELECTED
    hasVideo && !videoSupported -> PlaybackTracks.VIDEO_UNSUPPORTED
    !hasVideo && audioSelected -> PlaybackTracks.AUDIO_ONLY
    else -> PlaybackTracks.PENDING
}

internal fun playbackPhase(error: Boolean, state: Int, wantsPlay: Boolean, suppressed: Boolean,
                           firstFrame: Boolean, tracks: PlaybackTracks): PlaybackPhase = when {
    error -> PlaybackPhase.FAILED
    tracks == PlaybackTracks.VIDEO_UNSUPPORTED && (state == Player.STATE_READY || state == Player.STATE_ENDED) -> PlaybackPhase.FAILED
    state == Player.STATE_ENDED -> PlaybackPhase.ENDED
    !wantsPlay -> PlaybackPhase.PAUSED
    suppressed -> PlaybackPhase.INTERRUPTED
    tracks != PlaybackTracks.AUDIO_ONLY && !(tracks == PlaybackTracks.VIDEO_SELECTED && firstFrame) -> PlaybackPhase.WAITING
    state == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
    state == Player.STATE_READY -> PlaybackPhase.PLAYING
    else -> PlaybackPhase.WAITING
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackSession(
    private val context: Context,
    private val url: String,
    private val scope: CoroutineScope,
    private val savedPosition: MutableLongState,
    private val savedPlayIntent: MutableState<Boolean>,
) {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var phase by mutableStateOf(PlaybackPhase.WAITING)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var manifests by mutableIntStateOf(0)
        private set
    var mediaLoads by mutableIntStateOf(0)
        private set
    var firstFrame by mutableStateOf(false)
        private set
    var audioTrack by mutableStateOf(false)
        private set
    var audioAdvanced by mutableStateOf(false)
        private set
    private var tracks by mutableStateOf(PlaybackTracks.PENDING)
    val audioOnly get() = tracks == PlaybackTracks.AUDIO_ONLY
    var playing by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var canSeek by mutableStateOf(false)
        private set
    val positionMs get() = savedPosition.longValue
    val wantsPlay get() = savedPlayIntent.value
    val status get() = phase.label
    private val uri = Uri.parse(url)
    private val control = url == CONTROL_HLS_URL
    val valid = control || (uri.scheme == "https" && !uri.host.isNullOrBlank())
    private var foreground = false
    private var generation = 0L
    private var prepareJob: Job? = null
    private var listener: Player.Listener? = null
    private var analytics: AnalyticsListener? = null
    private var lastProgressBucket = -1L

    private fun log(event: String) {
        Log.i(PLAYBACK_TAG, "$event session=$generation")
    }

    fun start(inForeground: Boolean) {
        foreground = inForeground
        if (!valid) {
            failure = "播放地址无效"
            phase = PlaybackPhase.FAILED
            return
        }
        val source = if (control) {
            DataSource.Factory { AssetDataSource(context) }
        } else {
            ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { spec ->
                if (spec.uri.scheme != "https") throw IOException("HTTPS required")
                spec
            }
        }
        val current = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = current
        generation = sessionIds.incrementAndGet()
        lastProgressBucket = -1L
        failure = null
        firstFrame = false
        audioTrack = false
        audioAdvanced = false
        tracks = PlaybackTracks.PENDING
        manifests = 0
        mediaLoads = 0
        fun active() = player === current
        val events = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (active()) update(current)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (active() && foreground) savedPlayIntent.value = playWhenReady
            }
            override fun onRenderedFirstFrame() {
                if (!active()) return
                firstFrame = true
                update(current)
                log("first_frame position_ms=" + current.currentPosition)
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (!active()) return
                audioTrack = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
                this@PlaybackSession.tracks = classifyPlaybackTracks(
                    tracks.containsType(C.TRACK_TYPE_VIDEO),
                    tracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
                    tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true),
                    audioTrack,
                )
                if (this@PlaybackSession.tracks != PlaybackTracks.VIDEO_SELECTED) firstFrame = false
                log("tracks audio_selected=$audioTrack")
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (!active() || reason != Player.DISCONTINUITY_REASON_SEEK) return
                savedPosition.longValue = new.positionMs
                log("seek from_ms=" + old.positionMs + " to_ms=" + new.positionMs)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!active()) return
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val message = when (error.errorCode) {
                    in 4000..4999 -> "设备无法解码此媒体"
                    in 3000..3999 -> "无法识别媒体格式"
                    in 2000..2999 -> "媒体加载失败"
                    else -> "播放中断"
                }
                failure = listOfNotNull(message, http?.let { "HTTP $it" }).joinToString(" · ")
                update(current)
                log("player_error code=" + error.errorCodeName + " http=" + http)
            }
        }
        val diagnostics = object : AnalyticsListener {
            override fun onLoadCompleted(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                if (!active()) return
                when (data.dataType) {
                    C.DATA_TYPE_MANIFEST -> { manifests++; log("manifest count=$manifests") }
                    C.DATA_TYPE_MEDIA -> { mediaLoads++; log("media count=$mediaLoads bytes=" + info.bytesLoaded) }
                }
            }
            override fun onAudioPositionAdvancing(time: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                if (!active()) return
                audioAdvanced = true
                log("audio_output advancing=true")
            }
            override fun onLoadError(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData,
                                     error: IOException, wasCanceled: Boolean) {
                if (!active()) return
                val kind = if (data.dataType == C.DATA_TYPE_MANIFEST) "manifest" else "media"
                log("load_error phase=$kind kind=" + error.javaClass.simpleName + " canceled=$wasCanceled")
            }
        }
        listener = events
        analytics = diagnostics
        current.addListener(events)
        current.addAnalyticsListener(diagnostics)
        current.playWhenReady = foreground && wantsPlay
        log("open source=" + if (control) "asset" else "https")
        prepareJob = scope.launch {
            val mime = resolvePlaybackMimeType(url)
            if (!active()) return@launch
            current.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mime).build(), positionMs)
            current.prepare()
        }
    }

    private fun update(current: ExoPlayer) {
        playing = current.isPlaying
        durationMs = current.duration.coerceAtLeast(0L)
        if (!playing) poll()
        canSeek = current.isCurrentMediaItemSeekable && durationMs > 0
        phase = playbackPhase(failure != null, current.playbackState, current.playWhenReady,
            current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            firstFrame, tracks)
        if (phase == PlaybackPhase.FAILED && failure == null) {
            failure = "此设备不支持视频轨，请返回详情切换线路或来源"
            current.pause()
            log("unsupported_video")
        }
    }

    fun poll() {
        val current = player ?: return
        if (current.currentMediaItem == null) return
        savedPosition.longValue = if (current.playbackState == Player.STATE_ENDED && current.duration > 0L) {
            current.duration
        } else current.currentPosition.coerceAtLeast(0L)
        val bucket = positionMs / 5000
        if (playing && bucket != lastProgressBucket) {
            log("progress position_ms=$positionMs")
            lastProgressBucket = bucket
        }
    }

    fun foreground() {
        foreground = true
        player?.playWhenReady = wantsPlay
    }

    fun background(changingConfiguration: Boolean) {
        foreground = false
        poll()
        if (!changingConfiguration) savedPlayIntent.value = false
        player?.pause()
    }

    fun togglePlay() {
        val current = player ?: return
        if (!foreground || failure != null) return
        if (phase == PlaybackPhase.ENDED) {
            current.seekToDefaultPosition()
            current.play()
        } else if (current.playWhenReady) current.pause() else current.play()
    }

    fun seekTo(positionMs: Long) {
        if (canSeek) player?.seekTo(positionMs.coerceIn(0L, durationMs))
    }

    fun retry() {
        if (!valid || failure == null || !foreground) return
        close()
        savedPlayIntent.value = true
        start(true)
    }

    fun close() {
        val current = player ?: return
        poll()
        // Invalidate the owner before removing listeners or releasing the old player.
        player = null
        prepareJob?.cancel()
        listener?.let(current::removeListener)
        analytics?.let(current::removeAnalyticsListener)
        current.release()
        playing = false
        canSeek = false
        durationMs = 0
        log("release")
    }
}

@Composable
internal fun rememberPlaybackSession(sessionKey: Any, url: String): PlaybackSession {
    val context = LocalContext.current.applicationContext
    val activity = LocalActivity.current as ComponentActivity
    val lifecycle = activity.lifecycle
    val scope = rememberCoroutineScope()
    val position = rememberSaveable(sessionKey, url) { mutableLongStateOf(0L) }
    val intent = rememberSaveable(sessionKey, url) { mutableStateOf(true) }
    val session = remember(sessionKey, url, lifecycle) { PlaybackSession(context, url, scope, position, intent) }
    DisposableEffect(session) {
        session.start(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.background(activity.isChangingConfigurations)
                Lifecycle.Event.ON_START -> session.foreground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.close()
        }
    }
    LaunchedEffect(session, session.playing) {
        while (session.playing) {
            session.poll()
            delay(400)
        }
    }
    return session
}
