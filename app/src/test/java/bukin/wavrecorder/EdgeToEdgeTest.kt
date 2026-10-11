package bukin.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * MainActivity draws edge-to-edge (enforced from Android 15, done on every version here) and keeps
 * its content clear of the system bars and cutouts by applying each inset exactly once: the
 * header takes the top, the bottom navigation the bottom, and all of it is kept clear of the
 * sides. The insets are consumed there, so the bottom navigation (which would pad itself) and the
 * pages never apply them a second time.
 */
@RunWith(RobolectricTestRunner::class)
class EdgeToEdgeTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // RecordFragment binds in onStart(); give it an idle service to bind to.
        val idleService = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            idleService.LocalBinder()
        )
    }

    private class Layout(activity: MainActivity) {
        val root: View = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val header: View = activity.findViewById(R.id.appHeader)
        val pager: View = activity.findViewById(R.id.viewPager)
        val navigation: View = activity.findViewById(R.id.bottomNavigation)
    }

    private data class Applied(
        val headerTop: Int, val headerLeft: Int, val headerRight: Int,
        val pagerLeft: Int, val pagerRight: Int,
        val navLeft: Int, val navRight: Int, val navBottom: Int
    )

    private fun Layout.applied(): Applied {
        val headerMargins = header.layoutParams as ViewGroup.MarginLayoutParams
        val pagerMargins = pager.layoutParams as ViewGroup.MarginLayoutParams
        return Applied(
            header.paddingTop, headerMargins.leftMargin, headerMargins.rightMargin,
            pagerMargins.leftMargin, pagerMargins.rightMargin,
            navigation.paddingLeft, navigation.paddingRight, navigation.paddingBottom
        )
    }

    private fun Layout.dispatch(systemBars: Insets, cutout: Insets = Insets.NONE) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), systemBars)
            .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    @Test
    fun `system bar and cutout insets are applied once, to the header, the sides and the bottom navigation`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            val layout = Layout(activity)
            layout.dispatch(Insets.NONE)
            val base = layout.applied()

            // A landscape phone: status bar on top, 3-button navigation bar on the right, a
            // display cutout on the left, a gesture area at the bottom.
            layout.dispatch(systemBars = Insets.of(0, 60, 40, 120), cutout = Insets.of(90, 0, 0, 0))
            val applied = layout.applied()

            assertEquals("header below the status bar", base.headerTop + 60, applied.headerTop)
            assertEquals(90, applied.headerLeft)
            assertEquals(40, applied.headerRight)
            assertEquals(90, applied.pagerLeft)
            assertEquals(40, applied.pagerRight)
            assertEquals("navigation items above the navigation bar", base.navBottom + 120, applied.navBottom)
            assertEquals(base.navLeft + 90, applied.navLeft)
            assertEquals(base.navRight + 40, applied.navRight)
        }
    }

    @Test
    fun `new insets replace the old ones rather than adding to them`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            val layout = Layout(activity)
            layout.dispatch(Insets.NONE)
            val base = layout.applied()

            layout.dispatch(Insets.of(0, 60, 40, 120))
            layout.dispatch(Insets.of(0, 60, 40, 120))
            layout.dispatch(Insets.of(0, 70, 0, 50)) // rotated back to portrait
            val applied = layout.applied()
            assertEquals(base.headerTop + 70, applied.headerTop)
            assertEquals(0, applied.pagerRight)
            assertEquals(base.navBottom + 50, applied.navBottom)
            assertEquals(base.navRight, applied.navRight)

            layout.dispatch(Insets.NONE)
            assertEquals(base, layout.applied())
        }
    }

    @Test
    fun `the bottom navigation and the pages never apply the insets a second time`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            val layout = Layout(activity)
            val reachedChildren = mutableListOf<String>()
            ViewCompat.setOnApplyWindowInsetsListener(layout.navigation) { _, insets -> reachedChildren += "navigation"; insets }
            ViewCompat.setOnApplyWindowInsetsListener(layout.pager) { _, insets -> reachedChildren += "pager"; insets }

            layout.dispatch(Insets.of(0, 60, 0, 120))

            assertEquals("consumed by MainActivity", emptyList<String>(), reachedChildren)
        }
    }

    @Test
    @Config(sdk = [28])
    fun `Android 9 - the window is laid out behind transparent system bars`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            @Suppress("DEPRECATION")
            val flags = activity.window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_STABLE != 0)
            @Suppress("DEPRECATION")
            assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0)
            @Suppress("DEPRECATION")
            assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        }
    }

    @Test
    @Config(sdk = [34])
    fun `Android 14 - transparent status bar, and no scrim over the bottom navigation`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
            assertFalse(activity.window.isNavigationBarContrastEnforced)
        }
    }
}
