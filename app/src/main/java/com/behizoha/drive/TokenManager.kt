package com.behi.zoha.drive

import android.content.Context
import android.util.Base64
import android.util.Log
import retrofit2.HttpException

class DriveAuthException(message: String) : Exception(message)

data class B2Session(
    val apiUrl: String,
    val downloadUrl: String,
    val token: String,
    val bucketId: String
)

object TokenManager {

    private const val TAG = "BehiAuth"

    @Volatile
    private var session: B2Session? = null

    /** Current auth token, or null if not authorized yet (used by Glide). */
    fun peek(): String? = session?.token

    fun downloadUrl(): String? = session?.downloadUrl

    fun invalidate() {
        session = null
    }

    /** Kept for compatibility with existing callers. */
    suspend fun get(context: Context): String = session().token

    suspend fun session(): B2Session {
        session?.let { return it }
        if (B2Config.KEY_ID.startsWith("PASTE") || B2Config.APPLICATION_KEY.startsWith("PASTE")) {
            throw DriveAuthException("Fill in B2Config.kt with your key first")
        }
        val basic = "Basic " + Base64.encodeToString(
            "${B2Config.KEY_ID}:${B2Config.APPLICATION_KEY}".toByteArray(),
            Base64.NO_WRAP
        )
        try {
            val r = B2Http.api.authorize(basic)
            val bucketId = r.allowed?.bucketId
                ?: throw DriveAuthException("The key must be restricted to a single bucket")
            val fresh = B2Session(
                apiUrl = r.apiUrl ?: throw DriveAuthException("No apiUrl in response"),
                downloadUrl = r.downloadUrl ?: throw DriveAuthException("No downloadUrl in response"),
                token = r.authorizationToken ?: throw DriveAuthException("No token in response"),
                bucketId = bucketId
            )
            session = fresh
            Log.i(TAG, "B2 authorized, bucket=${r.allowed?.bucketName}")
            return fresh
        } catch (e: HttpException) {
            Log.e(TAG, "B2 authorize failed: ${e.code()}", e)
            if (e.code() == 401) throw DriveAuthException("Invalid key ID or application key")
            throw e
        }
    }
}
