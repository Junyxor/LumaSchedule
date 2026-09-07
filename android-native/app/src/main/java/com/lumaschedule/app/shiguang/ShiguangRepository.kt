package com.lumaschedule.app.shiguang

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class ShiguangRepository(private val context: Context) {
    @Volatile private var schoolCache: List<School>? = null

    fun listSchools(query: String?): JSONArray {
        val all = schools()
        val needle = query.orEmpty().trim().lowercase()
        val filtered = if (needle.isBlank()) all else all.filter {
            it.name.lowercase().contains(needle) ||
                it.id.lowercase().contains(needle) ||
                it.initial.lowercase().contains(needle)
        }
        return JSONArray(
            filtered
                .sortedWith(compareBy<School> { it.initial }.thenBy { it.name })
                .map { it.toJson() }
        )
    }

    fun listAdapters(schoolId: String): JSONArray {
        val school = schools().firstOrNull { it.id.equals(schoolId, ignoreCase = true) }
            ?: error("拾光适配仓库中找不到学校：$schoolId")
        return JSONArray(adapters(school).map { it.toJson() })
    }

    fun startImport(activity: Activity, schoolId: String, adapterId: String): JSONObject {
        val school = schools().firstOrNull { it.id.equals(schoolId, ignoreCase = true) }
            ?: error("拾光适配仓库中找不到学校：$schoolId")
        val adapter = adapters(school).firstOrNull { it.adapterId == adapterId }
            ?: error("找不到适配器：$adapterId")
        require(adapter.importUrl.startsWith("https://") || adapter.importUrl.startsWith("http://")) { "适配器登录地址无效" }
        require(adapter.assetJsPath.isNotBlank()) { "适配器脚本路径为空" }

        val scriptPath = safeAssetPath("shiguang_warehouse/resources/${school.resourceFolder}/${adapter.assetJsPath}")
        val script = readAsset(scriptPath)
        val allowedHosts = collectLoginHosts(adapter.importUrl)
        require(allowedHosts.isNotEmpty()) { "无法从适配器登录地址确定允许访问的教务域名" }
        val insecure = containsHttpUrl(adapter.importUrl)
        val sessionId = UUID.randomUUID().toString()
        val sha = sha256(script)

        val prefs = context.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(ShiguangImportActivity.key(sessionId, "status"), "running")
            .putString(ShiguangImportActivity.key(sessionId, "message"), "正在打开教务登录窗口…")
            .putString(ShiguangImportActivity.key(sessionId, "adapter_name"), adapter.adapterName)
            .putString(ShiguangImportActivity.key(sessionId, "school_name"), school.name)
            .putString(ShiguangImportActivity.key(sessionId, "source_sha256"), sha)
            .apply()

        val intent = Intent(activity, ShiguangImportActivity::class.java).apply {
            putExtra(ShiguangImportActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(ShiguangImportActivity.EXTRA_IMPORT_URL, adapter.importUrl)
            putExtra(ShiguangImportActivity.EXTRA_ADAPTER_SCRIPT, script)
            putExtra(ShiguangImportActivity.EXTRA_ALLOWED_HOSTS_JSON, JSONArray(allowedHosts.sorted()).toString())
            putExtra(ShiguangImportActivity.EXTRA_ADAPTER_NAME, adapter.adapterName)
            putExtra(ShiguangImportActivity.EXTRA_SCHOOL_NAME, school.name)
            putExtra(ShiguangImportActivity.EXTRA_INSECURE_TRANSPORT, insecure)
        }
        activity.runOnUiThread { activity.startActivity(intent) }

        return JSONObject()
            .put("sessionId", sessionId)
            .put("adapterName", adapter.adapterName)
            .put("schoolName", school.name)
            .put("sourceSha256", sha)
            .put("allowedHosts", JSONArray(allowedHosts.sorted()))
            .put("insecureTransport", insecure)
            .put("status", "running")
    }

    fun session(sessionId: String): JSONObject {
        require(sessionId.isNotBlank()) { "导入会话 ID 为空" }
        val prefs = context.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val key: (String) -> String = { field -> ShiguangImportActivity.key(sessionId, field) }
        val status = prefs.getString(key("status"), "running") ?: "running"
        val adapterName = prefs.getString(key("adapter_name"), "") ?: ""
        val schoolName = prefs.getString(key("school_name"), "") ?: ""
        val message = prefs.getString(key("message"), null)
        val coursesRaw = prefs.getString(key("courses"), null)
        val slotsRaw = prefs.getString(key("time_slots"), null)
        val configRaw = prefs.getString(key("config"), null)

        val out = JSONObject()
            .put("sessionId", sessionId)
            .put("adapterName", adapterName)
            .put("schoolName", schoolName)
            .put("status", status)
        if (!message.isNullOrBlank()) out.put("message", message)
        parseJson(slotsRaw)?.let { out.put("timeSlots", it) }
        parseJson(configRaw)?.let { out.put("config", it) }
        if (status == "complete" && !coursesRaw.isNullOrBlank()) {
            out.put("bundle", buildBundle(schoolName, adapterName, coursesRaw, slotsRaw, configRaw))
        }
        return out
    }

    fun closeSession(sessionId: String) {
        val prefs = context.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (field in listOf("status", "message", "courses", "time_slots", "config", "adapter_name", "school_name", "source_sha256")) {
            editor.remove(ShiguangImportActivity.key(sessionId, field))
        }
        editor.apply()
    }

    private fun buildBundle(schoolName: String, adapterName: String, coursesRaw: String, slotsRaw: String?, configRaw: String?): JSONObject {
        val array = runCatching { JSONArray(coursesRaw) }.getOrElse { error("适配器课程数据不是有效 JSON 数组") }
        val merged = LinkedHashMap<String, MutableCourse>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            val teacher = item.optString("teacher").trim()
            val location = item.optString("position", item.optString("location")).trim()
            val day = item.optInt("day", item.optInt("weekday", 0))
            val startSection = item.optInt("startSection", 0)
            val endSection = item.optInt("endSection", startSection)
            if (day !in 1..7 || startSection <= 0 || endSection < startSection) continue
            val startTime = item.optString("startTime").trim()
            val endTime = item.optString("endTime").trim()
            val mergeKey = listOf(name, teacher, location, day, startSection, endSection, startTime, endTime).joinToString("\u001f")
            val target = merged.getOrPut(mergeKey) {
                MutableCourse(name, teacher, location, day, startSection, endSection, startTime, endTime, linkedSetOf())
            }
            val weeks = item.optJSONArray("weeks")
            if (weeks != null) {
                for (j in 0 until weeks.length()) {
                    weeks.optInt(j).takeIf { it in 1..64 }?.let(target.weeks::add)
                }
            }
        }

        val courses = JSONArray()
        merged.values.forEach { item ->
            if (item.weeks.isEmpty()) (1..20).forEach(item.weeks::add)
            courses.put(
                JSONObject()
                    .put("name", item.name)
                    .put("teacher", item.teacher.ifBlank { JSONObject.NULL })
                    .put("location", item.location.ifBlank { JSONObject.NULL })
                    .put("weekday", item.day)
                    .put("startSection", item.startSection)
                    .put("endSection", item.endSection)
                    .put("weeks", JSONArray(item.weeks.sorted()))
                    .put("startTime", item.startTime.ifBlank { JSONObject.NULL })
                    .put("endTime", item.endTime.ifBlank { JSONObject.NULL })
            )
        }

        val config = configRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val metadata = JSONObject()
        if (!slotsRaw.isNullOrBlank()) metadata.put("timeScheme", slotsRaw)
        if (!configRaw.isNullOrBlank()) metadata.put("courseConfig", configRaw)
        val termStart = config?.optString("semesterStartDate").orEmpty().ifBlank { null }
        val termName = config?.optString("semesterName").orEmpty().ifBlank { "$schoolName · 导入学期" }

        return JSONObject()
            .put("source", "Shiguang · $adapterName")
            .put("termName", termName)
            .put("termStart", termStart ?: JSONObject.NULL)
            .put("courses", courses)
            .put("metadata", metadata)
    }

    private fun schools(): List<School> = schoolCache ?: synchronized(this) {
        schoolCache ?: loadSchools().also { schoolCache = it }
    }

    private fun loadSchools(): List<School> {
        val text = readAsset("shiguang_warehouse/index/root_index.yaml")
        return parseYamlList(text, "schools").mapNotNull { raw ->
            val id = raw["id"].orEmpty()
            val name = raw["name"].orEmpty()
            if (id.isBlank() || name.isBlank()) null else School(
                id = id,
                name = name,
                initial = raw["initial"].orEmpty(),
                resourceFolder = raw["resource_folder"].orEmpty().ifBlank { id }
            )
        }
    }

    private fun adapters(school: School): List<Adapter> {
        val text = readAsset(safeAssetPath("shiguang_warehouse/resources/${school.resourceFolder}/adapters.yaml"))
        return parseYamlList(text, "adapters").mapNotNull { raw ->
            val id = raw["adapter_id"].orEmpty()
            if (id.isBlank()) null else Adapter(
                school.id,
                school.name,
                school.resourceFolder,
                id,
                raw["adapter_name"].orEmpty().ifBlank { id },
                raw["category"].orEmpty(),
                raw["asset_js_path"].orEmpty(),
                raw["import_url"].orEmpty(),
                raw["maintainer"].orEmpty(),
                raw["description"].orEmpty()
            )
        }
    }

    private fun readAsset(path: String): String = context.assets
        .open(safeAssetPath(path))
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }

    private fun safeAssetPath(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it.isBlank() || it == ".." }) { "非法适配器资源路径" }
        return normalized
    }

    private fun parseYamlList(text: String, root: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        var current: LinkedHashMap<String, String>? = null
        var inside = false
        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#')) continue
            if (!inside) {
                if (trimmed == "$root:") inside = true
                continue
            }
            if (rawLine.isNotEmpty() && !rawLine.first().isWhitespace() && !trimmed.startsWith('-')) break
            if (trimmed.startsWith("- ")) {
                current?.takeIf { it.isNotEmpty() }?.let(result::add)
                current = linkedMapOf()
                parseYamlPair(trimmed.removePrefix("- "))?.let { (key, value) -> current[key] = value }
            } else if (current != null) {
                parseYamlPair(trimmed)?.let { (key, value) -> current[key] = value }
            }
        }
        current?.takeIf { it.isNotEmpty() }?.let(result::add)
        return result
    }

    private fun parseYamlPair(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val key = line.substring(0, colon).trim()
        var value = stripYamlComment(line.substring(colon + 1)).trim()
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length - 1)
            }
        }
        return key to value.replace("\\\"", "\"").replace("\\'", "'")
    }

    private fun stripYamlComment(raw: String): String {
        var singleQuoted = false
        var doubleQuoted = false
        var escaped = false
        for (index in raw.indices) {
            val char = raw[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && doubleQuoted) {
                escaped = true
                continue
            }
            when (char) {
                '\'' -> if (!doubleQuoted) singleQuoted = !singleQuoted
                '"' -> if (!singleQuoted) doubleQuoted = !doubleQuoted
                '#' -> if (!singleQuoted && !doubleQuoted && (index == 0 || raw[index - 1].isWhitespace())) {
                    return raw.substring(0, index)
                }
            }
        }
        return raw
    }

    private fun collectLoginHosts(importUrl: String): Set<String> {
        val hosts = linkedSetOf<String>()
        val seen = hashSetOf<String>()
        fun visit(raw: String, depth: Int) {
            if (depth > 4 || !seen.add(raw)) return
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
            uri.host?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let(hosts::add)
            runCatching {
                uri.queryParameterNames.forEach { name ->
                    uri.getQueryParameters(name).forEach { value ->
                        if (value.startsWith("http://") || value.startsWith("https://")) visit(value, depth + 1)
                    }
                }
            }
        }
        visit(importUrl, 0)
        return hosts
    }

    private fun containsHttpUrl(importUrl: String): Boolean {
        var insecure = false
        val seen = hashSetOf<String>()
        fun visit(raw: String, depth: Int) {
            if (depth > 4 || !seen.add(raw)) return
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
            if (uri.scheme.equals("http", true)) insecure = true
            runCatching {
                uri.queryParameterNames.forEach { name ->
                    uri.getQueryParameters(name).forEach { value ->
                        if (value.startsWith("http://") || value.startsWith("https://")) visit(value, depth + 1)
                    }
                }
            }
        }
        visit(importUrl, 0)
        return insecure
    }

    private fun parseJson(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return runCatching { if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull()
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class School(val id: String, val name: String, val initial: String, val resourceFolder: String) {
        fun toJson() = JSONObject().put("id", id).put("name", name).put("initial", initial).put("resourceFolder", resourceFolder)
    }

    private data class Adapter(
        val schoolId: String,
        val schoolName: String,
        val resourceFolder: String,
        val adapterId: String,
        val adapterName: String,
        val category: String,
        val assetJsPath: String,
        val importUrl: String,
        val maintainer: String,
        val description: String
    ) {
        fun toJson() = JSONObject()
            .put("schoolId", schoolId)
            .put("schoolName", schoolName)
            .put("resourceFolder", resourceFolder)
            .put("adapterId", adapterId)
            .put("adapterName", adapterName)
            .put("category", category)
            .put("assetJsPath", assetJsPath)
            .put("importUrl", importUrl)
            .put("maintainer", maintainer)
            .put("description", description)
    }

    private data class MutableCourse(
        val name: String,
        val teacher: String,
        val location: String,
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val startTime: String,
        val endTime: String,
        val weeks: LinkedHashSet<Int>
    )
}
