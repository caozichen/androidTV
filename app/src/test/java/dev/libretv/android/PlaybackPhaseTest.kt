package dev.libretv.android

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPhaseTest {
    private fun phase(
        state: Int, frame: Boolean = false, play: Boolean = true,
        error: Boolean = false, suppressed: Boolean = false, tracks: PlaybackTracks = PlaybackTracks.VIDEO_SELECTED,
    ) = playbackPhase(error, state, play, suppressed, frame, tracks)

    @Test fun firstFrameAndRebufferingHaveSeparateIndicators() {
        assertEquals(PlaybackPhase.WAITING, phase(Player.STATE_BUFFERING))
        assertEquals(PlaybackPhase.WAITING, phase(Player.STATE_READY))
        assertEquals(PlaybackPhase.PLAYING, phase(Player.STATE_READY, frame = true))
        assertEquals(PlaybackPhase.BUFFERING, phase(Player.STATE_BUFFERING, frame = true))
        assertEquals(PlaybackPhase.PLAYING, phase(Player.STATE_READY, tracks = PlaybackTracks.AUDIO_ONLY))
    }

    @Test fun explicitPauseAndFocusSuppressionOverrideNormalPlayback() {
        assertEquals(PlaybackPhase.PAUSED, phase(Player.STATE_BUFFERING, play = false))
        assertEquals(PlaybackPhase.INTERRUPTED, phase(Player.STATE_READY, frame = true, suppressed = true))
        assertEquals(PlaybackPhase.PAUSED, phase(Player.STATE_READY, play = false, suppressed = true))
    }

    @Test fun endAndFailureRemainVisibleWhenPlayerIsNotPlaying() {
        assertEquals(PlaybackPhase.ENDED, phase(Player.STATE_ENDED, frame = true, play = false))
        assertEquals(PlaybackPhase.FAILED, phase(Player.STATE_IDLE, play = false, error = true))
    }

    @Test fun selectedAudioCannotHideUnsupportedVideoAtReadyOrEnd() {
        val tracks = classifyPlaybackTracks(hasVideo = true, videoSelected = false, videoSupported = false, audioSelected = true)
        assertEquals(PlaybackTracks.VIDEO_UNSUPPORTED, tracks)
        assertEquals(PlaybackPhase.WAITING, phase(Player.STATE_BUFFERING, tracks = tracks))
        assertEquals(PlaybackPhase.FAILED, phase(Player.STATE_READY, tracks = tracks))
        assertEquals(PlaybackPhase.FAILED, phase(Player.STATE_ENDED, tracks = tracks))
    }

    @Test fun confirmedAudioOnlyCanPlayWithoutVideoFrame() {
        val tracks = classifyPlaybackTracks(hasVideo = false, videoSelected = false, videoSupported = false, audioSelected = true)
        assertEquals(PlaybackTracks.AUDIO_ONLY, tracks)
        assertEquals(PlaybackPhase.PLAYING, phase(Player.STATE_READY, tracks = tracks))
    }

    @Test fun pendingTrackSelectionCannotPublishAudioOnlyOrReuseAnEarlierVideoFrame() {
        val pending = classifyPlaybackTracks(hasVideo = true, videoSelected = false, videoSupported = true, audioSelected = true)
        assertEquals(PlaybackTracks.PENDING, pending)
        assertEquals(PlaybackPhase.WAITING, phase(Player.STATE_READY, frame = true, tracks = pending))
        assertEquals(PlaybackTracks.PENDING, classifyPlaybackTracks(false, false, false, false))
        val selected = classifyPlaybackTracks(true, true, false, true)
        assertEquals(PlaybackTracks.VIDEO_SELECTED, selected)
        assertEquals(PlaybackPhase.WAITING, phase(Player.STATE_READY, tracks = selected))
        assertEquals(PlaybackPhase.PLAYING, phase(Player.STATE_READY, frame = true, tracks = selected))
    }
}
