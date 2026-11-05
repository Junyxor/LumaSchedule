package com.lumaschedule.app.shiguang

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.lumaschedule.app.MainActivity
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShiguangImportActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var hintView: TextView? = null
    private lateinit var sessionId: String
    private lateinit var adapterScript: String
    private lateinit var adapterName: String
    private lateinit var schoolName: String
    private lateinit var captureKind: String
    private var manualTrigger: Boolean = false
    private var allowedHosts: Set<String> = emptySet()
    private var insecureTransport: Boolean = false
    private var primaryLoginHost: String = ""
    private var backNavigationStarted = false
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private val injectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingInject: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        adapterScript = intent.getStringExtra(EXTRA_ADAPTER_SCRIPT).orEmpty()
        adapterName = intent.getStringExtra(EXTRA_ADAPTER_NAME).orEmpty()
        schoolName = intent.getStringExtra(EXTRA_SCHOOL_NAME).orEmpty()
        captureKind = intent.getStringExtra(EXTRA_CAPTURE_KIND).orEmpty().ifBlank { "schedule" }
        manualTrigger = intent.getBooleanExtra(EXTRA_MANUAL_TRIGGER, false)
        allowedHosts = parseHosts(intent.getStringExtra(EXTRA_ALLOWED_HOSTS_JSON).orEmpty())
        insecureTransport = intent.getBooleanExtra(EXTRA_INSECURE_TRANSPORT, false)
        val importUrl = intent.getStringExtra(EXTRA_IMPORT_URL).orEmpty()
        primaryLoginHost = runCatching { android.net.Uri.parse(importUrl).host?.lowercase() }.getOrNull().orEmpty()
        if (sessionId.isBlank() || adapterScript.isBlank() || importUrl.isBlank()) {
            finishWithError("教务导入参数不完整")
            return
        }

        title = "$schoolName · $adapterName"
        setContentView(buildUi())
        registerSystemBack()
        val initialMessage = when {
            captureKind == "grades" -> "登录教务系统并打开成绩查询（历年成绩）页面，再点下方「读取成绩」。"
            manualTrigger -> "登录教务系统并打开个人课表页面，查询出课表后点下方「读取课表」。"
            else -> "登录成功后会自动读取课表；如果没有反应，打开课表页面后点下方「读取课表」。"
        }
        hintView?.text = initialMessage
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(key(sessionId, "status"), "running")
            .putString(key(sessionId, "message"), initialMessage)
            .putString(key(sessionId, "adapter_name"), adapterName)
            .putString(key(sessionId, "school_name"), schoolName)
            .putString(key(sessionId, "capture_kind"), captureKind)
            .apply()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        if (insecureTransport) webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.addJavascriptInterface(Bridge(), "LumaShiguangBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host?.lowercase().orEmpty()
                val allowedScheme = uri.scheme == "https" || (uri.scheme == "http" && insecureTransport)
                if (!allowedScheme || !isHostAllowed(host)) {
                    Toast.makeText(this@ShiguangImportActivity, "已阻止跳转到未声明域名：$host", Toast.LENGTH_LONG).show()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return blockedResponse()
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (scheme in setOf("about", "data", "blob")) return null
                val host = uri.host?.lowercase().orEmpty()
                if ((scheme == "https" || (scheme == "http" && insecureTransport)) && isHostAllowed(host)) return null
                return blockedResponse()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
                scheduleAutoInject(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (isReload) scheduleAutoInject(url)
            }
        }
        webView.loadUrl(importUrl)
    }

    private fun buildUi(): LinearLayout {
        val accent = Color.rgb(91, 86, 214)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // Android 15+ always draws apps edge-to-edge; keep the bars and the bottom action clear of
        // the status bar, navigation bar and keyboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 4.dp, 14.dp, 4.dp)
            minimumHeight = 56.dp
        }
        val close = TextView(this).apply {
            text = "关闭"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0)
            setTextColor(accent)
            isClickable = true
            isFocusable = true
            background = ripple(Color.TRANSPARENT, 12.dp.toFloat())
            contentDescription = "关闭并返回 LumaSchedule"
            setOnClickListener { handleBack() }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp, 0, 8.dp, 0)
        }
        titles.addView(TextView(this).apply {
            text = listOf(schoolName, adapterName).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(22, 24, 36))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titles.addView(TextView(this).apply {
            text = if (insecureTransport) "⚠ 未加密连接 · $primaryLoginHost" else "🔒 $primaryLoginHost"
            textSize = 12f
            setTextColor(if (insecureTransport) Color.rgb(181, 109, 7) else Color.rgb(110, 113, 128))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        progress = ProgressBar(this).apply { isIndeterminate = true }
        bar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp))
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(progress, LinearLayout.LayoutParams(22.dp, 22.dp))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(230, 231, 236)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Full-width bottom action: easy to reach after SSO and independent of the page layout.
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 10.dp, 16.dp, 12.dp)
            setBackgroundColor(Color.rgb(248, 249, 251))
        }
        hintView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.rgb(96, 99, 115))
            setPadding(2.dp, 0, 2.dp, 8.dp)
        }
        val capture = Button(this).apply {
            text = if (captureKind == "grades") "读取成绩" else "读取课表"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            stateListAnimator = null
            background = ripple(accent, 14.dp.toFloat())
            setOnClickListener { triggerManualCapture() }
        }
        footer.addView(hintView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        footer.addView(capture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50.dp))
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(230, 231, 236)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun ripple(color: Int, radius: Float): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }
        val mask = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), shape, mask)
    }

    private fun registerSystemBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback { handleSystemBack() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
        backInvokedCallback = callback
    }

    private fun triggerManualCapture() {
        if (!::webView.isInitialized || currentStatus() == "complete") return
        cancelPendingInject()
        progress.visibility = View.VISIBLE
        // Always re-inject: auto-injection is skipped on the login host, which is often
        // the same host that serves the grade page, so the capture function may not exist yet.
        val script = if (captureKind == "grades") {
            "window.__LUMA_CAPTURE_REQUESTED__=true;delete window.__LUMA_SHIGUANG_BOOTSTRAPPED__;\n${buildInjectionScript()}"
        } else {
            "delete window.__LUMA_SHIGUANG_BOOTSTRAPPED__;\n${buildInjectionScript()}"
        }
        webView.evaluateJavascript(script) { progress.visibility = View.GONE }
    }

    private fun cancelPendingInject() {
        pendingInject?.let(injectHandler::removeCallbacks)
        pendingInject = null
    }

    private fun shouldAutoInject(url: String?): Boolean {
        if (manualTrigger) return false
        if (!::webView.isInitialized || currentStatus() == "complete") return false
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isBlank() || !isHostAllowed(host)) return false
        // Do not fire the adapter on the CAS/login entry page. Wait until SSO lands on the school system.
        if (host == primaryLoginHost) return false
        return true
    }

    private fun scheduleAutoInject(url: String?) {
        if (!shouldAutoInject(url)) {
            cancelPendingInject()
            return
        }
        cancelPendingInject()
        val runnable = Runnable {
            pendingInject = null
            if (isFinishing || !::webView.isInitialized) return@Runnable
            val current = webView.url
            if (!shouldAutoInject(current)) return@Runnable
            // Re-check the page is still the same navigation target that scheduled this inject.
            val scheduledHost = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()
            val currentHost = runCatching { android.net.Uri.parse(current).host?.lowercase() }.getOrNull()
            if (scheduledHost != null && currentHost != null && scheduledHost != currentHost) return@Runnable
            webView.evaluateJavascript(buildInjectionScript(), null)
        }
        pendingInject = runnable
        // Give SSO callbacks / SPA shells a short settle window before running the adapter.
        injectHandler.postDelayed(runnable, 1200)
    }

    private fun buildInjectionScript(): String = """
(() => {
  if (window.top !== window.self || window.__LUMA_SHIGUANG_BOOTSTRAPPED__) return;
  window.__LUMA_SHIGUANG_BOOTSTRAPPED__ = true;
  const nativeBridge = window.LumaShiguangBridge;
  window.shiguangBridge = {
    showToast(message) { nativeBridge.showToast(String(message)); },
    notifyTaskCompletion() { nativeBridge.notifyTaskCompletion(); },
    reportError(message) { nativeBridge.reportError(String(message)); },
    updateProgress(message) { nativeBridge.updateProgress(String(message)); }
  };
  window.shiguangBridgePromise = {
    async showAlert(title, message, confirmText) {
      return !!nativeBridge.showAlert(String(title || ''), String(message || ''), String(confirmText || '确认'));
    },
    async showPrompt(title, tip, defaultText, validatorJsFunction) {
      let current = String(defaultText || '');
      while (true) {
        const result = nativeBridge.showPrompt(String(title || ''), String(tip || ''), current);
        if (result === null || result === undefined) return null;
        const value = String(result);
        if (!validatorJsFunction) return value;
        try {
          const validationResult = (0, eval)(String(validatorJsFunction) + '(' + JSON.stringify(value) + ')');
          const resolved = validationResult && typeof validationResult.then === 'function' ? await validationResult : validationResult;
          if (resolved === false || resolved === null || resolved === undefined || String(resolved).length === 0) return value;
          nativeBridge.showToast(String(resolved));
          current = value;
        } catch (error) {
          nativeBridge.showToast('输入校验器执行失败：' + String(error));
          return value;
        }
      }
    },
    async showSingleSelection(title, optionsJson, defaultIndex) {
      return nativeBridge.showSingleSelection(String(title || ''), String(optionsJson || '[]'), Number(defaultIndex ?? -1));
    },
    async saveImportedCourses(payload) { return !!nativeBridge.saveImportedCourses(String(payload || '[]')); },
    async savePresetTimeSlots(payload) { return !!nativeBridge.savePresetTimeSlots(String(payload || '[]')); },
    async saveCourseConfig(payload) { return !!nativeBridge.saveCourseConfig(String(payload || '{}')); },
    async saveImportedGrades(payload) { return !!nativeBridge.saveImportedGrades(String(payload || '[]')); },
    async saveGradeMeta(payload) { return !!nativeBridge.saveGradeMeta(String(payload || '{}')); }
  };
  window.AndroidBridgePromise = window.shiguangBridgePromise;
  window.AndroidBridge = window.shiguangBridge;
  window.addEventListener('unhandledrejection', event => nativeBridge.reportDiagnostic(String(event.reason || 'page rejection')));
  window.addEventListener('error', event => nativeBridge.reportDiagnostic(String(event.error || event.message || 'page error')));

  const __lumaStartAdapter = () => {
    try {
$adapterScript
    } catch (error) {
      nativeBridge.reportDiagnostic(String((error && error.stack) || error));
      nativeBridge.reportError('适配脚本执行失败：' + String((error && error.message) || error));
    }
  };
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(__lumaStartAdapter, 250);
  } else {
    document.addEventListener('DOMContentLoaded', () => setTimeout(__lumaStartAdapter, 250), { once: true });
  }
})();
""".trimIndent()

    private inner class Bridge {
        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread { Toast.makeText(this@ShiguangImportActivity, message, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun updateProgress(message: String) {
            if (currentStatus() == "complete") return
            save("message", message.take(200))
            runOnUiThread { hintView?.text = message.take(200) }
        }

        @JavascriptInterface
        fun showAlert(title: String, message: String, confirmText: String): Boolean {
            if (isFinishing) return false
            val latch = CountDownLatch(1)
            var accepted = false
            runOnUiThread {
                val dialog = AlertDialog.Builder(this@ShiguangImportActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(confirmText.ifBlank { "确认" }) { _, _ -> accepted = true; latch.countDown() }
                    .setNegativeButton("取消") { _, _ -> accepted = false; latch.countDown() }
                    .setOnCancelListener { accepted = false; latch.countDown() }
                    .create()
                dialog.show()
            }
            latch.await(10, TimeUnit.MINUTES)
            return accepted
        }

        @JavascriptInterface
        fun showPrompt(title: String, tip: String, defaultText: String): String? {
            if (isFinishing) return null
            val latch = CountDownLatch(1)
            var result: String? = null
            runOnUiThread {
                val input = EditText(this@ShiguangImportActivity).apply {
                    setText(defaultText)
                    setSelection(text.length)
                    setSingleLine(false)
                    minLines = 1
                    maxLines = 5
                    setPadding(20.dp, 12.dp, 20.dp, 12.dp)
                }
                val dialog = AlertDialog.Builder(this@ShiguangImportActivity)
                    .setTitle(title)
                    .setMessage(tip)
                    .setView(input)
                    .setPositiveButton("确认") { _, _ -> result = input.text?.toString() ?: ""; latch.countDown() }
                    .setNegativeButton("取消") { _, _ -> result = null; latch.countDown() }
                    .setOnCancelListener { result = null; latch.countDown() }
                    .create()
                dialog.show()
            }
            latch.await(10, TimeUnit.MINUTES)
            return result
        }

        @JavascriptInterface
        fun showSingleSelection(title: String, optionsJson: String, defaultIndex: Int): Int {
            if (isFinishing) return -1
            val optionsArray = runCatching { JSONArray(optionsJson) }.getOrNull() ?: return -1
            val options = Array(optionsArray.length()) { index -> optionsArray.optString(index, "选项 ${index + 1}") }
            if (options.isEmpty()) return -1
            val latch = CountDownLatch(1)
            var selected = -1
            val checked = defaultIndex.takeIf { it in options.indices } ?: -1
            runOnUiThread {
                val dialog = AlertDialog.Builder(this@ShiguangImportActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(options, checked) { d, which -> selected = which; d.dismiss(); latch.countDown() }
                    .setNegativeButton("取消") { _, _ -> selected = -1; latch.countDown() }
                    .setOnCancelListener { selected = -1; latch.countDown() }
                    .create()
                dialog.show()
            }
            latch.await(10, TimeUnit.MINUTES)
            return selected
        }

        @JavascriptInterface
        fun saveImportedCourses(payload: String): Boolean {
            save("courses", payload)
            save("status", "collecting")
            save("message", "课程已经读取，正在整理学期与作息信息。")
            return true
        }

        @JavascriptInterface
        fun savePresetTimeSlots(payload: String): Boolean { save("time_slots", payload); return true }

        @JavascriptInterface
        fun saveCourseConfig(payload: String): Boolean { save("config", payload); return true }

        @JavascriptInterface
        fun saveImportedGrades(payload: String): Boolean {
            save("grades", payload)
            save("status", "collecting")
            save("message", "成绩已经读取，正在整理预览。")
            return true
        }

        @JavascriptInterface
        fun saveGradeMeta(payload: String): Boolean { save("grade_meta", payload); return true }

        @JavascriptInterface
        fun notifyTaskCompletion() {
            val message = if (captureKind == "grades") {
                "成绩读取完成，返回 LumaSchedule 预览。"
            } else {
                "教务课程读取完成，返回 LumaSchedule 确认导入。"
            }
            save("status", "complete")
            save("message", message)
            runOnUiThread {
                Toast.makeText(
                    this@ShiguangImportActivity,
                    if (captureKind == "grades") "成绩读取完成" else "课表读取完成",
                    Toast.LENGTH_SHORT
                ).show()
                webView.postDelayed({ finish() }, 650)
            }
        }

        @JavascriptInterface
        fun reportError(message: String) {
            if (currentStatus() == "complete") return
            save("status", "error")
            save("message", message.take(1000))
        }

        @JavascriptInterface
        fun reportDiagnostic(message: String) {
            android.util.Log.w("LumaShiguang", message.take(1000))
        }

        private fun save(field: String, value: String) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key(sessionId, field), value).apply()
        }
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked by LumaSchedule adapter sandbox",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun currentStatus(): String = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .getString(key(sessionId, "status"), "running") ?: "running"

    private fun finishWithError(message: String) {
        if (::sessionId.isInitialized && sessionId.isNotBlank()) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(key(sessionId, "status"), "error")
                .putString(key(sessionId, "message"), message)
                .apply()
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun handleBack() {
        if (backNavigationStarted) return
        backNavigationStarted = true
        if (::sessionId.isInitialized && sessionId.isNotBlank()) {
            val status = currentStatus()
            if (status != "complete" && status != "error") {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(key(sessionId, "status"), "error")
                    .putString(key(sessionId, "message"), "已退出教务登录；本次没有导入数据。")
                    .apply()
            }
        }
        if (::webView.isInitialized) webView.stopLoading()
        setResult(RESULT_CANCELED)

        // Do not rely on finish() revealing the correct previous Activity. A duplicated
        // login surface or OEM task-stack behavior can leave another WebView on top and
        // make the button appear dead. Clear every login Activity above MainActivity.
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(returnIntent) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** The system back gesture walks back through the教务 pages first; the top-left button always leaves. */
    private fun handleSystemBack() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else handleBack()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleSystemBack()

    override fun onDestroy() {
        cancelPendingInject()
        // Finished without going through back/complete/error (task reset, another Activity
        // clearing the stack): end the session so the app stops waiting for this login.
        if (isFinishing && ::sessionId.isInitialized && sessionId.isNotBlank()) {
            val status = currentStatus()
            if (status == "running" || status == "collecting") {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(key(sessionId, "status"), "error")
                    .putString(key(sessionId, "message"), "登录窗口已关闭；本次没有导入数据。")
                    .apply()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let { callback ->
                runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
            }
            backInvokedCallback = null
        }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("LumaShiguangBridge")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isHostAllowed(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized in allowedHosts) return true
        return allowedHosts.any { seed -> sharesInstitutionScope(seed, normalized) }
    }

    private fun sharesInstitutionScope(seed: String, candidate: String): Boolean {
        if (seed.equals(candidate, ignoreCase = true)) return true
        val scope = institutionScope(seed)
        return candidate == scope || candidate.endsWith(".$scope")
    }

    private fun institutionScope(host: String): String {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return normalized
        val labels = normalized.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return normalized
        val cnSecondLevel = labels.last() == "cn" && labels.getOrNull(labels.size - 2) in setOf("edu", "com", "net", "org", "gov", "ac")
        val keep = if (cnSecondLevel && labels.size >= 3) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    private fun parseHosts(raw: String): Set<String> = runCatching {
        val json = JSONArray(raw)
        buildSet {
            for (index in 0 until json.length()) {
                json.optString(index).trim().lowercase().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "luma_shiguang_import"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_IMPORT_URL = "import_url"
        const val EXTRA_ADAPTER_SCRIPT = "adapter_script"
        const val EXTRA_ALLOWED_HOSTS_JSON = "allowed_hosts_json"
        const val EXTRA_ADAPTER_NAME = "adapter_name"
        const val EXTRA_SCHOOL_NAME = "school_name"
        const val EXTRA_INSECURE_TRANSPORT = "insecure_transport"
        const val EXTRA_CAPTURE_KIND = "capture_kind"
        const val EXTRA_MANUAL_TRIGGER = "manual_trigger"
        fun key(sessionId: String, field: String) = "$sessionId.$field"
    }
}
