package com.icloudalbum.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class IcloudPhotoRepository(
    private val context: Context,
    private val store: SessionStore,
    private val api: IcloudApi,
) : PhotoRepository {

    private val galleryCache = GalleryCache(context)

    private val _authState = MutableStateFlow(
        if (store.isLoggedIn) AuthState.UNKNOWN else AuthState.LOGGED_OUT,
    )
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override fun cachedPage(filter: MediaFilter, time: TimeFilter?): List<PhotoItem> =
        galleryCache.load(GalleryCache.keyFor(filter, time))

    override fun cachedLibraryCount(): Int? = store.libraryCount

    override suspend fun libraryCount(): Int? = withContext(Dispatchers.IO) {
        val cached = store.libraryCount
        val fresh = runCatching { api.totalCount() }.getOrNull()
        if (fresh != null && fresh > 0) {
            store.libraryCount = fresh
            fresh
        } else {
            cached
        }
    }

    override suspend fun countInRange(startMs: Long, endMs: Long): Int? = withContext(Dispatchers.IO) {
        runCatching { api.countInRange(startMs, endMs) }.getOrNull()
    }

    override suspend fun restoreSession(): AuthState = withContext(Dispatchers.IO) {
        val cookie = store.cookieHeader
        if (cookie.isNullOrBlank() || store.dsid.isNullOrBlank()) {
            _authState.value = AuthState.LOGGED_OUT
            return@withContext AuthState.LOGGED_OUT
        }
        // Optimistic: the persisted ckdatabasews endpoint is enough to query, so the
        // gallery can start loading immediately instead of waiting for the extra
        // validate round-trip. A real 401 from the query flips us to EXPIRED below.
        if (!store.ckDatabaseUrl.isNullOrBlank()) {
            _authState.value = AuthState.LOGGED_IN
        }
        try {
            val info = api.validateSession(cookie)
            persist(info)
            _authState.value = AuthState.LOGGED_IN
            AuthState.LOGGED_IN
        } catch (e: IcloudException) {
            if (e.unauthorized) {
                _authState.value = AuthState.EXPIRED
                AuthState.EXPIRED
            } else {
                // Transient problem (offline / Apple hiccup). Keep the session and let the
                // gallery retry; showing the login page would force a pointless re-login.
                _authState.value = AuthState.LOGGED_IN
                AuthState.LOGGED_IN
            }
        }
    }

    override suspend fun attachSession(cookieHeader: String, suggestedName: String?): AuthState =
        withContext(Dispatchers.IO) {
            val info = api.validateSession(cookieHeader)
            persist(info, suggestedName)
            _authState.value = AuthState.LOGGED_IN
            AuthState.LOGGED_IN
        }

    private fun persist(info: SessionInfo, suggestedName: String? = null) {
        store.saveSession(
            cookie = info.cookieHeader,
            dsid = info.dsid,
            name = info.fullName ?: suggestedName,
        )
        store.ckDatabaseUrl = info.ckDatabaseUrl
        store.photosWsUrl = info.photosWsUrl
        store.setupHost = info.setupHost
    }

    override suspend fun fetchPage(
        cursor: String?,
        filter: MediaFilter,
        time: TimeFilter?,
    ): PhotoPage =
        withContext(Dispatchers.IO) {
            var page: PhotoPage
            try {
                page = api.queryPhotos(cursor, filter, time)
            } catch (e: IcloudException) {
                // 421 usually means the account's ckdatabasews partition moved (or the
                // login token expired); re-resolving webservices fixes the first case.
                if (e.httpCode == 421) {
                    val refreshed = runCatching {
                        api.validateSession(store.cookieHeader.orEmpty()).also { persist(it) }
                    }.isSuccess
                    if (refreshed) {
                        try {
                            page = api.queryPhotos(cursor, filter, time)
                        } catch (retry: IcloudException) {
                            if (retry.unauthorized) _authState.value = AuthState.EXPIRED
                            throw retry
                        }
                    } else {
                        if (e.unauthorized) _authState.value = AuthState.EXPIRED
                        throw e
                    }
                } else {
                    if (e.unauthorized) _authState.value = AuthState.EXPIRED
                    throw e
                }
            }
            if (cursor == null) {
                galleryCache.save(GalleryCache.keyFor(filter, time), page.items)
            }
            page
        }

    override suspend fun downloadOriginal(item: PhotoItem): Uri = withContext(Dispatchers.IO) {
        val firstUrl = item.originalUrl
            ?: throw IcloudException("该${if (item.isVideo) "视频" else "照片"}没有可下载的原件")
        val temp = try {
            api.downloadToTempFile(firstUrl, item.filename)
        } catch (e: IcloudException) {
            // Signed media URLs expire after roughly an hour; the viewer stays open
            // much longer than that, so re-resolve the URL once before giving up.
            if (e.httpCode == 403 || e.httpCode == 410) {
                val fresh = api.refreshOriginalUrl(item.masterId, item.isVideo)
                    ?: throw e
                api.downloadToTempFile(fresh, item.filename)
            } else {
                throw e
            }
        }
        try {
            MediaSaver.save(context, temp, item.filename, item.mimeType, item.takenAt)
        } finally {
            temp.delete()
        }
    }

    override suspend fun accountInfo(): AccountInfo? = withContext(Dispatchers.IO) {
        AccountInfo(dsid = store.dsid, fullName = store.fullName, server = store.ckDatabaseUrl)
    }

    override suspend fun selfTest(): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val cookie = store.cookieHeader
        if (cookie.isNullOrBlank()) {
            return@withContext listOf("✗ 本机没有保存会话 Cookie，请先登录")
        }
        lines += "✓ Cookie 已保存（${cookie.length} 字节），DSID=${store.dsid ?: "未知"}"
        val session = try {
            api.validateSession(cookie).also {
                persist(it)
                lines += "✓ 会话校验通过"
                lines += "  服务地址 ${it.ckDatabaseUrl}"
                lines += "  账号 ${it.fullName ?: "未知"}"
            }
        } catch (e: Exception) {
            lines += "✗ 会话校验失败：${e.message}"
            if (e is IcloudException && e.unauthorized) {
                _authState.value = AuthState.EXPIRED
                lines += "  → Cookie 已过期，请在设置里退出登录后重新登录"
            }
            return@withContext lines
        }
        val page = try {
            api.queryPhotos(null)
        } catch (e: Exception) {
            lines += "✗ 照片列表读取失败：${e.message}"
            return@withContext lines
        }
        lines += "✓ 照片列表可读取：本页 ${page.items.size} 张，还有下一页=${page.nextCursor != null}"
        val total = api.totalCount()
        if (total != null) lines += "  图库总数：$total 张"
        val firstItem = page.items.firstOrNull()
        lines += "  首张：${firstItem?.filename ?: "-"}"
        val sample = page.items.firstOrNull { it.thumbUrl != null }
        if (sample?.thumbUrl != null) {
            lines += "✓ 缩略图链接存在（${sample.filename}）"
            runCatching {
                val f = api.downloadToTempFile(sample.thumbUrl, "selftest_thumb")
                lines += "✓ 缩略图下载成功：${f.length()} 字节"
                f.delete()
            }.onFailure { lines += "✗ 缩略图下载失败：${it.message}" }
        } else {
            lines += "· 未找到带缩略图的照片，跳过下载测试"
        }
        lines
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        store.clear()
        _authState.value = AuthState.LOGGED_OUT
    }
}
