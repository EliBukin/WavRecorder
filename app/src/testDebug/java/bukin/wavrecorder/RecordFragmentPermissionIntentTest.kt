package bukin.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.view.View
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowToast

/**
 * A permission result carries out exactly the operation that asked for it (audit finding F2):
 * a microphone test stays a microphone test and a recording stays a recording across the screen
 * being recreated while the system dialog is up; a result nobody is waiting for starts nothing; a
 * result is used once; and nothing starts when the screen can no longer start it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordFragmentPermissionIntentTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private lateinit var service: RecordingService
    private val startupWorker = ManualExecutor()

    @Before
    fun setUp() {
        shadowOf(app()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        service = Robolectric.buildService(RecordingService::class.java).create().get()
        // Nothing native is ever opened: the startup worker only runs when a test says so.
        service.recorder = WavRecorder(startup = AudioStartup(startupWorker, ManualExecutor()))
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java), service.LocalBinder()
        )
        // An external input, so a Record tap goes straight to the permission request.
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
    }

    @After
    fun tearDown() {
        service.recorder.requestStop()
    }

    private fun launch(): FragmentScenario<RecordFragment> =
        launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder).also {
            shadowOf(Looper.getMainLooper()).idle()
            drainStartedServices()
        }

    private fun drainStartedServices() {
        while (shadowOf(app()).nextStartedService != null) Unit
    }

    private fun FragmentScenario<RecordFragment>.tap(id: Int) = onFragment { it.requireView().findViewById<View>(id).performClick() }

    /** A microphone test that opens nothing: its startup worker never runs. */
    private fun FragmentScenario<RecordFragment>.withInertMicTest(): MicTestSession {
        val session = MicTestSession(startup = AudioStartup(ManualExecutor(), ManualExecutor()))
        onFragment { it.micTestSession = session }
        return session
    }

    private fun FragmentScenario<RecordFragment>.grant() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        onFragment { it.handlePermissionResult(mapOf(Manifest.permission.RECORD_AUDIO to true)) }
    }

    @Test
    fun `a microphone test's permission request stays a microphone test after recreation`() {
        val scenario = launch()
        scenario.tap(R.id.testMicButton)
        scenario.recreate()
        shadowOf(Looper.getMainLooper()).idle() // the new instance reconnects to the service
        drainStartedServices()
        val test = scenario.withInertMicTest()

        scenario.grant()

        assertNull("the grant never became a recording", shadowOf(app()).nextStartedService)
        assertFalse(service.isStarting)
        assertTrue("the microphone test the user asked for starts", test.starting)
        scenario.close()
    }

    @Test
    fun `a recording's permission request stays a recording after recreation`() {
        val scenario = launch()
        scenario.tap(R.id.recordButton)
        scenario.recreate()
        shadowOf(Looper.getMainLooper()).idle() // the new instance reconnects to the service
        drainStartedServices()
        val test = scenario.withInertMicTest()

        scenario.grant()

        assertEquals(RecordingService.ACTION_START, shadowOf(app()).nextStartedService?.action)
        assertFalse("no microphone test", test.starting)
        scenario.close()
    }

    @Test
    fun `a result nobody asked for starts nothing`() {
        val scenario = launch()
        val test = scenario.withInertMicTest()

        scenario.grant()

        assertNull(shadowOf(app()).nextStartedService)
        assertFalse(test.starting)
        scenario.close()
    }

    @Test
    fun `a result is used once - a repeated one starts nothing more`() {
        val scenario = launch()
        val test = scenario.withInertMicTest()
        scenario.tap(R.id.testMicButton)
        scenario.grant()
        assertTrue(test.starting)
        test.stop()

        scenario.onFragment { it.handlePermissionResult(mapOf(Manifest.permission.RECORD_AUDIO to true)) }
        assertFalse("not started a second time", test.starting)
        assertNull(shadowOf(app()).nextStartedService)
        scenario.close()
    }

    @Test
    fun `a denied microphone test explains itself and starts nothing`() {
        val scenario = launch()
        val test = scenario.withInertMicTest()
        scenario.tap(R.id.testMicButton)

        scenario.onFragment { it.handlePermissionResult(mapOf(Manifest.permission.RECORD_AUDIO to false)) }

        assertEquals(app().getString(R.string.mic_test_permission_denied), ShadowToast.getTextOfLatestToast())
        assertFalse(test.starting)
        assertNull(shadowOf(app()).nextStartedService)
        scenario.close()
    }

    @Test
    fun `a recording result that arrives while a recording is already starting starts nothing new`() {
        val scenario = launch()
        scenario.tap(R.id.recordButton)
        // Meanwhile a recording was started some other way (the notification, another screen).
        service.startRecording(99L)
        assertTrue(service.isStarting)
        drainStartedServices()

        scenario.grant()

        assertNull("no second start", shadowOf(app()).nextStartedService)
        assertEquals(99L, service.currentRequestId)
        scenario.close()
    }
}
