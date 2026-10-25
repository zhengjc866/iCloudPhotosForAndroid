package com.icloudalbum.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class SessionInfo(
    val cookieHeader: String,
    val dsid: String,
    val ckDatabaseUrl: String,
    val photosWsUrl: String?,
    val fullName: String?,
    /** Which setup host accepted the cookies: setup.icloud.com(.cn). */
    val setupHost: String = "setup.icloud.com",
)

/**
 * Client for Apple's private iCloud Photos ("CloudKit web services") API.
 *
 * See docs/icloud-protocol.md for the protocol summary.
 *
 *  - `POST https://setup.icloud.com[.cn]/setup/ws/1/validate` (body `null`) turns the
 *    session cookie into `dsInfo.dsid` + `webservices.ckdatabasews.url`.
 *  - Photos live in container `com.apple.photos.cloud`, production/private database,
 *    zone `PrimarySync`. Pages are read with `direction` + `startRank` over the
 *    `CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted` index (rank 0 = newest).
 *  - No `ckWebAuthToken` is needed: the `X-APPLE-WEBAUTH-*` cookies are the whole story.
 *  - `downloadURL`s are short-lived signed URLs (~1h), so they are re-fetched via
 *    `/records/lookup` when the CDN answers 403/410.
 *  - The index cannot filter by `assetDate` and has no working smart-album variant, so
 *    media categories are filtered client side and time windows are located by rank.
 */
