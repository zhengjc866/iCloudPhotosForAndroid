package com.icloudalbum.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.icloudalbum.app.data.AuthState
import com.icloudalbum.app.data.MediaFilter
import com.icloudalbum.app.data.PhotoItem
import com.icloudalbum.app.data.TimeFilter
import com.icloudalbum.app.data.PhotoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GalleryViewModel(private val repo: PhotoRepository) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var cursor: String? = null
    private var inFlight = false
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null
    /** Bumped on every filter/time change so stale in-flight results are dropped. */
    private var generation = 0

    init {
        // Paint the last page from disk immediately; the network refresh replaces it.
        _state.update { it.copy(totalCount = repo.cachedLibraryCount()) }
        primeFromCache(_state.value.filter, _state.value.timeFilter)
        viewModelScope.launch {
            repo.authState.collect { auth ->
                val previous = _state.value.authState
                _state.update { it.copy(authState = auth) }
                if (auth == AuthState.LOGGED_IN && previous != AuthState.LOGGED_IN) {
                    refresh()
                }
            }
        }
    }

    private fun primeFromCache(filter: MediaFilter, time: TimeFilter?) {
        val cached = repo.cachedPage(filter, time)
        if (cached.isEmpty()) return
        _state.update {
            it.copy(items = newestFirst(cached), loading = false, endReached = false, error = null)
        }
    }

    private var yearCountJob: Job? = null

    /**
     * Fills in exact per-year counts for the year cards. Each year needs the two rank
     * boundaries of its range; neighbours share one, so this converges quickly and is
     * cached on disk for later sessions.
     */
    fun ensureYearCounts(years: List<Int>) {
        val missing = years.filter { it !in _state.value.yearCounts }.distinct().take(8)
        if (missing.isEmpty()) return
        if (yearCountJob?.isActive == true) return
        yearCountJob = viewModelScope.launch {
            for (year in missing) {
                if (_state.value.yearCounts.containsKey(year)) continue
                val start = java.util.Calendar.getInstance().apply {
                    clear()
                    set(year, 0, 1, 0, 0, 0)
                }.timeInMillis
                val end = java.util.Calendar.getInstance().apply {
                    clear()
                    set(year + 1, 0, 1, 0, 0, 0)
                }.timeInMillis
                val count = runCatching { repo.countInRange(start, end) }.getOrNull() ?: continue
                _state.update { it.copy(yearCounts = it.yearCounts + (year to count)) }
            }
        }
    }

    /** Called once from the UI on first composition. */
    fun bootstrap() {
        viewModelScope.launch { repo.restoreSession() }
    }

    fun refresh() {
        // Cancel any in-flight refresh / load-more and invalidate older results:
        // filter changes used to race with a pending page append, which mixed photos
        // from the previous (unfiltered) list into the new one.
        generation++
        val gen = generation
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        inFlight = true
        val hasItems = _state.value.items.isNotEmpty()
        _state.update {
            it.copy(
                loading = !hasItems,
                refreshing = hasItems,
                error = null,
                endReached = false,
            )
        }
        refreshJob = viewModelScope.launch {
            try {
                val page = repo.fetchPage(null, _state.value.filter, _state.value.timeFilter)
                if (gen != generation) return@launch
                cursor = page.nextCursor
                val items = newestFirst(page.items)
                if (BuildConfigLogEnabled) {
                    android.util.Log.i(
                        "IcloudSort",
                        "first page: " + items.take(5).joinToString { "${it.filename}@${it.takenAt}" } +
                            " ... last=" + items.lastOrNull()?.let { "${it.filename}@${it.takenAt}" },
                    )
                }
                _state.update {
                    it.copy(
                        items = items,
                        endReached = page.nextCursor.isNullOrBlank(),
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                    )
                }
                // Library total is shown in the header; refresh it in the background so
                // the header never shows a partial page count as "全部".
                if (_state.value.filter == MediaFilter.ALL && _state.value.timeFilter == null) {
                    launch { repo.libraryCount()?.let { total ->
                        _state.update { it.copy(totalCount = total) }
                    } }
                }
                // Exact count for the active time window (e.g. "2023年7月 · 197 张").
                _state.value.timeFilter?.let { window ->
                    launch {
                        windowMillis(window)?.let { (start, end) ->
                            repo.countInRange(start, end)?.let { count ->
                                if (_state.value.timeFilter == window) {
                                    _state.update { it.copy(timeCount = count) }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        error = t.message ?: "加载失败，请稍后重试",
                    )
                }
            } finally {
                inFlight = false
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        // No cursor yet means the first page is still loading (or a filter just reset
        // the list); firing here would duplicate the expensive time-window lookup.
        val c = cursor ?: return
        if (inFlight || s.endReached || s.items.isEmpty()) return
        inFlight = true
        val gen = generation
        _state.update { it.copy(loadingMore = true, error = null) }
        loadMoreJob = viewModelScope.launch {
            try {
                val page = repo.fetchPage(c, s.filter, s.timeFilter)
                if (gen != generation) return@launch
                cursor = page.nextCursor
                val merged = newestFirst((s.items + page.items).distinctBy { it.id })
                _state.update {
                    it.copy(
                        items = merged,
                        endReached = page.nextCursor.isNullOrBlank(),
                        loadingMore = false,
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                _state.update {
                    it.copy(loadingMore = false, message = t.message ?: "加载更多失败")
                }
            } finally {
                inFlight = false
            }
        }
    }

    /** Called by the login WebView after the user authenticated successfully. */
    fun onLoginSuccess(cookieHeader: String, displayName: String?) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val auth = repo.attachSession(cookieHeader, displayName)
                if (auth != AuthState.LOGGED_IN) {
                    _state.update {
                        it.copy(loading = false, error = "已获取到 iCloud 登录状态，但校验会话失败，请重试")
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, error = "登录校验失败：${t.message ?: "网络错误"}")
                }
            }
        }
    }

    fun download(item: PhotoItem) {
        viewModelScope.launch {
            _state.update { it.copy(message = "正在保存 ${item.filename} …") }
            try {
                repo.downloadOriginal(item)
                _state.update { it.copy(message = "已保存到系统相册：${item.filename}") }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "保存失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    fun openViewer(index: Int) {
        _state.update { it.copy(viewerIndex = index) }
    }

    fun closeViewer() {
        _state.update { it.copy(viewerIndex = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Switches the media category: 全部 / 照片 / 视频 / 收藏. */
    fun setFilter(filter: MediaFilter) {
        if (_state.value.filter == filter) return
        cursor = null
        inFlight = false
        _state.update {
            it.copy(
                filter = filter,
                items = emptyList(),
                endReached = false,
                error = null,
                loading = true,
            )
        }
        primeFromCache(filter, _state.value.timeFilter)
        refresh()
    }

    private fun windowMillis(window: TimeFilter): Pair<Long, Long>? = runCatching {
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(window.year, (window.month ?: 1) - 1, 1, 0, 0, 0)
        val start = cal.timeInMillis
        if (window.month != null) cal.add(java.util.Calendar.MONTH, 1) else cal.add(java.util.Calendar.YEAR, 1)
        start to cal.timeInMillis
    }.getOrNull()

    /** Applies a year / year-month window (or null to clear). */
    fun setTimeFilter(time: TimeFilter?) {
        if (_state.value.timeFilter == time) return
        cursor = null
        inFlight = false
        _state.update {
            it.copy(
                timeFilter = time,
                timeCount = null,
                items = emptyList(),
                endReached = false,
                error = null,
                loading = true,
            )
        }
        primeFromCache(_state.value.filter, time)
        refresh()
    }

    fun signOut() {
        viewModelScope.launch {
            repo.signOut()
            cursor = null
            inFlight = false
            _state.value = GalleryUiState(authState = AuthState.LOGGED_OUT)
        }
    }

    /**
     * The timeline is always newest → oldest. The server already walks the
     * assetDate index descending, but sorting here keeps the order stable across
     * pages even if a page arrives slightly out of order (or dedupes overlap).
     */
    private fun newestFirst(items: List<PhotoItem>): List<PhotoItem> = items
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<PhotoItem> { it.takenAt }
                .thenByDescending { it.addedAt }
                .thenByDescending { it.filename },
        )

    private fun groupByMonth(items: List<PhotoItem>): List<PhotoSection> {
        val fmt = java.text.SimpleDateFormat("yyyy年M月", java.util.Locale.CHINA)
        val map = LinkedHashMap<String, MutableList<PhotoItem>>()
        for (item in items) {
            val key = fmt.format(java.util.Date(item.takenAt))
            map.getOrPut(key) { mutableListOf() }.add(item)
        }
        return map.map { PhotoSection(it.key, it.value) }
    }

    companion object {
        private const val BuildConfigLogEnabled = true

        fun factory(repo: PhotoRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GalleryViewModel(repo) as T
            }
    }
}
