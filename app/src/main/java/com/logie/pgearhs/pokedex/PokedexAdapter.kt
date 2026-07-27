package com.logie.pgearhs.pokedex

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.logie.pgearhs.R
import com.logie.pgearhs.retroarch.LiveDexState

class PokedexAdapter(
    private var entries: List<PokedexEntry>,
    private var dexMode: DexMode,
    private var selectedIndex: Int,
    private val onRowClicked: (Int) -> Unit
) : RecyclerView.Adapter<PokedexAdapter.ViewHolder>() {

    class ViewHolder(row: View) : RecyclerView.ViewHolder(row) {
        val caughtBallIcon: ImageView = row.findViewById(R.id.caughtBallIcon)
        val label: TextView = row.findViewById(R.id.entryLabel)
    }

    fun submit(newEntries: List<PokedexEntry>, newDexMode: DexMode) {
        entries = newEntries
        dexMode = newDexMode
        notifyDataSetChanged()
    }

    fun setSelectedIndex(index: Int) {
        val previous = selectedIndex
        selectedIndex = index
        if (previous in entries.indices) notifyItemChanged(previous)
        if (selectedIndex in entries.indices) notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val row = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_pokedex_entry, parent, false)
        return ViewHolder(row)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val number = if (dexMode == DexMode.NATIONAL) entry.nationalDexNumber else entry.regionalDexNumber
        holder.label.text = "No%03d %s".format(number, entry.displayName)
        holder.caughtBallIcon.visibility =
            if (LiveDexState.isOwned(entry.nationalDexNumber)) View.VISIBLE else View.INVISIBLE
        holder.itemView.isSelected = position == selectedIndex
        holder.itemView.setBackgroundColor(
            if (position == selectedIndex) 0x33FFFFFF else 0x00000000
        )
        holder.itemView.setOnClickListener { onRowClicked(position) }
    }

    override fun getItemCount(): Int = entries.size
}
