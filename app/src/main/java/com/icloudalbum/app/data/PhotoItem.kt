package com.icloudalbum.app.data

/**
 * One entry of the iCloud photo library, flattened from the CloudKit
 * CPLAsset (metadata) + CPLMaster (resources) record pair.
 */
data class PhotoItem(
    /** CloudKit recordName of the CPLAsset record. Stable id. */
    val id: String,
    /** CloudKit recordName of the CPLMaster record that owns the binaries. */
    val masterId: String,
    val filename: String,
    /** Milliseconds since epoch (assetDate field). */
    val takenAt: Long,
    val addedAt: Long,
    val favorite: Boolean,
    val mediaType: MediaType,
    /** Seconds; 0 for still images. */
    val durationSec: Double,
    val width: Int,
    val height: Int,
    /** Small JPEG thumbnail URL (already contains auth query params). */
    val thumbUrl: String?,
    /** Medium JPEG preview URL, may be null for videos. */
    val previewUrl: String?,
    /** Full resolution original download URL, may be null if unavailable. */
    val originalUrl: String?,
    /** Medium-resolution video URL for in-app streaming (videos only). */
    val streamUrl: String? = null,
    val fileSize: Long,
    val mimeType: String,
) {
    val isVideo: Boolean get() = mediaType == MediaType.VIDEO
}

enum class MediaType { IMAGE, VIDEO }

/** One page of a paged CloudKit query. */
data class PhotoPage(
    val items: List<PhotoItem>,
    val nextCursor: String?,
)
