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
        val script = readAsset(safeAssetPath("shiguang_warehouse/resources/${school.resourceFolder}/${adapter.assetJsPath}"))
        val displaySchool = schoolName.trim().ifBlank { Uri.parse(url).host.orEmpty() }
        return launchSession(
            activity = activity,
            importUrl = url,
            script = script,
            adapterName = "兼容模式 · ${adapter.adapterName}",
            schoolName = displaySchool,
            captureKind = "schedule"
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
        captureKind: String
    ): JSONObject {
        val allowedHosts = collectLoginHosts(importUrl)
        require(allowedHosts.isNotEmpty()) { "无法从登录地址确定允许访问的教务域名" }
        val insecure = containsHttpUrl(importUrl)
        val sessionId = UUID.randomUUID().toString()
        val sha = sha256(script)

        val prefs = context.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(ShiguangImportActivity.key(sessionId, "status"), "running")
            .putString(ShiguangImportActivity.key(sessionId, "message"), if (captureKind == "grades") "请登录教务系统并进入成绩查询页面，然后点击抓取成绩。" else "正在打开教务登录窗口…")
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