class IcloudApi(
    private val store: SessionStore,
    private val cacheDir: File,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // The date→rank locator fires several small probes at once; without a wider
        // dispatcher they would queue behind the default 5-per-host limit.
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            },
        )
        .build()

    // ---------------------------------------------------------------- session

    suspend fun validateSession(cookieHeader: String): SessionInfo = withContext(Dispatchers.IO) {
        // China-region Apple IDs authenticate against setup.icloud.com.cn and their
        // cookies are scoped to .icloud.com.cn, so the global host would answer 421
        // for a session that is perfectly valid. Try the remembered host first.
        val preferred = store.setupHost
        val candidates = listOf(preferred, "setup.icloud.com.cn", "setup.icloud.com")
            .distinct()
        var lastAuthError: IcloudException? = null
        for (host in candidates) {
            val url = "https://$host/setup/ws/1/validate?${setupParams()}"
            val body = try {
                // pyicloud posts the JSON literal `null` and validates purely from cookies.
                postJson(url, "null", cookieHeader, retryAsGet = true)
            } catch (e: IcloudException) {
                if (e.unauthorized || e.httpCode == 421) {
                    lastAuthError = e
                    continue
                }
                throw e
            }
            val root = parseObject(body, "iCloud 校验响应")
            val ckErr = ckError(root)
            if (ckErr != null) {
                val mapped = errorFor(ckErr)
                if (mapped.unauthorized) {
                    lastAuthError = mapped
                    continue
                }
                throw mapped
            }
            return@withContext buildSessionInfo(root, cookieHeader, host)
        }
        throw lastAuthError ?: IcloudException("iCloud 校验失败，请重新登录", unauthorized = true)
    }

    private fun buildSessionInfo(
        root: JSONObject,
        cookieHeader: String,
        setupHost: String,
    ): SessionInfo {
        if (!root.optBoolean("hsaTrustedBrowser", true)) {
            throw IcloudException(
                "该登录会话未被信任（hsaTrustedBrowser=false），请在登录页完成双重认证后重试",
                unauthorized = true,
            )
        }
        val dsInfo = root.optJSONObject("dsInfo")
        val dsid = dsInfo?.optString("dsid")?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw IcloudException("无法从 iCloud 响应中获取账号信息，请重新登录", unauthorized = true)
        val webservices = root.optJSONObject("webservices")
        val ckUrl = webservices?.optJSONObject("ckdatabasews")?.optString("url")
            ?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http") }
            ?: throw IcloudException("该账号未开通 iCloud 照片（找不到 ckdatabasews 服务）")
        val photosUrl = webservices?.optJSONObject("photos")?.optString("url")
            ?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") }
        val fullName = buildString {
            dsInfo.optString("fullName").takeIf { it.isNotBlank() }?.let { append(it) }
            if (isEmpty()) dsInfo.optString("appleId").takeIf { it.isNotBlank() }?.let { append(it) }
        }.takeIf { it.isNotBlank() }
        return SessionInfo(cookieHeader, dsid, ckUrl, photosUrl, fullName, setupHost)
    }

    // ----------------------------------------------------------------- photos

    suspend fun queryPhotos(
        cursor: String?,
        filter: MediaFilter = MediaFilter.ALL,
        time: TimeFilter? = null,
    ): PhotoPage = withContext(Dispatchers.IO) {
        val cookie = requireCookie()
        val dsid = requireDsid()
        val endpoint = endpoint()

        // The assetDate index is ordered newest → oldest as startRank grows, so
        // ASCENDING from rank 0 is exactly "由近到远". (Verified against a live
        // account: ASC 0 = 2026-10, DESC count-1 = the oldest page.)
        //
        // Media categories are filtered client side (iCloud's smart-album index
        // rejects every smartAlbum name). Time windows cannot be pushed as a
        // `filterBy` either — `assetDate` filters are silently ignored — so the
        // window is located by binary searching the rank of its date bounds, which
        // turns "page through 10k photos" into a handful of small probes.
        var startRank = cursor?.toIntOrNull() ?: 0
        if (startRank < 0) return@withContext PhotoPage(emptyList(), null)

        var windowStart = 0L
        var windowEnd = Long.MAX_VALUE
        if (time != null) {
            val (ws, we) = timeWindowMillis(time)
            windowStart = ws
            windowEnd = we
            // Only the newer boundary needs searching: paging forward stops as soon
            // as a page contains an asset older than the window.
            val firstInWindow = rankAtOrBefore(we, cookie, dsid, endpoint)
            if (cursor.isNullOrBlank()) startRank = firstInWindow
        }

        // The time-window locator can land a page or two before/after the exact
        // boundary, so keep refilling until the window is reached (12 pages = 1200
        // photos of slack). Media filters need refills too, since a page may contain
        // no item of the requested category.
        val maxAttempts = when {
            time != null -> 12
            filter == MediaFilter.ALL -> 1
            else -> 6
        }
        var pageItems: List<PhotoItem> = emptyList()
        var next: String? = null
        var attempts = 0
        while (true) {
            attempts++
            val payload = queryPayload(
                recordType = RECORD_TYPE_DEFAULT,
                direction = "ASCENDING",
                startRank = startRank,
            )
            val url = "$endpoint/records/query?${queryParams(store.clientId, dsid)}"
            val parsed = parseRecords(postJson(url, payload.toString(), cookie)) { missing ->
                lookupMasters(missing, cookie, dsid, endpoint)
            }

            val inWindow = if (time == null) {
                parsed.items
            } else {
                parsed.items.filter { it.takenAt in windowStart until windowEnd }
            }
            pageItems = inWindow
                .filter { filter.accepts(it) }
                .sortedWith(
                    compareByDescending<PhotoItem> { it.takenAt }.thenByDescending { it.addedAt },
                )
            // Never treat a short page as the end: at window boundaries the server can
            // return 99 masters out of a 100-asset page. Only an empty page (or a page
            // that reached past the time window) ends paging.
            val passedWindow = time != null && parsed.items.any { it.takenAt < windowStart }
            val advanced = startRank + parsed.masterCount
            next = when {
                parsed.masterCount <= 0 -> null
                passedWindow -> null
                else -> advanced.toString()
            }
            startRank = advanced

            if (pageItems.isNotEmpty() || next == null || parsed.masterCount <= 0 || attempts >= maxAttempts) {
                break
            }
        }
        PhotoPage(pageItems, next)
    }

    private fun timeWindowMillis(time: TimeFilter): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(time.year, (time.month ?: 1) - 1, 1, 0, 0, 0)
        val start = cal.timeInMillis
        if (time.month != null) cal.add(Calendar.MONTH, 1) else cal.add(Calendar.YEAR, 1)
        return start to cal.timeInMillis
    }

    /**
     * Exact number of photos captured in [startMs, endMs), computed from the two
     * rank boundaries of the time window (shared/cached across neighbouring ranges).
     */
    suspend fun countInRange(startMs: Long, endMs: Long): Int = withContext(Dispatchers.IO) {
        val cookie = requireCookie()
        val dsid = requireDsid()
        val endpoint = endpoint()
        val bounds = kotlinx.coroutines.coroutineScope {
            val startDeferred = async(Dispatchers.IO) {
                rankAtOrBefore(endMs, cookie, dsid, endpoint, exact = true)
            }
            val endDeferred = async(Dispatchers.IO) {
                rankAtOrBefore(startMs, cookie, dsid, endpoint, exact = true)
            }
            startDeferred.await() to endDeferred.await()
        }
        (bounds.second - bounds.first).coerceAtLeast(0)
    }

    // ---------------------------------------------------- time window by rank

    /** Exact date→rank boundary, stored together with the library size it was measured at. */
    private data class Boundary(val rank: Int, val total: Int)

    /** Lower-bound date→rank boundary used to start paging (never past the window). */
    private val boundaryCache = java.util.concurrent.ConcurrentHashMap<Long, Boundary>()
    /** Exact date→rank boundary used for counts. */
    private val exactCache = java.util.concurrent.ConcurrentHashMap<Long, Boundary>()
    private var cachedTotal: Int? = null
    private var boundaryCacheLoaded = false

    private fun loadBoundaryCache() {
        if (boundaryCacheLoaded) return
        boundaryCacheLoaded = true
        runCatching {
            store.rankCacheEntries.forEach { entry ->
                val parts = entry.split(':')
                // "d:<date>:<rank>:<total>:<ver>" = paging lower bound
                // "x:<date>:<rank>:<total>:<ver>" = exact boundary (counts)
                if (parts.size != 5 || parts[4] != CACHE_VERSION) return@forEach
                val date = parts[1].toLongOrNull() ?: return@forEach
                val rank = parts[2].toIntOrNull() ?: return@forEach
                val total = parts[3].toIntOrNull() ?: return@forEach
                if (date <= 0 || rank < 0 || total <= 0) return@forEach
                val entryValue = Boundary(rank, total)
                when (parts[0]) {
                    "x" -> exactCache[date] = entryValue
                    "d" -> boundaryCache[date] = entryValue
                }
            }
        }
    }

    private fun persistBoundaryCache() {
        runCatching {
            val lower = boundaryCache.entries
                .sortedByDescending { it.key }
                .take(MAX_CACHED_BOUNDARIES)
                .map { "d:${it.key}:${it.value.rank}:${it.value.total}:$CACHE_VERSION" }
            val exact = exactCache.entries
                .sortedByDescending { it.key }
                .take(MAX_CACHED_BOUNDARIES)
                .map { "x:${it.key}:${it.value.rank}:${it.value.total}:$CACHE_VERSION" }
            store.rankCacheEntries = (lower + exact).toSet()
        }
    }

    /**
     * Number of photos captured at/after [target], i.e. the rank where the assetDate
     * index stops being newer than [target]. Ranks are newest-first, so it is monotone.
     *
     * Located with parallel sampling: each round probes 7 evenly spaced ranks in one
     * round-trip (narrowing the bracket ~8x), then the page containing the boundary is
     * counted for an exact answer. Results are cached and persisted; because new photos
     * only enter at the newest end, a stored rank just shifts by the library size delta.
     */
    private suspend fun rankAtOrBefore(
        target: Long,
        cookie: String,
        dsid: String,
        endpoint: String,
        exact: Boolean = false,
    ): Int {
        loadBoundaryCache()
        val total = cachedTotal ?: fetchItemCount(endpoint, cookie, dsid).also {
            if (it > 0) cachedTotal = it
        }.coerceAtLeast(0)
        if (total <= 0) return 0

        val cache = if (exact) exactCache else boundaryCache
        exactCache[target]?.let { cached ->
            if (cached.total <= total) {
                val shifted = (cached.rank + (total - cached.total)).coerceIn(0, total)
                exactCache[target] = Boundary(shifted, total)
                return shifted
            }
            exactCache.remove(target)
        }
        cache[target]?.let { cached ->
            if (cached.total <= total) {
                val shifted = (cached.rank + (total - cached.total)).coerceIn(0, total)
                cache[target] = Boundary(shifted, total)
                return shifted
            }
            cache.remove(target)
        }

        val startedAt = System.currentTimeMillis()
        var requests = 0
        var lo = 0
        var hi = total
        // Parallel sampling rounds: 15 evenly spaced ranks in one wave narrow the
        // bracket 16x (13k → 823 → 52), so two waves are enough. The answer is the
        // lower bound `lo` (the photo at `lo` is still at/after the target), which can
        // only be too *new* — never past the window — and the paging loop below walks
        // the remaining ≤100 photos forward.
        var guard = 0
        while (hi - lo > PAGE_SIZE && guard++ < 4) {
            val step = ((hi - lo) / PROBE_SPLITS).coerceAtLeast(1)
            val ranks = (1 until PROBE_SPLITS)
                .map { lo + step * it }
                .filter { it in (lo + 1) until hi }
            if (ranks.isEmpty()) break
            val probed = kotlinx.coroutines.coroutineScope {
                ranks.map { rank ->
                    async(Dispatchers.IO) { rank to probeDates(rank, cookie, dsid, endpoint) }
                }.awaitAll()
            }
            requests += probed.size
            var moved = false
            for ((rank, dates) in probed) {
                val first = dates.firstOrNull() ?: continue
                if (first >= target) {
                    if (rank > lo) {
                        lo = rank
                        moved = true
                    }
                } else if (rank < hi) {
                    hi = rank
                    moved = true
                }
            }
            if (!moved) break
        }
        // Exact mode counts inside the boundary page: the bracket is already <= one
        // page here, so this yields the precise number of photos at/after the target.
        var answer = lo
        if (exact) {
            val page = probeDates(lo, cookie, dsid, endpoint)
            requests++
            val newer = page.count { it >= target }
            answer = (lo + newer).coerceIn(0, total)
        }
        val targetCache = if (exact) exactCache else boundaryCache
        targetCache[target] = Boundary(answer, total)
        persistBoundaryCache()
        android.util.Log.i(
            "IcloudRank",
            (if (exact) "exactRank" else "lowerRank") +
                "($target) = $answer (bracket $lo..$hi) with $requests requests in " +
                "${System.currentTimeMillis() - startedAt}ms (total=$total)",
        )
        return answer
    }

    /** assetDate values (newest first) of the assets starting at [startRank]. */
    private fun probeDates(
        startRank: Int,
        cookie: String,
        dsid: String,
        endpoint: String,
    ): List<Long> {
        val payload = queryPayload(
            recordType = RECORD_TYPE_DEFAULT,
            direction = "ASCENDING",
            startRank = startRank,
            desiredKeys = listOf("assetDate"),
        )
        val url = "$endpoint/records/query?${queryParams(store.clientId, dsid)}"
        val root = parseObject(postJson(url, payload.toString(), cookie), "iCloud 响应")
        ckError(root)?.let { throw errorFor(it) }
        val records = root.optJSONArray("records") ?: return emptyList()
        val dates = ArrayList<Long>(records.length())
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (record.optString("recordType") != "CPLAsset") continue
            val value = record.optJSONObject("fields")?.optJSONObject("assetDate")
                ?.optLong("value", 0L) ?: 0L
            if (value > 0) dates.add(value)
        }
        return dates
    }

    private fun MediaFilter.accepts(item: PhotoItem): Boolean = when (this) {
        MediaFilter.PHOTOS -> !item.isVideo
        MediaFilter.VIDEOS -> item.isVideo
        MediaFilter.FAVORITES -> item.favorite
        MediaFilter.ALL -> true
    }

    /**
     * Re-resolves a master record's signed media URLs. Used when a download URL
     * has expired (CDN 403/410) while the photo sits in the open viewer.
     */
    suspend fun refreshOriginalUrl(masterId: String, isVideo: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (masterId.isBlank()) return@withContext null
            val payload = JSONObject()
                .put("records", JSONArray().put(JSONObject().put("recordName", masterId)))
                .put("zoneID", zoneJson())
                .put("desiredKeys", JSONArray(MEDIA_URL_KEYS.toList()))
            val url = "${endpoint()}/records/lookup?${queryParams(store.clientId, requireDsid())}"
            val body = postJson(url, payload.toString(), requireCookie())
            val fields = parseObject(body, "iCloud lookup 响应")
                .optJSONArray("records")?.optJSONObject(0)?.optJSONObject("fields")
                ?: return@withContext null
            val keys = if (isVideo) {
                listOf("resOriginalVidComplRes", "resOriginalRes", "resVidMedRes")
            } else {
                listOf("resOriginalRes", "resJPEGFullRes")
            }
            keys.firstNotNullOfOrNull { key ->
                fields.optJSONObject(key)?.optJSONObject("value")
                    ?.optString("downloadURL")?.takeIf { it.startsWith("http") }
            }
        }

    // --------------------------------------------------------------- download

    suspend fun downloadToTempFile(url: String, filename: String): File = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) throw IcloudException("下载地址无效")
        val cookie = requireCookie()
        val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            .ifBlank { "icloud_${System.currentTimeMillis()}.bin" }
        val dir = File(cacheDir, "downloads").apply { if (!exists()) mkdirs() }
        val target = File(dir, "dl_${System.currentTimeMillis()}_$safe")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    when {
                        code == 401 -> throw IcloudException(
                            "iCloud 会话已过期，请重新登录",
                            unauthorized = true,
                            httpCode = code,
                        )
                        code == 403 || code == 410 -> throw IcloudException(
                            "下载链接已过期，请刷新列表后重试",
                            httpCode = code,
                        )
                        code == 429 || code >= 500 -> {
                            // One polite retry; iCloud rate-limits long download runs.
                            sleepQuietly(retryAfterMillis(response.header("Retry-After")))
                            http.newCall(request).execute().use { second ->
                                if (second.isSuccessful) {
                                    val b = second.body ?: throw IcloudException("下载失败：响应为空")
                                    target.outputStream().use { out -> b.byteStream().copyTo(out) }
                                    return@withContext target
                                }
                                throw httpError(second.code, second.body?.string().orEmpty(), isDownload = true)
                            }
                        }
                        else -> throw httpError(code, text, isDownload = true)
                    }
                }
                val body = response.body ?: throw IcloudException("下载失败：响应为空")
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        } catch (e: IOException) {
            target.delete()
            throw IcloudException("下载失败：${e.message ?: "网络错误"}")
        }
        target
    }

    // -------------------------------------------------------------- internals

    private fun requireCookie(): String =
        store.cookieHeader?.takeIf { it.isNotBlank() }
            ?: throw IcloudException("尚未登录 iCloud", unauthorized = true)

    private fun requireDsid(): String =
        store.dsid?.takeIf { it.isNotBlank() }
            ?: throw IcloudException("会话缺少账号信息，请重新登录", unauthorized = true)

    private fun endpoint(): String {
        val base = store.ckDatabaseUrl?.takeIf { it.startsWith("http") }
            ?: throw IcloudException("会话缺少服务地址，请重新登录", unauthorized = true)
        return "$base/database/1/$CONTAINER/production/private"
    }

    /** Query params Apple's setup endpoint expects — deliberately minimal. */
    private fun setupParams(): String {
        val parts = linkedMapOf(
            "clientBuildNumber" to CLIENT_BUILD,
            "clientMasteringNumber" to CLIENT_MASTERING,
            "clientId" to store.clientId,
        )
        return parts.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    private fun queryParams(clientId: String, dsid: String): String {
        val parts = linkedMapOf(
            "dsid" to dsid,
            "clientBuildNumber" to CLIENT_BUILD,
            "clientMasteringNumber" to CLIENT_MASTERING,
            "clientId" to clientId,
            // Literal "True"/"True" is what `urlencode({"remapEnums": True})` produces
            // in pyicloud; keep the capitalisation, Apple accepts it.
            "remapEnums" to "True",
            "getCurrentSyncToken" to "True",
        )
        return parts.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    private fun baseHeaders(cookie: String): Map<String, String> = linkedMapOf(
        "Cookie" to cookie,
        "Origin" to ORIGIN,
        "Referer" to "$ORIGIN/",
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )

    private fun postJson(
        url: String,
        body: String,
        cookie: String,
        retryAsGet: Boolean = false,
    ): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .apply { baseHeaders(cookie).forEach { (k, v) -> header(k, v) } }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) return text
                // Only a method/route mismatch is worth retrying as GET; a 400 means
                // the payload itself is wrong and a GET would hide it.
                if (retryAsGet && (response.code == 404 || response.code == 405)) {
                    return getJson(url, cookie)
                }
                throw httpError(response.code, text)
            }
        } catch (e: IOException) {
            throw IcloudException("网络请求失败：${e.message ?: "请检查网络连接"}（${e.javaClass.simpleName}）")
        }
    }

    private fun getJson(url: String, cookie: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { baseHeaders(cookie).forEach { (k, v) -> header(k, v) } }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) return text
                throw httpError(response.code, text)
            }
        } catch (e: IOException) {
            throw IcloudException("网络请求失败：${e.message ?: "请检查网络连接"}")
        }
    }

    private fun httpError(code: Int, body: String, isDownload: Boolean = false): IcloudException {
        val upper = body.uppercase()
        val authInBody = upper.contains("AUTHENTICATION_FAILED") ||
            upper.contains("AUTHENTICATION_REQUIRED") ||
            upper.contains("UNAUTHORIZED")
        // 421 = LOGIN_TOKEN_EXPIRED: Apple wants a fresh authentication, not a retry.
        val unauthorized = !isDownload && (code == 401 || code == 403 || code == 421 || authInBody)
        val hint = when {
            unauthorized -> "iCloud 登录状态已过期，请重新登录"
            isDownload && (code == 403 || code == 410) -> "下载链接已过期，请刷新列表后重试"
            code == 429 -> "请求过于频繁，请稍后再试（HTTP 429）"
            code >= 500 -> "iCloud 服务暂时不可用，请稍后重试（HTTP $code）"
            code == 421 -> "iCloud 要求重新路由，请稍后重试（HTTP 421）"
            else -> "iCloud 返回错误（HTTP $code）"
        }
        return IcloudException("$hint ${body.take(180)}".trim(), unauthorized, code)
    }

    private fun parseObject(body: String, what: String): JSONObject =
        runCatching { JSONObject(body) }.getOrElse {
            throw IcloudException("无法解析$what（${body.take(120)}）")
        }

    private class CkError(val code: String, val reason: String)

    /**
     * CloudKit reports failures inside HTTP 200 responses as flat top-level
     * fields (`serverErrorCode`, `reason`), not as a `serverError` object.
     */
    private fun ckError(obj: JSONObject): CkError? {
        val code = obj.optString("serverErrorCode").takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val reason = listOf("reason", "errorMessage", "errorReason", "error")
            .firstNotNullOfOrNull { key ->
                obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty()
        return CkError(code, reason)
    }

    private fun errorFor(error: CkError): IcloudException = when (error.code.uppercase()) {
        "AUTHENTICATION_FAILED", "AUTHENTICATION_REQUIRED", "UNAUTHORIZED" ->
            IcloudException("iCloud 会话已失效（${error.code}），请重新登录", unauthorized = true)
        "ZONE_NOT_FOUND" ->
            IcloudException("该账号的 iCloud 照片未开启或仍在初始化，请先在 icloud.com/photos 打开一次")
        "ACCESS_DENIED", "THROTTLED", "TRY_AGAIN_LATER", "ZONE_BUSY" ->
            IcloudException("iCloud 正在限流，请稍后重试（${error.code}）")
        else -> IcloudException("iCloud 返回错误：${error.code} ${error.reason.take(120)}".trim())
    }

    /** Library-wide asset count; used by the settings self-test and header total. */
    suspend fun totalCount(): Int? = withContext(Dispatchers.IO) {
        runCatching { fetchItemCount(endpoint(), requireCookie(), requireDsid()) }
            .getOrNull()?.takeIf { it >= 0 }
    }

    private fun fetchItemCount(endpoint: String, cookie: String, dsid: String): Int {
        val fieldValue = JSONObject()
            .put("type", "STRING_LIST")
            .put("value", JSONArray().put(COUNT_INDEX_ID))
        val query = JSONObject()
            .put("recordType", "HyperionIndexCountLookup")
            .put(
                "filterBy",
                JSONObject()
                    .put("fieldName", "indexCountID")
                    .put("comparator", "IN")
                    .put("fieldValue", fieldValue),
            )
        val payload = JSONObject().put(
            "batch",
            JSONArray().put(
                JSONObject()
                    .put("resultsLimit", 1)
                    .put("query", query)
                    // pyicloud sends zoneWide *and* zoneID here; Apple accepts both.
                    .put("zoneWide", true)
                    .put("zoneID", zoneJson()),
            ),
        )
        val url = "$endpoint/internal/records/query/batch?${queryParams(store.clientId, dsid)}"
        val body = postJson(url, payload.toString(), cookie)
        val root = parseObject(body, "iCloud 计数响应")
        return runCatching {
            root.getJSONArray("batch").getJSONObject(0)
                .getJSONArray("records").getJSONObject(0)
                .getJSONObject("fields").getJSONObject("itemCount").getInt("value")
        }.getOrElse { -1 }
    }

    private fun zoneJson(): JSONObject = JSONObject()
        .put("zoneName", ZONE_NAME)
        .put("zoneType", "REGULAR_CUSTOM_ZONE")

    private fun queryPayload(
        recordType: String,
        direction: String,
        startRank: Int,
        extraFilters: List<JSONObject> = emptyList(),
        desiredKeys: List<String> = DESIRED_KEYS,
    ): JSONObject {
        val directionFilter = JSONObject()
            .put("comparator", "EQUALS")
            .put("fieldName", "direction")
            .put("fieldValue", JSONObject().put("type", "STRING").put("value", direction))
        val rankFilter = JSONObject()
            .put("comparator", "EQUALS")
            .put("fieldName", "startRank")
            .put("fieldValue", JSONObject().put("type", "INT64").put("value", startRank))
        val filters = JSONArray().put(directionFilter).put(rankFilter)
        extraFilters.forEach { filters.put(it) }
        return JSONObject()
            .put("query", JSONObject().put("recordType", recordType).put("filterBy", filters))
            .put("zoneID", zoneJson())
            .put("desiredKeys", JSONArray(desiredKeys.toList()))
            .put("resultsLimit", PAGE_SIZE * 2)
    }

    private class ParsedRecords(val items: List<PhotoItem>, val masterCount: Int)

    /**
     * Parses one query page.
     *
     * The paging window is measured in master records, so a page boundary can split a
     * CPLAsset/CPLMaster pair: the asset arrives without its master and would silently
     * be dropped, which makes counts (and the last photo of every page) go missing.
     * [masterLookup] fetches those stragglers by recordName so nothing is lost.
     */
    private fun parseRecords(
        body: String,
        masterLookup: ((List<String>) -> Map<String, JSONObject>)? = null,
    ): ParsedRecords {
        val root = parseObject(body, "iCloud 响应")
        ckError(root)?.let { throw errorFor(it) }
        val records = root.optJSONArray("records")
            ?: throw IcloudException("iCloud 响应缺少 records（${body.take(120)}）")

        val masters = HashMap<String, JSONObject>()
        val assets = ArrayList<JSONObject>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (ckError(record) != null) continue // per-record error item
            when (record.optString("recordType")) {
                "CPLMaster" -> {
                    val name = record.optString("recordName")
                        .takeIf { it.isNotBlank() && it != "null" } ?: continue
                    masters[name] = record
                }
                "CPLAsset" -> assets.add(record)
                else -> Unit
            }
        }

        val pending = ArrayList<Pair<JSONObject, String>>(2)
        for (asset in assets) {
            val masterName = asset.optJSONObject("fields")
                ?.optJSONObject("masterRef")?.optJSONObject("value")
                ?.optString("recordName")?.takeIf { it.isNotBlank() && it != "null" }
                ?: continue
            if (masters.containsKey(masterName)) continue
            pending.add(asset to masterName)
        }
        if (pending.isNotEmpty() && masterLookup != null) {
            val missing = pending.map { it.second }.distinct()
            runCatching { masterLookup(missing) }.getOrNull()?.let { fetched ->
                fetched.forEach { (name, record) -> masters[name] = record }
            }
        }

        val items = ArrayList<PhotoItem>(masters.size)
        for (asset in assets) {
            val masterName = asset.optJSONObject("fields")
                ?.optJSONObject("masterRef")?.optJSONObject("value")
                ?.optString("recordName")?.takeIf { it.isNotBlank() && it != "null" }
                ?: continue
            val master = masters[masterName] ?: continue
            buildItem(asset, master)?.let { items.add(it) }
        }
        // The paging window is measured in master records (one per asset pair),
        // not in "successfully parsed items": a duplicated asset shares a master.
        return ParsedRecords(items, masters.size)
    }

    /** Fetches master records that fell on the other side of a page boundary. */
    private fun lookupMasters(
        names: List<String>,
        cookie: String,
        dsid: String,
        endpoint: String,
    ): Map<String, JSONObject> {
        if (names.isEmpty()) return emptyMap()
        val wrapped = names.distinct().take(200).map { name ->
            JSONObject().put("recordName", name)
        }
        val payload = JSONObject()
            .put("records", JSONArray(wrapped))
            .put("zoneID", zoneJson())
            .put("desiredKeys", JSONArray(DESIRED_KEYS.toList()))
        val url = "$endpoint/records/lookup?${queryParams(store.clientId, dsid)}"
        val root = parseObject(postJson(url, payload.toString(), cookie), "iCloud 响应")
        ckError(root)?.let { throw errorFor(it) }
        val records = root.optJSONArray("records") ?: return emptyMap()
        val result = HashMap<String, JSONObject>(records.length())
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (ckError(record) != null) continue
            if (record.optString("recordType") != "CPLMaster") continue
            val name = record.optString("recordName")
                .takeIf { it.isNotBlank() && it != "null" } ?: continue
            result[name] = record
        }
        return result
    }

    private fun buildItem(asset: JSONObject, master: JSONObject): PhotoItem? {
        val af = asset.optJSONObject("fields") ?: return null
        val mf = master.optJSONObject("fields") ?: return null

        fun text(obj: JSONObject, key: String): String? =
            obj.optJSONObject(key)?.optString("value")
                ?.takeIf { it.isNotBlank() && it != "null" }

        fun number(obj: JSONObject, key: String): Long =
            obj.optJSONObject(key)?.optLong("value", 0L) ?: 0L

        fun assetUrl(obj: JSONObject, key: String): String? =
            obj.optJSONObject(key)?.optJSONObject("value")
                ?.optString("downloadURL")
                ?.takeIf { it.startsWith("http") }

        // Deleted photos can linger in the index for a while.
        if (number(af, "isDeleted") == 1L) return null

        val recordName = asset.optString("recordName")
            .takeIf { it.isNotBlank() && it != "null" } ?: return null
        val filename = decodeFilename(text(mf, "filenameEnc")) ?: (recordName + ".jpg")
        val itemType = text(mf, "itemType").orEmpty()
        val fileType = text(mf, "resOriginalFileType").orEmpty()
        val lowerName = filename.lowercase()
        val isVideo = itemType in VIDEO_TYPES || fileType in VIDEO_TYPES ||
            lowerName.endsWith(".mov") || lowerName.endsWith(".mp4") ||
            lowerName.endsWith(".m4v")

        val thumb = assetUrl(mf, "resJPEGThumbRes") ?: assetUrl(mf, "resJPEGMedRes")
        val preview = if (isVideo) {
            assetUrl(mf, "resJPEGFullRes") ?: assetUrl(mf, "resJPEGMedRes") ?: thumb
        } else {
            assetUrl(mf, "resJPEGMedRes") ?: assetUrl(mf, "resJPEGFullRes") ?: thumb
        }
        val original = if (isVideo) {
            assetUrl(mf, "resOriginalVidComplRes")
                ?: assetUrl(mf, "resOriginalRes")
                ?: assetUrl(mf, "resVidMedRes")
        } else {
            assetUrl(mf, "resOriginalRes") ?: assetUrl(mf, "resJPEGFullRes")
        }
        // Videos stream from the medium rendition (much smaller than the original).
        val stream = if (isVideo) {
            assetUrl(mf, "resVidMedRes")
                ?: assetUrl(mf, "resVidSmallRes")
                ?: assetUrl(mf, "resOriginalVidComplRes")
                ?: assetUrl(mf, "resOriginalRes")
        } else {
            null
        }
        val takenAt = number(af, "assetDate").takeIf { it > 0 } ?: number(af, "addedDate")
        val addedAt = number(af, "addedDate").takeIf { it > 0 } ?: takenAt
        val favorite = run {
            val raw = af.optJSONObject("isFavorite")
            when {
                raw == null -> false
                raw.optBoolean("value", false) -> true
                else -> raw.optLong("value", 0L) == 1L
            }
        }
        // `duration` is milliseconds (verified on a live account: 45120 ↔ 45.12s
        // for a 573 MB clip; interpreting it as seconds yields ~12.5 hours).
        val durationSec = (af.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0) / 1000.0
            .coerceAtLeast(0.0)
        val mime = mimeFor(fileType.ifBlank { itemType }, isVideo)

        return PhotoItem(
            id = recordName,
            masterId = master.optString("recordName")
                .takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            filename = filename,
            takenAt = takenAt,
            addedAt = addedAt,
            favorite = favorite,
            mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            durationSec = durationSec,
            width = number(mf, "resOriginalWidth").toInt(),
            height = number(mf, "resOriginalHeight").toInt(),
            thumbUrl = thumb,
            previewUrl = preview,
            originalUrl = original,
            streamUrl = stream,
            fileSize = mf.optJSONObject("resOriginalRes")?.optJSONObject("value")
                ?.optLong("size", 0L) ?: 0L,
            mimeType = mime,
        )
    }

    private fun decodeFilename(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        val cleaned = encoded.filterNot { it.isWhitespace() }
        return runCatching {
            String(java.util.Base64.getDecoder().decode(cleaned), Charsets.UTF_8)
        }.getOrNull()?.takeIf { decoded ->
            decoded.isNotBlank() && decoded.none { it.code < 32 }
        }
    }

    private fun mimeFor(appleType: String, isVideo: Boolean): String = when (appleType) {
        "public.heic" -> "image/heic"
        "public.heif" -> "image/heif"
        "public.jpeg" -> "image/jpeg"
        "public.png" -> "image/png"
        "public.tiff" -> "image/tiff"
        "com.apple.quicktime-movie" -> "video/quicktime"
        "public.mpeg-4" -> "video/mp4"
        "com.apple.m4v-video" -> "video/x-m4v"
        else -> if (isVideo) "video/mp4" else "image/jpeg"
    }

    private fun retryAfterMillis(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: 1L
        return (seconds.coerceIn(1L, 5L)) * 1000L
    }

    private fun sleepQuietly(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }

    private companion object {
        const val ORIGIN = "https://www.icloud.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
        const val CLIENT_BUILD = "2534Project66"
        const val CLIENT_MASTERING = "2534B22"
        const val CONTAINER = "com.apple.photos.cloud"
        const val ZONE_NAME = "PrimarySync"
        const val RECORD_TYPE_DEFAULT = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"
        const val COUNT_INDEX_ID = "CPLAssetByAssetDateWithoutHiddenOrDeleted"
        const val PAGE_SIZE = 100

        /** Evenly spaced ranks probed per round when locating a date boundary. */
        const val PROBE_SPLITS = 16
        const val MAX_CACHED_BOUNDARIES = 240
        /** Bumped whenever the locator changes so stale boundary values are ignored. */
        const val CACHE_VERSION = "v2"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val VIDEO_TYPES = setOf(
            "com.apple.quicktime-movie",
            "public.mpeg-4",
            "com.apple.m4v-video",
            "public.mpeg",
        )
        val MEDIA_URL_KEYS = listOf(
            "resOriginalRes", "resJPEGThumbRes", "resJPEGMedRes", "resJPEGFullRes",
            "resVidMedRes", "resOriginalVidComplRes",
        )

        /** Exact desiredKeys list used by pyicloud's Photos indexes. */
        val DESIRED_KEYS = listOf(
            "resJPEGFullWidth", "resJPEGFullHeight", "resJPEGFullFileType",
            "resJPEGFullFingerprint", "resJPEGFullRes",
            "resJPEGLargeWidth", "resJPEGLargeHeight", "resJPEGLargeFileType",
            "resJPEGLargeFingerprint", "resJPEGLargeRes",
            "resJPEGMedWidth", "resJPEGMedHeight", "resJPEGMedFileType",
            "resJPEGMedFingerprint", "resJPEGMedRes",
            "resJPEGThumbWidth", "resJPEGThumbHeight", "resJPEGThumbFileType",
            "resJPEGThumbFingerprint", "resJPEGThumbRes",
            "resVidFullWidth", "resVidFullHeight", "resVidFullFileType",
            "resVidFullFingerprint", "resVidFullRes",
            "resVidMedWidth", "resVidMedHeight", "resVidMedFileType",
            "resVidMedFingerprint", "resVidMedRes",
            "resVidSmallWidth", "resVidSmallHeight", "resVidSmallFileType",
            "resVidSmallFingerprint", "resVidSmallRes",
            "resSidecarWidth", "resSidecarHeight", "resSidecarFileType",
            "resSidecarFingerprint", "resSidecarRes",
            "itemType", "dataClassType", "filenameEnc", "originalOrientation",
            "resOriginalWidth", "resOriginalHeight", "resOriginalFileType",
            "resOriginalFingerprint", "resOriginalRes",
            "resOriginalAltWidth", "resOriginalAltHeight", "resOriginalAltFileType",
            "resOriginalAltFingerprint", "resOriginalAltRes",
            "resOriginalVidComplWidth", "resOriginalVidComplHeight",
            "resOriginalVidComplFileType", "resOriginalVidComplFingerprint",
            "resOriginalVidComplRes",
            "isDeleted", "isExpunged", "dateExpunged", "remappedRef",
            "recordName", "recordType", "recordChangeTag", "masterRef",
            "adjustmentRenderType", "assetDate", "addedDate", "isFavorite",
            "isHidden", "orientation", "duration", "assetSubtype", "assetSubtypeV2",
            "assetHDRType", "burstFlags", "burstFlagsExt", "burstId", "captionEnc",
            "locationEnc", "locationV2Enc", "locationLatitude", "locationLongitude",
            "adjustmentType", "timeZoneOffset", "vidComplDurValue",
            "vidComplDurScale", "vidComplDispValue", "vidComplDispScale",
            "vidComplVisibilityState", "customRenderedValue", "containerId",
            "itemId", "position", "isKeyAsset",
        )
    }
}
