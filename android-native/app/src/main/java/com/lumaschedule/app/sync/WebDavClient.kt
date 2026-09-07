package com.lumaschedule.app.sync

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

class WebDavClient {
    fun test(credentials: JSONObject): JSONObject {
        val target = normalizeBase(credentials.getString("baseUrl"))
        val connection = open(target, "PROPFIND", credentials).apply {
            setRequestProperty("Depth", "0")
            setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            doOutput = true
        }
        connection.outputStream.use { stream ->
            stream.write("<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><displayname/></prop></propfind>".toByteArray())
        }
        return connection.useResponse { status, _ ->
            val ok = status in 200..299
            result(ok, status, if (ok) "WebDAV 连接正常。" else "WebDAV 返回 HTTP $status", target.toString(), connection.getHeaderField("ETag"))
        }
    }

    fun upload(credentials: JSONObject, backup: String): JSONObject {
        val base = normalizeBase(credentials.getString("baseUrl"))
        val remotePath = sanitizeRemotePath(credentials.getString("remotePath"))
        ensureCollections(base, remotePath, credentials)
        val target = resolveRemote(base, remotePath)
        val connection = open(target, "PUT", credentials).apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(backup.toByteArray(StandardCharsets.UTF_8).size)
            doOutput = true
        }
        BufferedOutputStream(connection.outputStream).use { it.write(backup.toByteArray(StandardCharsets.UTF_8)) }
        return connection.useResponse { status, _ ->
            val ok = status in 200..299
            result(ok, status, if (ok) "完整备份已上传。" else "上传失败：HTTP $status", target.toString(), connection.getHeaderField("ETag"))
        }
    }

    fun download(credentials: JSONObject): Pair<JSONObject, String?> {
        val base = normalizeBase(credentials.getString("baseUrl"))
        val remotePath = sanitizeRemotePath(credentials.getString("remotePath"))
        val target = resolveRemote(base, remotePath)
        val connection = open(target, "GET", credentials)
        return connection.useResponse { status, body ->
            val ok = status in 200..299
            val response = result(ok, status, if (ok) "云端备份已下载。" else "下载失败：HTTP $status", target.toString(), connection.getHeaderField("ETag"))
            response to if (ok) body else null
        }
    }

    private fun ensureCollections(base: URL, remotePath: String, credentials: JSONObject) {
        val segments = remotePath.split('/').filter { it.isNotBlank() }
        if (segments.size <= 1) return
        var accumulated = ""
        for (segment in segments.dropLast(1)) {
            accumulated += "${encodePathSegment(segment)}/"
            val target = URL(base, accumulated)
            val connection = open(target, "MKCOL", credentials)
            connection.useResponse { status, _ ->
                if (status !in 200..299 && status != 405) {
                    error("创建 WebDAV 目录失败：HTTP $status")
                }
            }
        }
    }

    private fun normalizeBase(raw: String): URL {
        val text = raw.trim()
        require(text.isNotBlank()) { "WebDAV 地址不能为空。" }
        val uri = URI(text)
        val scheme = uri.scheme?.lowercase() ?: error("WebDAV 地址缺少协议。")
        val host = uri.host?.lowercase() ?: error("WebDAV 地址缺少主机名。")
        require(uri.userInfo == null) { "请不要把用户名或密码写进 WebDAV URL。" }
        require(scheme == "https" || (scheme == "http" && isTrustedLocalHttpHost(host))) {
            "公网 WebDAV 必须使用 HTTPS；HTTP 只允许 localhost、局域网 IP 或 .local 主机。"
        }
        val normalized = if (text.endsWith('/')) text else "$text/"
        return URL(normalized)
    }

    private fun isTrustedLocalHttpHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            if (first == 172 && second in 16..31) return true
        }
        return runCatching {
            InetAddress.getByName(host).isLoopbackAddress
        }.getOrDefault(false)
    }

    private fun sanitizeRemotePath(raw: String): String {
        val path = raw.trim().trimStart('/')
        require(path.isNotBlank()) { "远程备份路径不能为空。" }
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "远程备份路径无效。" }
        return segments.joinToString("/") { encodePathSegment(it) }
    }

    private fun resolveRemote(base: URL, encodedPath: String): URL = URL(base, encodedPath)

    private fun encodePathSegment(segment: String): String = URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun open(target: URL, method: String, credentials: JSONObject): HttpURLConnection {
        val connection = target.openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", "LumaScheduleNative/0.1")
        connection.setRequestProperty("Accept", "application/json, application/xml, */*")
        val username = credentials.optString("username")
        val password = credentials.optString("password")
        if (username.isNotBlank() || password.isNotBlank()) {
            val basic = Base64.encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $basic")
        }
        if (connection is HttpsURLConnection) {
            // Deliberately keep the platform hostname verifier and trust store.
            connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        }
        return connection
    }

    private inline fun <T> HttpURLConnection.useResponse(block: (Int, String) -> T): T {
        return try {
            val status = responseCode
            val stream = if (status >= 400) errorStream else inputStream
            val body = stream?.let { input -> BufferedInputStream(input).bufferedReader(StandardCharsets.UTF_8).use { it.readText() } }.orEmpty()
            block(status, body)
        } finally {
            disconnect()
        }
    }

    private fun result(ok: Boolean, status: Int, message: String, remoteUrl: String?, etag: String?): JSONObject = JSONObject()
        .put("ok", ok)
        .put("status", status)
        .put("message", message)
        .put("remoteUrl", remoteUrl ?: JSONObject.NULL)
        .put("etag", etag ?: JSONObject.NULL)
}
