package com.behi.zoha

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.behi.zoha.drive.DriveAuthException
import com.behi.zoha.drive.DriveRepo
import com.behi.zoha.drive.TokenManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.util.Calendar

class FolderActivity : AppCompatActivity() {

    private lateinit var adapter: FolderAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder)
        title = appTitle()

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

        lifecycleScope.launch { load() }
    }

    /** Reads versionName from build.gradle so the version is managed in one place. */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun appTitle(): String = getString(R.string.app_name) + " - ver." + versionName()

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

    // ---- Reorder mode ----

    private fun startReorder() {
        adapter.startReorder()
        swipe.isEnabled = false
        title = getString(R.string.reorder_title)
        supportActionBar?.subtitle = null
        invalidateOptionsMenu()
        Ui.toast(this, getString(R.string.reorder_hint))
    }

    private fun leaveReorderUi() {
        swipe.isEnabled = true
        title = appTitle()
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
                DriveRepo.saveFolderOrder(this@FolderActivity, ids)
                adapter.finishReorder()
                leaveReorderUi()
                Ui.toast(this@FolderActivity, getString(R.string.order_saved))
            } catch (e: Exception) {
                Ui.toast(
                    this@FolderActivity,
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
        val reordering = adapter.reorderMode
        menu.findItem(R.id.action_delete)?.isVisible =
            !reordering && adapter.selectionMode && adapter.selectedIds.isNotEmpty()
        menu.findItem(R.id.action_rename)?.isVisible =
            !reordering && adapter.selectionMode && adapter.selectedIds.size == 1
        menu.findItem(R.id.action_new_album)?.isVisible = !reordering
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
            R.id.action_rename -> {
                promptRename()
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

    private fun promptNewAlbum() {
        val view = layoutInflater.inflate(R.layout.dialog_new_album, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilName)
        val inputName = view.findViewById<TextInputEditText>(R.id.albumName)
        val inputDate = view.findViewById<TextInputEditText>(R.id.albumDate)
        val inputLocation = view.findViewById<TextInputEditText>(R.id.albumLocation)

        // Date defaults to today (Jalali); tapping the field opens a date picker.
        val calendar = Calendar.getInstance()
        val (jy, jm, jd) = JalaliDate.fromGregorian(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        var selectedJalali = Triple(jy, jm, jd)
        inputDate.setText("%04d/%02d/%02d".format(jy, jm, jd))
        inputDate.setOnClickListener {
            JalaliDatePickerDialog(
                this,
                selectedJalali.first,
                selectedJalali.second,
                selectedJalali.third
            ) { year, month, day ->
                selectedJalali = Triple(year, month, day)
                inputDate.setText("%04d/%02d/%02d".format(year, month, day))
            }.show()
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
            val date = JalaliDate.toIso(inputDate.text.toString().trim()) ?: inputDate.text.toString().trim()
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

    private fun promptRename() {
        val folder = adapter.selectedFolder() ?: return
        val view = layoutInflater.inflate(R.layout.dialog_rename_album, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilName)
        val inputName = view.findViewById<TextInputEditText>(R.id.albumName)
        inputName.setText(folder.name)
        inputName.setSelection(inputName.text?.length ?: 0)

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnRename).setOnClickListener {
            val name = inputName.text.toString().trim()
            if (name.isEmpty()) {
                tilName.error = getString(R.string.album_name_required)
                return@setOnClickListener
            }
            val prefix = folder.id ?: return@setOnClickListener
            dialog.dismiss()
            val progress = Ui.progress(this, getString(R.string.loading))
            lifecycleScope.launch {
                try {
                    DriveRepo.renameFolder(this@FolderActivity, prefix, name)
                    Ui.toast(this@FolderActivity, getString(R.string.renamed))
                    adapter.clearSelection()
                    load()
                } catch (e: DriveRepo.DuplicateAlbumException) {
                    Ui.toast(this@FolderActivity, getString(R.string.rename_exists))
                } catch (e: Exception) {
                    Ui.toast(
                        this@FolderActivity,
                        getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
                    )
                } finally {
                    progress.dismiss()
                }
            }
        }
        dialog.show()
    }
}