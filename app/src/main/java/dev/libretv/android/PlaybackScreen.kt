package dev.libretv.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as MediaR
import kotlinx.coroutines.delay
import java.util.Locale

private val fullScreenColors = darkColorScheme(primary = Color.White, onPrimary = Color.Black)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlaybackScreen(
    title: String,
    url: String,
    sessionKey: Any,
    episodeNames: List<String>,
    episodeIndex: Int,
    onEpisode: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val activity = checkNotNull(LocalActivity.current)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val originalOrientation = rememberSaveable { activity.requestedOrientation }
    var fullScreen by rememberSaveable { mutableStateOf(landscape) }
    var lastLandscape by rememberSaveable { mutableStateOf(landscape) }
    var choosingEpisode by rememberSaveable { mutableStateOf(false) }
    val session = rememberPlaybackSession(sessionKey, url)
    var controlsVisible by remember(session) { mutableStateOf(true) }
    var scrubbing by remember(session) { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    val controlsTimeout = accessibility?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 5_000L, containsIcons = true, containsText = true, containsControls = true,
    ) ?: 5_000L

    LaunchedEffect(landscape) {
        if (landscape != lastLandscape) {
            lastLandscape = landscape
            fullScreen = landscape
            controlsVisible = true
        }
    }
    val bars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    val controller = remember(activity) {
        val window = activity.window
        WindowCompat.getInsetsController(window, window.decorView)
    }
    val originalBarsBehavior = remember(controller) { controller.systemBarsBehavior }
    DisposableEffect(activity) {
        onDispose {
            controller.systemBarsBehavior = originalBarsBehavior
            controller.show(bars)
            activity.requestedOrientation = originalOrientation
        }
    }
    DisposableEffect(controller, fullScreen, landscape) {
        controller.systemBarsBehavior = if (fullScreen) {
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else originalBarsBehavior
        fun applyVisibility(types: Int) {
            if (types != 0) {
                if (fullScreen) controller.hide(types) else controller.show(types)
            }
        }
        // Rotation can transfer bar control after the windowed layout is already on screen.
        val listener = WindowInsetsControllerCompat.OnControllableInsetsChangedListener { _, types ->
            applyVisibility(types and bars)
        }
        controller.addOnControllableInsetsChangedListener(listener)
        applyVisibility(bars)
        onDispose { controller.removeOnControllableInsetsChangedListener(listener) }
    }
    LaunchedEffect(session, fullScreen, session.playing, controlsVisible, scrubbing, choosingEpisode, controlsTimeout) {
        if (fullScreen && session.playing && controlsVisible && !scrubbing && !choosingEpisode) {
            delay(controlsTimeout)
            controlsVisible = false
        }
    }
    LaunchedEffect(session, session.phase) {
        if (session.phase != PlaybackPhase.PLAYING) controlsVisible = true
    }
    val setFullScreen: (Boolean) -> Unit = { enter ->
        fullScreen = enter
        controlsVisible = true
        activity.requestedOrientation = if (enter) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else originalOrientation
    }
    val back = { if (fullScreen) setFullScreen(false) else onBack() }
    BackHandler(onBack = back)

    val controls: @Composable () -> Unit = {
        PlaybackControls(session, episodeNames.size, episodeIndex, onEpisode,
            onChoose = { choosingEpisode = true }, onScrub = { scrubbing = it })
    }
    if (fullScreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlaybackVideo(session, Modifier.fillMaxSize())
            val failed = session.failure != null
            if (!failed) {
                Box(Modifier.matchParentSize().clickable(
                    onClickLabel = if (controlsVisible) "隐藏播放控件" else "显示播放控件",
                    onClick = { controlsVisible = !controlsVisible },
                ).semantics { contentDescription = "播放画面" })
            }
            if (controlsVisible || failed) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.82f))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 12.dp),
                ) {
                    MaterialTheme(colorScheme = fullScreenColors) {
                        CompositionLocalProvider(LocalContentColor provides Color.White) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                PlayerIcon("退出全屏", MediaR.drawable.exo_ic_fullscreen_exit) { setFullScreen(false) }
                            }
                            Text(session.failure ?: session.status, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            controls()
                        }
                    }
                }
            }
        }
    } else {
        Surface(Modifier.fillMaxSize()) {
            val content = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            Column(if (landscape) content else content.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back) { Text("返回") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = if (landscape) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                    PlayerIcon("全屏", MediaR.drawable.exo_ic_fullscreen_enter) { setFullScreen(true) }
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        PlaybackVideo(session, Modifier.weight(0.55f).fillMaxHeight())
                        WindowedControls(session, Modifier.weight(0.45f).verticalScroll(rememberScrollState()), controls)
                    }
                } else {
                    PlaybackVideo(session, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    WindowedControls(session, Modifier.fillMaxWidth(), controls)
                }
            }
        }
    }
    if (choosingEpisode && episodeNames.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { choosingEpisode = false },
            title = { Text("选择剧集") },
            text = {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = episodeIndex.coerceIn(episodeNames.indices))
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
                    items(episodeNames.size) { index ->
                        val current = index == episodeIndex
                        TextButton(
                            modifier = Modifier.fillMaxWidth(), onClick = {
                                choosingEpisode = false
                                if (!current) onEpisode(index)
                            },
                        ) {
                            Text(episodeNames[index] + if (current) " · 当前" else "", Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingEpisode = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun WindowedControls(session: PlaybackSession, modifier: Modifier, controls: @Composable () -> Unit) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(session.failure ?: session.status, style = MaterialTheme.typography.titleMedium)
        controls()
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val audio = when {
            session.audioAdvanced -> "已输出"
            session.audioTrack -> "已选择"
            else -> "等待"
        }
        val picture = if (session.audioOnly) "纯音频" else "首帧 " + (if (session.firstFrame) "已显示" else "等待")
        Text("$picture  ·  音轨 $audio",
            style = MaterialTheme.typography.bodySmall)
        Text("清单 " + session.manifests + "  ·  媒体加载 " + session.mediaLoads,
            style = MaterialTheme.typography.bodySmall)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlaybackVideo(session: PlaybackSession, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            if (view.player !== session.player) view.player = session.player
            view.keepScreenOn = session.wantsPlay && session.phase != PlaybackPhase.ENDED && session.failure == null
        },
        onRelease = { it.keepScreenOn = false; it.player = null },
        modifier = modifier,
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlaybackControls(
    session: PlaybackSession, count: Int, index: Int, onEpisode: (Int) -> Unit,
    onChoose: () -> Unit, onScrub: (Boolean) -> Unit,
) {
    var drag by remember(session) { mutableStateOf<Float?>(null) }
    val duration = session.durationMs
    val fraction = drag ?: if (duration > 0) (session.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    DisposableEffect(session) { onDispose { onScrub(false) } }
    Slider(
        value = fraction,
        enabled = session.canSeek && duration > 0 && session.failure == null,
        onValueChange = { drag = it; onScrub(true) },
        onValueChangeFinished = {
            drag?.let { session.seekTo((duration * it.toDouble()).toLong()) }
            drag = null
            onScrub(false)
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "播放进度" },
    )
    Text(clockText(drag?.let { (duration * it.toDouble()).toLong() } ?: session.positionMs) +
        " / " + if (duration > 0) clockText(duration) else "--:--",
        style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (session.failure != null) {
            if (session.valid) TextButton(onClick = session::retry) { Text("重试") }
        } else {
            val ended = session.phase == PlaybackPhase.ENDED
            val pause = session.wantsPlay && !ended
            PlayerIcon(if (ended) "重播" else if (pause) "暂停" else "播放",
                if (pause) MediaR.drawable.exo_ic_pause_circle_filled else MediaR.drawable.exo_ic_play_circle_filled,
                enabled = session.player != null, onClick = session::togglePlay)
        }
        if (count > 0) {
            PlayerIcon("上一集", MediaR.drawable.exo_ic_skip_previous, enabled = index > 0) { onEpisode(index - 1) }
            PlayerIcon("下一集", MediaR.drawable.exo_ic_skip_next, enabled = index < count - 1) { onEpisode(index + 1) }
            TextButton(onClick = onChoose) { Text("选集") }
        }
    }
}

@Composable
private fun PlayerIcon(label: String, drawable: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(drawable), contentDescription = label)
    }
}

private fun clockText(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds < 3600) String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
