package com.behi.zoha

import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.ImageView
import com.behi.zoha.drive.DriveRepo
import com.behi.zoha.drive.TokenManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

object AuthImageLoader {

    private const val TAG = "AuthImageLoader"

    /** Builds a Glide model that carries the B2 authorization token. */
    fun model(fileId: String): Any {
        val url = DriveRepo.mediaUrl(fileId)
        val token = TokenManager.peek() ?: return url
        // B2 expects the raw token in the Authorization header (no "Bearer" prefix).
        return GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("Authorization", token)
                .build()
        )
    }

    fun load(into: ImageView, fileId: String) {
        Glide.with(into)
            .load(model(fileId))
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .placeholder(R.drawable.ic_photo)
            .error(R.drawable.ic_photo)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.e(TAG, "Image load failed for fileId=$fileId")
                    e?.logRootCauses(TAG)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean = false
            })
            .into(into)
    }
}