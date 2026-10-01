package com.behi.zoha

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.behi.zoha.drive.DriveRepo
import com.behi.zoha.drive.TokenManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_FILE_NAME = "file_name"
    }

    private lateinit var fileId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: ""
        title = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.untitled)

        val image = findViewById<ImageView>(R.id.image)
        val progress = findViewById<ProgressBar>(R.id.progress)

        lifecycleScope.launch {
            try {
                TokenManager.get(this@ViewerActivity)
            } catch (ignored: Exception) {
            }
            showImage(image, progress)
        }

        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }
    }

    private fun showImage(image: ImageView, progress: ProgressBar) {
        val url = DriveRepo.mediaUrl(fileId)
        val token = TokenManager.peek()
        val model: Any = if (token != null) {
            GlideUrl(
                url,
                LazyHeaders.Builder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            )
        } else {
            url
        }
        Glide.with(this)
            .load(model)
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
}
