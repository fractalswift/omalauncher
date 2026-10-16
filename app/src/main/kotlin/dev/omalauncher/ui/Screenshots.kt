package dev.omalauncher.ui

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.UUID

/** Imports the newest accessible Android screenshot, preserving its original image bytes. */
internal object Screenshots {
    const val GUEST_DIR = "/root/.local/share/omalauncher/attachments"

    fun latest(context: Context, directory: File): String {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val name = MediaStore.Images.Media.DISPLAY_NAME
        val path = MediaStore.Images.Media.RELATIVE_PATH
        val mime = MediaStore.Images.Media.MIME_TYPE
        val where =
            "(LOWER($path) LIKE ? OR LOWER($name) LIKE ? OR LOWER($name) LIKE ?)" +
                " AND $mime IN ('image/png', 'image/jpeg', 'image/webp')" +
                " AND ${MediaStore.Images.Media.IS_PENDING} = 0" +
                if (Build.VERSION.SDK_INT >= 30) " AND ${MediaStore.Images.Media.IS_TRASHED} = 0" else ""
        val projection = arrayOf(MediaStore.Images.Media._ID, mime)
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        context.contentResolver.query(images, projection, where, arrayOf("%screenshot%", "%screenshot%", "%screen_capture%"), order)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("No accessible screenshots found. Take a screenshot and try again.")
            val uri = ContentUris.withAppendedId(images, cursor.getLong(0))
            val extension = when (cursor.getString(1)) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create attachment directory")
            // Unique, space-free names avoid quoting ambiguity and keep previous prompt attachments valid.
            val file = File(directory, "screenshot-${UUID.randomUUID()}.$extension")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Could not open the latest screenshot")
                if (file.length() == 0L) throw IOException("The latest screenshot is empty")
                return "$GUEST_DIR/${file.name}"
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
        throw IOException("Could not read screenshots")
    }
}
