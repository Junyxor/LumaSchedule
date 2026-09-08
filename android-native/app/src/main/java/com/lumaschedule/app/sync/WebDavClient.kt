package com.lumaschedule.app.sync

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WebDavClient {
    fun test(credentials: JSONObject): JSONObject {
        val target = normalizeBase(credentials.getString("baseUrl"))
        val response = request(
            target,
            "PROPFIND",
            credentials,
            mapOf("Depth" to "0", "Content-Type" to "application/xml; charset=utf-8"),
            "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><displayname/></prop></propfind>".toByteArray(StandardCharsets.UTF_8)
        )
        val ok = response.status in 200..299
        return result(ok, response.status, if (ok) "WebDAV 连接正常。" else "WebDAV 返回 HTTP ${response.status}", target.toString(), response.header("etag"))
    }

    fun upload(credentials: JSONObject, backup: String): JSONObject {
        val base = normalizeBase(credentials.getString("baseUrl"))
        val remotePath = sanitizeRemotePath(credentials.getString("remotePath"))
        ensureCollections(base, remotePath, credentials)
        val target = resolveRemote(base, remotePath)
        val bytes = backup.toByteArray(StandardCharsets.UTF_8)
        val response = request(
            target,
            "PUT",
            credentials,
            mapOf("Content-Type" to "application/json; charset=utf-8"),
            bytes
        )
        val ok = response.status in 200..299
        return result(ok, response.status, if (ok) "完整备份已上传。" else "上传失败：HTTP ${response.status}", target.toString(), response.header("etag"))
    }

    fun download(credentials: JSONObject): Pair<JSONObject, String?> {
        val base = normalizeBase(credentials.getString("baseUrl"))
        val remotePath = sanitizeRemotePath(credentials.getString("remotePath"))
        val target = resolveRemote(base, remotePath)
        val response = request(target, "GET", credentials, emptyMap(), null)
        val ok = response.status in 200..299
        val result = result(ok, response.status, if (ok) "云端备份已下载。" else "下载失败：HTTP ${response.status}", target.toString(), response.header("etag"))
        val body = if (ok) response.body.toString(StandardCharsets.UTF_8) else null
        return result to body
    }

    private fun ensureCollections(base: URL, remotePath: String, credentials: JSONObject) {
        val segments = remotePath.split('/').filter { it.isNotBlank() }
        if (segments.size <= 1) return
        val accumulated = mutableListOf<String>()
        for (segment in segments.dropLast(1)) {
            accumulated += segment
            val target = resolveRemote(base, accumulated.joinToString("/") + "/")
            val response = request(target, "MKCOL", credentials, emptyMap(), null)
            if (response.status !in 200..299 && response.status != 405) {
                error("创建 WebDAV 目录失败：HTTP ${response.status}")
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
        if (parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            if (first == 172 && second in 16..31) return true
        }
        return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    private fun sanitizeRemotePath(raw: String): String {
        val path = raw.trim().trimStart('/')
        require(path.isNotBlank()) { "远程备份路径不能为空。" }
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "远程备份路径无效。" }
        return segments.joinToString("/")
    }

    private fun resolveRemote(base: URL, rawPath: String): URL {
        val trailingSlash = rawPath.endsWith('/')
        val encoded = rawPath.trimEnd('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodePathSegment(it) }
        return URL(base, encoded + if (trailingSlash) "/" else "")
    }

    private fun encodePathSegment(segment: String): String = URLEncoder
        .encode(segment, StandardCharsets.UTF_8.name())
        .replace("+", "%20")

    private fun request(
        target: URL,
        method: String,
        credentials: JSONObject,
        extraHeaders: Map<String, String>,
        body: ByteArray?
    ): Response {
        val uri = target.toURI()
        val host = uri.host ?: error("WebDAV URL 缺少主机。")
        val secure = uri.scheme.equals("https", true)
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val socket = openSocket(host, port, secure)
        return socket.use { rawSocket ->
            rawSocket.soTimeout = READ_TIMEOUT_MS
            val output = BufferedOutputStream(rawSocket.getOutputStream())
            val input = BufferedInputStream(rawSocket.getInputStream())
            val targetPath = buildRequestTarget(uri)
            val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
            val username = credentials.optString("username")
            val password = credentials.optString("password")
            val auth = if (username.isNotBlank() || password.isNotBlank()) {
                Base64.encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            } else null

            val headers = LinkedHashMap<String, String>()
            headers["Host"] = hostHeader
            headers["User-Agent"] = "LumaScheduleNative/0.1"
            headers["Accept"] = "application/json, application/xml, */*"
            headers["Connection"] = "close"
            if (auth != null) headers["Authorization"] = "Basic $auth"
            extraHeaders.forEach { (key, value) -> headers[key] = value }
            if (body != null) headers["Content-Length"] = body.size.toString()
            else if (method == "PROPFIND" || method == "PUT") headers["Content-Length"] = "0"

            output.write("$method $targetPath HTTP/1.1\r\n".toByteArray(StandardCharsets.US_ASCII))
            headers.forEach { (key, value) ->
                output.write("$key: $value\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            if (body != null) output.write(body)
            output.flush()
            readResponse(input)
        }
    }

    private fun openSocket(host: String, port: Int, secure: Boolean): Socket {
        if (!secure) {
            return Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        }
        val plain = Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, host, port, true) as SSLSocket
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    private fun buildRequestTarget(uri: URI): String {
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.takeIf { it.isNotEmpty() }
        return if (query == null) path else "$path?$query"
    }

    private fun readResponse(input: BufferedInputStream): Response {
        val statusLine = readAsciiLine(input) ?: error("WebDAV 服务器没有返回 HTTP 状态行。")
        val parts = statusLine.split(' ', limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: error("WebDAV HTTP 状态行无效：$statusLine")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunkedBody(input)
            headers["content-length"]?.toLongOrNull() != null -> readFixedBody(input, headers.getValue("content-length").toLong())
            else -> input.readBytes()
        }
        return Response(status, headers, body)
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input) ?: error("WebDAV chunked 响应提前结束。")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: error("WebDAV chunk 大小无效。")
            if (size == 0) {
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            val buffer = ByteArray(size)
            readFully(input, buffer)
            output.write(buffer)
            readAsciiLine(input) // trailing CRLF
        }
        return output.toByteArray()
    }

    private fun readFixedBody(input: BufferedInputStream, length: Long): ByteArray {
        require(length <= MAX_RESPONSE_BYTES) { "WebDAV 响应过大。" }
        val buffer = ByteArray(length.toInt())
        readFully(input, buffer)
        return buffer
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) error("WebDAV 响应提前结束。")
            offset += read
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val output = java.io.ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) {
                if (output.size() == 0) return null
                break
            }
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = output.toByteArray()
                return String(bytes, 0, (bytes.size - 1).coerceAtLeast(0), StandardCharsets.ISO_8859_1)
            }
            output.write(current)
            previous = current
            if (output.size() > MAX_HEADER_LINE_BYTES) error("WebDAV HTTP 头过长。")
        }
        return output.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun result(
        ok: Boolean,
        status: Int,
        message: String,
        remoteUrl: String?,
        etag: String?
    ): JSONObject = JSONObject()
        .put("ok", ok)
        .put("status", status)
        .put("message", message)
        .put("remoteUrl", remoteUrl ?: JSONObject.NULL)
        .put("etag", etag ?: JSONObject.NULL)

    private data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_LINE_BYTES = 32 * 1024
        private const val MAX_RESPONSE_BYTES = 32L * 1024L * 1024L
    }
}
