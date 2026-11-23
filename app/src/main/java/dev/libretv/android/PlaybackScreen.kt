package dev.libretv.android

import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.media3.ui.PlayerView
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val CONTROL_HLS_URL = "asset:///control/index.m3u8"
private const val PLAYBACK_TAG = "LibreTVPlayback"

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(title: String, url: String, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = (LocalActivity.current as ComponentActivity).lifecycle
    val playbackScope = rememberCoroutineScope()
    val control = url == CONTROL_HLS_URL
    val itemUri = remember(url) { Uri.parse(url) }
    val valid = control || (itemUri.scheme == "https" && !itemUri.host.isNullOrEmpty())
    var position by rememberSaveable(url) { mutableLongStateOf(0L) }
    var resume by rememberSaveable(url) { mutableStateOf(true) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var livePlayer by remember(url) { mutableStateOf<ExoPlayer?>(null) }
    var status by remember(url) { mutableStateOf("正在准备") }
    var failure by remember(url) { mutableStateOf<String?>(null) }
    var manifests by remember(url) { mutableIntStateOf(0) }
    var mediaLoads by remember(url) { mutableIntStateOf(0) }
    var firstFrame by remember(url) { mutableStateOf(false) }
    var audioTrack by remember(url) { mutableStateOf(false) }
    var audioAdvanced by remember(url) { mutableStateOf(false) }
    var isPlaying by remember(url) { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    DisposableEffect(url, attempt, lifecycle) {
        if (!valid) {
            failure = "播放地址无效"
            return@DisposableEffect onDispose {}
        }
        val source: DataSource.Factory = if (control) {
            DataSource.Factory { AssetDataSource(context) }
        } else {
            ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { spec ->
                if (spec.uri.scheme != "https") throw IOException("HTTPS required")
                spec
            }
        }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        failure = null
        firstFrame = false
        audioTrack = false
        audioAdvanced = false
        manifests = 0
        mediaLoads = 0
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_BUFFERING -> "缓冲中"
                    Player.STATE_READY -> "就绪"
                    Player.STATE_ENDED -> "已播完"
                    else -> "等待"
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                Log.i(PLAYBACK_TAG, "first_frame position_ms=" + player.currentPosition)
            }
            override fun onTracksChanged(tracks: Tracks) {
                audioTrack = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
                Log.i(PLAYBACK_TAG, "tracks audio_selected=" + audioTrack)
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    position = new.positionMs
                    Log.i(PLAYBACK_TAG, "seek from_ms=" + old.positionMs + " to_ms=" + new.positionMs)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                failure = listOfNotNull(error.errorCodeName, http?.let { "HTTP " + it }).joinToString(" · ")
                Log.w(PLAYBACK_TAG, "player_error code=" + error.errorCodeName + " http=" + http)
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onLoadCompleted(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                when (data.dataType) {
                    C.DATA_TYPE_MANIFEST -> {
                        manifests++
                        Log.i(PLAYBACK_TAG, "manifest count=" + manifests)
                    }
                    C.DATA_TYPE_MEDIA -> {
                        mediaLoads++
                        Log.i(PLAYBACK_TAG, "media count=" + mediaLoads + " bytes=" + info.bytesLoaded)
                    }
                }
            }
            override fun onAudioPositionAdvancing(time: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                audioAdvanced = true
                Log.i(PLAYBACK_TAG, "audio_output advancing=true")
            }
            override fun onLoadError(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                val phase = if (data.dataType == C.DATA_TYPE_MANIFEST) "manifest" else "media"
                Log.w(PLAYBACK_TAG, "load_error phase=" + phase + " kind=" + error.javaClass.simpleName + " canceled=" + wasCanceled)
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        player.playWhenReady = resume && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val preparation = playbackScope.launch {
            val mime = resolvePlaybackMimeType(url)
            val item = MediaItem.Builder().setUri(itemUri).setMimeType(mime).build()
            player.setMediaItem(item, position)
            player.prepare()
        }
        livePlayer = player
        Log.i(PLAYBACK_TAG, "open source=" + if (control) "asset" else "https")
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resume = player.playWhenReady
                    position = player.currentPosition
                    player.pause()
                }
                Lifecycle.Event.ON_START -> player.playWhenReady = resume
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            preparation.cancel()
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
            player.release()
            livePlayer = null
        }
    }

    LaunchedEffect(livePlayer, isPlaying) {
        val player = livePlayer ?: return@LaunchedEffect
        var lastReported = -1L
        while (isPlaying) {
            position = player.currentPosition
            val bucket = position / 5000
            if (bucket != lastReported) {
                Log.i(PLAYBACK_TAG, "progress position_ms=" + position)
                lastReported = bucket
            }
            delay(500)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(title, style = MaterialTheme.typography.titleLarge)
        AndroidView(
            factory = { PlayerView(it) },
            update = { it.player = livePlayer },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        Text(failure ?: if (isPlaying) "播放中" else status)
        Text("清单 " + manifests + "  ·  媒体加载 " + mediaLoads + "  ·  位置 " + position / 1000 + " 秒")
        val audio = when {
            audioAdvanced -> "已输出"
            audioTrack -> "已选择"
            else -> "等待"
        }
        Text("首帧 " + (if (firstFrame) "已显示" else "等待") + "  ·  音轨 " + audio)
        if (failure != null && valid) {
            Button(onClick = { resume = true; attempt++ }) { Text("重试") }
        }
    }
}
