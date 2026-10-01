package com.behi.zoha

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.behi.zoha.drive.DriveFile
import com.google.android.material.imageview.ShapeableImageView

class ImageAdapter(
    private val onOpen: (DriveFile) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.VH>() {

    val items = mutableListOf<DriveFile>()
    private val selected = linkedSetOf<String>()
    var selectionMode = false
        private set

    val selectedIds: List<String>
        get() = selected.toList()

    fun submit(images: List<DriveFile>) {
        items.clear()
        items.addAll(images)
        val hadSelection = selectionMode || selected.isNotEmpty()
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
        if (hadSelection) onSelectionChanged(0)
    }

    fun clearSelection() {
        if (!selectionMode && selected.isEmpty()) return
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun toggle(position: Int) {
        val id = items.getOrNull(position)?.id ?: return
        if (!selected.remove(id)) selected.add(id)
        notifyItemChanged(position)
        if (selected.isEmpty()) {
            selectionMode = false
            notifyDataSetChanged()
        }
        onSelectionChanged(selected.size)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ShapeableImageView = view.findViewById(R.id.thumb)
        val name: TextView = view.findViewById(R.id.name)
        val date: TextView = view.findViewById(R.id.date)
        val check: CheckBox = view.findViewById(R.id.check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_image, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.name.text = item.name ?: context.getString(R.string.untitled)
        holder.date.text = item.createdTime?.take(10) ?: ""
        item.id?.let { AuthImageLoader.load(holder.thumb, it) }

        val isSelected = selected.contains(item.id)
        holder.itemView.setBackgroundColor(
            if (isSelected) context.getColor(R.color.selected_bg) else Color.TRANSPARENT
        )
        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.check.isChecked = isSelected

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= items.size) return@setOnClickListener
            if (selectionMode) {
                toggle(pos)
            } else {
                onOpen(items[pos])
            }
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= items.size) {
                return@setOnLongClickListener false
            }
            if (!selectionMode) {
                selectionMode = true
                val id = items[pos].id
                if (id != null) selected.add(id)
                notifyDataSetChanged()
                onSelectionChanged(selected.size)
            } else {
                toggle(pos)
            }
            true
        }
    }
}
