package com.behi.zoha

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.behi.zoha.drive.DriveAuthException
import com.behi.zoha.drive.DriveFile
import com.behi.zoha.drive.DriveRepo
import com.behi.zoha.drive.TokenManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class ImageListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
        const val EXTRA_DELETED = "deleted"
        private const val RC_PICK = 2001
        private const val RC_VIEWER = 2002
    }

    private lateinit var adapter: ImageAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var folderId: String
    private lateinit var folderName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_images)
        folderId = intent.getStringExtra(EXTRA_FOLDER_ID) ?: ""
        folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: getString(R.string.untitled)
        title = folderName

        swipe = findViewById(R.id.swipe)
        empty = findViewById(R.id.empty)
        fab = findViewById(R.id.fabAdd)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = ImageAdapter(
            onOpen = { file -> openViewer(file) },
            onSelectionChanged = { count ->
                supportActionBar?.subtitle =
                    if (count > 0) getString(R.string.selected_count, count) else null
                invalidateOptionsMenu()
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        recycler.adapter = adapter

        // Drag & drop: long-press drag only works while reorder mode is on.
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = adapter.reorderMode
        })
        touchHelper.attachToRecyclerView(recycler)

        swipe.setOnRefreshListener { lifecycleScope.launch { load() } }
        fab.setOnClickListener { pickImages() }

        lifecycleScope.launch { load() }
    }

    // Up button: just close this screen so the album list is not recreated.
    override fun onSupportNavigateUp(): Boolean {
        if (adapter.reorderMode) cancelReorder() else finish()
        return true
    }

    private suspend fun load() {
        swipe.isRefreshing = true
        try {
            TokenManager.get(this)
            val images = DriveRepo.images(this, folderId)
            adapter.submit(images)
            empty.visibility = if (images.isEmpty()) View.VISIBLE else View.GONE
        } catch (e: DriveAuthException) {
            TokenManager.invalidate()
            Ui.toast(this, getString(R.string.error_generic, e.message ?: "auth"))
        } catch (e: Exception) {
            Ui.toast(this, getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
        } finally {
            swipe.isRefreshing = false
        }
    }

    // ---- Reorder mode ----

    private fun startReorder() {
        adapter.startReorder()
        swipe.isEnabled = false
        fab.visibility = View.GONE
        title = getString(R.string.reorder_title)
        supportActionBar?.subtitle = null
        invalidateOptionsMenu()
        Ui.toast(this, getString(R.string.reorder_hint_photos))
    }

    private fun leaveReorderUi() {
        swipe.isEnabled = true
        fab.visibility = View.VISIBLE
        title = folderName
        invalidateOptionsMenu()
    }

    private fun cancelReorder() {
        adapter.cancelReorder()
        leaveReorderUi()
    }

    private fun saveOrder() {
        val ids = adapter.currentIds()
        val progress = Ui.progress(this, getString(R.string.loading))
        lifecycleScope.launch {
            try {
                DriveRepo.saveImageOrder(this@ImageListActivity, folderId, ids)
                adapter.finishReorder()
                leaveReorderUi()
                Ui.toast(this@ImageListActivity, getString(R.string.order_saved))
            } catch (e: Exception) {
                Ui.toast(
                    this@ImageListActivity,
                    getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
                )
            } finally {
                progress.dismiss()
            }
        }
    }

    override fun onBackPressed() {
        if (adapter.reorderMode) cancelReorder() else super.onBackPressed()
    }

    private fun openViewer(file: DriveFile) {
        startActivityForResult(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_FILE_ID, file.id)
                .putExtra(ViewerActivity.EXTRA_FILE_NAME, file.name ?: getString(R.string.untitled))
                .putExtra(ViewerActivity.EXTRA_FOLDER_ID, folderId)
                .putExtra(ViewerActivity.EXTRA_STORED_NAME, file.storedName ?: file.name ?: ""),
            RC_VIEWER
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_VIEWER) {
            if (resultCode == Activity.RESULT_OK &&
                data?.getBooleanExtra(EXTRA_DELETED, false) == true
            ) {
                lifecycleScope.launch { load() }
            }
        } else if (requestCode == RC_PICK && resultCode == Activity.RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val clip: ClipData? = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
            data.data?.let { if (!uris.contains(it)) uris.add(it) }
            if (uris.isNotEmpty()) upload(uris)
        }
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.add)),
            RC_PICK
        )
    }

    private fun upload(uris: List<Uri>) {
        val progress = Ui.progress(this, getString(R.string.uploading, 1, uris.size))
        lifecycleScope.launch {
            var ok = 0
            var failed = 0
            try {
                for ((index, uri) in uris.withIndex()) {
                    progress.setMessage(getString(R.string.uploading, index + 1, uris.size))
                    try {
                        DriveRepo.upload(this@ImageListActivity, folderId, uri)
                        ok++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            } finally {
                progress.dismiss()
            }
            if (failed == 0) {
                Ui.toast(this@ImageListActivity, getString(R.string.uploaded, ok))
            } else if (ok == 0) {
                Ui.toast(
                    this@ImageListActivity,
                    getString(R.string.upload_failed, "$failed of ${uris.size}")
                )
            } else {
                Ui.toast(this@ImageListActivity, getString(R.string.uploaded, ok))
            }
            load()
        }
    }

    private fun deleteSelected() {
        val ids = adapter.selectedIds
        if (ids.isEmpty()) return
        Ui.confirm(
            this,
            getString(R.string.delete_title),
            getString(R.string.delete_message),
            getString(R.string.delete)
        ) {
            val progress = Ui.progress(this, getString(R.string.loading))
            lifecycleScope.launch {
                var count = 0
                try {
                    count = DriveRepo.delete(this@ImageListActivity, ids)
                } finally {
                    progress.dismiss()
                }
                adapter.clearSelection()
                Ui.toast(this@ImageListActivity, getString(R.string.deleted, count))
                load()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_images, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val reordering = adapter.reorderMode
        menu.findItem(R.id.action_delete)?.isVisible =
            !reordering && adapter.selectionMode && adapter.selectedIds.isNotEmpty()
        menu.findItem(R.id.action_refresh)?.isVisible = !reordering
        menu.findItem(R.id.action_reorder)?.isVisible = !reordering && adapter.itemCount > 1
        menu.findItem(R.id.action_save_order)?.isVisible = reordering
        menu.findItem(R.id.action_cancel_order)?.isVisible = reordering
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                deleteSelected()
                true
            }
            R.id.action_refresh -> {
                lifecycleScope.launch { load() }
                true
            }
            R.id.action_reorder -> {
                startReorder()
                true
            }
            R.id.action_save_order -> {
                saveOrder()
                true
            }
            R.id.action_cancel_order -> {
                cancelReorder()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}