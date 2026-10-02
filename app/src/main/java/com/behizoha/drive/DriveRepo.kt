package com.behi.zoha.drive

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * Display order is stored in small JSON files named ".order":
 *  - bucket root ".order": list of album prefixes
 *  - "Album/.order": list of photo fileIds in that album
 */
object DriveRepo {

    private const val PLACEHOLDER = ".bzEmpty"
    private const val ORDER_FILE = ".order"
    private const val INFO_DATE = "album-date"
    private const val INFO_LOCATION = "album-location"
    private const val COMMENTS_DIR = ".comments"
    private val STAMP = Regex("^\\d{13}_")

    private val api: DriveApi get() = B2Http.api
    private val http = OkHttpClient()

    fun mediaUrl(fileId: String): String {
        val base = TokenManager.downloadUrl() ?: ""
        return "$base/b2api/v2/b2_download_file_by_id?fileId=$fileId"
    }

    /** Albums in the saved order; albums missing from the order file go last, sorted by name. */
    suspend fun folders(context: Context): List<DriveFile> = authed { s ->
        val all = listAll(s, "")
        val folders = all
            .filter { it.action == "folder" && it.fileName != null }
            .map { DriveFile(id = it.fileName, name = it.fileName!!.trimEnd('/')) }
        applyOrder(folders, orderFor(s, all, ORDER_FILE))
    }

    /** Saves the album order (list of album prefixes) so both phones see the same order. */
    suspend fun saveFolderOrder(context: Context, ids: List<String>) {
        writeOrder(ORDER_FILE, ids)
    }

    /** Saves the photo order (list of fileIds) for one album. */
    suspend fun saveImageOrder(context: Context, folderId: String, ids: List<String>) {
        writeOrder(folderId + ORDER_FILE, ids)
    }

