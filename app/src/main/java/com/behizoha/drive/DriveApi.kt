package com.behi.zoha.drive

import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

// UI-facing model (kept so adapters/activities do not change).
// For folders: id = prefix such as "Trip/", name = "Trip".
// For images: id = B2 fileId.
data class DriveFile(
    val id: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val createdTime: String? = null,
    // Full stored name without the album prefix (keeps the timestamp). Unique per photo.
    val storedName: String? = null,
    // Number of comments on this photo (images only).
    val commentCount: Int = 0
)

/** Comment on an image. */
data class Comment(
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val author: String = "You",
    // Unique id so a comment can be deleted safely even if the list changed on the other phone.
    val id: String = ""
) {
    /** Jalali date with year and time, consistent with the rest of the app. */
    fun displayTime(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val (jy, jm, jd) = com.behi.zoha.JalaliDate.fromGregorian(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        return "%04d/%02d/%02d %02d:%02d".format(
            jy, jm, jd,
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }
}

data class CommentsFile(val comments: List<Comment> = emptyList())

/** Album summary: photo count, cover photo id and optional date/location metadata. */
data class FolderStats(
    val count: Int,
    val coverId: String?,
    val date: String?,
    val location: String?
)

data class AllowedInfo(
    val bucketId: String? = null,
    val bucketName: String? = null
)

data class AuthResponse(
    val apiUrl: String? = null,
    val downloadUrl: String? = null,
    val authorizationToken: String? = null,
    val allowed: AllowedInfo? = null
)

data class ListRequest(
    val bucketId: String,
    val prefix: String,
    val delimiter: String? = "/",
    val maxFileCount: Int = 1000,
    val startFileName: String? = null
)

data class B2File(
    val fileId: String? = null,
    val fileName: String? = null,
    val action: String? = null,
    val contentType: String? = null,
    val uploadTimestamp: Long? = null,
    val fileInfo: Map<String, String>? = null
)

data class ListResponse(
    val files: List<B2File>? = null,
    val nextFileName: String? = null
)

data class UploadUrlRequest(val bucketId: String)

data class UploadUrlResponse(
    val uploadUrl: String? = null,
    val authorizationToken: String? = null
)

data class FileInfoRequest(val fileId: String)

data class DeleteRequest(val fileName: String, val fileId: String)

data class DeleteResponse(
    val fileId: String? = null,
    val fileName: String? = null
)

// b2_copy_file copies the source metadata by default (metadataDirective COPY),
// so contentType/fileInfo must NOT be sent — B2 rejects them with HTTP 400.
data class CopyRequest(
    val sourceFileId: String,
    val fileName: String,
    val destinationBucketId: String? = null
)

interface DriveApi {

    @GET("b2api/v2/b2_authorize_account")
    suspend fun authorize(@Header("Authorization") basic: String): AuthResponse

    @POST
    suspend fun listFileNames(
        @Url url: String,
        @Header("Authorization") token: String,
        @Body body: ListRequest
    ): ListResponse

    @POST
    suspend fun getUploadUrl(
        @Url url: String,
        @Header("Authorization") token: String,
        @Body body: UploadUrlRequest
    ): UploadUrlResponse

    @POST
    suspend fun upload(
        @Url uploadUrl: String,
        @Header("Authorization") uploadToken: String,
        @Header("X-Bz-File-Name") fileName: String,
        @Header("X-Bz-Content-Sha1") sha1: String,
        @HeaderMap extraHeaders: Map<String, String>,
        @Body body: RequestBody
    ): B2File

    @POST
    suspend fun getFileInfo(
        @Url url: String,
        @Header("Authorization") token: String,
        @Body body: FileInfoRequest
    ): B2File

    @POST
    suspend fun deleteFileVersion(
        @Url url: String,
        @Header("Authorization") token: String,
        @Body body: DeleteRequest
    ): DeleteResponse

    @POST
    suspend fun copyFile(
        @Url url: String,
        @Header("Authorization") token: String,
        @Body body: CopyRequest
    ): B2File
}

internal object B2Http {
    val api: DriveApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.backblazeb2.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DriveApi::class.java)
    }
}