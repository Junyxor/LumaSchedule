package com.lumaschedule.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lumaschedule.app.data.LumaDatabase
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var database: LumaDatabase
    private lateinit var bridge: LumaBridge
    private val ioExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "luma-native-io").apply { priority = Thread.NORM_PRIORITY }
    }
    private val launchStarted = SystemClock.elapsedRealtime()

    @Volatile private var notificationLatch: CountDownLatch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow(window)
        database = LumaDatabase(this)
        webView = WebView(this)
        configureWebView(webView)
        bridge = LumaBridge(this, webView, database, ioExecutor, launchStarted)
        webView.addJavascriptInterface(bridge, "LumaNative")
        setContentView(webView)
        webView.loadUrl(APP_URL)
    }

    private fun configureWindow(window: Window) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            )
    }

    private fun configureWebView(view: WebView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = false // requests are intercepted below; unknown origins are returned as 403.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString LumaScheduleNative/${BuildConfig.VERSION_NAME}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (isAppUri(uri)) return false
                if (uri.scheme == "https" || uri.scheme == "http") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.let(Uri::parse) ?: return true
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
                    val headers = if (path == "index.html") {
                        mapOf("Cache-Control" to "no-cache")
                    } else {
                        mapOf("Cache-Control" to "public, max-age=31536000, immutable")
                    }
                    WebResourceResponse(mimeFor(path), encodingFor(path), 200, "OK", headers, stream)
                }.getOrElse {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.let(Uri::parse)?.let(::isAppUri) == true) bridge.markUiReady()
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
        "text/plain", "utf-8", 403, "Blocked by LumaSchedule", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0))
    )

    fun ensureNotificationPermissionBlocking(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        synchronized(this) {
            if (notificationLatch != null) return false
            val latch = CountDownLatch(1)
            notificationLatch = latch
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS) }
            latch.await(30, TimeUnit.SECONDS)
            notificationLatch = null
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) notificationLatch?.countDown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("LumaNative")
            webView.stopLoading()
            webView.destroy()
        }
        if (::database.isInitialized) database.close()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = "app.luma.local"
        private const val APP_URL = "https://$APP_HOST/index.html"
        private const val REQUEST_NOTIFICATIONS = 4017
    }
}
