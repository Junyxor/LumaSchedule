package com.lumaschedule.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.lumaschedule.app.data.LumaDatabase
import com.lumaschedule.app.widgets.ReminderReceiver
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private lateinit var bridge: LumaBridge
    private val ioExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "luma-native-io").apply { priority = Thread.NORM_PRIORITY }
    }
    private val launchStarted = SystemClock.elapsedRealtime()

    @Volatile private var notificationLatch: CountDownLatch? = null
    private val pickerLock = Any()
    @Volatile private var pickerLatch: CountDownLatch? = null
    @Volatile private var pickerResult: Uri? = null
    private var webFileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** System bar insets in CSS pixels; the bottom inset is 0 while the keyboard is open. */
    @Volatile private var cssInsets = IntArray(4) // top, right, bottom, left

    /**
     * WebView text zoom from the system font size, capped so layouts keep their shape.
     * fontScale is not in configChanges, so a font size change recreates the Activity.
     */
    private val textZoomPercent by lazy {
        (resources.configuration.fontScale * 100).roundToInt().coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
    }

    @Volatile private var webBackEnabled = false
    private var backCallback: Any? = null
    private var darkBars = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = LumaDatabase.get(this)
        darkBars = when (database.appearance()?.optString("theme")) {
            "dark" -> true
            "light" -> false
            else -> isSystemDark()
        }
        configureWindow()
        container = FrameLayout(this)
        webView = WebView(this)
        container.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets) }
        setContentView(container)
        configureWebView(webView)
        bridge = LumaBridge(this, webView, ioExecutor, launchStarted)
        webView.addJavascriptInterface(bridge, "LumaNative")
        ReminderReceiver.ensureChannel(this)
        webView.loadUrl(APP_URL)
    }

    // ------------------------------------------------------------------ window & insets

    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Implied on Android 15+, still required on Android 11–14.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        applySystemBarColors()
    }

    private fun applySystemBarColors() {
        val background = if (darkBars) DARK_BACKGROUND else LIGHT_BACKGROUND
        window.setBackgroundDrawable(ColorDrawable(background))
        if (::webView.isInitialized) webView.setBackgroundColor(background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (darkBars) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            @Suppress("DEPRECATION")
            if (!darkBars) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * Pads the container above the keyboard and publishes the system-bar insets as
     * CSS variables. Returns what the WebView itself sees: the keyboard and, while it
     * is open, the bottom bar are already covered by the padding, so they are removed
     * and `env(safe-area-inset-*)` cannot count them a second time.
     */
    @Suppress("DEPRECATION")
    private fun applyInsets(insets: WindowInsets): WindowInsets {
        val density = resources.displayMetrics.density
        val top: Int
        val left: Int
        val right: Int
        val barsBottom: Int
        val imeBottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top = bars.top
            left = bars.left
            right = bars.right
            barsBottom = bars.bottom
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            top = insets.systemWindowInsetTop
            left = insets.systemWindowInsetLeft
            right = insets.systemWindowInsetRight
            barsBottom = insets.stableInsetBottom
            imeBottom = insets.systemWindowInsetBottom
        }
        val keyboardOpen = imeBottom > barsBottom + (80 * density).toInt()
        // Shrinking the WebView above the keyboard lets Chromium scroll focused inputs into view.
        container.setPadding(0, 0, 0, if (keyboardOpen) imeBottom else 0)
        cssInsets = intArrayOf(
            (top / density).toInt(),
            (right / density).toInt(),
            if (keyboardOpen) 0 else (barsBottom / density).toInt(),
            (left / density).toInt()
        )
        pushInsets()
        return insetsForWebView(insets, keyboardOpen)
    }

    @Suppress("DEPRECATION")
    private fun insetsForWebView(insets: WindowInsets, keyboardOpen: Boolean): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val builder = WindowInsets.Builder(insets).setInsets(WindowInsets.Type.ime(), Insets.NONE)
            if (keyboardOpen) {
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(nav.left, nav.top, nav.right, 0))
            }
            return builder.build()
        }
        if (!keyboardOpen) return insets
        return insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, 0)
    }

    fun insetsJson(): String = JSONObject()
        .put("top", cssInsets[0])
        .put("right", cssInsets[1])
        .put("bottom", cssInsets[2])
        .put("left", cssInsets[3])
        .put("textZoom", textZoomPercent)
        .toString()

    private fun pushInsets() {
        if (!::webView.isInitialized) return
        val (top, right, bottom, left) = cssInsets.toList()
        webView.evaluateJavascript(
            "(function(s){s.setProperty('--native-inset-top','${top}px');s.setProperty('--native-inset-right','${right}px');" +
                "s.setProperty('--native-inset-bottom','${bottom}px');s.setProperty('--native-inset-left','${left}px');" +
                "s.setProperty('--text-zoom','${textZoomPercent / 100.0}');})(document.documentElement.style);",
            null
        )
    }

    fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun setSystemBarsDark(dark: Boolean) = runOnUiThread {
        if (darkBars == dark) return@runOnUiThread
        darkBars = dark
        applySystemBarColors()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dark = isSystemDark()
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('luma-system-theme',{detail:{dark:$dark}}));", null)
        }
    }

    // ------------------------------------------------------------------ back navigation

    fun setWebBackEnabled(enabled: Boolean) = runOnUiThread {
        webBackEnabled = enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) updateBackCallback()
    }

    @SuppressLint("NewApi")
    private fun updateBackCallback() {
        val dispatcher = onBackInvokedDispatcher
        if (webBackEnabled && backCallback == null) {
            val callback = OnBackInvokedCallback { dispatchBackToWeb() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!webBackEnabled && backCallback != null) {
            runCatching { dispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback) }
            backCallback = null
        }
    }

    private fun dispatchBackToWeb() {
        webView.evaluateJavascript("(function(){try{return !!(window.__lumaBack&&window.__lumaBack());}catch(e){return false;}})()") { result ->
            if (result != "true") moveTaskToBack(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webBackEnabled && ::webView.isInitialized) dispatchBackToWeb() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.setBackgroundColor(if (darkBars) DARK_BACKGROUND else LIGHT_BACKGROUND)
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = false // every request is intercepted below; unknown origins get 403.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Follow the system font size (capped); the CSS also receives it as --text-zoom.
            textZoom = textZoomPercent
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString LumaScheduleNative/${BuildConfig.VERSION_NAME}"
            safeBrowsingEnabled = true
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                webFileChooserCallback?.onReceiveValue(null)
                webFileChooserCallback = filePathCallback
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()?.apply {
                    // Many file managers grey out `.wakeup_schedule` and `.ics` when filtered by MIME type.
                    type = "*/*"
                    removeExtra(Intent.EXTRA_MIME_TYPES)
                }
                if (intent == null) {
                    webFileChooserCallback?.onReceiveValue(null)
                    webFileChooserCallback = null
                    return false
                }
                return runCatching {
                    startActivityForResult(intent, REQUEST_WEB_FILE)
                    true
                }.getOrElse {
                    webFileChooserCallback?.onReceiveValue(null)
                    webFileChooserCallback = null
                    false
                }
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (isAppUri(uri)) return false
                if (uri.scheme == "https" || uri.scheme == "http") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url ?: return blockedResponse()
                if (!isAppUri(uri)) return blockedResponse()
                val path = uri.path.orEmpty().trimStart('/').ifEmpty { "index.html" }
                if (path.split('/').any { it == ".." }) return blockedResponse()
                return runCatching {
                    val stream = assets.open(path)
                    val headers = if (path == "index.html") mapOf("Cache-Control" to "no-cache") else mapOf("Cache-Control" to "public, max-age=31536000, immutable")
                    WebResourceResponse(mimeFor(path), encodingFor(path), 200, "OK", headers, stream)
                }.getOrElse {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.let(Uri::parse)?.let(::isAppUri) == true) {
                    pushInsets()
                    bridge.markUiReady()
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // The renderer can be killed under memory pressure; rebuild the UI instead of crashing.
                DiagnosticLog.record(this@MainActivity, "WARN", "webview.render_gone", "crash=${detail?.didCrash()}")
                recreate()
                return true
            }
        }
    }

    private fun isAppUri(uri: Uri): Boolean = uri.scheme == "https" && uri.host == APP_HOST

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun encodingFor(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "js", "mjs", "css", "json", "svg", "map" -> "utf-8"
        else -> null
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked by LumaSchedule",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0))
    )

    // ------------------------------------------------------------------ permissions & pickers

    fun requestExactAlarmAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = getSystemService(ALARM_SERVICE) as AlarmManager
        if (manager.canScheduleExactAlarms()) return true
        return runCatching {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:$packageName") })
            }
            true
        }.getOrDefault(false)
    }

    fun ensureNotificationPermissionBlocking(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        synchronized(this) {
            if (notificationLatch != null) return false
            val latch = CountDownLatch(1)
            notificationLatch = latch
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS) }
            latch.await(60, TimeUnit.SECONDS)
            notificationLatch = null
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun createDocumentBlocking(mimeType: String, suggestedName: String): Uri? = runPickerBlocking(
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
    )

    fun openDocumentBlocking(mimeTypes: Array<String>): Uri? = runPickerBlocking(
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
    )

    private fun runPickerBlocking(intent: Intent): Uri? = synchronized(pickerLock) {
        val latch = CountDownLatch(1)
        pickerResult = null
        pickerLatch = latch
        runOnUiThread {
            runCatching { startActivityForResult(intent, REQUEST_DOCUMENT) }.onFailure { latch.countDown() }
        }
        latch.await(10, TimeUnit.MINUTES)
        pickerLatch = null
        pickerResult
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WEB_FILE) {
            val callback = webFileChooserCallback
            webFileChooserCallback = null
            callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            return
        }
        if (requestCode == REQUEST_DOCUMENT) {
            pickerResult = if (resultCode == RESULT_OK) data?.data else null
            pickerLatch?.countDown()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) notificationLatch?.countDown()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        if (::bridge.isInitialized) bridge.onResume()
    }

    override fun onDestroy() {
        pickerLatch?.countDown()
        notificationLatch?.countDown()
        webFileChooserCallback?.onReceiveValue(null)
        webFileChooserCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback) }
            backCallback = null
        }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("LumaNative")
            webView.stopLoading()
            container.removeView(webView)
            webView.destroy()
        }
        ioExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        /**
         * Opens the app the way the launcher icon does: an existing task comes to the
         * front unchanged, so a 教务 login in progress stays on top instead of being
         * finished by CLEAR_TOP. Used by reminder notifications and the widget.
         */
        fun launcherIntent(context: Context): Intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        private const val APP_HOST = "app.luma.local"
        private const val APP_URL = "https://$APP_HOST/index.html"
        private const val MIN_TEXT_ZOOM = 85
        private const val MAX_TEXT_ZOOM = 130
        private const val REQUEST_NOTIFICATIONS = 4017
        private const val REQUEST_DOCUMENT = 4018
        private const val REQUEST_WEB_FILE = 4019
        private val LIGHT_BACKGROUND = Color.rgb(0xF3, 0xF4, 0xF7)
        private val DARK_BACKGROUND = Color.rgb(0x0D, 0x0F, 0x13)
    }
}
