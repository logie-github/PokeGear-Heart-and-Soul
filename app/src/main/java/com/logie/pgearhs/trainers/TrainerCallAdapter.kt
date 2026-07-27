package com.logie.pgearhs.trainers

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.logie.pgearhs.R

class TrainerCallAdapter(
    private var trainers: List<Trainer>,
    private val onTrainerClicked: (Trainer) -> Unit
) : RecyclerView.Adapter<TrainerCallAdapter.ViewHolder>() {

    class ViewHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    fun submit(newTrainers: List<Trainer>) {
        trainers = newTrainers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_trainer_entry, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val trainer = trainers[position]
        holder.label.text = trainer.displayName
        holder.label.setOnClickListener { onTrainerClicked(trainer) }
    }

    override fun getItemCount(): Int = trainers.size
}
