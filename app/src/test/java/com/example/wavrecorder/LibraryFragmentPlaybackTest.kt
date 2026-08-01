package com.example.wavrecorder

import android.app.Application
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

/**
 * Covers [LibraryFragment]'s asynchronous MediaPlayer state handling: audio-focus callbacks and
 * ACTION_AUDIO_BECOMING_NOISY can land while a player is still in prepareAsync()'s Preparing
 * state, where Android documents most control calls as undefined, and a prepared/error/completion
 * callback can arrive for a player that's since been replaced (switching tracks) or released (the
 * view destroyed) -- these must never touch a stale player, a live one they no longer belong to,
 * or a detached Fragment.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFragmentPlaybackTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun recordingItem(name: String): RecordingItem = RecordingItem(
        name = name,
        uri = Uri.parse("file:///$name"),
        durationSeconds = 5.0,
        sizeBytes = 100_000
    )

    /** Registers [uri] with Robolectric's MediaPlayer shadow so setDataSource()/prepareAsync()
     * against it succeeds without needing a real audio file. [preparationDelayMs] controls how
     * long the player stays in the Preparing state after prepareAsync() -- a real gap (not 0) is
     * what gives a test room to inject a focus/lifecycle event *during* preparation before
     * advancing the looper to let onPrepared actually fire. */
    private fun registerMedia(uri: Uri, durationMs: Int = 5000, preparationDelayMs: Int = 200) {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(app(), uri),
            ShadowMediaPlayer.MediaInfo(durationMs, preparationDelayMs)
        )
    }

    private fun launchWithItems(items: List<RecordingItem>): FragmentScenario<LibraryFragment> {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(app()) {
                override fun listRecordings(): List<RecordingItem> = items
            }
        }
        scenario.onFragment { fragment -> fragment.refreshList() }
        val deadline = System.currentTimeMillis() + 2000
        var itemCount = -1
        while (itemCount != items.size && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onFragment { fragment ->
                val recyclerView = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList)
                itemCount = recyclerView.adapter?.itemCount ?: -1
            }
        }
        return scenario
    }

    /** A fresh ViewHolder bound to [position] -- fine to recreate per interaction since state is
     * always re-read from the fragment/adapter at click time, not held on the holder itself. */
    private fun holderFor(scenario: FragmentScenario<LibraryFragment>, position: Int): RecordingsAdapter.ViewHolder {
        var holder: RecordingsAdapter.ViewHolder? = null
        scenario.onFragment { fragment ->
            val recyclerView = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList)
            val adapter = recyclerView.adapter as RecordingsAdapter
            val h = adapter.onCreateViewHolder(recyclerView, 0)
            adapter.onBindViewHolder(h, position)
            holder = h
        }
        return requireNotNull(holder)
    }

    private fun focusListener(): AudioManager.OnAudioFocusChangeListener {
        val audioManager = app().getSystemService(AudioManager::class.java)
        return requireNotNull(shadowOf(audioManager).lastAudioFocusRequest?.listener) {
            "expected a focus request to have been made"
        }
    }

    @Test
    fun `permanent focus loss during preparation suppresses auto-start`() {
        val item = recordingItem("track1.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))

        holderFor(scenario, 0).binding.playButton.performClick() // playNew(): now Preparing

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        // Let the previously-scheduled onPrepared event actually fire now that focus is gone.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        var isPlaying = true
        scenario.onFragment {
            val holder = holderFor(scenario, 0)
            isPlaying = holder.binding.playButton.contentDescription ==
                app().getString(R.string.pause_button_description)
        }
        assertFalse("a permanent focus loss during preparation must cancel the auto-start, not " +
            "play into a focus state that's already gone", isPlaying)
    }

    @Test
    fun `transient focus loss during preparation does not crash and auto-resumes once focus returns`() {
        val item = recordingItem("track1.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))

        holderFor(scenario, 0).binding.playButton.performClick() // playNew(): now Preparing

        val listener = focusListener()
        // Both must be safe to call while still Preparing -- Android documents isPlaying()/pause()
        // as undefined in that state, which is exactly the bug this covers.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)) // let onPrepared fire

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idle()

        var isPlaying = false
        scenario.onFragment {
            val holder = holderFor(scenario, 0)
            isPlaying = holder.binding.playButton.contentDescription ==
                app().getString(R.string.pause_button_description)
        }
        assertTrue("expected playback to resume once focus actually returned", isPlaying)
    }

    @Test
    fun `ACTION_AUDIO_BECOMING_NOISY during preparation does not crash`() {
        val item = recordingItem("track1.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))

        holderFor(scenario, 0).binding.playButton.performClick() // playNew(): now Preparing

        scenario.onFragment { fragment ->
            fragment.requireContext().sendBroadcast(
                android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
        }
        shadowOf(Looper.getMainLooper()).idle()

        // Reaching this point without an IllegalStateException from touching isPlaying()/pause()
        // during Preparing is the assertion; let the scheduled prepare complete normally too.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test
    fun `switching tracks before preparation completes only starts the newly selected track`() {
        val first = recordingItem("track1.wav")
        val second = recordingItem("track2.wav")
        registerMedia(first.uri)
        registerMedia(second.uri, preparationDelayMs = 0)
        val scenario = launchWithItems(listOf(first, second))

        holderFor(scenario, 0).binding.playButton.performClick() // playNew(first): now Preparing
        holderFor(scenario, 1).binding.playButton.performClick() // switches to second immediately

        // Let both players' scheduled events run: a stale onPrepared for the released first
        // player must not be able to touch the second (or start playing the first).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        var firstDescription: CharSequence? = null
        var secondDescription: CharSequence? = null
        scenario.onFragment {
            firstDescription = holderFor(scenario, 0).binding.playButton.contentDescription
            secondDescription = holderFor(scenario, 1).binding.playButton.contentDescription
        }
        assertTrue(
            "expected the second (newly selected) track to end up playing",
            secondDescription == app().getString(R.string.pause_button_description)
        )
        assertTrue(
            "the first, released track must show as not playing -- a stale callback for it " +
                "must never mark it (or anything else) as active",
            firstDescription != app().getString(R.string.pause_button_description)
        )
    }

    @Test
    fun `destroying the view while preparation is pending does not crash when the stale callback later fires`() {
        val item = recordingItem("track1.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))

        holderFor(scenario, 0).binding.playButton.performClick() // playNew(): now Preparing

        scenario.moveToState(Lifecycle.State.DESTROYED)

        // If the stale onPrepared callback isn't guarded against a released/replaced player and a
        // torn-down view, this throws (touching a released MediaPlayer, or requireContext()/the
        // adapter after detachment). Reaching the end of the test without an exception is itself
        // the assertion.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }
}
