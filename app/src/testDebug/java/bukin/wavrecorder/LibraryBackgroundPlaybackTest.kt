package bukin.wavrecorder

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.util.DataSource
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Library playback stops when the app leaves the foreground (Home, another app, the screen turning
 * off -- [AppForeground]'s ON_STOP): the player, its audio focus and progress updates are released,
 * the list shows the track stopped, nothing resumes on return, and late player callbacks can't
 * restart it. Switching to the Record page isn't leaving the app, and recording itself carries on.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryBackgroundPlaybackTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Stands in for ProcessLifecycleOwner: the app starts in the foreground. */
    private class FakeApp : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        fun leave() { registry.currentState = Lifecycle.State.CREATED }
        fun returnToForeground() { registry.currentState = Lifecycle.State.RESUMED }
    }

    private val fakeApp = FakeApp()
    private lateinit var realAppLifecycle: () -> Lifecycle
    private val players = mutableListOf<MediaPlayer>()

    @Before
    fun setUp() {
        realAppLifecycle = AppForeground.lifecycleProvider
        AppForeground.lifecycleProvider = { fakeApp.lifecycle }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
    }

    @After
    fun tearDown() {
        AppForeground.lifecycleProvider = realAppLifecycle
    }

    private fun recordingItem(name: String) = RecordingItem(
        name = name,
        uri = Uri.parse("file:///$name"),
        durationSeconds = 5.0,
        sizeBytes = 100_000
    )

    /** Lets setDataSource()/prepareAsync() on [uri] succeed; the player stays Preparing for
     * [preparationDelayMs], which leaves room to leave the app mid-preparation. */
    private fun registerMedia(uri: Uri, preparationDelayMs: Int = 200) {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(app(), uri),
            ShadowMediaPlayer.MediaInfo(5000, preparationDelayMs)
        )
    }

    private fun launchWithItems(items: List<RecordingItem>): FragmentScenario<LibraryFragment> {
        val scenario = isolation.closeAfter(launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder))
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(app()) {
                override fun listRecordings(): List<RecordingItem> = items
            }
            fragment.refreshList()
        }
        val deadline = System.currentTimeMillis() + 2000
        var itemCount = -1
        while (itemCount != items.size && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            idle()
            scenario.onFragment { itemCount = it.requireView().findViewById<RecyclerView>(R.id.recordingsList).adapter?.itemCount ?: -1 }
        }
        return scenario
    }

    /** A freshly bound row: what the list currently shows for [position]. */
    private fun row(scenario: FragmentScenario<LibraryFragment>, position: Int = 0): RecordingsAdapter.ViewHolder {
        var holder: RecordingsAdapter.ViewHolder? = null
        scenario.onFragment { fragment ->
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList)
            val adapter = list.adapter as RecordingsAdapter
            holder = adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, position) }
        }
        return holder!!
    }

    private fun play(scenario: FragmentScenario<LibraryFragment>) = row(scenario).binding.playButton.performClick()

    private fun audioManager() = app().getSystemService(AudioManager::class.java)

    /** The row shows the track as stopped: not active, not preparing, offering Play. */
    private fun assertRowStopped(scenario: FragmentScenario<LibraryFragment>) {
        val row = row(scenario)
        assertEquals(View.INVISIBLE, row.binding.activeAccent.visibility)
        assertEquals(View.GONE, row.binding.preparingIndicator.visibility)
        assertEquals(View.VISIBLE, row.binding.playButton.visibility)
        assertEquals(app().getString(R.string.play_button_description), row.binding.playButton.contentDescription)
        assertNull("no spoken playback state", ViewCompat.getStateDescription(row.binding.root))
    }

    private fun assertReleased(scenario: FragmentScenario<LibraryFragment>, player: MediaPlayer) {
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        scenario.onFragment { assertNull("no active track", it.activeUri) }
        val focus = shadowOf(audioManager())
        assertEquals("audio focus given up", focus.lastAudioFocusRequest.audioFocusRequest, focus.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `leaving the app while a track plays stops it, and returning shows it stopped without resuming`() {
        val item = recordingItem("track.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))
        play(scenario)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val player = players.single()
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(player).state)

        fakeApp.leave()
        idle()
        assertReleased(scenario, player)
        assertRowStopped(scenario)

        fakeApp.returnToForeground()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals("no new player on return", 1, players.size)
        scenario.onFragment { assertNull(it.activeUri) }
        assertRowStopped(scenario)
    }

    @Test
    fun `leaving the app while a track is still preparing releases it, and its late prepared callback starts nothing`() {
        val item = recordingItem("track.wav")
        registerMedia(item.uri, preparationDelayMs = 200)
        val scenario = launchWithItems(listOf(item))
        play(scenario)
        val player = players.single()
        assertEquals(ShadowMediaPlayer.State.PREPARING, shadowOf(player).state)

        fakeApp.leave()
        idle()
        assertReleased(scenario, player)

        // Whatever the released player's preparation still delivers -- on schedule, or forced
        // straight through its listeners -- must neither start it nor touch the list.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals("the scheduled preparation did nothing to the released player", ShadowMediaPlayer.State.END, shadowOf(player).state)
        // Forcing the callback through makes the shadow itself report PREPARED (a real released
        // player can't), so what matters is that the app never starts it or touches the list.
        shadowOf(player).invokePreparedListener()
        idle()
        assertFalse("the released player was never started", shadowOf(player).state == ShadowMediaPlayer.State.STARTED)
        assertEquals(1, players.size)
        scenario.onFragment { assertNull(it.activeUri) }
        assertRowStopped(scenario)
    }

    @Test
    fun `late completion and error callbacks from the released player are ignored`() {
        val item = recordingItem("track.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))
        play(scenario)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val player = players.single()

        fakeApp.leave()
        idle()
        assertReleased(scenario, player)
        ShadowToast.reset()
        shadowOf(player).invokeCompletionListener()
        shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        idle()

        assertNull("no error toast for a player that is already gone", ShadowToast.getLatestToast())
        scenario.onFragment { assertNull(it.activeUri) }
        assertRowStopped(scenario)
    }

    @Test
    fun `a new track can be played after returning, and leaving again stops that one too`() {
        val item = recordingItem("track.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))
        play(scenario)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        fakeApp.leave()
        idle()
        fakeApp.returnToForeground()
        idle()

        play(scenario)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val second = players.last()
        assertEquals(2, players.size)
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(second).state)

        fakeApp.leave()
        idle()
        assertReleased(scenario, second)
    }

    @Test
    fun `switching to the Record page is not leaving the app - playback continues`() {
        val item = recordingItem("track.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))
        play(scenario)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val player = players.single()

        // ViewPager2 caps the page that isn't shown at STARTED; the app itself stays in the foreground.
        scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(player).state)
        scenario.moveToState(Lifecycle.State.RESUMED)
        idle()
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(player).state)
        scenario.onFragment { assertEquals(item.uri, it.activeUri) }
    }

    @Test
    fun `a configuration change ends playback as before, and only the new view keeps observing the app`() {
        val item = recordingItem("track.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))
        assertEquals(1, fakeApp.registry.observerCount)
        play(scenario)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val player = players.single()

        scenario.recreate()
        idle()

        // The view (and the player with it) is destroyed and recreated; nothing restarts on its own.
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertEquals(1, players.size)
        assertEquals("the old view stopped observing, the new one started", 1, fakeApp.registry.observerCount)
        scenario.onFragment { assertNull(it.activeUri) }

        scenario.moveToState(Lifecycle.State.DESTROYED)
        assertEquals("nothing left observing once the view is gone", 0, fakeApp.registry.observerCount)
    }

    @Test
    fun `leaving the app stops playback but not a recording in progress`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val release = CountDownLatch(1)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (release.await(20, TimeUnit.MILLISECONDS)) return -1
                java.util.Arrays.fill(buffer, offset, offset + 4, 0)
                return 4
            }
            override fun stop() { release.countDown() }
            override fun release() {}
        }
        val segment = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(app()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segment)
        }
        service.recorder = WavRecorder(
            startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4) }
        )
        service.startRecording(1L)
        idle()
        assertTrue(service.isRecording)

        val item = recordingItem("track.wav")
        registerMedia(item.uri)
        val scenario = launchWithItems(listOf(item))
        play(scenario)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        fakeApp.leave()
        idle()
        Thread.sleep(100)
        idle()

        assertReleased(scenario, players.single())
        assertTrue("recording continues in the background", service.isRecording)
        assertFalse("no stop was requested", service.isFinalizing)

        service.stopAndAwaitFinalization()
    }
}
