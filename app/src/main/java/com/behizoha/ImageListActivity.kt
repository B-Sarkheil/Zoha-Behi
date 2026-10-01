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
    private lateinit var folderId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_images)
        folderId = intent.getStringExtra(EXTRA_FOLDER_ID) ?: ""
        title = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: getString(R.string.untitled)

        swipe = findViewById(R.id.swipe)
        empty = findViewById(R.id.empty)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = ImageAdapter(
            onOpen = { file -> openViewer(file) },
            onSelectionChanged = { count ->
                supportActionBar?.subtitle =
                    if (count > 0) getString(R.string.selected_count, count) else null
                invalidateOptionsMenu()
            }
        )
        recycler.adapter = adapter

        swipe.setOnRefreshListener { lifecycleScope.launch { load() } }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { pickImages() }

        lifecycleScope.launch { load() }
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

    private fun openViewer(file: DriveFile) {
        startActivityForResult(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_FILE_ID, file.id)
                .putExtra(ViewerActivity.EXTRA_FILE_NAME, file.name ?: getString(R.string.untitled)),
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
        menu.findItem(R.id.action_delete)?.isVisible =
            adapter.selectionMode && adapter.selectedIds.isNotEmpty()
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun goSignIn() {
        TokenManager.invalidate()
        startActivity(Intent(this, SignInActivity::class.java))
        finish()
    }
}
