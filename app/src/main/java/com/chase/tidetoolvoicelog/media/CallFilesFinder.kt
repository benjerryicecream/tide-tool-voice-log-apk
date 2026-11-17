package com.chase.tidetoolvoicelog.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.util.Log
import android.provider.MediaStore
import com.chase.tidetoolvoicelog.util.CallFileInfo
import com.chase.tidetoolvoicelog.util.CallFiles
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Finds Samsung's own call recordings through MediaStore - no hard-coded folder.
 * On One UI they land in Recordings/Call, but the query does not care where.
 */
object CallFilesFinder {

    data class Found(
        val mediaId: Long,
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val dateAddedMs: Long,
        val relativePath: String,
        val info: CallFileInfo
    )

    fun recent(context: Context, limit: Int = 40): List<Found> {
        val out = ArrayList<Found>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("Call%")

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                "${MediaStore.Audio.Media._ID} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)

                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val path = if (pathCol >= 0) c.getString(pathCol).orEmpty() else ""
                    val looksLikeCall = name.startsWith("Call", ignoreCase = true) ||
                        path.contains("Call", ignoreCase = true)
                    if (!looksLikeCall) continue
                    if (!name.contains(Regex("\\.(m4a|mp3|amr|aac|wav|ogg)$", RegexOption.IGNORE_CASE))) continue

                    out.add(
                        Found(
                            mediaId = id,
                            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            displayName = name,
                            sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                            dateAddedMs = if (dateCol >= 0) c.getLong(dateCol) * 1000 else 0L,
                            relativePath = path,
                            info = CallFiles.parse(name)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w("CallFiles", "no permission to read audio: " + e.message)
            return emptyList()
        } catch (e: Exception) {
            Log.w("CallFiles", "media query failed", e)
            return emptyList()
        }
        Log.i("CallFiles", "media query returned " + out.size + " call file(s)")
        return out
    }

    /** Copies the recording into app-private storage so it survives the picker's permission. */
    fun copyInto(context: Context, uri: Uri, displayName: String, into: File): File {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._ \\-]"), "_").take(80)
        val target = File(into, "call-" + UUID.randomUUID().toString().take(8) + "-" + safe)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        } ?: throw IOException("could not read that recording")
        if (target.length() < 1024) throw IOException("that recording is empty")
        return target
    }
}
