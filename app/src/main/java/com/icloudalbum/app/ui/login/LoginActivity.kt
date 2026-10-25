package com.icloudalbum.app.ui.login

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.icloudalbum.app.App
import com.icloudalbum.app.data.AuthState
import com.icloudalbum.app.data.PhotoRepository
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOGIN_URL_CN = "https://www.icloud.com.cn/photos/"
private const val LOGIN_URL_GLOBAL = "https://www.icloud.com/photos/"
private const val DIAG_TAG = "IcloudLoginDiag"

/**
 * Full-screen native login host.
 *
 * Deliberately a plain Activity with a classic WebView and *no Compose*: on some
 * devices a WebView hosted in Compose's AndroidView paints nothing while its JS
 * runs normally, which looks exactly like "the login page never loads".
 */
class LoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var stageText: TextView
    private lateinit var diagScroll: ScrollView
    private lateinit var diagText: TextView

    private val consoleLog = ArrayDeque<String>()
    private val errorLog = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())

    private var probeJson: JSONObject? = null
    private var iframeInfo = "无 iframe"
    private var lastUrl = ""
    private var waitedSeconds = 0
    private var captured = false
    private var finishedLogin = false
    private var loginStep = "等待页面"
    private var chinaRegion = true
    private var uaMode = UaMode.REAL
    private var defaultUserAgent = ""
    private var softwareRendering = false

    private val repo: PhotoRepository get() = (application as App).container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = Color.parseColor("#121216")
        window.setBackgroundDrawable(ColorDrawable(dark))
        window.statusBarColor = dark
        window.navigationBarColor = dark
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        // Must run *after* setContentView: window.insetsController needs a DecorView,
        // and touching it earlier throws NullPointerException on some OEM builds.
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        startLoop()
    }

    // ------------------------------------------------------------------- UI

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121216"))
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }

        titleText = TextView(this).apply {
            text = "登录 iCloud"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleText)

        statusText = TextView(this).apply {
            text = "正在加载 iCloud 登录页…"
            setTextColor(Color.parseColor("#B8B8C0"))
            textSize = 13f
        }
        root.addView(statusText)

        stageText = TextView(this).apply {
            setTextColor(Color.parseColor("#8E8E96"))
            textSize = 11f
            setPadding(0, dp(2), 0, dp(4))
        }
        root.addView(stageText)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(Color.BLACK)
            setupWebView(this)
        }
        root.addView(webView)

        diagScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(170),
            )
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#1E1E24"))
        }
        diagText = TextView(this).apply {
            setTextColor(Color.parseColor("#C9C9D1"))
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        diagScroll.addView(diagText)
        root.addView(diagScroll)

        root.addView(buttonRow(
            "刷新" to { reloadPage() },
            "区域" to { switchRegion() },
            "UA" to { switchUa() },
            "诊断" to { toggleDiag() },
        ))
        root.addView(buttonRow(
            "复制诊断" to { copyDiagnostics() },
            "清 Cookie" to { clearCookies() },
            "软渲染" to { toggleSoftwareRendering() },
            "我已完成登录" to { checkNow(force = true) },
        ))

        setContentView(root)
        // Android 15 enforces edge-to-edge for targetSdk 35; include IME so the
        // Apple ID field stays visible when the keyboard opens.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(dp(12) + bars.left, dp(10) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            insets
        }
    }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        buttons.forEach { (label, action) ->
            row.addView(Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                setTextColor(Color.parseColor("#9CCAFF"))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    // --------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            // WebSettings has no getter for this one, so call the setter directly.
            setSupportMultipleWindows(true)
            // Mobile layout: keep the CSS viewport equal to the WebView width.
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
            defaultUserAgent = userAgentString
            uaMode.value?.let { userAgentString = it }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(v, url, favicon)
                statusText.text = "页面加载中…"
            }

            override fun shouldOverrideUrlLoading(
                v: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                return scheme.isNotEmpty() && scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                lastUrl = url.orEmpty()
                // Apple redirects China-region accounts to icloud.com.cn; follow it.
                val host = runCatching { Uri.parse(lastUrl).host.orEmpty() }.getOrDefault("")
                when {
                    host.endsWith("icloud.com.cn") -> chinaRegion = true
                    host.endsWith("icloud.com") -> chinaRegion = false
                }
                CookieManager.getInstance().flush()
                statusText.text = "页面已加载"
                v?.scrollTo(0, 0)
                v?.postDelayed({ probe(v) }, 1500)
                updateStage()
                checkNow()
            }

            override fun onReceivedError(
                v: WebView?,
                request: WebResourceRequest?,
                err: WebResourceError?,
            ) {
                super.onReceivedError(v, request, err)
                addLog(errorLog, "ERR ${request?.url}: ${err?.description}")
                if (request?.isForMainFrame == true) {
                    statusText.text = "网页加载失败：${err?.description}"
                }
            }

            override fun onReceivedHttpError(
                v: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?,
            ) {
                super.onReceivedHttpError(v, request, response)
                addLog(errorLog, "HTTP ${response?.statusCode} ${request?.url}")
            }

            override fun onReceivedSslError(
                v: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                val host = runCatching { Uri.parse(error?.url?.toString() ?: "").host.orEmpty() }
                    .getOrDefault("")
                addLog(errorLog, "SSL $host primary=${error?.primaryError}")
                if (host.contains("apple") || host.contains("icloud")) handler?.proceed() else handler?.cancel()
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                if (newProgress >= 100) CookieManager.getInstance().flush()
                if (newProgress in 1..99) stageText.text = "加载中 $newProgress%"
            }

            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                message ?: return false
                addLog(consoleLog, "${message.messageLevel()} ${message.message()}")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onCreateWindow(
                v: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(v?.context ?: this@LoginActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        pv: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val target = request?.url?.toString() ?: return false
                        addLog(errorLog, "popup -> $target")
                        webView.loadUrl(target)
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        view.loadUrl(loginUrl())
    }

    private fun loginUrl(): String = if (chinaRegion) LOGIN_URL_CN else LOGIN_URL_GLOBAL

    private fun reloadPage() {
        waitedSeconds = 0
        loginStep = "等待页面"
        iframeInfo = "无 iframe"
        statusText.text = "正在重新加载…"
        webView.reload()
    }

    private fun switchRegion() {
        chinaRegion = !chinaRegion
        waitedSeconds = 0
        loginStep = "等待页面"
        iframeInfo = "无 iframe"
        statusText.text = "已切换到${if (chinaRegion) "中国区（icloud.com.cn）" else "全球（icloud.com）"}，正在加载…"
        webView.loadUrl(loginUrl())
    }

    private fun switchUa() {
        uaMode = UaMode.values()[(uaMode.ordinal + 1) % UaMode.values().size]
        webView.settings.userAgentString = uaMode.value ?: defaultUserAgent
        waitedSeconds = 0
        statusText.text = "已切换 UA：${uaMode.label}，正在重载…"
        webView.reload()
    }

    /**
     * Some OEM WebView builds composite into a hardware layer that never reaches
     * the screen (page JS works, pixels stay blank). A software layer is slower but
     * paints unconditionally, which makes it a useful last-resort switch.
     */
    private fun toggleSoftwareRendering() {
        softwareRendering = !softwareRendering
        webView.setLayerType(
            if (softwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE,
            null,
        )
        statusText.text = if (softwareRendering) {
            "已切换为软件渲染，正在重新加载…"
        } else {
            "已恢复硬件渲染，正在重新加载…"
        }
        waitedSeconds = 0
        webView.reload()
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        waitedSeconds = 0
        statusText.text = "已清除 Cookie 和缓存，重新加载…"
        webView.loadUrl(loginUrl())
    }

    private fun toggleDiag() {
        val show = diagScroll.visibility != View.VISIBLE
        diagScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (show) diagText.text = buildDiagnostics()
    }

    private fun copyDiagnostics() {
        val text = buildDiagnostics()
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("iCloud 相册诊断", text))
        // Mirror to logcat so the diagnostics can be read over adb too.
        android.util.Log.i(DIAG_TAG, text)
        Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show()
        if (diagScroll.visibility == View.VISIBLE) diagText.text = text
    }

    private fun openInBrowser() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl()))) }
    }

    // --------------------------------------------------------------- capture

    private fun cookiePairs(host: String): List<Pair<String, String>> {
        val raw = CookieManager.getInstance().getCookie("https://$host") ?: return emptyList()
        return raw.split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
        }.filter { it.first.isNotEmpty() }
    }

    /**
     * iCloud China and the global site are separate cookie jars: `.icloud.com.cn`
     * vs `.icloud.com`. Merge both, region first, so whichever site the user signed
     * in on still yields a usable Cookie header.
     */
    private fun mergedCookieHeader(): String? {
        val hosts = listOf("www.icloud.com.cn", "icloud.com.cn", "www.icloud.com", "icloud.com")
        val merged = LinkedHashMap<String, String>()
        hosts.forEach { host ->
            cookiePairs(host).forEach { (name, value) ->
                if (!merged.containsKey(name)) merged[name] = value
            }
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun hasSession(raw: String): Boolean =
        raw.contains("X-APPLE-WEBAUTH-USER") || raw.contains("X-APPLE-WEBAUTH-TOKEN")

    private fun checkNow(force: Boolean = false) {
        if (captured) return
        val raw = mergedCookieHeader()
        if (raw == null || !hasSession(raw)) {
            if (force) {
                val domain = if (chinaRegion) "icloud.com.cn" else "icloud.com"
                statusText.text = "还没检测到登录 Cookie（域 $domain）：请先在页面里完成 Apple ID 登录"
                Toast.makeText(this, "还没检测到登录 Cookie", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val onPhotos = lastUrl.contains("photos")
        val hasPcs = raw.contains("WEBAUTH-PCS") || raw.contains("X-APPLE-HSA-TRUST")
        if (!force && !hasPcs && !onPhotos && waitedSeconds < 8) return
        submit(raw)
    }

    private fun submit(raw: String) {
        if (captured) return
        captured = true
        statusText.text = "已获取 iCloud 会话，正在校验…"
        lifecycleScope.launch {
            try {
                val state = repo.attachSession(raw, dsidFromCookie(raw))
                if (state == AuthState.LOGGED_IN) {
                    finishedLogin = true
                    setResult(RESULT_OK)
                    finish()
                } else {
                    captured = false
                    statusText.text = "会话校验未通过，请重新登录"
                }
            } catch (t: Throwable) {
                captured = false
                statusText.text = "登录校验失败：${t.message ?: "网络错误"}"
                addLog(errorLog, "attach: ${t.message}")
            }
        }
    }

    private fun startLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || finishedLogin) return
                waitedSeconds++
                checkNow()
                if (!captured) {
                    webView.evaluateJavascript(AUTO_LOGIN_JS) { raw -> onProbe(parse(raw)) }
                }
                updateStage()
                handler.postDelayed(this, 1200)
            }
        })
    }

    private fun updateStage() {
        stageText.text = "$loginStep · 已等待 ${waitedSeconds}s · $iframeInfo"
        if (waitedSeconds == 45 && !captured) {
            statusText.text = "Apple 登录表单 45 秒仍未出现，可点「诊断 → 复制诊断」发我"
        }
    }

    private fun onProbe(json: JSONObject?) {
        json ?: return
        if (json.has("error")) {
            addLog(errorLog, "JS: ${json.optString("error")}")
            return
        }
        probeJson = json
        val frames = json.optJSONArray("iframes")
        iframeInfo = if (frames == null || frames.length() == 0) {
            "无 iframe"
        } else {
            val f = frames.optJSONObject(0)
            "iframe×${frames.length()} ${f?.optInt("w") ?: 0}x${f?.optInt("h") ?: 0}"
        }
        val clicked = json.optBoolean("clicked")
        val text = json.optString("text")
        loginStep = when {
            clicked && (frames == null || frames.length() == 0) -> "已点击登录，等待 Apple iframe"
            frames != null && frames.length() > 0 -> "Apple 登录表单已加载"
            text.contains("登录") -> "已渲染落地页，等待点击"
            else -> "页面渲染中"
        }
        if (frames != null && frames.length() > 0 && !captured &&
            statusText.text.startsWith("页面已加载")
        ) {
            statusText.text = "请在下方输入 Apple ID 登录"
        }
        if (json.has("url")) lastUrl = json.optString("url")
    }

    /** Runs the page probe and refreshes title/stage. */
    private fun probe(view: WebView) {
        view.evaluateJavascript(PROBE_JS) { raw ->
            onProbe(parse(raw))
            probeJson?.let { json ->
                val url = json.optString("url")
                if (url.isNotBlank()) lastUrl = url
            }
        }
    }

    // ----------------------------------------------------------- diagnostics

    private fun buildDiagnostics(): String = buildString {
        appendLine("iCloud 相册 登录诊断 ${timeNow()}")
        appendLine("版本 1.0.8 (versionCode 9)")
        appendLine("WebView: ${webViewVersion()}")
        appendLine("UA 模式: ${uaMode.label}")
        appendLine("实际 UA: ${webView.settings.userAgentString}")
        appendLine("WebView 尺寸: ${webView.width}x${webView.height} contentHeight=${webView.contentHeight} visibility=${webView.visibility}")
        appendLine("layerType=${webView.layerType} software=$softwareRendering")
        appendLine("区域: ${if (chinaRegion) "中国区 icloud.com.cn" else "全球 icloud.com"}")
        appendLine("进度: $loginStep 已等待: ${waitedSeconds}s")
        appendLine("URL: $lastUrl")
        probeJson?.let { p ->
            appendLine("页面标题: ${p.optString("title")}")
            appendLine("页面 UA: ${p.optString("ua")}")
            appendLine("brands: ${p.optString("brands")}")
            appendLine("inner=${p.optString("inner")} client=${p.optString("client")} scrollY=${p.optInt("scrollY")} dpr=${p.optString("dpr")}")
            appendLine("text: ${p.optString("text")}")
            appendLine("features: replaceAll=${p.optString("replaceAll")} promiseAny=${p.optString("promiseAny")} mathml=${p.optString("mathml")}")
        }
        appendLine("iframe: $iframeInfo")
        appendLine("cookie .com.cn: ${CookieManager.getInstance().getCookie("https://www.icloud.com.cn")?.length ?: 0}")
        appendLine("cookie .com: ${CookieManager.getInstance().getCookie("https://www.icloud.com")?.length ?: 0}")
        appendLine("merged cookie: ${mergedCookieHeader()?.length ?: 0}")
        appendLine("idmsa cookie: ${CookieManager.getInstance().getCookie("https://idmsa.apple.com")?.length ?: 0}")
        appendLine("session cookie: ${if (mergedCookieHeader()?.let(::hasSession) == true) "有" else "无"}")
        appendLine("-- 网络错误 --")
        errorLog.forEach { appendLine(it) }
        appendLine("-- 控制台 --")
        consoleLog.forEach { appendLine(it) }
    }

    private fun addLog(target: ArrayDeque<String>, line: String) {
        if (target.size >= 20) target.removeFirst()
        target.addLast("${timeNow()}  $line")
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun webViewVersion(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.versionName.orEmpty()
        } else {
            ""
        }
    }.getOrDefault("")

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (this::webView.isInitialized) {
            runCatching {
                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    private companion object {
        fun parse(raw: String?): JSONObject? {
            val text = runCatching { (JSONTokener(raw).nextValue() as? String) ?: raw }.getOrNull()
                ?: return null
            return runCatching { JSONObject(text) }.getOrNull()
        }
    }

    private enum class UaMode(val label: String, val value: String?) {
        REAL("真实 UA", null),
        IPHONE(
            "iPhone Safari",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        ),
        DESKTOP(
            "桌面 Safari",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.3.1 Safari/605.1.15",
        ),
    }
}

private const val PROBE_JS = """
(function(){
  try{
    var frames=[];
    document.querySelectorAll('iframe').forEach(function(f){
      var r=f.getBoundingClientRect();
      frames.push({src:(f.src||'').slice(0,160), w:Math.round(r.width), h:Math.round(r.height)});
    });
    var brands='';
    try{ brands=JSON.stringify((navigator.userAgentData&&navigator.userAgentData.brands)||[]); }catch(e0){}
    return JSON.stringify({
      ua:navigator.userAgent,
      brands:brands,
      url:location.href,
      title:(document.title||'').slice(0,120),
      text:(document.documentElement.innerText||'').replace(/\s+/g,' ').slice(0,200),
      iframes:frames,
      inner:[window.innerWidth,window.innerHeight],
      client:[document.documentElement.clientWidth,document.documentElement.clientHeight],
      scrollY:Math.round(window.scrollY||0),
      dpr:window.devicePixelRatio,
      clicked:!!window.__icClicked,
      replaceAll:typeof ''.replaceAll,
      promiseAny:typeof Promise.any,
      mathml:typeof MathMLElement
    });
  }catch(e){ return JSON.stringify({error:String(e)}); }
})()
"""

private const val AUTO_LOGIN_JS = """
(function(){
  try{
    function deep(root, acc, d){
      if(!root||d>8) return acc;
      var els=root.querySelectorAll('*');
      for(var i=0;i<els.length;i++){
        var e=els[i];
        if(e.shadowRoot) deep(e.shadowRoot, acc, d+1);
        var tag=e.tagName.toLowerCase();
        if(tag==='button'||tag==='a'||(e.getAttribute&&e.getAttribute('role')==='button')) acc.push(e);
      }
      return acc;
    }
    var clicked=false;
    if(!window.__icClicked){
      var cands=deep(document,[],0);
      function label(e){ return ((e.innerText||e.textContent||'')+' '+((e.getAttribute&&e.getAttribute('aria-label'))||'')).replace(/\s+/g,' ').trim(); }
      var btn=cands.find(function(e){
        var t=label(e);
        return /^\s*(登录|登入|Sign In|sign in)\s*$/i.test(t) || /通过 Apple 账户登录/.test(t);
      });
      if(btn){ btn.click(); var inner=btn.querySelector&&btn.querySelector('button'); if(inner) inner.click(); window.__icClicked=true; clicked=true; }
    }
    var frames=[];
    document.querySelectorAll('iframe').forEach(function(f){
      var r=f.getBoundingClientRect();
      frames.push({src:(f.src||'').slice(0,160), w:Math.round(r.width), h:Math.round(r.height)});
    });
    return JSON.stringify({
      clicked:clicked,
      iframes:frames,
      text:(document.documentElement.innerText||'').replace(/\s+/g,' ').slice(0,200),
      ua:navigator.userAgent,
      title:document.title,
      url:location.href,
      inner:[window.innerWidth,window.innerHeight],
      client:[document.documentElement.clientWidth,document.documentElement.clientHeight],
      scrollY:Math.round(window.scrollY||0),
      dpr:window.devicePixelRatio,
      replaceAll:typeof ''.replaceAll,
      promiseAny:typeof Promise.any,
      mathml:typeof MathMLElement
    });
  }catch(err){ return JSON.stringify({error:String(err)}); }
})()
"""

/** Extract DSID from a X-APPLE-WEBAUTH-USER cookie value like `v=1:s=0:d=123456789`. */
internal fun dsidFromCookie(cookieHeader: String): String? {
    val decoded = runCatching { URLDecoder.decode(cookieHeader, "UTF-8") }
        .getOrDefault(cookieHeader)
    val userCookie = Regex("X-APPLE-WEBAUTH-USER=([^;]*)")
        .find(decoded)?.groupValues?.getOrNull(1) ?: return null
    return Regex("d=(\\d{5,})").find(userCookie)?.groupValues?.getOrNull(1)
}
