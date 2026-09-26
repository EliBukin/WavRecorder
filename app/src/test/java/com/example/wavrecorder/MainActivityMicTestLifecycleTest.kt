package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the real [MainActivity]/`ViewPager2`/`FragmentStateAdapter` lifecycle: with only 2 pages,
 * switching from the Record tab to the Library tab leaves the off-screen `RecordFragment` in the
 * STARTED state (it receives onPause(), never onStop()) rather than being torn down. Without
 * RecordFragment.onPause() explicitly stopping an active microphone test, its AudioRecord and
 * background thread would keep running invisibly. This drives the actual ViewPager2 tab switch
 * (not just a direct Fragment lifecycle transition) so the fix is proven against the real
 * mechanism that exposed the bug, not just a plausible-looking substitute for it.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityMicTestLifecycleTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // RecordFragment always binds in onStart() regardless of what a given test actually needs,
        // and an unregistered bindService() call still delivers onServiceConnected with a null
        // binder in Robolectric, crashing the fragment's own cast -- mirrors every other
        // RecordFragment test file's identical setup. An idle service by default; the real-recording
        // test below registers its own busy one instead, before launching.
        val idleService = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            idleService.LocalBinder()
        )
    }

    private fun recordFragmentOf(activity: MainActivity): RecordFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<RecordFragment>().first()

    private fun viewPagerOf(activity: MainActivity): ViewPager2 = activity.findViewById(R.id.viewPager)

    private fun blockingFake(released: () -> Unit = {}): AudioSource = object : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
        override fun stop() { released() }
        override fun release() {}
    }

    @Test
    fun `switching from Record to Library stops an active mic test and releases its source exactly once, then Record shows a clean idle state`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var releaseCount = 0
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
            override fun stop() {}
            override fun release() { releaseCount++ }
        }
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })

        scenario.onActivity { activity ->
            val fragment = recordFragmentOf(activity)
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertTrue("sanity: the test must genuinely be active before switching tabs", session.active)

        // The actual ViewPager2 tab switch -- not a direct Fragment lifecycle call.
        scenario.onActivity { activity -> viewPagerOf(activity).currentItem = 1 }
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onActivity { activity ->
            assertEquals(
                "the off-screen Record page must stay STARTED (not stopped/destroyed) with only 2 pager pages",
                Lifecycle.State.STARTED, recordFragmentOf(activity).lifecycle.currentState
            )
        }
        assertFalse("leaving the Record tab must stop the active test", session.active)
        assertEquals("the test's AudioSource must be released exactly once", 1, releaseCount)

        // Switching back must show a clean idle state, not a leftover "Stop test"/disabled button.
        scenario.onActivity { activity -> viewPagerOf(activity).currentItem = 0 }
        shadowOf(Looper.getMainLooper()).idle()

        var buttonText: String? = null
        var testButtonEnabled = false
        var recordButtonEnabled = false
        scenario.onActivity { activity ->
            val fragment = recordFragmentOf(activity)
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
            testButtonEnabled = fragment.view!!.findViewById<Button>(R.id.testMicButton).isEnabled
            recordButtonEnabled = fragment.view!!.findViewById<android.view.View>(R.id.recordButton).isEnabled
        }
        assertEquals(
            "returning to the Record tab must show the idle 'Test microphone' label, not a stale 'Stop test'",
            app().getString(R.string.test_microphone), buttonText
        )
        assertTrue("mic testing must be usable again after returning to the Record tab", testButtonEnabled)
        assertTrue("the record button must not be left stuck disabled from the earlier test", recordButtonEnabled)
    }

    @Test
    fun `switching tabs away from an active mic test does not stop a real foreground recording`() {
        val boundService = Robolectric.buildService(RecordingService::class.java).create().get()
        val blockForever = CountDownLatch(1)
        boundService.recorder = WavRecorder(startup = ImmediateStartup,
            openAudioSource = {
                val source = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        blockForever.await(5, TimeUnit.SECONDS)
                        return -1
                    }
                    override fun stop() { blockForever.countDown() }
                    override fun release() {}
                }
                WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4)
            }
        )
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            boundService.LocalBinder()
        )
        boundService.startRecording(1L)
        assertTrue("sanity: the service must genuinely be recording before switching tabs", boundService.isRecording)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "sanity: the Record page must have picked up the already-active recording on connecting",
            boundService.isRecording
        )

        scenario.onActivity { activity -> viewPagerOf(activity).currentItem = 1 }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "switching to the Library tab must never stop a real foreground recording",
            boundService.isRecording
        )

        scenario.onActivity { activity -> viewPagerOf(activity).currentItem = 0 }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "switching back to the Record tab must still leave the real recording running",
            boundService.isRecording
        )
        boundService.stopRecording()
    }
}
