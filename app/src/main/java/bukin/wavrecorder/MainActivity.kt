package bukin.wavrecorder

import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import bukin.wavrecorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge on every Android version, not only where Android 15+ enforces it, so the
        // layout behaves the same everywhere: transparent system bars, with the insets applied
        // explicitly below. The theme's bar colors only style the launch window.
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No scrim behind 3-button navigation: the bottom navigation's own background shows
            // through, as the navigation bar color did before. Icons still follow the theme.
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        // Runs on every normal app launch, independent of RecordingService/recording state -- see
        // LegacyNotificationCleanup's own doc for why this is the right place for it.
        LegacyNotificationCleanup.run(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        ViewCompat.setAccessibilityHeading(binding.appHeader, true)
        binding.aboutButton.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(AboutDialogFragment.TAG) == null) {
                AboutDialogFragment().show(supportFragmentManager, AboutDialogFragment.TAG)
            }
        }

        // Paging is unchanged: FragmentStateAdapter still owns both fragments (and restores them
        // on recreation), so switching pages never recreates a fragment and the off-screen Record
        // page keeps its STARTED lifecycle -- see MainActivityMicTestLifecycleTest. The bottom
        // navigation is just a second way to drive the same pager; swiping still works too.
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = PAGE_COUNT
            override fun createFragment(position: Int): Fragment =
                if (position == PAGE_RECORD) RecordFragment() else LibraryFragment()
        }

        // Both directions guard against re-entry: selecting an item moves the pager, whose page
        // callback then finds that item already selected and does nothing.
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val page = pageForMenuItem(item.itemId) ?: return@setOnItemSelectedListener false
            if (binding.viewPager.currentItem != page) binding.viewPager.currentItem = page
            true
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = menuItemForPage(position)
                if (binding.bottomNavigation.selectedItemId != itemId) {
                    binding.bottomNavigation.selectedItemId = itemId
                }
            }
        })
    }

    /**
     * Keeps everything clear of the system bars and display cutouts, applying each inset exactly
     * once, here: the header grows by the top inset (so the status bar shows the header's
     * background), the bottom navigation by the bottom inset (its background runs behind the
     * navigation bar, its items sit above it), and all three are kept clear of the sides (a
     * landscape navigation bar or cutout). The insets are then consumed, so the bottom navigation
     * doesn't also apply them itself (Material's default) and the pages, which sit between the
     * header and the bottom navigation, have nothing left to avoid. Dispatching them all from the
     * root also works on Android 10 and lower, where siblings after one that consumes insets
     * never receive them.
     */
    private fun applyWindowInsets() {
        val header = binding.appHeader
        val navigation = binding.bottomNavigation
        val headerPaddingTop = header.paddingTop
        val navigationPaddingBottom = navigation.paddingBottom
        val navigationPaddingLeft = navigation.paddingLeft
        val navigationPaddingRight = navigation.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.updatePadding(top = headerPaddingTop + safe.top)
            header.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = safe.left
                rightMargin = safe.right
            }
            binding.viewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = safe.left
                rightMargin = safe.right
            }
            navigation.updatePadding(
                left = navigationPaddingLeft + safe.left,
                right = navigationPaddingRight + safe.right,
                bottom = navigationPaddingBottom + safe.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    companion object {
        internal const val PAGE_RECORD = 0
        internal const val PAGE_LIBRARY = 1
        private const val PAGE_COUNT = 2

        internal fun pageForMenuItem(itemId: Int): Int? = when (itemId) {
            R.id.nav_record -> PAGE_RECORD
            R.id.nav_library -> PAGE_LIBRARY
            else -> null
        }

        internal fun menuItemForPage(page: Int): Int =
            if (page == PAGE_LIBRARY) R.id.nav_library else R.id.nav_record
    }
}
