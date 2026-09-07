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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lumaschedule.app.data.LumaDatabase
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
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
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
                if (uri.toString().startsWith(APP_PREFIX)) return false
                if (uri.scheme == "https" || uri.scheme == "http") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.let(Uri::parse) ?: return true
                if (url.startsWith(APP_PREFIX)) return false
                if (uri.scheme == "https" || uri.scheme == "http") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith(APP_PREFIX) == true) bridge.markUiReady()
            }
        }
    }

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
        private const val APP_URL = "file:///android_asset/index.html"
        private const val APP_PREFIX = "file:///android_asset/"
        private const val REQUEST_NOTIFICATIONS = 4017
    }
}
