package bukin.wavrecorder

import android.app.Application
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPopupMenu

/** A Library row's menu: Share, Save a copy, Rename, Stats and Delete, each reaching its own action. */
@RunWith(RobolectricTestRunner::class)
class RecordingsAdapterMenuTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val item = RecordingItem("recording_20261019_115500_part01.wav", Uri.parse("file:///r.wav"), 13.0, 250_000)

    private val calls = mutableListOf<String>()

    private fun adapter(inUse: Boolean = false) = RecordingsAdapter(
        onPlayPause = {},
        onSeekTo = { _, _ -> },
        onSpeedToggle = {},
        onShowStats = { calls += "stats:${it.name}" },
        onDelete = { calls += "delete:${it.name}" },
        isActive = { false },
        isPlaying = { false },
        isPreparing = { false },
        playbackPositionMs = { 0 },
        playbackDurationMs = { 1000 },
        playbackSpeedLabel = { "1.0x" },
        isInUse = { inUse },
        onShare = { calls += "share:${it.name}" },
        onSaveCopy = { calls += "copy:${it.name}" },
        onRename = { calls += "rename:${it.name}" }
    )

    private fun boundRow(adapter: RecordingsAdapter): RecordingsAdapter.ViewHolder {
        adapter.submitList(listOf(item))
        val parent = FrameLayout(android.view.ContextThemeWrapper(app, R.style.Theme_WavRecorder))
        return adapter.onCreateViewHolder(parent, 0).also { adapter.onBindViewHolder(it, 0) }
    }

    @Test
    fun `the menu offers share, save a copy, rename, stats and delete, in that order`() {
        boundRow(adapter()).binding.overflowButton.performClick()

        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }
        assertEquals(
            listOf(R.string.share_button, R.string.save_copy_button, R.string.rename_button, R.string.stats_button, R.string.delete_button)
                .map(app::getString),
            titles
        )
    }

    @Test
    fun `each menu item reaches its own action for that row`() {
        val row = boundRow(adapter())
        for (id in listOf(
            RecordingsAdapter.MENU_SHARE, RecordingsAdapter.MENU_SAVE_COPY, RecordingsAdapter.MENU_RENAME,
            RecordingsAdapter.MENU_STATS, RecordingsAdapter.MENU_DELETE
        )) {
            row.binding.overflowButton.performClick()
            val popup = ShadowPopupMenu.getLatestPopupMenu()
            shadowOf(popup).onMenuItemClickListener.onMenuItemClick(popup.menu.findItem(id))
        }

        assertEquals(listOf("share", "copy", "rename", "stats", "delete").map { "$it:${item.name}" }, calls)
    }

    @Test
    fun `a row being written has no menu, so it can't be shared, copied or renamed`() {
        val row = boundRow(adapter(inUse = true))
        assertEquals(View.INVISIBLE, row.binding.overflowButton.visibility)
    }
}
