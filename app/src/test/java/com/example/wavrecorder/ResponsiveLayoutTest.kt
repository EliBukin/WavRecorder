package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real-text layout checks (native graphics, so text is measured with actual fonts) for the
 * redesigned screens at narrow widths and large font scales: key labels are never ellipsized or
 * clipped, nothing runs off the side of the screen, main controls keep 48dp touch targets, and
 * nothing sits behind the bottom navigation. The class default is the harshest case -- 320dp wide
 * at font scale 1.3; individual tests add 360dp and the Samsung A20's own configuration.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 1.3f)
class ResponsiveLayoutTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private lateinit var service: RecordingService
    private val release = CountDownLatch(1)

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
        service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = {
            WavRecorder.RecorderConfig(object : AudioSource {
                override fun startRecording() {}
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    release.await(5, TimeUnit.SECONDS); return -1
                }
                override fun stop() { release.countDown() }
                override fun release() {}
                override fun describeMicrophone() = MicrophoneInfo("Insta360 Mic Air", isExternal = true, verified = true)
            }, 48000, 4)
        })
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java), service.LocalBinder()
        )
    }

    @After
    fun tearDown() {
        release.countDown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun dp(v: View, value: Int) = (value * v.resources.displayMetrics.density).toInt()

    private fun TextView.assertFullyShown() {
        val layout = layout
        assertTrue("$name has no layout", layout != null)
        for (line in 0 until layout.lineCount) {
            assertTrue("$name is ellipsized: \"$text\"", layout.getEllipsisCount(line) == 0)
        }
        val available = height - compoundPaddingTop - compoundPaddingBottom
        assertTrue("$name is clipped vertically (${layout.height}px text in ${available}px)", layout.height <= available + 1)
    }

    private val View.name get() = try { resources.getResourceEntryName(id) } catch (_: Exception) { javaClass.simpleName }

    private fun View.assertOnScreenHorizontally(screenWidth: Int) {
        val location = IntArray(2).also { getLocationInWindow(it) }
        assertTrue("$name starts off screen", location[0] >= 0)
        assertTrue("$name runs off the right edge", location[0] + width <= screenWidth)
    }

    private fun View.assertTouchTarget(minDp: Int = 48) {
        assertTrue("$name is only ${height}px tall", height >= dp(this, minDp) - 1)
        assertTrue("$name is only ${width}px wide", width >= dp(this, minDp) - 1)
    }

    private fun checkRecordScreen(activity: MainActivity) {
        val root = activity.window.decorView
        val screenWidth = root.width
        val nav = activity.findViewById<View>(R.id.bottomNavigation)
        val record = activity.findViewById<TextView>(R.id.recordButton)
        val navTop = IntArray(2).also { nav.getLocationInWindow(it) }[1]
        val recordBottom = IntArray(2).also { record.getLocationInWindow(it) }[1] + record.height
        assertTrue("the primary action must sit above the bottom navigation", recordBottom <= navTop)
        assertTrue("bottom navigation must be on screen", navTop + nav.height <= root.height)

        record.assertFullyShown()
        record.assertTouchTarget(56)
        record.assertOnScreenHorizontally(screenWidth)
        listOf(R.id.statusText, R.id.micStatusTitle, R.id.micStatusSubtitle, R.id.micStatusBadge,
            R.id.split30Button, R.id.split45Button, R.id.split60Button, R.id.chooseFolderButton).forEach { id ->
            val view = activity.findViewById<TextView>(id)
            if (view.visibility == View.VISIBLE && view.isShown) {
                view.assertFullyShown()
                view.assertOnScreenHorizontally(screenWidth)
            }
        }
        listOf(R.id.split30Button, R.id.split45Button, R.id.split60Button, R.id.chooseFolderButton).forEach {
            activity.findViewById<View>(it).assertTouchTarget()
        }
        activity.findViewById<View>(R.id.nav_record).assertTouchTarget()
        activity.findViewById<View>(R.id.nav_library).assertTouchTarget()
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
        return scenario
    }

    private fun startRecording(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.recordButton).performClick() }
        val deadline = System.currentTimeMillis() + 3000
        var recording = false
        while (!recording && System.currentTimeMillis() < deadline) {
            Thread.sleep(5); idle()
            scenario.onActivity { a ->
                recording = a.supportFragmentManager.fragments.filterIsInstance<RecordFragment>().first()
                    .panelState == RecordFragment.PanelState.RECORDING
            }
        }
        assertTrue(recording)
    }

    private fun idleRecordScreen() {
        val scenario = launch()
        scenario.onActivity { checkRecordScreen(it) }
        scenario.onActivity { a ->
            a.findViewById<View>(R.id.testMicButton).assertTouchTarget()
            a.findViewById<TextView>(R.id.testMicButton).assertFullyShown()
        }
    }

    private fun recordingScreen() {
        val scenario = launch()
        startRecording(scenario)
        scenario.onActivity { a ->
            checkRecordScreen(a)
            listOf(R.id.elapsedText, R.id.sessionDetailsText, R.id.audioFormatText).forEach {
                a.findViewById<TextView>(it).assertFullyShown()
            }
        }
        service.stopRecording()
    }

    private fun libraryScreen(selection: Boolean) {
        val scenario = launch()
        val items = listOf(
            RecordingItem("recording_20260924_143005_part02.wav", Uri.parse("file:///a.wav"), 3725.0, 357_600_000),
            RecordingItem("recording_20260923_101500_part01.wav", Uri.parse("file:///b.wav"), 48.0, 768_000),
            RecordingItem("an_imported_file_with_a_long_descriptive_name.wav", Uri.parse("file:///c.wav"), 30.0, 480_000)
        )
        scenario.onActivity { a ->
            a.findViewById<View>(R.id.nav_library).performClick()
            idle()
            val library = a.supportFragmentManager.fragments.filterIsInstance<LibraryFragment>().first()
            library.destinationManager = object : DestinationManager(app()) {
                override fun listRecordings() = items
            }
            library.refreshList()
        }
        val deadline = System.currentTimeMillis() + 3000
        var shown = 0
        while (shown < items.size && System.currentTimeMillis() < deadline) {
            Thread.sleep(5); idle()
            scenario.onActivity { shown = it.findViewById<RecyclerView>(R.id.recordingsList).childCount }
        }
        if (selection) {
            scenario.onActivity { a ->
                a.supportFragmentManager.fragments.filterIsInstance<LibraryFragment>().first().enterSelectionMode(items[0])
            }
            idle()
        }
        scenario.onActivity { a ->
            val list = a.findViewById<RecyclerView>(R.id.recordingsList)
            val screenWidth = a.window.decorView.width
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i)
                row.findViewById<TextView>(R.id.fileMeta).assertFullyShown()
                row.findViewById<View>(R.id.rowCard).assertOnScreenHorizontally(screenWidth)
                val header = row.findViewById<TextView>(R.id.dateHeader)
                if (header.visibility == View.VISIBLE) header.assertFullyShown()
                // Friendly titles (the app's own names) must never be cut; an arbitrary long imported
                // file name may ellipsize after its two lines, by design.
                val title = row.findViewById<TextView>(R.id.fileName)
                if (title.text.startsWith("Sep")) title.assertFullyShown()
                if (!selection) {
                    row.findViewById<View>(R.id.playButton).assertTouchTarget()
                    row.findViewById<View>(R.id.overflowButton).assertTouchTarget()
                }
            }
            if (selection) {
                a.findViewById<TextView>(R.id.selectionCountText).assertFullyShown()
                a.findViewById<TextView>(R.id.selectionDeleteButton).assertFullyShown()
                a.findViewById<View>(R.id.selectionDeleteButton).assertTouchTarget()
                a.findViewById<View>(R.id.selectionCancelButton).assertTouchTarget()
            } else {
                a.findViewById<TextView>(R.id.libraryTitle).assertFullyShown()
                a.findViewById<TextView>(R.id.selectButton).assertFullyShown()
                a.findViewById<View>(R.id.selectButton).assertTouchTarget()
            }
        }
    }

    @Test fun `record screen fits 320dp at font scale 1_3`() = idleRecordScreen()
    @Test fun `recording screen fits 320dp at font scale 1_3`() = recordingScreen()
    @Test fun `library fits 320dp at font scale 1_3`() = libraryScreen(selection = false)
    @Test fun `library selection fits 320dp at font scale 1_3`() = libraryScreen(selection = true)

    @Test @Config(qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 1.0f)
    fun `record screen fits 360dp`() = idleRecordScreen()

    @Test @Config(qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 1.3f)
    fun `recording screen fits 360dp at font scale 1_3`() = recordingScreen()

    // Samsung Galaxy A20 (SM-A205G): 720 x 1560 px at 280 dpi = 411 x 891 dp, font scale 1.1.
    @Test @Config(qualifiers = "w411dp-h891dp-280dpi", fontScale = 1.1f)
    fun `record screen fits the A20`() = idleRecordScreen()

    @Test @Config(qualifiers = "w411dp-h891dp-280dpi", fontScale = 1.1f)
    fun `recording screen fits the A20`() = recordingScreen()

    @Test @Config(qualifiers = "w411dp-h891dp-night-280dpi", fontScale = 1.1f)
    fun `library fits the A20 in dark mode`() = libraryScreen(selection = false)
}
