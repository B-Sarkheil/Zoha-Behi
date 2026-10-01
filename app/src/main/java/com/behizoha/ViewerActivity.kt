package com.behi.zoha

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.behi.zoha.drive.Comment
import com.behi.zoha.drive.DriveRepo
import com.behi.zoha.drive.TokenManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_FOLDER_ID = "folder_id"
    }

    private lateinit var fileId: String
    private lateinit var fileName: String
    private lateinit var folderId: String
    private var comments: List<Comment> = emptyList()
    private lateinit var adapter: CommentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.untitled)
        folderId = intent.getStringExtra(EXTRA_FOLDER_ID) ?: ""
        title = fileName

        val image = findViewById<ImageView>(R.id.image)
        val progress = findViewById<ProgressBar>(R.id.progress)

        val bottomSheet = findViewById<View>(R.id.bottomSheet)
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.peekHeight = 0
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED

        val commentsRecycler = findViewById<RecyclerView>(R.id.commentsRecycler)
        adapter = CommentAdapter(comments) { index -> deleteComment(index) }
        commentsRecycler.layoutManager = LinearLayoutManager(this)
        commentsRecycler.adapter = adapter

        val commentInput = findViewById<TextInputEditText>(R.id.commentInput)
        findViewById<MaterialButton>(R.id.btnSendComment).setOnClickListener {
            val text = commentInput.text?.toString()?.trim()
            if (text != null && text.isNotEmpty()) {
                addComment(text)
                commentInput.text?.clear()
            }
        }

        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        lifecycleScope.launch {
            try {
                TokenManager.get(this@ViewerActivity)
            } catch (ignored: Exception) {
            }
            showImage(image, progress)
            loadComments()
        }
    }

    private fun loadComments() {
        lifecycleScope.launch {
            comments = DriveRepo.loadComments(folderId, fileName)
            adapter.updateComments(comments)
        }
    }

    private fun addComment(text: String) {
        val comment = Comment(text = text)
        lifecycleScope.launch {
            comments = DriveRepo.addComment(folderId, fileName, comment)
            adapter.updateComments(comments)
            // Expand bottom sheet to show new comment
            val bottomSheet = findViewById<View>(R.id.bottomSheet)
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun deleteComment(index: Int) {
        lifecycleScope.launch {
            comments = DriveRepo.deleteComment(folderId, fileName, index)
            adapter.updateComments(comments)
        }
    }

    // Up button: just close this screen so the album list keeps its folder extras.
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showImage(image: ImageView, progress: ProgressBar) {
        Glide.with(this)
            .load(AuthImageLoader.model(fileId))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    progress.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    progress.visibility = View.GONE
                    return false
                }
            })
            .into(image)
    }

    private fun confirmDelete() {
        Ui.confirm(
            this,
            getString(R.string.delete_title),
            getString(R.string.delete_message),
            getString(R.string.delete)
        ) {
            lifecycleScope.launch {
                var deleted = false
                try {
                    deleted = DriveRepo.delete(this@ViewerActivity, listOf(fileId)) > 0
                } finally {
                    if (deleted) {
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(ImageListActivity.EXTRA_DELETED, true)
                        )
                    }
                    finish()
                }
            }
        }
    }

    private class CommentAdapter(
        private var comments: List<Comment>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<CommentAdapter.VH>() {

        fun updateComments(newComments: List<Comment>) {
            comments = newComments
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_comment, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = comments.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val comment = comments[position]
            holder.author.text = comment.author
            holder.text.text = comment.text
            holder.time.text = comment.displayTime()
            holder.itemView.setOnLongClickListener {
                Ui.confirm(
                    holder.itemView.context,
                    "Delete comment?",
                    "This comment will be permanently deleted.",
                    "Delete"
                ) { onDelete(position) }
                true
            }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val author: TextView = view.findViewById(R.id.commentAuthor)
            val text: TextView = view.findViewById(R.id.commentText)
            val time: TextView = view.findViewById(R.id.commentTime)
        }
    }
}