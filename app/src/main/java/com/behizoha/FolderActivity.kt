package com.behi.zoha

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.behi.zoha.drive.DriveAuthException
import com.behi.zoha.drive.DriveRepo
import com.behi.zoha.drive.TokenManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class FolderActivity : AppCompatActivity() {

    private lateinit var adapter: FolderAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder)
        title = getString(R.string.app_name) + " Ver." + versionName()

        swipe = findViewById(R.id.swipe)
        empty = findViewById(R.id.empty)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = FolderAdapter(
            scope = lifecycleScope,
            onClick = { folder ->
                startActivity(
                    Intent(this, ImageListActivity::class.java)
                        .putExtra(ImageListActivity.EXTRA_FOLDER_ID, folder.id)
                        .putExtra(
                            ImageListActivity.EXTRA_FOLDER_NAME,
                            folder.name ?: getString(R.string.untitled)
                        )
                )
            },
            onSelectionChanged = { count ->
                supportActionBar?.subtitle =
                    if (count > 0) getString(R.string.selected_count, count) else null
                invalidateOptionsMenu()
            }
        )
        recycler.adapter = adapter

        swipe.setOnRefreshListener { lifecycleScope.launch { load() } }

        lifecycleScope.launch { load() }
    }

    /** Reads versionName from build.gradle so the version is managed in one place. */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private suspend fun load() {
        swipe.isRefreshing = true
        try {
            TokenManager.get(this)
            val folders = DriveRepo.folders(this)
            adapter.submit(folders)
            empty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        } catch (e: DriveAuthException) {
            TokenManager.invalidate()
            Ui.toast(this, getString(R.string.error_generic, e.message ?: "auth"))
        } catch (e: Exception) {
            Ui.toast(this, getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
        } finally {
            swipe.isRefreshing = false
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
                    count = DriveRepo.delete(this@FolderActivity, ids)
                } finally {
                    progress.dismiss()
                }
                adapter.clearSelection()
                Ui.toast(this@FolderActivity, getString(R.string.deleted, count))
                load()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_folder, menu)
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
            R.id.action_new_album -> {
                promptNewAlbum()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptNewAlbum() {
        val view = layoutInflater.inflate(R.layout.dialog_new_album, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilName)
        val inputName = view.findViewById<TextInputEditText>(R.id.albumName)
        val inputDate = view.findViewById<TextInputEditText>(R.id.albumDate)
        val inputLocation = view.findViewById<TextInputEditText>(R.id.albumLocation)

        // Date defaults to today; tapping the field opens a date picker.
        val calendar = Calendar.getInstance()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        inputDate.setText(format.format(calendar.time))
        inputDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    calendar.set(year, month, day)
                    inputDate.setText(format.format(calendar.time))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        // Transparent window so the rounded background of the layout is visible.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnCreate).setOnClickListener {
            val name = inputName.text.toString().trim()
            if (name.isEmpty()) {
                tilName.error = getString(R.string.album_name_required)
                return@setOnClickListener
            }
            val date = inputDate.text.toString().trim()
            val location = inputLocation.text.toString().trim()
            dialog.dismiss()
            lifecycleScope.launch {
                try {
                    DriveRepo.createFolder(this@FolderActivity, name, date, location)
                } catch (e: Exception) {
                    Ui.toast(
                        this@FolderActivity,
                        getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
                    )
                }
                load()
            }
        }
        dialog.show()
    }
}