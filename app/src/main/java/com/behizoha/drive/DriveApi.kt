package com.behi.zoha.drive

import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

// UI-facing model (kept so adapters/activities do not change).
// For folders: id = prefix such as "Trip/", name = "Trip".
// For images: id = B2 fileId.
data class DriveFile(
    val id: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val createdTime: String? = null
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
    val uploadTimestamp: Long? = null
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
