package com.icloudalbum.app.data

import android.content.Context

/**
 * Persists the cookies harvested from the iCloud web login plus the metadata every
 * CloudKit call needs (DSID, webservice URL, setup host, client id).
 *
 * Only the session cookie is stored — the Apple ID password never touches the app, and
 * nothing is uploaded anywhere. Data lives in the app-private SharedPreferences (it is
 * not encrypted; uninstalling the app removes it).
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("icloud_session", Context.MODE_PRIVATE)

    var cookieHeader: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    var dsid: String?
        get() = prefs.getString(KEY_DSID, null)
        set(value) = prefs.edit().putString(KEY_DSID, value).apply()

    var fullName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    /** Per-account CloudKit endpoint, e.g. `https://pXX-ckdatabasews.icloud.com`. */
    var ckDatabaseUrl: String?
        get() = prefs.getString(KEY_CK_URL, null)
        set(value) = prefs.edit().putString(KEY_CK_URL, value).apply()

    /** Photos webservice endpoint from validate; kept for diagnostics. */
    var photosWsUrl: String?
        get() = prefs.getString(KEY_PHOTOS_URL, null)
        set(value) = prefs.edit().putString(KEY_PHOTOS_URL, value).apply()

    /**
     * setup host that accepted the session: `setup.icloud.com` for global accounts,
     * `setup.icloud.com.cn` for China-region accounts. Cookies are domain-scoped, so
     * the wrong host answers 421 even for a perfectly valid session.
     */
    var setupHost: String
        get() = prefs.getString(KEY_SETUP_HOST, null) ?: "setup.icloud.com"
        set(value) = prefs.edit().putString(KEY_SETUP_HOST, value).apply()

    /**
     * Cached date→rank boundaries for the assetDate index
     * (`[d|x]:dateMs:rank:total:version`). New photos only enter at the newest end, so a
     * stored rank is corrected by the library-size delta on read.
     */
    var rankCacheEntries: Set<String>
        get() = prefs.getStringSet(KEY_RANK_CACHE, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_RANK_CACHE, value).apply()

    /** Total number of photos in the library, shown in the gallery header. */
    var libraryCount: Int?
        get() = prefs.getInt(KEY_LIBRARY_COUNT, -1).takeIf { it >= 0 }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_LIBRARY_COUNT).apply()
            } else {
                prefs.edit().putInt(KEY_LIBRARY_COUNT, value).apply()
            }
        }

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    val isLoggedIn: Boolean
        get() = !cookieHeader.isNullOrBlank() && !dsid.isNullOrBlank()

    fun saveSession(cookie: String, dsid: String, name: String?) {
        prefs.edit()
            .putString(KEY_COOKIE, cookie)
            .putString(KEY_DSID, dsid)
            .putString(KEY_NAME, name)
            .apply()
    }

    /** Clears session data but keeps the stable client id. */
    fun clear() {
        val clientId = clientId
        prefs.edit().clear().apply()
        this.clientId = clientId
    }

    private companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_DSID = "dsid"
        const val KEY_NAME = "name"
        const val KEY_CK_URL = "ck_url"
        const val KEY_PHOTOS_URL = "photos_url"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_SETUP_HOST = "setup_host"
        const val KEY_RANK_CACHE = "rank_cache"
        const val KEY_LIBRARY_COUNT = "library_count"
    }
}
