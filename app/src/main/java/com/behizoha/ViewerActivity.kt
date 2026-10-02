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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_STORED_NAME = "stored_name"
        const val EXTRA_FOLDER_ID = "folder_id"
    }

    private lateinit var fileId: String

    // Display name; also the legacy comments key (name without timestamp).
    private lateinit var fileName: String

    // Full stored name without the album prefix; the unique comments key.
    private lateinit var storedName: String
    private lateinit var folderId: String

    private var comments: List<Comment> = emptyList()
    private lateinit var adapter: CommentAdapter
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var commentsRecycler: RecyclerView
    private lateinit var commentsTitle: TextView
    private lateinit var commentsEmpty: TextView
    private lateinit var sendButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.untitled)
        storedName = intent.getStringExtra(EXTRA_STORED_NAME)?.takeIf { it.isNotEmpty() } ?: fileName
        folderId = intent.getStringExtra(EXTRA_FOLDER_ID) ?: ""
        title = fileName

        val image = findViewById<ImageView>(R.id.image)
        val progress = findViewById<ProgressBar>(R.id.progress)

        // The sheet stays collapsed to its header (peek height set in the layout); tap the header to open.
        behavior = BottomSheetBehavior.from(findViewById<View>(R.id.bottomSheet))
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        findViewById<View>(R.id.commentsHeader).setOnClickListener {
            behavior.state =
                if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    BottomSheetBehavior.STATE_COLLAPSED
                } else {
                    BottomSheetBehavior.STATE_EXPANDED
                }
        }

        commentsTitle = findViewById(R.id.commentsTitle)
        commentsEmpty = findViewById(R.id.commentsEmpty)
        sendButton = findViewById(R.id.btnSendComment)
        commentsRecycler = findViewById(R.id.commentsRecycler)
        adapter = CommentAdapter(comments) { index -> deleteComment(index) }
        commentsRecycler.layoutManager = LinearLayoutManager(this)
        commentsRecycler.adapter = adapter
        renderComments()

        val commentInput = findViewById<TextInputEditText>(R.id.commentInput)
        sendButton.setOnClickListener { addComment(commentInput) }

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

    // Back collapses the comments sheet first, then closes the screen.
    override fun onBackPressed() {
        if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            super.onBackPressed()
        }
    }

    private fun renderComments() {
        adapter.updateComments(comments)
        commentsTitle.text = getString(R.string.comments_with_count, comments.size)
        commentsEmpty.visibility = if (comments.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun reportError(resId: Int, e: Exception) {
        Ui.toast(this, getString(resId, e.message ?: e.javaClass.simpleName))
    }

    private fun loadComments() {
        lifecycleScope.launch {
            try {
                comments = DriveRepo.loadComments(folderId, storedName, fileName)
                renderComments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(R.string.comments_load_failed, e)
            }
        }
    }

    private fun addComment(input: TextInputEditText) {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        sendButton.isEnabled = false
        lifecycleScope.launch {
            try {
                comments = DriveRepo.addComment(folderId, storedName, Comment(text = text), fileName)
                // Clear the input only after the comment was really saved.
                input.text?.clear()
                renderComments()
                commentsRecycler.scrollToPosition(comments.size - 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(R.string.comment_send_failed, e)
            } finally {
                sendButton.isEnabled = true
            }
        }
    }

    private fun deleteComment(index: Int) {
        lifecycleScope.launch {
            try {
                comments = DriveRepo.deleteComment(folderId, storedName, index, fileName)
                renderComments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(R.string.comment_delete_failed, e)
            }
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
                // Use the live adapter position, not the one captured at bind time.
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val ctx = holder.itemView.context
                Ui.confirm(
                    ctx,
                    ctx.getString(R.string.delete_comment_title),
                    ctx.getString(R.string.delete_comment_message),
                    ctx.getString(R.string.delete)
                ) { onDelete(pos) }
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
