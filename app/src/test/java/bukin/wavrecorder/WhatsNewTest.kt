package bukin.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one-time What's new card: shown once after an update, never after a fresh install, and
 * never over a recording or a microphone or notification permission request -- in those cases it
 * waits, unstored, for a later idle visit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WhatsNewTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private lateinit var service: RecordingService

    /** Released after each test: a microphone read that never returns while the test runs. */
    private val readWedge = CountDownLatch(1)

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
    }

    @After
    fun tearDown() {
        readWedge.countDown()
        if (service.state != ServiceState.IDLE) service.stopAndAwaitFinalization()
    }

    /** How Android tells an update from a fresh install: only an update moves lastUpdateTime. */
    private fun installedAs(update: Boolean) {
        val info = shadowOf(app().packageManager).getInternalMutablePackageInfo(app().packageName)
        info.firstInstallTime = 1_000L
        info.lastUpdateTime = if (update) 2_000L else 1_000L
    }

    private fun prefs() = app().getSharedPreferences(WhatsNew.PREFS_NAME, Context.MODE_PRIVATE)
    private fun seenVersion() = prefs().getInt(WhatsNew.KEY_SEEN_VERSION, 0)

    private fun MainActivity.card(): AlertDialog? =
        (supportFragmentManager.findFragmentByTag(WhatsNewDialogFragment.TAG) as DialogFragment?)?.requireDialog() as AlertDialog?

    private fun launch(): ActivityScenario<MainActivity> {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        idle()
        return scenario
    }

    /** The app leaving the screen and coming back: RecordFragment unbinds, then binds again. */
    private fun ActivityScenario<MainActivity>.leaveAndReturn() {
        moveToState(Lifecycle.State.CREATED)
        moveToState(Lifecycle.State.RESUMED)
        idle()
    }

    private fun ActivityScenario<MainActivity>.recordFragment(): RecordFragment {
        var fragment: RecordFragment? = null
        onActivity { activity -> fragment = activity.supportFragmentManager.fragments.filterIsInstance<RecordFragment>().single() }
        return fragment!!
    }

    private fun ActivityScenario<MainActivity>.cardShown(): Boolean {
        var shown = false
        onActivity { activity -> shown = activity.card()?.isShowing == true }
        return shown
    }

    // --- The rules ---

    @Test
    fun `the rules - shown once after an update, never after a fresh install, and only when nothing is going on`() {
        val card = WhatsNew.CARD_VERSION
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(seenVersion = 0, isUpdate = true, busy = false))
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(seenVersion = card - 1, isUpdate = true, busy = false))
        assertEquals(WhatsNew.Decision.LATER, WhatsNew.decide(seenVersion = 0, isUpdate = true, busy = true))
        assertEquals(WhatsNew.Decision.MARK_SEEN, WhatsNew.decide(seenVersion = 0, isUpdate = false, busy = false))
        assertEquals(WhatsNew.Decision.MARK_SEEN, WhatsNew.decide(seenVersion = 0, isUpdate = false, busy = true))
        for (update in listOf(true, false)) for (busy in listOf(true, false)) {
            assertEquals(WhatsNew.Decision.NONE, WhatsNew.decide(seenVersion = card, isUpdate = update, busy = busy))
        }
    }

    @Test
    fun `the card describes 1_2, the release that introduced it`() {
        assertEquals(3, WhatsNew.CARD_VERSION)
        assertEquals("What's new in 1.2", app().getString(R.string.whats_new_title))
    }

    // --- In the app ---

    @Test
    fun `after an update the card appears once, and is remembered`() {
        installedAs(update = true)
        val scenario = launch()
        assertTrue("shown after the update", scenario.cardShown())
        assertEquals(WhatsNew.CARD_VERSION, seenVersion())
        scenario.onActivity { activity ->
            val card = activity.card()!!
            val text = card.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
            assertTrue(text, text.contains("Send feedback"))
            assertTrue(text, text.contains("Share, Save a copy and Rename"))
            card.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        idle()
        assertTrue("closed", !scenario.cardShown())

        scenario.leaveAndReturn()
        assertTrue("not again on the next visit", !scenario.cardShown())
        scenario.close()
        assertTrue("not again on the next launch", !launch().cardShown())
    }

    @Test
    fun `a fresh install never shows the card, and remembers it as seen`() {
        installedAs(update = false)
        val scenario = launch()
        assertTrue(!scenario.cardShown())
        assertEquals(WhatsNew.CARD_VERSION, seenVersion())
        installedAs(update = true) // a later update to the same card doesn't show it either
        scenario.leaveAndReturn()
        assertTrue(!scenario.cardShown())
    }

    @Test
    fun `never over a recording - it waits, unstored, until the app is idle again`() {
        installedAs(update = true)
        service.recorder = blockingRecorder()
        service.startRecording(1L)
        assertTrue("sanity: recording before the app opens", service.isRecording)

        val scenario = launch()
        assertTrue("not over a recording", !scenario.cardShown())
        assertEquals("not stored", 0, seenVersion())
        scenario.leaveAndReturn()
        assertTrue("still recording: still not shown", !scenario.cardShown())

        service.stopAndAwaitFinalization()
        idle()
        assertTrue("not when the recording ends while the app is open", !scenario.cardShown())
        scenario.leaveAndReturn()
        assertTrue("shown on the next idle visit", scenario.cardShown())
        assertEquals(WhatsNew.CARD_VERSION, seenVersion())
    }

    @Test
    fun `never over the microphone permission request`() {
        shadowOf(app()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        installedAs(update = false) // not due yet, so the screen can be used first
        val scenario = launch()
        val fragment = scenario.recordFragment()
        // Mic Test without the microphone permission asks for it.
        fragment.requireView().findViewById<View>(R.id.testMicButton).performClick()
        assertNotNull("the permission request is in flight", shadowOf(fragment.requireActivity()).nextStartedActivityForResult)

        becomeDue()
        scenario.leaveAndReturn()
        assertTrue("not over the permission request", !scenario.cardShown())
        assertEquals("not stored", 0, seenVersion())

        scenario.recordFragment().handlePermissionResult(mapOf(Manifest.permission.RECORD_AUDIO to false))
        scenario.leaveAndReturn()
        assertTrue("shown once the request is answered", scenario.cardShown())
    }

    @Test
    fun `never over the notification permission request`() {
        shadowOf(app()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // An external microphone, so a Record tap goes straight to the permission request.
        shadowOf(app().getSystemService(android.media.AudioManager::class.java)).setInputDevices(
            listOf(
                org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                    .setType(android.media.AudioDeviceInfo.TYPE_USB_DEVICE).build()
            )
        )
        installedAs(update = false)
        val scenario = launch()
        val fragment = scenario.recordFragment()
        fragment.requireView().findViewById<View>(R.id.recordButton).performClick()
        val request = shadowOf(fragment.requireActivity()).nextStartedActivityForResult
        assertNotNull("the permission request is in flight", request)

        becomeDue()
        scenario.leaveAndReturn()
        assertTrue("not over the permission request", !scenario.cardShown())
        assertEquals("not stored", 0, seenVersion())
        assertEquals("nothing started recording", ServiceState.IDLE, service.state)
    }

    @Test
    fun `not stacked on About, and kept, alone, when the screen is recreated`() {
        installedAs(update = false)
        val scenario = launch()
        scenario.onActivity { it.findViewById<View>(R.id.aboutButton).performClick() }
        idle()
        becomeDue()
        scenario.leaveAndReturn()
        assertTrue("not on top of About", !scenario.cardShown())
        assertEquals(0, seenVersion())

        scenario.onActivity { activity ->
            (activity.supportFragmentManager.findFragmentByTag(AboutDialogFragment.TAG) as DialogFragment).dismiss()
        }
        idle()
        scenario.leaveAndReturn()
        assertTrue(scenario.cardShown())
        scenario.recreate()
        idle()
        scenario.onActivity { activity ->
            assertEquals(1, activity.supportFragmentManager.fragments.count { it is WhatsNewDialogFragment })
            assertTrue(activity.card()!!.isShowing)
        }
    }

    @Test
    fun `the card's Send feedback opens the email`() {
        installedAs(update = true)
        val scenario = launch()
        scenario.onActivity { activity ->
            val button = activity.card()!!.getButton(DialogInterface.BUTTON_NEGATIVE)
            assertEquals("Send feedback", button.text.toString())
            button.performClick()
        }
        idle()
        scenario.onActivity { activity ->
            val email = shadowOf(activity).nextStartedActivity
            assertNotNull(email)
            assertEquals(android.content.Intent.ACTION_SENDTO, email.action)
            assertEquals("mailto", email.data?.scheme)
        }
        scenario.onActivity { activity -> assertNull("closed", activity.card()) }
    }

    private fun becomeDue() {
        prefs().edit().remove(WhatsNew.KEY_SEEN_VERSION).commit()
        installedAs(update = true)
    }

    /** A recorder whose microphone read blocks until the test ends or the recording stops -- a
     * real RECORDING state under Robolectric (see RecordFragmentServiceStateGateTest). */
    private fun blockingRecorder(): WavRecorder = WavRecorder(startup = ImmediateStartup,
        openAudioSource = {
            val stopped = CountDownLatch(1)
            val source = object : AudioSource {
                override fun startRecording() {}
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    while (stopped.count > 0 && readWedge.count > 0) stopped.await(50, TimeUnit.MILLISECONDS)
                    return -1
                }
                override fun stop() { stopped.countDown() }
                override fun release() {}
            }
            WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4)
        }
    )
}