    private suspend fun writeOrder(orderName: String, ids: List<String>) {
        val bytes = Gson().toJson(ids).toByteArray(Charsets.UTF_8)
        val previousId = authed { s ->
            listAll(s, orderName, limit = 10, delimiter = null)
                .firstOrNull { it.fileName == orderName }?.fileId
        }
        putFile(orderName, "application/json", bytes)
        // Remove the old version so versions do not pile up (best effort).
        if (previousId != null) {
            try {
                authed { s ->
                    api.deleteFileVersion(
                        s.apiUrl + "/b2api/v2/b2_delete_file_version", s.token,
                        DeleteRequest(orderName, previousId)
                    )
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /** Finds the order file in an already loaded listing and reads it (empty if missing). */
    private suspend fun orderFor(s: B2Session, files: List<B2File>, orderName: String): List<String> {
        val id = files.firstOrNull { it.action == "upload" && it.fileName == orderName }?.fileId
            ?: return emptyList()
        return readOrder(s, id)
    }

    private suspend fun readOrder(s: B2Session, fileId: String): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = s.downloadUrl + "/b2api/v2/b2_download_file_by_id?fileId=" + fileId
                val request = Request.Builder().url(url).header("Authorization", s.token).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        emptyList<String>()
                    } else {
                        val text = response.body?.string().orEmpty()
                        Gson().fromJson(text, Array<String>::class.java)?.toList().orEmpty()
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun applyOrder(items: List<DriveFile>, order: List<String>): List<DriveFile> {
        val index = HashMap<String, Int>()
        order.forEachIndexed { i, id -> index[id] = i }
        return items.sortedWith(
            compareBy<DriveFile> { index[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.name?.lowercase() }
        )
    }

    /** Photos in saved order; photos missing from the order file go last, oldest first. */
    private fun sortPhotos(photos: List<B2File>, order: List<String>): List<B2File> {
        val index = HashMap<String, Int>()
        order.forEachIndexed { i, id -> index[id] = i }
        return photos.sortedWith(
            compareBy<B2File> { index[it.fileId] ?: Int.MAX_VALUE }
                .thenBy { it.uploadTimestamp ?: 0L }
        )
    }

    /** Photo count, cover (first photo in order) and the album date/location from the placeholder. */
    suspend fun folderStats(context: Context, folderId: String): FolderStats =
        authed { s ->
            val files = listAll(s, folderId)
            val photos = sortPhotos(files.filter(::isImage), orderFor(s, files, folderId + ORDER_FILE))
            val info = files.firstOrNull { it.fileName == folderId + PLACEHOLDER }?.fileInfo
            FolderStats(
                count = photos.size,
                coverId = photos.firstOrNull()?.fileId,
                date = infoValue(info, INFO_DATE),
                location = infoValue(info, INFO_LOCATION)
            )
        }

    suspend fun images(context: Context, folderId: String): List<DriveFile> =
        authed { s ->
            val files = listAll(s, folderId)
            sortPhotos(files.filter(::isImage), orderFor(s, files, folderId + ORDER_FILE))
                .map {
                    DriveFile(
                        id = it.fileId,
                        name = (it.fileName ?: "").removePrefix(folderId).replaceFirst(STAMP, ""),
                        storedName = (it.fileName ?: "").removePrefix(folderId),
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

    /** Thrown when renaming to a name that already belongs to another album. */
    class DuplicateAlbumException : IllegalStateException("Album name already exists")

    /**
     * Renames an album by copying all files to the new prefix and deleting the old ones.
     * The photo order and the album date/location metadata are preserved.
     * Throws [DuplicateAlbumException] if the target name is already taken.
     */
    suspend fun renameFolder(context: Context, oldPrefix: String, newName: String): Boolean {
        val clean = newName.trim().replace("/", "-")
        if (clean.isEmpty()) return false
        val newPrefix = "$clean/"
        if (oldPrefix == newPrefix) return true

        authed { s ->
            // Same root listing pattern as folders().
            if (listAll(s, "").any { it.action == "folder" && it.fileName == newPrefix }) {
                throw DuplicateAlbumException()
            }

            val all = listAll(s, oldPrefix, limit = 100_000, delimiter = null)
            val orderedPhotos =
                sortPhotos(all.filter(::isImage), orderFor(s, all, oldPrefix + ORDER_FILE))

            val created = mutableListOf<Pair<String, String>>()
            val idMap = HashMap<String, String>()
            try {
                for (f in all) {
                    val oldName = f.fileName ?: continue
                    val srcId = f.fileId ?: continue
                    if (oldName == oldPrefix + ORDER_FILE) continue
                    val newNameInFile = newPrefix + oldName.removePrefix(oldPrefix)

                    if (oldName == oldPrefix + PLACEHOLDER) {
                        // Re-upload the placeholder so date/location metadata is kept exactly.
                        val extra = mutableMapOf<String, String>()
                        infoValue(f.fileInfo, INFO_DATE)
                            ?.let { extra["X-Bz-Info-$INFO_DATE"] = encodeValue(it) }
                        infoValue(f.fileInfo, INFO_LOCATION)
                            ?.let { extra["X-Bz-Info-$INFO_LOCATION"] = encodeValue(it) }
                        putFile(newNameInFile, "application/octet-stream", ByteArray(0), extra)
                            .fileId?.let { created.add(newNameInFile to it) }
                        continue
                    }

                    val copied = api.copyFile(
                        s.apiUrl + "/b2api/v2/b2_copy_file", s.token,
                        CopyRequest(
                            sourceFileId = srcId,
                            fileName = newNameInFile
                        )
                    )
                    copied.fileId?.let {
                        created.add(newNameInFile to it)
                        idMap[srcId] = it
                    }
                }

                // Copies get new fileIds, so the order file must be rewritten with the new ids
                // to keep the same photo order.
                val newOrder = orderedPhotos.mapNotNull { idMap[it.fileId] }
                if (newOrder.isNotEmpty()) {
                    val bytes = Gson().toJson(newOrder).toByteArray(Charsets.UTF_8)
                    putFile(newPrefix + ORDER_FILE, "application/json", bytes)
                        .fileId?.let { created.add(newPrefix + ORDER_FILE to it) }
                }
            } catch (e: Exception) {
                // Best-effort cleanup so a failed rename does not leave a partial album behind.
                for ((name, id) in created) {
                    try {
                        api.deleteFileVersion(
                            s.apiUrl + "/b2api/v2/b2_delete_file_version", s.token,
                            DeleteRequest(name, id)
                        )
                    } catch (ignored: Exception) {
                    }
                }
                throw e
            }

            // Only start deleting the old album once the new one is complete.
            for (f in all) {
                val name = f.fileName ?: continue
                val id = f.fileId ?: continue
                api.deleteFileVersion(
                    s.apiUrl + "/b2api/v2/b2_delete_file_version", s.token,
                    DeleteRequest(name, id)
                )
            }
        }
        return true
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
            // Also remove this photo's comments file (best effort) so it does not stay orphaned.
            try {
                val slash = fileName.lastIndexOf('/')
                val prefix = if (slash >= 0) fileName.substring(0, slash + 1) else ""
                val commentsName = commentsFileName(prefix, fileName.substring(slash + 1))
                val found = listAll(s, commentsName, limit = 10, delimiter = null)
                    .filter { it.fileName == commentsName }
                for (c in found) {
                    val cid = c.fileId ?: continue
                    api.deleteFileVersion(
                        s.apiUrl + "/b2api/v2/b2_delete_file_version", s.token,
                        DeleteRequest(commentsName, cid)
                    )
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private suspend fun deleteFolder(prefix: String) {
        authed { s ->
            // No delimiter: list every file under the prefix, including placeholder and order file.
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

    /**
     * Loads comments for an image. Comments are keyed by the stored name (with timestamp),
     * so two photos with the same original name never share comments.
     * [legacyName] is the old key (name without timestamp) used only as a read fallback.
     * Throws on network/parse errors instead of returning an empty list.
     */
    suspend fun loadComments(
        folderId: String,
        storedName: String,
        legacyName: String? = null
    ): List<Comment> = authed { s -> fetchComments(s, folderId, storedName, legacyName) }

    /** Adds a comment to an image. Fails (throws) if the existing comments cannot be read. */
    suspend fun addComment(
        folderId: String,
        storedName: String,
        comment: Comment,
        legacyName: String? = null
    ): List<Comment> = authed { s ->
        val comments = fetchComments(s, folderId, storedName, legacyName).toMutableList()
        comments.add(comment)
        saveComments(s, folderId, storedName, comments)
        comments
    }

    /** Deletes a comment by its id. Fails (throws) if the existing comments cannot be read. */
    suspend fun deleteComment(
        folderId: String,
        storedName: String,
        commentId: String,
        legacyName: String? = null
    ): List<Comment> = authed { s ->
        val comments = fetchComments(s, folderId, storedName, legacyName)
        val remaining = comments.filter { it.id != commentId }
        if (remaining.size != comments.size) {
            saveComments(s, folderId, storedName, remaining)
        }
        remaining
    }

    private fun commentsFileName(folderId: String, name: String): String =
        "$folderId$COMMENTS_DIR/$name.json"

    /** Reads the comments file (new key first, then the legacy key). Empty only if no file exists. */
    private suspend fun fetchComments(
        s: B2Session,
        folderId: String,
        storedName: String,
        legacyName: String?
    ): List<Comment> {
        val candidates = listOfNotNull(
            commentsFileName(folderId, storedName),
            legacyName?.let { commentsFileName(folderId, it) }
        ).distinct()
        for (name in candidates) {
            val fileId = listAll(s, name, limit = 10, delimiter = null)
                .firstOrNull { it.fileName == name && it.action == "upload" }
                ?.fileId
                ?: continue
            // Old comments have no id; give them a stable one so they can be deleted by id.
            return readComments(s, fileId).map { c ->
                if (c.id.isBlank()) c.copy(id = "legacy-${c.timestamp}-${(c.author + c.text).hashCode()}") else c
            }
        }
        return emptyList()
    }

    private suspend fun saveComments(
        s: B2Session,
        folderId: String,
        storedName: String,
        comments: List<Comment>
    ) {
        val fileName = commentsFileName(folderId, storedName)
        val bytes = Gson().toJson(CommentsFile(comments)).toByteArray(Charsets.UTF_8)
        val previousId = listAll(s, fileName, limit = 10, delimiter = null)
            .firstOrNull { it.fileName == fileName }?.fileId
        putFile(fileName, "application/json", bytes)
        if (previousId != null) {
            try {
                api.deleteFileVersion(
                    s.apiUrl + "/b2api/v2/b2_delete_file_version", s.token,
                    DeleteRequest(fileName, previousId)
                )
            } catch (ignored: Exception) {
            }
        }
    }

    /** Downloads and parses a comments file. Throws on any failure so callers never overwrite with partial data. */
    private suspend fun readComments(s: B2Session, fileId: String): List<Comment> =
        withContext(Dispatchers.IO) {
            val url = s.downloadUrl + "/b2api/v2/b2_download_file_by_id?fileId=" + fileId
            val request = Request.Builder().url(url).header("Authorization", s.token).build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    // Let authed() refresh the token and retry.
                    throw HttpException(retrofit2.Response.error<Any>(401, "".toResponseBody(null)))
                }
                if (!response.isSuccessful) {
                    throw java.io.IOException("Could not read comments (HTTP ${response.code})")
                }
                val text = response.body?.string().orEmpty()
                Gson().fromJson(text, CommentsFile::class.java)?.comments ?: emptyList()
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