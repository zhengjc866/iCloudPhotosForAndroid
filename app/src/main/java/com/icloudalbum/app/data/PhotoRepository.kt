package com.icloudalbum.app.data

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

enum class AuthState { UNKNOWN, LOGGED_OUT, LOGGED_IN, EXPIRED }

/** Media category shown as filter chips in the gallery. */
enum class MediaFilter(val label: String) {
    ALL("全部"),
    PHOTOS("照片"),
    VIDEOS("视频"),
    FAVORITES("收藏"),
}

/**
 * Time window filter. iCloud's query index cannot filter by date, so the app locates
 * the window's rank in the date-ordered index and pages from there (see
 * [IcloudApi.countInRange] / `rankAtOrBefore`).
 */
data class TimeFilter(val year: Int, val month: Int? = null) {
    val label: String
        get() = if (month != null) "${year}年${month}月" else "${year}年"
}

/** Account metadata shown on the settings screen. */
data class AccountInfo(
    val dsid: String?,
    val fullName: String?,
    val server: String?,
)

/** The single entry point the UI layer uses to talk to iCloud. */
interface PhotoRepository {

    val authState: StateFlow<AuthState>

    /** Validates persisted cookies against Apple. Call once on app start. */
    suspend fun restoreSession(): AuthState

    /**
     * Called after the login WebView harvested cookies. Validates them against
     * setup.icloud.com, resolves dsid/webservices and persists everything.
     */
    suspend fun attachSession(cookieHeader: String, suggestedName: String?): AuthState

    /** Fetches one page of the library, newest first. Null cursor = first page. */
    suspend fun fetchPage(
        cursor: String?,
        filter: MediaFilter = MediaFilter.ALL,
        time: TimeFilter? = null,
    ): PhotoPage

    /** Last successfully rendered first page for this filter/window (instant paint). */
    fun cachedPage(filter: MediaFilter, time: TimeFilter?): List<PhotoItem> = emptyList()

    /** Total photos in the library (cached), or null when unknown. */
    fun cachedLibraryCount(): Int? = null

    /** Refreshes and returns the total photos in the library. */
    suspend fun libraryCount(): Int? = null

    /** Exact photo count in [startMs, endMs), or null when it cannot be computed. */
    suspend fun countInRange(startMs: Long, endMs: Long): Int? = null

    /** Downloads the full-res original into the system gallery. */
    suspend fun downloadOriginal(item: PhotoItem): Uri

    /** Account metadata for the settings screen. */
    suspend fun accountInfo(): AccountInfo?

    /**
     * Read-only connection self-test: validates the session, reads one page of the
     * library and reports what worked. Exposed in Settings so a user can tell
     * "server side rejected us" apart from "no session / no network".
     */
    suspend fun selfTest(): List<String>

    suspend fun signOut()
}

class IcloudException(
    message: String,
    val unauthorized: Boolean = false,
    val httpCode: Int = 0,
) : Exception(message)
