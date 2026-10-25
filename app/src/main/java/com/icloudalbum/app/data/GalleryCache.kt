package com.icloudalbum.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disk cache of rendered first pages, keyed by filter + time window.
 *
 * iCloud's query + webservices round-trips take seconds from China, so a cold start or
 * a repeated "2024年3月" selection would otherwise show a spinner. The cached page lets
 * the grid paint immediately (thumbnails are already in Coil's disk cache by photo id)
 * while a fresh page is fetched in the background.
 */
class GalleryCache(context: Context) {

    private val dir = File(context.cacheDir, "album_pages").apply { mkdirs() }

    fun load(key: String): List<PhotoItem> = runCatching {
        val file = fileFor(key)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                add(
                    PhotoItem(
                        id = id,
                        masterId = o.optString("masterId"),
                        filename = o.optString("filename"),
                        takenAt = o.optLong("takenAt"),
                        addedAt = o.optLong("addedAt"),
                        favorite = o.optBoolean("favorite"),
                        mediaType = runCatching {
                            MediaType.valueOf(o.optString("mediaType", "IMAGE"))
                        }.getOrDefault(MediaType.IMAGE),
                        durationSec = o.optDouble("durationSec", 0.0),
                        width = o.optInt("width"),
                        height = o.optInt("height"),
                        thumbUrl = o.optString("thumbUrl").ifBlank { null },
                        previewUrl = o.optString("previewUrl").ifBlank { null },
                        originalUrl = o.optString("originalUrl").ifBlank { null },
                        streamUrl = o.optString("streamUrl").ifBlank { null },
                        fileSize = o.optLong("fileSize"),
                        mimeType = o.optString("mimeType"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(key: String, items: List<PhotoItem>) = runCatching {
        val array = JSONArray()
        items.take(MAX_ITEMS).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("masterId", item.masterId)
                    put("filename", item.filename)
                    put("takenAt", item.takenAt)
                    put("addedAt", item.addedAt)
                    put("favorite", item.favorite)
                    put("mediaType", item.mediaType.name)
                    put("durationSec", item.durationSec)
                    put("width", item.width)
                    put("height", item.height)
                    put("thumbUrl", item.thumbUrl ?: "")
                    put("previewUrl", item.previewUrl ?: "")
                    put("originalUrl", item.originalUrl ?: "")
                    put("streamUrl", item.streamUrl ?: "")
                    put("fileSize", item.fileSize)
                    put("mimeType", item.mimeType)
                },
            )
        }
        val file = fileFor(key)
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
        trim()
    }

    private fun fileFor(key: String): File = File(dir, "page_${key.hashCode().toUInt()}.json")

    /** Keep the cache directory bounded (newest files win). */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    companion object {
        private const val MAX_ITEMS = 120
        private const val MAX_FILES = 12

        fun keyFor(filter: MediaFilter, time: TimeFilter?): String =
            "${filter.name}|${time?.year ?: 0}|${time?.month ?: 0}"
    }
}
