package com.behi.zoha.drive

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Backblaze B2 backed repository.
 * Albums are "folders": B2 has no real folders, so an album is a name prefix such as "Trip/".
 * An empty album is kept alive by a small placeholder file named ".bzEmpty".
 * The album date and location are stored as file info on that placeholder.
 */
object DriveRepo {

    private const val PLACEHOLDER = ".bzEmpty"
    private const val INFO_DATE = "album-date"
    private const val INFO_LOCATION = "album-location"
    private val STAMP = Regex("^\\d{13}_")

    private val api: DriveApi get() = B2Http.api

    fun mediaUrl(fileId: String): String {
        val base = TokenManager.downloadUrl() ?: ""
        return "$base/b2api/v2/b2_download_file_by_id?fileId=$fileId"
    }

    suspend fun folders(context: Context): List<DriveFile> = authed { s ->
        listAll(s, "")
            .filter { it.action == "folder" && it.fileName != null }
            .map { DriveFile(id = it.fileName, name = it.fileName!!.trimEnd('/')) }
            .sortedBy { it.name?.lowercase() }
    }

    /** Photo count, oldest photo as cover, and the album date/location from the placeholder. */
    suspend fun folderStats(context: Context, folderId: String): FolderStats =
        authed { s ->
            val files = listAll(s, folderId)
            val photos = files.filter(::isImage)
            val cover = photos.minByOrNull { it.uploadTimestamp ?: Long.MAX_VALUE }
            val info = files.firstOrNull { it.fileName == folderId + PLACEHOLDER }?.fileInfo
            FolderStats(
                count = photos.size,
                coverId = cover?.fileId,
                date = infoValue(info, INFO_DATE),
                location = infoValue(info, INFO_LOCATION)
            )
        }

    suspend fun images(context: Context, folderId: String): List<DriveFile> =
        authed { s ->
            listAll(s, folderId)
                .filter(::isImage)
                .sortedBy { it.uploadTimestamp ?: 0L }
                .map {
                    DriveFile(
                        id = it.fileId,
                        name = (it.fileName ?: "").removePrefix(folderId).replaceFirst(STAMP, ""),
                        mimeType = it.contentType,
                        createdTime = iso(it.uploadTimestamp)
                    )
                }
        }

    suspend fun upload(context: Context, folderId: String, uri: Uri): DriveFile {
        val resolver: ContentResolver = context.applicationContext.contentResolver
        val name = displayName(resolver, uri) ?: "photo_${System.currentTimeMillis()}.jpg"
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Could not read the selected file")
        // Timestamp prefix avoids name collisions between two phones (e.g. IMG_0001.jpg).
        val fullName = folderId + System.currentTimeMillis() + "_" + name
        val stored = putFile(fullName, mime, bytes)
        return DriveFile(id = stored.fileId, name = name, mimeType = mime)
    }

    suspend fun createFolder(
        context: Context,
        name: String,
        date: String? = null,
        location: String? = null
    ) {
        val clean = name.trim().replace("/", "-")
        if (clean.isEmpty()) return
        val extra = mutableMapOf<String, String>()
        if (!date.isNullOrBlank()) extra["X-Bz-Info-$INFO_DATE"] = encodeValue(date.trim())
        if (!location.isNullOrBlank()) extra["X-Bz-Info-$INFO_LOCATION"] = encodeValue(location.trim())
        putFile("$clean/$PLACEHOLDER", "application/octet-stream", ByteArray(0), extra)
    }

    /**
     * Deletes the given items and returns how many were removed.
     * An id ending with "/" is an album prefix (all files under it are deleted),
     * any other id is a single B2 fileId.
     */
    suspend fun delete(context: Context, ids: List<String>): Int {
        var count = 0
        for (id in ids) {
            try {
                if (id.endsWith("/")) deleteFolder(id) else deleteFile(id)
                count++
            } catch (ignored: Exception) {
            }
        }
        return count
    }

    private suspend fun deleteFile(fileId: String) {
        authed { s ->
            val info = api.getFileInfo(
                s.apiUrl + "/b2api/v2/b2_get_file_info", s.token, FileInfoRequest(fileId)
            )
            val fileName = info.fileName ?: throw IllegalStateException("No file name")
            api.deleteFileVersion(
                s.apiUrl + "/b2api/v2/b2_delete_file_version", s.token,
                DeleteRequest(fileName, fileId)
            )
        }
    }

    private suspend fun deleteFolder(prefix: String) {
        authed { s ->
            // No delimiter: list every file under the prefix, including the placeholder.
            val all = listAll(s, prefix, limit = 100_000, delimiter = null)
            for (f in all) {
                val name = f.fileName ?: continue
                val id = f.fileId ?: continue
                api.deleteFileVersion(
                    s.apiUrl + "/b2api/v2/b2_delete_file_version", s.token,
                    DeleteRequest(name, id)
                )
            }
        }
    }

    private suspend fun putFile(
        fileName: String,
        mime: String,
        bytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ): B2File =
        authed { s ->
            val target = api.getUploadUrl(
                s.apiUrl + "/b2api/v2/b2_get_upload_url", s.token, UploadUrlRequest(s.bucketId)
            )
            api.upload(
                target.uploadUrl ?: throw IllegalStateException("No upload url"),
                target.authorizationToken ?: throw IllegalStateException("No upload token"),
                encodeName(fileName),
                "do_not_verify",
                extraHeaders,
                bytes.toRequestBody(mime.toMediaTypeOrNull())
            )
        }

    private suspend fun listAll(
        s: B2Session,
        prefix: String,
        limit: Int = 5000,
        delimiter: String? = "/"
    ): List<B2File> {
        val out = mutableListOf<B2File>()
        var start: String? = null
        while (out.size < limit) {
            val page = api.listFileNames(
                s.apiUrl + "/b2api/v2/b2_list_file_names",
                s.token,
                ListRequest(
                    bucketId = s.bucketId,
                    prefix = prefix,
                    delimiter = delimiter,
                    startFileName = start
                )
            )
            out.addAll(page.files.orEmpty())
            start = page.nextFileName ?: break
        }
        return out
    }

    private fun isImage(f: B2File): Boolean =
        f.action == "upload" && f.contentType?.startsWith("image/") == true

    private fun encodeName(name: String): String =
        URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("%2F", "/")

    /** Header values must be percent-encoded (supports non-Latin text). */
    private fun encodeValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Reads a file-info value (case-insensitive key) and decodes it defensively. */
    private fun infoValue(info: Map<String, String>?, key: String): String? {
        val raw = info?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            ?: return null
        val decoded = try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            raw
        }
        return decoded.takeIf { it.isNotBlank() }
    }

    private fun iso(ts: Long?): String? {
        if (ts == null) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ts))
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
    } catch (ignored: Exception) {
        null
    }

    /** Runs the call with a valid session; re-authorizes once if the token expired (HTTP 401). */
    private suspend fun <T> authed(block: suspend (B2Session) -> T): T {
        val first = TokenManager.session()
        return try {
            block(first)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                TokenManager.invalidate()
                block(TokenManager.session())
            } else {
                throw e
            }
        }
    }
}