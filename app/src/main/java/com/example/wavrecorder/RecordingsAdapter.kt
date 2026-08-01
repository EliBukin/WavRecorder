package com.example.wavrecorder

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
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
    private val playbackSpeedLabel: (Uri) -> String
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
        if (payloads.contains(PROGRESS_PAYLOAD)) {
            bindProgress(holder, items[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.fileName.text = RecordingNameFormatter.friendlyTitle(item.name)
        val active = isActive(item.uri)
        holder.binding.activeAccent.visibility = if (active) View.VISIBLE else View.INVISIBLE
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
            holder.binding.fileMeta.text = String.format(
                Locale.US, "%.1fs • %s", item.durationSeconds, formatSize(item.sizeBytes)
            )
        }
    }

    /** Shows exactly one of three states at a time: a spinner while [MediaPlayer] is still
     * preparing (can't accept start()/pause() yet), otherwise a play or pause icon reflecting
     * whether this specific row is the one actually playing right now. */
    private fun bindPlaybackButton(holder: ViewHolder, item: RecordingItem) {
        val active = isActive(item.uri)
        val preparing = active && isPreparing(item.uri)
        val playing = active && isPlaying(item.uri)
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
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.0f KB", kb)
        else String.format(Locale.US, "%.1f MB", kb / 1024)
    }
}
