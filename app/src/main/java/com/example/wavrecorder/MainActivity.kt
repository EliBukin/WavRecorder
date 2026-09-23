package com.example.wavrecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.wavrecorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Runs on every normal app launch, independent of RecordingService/recording state -- see
        // LegacyNotificationCleanup's own doc for why this is the right place for it.
        LegacyNotificationCleanup.run(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.appHeader, true)

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
