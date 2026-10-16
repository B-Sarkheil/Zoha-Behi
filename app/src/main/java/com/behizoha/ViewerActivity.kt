package com.behi.zoha

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import java.util.UUID
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_STORED_NAME = "stored_name"
        const val EXTRA_FOLDER_ID = "folder_id"
        private const val PREFS = "zoha_prefs"
        private const val KEY_AUTHOR = "author_name"
        const val EXTRA_IDS = "ids"
        const val EXTRA_NAMES = "names"
        const val EXTRA_STORED_NAMES = "stored_names"
        const val EXTRA_INDEX = "index"
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
    private lateinit var progress: ProgressBar
    private var ids: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var storedNames: List<String> = emptyList()
    private var index = 0
    private lateinit var image: ZoomImageView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.untitled)
        storedName = intent.getStringExtra(EXTRA_STORED_NAME)?.takeIf { it.isNotEmpty() } ?: fileName
        folderId = intent.getStringExtra(EXTRA_FOLDER_ID) ?: ""
        title = fileName

        image = findViewById(R.id.image)
        progress = findViewById(R.id.progress)

        ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: arrayListOf(fileId)
        names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf(fileName)
        storedNames = intent.getStringArrayListExtra(EXTRA_STORED_NAMES) ?: arrayListOf(storedName)
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, ids.size - 1)

        // Swipe left = next photo, swipe right = previous photo, tap = close the comments sheet.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    // Hide the keyboard first, then collapse the sheet.
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(image.windowToken, 0)
                    behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) < 120 || Math.abs(dx) < Math.abs(dy)) return false
                showAt(if (dx < 0) index + 1 else index - 1)
                return true
            }
        })
        image.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }

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
        adapter = CommentAdapter(comments) { id -> deleteComment(id) }
        commentsRecycler.layoutManager = LinearLayoutManager(this)
        commentsRecycler.adapter = adapter
        renderComments()

        val commentInput = findViewById<TextInputEditText>(R.id.commentInput)
        sendButton.setOnClickListener { addComment(commentInput) }

        lifecycleScope.launch {
            try {
                TokenManager.get(this@ViewerActivity)
            } catch (ignored: Exception) {
            }
            showImage(image, progress)
            loadComments()
        }
    }

    // ---- Comment author (name is stored per phone) ----

    private fun authorName(): String? =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUTHOR, null)
            ?.takeIf { it.isNotBlank() }

    /** Asks for the name shown on this phone's comments; [onDone] runs after it was saved. */
    private fun promptAuthorName(onDone: (() -> Unit)? = null) {
        val edit = EditText(this).apply {
            hint = getString(R.string.your_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
            setText(authorName().orEmpty())
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.name_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) {
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_AUTHOR, name).apply()
                    onDone?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_name -> {
                promptAuthorName()
                true
            }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            else -> super.onOptionsItemSelected(item)
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
        val requestedKey = storedName
        lifecycleScope.launch {
            try {
                val loaded = DriveRepo.loadComments(folderId, requestedKey, fileName)
                // Ignore the result if the user already moved to another photo.
                if (requestedKey == storedName) {
                    comments = loaded
                    renderComments()
                }
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
        val author = authorName()
        if (author == null) {
            // First comment on this phone: ask for a name, then send.
            promptAuthorName { addComment(input) }
            return
        }
        sendButton.isEnabled = false
        lifecycleScope.launch {
            try {
                comments = DriveRepo.addComment(folderId, storedName, Comment(text = text, author = author, id = UUID.randomUUID().toString()), fileName)
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

    private fun deleteComment(commentId: String) {
        lifecycleScope.launch {
            try {
                comments = DriveRepo.deleteComment(folderId, storedName, commentId, fileName)
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

    /** Switches to another photo of the album; ignored at the first/last photo. */
    private fun showAt(newIndex: Int) {
        if (newIndex !in ids.indices || newIndex == index) return
        index = newIndex
        fileId = ids[index]
        fileName = names[index].ifEmpty { getString(R.string.untitled) }
        storedName = storedNames[index].ifEmpty { fileName }
        title = fileName

        comments = emptyList()
        renderComments()
        progress.visibility = View.VISIBLE
        showImage(image, progress)
        loadComments()
    }

    private fun showImage(image: ImageView, progress: ProgressBar) {
        Glide.with(this)
            .load(AuthImageLoader.model(fileId))
            .override(2400, 2400)
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
        private val onDelete: (String) -> Unit
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
                val commentId = comments.getOrNull(pos)?.id ?: return@setOnLongClickListener false
                val ctx = holder.itemView.context
                Ui.confirm(
                    ctx,
                    ctx.getString(R.string.delete_comment_title),
                    ctx.getString(R.string.delete_comment_message),
                    ctx.getString(R.string.delete)
                ) { onDelete(commentId) }
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
