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
            ?: error("适配仓库中找不到学校：$schoolId")
        return JSONArray(adapters(school).map { it.toJson() })
    }

    fun startImport(activity: Activity, schoolId: String, adapterId: String): JSONObject {
        val school = schools().firstOrNull { it.id.equals(schoolId, ignoreCase = true) }
            ?: error("适配仓库中找不到学校：$schoolId")
        val adapter = adapters(school).firstOrNull { it.adapterId == adapterId }
            ?: error("找不到适配器：$adapterId")
        require(adapter.importUrl.startsWith("https://") || adapter.importUrl.startsWith("http://")) { "适配器登录地址无效" }
        require(adapter.assetJsPath.isNotBlank()) { "适配器脚本路径为空" }

        val scriptPath = safeAssetPath("shiguang_warehouse/resources/${school.resourceFolder}/${adapter.assetJsPath}")
        val script = loadAdapterScript(scriptPath)
        return launchSession(
            activity = activity,
            importUrl = adapter.importUrl,
            script = script,
            adapterName = adapter.adapterName,
            schoolName = school.name,
            captureKind = "schedule"
        )
    }

    fun startCustomImport(activity: Activity, rawUrl: String, family: String, schoolName: String): JSONObject {
        require(family in GENERIC_FAMILIES) { "暂不支持的通用教务类型：$family" }
        val url = normalizeCustomUrl(rawUrl)
        val school = schools().firstOrNull { it.id.equals(family, ignoreCase = true) }
            ?: error("内置快照缺少通用教务解析器：$family")
        val adapter = adapters(school).firstOrNull()
            ?: error("通用教务解析器没有可用脚本：$family")
        require(adapter.assetJsPath.isNotBlank()) { "通用教务解析器脚本为空" }
        val script = loadAdapterScript(safeAssetPath("shiguang_warehouse/resources/${school.resourceFolder}/${adapter.assetJsPath}"))
        val displaySchool = schoolName.trim().ifBlank { Uri.parse(url).host.orEmpty() }
        return launchSession(
            activity = activity,
            importUrl = url,
            script = script,
            adapterName = "兼容模式 · ${adapter.adapterName}",
            schoolName = displaySchool,
            captureKind = "schedule",
            manualTrigger = true
        )
    }

    fun startSmartImport(activity: Activity, rawUrl: String, schoolName: String): JSONObject {
        val url = normalizeCustomUrl(rawUrl)
        val displaySchool = schoolName.trim().ifBlank { Uri.parse(url).host.orEmpty() }
        val detectedFamily = detectGenericFamily(url)
        if (detectedFamily != null) {
            val school = schools().firstOrNull { it.id.equals(detectedFamily, ignoreCase = true) }
            val adapter = school?.let(::adapters)?.firstOrNull()
            if (school != null && adapter != null && adapter.assetJsPath.isNotBlank()) {
                val script = loadAdapterScript(safeAssetPath("shiguang_warehouse/resources/${school.resourceFolder}/${adapter.assetJsPath}"))
                return launchSession(
                    activity = activity,
                    importUrl = url,
                    script = script,
                    adapterName = "智能识别 · ${adapter.adapterName}",
                    schoolName = displaySchool,
                    captureKind = "schedule",
                    manualTrigger = true
                )
            }
        }
        return launchSession(
            activity = activity,
            importUrl = url,
            script = SMART_SCHEDULE_CAPTURE_SCRIPT,
            adapterName = "智能兼容",
            schoolName = displaySchool,
            captureKind = "schedule",
            manualTrigger = true
        )
    }

    fun startGradeCapture(activity: Activity, rawUrl: String, institution: String): JSONObject {
        val url = normalizeCustomUrl(rawUrl)
        val host = Uri.parse(url).host.orEmpty()
        return launchSession(
            activity = activity,
            importUrl = url,
            script = GRADE_CAPTURE_SCRIPT,
            adapterName = "兼容成绩抓取",
            schoolName = institution.trim().ifBlank { host },
            captureKind = "grades"
        )
    }

    private fun launchSession(
        activity: Activity,
        importUrl: String,
        script: String,
        adapterName: String,
        schoolName: String,
        captureKind: String,
        manualTrigger: Boolean = false
    ): JSONObject {
        val allowedHosts = collectLoginHosts(importUrl)
        require(allowedHosts.isNotEmpty()) { "无法从登录地址确定允许访问的教务域名" }
        val insecure = containsHttpUrl(importUrl)
        val sessionId = UUID.randomUUID().toString()
        val sha = sha256(script)
        val initialMessage = when {
            captureKind == "grades" -> "请登录教务系统并进入成绩查询页面，然后点击抓取成绩。"
            manualTrigger -> "请登录教务系统并进入个人课表页面，查询课表后点击尝试抓取。"
            else -> "正在打开教务登录窗口…"
        }

        val prefs = context.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(ShiguangImportActivity.key(sessionId, "status"), "running")
            .putString(ShiguangImportActivity.key(sessionId, "message"), initialMessage)
            .putString(ShiguangImportActivity.key(sessionId, "adapter_name"), adapterName)
            .putString(ShiguangImportActivity.key(sessionId, "school_name"), schoolName)
            .putString(ShiguangImportActivity.key(sessionId, "source_sha256"), sha)
            .putString(ShiguangImportActivity.key(sessionId, "capture_kind"), captureKind)
            .apply()

        val intent = Intent(activity, ShiguangImportActivity::class.java).apply {
            putExtra(ShiguangImportActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(ShiguangImportActivity.EXTRA_IMPORT_URL, importUrl)
            putExtra(ShiguangImportActivity.EXTRA_ADAPTER_SCRIPT, script)
            putExtra(ShiguangImportActivity.EXTRA_ALLOWED_HOSTS_JSON, JSONArray(allowedHosts.sorted()).toString())
            putExtra(ShiguangImportActivity.EXTRA_ADAPTER_NAME, adapterName)
            putExtra(ShiguangImportActivity.EXTRA_SCHOOL_NAME, schoolName)
            putExtra(ShiguangImportActivity.EXTRA_INSECURE_TRANSPORT, insecure)
            putExtra(ShiguangImportActivity.EXTRA_CAPTURE_KIND, captureKind)
            putExtra(ShiguangImportActivity.EXTRA_MANUAL_TRIGGER, manualTrigger)
        }
        activity.runOnUiThread { activity.startActivity(intent) }

        return JSONObject()
            .put("sessionId", sessionId)
            .put("adapterName", adapterName)
            .put("schoolName", schoolName)
            .put("sourceSha256", sha)
            .put("allowedHosts", JSONArray(allowedHosts.sorted()))
            .put("insecureTransport", insecure)
            .put("captureKind", captureKind)
            .put("status", "running")
    }

    fun session(sessionId: String): JSONObject {
        require(sessionId.isNotBlank()) { "导入会话 ID 为空" }
        val prefs = context.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val key: (String) -> String = { field -> ShiguangImportActivity.key(sessionId, field) }
        val status = prefs.getString(key("status"), "running") ?: "running"
        val adapterName = prefs.getString(key("adapter_name"), "") ?: ""
        val schoolName = prefs.getString(key("school_name"), "") ?: ""
        val captureKind = prefs.getString(key("capture_kind"), "schedule") ?: "schedule"
        val message = prefs.getString(key("message"), null)
        val coursesRaw = prefs.getString(key("courses"), null)
        val slotsRaw = prefs.getString(key("time_slots"), null)
        val configRaw = prefs.getString(key("config"), null)
        val gradesRaw = prefs.getString(key("grades"), null)
        val gradeMetaRaw = prefs.getString(key("grade_meta"), null)

        val out = JSONObject()
            .put("sessionId", sessionId)
            .put("adapterName", adapterName)
            .put("schoolName", schoolName)
            .put("captureKind", captureKind)
            .put("status", status)
        if (!message.isNullOrBlank()) out.put("message", message)
        parseJson(slotsRaw)?.let { out.put("timeSlots", it) }
        parseJson(configRaw)?.let { out.put("config", it) }
        if (status == "complete" && captureKind == "grades" && !gradesRaw.isNullOrBlank()) {
            out.put("gradeBundle", buildGradeBundle(schoolName, adapterName, gradesRaw, gradeMetaRaw))
        } else if (status == "complete" && !coursesRaw.isNullOrBlank()) {
            out.put("bundle", buildBundle(schoolName, adapterName, coursesRaw, slotsRaw, configRaw))
        }
        return out
    }

    fun closeSession(sessionId: String) {
        val prefs = context.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (field in listOf("status", "message", "courses", "time_slots", "config", "grades", "grade_meta", "adapter_name", "school_name", "source_sha256", "capture_kind")) {
            editor.remove(ShiguangImportActivity.key(sessionId, field))
        }
        editor.apply()
    }

    private fun buildGradeBundle(schoolName: String, adapterName: String, gradesRaw: String, metaRaw: String?): JSONObject {
        val records = runCatching { JSONArray(gradesRaw) }.getOrElse { error("成绩数据不是有效 JSON 数组") }
        val meta = metaRaw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        return JSONObject()
            .put("source", "Luma · $adapterName")
            .put("institution", schoolName)
            .put("termName", meta.optString("termName").trim().ifBlank { "未分组" })
            .put("records", records)
            .put(
                "metadata",
                JSONObject()
                    .put("sourceUrl", meta.optString("sourceUrl"))
                    .put("pageTitle", meta.optString("pageTitle"))
            )
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
            .put("source", "Luma · $adapterName")
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

    private fun detectGenericFamily(url: String): String? {
        val lower = url.lowercase()
        return when {
            "chaoxing" in lower || "xueyinonline" in lower || "mooc1" in lower -> "chaoxing_jiaowu"
            "qingguo" in lower -> "qingguo_jiaowu"
            "urp" in lower -> "urp_jiaowu"
            "jwglxt" in lower || "/xtgl/" in lower -> "zhengfang_jiaowu"
            else -> null
        }
    }

    private fun normalizeCustomUrl(raw: String): String {
        var value = raw.trim()
        require(value.isNotBlank()) { "请粘贴学校官方教务系统地址" }
        if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) value = "https://$value"
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: error("教务系统地址格式无效")
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) { "只允许 HTTP/HTTPS 教务地址" }
        require(!uri.host.isNullOrBlank()) { "教务系统地址缺少域名" }
        require(uri.userInfo.isNullOrBlank()) { "请不要在 URL 中包含账号或密码" }
        return uri.toString()
    }

    private fun readAsset(path: String): String = context.assets
        .open(safeAssetPath(path))
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }

    /**
     * Prefer first-party adapter overrides shipped outside the upstream warehouse
     * snapshot, then fall back to the bundled shiguang script.
     */
    private fun loadAdapterScript(warehousePath: String): String {
        val normalized = safeAssetPath(warehousePath)
        val marker = "shiguang_warehouse/resources/"
        val overridePath = if (normalized.startsWith(marker)) {
            "shiguang_overrides/" + normalized.removePrefix(marker)
        } else {
            "shiguang_overrides/$normalized"
        }
        val override = runCatching { readAsset(overridePath) }.getOrNull()
        if (!override.isNullOrBlank()) return override
        return readAsset(normalized)
    }

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

    companion object {
        private val GENERIC_FAMILIES = setOf("zhengfang_jiaowu", "qingguo_jiaowu", "urp_jiaowu", "chaoxing_jiaowu")

        private val SMART_SCHEDULE_CAPTURE_SCRIPT = """
(async () => {
  const clean = (value) => String(value == null ? '' : value).replace(/\u00a0/g, ' ').replace(/[ \t]+/g, ' ').trim();
  const cellText = (node) => clean(node && (node.innerText || node.textContent));
  const weekdayOf = (raw) => {
    const text = clean(raw);
    const names = [['一',1],['二',2],['三',3],['四',4],['五',5],['六',6],['日',7],['天',7]];
    for (const item of names) if (new RegExp('(星期|周)' + item[0]).test(text)) return item[1];
    const match = text.match(/(?:星期|周)\s*([1-7])/);
    return match ? Number(match[1]) : 0;
  };
  const sectionRange = (raw) => {
    const text = clean(raw);
    let match = text.match(/第?\s*(\d{1,2})\s*[-~—–至到]\s*(\d{1,2})\s*节?/);
    if (match) return [Number(match[1]), Number(match[2])];
    match = text.match(/第\s*(\d{1,2})\s*节/);
    return match ? [Number(match[1]), Number(match[1])] : null;
  };
  const weeksOf = (raw) => {
    const text = clean(raw);
    const result = new Set();
    const matches = text.matchAll(/(\d{1,2})\s*[-~—–至到]\s*(\d{1,2})\s*周/g);
    for (const match of matches) {
      const from = Math.max(1, Math.min(64, Number(match[1])));
      const to = Math.max(1, Math.min(64, Number(match[2])));
      for (let week = Math.min(from, to); week <= Math.max(from, to); week += 1) result.add(week);
    }
    if (!result.size) {
      const singles = text.matchAll(/(?:第)?\s*(\d{1,2})\s*周/g);
      for (const match of singles) result.add(Math.max(1, Math.min(64, Number(match[1]))));
    }
    const odd = /单周|单数周/.test(text);
    const even = /双周|偶数周/.test(text);
    if ((odd || even) && result.size) {
      for (const week of Array.from(result)) if ((odd && week % 2 === 0) || (even && week % 2 === 1)) result.delete(week);
    }
    return result.size ? Array.from(result).sort((a,b) => a-b) : Array.from({length:20}, (_,i) => i + 1);
  };
  const field = (raw, keys) => {
    const text = String(raw || '');
    for (const key of keys) {
      const match = text.match(new RegExp(key + '\\s*[:：]?\\s*([^\\n；;]+)', 'i'));
      if (match) return clean(match[1]);
    }
    return '';
  };
  const guessLocation = (lines) => lines.find((line) => /(教室|实验室|楼|校区|馆|室\b|区\b)/.test(line) && !/(周|节)/.test(line)) || '';
  const guessTeacher = (lines) => field(lines.join('\n'), ['教师','老师','任课教师']) || '';
  const guessName = (lines) => lines.find((line) => line && !/(第?\d+.*周|第?\d+.*节|教师|老师|教室|地点|星期|周[一二三四五六日天])/.test(line)) || '';
  const records = [];
  const seen = new Set();
  const push = (record) => {
    if (!record.name || !(record.day >= 1 && record.day <= 7) || !(record.startSection > 0)) return;
    record.endSection = Math.max(record.startSection, record.endSection || record.startSection);
    const key = [record.name, record.day, record.startSection, record.endSection, record.position || '', record.teacher || ''].join('|');
    if (seen.has(key)) return;
    seen.add(key);
    records.push(record);
  };
  const findIndex = (headers, rules) => headers.findIndex((header) => rules.some((rule) => rule.test(header)));

  for (const table of Array.from(document.querySelectorAll('table'))) {
    const rows = Array.from(table.querySelectorAll('tr'));
    for (let headerIndex = 0; headerIndex < Math.min(rows.length, 8); headerIndex += 1) {
      const headers = Array.from(rows[headerIndex].querySelectorAll('th,td')).map(cellText);
      const nameIndex = findIndex(headers, [/课程名称/i,/课程名/i,/科目/i]);
      const dayIndex = findIndex(headers, [/星期/i,/周几/i,/上课日/i]);
      const sectionIndex = findIndex(headers, [/节次/i,/上课节次/i,/时间段/i]);
      if (nameIndex < 0 || dayIndex < 0 || sectionIndex < 0) continue;
      const teacherIndex = findIndex(headers, [/教师/i,/老师/i]);
      const roomIndex = findIndex(headers, [/教室/i,/地点/i,/上课地点/i]);
      const weeksIndex = findIndex(headers, [/周次/i,/教学周/i,/上课周/i]);
      for (let rowIndex = headerIndex + 1; rowIndex < rows.length; rowIndex += 1) {
        const cells = Array.from(rows[rowIndex].querySelectorAll('th,td')).map(cellText);
        const range = sectionRange(cells[sectionIndex] || '');
        const day = weekdayOf(cells[dayIndex] || '');
        if (!range || !day) continue;
        push({
          name: clean(cells[nameIndex]),
          teacher: teacherIndex >= 0 ? clean(cells[teacherIndex]) : '',
          position: roomIndex >= 0 ? clean(cells[roomIndex]) : '',
          day,
          startSection: range[0],
          endSection: range[1],
          weeks: weeksOf(weeksIndex >= 0 ? cells[weeksIndex] : ''),
          startTime: '', endTime: ''
        });
      }
    }
  }

  if (!records.length) {
    for (const table of Array.from(document.querySelectorAll('table'))) {
      const rows = Array.from(table.querySelectorAll('tr'));
      let headerIndex = -1;
      let dayColumns = [];
      for (let i = 0; i < Math.min(rows.length, 10); i += 1) {
        const cells = Array.from(rows[i].querySelectorAll('th,td')).map(cellText);
        const mapped = cells.map((value, index) => [index, weekdayOf(value)]).filter((item) => item[1]);
        if (mapped.length >= 3) { headerIndex = i; dayColumns = mapped; break; }
      }
      if (headerIndex < 0) continue;
      for (let rowIndex = headerIndex + 1; rowIndex < rows.length; rowIndex += 1) {
        const cells = Array.from(rows[rowIndex].querySelectorAll('th,td'));
        const rowText = cells.map(cellText);
        const rowRange = sectionRange(rowText.slice(0, 2).join(' '));
        for (const pair of dayColumns) {
          const column = pair[0]; const day = pair[1];
          const raw = cellText(cells[column]);
          if (!raw || raw.length < 2) continue;
          const range = sectionRange(raw) || rowRange;
          if (!range) continue;
          const lines = raw.split(/\n+/).map(clean).filter(Boolean);
          const name = guessName(lines);
          if (!name) continue;
          push({
            name,
            teacher: guessTeacher(lines),
            position: field(raw, ['教室','地点','上课地点']) || guessLocation(lines),
            day,
            startSection: range[0],
            endSection: range[1],
            weeks: weeksOf(raw),
            startTime: '', endTime: ''
          });
        }
      }
    }
  }

  if (!records.length) {
    await window.shiguangBridgePromise.showAlert(
      '暂时没有识别到课表',
      '请先进入教务系统的个人课表/我的课表页面并完成查询，再重新点击「尝试抓取课表」。如果仍然失败，可返回 LumaSchedule 在高级选项中手动选择教务系统类型。',
      '知道了'
    );
    return;
  }

  const bodyText = clean(document.body && document.body.innerText);
  const termMatch = bodyText.match(/20\d{2}\s*[-—–~至]\s*20?\d{2}\s*学年.{0,12}(?:第一|第二|第1|第2|春|秋).{0,4}学期/);
  if (termMatch) {
    await window.shiguangBridgePromise.saveCourseConfig(JSON.stringify({ semesterName: clean(termMatch[0]), semesterStartDate: '' }));
  }
  await window.shiguangBridgePromise.saveImportedCourses(JSON.stringify(records));
  window.shiguangBridge.showToast('智能兼容识别到 ' + records.length + ' 个课程时段。');
  window.shiguangBridge.notifyTaskCompletion();
})();
""".trimIndent()

        private val GRADE_CAPTURE_SCRIPT = """
window.__LUMA_CAPTURE_GRADES__ = async function () {
  const text = (node) => String(node && node.textContent || '').replace(/\s+/g, ' ').trim();
  const cellsOf = (row) => Array.from(row.querySelectorAll('th,td')).map(text);
  const findColumn = (headers, rules) => headers.findIndex((header) => rules.some((rule) => rule.test(header)));
  const toNumber = (raw) => {
    const value = String(raw || '').replace(/,/g, '').trim();
    const match = value.match(/-?\d+(?:\.\d+)?/);
    if (!match) return null;
    const number = Number(match[0]);
    return Number.isFinite(number) ? number : null;
  };
  const termFromPage = () => {
    const body = String(document.body && document.body.innerText || '').replace(/\s+/g, ' ');
    const match = body.match(/20\d{2}\s*[-—–~至]\s*20?\d{2}\s*学年.{0,10}(?:第一|第二|第1|第2|春|秋).{0,4}学期/);
    return match ? match[0].replace(/\s+/g, '') : '';
  };

  let best = [];
  let bestTerm = '';
  const tables = Array.from(document.querySelectorAll('table'));
  for (const table of tables) {
    const rows = Array.from(table.querySelectorAll('tr'));
    for (let headerIndex = 0; headerIndex < Math.min(rows.length, 6); headerIndex += 1) {
      const headers = cellsOf(rows[headerIndex]);
      if (headers.length < 2) continue;
      const courseNameIndex = findColumn(headers, [/课程名称/i, /课程名/i, /科目名称/i, /^科目/i]);
      const courseCodeIndex = findColumn(headers, [/课程代码/i, /课程编号/i, /课程号/i]);
      const scoreIndex = findColumn(headers, [/总评成绩/i, /最终成绩/i, /课程成绩/i, /^成绩/i]);
      const pointIndex = findColumn(headers, [/学分绩点/i, /^绩点/i]);
      const creditIndex = findColumn(headers, [/^学分/i]);
      const typeIndex = findColumn(headers, [/课程性质/i, /课程属性/i, /课程类别/i, /修读性质/i]);
      const termIndex = findColumn(headers, [/学年学期/i, /^学期/i]);
      if (courseNameIndex < 0 || (scoreIndex < 0 && pointIndex < 0)) continue;

      const records = [];
      for (let rowIndex = headerIndex + 1; rowIndex < rows.length; rowIndex += 1) {
        const cells = cellsOf(rows[rowIndex]);
        if (cells.length <= courseNameIndex) continue;
        const courseName = cells[courseNameIndex] || '';
        if (!courseName || /课程名称|课程名|科目名称/.test(courseName)) continue;
        const scoreText = scoreIndex >= 0 ? (cells[scoreIndex] || '') : '';
        const courseType = typeIndex >= 0 ? (cells[typeIndex] || '') : '';
        const term = termIndex >= 0 ? (cells[termIndex] || '') : '';
        records.push({
          courseName,
          courseCode: courseCodeIndex >= 0 ? (cells[courseCodeIndex] || '') : '',
          courseType,
          credit: creditIndex >= 0 ? toNumber(cells[creditIndex]) : null,
          scoreText,
          numericScore: toNumber(scoreText),
          gradePoint: pointIndex >= 0 ? toNumber(cells[pointIndex]) : null,
          elective: /选修|任选|限选|公选/.test(courseType),
          attempt: 1,
          term
        });
      }
      if (records.length > best.length) {
        best = records;
        bestTerm = records.find((item) => item.term)?.term || '';
      }
    }
  }

  if (!best.length) {
    await window.shiguangBridgePromise.showAlert(
      '没有识别到成绩表',
      '请先进入教务系统的成绩查询/历年成绩页面，并确保成绩表已经加载出来。兼容模式目前优先识别标准 HTML 表格；若学校使用特殊组件，需要单独适配。',
      '知道了'
    );
    return;
  }

  const termName = bestTerm || termFromPage();
  await window.shiguangBridgePromise.saveImportedGrades(JSON.stringify(best));
  await window.shiguangBridgePromise.saveGradeMeta(JSON.stringify({
    termName,
    sourceUrl: location.href,
    pageTitle: document.title || ''
  }));
  window.shiguangBridge.showToast('识别到 ' + best.length + ' 条成绩，正在返回 LumaSchedule 预览。');
  window.shiguangBridge.notifyTaskCompletion();
};
""".trimIndent()
    }
}
