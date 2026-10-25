package com.icloudalbum.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.icloudalbum.app.data.IcloudApi
import com.icloudalbum.app.data.IcloudPhotoRepository
import com.icloudalbum.app.data.PhotoRepository
import com.icloudalbum.app.data.SessionStore
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File

class App : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Lets the login page be inspected from chrome://inspect on a debug build.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        container = AppContainer(this)
    }

    /**
     * Thumbnails come from Apple's CDN as signed URLs. Signatures alone seem to be
     * enough today, but the iCloud web app always sends the session cookie plus
     * Origin/Referer, so we mirror that for every *.icloud host instead of hoping
     * the CDN keeps accepting bare requests.
     *
     * The loader is tuned for a 100-cell scrolling grid: upper-limit memory cache,
     * a large disk cache (thumbnails survive app restarts), no crossfade and a
     * wider HTTP dispatcher, since the signed URLs rotate every hour and a cold
     * start would otherwise refetch every thumbnail.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 16
                    },
                )
                .addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val cookie = container.sessionStore.cookieHeader.orEmpty()
                    if (cookie.isNotBlank() && host.contains("icloud")) {
                        chain.proceed(
                            request.newBuilder()
                                .header("Cookie", cookie)
                                .header("Origin", "https://www.icloud.com")
                                .header("Referer", "https://www.icloud.com/")
                                .header("User-Agent", CDN_USER_AGENT)
                                .header("Accept", "*/*")
                                .build(),
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "image_cache"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .allowHardware(true)
        .build()

    private companion object {
        const val CDN_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
    }
}

/** Tiny manual DI container — avoids Hilt/kapt to keep the build fast and simple. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val sessionStore: SessionStore = SessionStore(appContext)
    val api: IcloudApi = IcloudApi(sessionStore, appContext.cacheDir)
    val repository: PhotoRepository = IcloudPhotoRepository(appContext, sessionStore, api)
}
