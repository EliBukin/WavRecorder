package bukin.wavrecorder

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Guards the owner-approved ID, generated resource namespace and manifest components. */
@RunWith(RobolectricTestRunner::class)
class ApplicationIdentityTest {
    @Test
    fun `application resources and manifest use the approved identity`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("bukin.wavrecorder", app.packageName)
        assertEquals("bukin.wavrecorder", R::class.java.`package`!!.name)
        val activity = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        val service = app.packageManager.getServiceInfo(ComponentName(app, RecordingService::class.java), 0)
        assertEquals("bukin.wavrecorder.MainActivity", activity.name)
        assertEquals("bukin.wavrecorder.RecordingService", service.name)
    }
}
