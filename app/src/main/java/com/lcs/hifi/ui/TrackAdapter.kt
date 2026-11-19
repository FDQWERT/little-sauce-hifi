package com.lcs.hifi.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lcs.hifi.R
import com.lcs.hifi.audio.Track
import com.lcs.hifi.util.PcmCodec

class TrackAdapter(private val onPick: (Int) -> Unit) :
    RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = ArrayList<Track>()

    var playingIndex: Int = -1
        set(value) {
            val old = field
            field = value
            if (old in items.indices) notifyItemChanged(old)
            if (value in items.indices) notifyItemChanged(value)
        }

    fun submit(list: List<Track>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun size(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.index.text = (position + 1).toString().padStart(2, '0')
        holder.name.text = t.title
        val fmt = buildString {
            if (t.ext.isNotBlank()) append(t.ext)
            if (t.sampleRate > 0) {
                if (isNotEmpty()) append(" · ")
                append(t.sampleRate / 1000f).append("kHz")
            }
            if (isNotEmpty()) append(" · ")
            append(t.subtitle)
        }
        holder.info.text = fmt
        holder.dur.text = PcmCodec.timeText(t.durationMs)

        val isCur = position == playingIndex
        holder.name.setTextColor(
            if (isCur) 0xFFFF9A3C.toInt() else 0xFFF2F3F5.toInt()
        )
        holder.index.setTextColor(
            if (isCur) 0xFFFF9A3C.toInt() else 0xFF5C616C.toInt()
        )
        holder.itemView.setOnClickListener { onPick(holder.bindingAdapterPosition) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.trackIndex)
        val name: TextView = v.findViewById(R.id.trackName)
        val info: TextView = v.findViewById(R.id.trackInfo)
        val dur: TextView = v.findViewById(R.id.trackDur)
    }
}
