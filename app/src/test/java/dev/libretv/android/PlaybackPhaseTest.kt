package dev.libretv.android

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPhaseTest {
    private fun phase(
        state: Int, frame: Boolean = false, play: Boolean = true,
        error: Boolean = false, suppressed: Boolean = false, audioOnly: Boolean = false,
    ) = playbackPhase(error, state, play, suppressed, frame, audioOnly)

    @Test fun firstFrameAndRebufferingHaveSeparateIndicators() {
        assertEquals(PlaybackPhase.WAITING, phase(Player.STATE_BUFFERING))
        assertEquals(PlaybackPhase.WAITING, phase(Player.STATE_READY))
        assertEquals(PlaybackPhase.PLAYING, phase(Player.STATE_READY, frame = true))
        assertEquals(PlaybackPhase.BUFFERING, phase(Player.STATE_BUFFERING, frame = true))
        assertEquals(PlaybackPhase.PLAYING, phase(Player.STATE_READY, audioOnly = true))
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
}
