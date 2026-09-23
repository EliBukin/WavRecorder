package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.view.View
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The bottom navigation that replaced the old top tabs: it must drive the existing ViewPager2 pages
 * (and follow them when the pager is swiped), survive recreation without duplicating fragments, and
 * be labelled for TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // RecordFragment binds in onStart(); give it an idle service to bind to (see
        // MainActivityMicTestLifecycleTest for why this is needed at all).
        val idleService = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            idleService.LocalBinder()
        )
    }

    private fun pager(activity: MainActivity): ViewPager2 = activity.findViewById(R.id.viewPager)
    private fun nav(activity: MainActivity): BottomNavigationView = activity.findViewById(R.id.bottomNavigation)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the app opens on Record with the matching navigation item selected`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertEquals(MainActivity.PAGE_RECORD, pager(activity).currentItem)
            assertEquals(R.id.nav_record, nav(activity).selectedItemId)
        }
    }

    @Test
    fun `tapping a navigation item switches the page, in both directions`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.nav_library).performClick()
            idle()
            assertEquals(MainActivity.PAGE_LIBRARY, pager(activity).currentItem)
            assertEquals(R.id.nav_library, nav(activity).selectedItemId)

            activity.findViewById<View>(R.id.nav_record).performClick()
            idle()
            assertEquals(MainActivity.PAGE_RECORD, pager(activity).currentItem)
            assertEquals(R.id.nav_record, nav(activity).selectedItemId)
        }
    }

    @Test
    fun `changing the page directly (as a swipe does) moves the navigation selection with it`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertTrue("swiping between pages must stay supported", pager(activity).isUserInputEnabled)
            pager(activity).currentItem = MainActivity.PAGE_LIBRARY
            idle()
            assertEquals(R.id.nav_library, nav(activity).selectedItemId)
            pager(activity).currentItem = MainActivity.PAGE_RECORD
            idle()
            assertEquals(R.id.nav_record, nav(activity).selectedItemId)
        }
    }

    @Test
    fun `switching pages never recreates or duplicates a fragment`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.nav_library).performClick()
            idle()
            val record = activity.supportFragmentManager.fragments.filterIsInstance<RecordFragment>().single()
            val library = activity.supportFragmentManager.fragments.filterIsInstance<LibraryFragment>().single()

            activity.findViewById<View>(R.id.nav_record).performClick()
            idle()
            activity.findViewById<View>(R.id.nav_library).performClick()
            idle()

            assertTrue(record === activity.supportFragmentManager.fragments.filterIsInstance<RecordFragment>().single())
            assertTrue(library === activity.supportFragmentManager.fragments.filterIsInstance<LibraryFragment>().single())
        }
    }

    @Test
    fun `recreation restores the selected page and navigation item without duplicate fragments`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.nav_library).performClick()
            idle()
        }

        scenario.recreate()
        idle()

        scenario.onActivity { activity ->
            assertEquals(MainActivity.PAGE_LIBRARY, pager(activity).currentItem)
            assertEquals(R.id.nav_library, nav(activity).selectedItemId)
            assertEquals(1, activity.supportFragmentManager.fragments.filterIsInstance<RecordFragment>().size)
            assertEquals(1, activity.supportFragmentManager.fragments.filterIsInstance<LibraryFragment>().size)
        }
    }

    @Test
    fun `reselecting the current item is a no-op`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.nav_record).performClick()
            idle()
            assertEquals(MainActivity.PAGE_RECORD, pager(activity).currentItem)
        }
    }

    @Test
    fun `navigation items and the header are labelled for accessibility`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val menu = nav(activity).menu
            assertEquals(2, menu.size())
            assertEquals(app().getString(R.string.tab_record), menu.findItem(R.id.nav_record).title.toString())
            assertEquals(app().getString(R.string.tab_library), menu.findItem(R.id.nav_library).title.toString())
            assertTrue(menu.findItem(R.id.nav_record).icon != null && menu.findItem(R.id.nav_library).icon != null)

            val header = activity.findViewById<View>(R.id.appHeader)
            assertEquals(app().getString(R.string.app_name), header.contentDescription.toString())
            assertTrue("the app header should be announced as a heading", ViewCompat.isAccessibilityHeading(header))
            assertEquals("the decorative WAV label must not be announced separately",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                activity.findViewById<View>(R.id.appHeaderFormatLabel).importantForAccessibility)
        }
    }

    @Test
    fun `menu items and pages map one to one`() {
        assertEquals(MainActivity.PAGE_RECORD, MainActivity.pageForMenuItem(R.id.nav_record))
        assertEquals(MainActivity.PAGE_LIBRARY, MainActivity.pageForMenuItem(R.id.nav_library))
        assertNull(MainActivity.pageForMenuItem(View.NO_ID))
        assertEquals(R.id.nav_record, MainActivity.menuItemForPage(MainActivity.PAGE_RECORD))
        assertEquals(R.id.nav_library, MainActivity.menuItemForPage(MainActivity.PAGE_LIBRARY))
    }
}
