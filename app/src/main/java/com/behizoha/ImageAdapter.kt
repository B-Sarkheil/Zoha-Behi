package com.behi.zoha

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.behi.zoha.drive.DriveFile
import com.google.android.material.imageview.ShapeableImageView

class ImageAdapter(
    private val onOpen: (DriveFile) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ImageAdapter.VH>() {

    val items = mutableListOf<DriveFile>()
    private val selected = linkedSetOf<String>()
    private var backup: List<DriveFile>? = null

    var selectionMode = false
        private set

    var reorderMode = false
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

    // ---- Reorder mode ----

    fun startReorder() {
        clearSelection()
        backup = items.toList()
        reorderMode = true
        notifyDataSetChanged()
    }

    /** Restores the order that existed when reorder mode started. */
    fun cancelReorder() {
        backup?.let {
            items.clear()
            items.addAll(it)
        }
        backup = null
        reorderMode = false
        notifyDataSetChanged()
    }

    fun finishReorder() {
        backup = null
        reorderMode = false
        notifyDataSetChanged()
    }

    fun currentIds(): List<String> = items.mapNotNull { it.id }

    fun moveItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
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
        val commentCount: TextView = view.findViewById(R.id.commentCount)
        val check: CheckBox = view.findViewById(R.id.check)
        val handle: ImageView = view.findViewById(R.id.handle)
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
        // The stored date is the upload date, not the date the photo was taken.
        val uploadDate = item.createdTime?.take(10)
        holder.date.text = if (uploadDate.isNullOrEmpty()) "" else "upload date: $uploadDate"
        holder.commentCount.text = item.commentCount.toString()
        item.id?.let { AuthImageLoader.load(holder.thumb, it) }

        val isSelected = selected.contains(item.id)
        // ContextCompat keeps this working on API 21-22 (Context.getColor needs API 23).
        holder.itemView.setBackgroundColor(
            if (isSelected) ContextCompat.getColor(context, R.color.selected_bg) else Color.TRANSPARENT
        )
        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.check.isChecked = isSelected

        // Drag handle: touching it starts a drag right away (only in reorder mode).
        holder.handle.visibility = if (reorderMode) View.VISIBLE else View.GONE
        holder.handle.setOnTouchListener { _, event ->
            if (reorderMode && event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }

        if (reorderMode) {
            // No open/select while reordering; long-press drag is handled by ItemTouchHelper.
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
        } else {
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
}