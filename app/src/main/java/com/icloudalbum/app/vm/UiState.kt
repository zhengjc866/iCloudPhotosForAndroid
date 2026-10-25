package com.icloudalbum.app.vm

import com.icloudalbum.app.data.AuthState
import com.icloudalbum.app.data.MediaFilter
import com.icloudalbum.app.data.PhotoItem
import com.icloudalbum.app.data.TimeFilter

/** A month/year bucket used for the sticky headers of the timeline grid. */
data class PhotoSection(val title: String, val items: List<PhotoItem>)

data class GalleryUiState(
    val authState: AuthState = AuthState.UNKNOWN,
    val filter: MediaFilter = MediaFilter.ALL,
    val timeFilter: TimeFilter? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<PhotoItem> = emptyList(),
    /** Total photos in the iCloud library (not just the loaded page). */
    val totalCount: Int? = null,
    /** Exact number of photos in the active time window, when known. */
    val timeCount: Int? = null,
    /** Exact per-year photo counts, filled in the background from rank boundaries. */
    val yearCounts: Map<Int, Int> = emptyMap(),
    val endReached: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val viewerIndex: Int? = null,
)

data class SettingsUiState(
    val loggedIn: Boolean = false,
    val account: String? = null,
    val dsid: String? = null,
    val server: String? = null,
    val loadedCount: Int = 0,
    val version: String = "",
    val message: String? = null,
    val testing: Boolean = false,
    val testLines: List<String> = emptyList(),
)
