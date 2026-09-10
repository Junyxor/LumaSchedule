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
            captureKind == "grades" -> "请登录教务系统，进入成绩查询/历年成绩页面后点击「读取成绩」。"
            manualTrigger -> "请登录教务系统，进入个人课表页面并点击查询，再点「读取课表」。"
            else -> "请完成教务系统登录。登录成功后会自动尝试读取；也可随时点右上角「读取课表」。"
        }
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
        webView.settings.databaseEnabled = true
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 247, 251))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            setBackgroundColor(Color.WHITE)
        }
        val back = TextView(this).apply {
            text = "返回"
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = 64.dp
            minimumWidth = 64.dp
            minHeight = 48.dp
            minimumHeight = 48.dp
            setPadding(8.dp, 0, 8.dp, 0)
            setTextColor(Color.rgb(81, 75, 194))
            isClickable = true
            isFocusable = true
            contentDescription = "返回 LumaSchedule"
            setOnClickListener { handleBack() }
        }
        val titleView = TextView(this).apply {
            text = "$schoolName  ·  $adapterName"
            textSize = 14f
            setTextColor(Color.rgb(42, 44, 55))
            setPadding(10.dp, 0, 8.dp, 0)
        }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        bar.addView(back, LinearLayout.LayoutParams(64.dp, 48.dp))
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Always expose a manual capture control. Official adapters still auto-inject after login,
        // but SSO redirects / SPA shells can miss onPageFinished; the button is the recovery path.
        val capture = Button(this).apply {
            text = if (captureKind == "grades") "读取成绩" else "读取课表"
            isAllCaps = false
            setOnClickListener { triggerManualCapture() }
        }
        bar.addView(capture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(progress, LinearLayout.LayoutParams(24.dp, 24.dp))
        webView = WebView(this)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun registerSystemBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback { handleBack() }
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
        val script = if (captureKind == "grades") {
            "if(typeof window.__LUMA_CAPTURE_GRADES__==='function'){window.__LUMA_CAPTURE_GRADES__();}else{void 0;}"
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
    reportError(message) { nativeBridge.reportError(String(message)); }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    override fun onDestroy() {
        cancelPendingInject()
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
