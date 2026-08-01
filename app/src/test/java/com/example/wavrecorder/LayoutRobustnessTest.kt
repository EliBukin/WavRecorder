package com.example.wavrecorder

import android.text.TextUtils
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.wavrecorder.databinding.FragmentRecordBinding
import com.example.wavrecorder.databinding.ItemRecordingBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A long device name (or a long friendly recording title) must not be able to blow out these
 * layouts horizontally or crash measure/layout -- checked directly against the inflated bindings
 * at a small, phone-realistic width rather than through the full fragment/adapter wiring, since
 * this is specifically a layout-robustness concern, not a logic one.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutRobustnessTest {

    private val narrowWidthPx = 320

    private fun measureAndLayout(root: View, widthPx: Int) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    // MaterialButton/Chip read Material theme attributes at construction time, so inflating
    // against a plain, un-themed application context throws -- the real app always inflates these
    // through an Activity/Fragment context that already carries Theme.WavRecorder.
    private fun themedContext(): android.content.Context {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        return android.view.ContextThemeWrapper(app, R.style.Theme_WavRecorder)
    }

    @Test
    fun `a very long input-device name is clipped, not allowed to overflow, on the Record screen`() {
        val context = themedContext()
        val binding = FragmentRecordBinding.inflate(android.view.LayoutInflater.from(context))

        val veryLongName = "Some Extremely Long USB Audio Interface Product Name That A Manufacturer Actually Ships ".repeat(3)
        binding.micStatusTitle.text = context.getString(R.string.mic_status_connected_title, veryLongName)
        binding.micStatusSubtitle.text = veryLongName
        binding.destinationLabel.text = veryLongName

        measureAndLayout(binding.root, narrowWidthPx)

        assertEquals("expected the title to stay wrapped to a bounded number of lines",
            TextUtils.TruncateAt.END, binding.micStatusTitle.ellipsize)
        assertEquals(2, binding.micStatusTitle.maxLines)
        assertEquals(TextUtils.TruncateAt.END, binding.micStatusSubtitle.ellipsize)
        assertEquals(TextUtils.TruncateAt.MIDDLE, binding.destinationLabel.ellipsize)
        assertEquals(1, binding.destinationLabel.maxLines)

        assertTrue("the root layout must not measure wider than the space it was given",
            binding.root.measuredWidth <= narrowWidthPx)
    }

    @Test
    fun `a very long recording title and metadata do not break the Library row layout`() {
        val context = themedContext()
        val binding = ItemRecordingBinding.inflate(android.view.LayoutInflater.from(context))

        val veryLongTitle = "Jul 27, 2026 - 1:31 AM (Part 42) recorded with a very long destination folder name attached"
        binding.fileName.text = veryLongTitle
        binding.fileMeta.text = "123456.7s • 999.9 MB from an unusually verbose formatted size string"

        measureAndLayout(binding.root, narrowWidthPx)

        assertEquals(2, binding.fileName.maxLines)
        assertEquals(TextUtils.TruncateAt.END, binding.fileName.ellipsize)
        assertEquals(1, binding.fileMeta.maxLines)
        assertEquals(TextUtils.TruncateAt.END, binding.fileMeta.ellipsize)
        assertTrue("the root row must not measure wider than the space it was given",
            binding.root.measuredWidth <= narrowWidthPx)
    }
}
