package com.example.wavrecorder

import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the Library row's playback controls: a proper vector icon (not a text glyph) swapping
 * between play/pause, a distinct "preparing" state that disables the button rather than showing
 * either icon, the active-row accent, and the speed control's content description -- all the
 * pieces [LibraryFragment] relies on [RecordingsAdapter] to render correctly for whichever
 * playback state it's currently in.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingsAdapterTest {

    private val item = RecordingItem(
        name = "recording_20260727_013100_part01.wav",
        uri = Uri.parse("file:///recording.wav"),
        durationSeconds = 13.0,
        sizeBytes = 250_000
    )

    // Chip/MaterialButton read Material theme attributes at construction time, so inflating
    // against a plain, un-themed application context throws -- the real app always inflates rows
    // through the RecyclerView's own themed Activity/Fragment context.
    private fun newHolder(adapter: RecordingsAdapter): RecordingsAdapter.ViewHolder {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themed = android.view.ContextThemeWrapper(app, R.style.Theme_WavRecorder)
        val parent = FrameLayout(themed)
        return adapter.onCreateViewHolder(parent, 0)
    }

    private fun buildAdapter(
        active: Boolean = false,
        playing: Boolean = false,
        preparing: Boolean = false,
        speedLabel: String = "1.0x",
        selectionMode: Boolean = false,
        selected: Set<Uri> = emptySet(),
        onToggleSelect: (RecordingItem) -> Unit = {},
        onLongPress: (RecordingItem) -> Unit = {}
    ): RecordingsAdapter = RecordingsAdapter(
        onPlayPause = {},
        onSeekTo = { _, _ -> },
        onSpeedToggle = {},
        onShowStats = {},
        onDelete = {},
        isActive = { active },
        isPlaying = { active && playing },
        isPreparing = { active && preparing },
        playbackPositionMs = { 0 },
        playbackDurationMs = { 1000 },
        playbackSpeedLabel = { speedLabel },
        isSelectionModeActive = { selectionMode },
        isSelected = { uri -> selected.contains(uri) },
        onToggleSelect = onToggleSelect,
        onLongPress = onLongPress
    )

    @Test
    fun `an inactive row shows the play icon, no seek row, and an invisible active accent`() {
        val adapter = buildAdapter(active = false)
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals(ApplicationProvider.getApplicationContext<android.app.Application>()
            .getString(R.string.play_button_description), holder.binding.playButton.contentDescription)
        assertTrue(holder.binding.playButton.isEnabled)
        assertEquals(View.GONE, holder.binding.preparingIndicator.visibility)
        assertEquals(View.VISIBLE, holder.binding.playButton.visibility)
        assertEquals(View.GONE, holder.binding.seekRow.visibility)
        assertEquals(View.VISIBLE, holder.binding.fileMeta.visibility)
        assertEquals(View.INVISIBLE, holder.binding.activeAccent.visibility)
    }

    @Test
    fun `an active, playing row shows the pause icon and a visible active accent`() {
        val adapter = buildAdapter(active = true, playing = true)
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(context.getString(R.string.pause_button_description), holder.binding.playButton.contentDescription)
        assertEquals(View.VISIBLE, holder.binding.activeAccent.visibility)
        assertEquals(View.VISIBLE, holder.binding.seekRow.visibility)
        assertEquals(View.GONE, holder.binding.fileMeta.visibility)
    }

    @Test
    fun `an active, preparing row disables the play button and shows the preparing indicator instead of either icon`() {
        val adapter = buildAdapter(active = true, playing = false, preparing = true)
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.VISIBLE, holder.binding.preparingIndicator.visibility)
        assertEquals(View.INVISIBLE, holder.binding.playButton.visibility)
        assertFalse("the play button must not be clickable while genuinely preparing",
            holder.binding.playButton.isEnabled)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(context.getString(R.string.preparing_button_description),
            holder.binding.preparingIndicator.contentDescription)
    }

    @Test
    fun `the speed chip shows the current label and a matching content description while active`() {
        val adapter = buildAdapter(active = true, playing = true, speedLabel = "1.5x")
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals("1.5x", holder.binding.speedButton.text.toString())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(context.getString(R.string.playback_speed_description, "1.5x"),
            holder.binding.speedButton.contentDescription)
    }

    @Test
    fun `selection mode shows the checkbox checked and hides playback controls`() {
        val adapter = buildAdapter(selectionMode = true, selected = setOf(item.uri))
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.VISIBLE, holder.binding.selectionCheckbox.visibility)
        assertTrue(holder.binding.selectionCheckbox.isChecked)
        assertTrue("the row itself must reflect the selected state (for the selected-row background)",
            holder.binding.root.isActivated)
        assertEquals(View.INVISIBLE, holder.binding.playButton.visibility)
        assertEquals(View.INVISIBLE, holder.binding.overflowButton.visibility)
    }

    @Test
    fun `selection mode shows the checkbox unchecked for an unselected row`() {
        val adapter = buildAdapter(selectionMode = true, selected = emptySet())
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.VISIBLE, holder.binding.selectionCheckbox.visibility)
        assertFalse(holder.binding.selectionCheckbox.isChecked)
        assertFalse(holder.binding.root.isActivated)
    }

    @Test
    fun `outside selection mode the checkbox stays hidden and playback controls stay reachable`() {
        val adapter = buildAdapter(selectionMode = false)
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.GONE, holder.binding.selectionCheckbox.visibility)
        assertEquals(View.VISIBLE, holder.binding.playButton.visibility)
        assertEquals(View.VISIBLE, holder.binding.overflowButton.visibility)
    }

    @Test
    fun `tapping a row while in selection mode toggles its selection, not playback`() {
        var toggled: RecordingItem? = null
        var playPaused = false
        val adapter = RecordingsAdapter(
            onPlayPause = { playPaused = true },
            onSeekTo = { _, _ -> },
            onSpeedToggle = {},
            onShowStats = {},
            onDelete = {},
            isActive = { false },
            isPlaying = { false },
            isPreparing = { false },
            playbackPositionMs = { 0 },
            playbackDurationMs = { 1000 },
            playbackSpeedLabel = { "1.0x" },
            isSelectionModeActive = { true },
            isSelected = { false },
            onToggleSelect = { item -> toggled = item },
            onLongPress = {}
        )
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)
        adapter.onBindViewHolder(holder, 0)

        holder.binding.root.performClick()

        assertEquals(item, toggled)
        assertFalse("a row tap during selection mode must never trigger playback", playPaused)
    }

    @Test
    fun `tapping a row outside selection mode never triggers selection`() {
        var toggled: RecordingItem? = null
        val adapter = buildAdapter(selectionMode = false, onToggleSelect = { item -> toggled = item })
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)
        adapter.onBindViewHolder(holder, 0)

        holder.binding.root.performClick()

        assertEquals(null, toggled)
    }

    @Test
    fun `long-pressing a row reports it for selection regardless of current mode`() {
        var longPressed: RecordingItem? = null
        val adapter = buildAdapter(selectionMode = false, onLongPress = { item -> longPressed = item })
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)
        adapter.onBindViewHolder(holder, 0)

        holder.binding.root.performLongClick()

        assertEquals(item, longPressed)
    }

    @Test
    fun `the selection checkbox has a content description and is not itself a separate tap target`() {
        val adapter = buildAdapter(selectionMode = true)
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals(
            ApplicationProvider.getApplicationContext<android.app.Application>()
                .getString(R.string.selection_checkbox_description),
            holder.binding.selectionCheckbox.contentDescription
        )
        assertFalse(
            "the whole row is the tap target during selection -- the checkbox itself must not " +
                "independently intercept taps",
            holder.binding.selectionCheckbox.isClickable
        )
    }

    @Test
    fun `a selected row exposes a localized selected state description, and the filename stays available`() {
        val adapter = buildAdapter(selectionMode = true, selected = setOf(item.uri))
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(
            context.getString(R.string.selection_row_selected_description),
            androidx.core.view.ViewCompat.getStateDescription(holder.binding.root)?.toString()
        )
        assertEquals(
            "the filename must remain directly readable (by a screen reader's default child-text " +
                "fallback) alongside the state description, not replaced by it",
            RecordingNameFormatter.friendlyTitle(item.name), holder.binding.fileName.text.toString()
        )
    }

    @Test
    fun `an unselected row exposes a localized not-selected state description`() {
        val adapter = buildAdapter(selectionMode = true, selected = emptySet())
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(
            context.getString(R.string.selection_row_not_selected_description),
            androidx.core.view.ViewCompat.getStateDescription(holder.binding.root)?.toString()
        )
    }

    @Test
    fun `the state description is cleared outside selection mode, not left stale from a recycled row`() {
        val adapter = buildAdapter(selectionMode = false)
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)
        // Simulates a recycled ViewHolder that previously showed a selected row.
        androidx.core.view.ViewCompat.setStateDescription(holder.binding.root, "Selected")

        adapter.onBindViewHolder(holder, 0)

        assertEquals(null, androidx.core.view.ViewCompat.getStateDescription(holder.binding.root))
    }

    @Test
    fun `the selection checkbox is not separately exposed to accessibility services`() {
        val adapter = buildAdapter(selectionMode = true)
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)

        adapter.onBindViewHolder(holder, 0)

        assertEquals(
            "the checkbox is purely a visual indicator once the row itself carries the " +
                "selected/not-selected state description -- a separate announcement would be redundant",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO, holder.binding.selectionCheckbox.importantForAccessibility
        )
    }

    @Test
    fun `both playback control touch targets meet the 48dp minimum`() {
        val adapter = buildAdapter()
        adapter.submitList(listOf(item))
        val holder = newHolder(adapter)
        adapter.onBindViewHolder(holder, 0)

        val density = holder.binding.root.resources.displayMetrics.density
        val minPx = (48 * density).toInt()
        assertTrue(holder.binding.playButton.layoutParams.width >= minPx)
        assertTrue(holder.binding.playButton.layoutParams.height >= minPx)
        assertTrue(holder.binding.overflowButton.layoutParams.width >= minPx)
        assertTrue(holder.binding.overflowButton.layoutParams.height >= minPx)
    }
}
