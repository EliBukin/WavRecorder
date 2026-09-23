package com.example.wavrecorder

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.wavrecorder.databinding.ItemRecordingBinding
import java.util.Locale

class RecordingsAdapter(
    private val onPlayPause: (RecordingItem) -> Unit,
    private val onSeekTo: (RecordingItem, Int) -> Unit,
    private val onSpeedToggle: (RecordingItem) -> Unit,
    private val onShowStats: (RecordingItem) -> Unit,
    private val onDelete: (RecordingItem) -> Unit,
    private val isActive: (Uri) -> Boolean,
    private val isPlaying: (Uri) -> Boolean,
    private val isPreparing: (Uri) -> Boolean,
    private val playbackPositionMs: (Uri) -> Int,
    private val playbackDurationMs: (Uri) -> Int,
    private val playbackSpeedLabel: (Uri) -> String,
    // Bulk-selection: a tap or long-press anywhere on the row is routed through these rather than
    // the individual play/overflow buttons whenever selection mode is active -- see
    // LibraryFragment.enterSelectionMode/toggleSelection.
    private val isSelectionModeActive: () -> Boolean = { false },
    private val isSelected: (Uri) -> Boolean = { false },
    private val onToggleSelect: (RecordingItem) -> Unit = {},
    private val onLongPress: (RecordingItem) -> Unit = {},
    // "Now" for the presentation-only date group headers (Today/Yesterday/...) -- see
    // RecordingDateGroups. A seam only so tests can pin the date.
    private val nowMillis: () -> Long = System::currentTimeMillis
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private var items: List<RecordingItem> = emptyList()

    companion object {
        private val PROGRESS_PAYLOAD = Any()
    }

    fun submitList(newItems: List<RecordingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Cheap partial update for the ticking playback position, doesn't rebind the whole row. */
    fun notifyProgressChanged(uri: Uri) {
        val index = items.indexOfFirst { it.uri == uri }
        if (index >= 0) notifyItemChanged(index, PROGRESS_PAYLOAD)
    }

    inner class ViewHolder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
        var userIsSeeking = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        // A ticking progress payload must never partially rebind a row that's currently showing
        // the selection UI instead of the normal playback one -- fall through to the full rebind
        // (below, which itself short-circuits into the selection branch) rather than corrupting it.
        if (payloads.contains(PROGRESS_PAYLOAD) && !isSelectionModeActive()) {
            bindProgress(holder, items[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.fileName.text = RecordingNameFormatter.friendlyTitle(item.name)
        val context = holder.binding.root.context
        val header = RecordingDateGroups.headerFor(context, items, position, nowMillis())
        holder.binding.dateHeader.text = header
        holder.binding.dateHeader.visibility = if (header != null) View.VISIBLE else View.GONE
        ViewCompat.setAccessibilityHeading(holder.binding.dateHeader, header != null)

        val selectionMode = isSelectionModeActive()
        val selected = selectionMode && isSelected(item.uri)
        holder.binding.root.isActivated = selected
        holder.binding.root.setOnClickListener { if (isSelectionModeActive()) onToggleSelect(item) }
        holder.binding.root.setOnLongClickListener { onLongPress(item); true }
        holder.binding.selectionCheckbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.binding.selectionCheckbox.isChecked = selected
        // isActivated above only drives the visual (background) selected state -- TalkBack doesn't
        // announce it. A localized state description does, layered on top of (not replacing) the
        // row's default "speak children" announcement, so the filename (from the fileName TextView
        // below) stays audible alongside it. Explicitly cleared outside selection mode so a
        // recycled row never keeps announcing a stale selected/not-selected state from whatever it
        // last showed.
        ViewCompat.setStateDescription(
            holder.binding.root,
            if (selectionMode) {
                holder.binding.root.context.getString(
                    if (selected) R.string.selection_row_selected_description
                    else R.string.selection_row_not_selected_description
                )
            } else {
                null
            }
        )

        if (selectionMode) {
            // Suppresses every normal per-row control while selecting -- the whole row is the tap
            // target instead (wired above), and showing playback controls or an active-row accent
            // for a row currently mid-selection would be confusing (and, for the overflow menu's
            // own delete action, redundant with the bulk one).
            holder.binding.activeAccent.visibility = View.INVISIBLE
            holder.binding.rowActions.visibility = View.GONE
            holder.binding.preparingIndicator.visibility = View.GONE
            holder.binding.playButton.visibility = View.INVISIBLE
            holder.binding.playButton.setOnClickListener(null)
            holder.binding.overflowButton.visibility = View.INVISIBLE
            holder.binding.overflowButton.setOnClickListener(null)
            holder.binding.fileMeta.visibility = View.VISIBLE
            holder.binding.seekRow.visibility = View.GONE
            holder.binding.seekBar.setOnSeekBarChangeListener(null)
            holder.binding.fileMeta.text = formatMeta(holder, item)
            return
        }

        val active = isActive(item.uri)
        holder.binding.activeAccent.visibility = if (active) View.VISIBLE else View.INVISIBLE
        holder.binding.rowActions.visibility = View.VISIBLE
        holder.binding.playButton.visibility = View.VISIBLE
        holder.binding.overflowButton.visibility = View.VISIBLE
        bindPlaybackButton(holder, item)
        holder.binding.playButton.setOnClickListener { onPlayPause(item) }
        holder.binding.speedButton.setOnClickListener { onSpeedToggle(item) }
        holder.binding.overflowButton.setOnClickListener { anchor ->
            PopupMenu(anchor.context, anchor).apply {
                menu.add(0, 1, 0, anchor.context.getString(R.string.stats_button))
                menu.add(0, 2, 1, anchor.context.getString(R.string.delete_button))
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> { onShowStats(item); true }
                        2 -> { onDelete(item); true }
                        else -> false
                    }
                }
            }.show()
        }

        if (active) {
            holder.binding.fileMeta.visibility = View.GONE
            holder.binding.seekRow.visibility = View.VISIBLE
            holder.binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        holder.binding.timeLabel.text =
                            "${formatTime(progress)} / ${formatTime(seekBar.max)}"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    holder.userIsSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    holder.userIsSeeking = false
                    onSeekTo(item, seekBar.progress)
                }
            })
            bindProgress(holder, item)
        } else {
            holder.binding.fileMeta.visibility = View.VISIBLE
            holder.binding.seekRow.visibility = View.GONE
            holder.binding.seekBar.setOnSeekBarChangeListener(null)
            holder.binding.fileMeta.text = formatMeta(holder, item)
        }
    }

    /** Shows exactly one of three states at a time: a spinner while [MediaPlayer] is still
     * preparing (can't accept start()/pause() yet), otherwise a play or pause icon reflecting
     * whether this specific row is the one actually playing right now. Outside selection mode the
     * row itself also carries the spoken playback state (Playing/Paused/Preparing) for the active
     * row, and none for every other row. */
    private fun bindPlaybackButton(holder: ViewHolder, item: RecordingItem) {
        val active = isActive(item.uri)
        val preparing = active && isPreparing(item.uri)
        val playing = active && isPlaying(item.uri)
        if (!isSelectionModeActive()) {
            ViewCompat.setStateDescription(
                holder.binding.root,
                if (!active) null else holder.binding.root.context.getString(
                    when {
                        preparing -> R.string.playback_state_preparing
                        playing -> R.string.playback_state_playing
                        else -> R.string.playback_state_paused
                    }
                )
            )
        }
        holder.binding.preparingIndicator.visibility = if (preparing) View.VISIBLE else View.GONE
        holder.binding.preparingIndicator.contentDescription = holder.binding.root.context.getString(
            R.string.preparing_button_description
        )
        holder.binding.playButton.visibility = if (preparing) View.INVISIBLE else View.VISIBLE
        holder.binding.playButton.isEnabled = !preparing
        holder.binding.playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        holder.binding.playButton.contentDescription = holder.binding.root.context.getString(
            if (playing) R.string.pause_button_description else R.string.play_button_description
        )
    }

    private fun bindProgress(holder: ViewHolder, item: RecordingItem) {
        val speedLabel = playbackSpeedLabel(item.uri)
        holder.binding.speedButton.text = speedLabel
        holder.binding.speedButton.contentDescription =
            holder.binding.root.context.getString(R.string.playback_speed_description, speedLabel)
        bindPlaybackButton(holder, item)
        if (holder.userIsSeeking) return
        val durationMs = playbackDurationMs(item.uri).coerceAtLeast(1)
        val positionMs = playbackPositionMs(item.uri).coerceIn(0, durationMs)
        holder.binding.seekBar.max = durationMs
        holder.binding.seekBar.progress = positionMs
        holder.binding.timeLabel.text = "${formatTime(positionMs)} / ${formatTime(durationMs)}"
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun formatMeta(holder: ViewHolder, item: RecordingItem): String {
        val durationText = formatTime((item.durationSeconds * 1000).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        return holder.binding.root.context.getString(R.string.recording_meta, durationText, formatSize(item.sizeBytes))
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.0f KB", kb)
        else String.format(Locale.US, "%.1f MB", kb / 1024)
    }
}
