package dev.libretv.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EnvironmentScreen() } }
    }
}

@Composable
private fun EnvironmentScreen() {
    val context = LocalContext.current.applicationContext
    val pending = stringResource(R.string.pending)
    var databaseResult by remember { mutableStateOf(pending) }
    var playerResult by remember { mutableStateOf(pending) }

    LaunchedEffect(context) {
        databaseResult = checkResult(context, R.string.database_ok) {
            withContext(Dispatchers.IO) { checkRoom(context) }
        }
        playerResult = checkResult(context, R.string.player_ok) { checkPlayback(context) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.stage_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.database_title), style = MaterialTheme.typography.titleMedium)
            Text(databaseResult)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.player_title), style = MaterialTheme.typography.titleMedium)
            Text(playerResult)
        }
    }
}

private suspend fun checkResult(context: Context, successMessage: Int, check: suspend () -> Unit): String {
    return try {
        check()
        context.getString(successMessage)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        context.getString(R.string.check_failed, failure.javaClass.simpleName)
    }
}

private fun checkRoom(context: Context) {
    val database = Room.inMemoryDatabaseBuilder(context, ProbeDatabase::class.java).build()
    try {
        val record = ProbeRecord(id = 1, value = "libretv-room-probe")
        val records = database.records()
        records.insert(record)
        check(records.read(record.id) == record)
    } finally {
        database.close()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun checkPlayback(context: Context) {
    val player = ExoPlayer.Builder(context).build()
    try {
        val item = MediaItem.Builder()
            .setMediaId("hls-probe")
            .setUri("https://example.invalid/stage-zero.m3u8")
            .build()
        val source = HlsMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(item)
        // Stage zero checks initialization only. Preparing this item would start a network request.
        player.setMediaSource(source)
        check(player.mediaItemCount == 1 && player.currentMediaItem?.mediaId == item.mediaId)
    } finally {
        player.release()
    }
}
