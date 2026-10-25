package com.icloudalbum.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes a downloaded original into the system gallery so it shows up in the
 * phone's Photos app (Pictures/iCloudAlbum, Movies/iCloudAlbum).
 */
object MediaSaver {

    fun save(
        context: Context,
        source: File,
        filename: String,
        mimeType: String,
        takenAt: Long,
    ): Uri {
        val safeName = sanitize(filename)
        val isVideo = mimeType.startsWith("video/")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, source, safeName, mimeType, takenAt, isVideo)
        } else {
            saveLegacy(context, source, safeName, mimeType, takenAt, isVideo)
        }
    }

    private fun saveScoped(
        context: Context,
        source: File,
        filename: String,
        mimeType: String,
        takenAt: Long,
        isVideo: Boolean,
    ): Uri {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (isVideo) "Movies/iCloudAlbum" else "Pictures/iCloudAlbum"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (takenAt > 0) {
                put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IcloudException("无法在系统相册中创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw IcloudException("无法写入系统相册")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        source: File,
        filename: String,
        mimeType: String,
        takenAt: Long,
        isVideo: Boolean,
    ): Uri {
        val dir = if (isVideo) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "iCloudAlbum",
            )
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "iCloudAlbum",
            )
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw IcloudException("无法创建保存目录：${dir.absolutePath}")
        }
        var target = File(dir, filename)
        if (target.exists()) {
            val base = filename.substringBeforeLast('.')
            val ext = filename.substringAfterLast('.', "")
            target = File(dir, "${base}_${System.currentTimeMillis()}.$ext")
        }
        source.inputStream().use { input ->
            target.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return Uri.fromFile(target)
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
        return if (cleaned.isEmpty()) {
            "icloud_${System.currentTimeMillis()}.jpg"
        } else {
            cleaned
        }.take(180)
    }
}
