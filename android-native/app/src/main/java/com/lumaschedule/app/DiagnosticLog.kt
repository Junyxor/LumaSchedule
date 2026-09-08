package com.lumaschedule.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private const val FILE_NAME = "luma-diagnostic-events.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val KEEP_CHARS = 128 * 1024
    private val lock = Any()

    fun record(context: Context, level: String, event: String, detail: String = "") {
        val line = buildString {
            append(timestamp())
            append(' ')
            append(level.take(8).uppercase(Locale.ROOT))
            append(' ')
            append(event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80))
            if (detail.isNotBlank()) {
                append(' ')
                append(sanitize(detail).take(800))
            }
            append('\n')
        }
        runCatching {
            synchronized(lock) {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val tail = file.readText(Charsets.UTF_8).takeLast(KEEP_CHARS)
                    file.writeText("--- older diagnostic events trimmed ---\n$tail", Charsets.UTF_8)
                }
                file.appendText(line, Charsets.UTF_8)
            }
        }
    }

    fun suggestedFileName(): String = "lumaschedule-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    fun buildReport(
        context: Context,
        appVersion: String,
        uiReadyMs: Long,
        summary: JSONObject
    ): String = synchronized(lock) {
        val eventFile = File(context.filesDir, FILE_NAME)
        val events = runCatching {
            if (eventFile.exists()) eventFile.readText(Charsets.UTF_8) else "(no recorded events)\n"
        }.getOrElse { "(unable to read event log: ${sanitize(it.message.orEmpty())})\n" }

        buildString {
            appendLine("LumaSchedule Diagnostic Report")
            appendLine("generatedAt=${timestamp()}")
            appendLine("appVersion=$appVersion")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${sanitize(Build.MANUFACTURER)} ${sanitize(Build.MODEL)}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("locale=${Locale.getDefault().toLanguageTag()}")
            appendLine("uiReadyMs=$uiReadyMs")
            appendLine("processUptimeMs=${SystemClock.elapsedRealtime()}")
            appendLine("summary=${summary}")
            appendLine()
            appendLine("Privacy note: command payloads, passwords, cookies, authorization headers and course content are not intentionally recorded.")
            appendLine("URL query strings and common credential fields are redacted before export.")
            appendLine()
            appendLine("---- Recent app events ----")
            append(events)
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

    private fun sanitize(raw: String): String {
        if (raw.isBlank()) return raw
        var value = raw.replace('\n', ' ').replace('\r', ' ')
        value = URL_PATTERN.replace(value) { match ->
            val url = match.value
            if ('?' in url) "${url.substringBefore('?')}?<redacted-query>" else url
        }
        value = SECRET_PATTERN.replace(value) { match -> "${match.groupValues[1]}=<redacted>" }
        return value
    }

    private val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val SECRET_PATTERN = Regex(
        "(?i)\\b(password|passwd|pwd|token|cookie|authorization|secret|session(?:id)?|ticket|access[_-]?key)\\b\\s*[:=]\\s*[^\\s,;]+"
    )
}
