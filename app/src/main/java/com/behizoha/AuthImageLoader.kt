package com.behi.zoha

import android.widget.ImageView
import com.behi.zoha.drive.DriveRepo
import com.behi.zoha.drive.TokenManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

object AuthImageLoader {

    fun load(into: ImageView, fileId: String) {
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
        Glide.with(into)
            .load(model)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .placeholder(R.drawable.ic_photo)
            .error(R.drawable.ic_photo)
            .into(into)
    }
}
