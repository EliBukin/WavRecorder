package bukin.wavrecorder

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

/**
 * Unplugging headphones never leaves playback to start by itself on the speaker (audit finding
 * F5): not while the track is still preparing, and not through a later audio-focus gain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryNoisyOutputTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val uri = Uri.parse("file:///noisy_playback.wav")

    /** A 5-second track whose preparation takes 200 ms. */
    private fun launchWithTrack(): FragmentScenario<LibraryFragment> {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app(), uri), ShadowMediaPlayer.MediaInfo(5000, 200))
        return launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
    }

    private fun LibraryFragment.play() {
        LibraryFragment::class.java.getDeclaredMethod("playNew", RecordingItem::class.java)
            .apply { isAccessible = true }
            .invoke(this, RecordingItem("noisy_playback.wav", uri, 5.0, 100_000))
    }

    private fun LibraryFragment.player(): MediaPlayer =
        LibraryFragment::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }.get(this) as MediaPlayer

    private fun LibraryFragment.focusChange(change: Int) {
        val listener = LibraryFragment::class.java.getDeclaredField("audioFocusListener")
            .apply { isAccessible = true }.get(this) as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(change)
    }

    private fun LibraryFragment.unplug() = requireContext().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

    private fun finishPreparing() {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test
    fun `unplugging while the track prepares keeps it from starting on the speaker`() {
        val scenario = launchWithTrack()
        scenario.onFragment { it.play(); it.unplug() }
        finishPreparing()
        scenario.onFragment { assertFalse(it.player().isPlaying) }
        scenario.close()
    }

    @Test
    fun `unplugging while playing pauses it`() {
        val scenario = launchWithTrack()
        scenario.onFragment { it.play() }
        finishPreparing()
        scenario.onFragment { assertTrue(it.player().isPlaying) }
        scenario.onFragment { it.unplug() }
        shadowOf(Looper.getMainLooper()).idle()
        scenario.onFragment { assertFalse(it.player().isPlaying) }
        scenario.close()
    }

    @Test
    fun `a focus gain after unplugging doesn't resume a track a transient loss had paused`() {
        val scenario = launchWithTrack()
        scenario.onFragment { it.play() }
        finishPreparing()
        scenario.onFragment {
            it.focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) // e.g. a notification sound
            it.unplug()
        }
        shadowOf(Looper.getMainLooper()).idle()
        scenario.onFragment { it.focusChange(AudioManager.AUDIOFOCUS_GAIN) }
        scenario.onFragment { assertFalse("the user restarts it, on whatever output they now use", it.player().isPlaying) }
        scenario.close()
    }

    @Test
    fun `a focus gain after unplugging doesn't start a track that was preparing either`() {
        val scenario = launchWithTrack()
        scenario.onFragment {
            it.play()
            it.focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            it.unplug()
            it.focusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        finishPreparing()
        scenario.onFragment { assertFalse(it.player().isPlaying) }
        scenario.close()
    }

    // ---- The unplug stays in force whatever focus does next (follow-up review R4) ---------------

    private fun LibraryFragment.tapPlayPause() {
        LibraryFragment::class.java.getDeclaredMethod("togglePlayPause", RecordingItem::class.java)
            .apply { isAccessible = true }
            .invoke(this, RecordingItem("noisy_playback.wav", uri, 5.0, 100_000))
    }

    @Test
    fun `unplug, then a transient focus loss and gain while preparing, doesn't start playback`() {
        val scenario = launchWithTrack()
        scenario.onFragment { it.play(); it.unplug() }
        shadowOf(Looper.getMainLooper()).idle() // the unplug is handled before the focus changes
        scenario.onFragment {
            it.focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            it.focusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        finishPreparing()
        scenario.onFragment { assertFalse(it.player().isPlaying) }
        scenario.close()
    }

    @Test
    fun `unplug while preparing, preparation finishing, then a transient loss and gain doesn't start playback`() {
        val scenario = launchWithTrack()
        scenario.onFragment { it.play(); it.unplug() }
        finishPreparing()
        scenario.onFragment {
            it.focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            it.focusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        shadowOf(Looper.getMainLooper()).idle()
        scenario.onFragment { assertFalse(it.player().isPlaying) }
        scenario.close()
    }

    @Test
    fun `a permanent focus loss while preparing isn't undone by a later gain`() {
        val scenario = launchWithTrack()
        scenario.onFragment {
            it.play()
            it.focusChange(AudioManager.AUDIOFOCUS_LOSS)
            it.focusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        finishPreparing()
        scenario.onFragment { assertFalse(it.player().isPlaying) }
        scenario.close()
    }

    @Test
    fun `without an unplug, a transient loss while preparing still ends in playback once focus returns`() {
        val scenario = launchWithTrack()
        scenario.onFragment { it.play(); it.focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
        finishPreparing()
        scenario.onFragment { assertFalse("held back while focus is lost", it.player().isPlaying) }
        scenario.onFragment { it.focusChange(AudioManager.AUDIOFOCUS_GAIN) }
        scenario.onFragment { assertTrue("the user still wants it: resumed", it.player().isPlaying) }
        scenario.close()
    }

    @Test
    fun `after an unplug, only the user's own play starts it again - and then focus behaves normally`() {
        val scenario = launchWithTrack()
        scenario.onFragment { it.play() }
        finishPreparing()
        scenario.onFragment { it.unplug() }
        shadowOf(Looper.getMainLooper()).idle()
        scenario.onFragment { assertFalse(it.player().isPlaying) }

        scenario.onFragment { it.tapPlayPause() } // the user plays it again
        scenario.onFragment { assertTrue(it.player().isPlaying) }
        scenario.onFragment {
            it.focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertFalse(it.player().isPlaying)
            it.focusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertTrue("wanted again, so a transient loss resumes as usual", it.player().isPlaying)
        }
        scenario.close()
    }
}
