package com.agentpad.app.media

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

data class LocalPhotoItem(
    val id: Long,
    val uri: String,
    val displayName: String,
    val dateTakenMillis: Long,
    val sizeBytes: Long,
    val mimeType: String
)

data class PhotoSearchQuery(
    val startMillisInclusive: Long,
    val endMillisExclusive: Long,
    val limit: Int = 50
)

/**
 * Local-only MediaStore access. Requires READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE
 * granted by the user. Never uploads.
 */
class LocalMediaLibrary(private val context: Context) {
    fun hasReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    sealed class MediaSearchResult {
        data class Ok(val items: List<LocalPhotoItem>, val partialAccessNote: String?) : MediaSearchResult()
        data object PermissionDenied : MediaSearchResult()
    }

    fun searchByDate(query: PhotoSearchQuery): MediaSearchResult {
        require(query.limit in 1..200) { "limit 必须在 1..200" }
        require(query.endMillisExclusive > query.startMillisInclusive) { "日期范围无效" }
        if (!hasReadPermission()) return MediaSearchResult.PermissionDenied

        val collection: Uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )
        // DATE_TAKEN is ms; DATE_ADDED is seconds — prefer DATE_TAKEN, fallback DATE_ADDED*1000 in filter via OR.
        val selection =
            "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) OR " +
                "(${MediaStore.Images.Media.DATE_TAKEN} = 0 AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?)"
        val startSec = (query.startMillisInclusive / 1000L).toString()
        val endSec = (query.endMillisExclusive / 1000L).toString()
        val args = arrayOf(
            query.startMillisInclusive.toString(),
            query.endMillisExclusive.toString(),
            startSec,
            endSec
        )
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        val results = mutableListOf<LocalPhotoItem>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            args,
            sort
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (cursor.moveToNext() && results.size < query.limit) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(takenCol).let { t ->
                    if (t > 0) t else cursor.getLong(addedCol) * 1000L
                }
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                results += LocalPhotoItem(
                    id = id,
                    uri = contentUri.toString(),
                    displayName = cursor.getString(nameCol) ?: "photo-$id",
                    dateTakenMillis = taken,
                    sizeBytes = cursor.getLong(sizeCol),
                    mimeType = cursor.getString(mimeCol) ?: "image/*"
                )
            }
        }
        val note = if (Build.VERSION.SDK_INT >= 34) {
            "若系统仅授予「所选照片」，结果可能不是整库；可在系统设置中改为允许全部照片。"
        } else {
            null
        }
        return MediaSearchResult.Ok(results, note)
    }
}

object PhotoDateQueryParser {
    /**
     * Very small CN/EN patterns for G1: "2024年5月", "2024-05", "2024/5".
     * Returns null if not parseable.
     */
    fun parseYearMonth(goal: String): Pair<Long, Long>? {
        val ym1 = Regex("""(20\d{2})\s*年\s*(1[0-2]|[1-9])\s*月""").find(goal)
        // Put 1[0-2] before 0?[1-9] so "12" does not match as "1".
        val ym2 = Regex("""(20\d{2})[-/](1[0-2]|0?[1-9])""").find(goal)
        val match = ym1 ?: ym2 ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val start = java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = java.util.Calendar.getInstance().apply {
            timeInMillis = start
            add(java.util.Calendar.MONTH, 1)
        }.timeInMillis
        return start to end
    }
}
