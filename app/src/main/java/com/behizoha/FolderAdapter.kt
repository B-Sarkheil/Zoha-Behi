package com.behi.zoha

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.behi.zoha.drive.DriveFile
import com.behi.zoha.drive.DriveRepo
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

class FolderAdapter(
    private val scope: CoroutineScope,
    private val onClick: (DriveFile) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    class Row(val file: DriveFile) {
        var count: Int? = null
        var coverId: String? = null
        var date: String? = null
        var location: String? = null
        var loaded = false
    }

    private val rows = mutableListOf<Row>()
    private val selected = linkedSetOf<String>()
    var selectionMode = false
        private set

    val selectedIds: List<String>
        get() = selected.toList()

    fun submit(folders: List<DriveFile>) {
        rows.clear()
        rows.addAll(folders.map { Row(it) })
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
        val id = rows.getOrNull(position)?.file?.id ?: return
        if (!selected.remove(id)) selected.add(id)
        notifyItemChanged(position)
        if (selected.isEmpty()) {
            selectionMode = false
            notifyDataSetChanged()
        }
        onSelectionChanged(selected.size)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val cover: ShapeableImageView = view.findViewById(R.id.cover)
        val name: TextView = view.findViewById(R.id.name)
        val meta: TextView = view.findViewById(R.id.meta)
        val count: TextView = view.findViewById(R.id.count)
        val check: CheckBox = view.findViewById(R.id.check)
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows.getOrNull(position) ?: return
        val context = holder.itemView.context
        holder.name.text = row.file.name ?: context.getString(R.string.untitled)
        holder.cover.setImageResource(R.drawable.ic_photo)
        holder.meta.visibility = View.GONE

        // Selected albums get a colored border around the card (card background is not visible
        // because the cover image fills the whole card).
        val isSelected = selected.contains(row.file.id)
        holder.card.strokeColor = ContextCompat.getColor(context, R.color.primary)
        holder.card.strokeWidth =
            if (isSelected) (4 * context.resources.displayMetrics.density).toInt() else 0
        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.check.isChecked = isSelected

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= rows.size) return@setOnClickListener
            if (selectionMode) {
                toggle(pos)
            } else {
                onClick(rows[pos].file)
            }
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= rows.size) {
                return@setOnLongClickListener false
            }
            if (!selectionMode) {
                selectionMode = true
                val id = rows[pos].file.id
                if (id != null) selected.add(id)
                notifyDataSetChanged()
                onSelectionChanged(selected.size)
            } else {
                toggle(pos)
            }
            true
        }

        holder.job?.cancel()
        holder.job = null

        if (row.loaded) {
            render(holder, row)
            return
        }

        holder.count.setText(R.string.loading)
        val folderId = row.file.id ?: return
        holder.job = scope.launch {
            try {
                val stats = DriveRepo.folderStats(context, folderId)
                row.count = stats.count
                row.coverId = stats.coverId
                row.date = stats.date
                row.location = stats.location
                row.loaded = true
                if (isRowShowing(holder, row)) render(holder, row)
            } catch (e: Exception) {
                if (isRowShowing(holder, row)) holder.count.text = "—"
            }
        }
    }

    private fun isRowShowing(holder: VH, row: Row): Boolean {
        val pos = holder.bindingAdapterPosition
        return pos != RecyclerView.NO_POSITION && rows.getOrNull(pos) === row
    }

    private fun render(holder: VH, row: Row) {
        val count = row.count
        holder.count.text = when {
            count == null -> "—"
            count == 1 -> holder.itemView.context.getString(R.string.photo_count)
            else -> holder.itemView.context.getString(R.string.photos_count, count)
        }

        // Smaller line under the album name: date and location (both optional).
        val parts = listOfNotNull(
            row.date?.let { "📅 " + formatDate(it) },
            row.location?.takeIf { it.isNotBlank() }?.let { "📍 $it" }
        )
        if (parts.isEmpty()) {
            holder.meta.visibility = View.GONE
        } else {
            holder.meta.text = parts.joinToString("   ")
            holder.meta.visibility = View.VISIBLE
        }

        row.coverId?.let { AuthImageLoader.load(holder.cover, it) }
    }

    /** Stored as yyyy-MM-dd; shown in the phone's own date style. Falls back to the raw text. */
    private fun formatDate(raw: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)
        if (parsed != null) DateFormat.getDateInstance(DateFormat.MEDIUM).format(parsed) else raw
    } catch (e: Exception) {
        raw
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.job = null
        super.onViewRecycled(holder)
    }
}