use crate::widget_plugin;
use regex::Regex;
use schedule_core::{ImportBundle, ImportedCourse};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    sync::Mutex,
};
use tauri::{path::BaseDirectory, AppHandle, Manager, Runtime, State};
use tauri_plugin_fs::FsExt;
use url::Url;
use uuid::Uuid;

const WAREHOUSE_RAW_BASE: &str = "https://raw.githubusercontent.com/XingHeYuZhuan/shiguang_warehouse/main";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShiguangSchool {
    pub id: String,
    pub name: String,
    pub initial: String,
    #[serde(rename = "resourceFolder", alias = "resource_folder", default)]
    pub resource_folder: String,
}

#[derive(Debug, Deserialize)]
struct RootIndex { schools: Vec<ShiguangSchool> }

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShiguangAdapter {
    pub school_id: String,
    pub school_name: String,
    pub resource_folder: String,
    pub adapter_id: String,
    pub adapter_name: String,
    pub category: String,
    pub asset_js_path: String,
    pub import_url: String,
    pub maintainer: String,
    pub description: String,
}

#[derive(Debug, Deserialize)]
struct AdapterIndex { adapters: Vec<RawAdapter> }

#[derive(Debug, Clone, Deserialize)]
struct RawAdapter {
    adapter_id: String,
    adapter_name: String,
    category: String,
    asset_js_path: String,
    import_url: String,
    maintainer: String,
    description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShiguangImportStart {
    pub session_id: String,
    pub adapter_name: String,
    pub school_name: String,
    pub source_sha256: String,
    pub allowed_hosts: Vec<String>,
    pub insecure_transport: bool,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShiguangSessionSnapshot {
    pub session_id: String,
    pub adapter_name: String,
    pub school_name: String,
    pub status: String,
    pub message: Option<String>,
    pub bundle: Option<ImportBundle>,
    pub time_slots: Option<Value>,
    pub config: Option<Value>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShiguangBridgeRequest {
    pub session_id: String,
    pub op: String,
    pub payload: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ShiguangCourse {
    name: String,
    teacher: Option<String>,
    position: Option<String>,
    day: u8,
    start_section: u8,
    end_section: u8,
    weeks: Vec<u8>,
    #[serde(default)] is_custom_time: bool,
    #[serde(default)] start_time: Option<String>,
    #[serde(default)] end_time: Option<String>,
}

#[derive(Debug, Clone)]
struct DesktopSession {
    adapter: ShiguangAdapter,
    window_label: String,
    status: String,
    message: Option<String>,
    courses: Option<Vec<ShiguangCourse>>,
    time_slots: Option<Value>,
    config: Option<Value>,
}

pub struct ShiguangRuntime {
    client: reqwest::Client,
    schools_cache: Mutex<Option<Vec<ShiguangSchool>>>,
    sessions: Mutex<HashMap<String, DesktopSession>>,
}

impl ShiguangRuntime {
    pub fn new() -> Result<Self, String> {
        let client = reqwest::Client::builder()
            .user_agent("LumaSchedule/0.1 (+https://github.com/Junyxor/LumaSchedule)")
            .https_only(true)
            .build()
            .map_err(|error| error.to_string())?;
        Ok(Self { client, schools_cache: Mutex::new(None), sessions: Mutex::new(HashMap::new()) })
    }

    async fn fetch_text(&self, url: &str) -> Result<String, String> {
        let response = self.client.get(url).send().await.map_err(|error| format!("适配仓库请求失败: {error}"))?;
        if !response.status().is_success() { return Err(format!("适配仓库返回 HTTP {}", response.status())); }
        response.text().await.map_err(|error| error.to_string())
    }

    fn bundled_text<R: Runtime>(&self, app: &AppHandle<R>, relative: &str) -> Result<String, String> {
        if relative.split('/').any(|part| part == ".." || part.is_empty()) { return Err("非法适配器资源路径".into()); }
        let resource = app.path().resolve(format!("shiguang_warehouse/{relative}"), BaseDirectory::Resource)
            .map_err(|e| format!("无法解析内置适配器路径: {e}"))?;
        app.fs().read_to_string(resource).map_err(|e| format!("读取内置适配器失败: {e}"))
    }

    async fn warehouse_text<R: Runtime>(&self, app: &AppHandle<R>, relative: &str) -> Result<String, String> {
        let remote = format!("{WAREHOUSE_RAW_BASE}/{relative}");
        match self.fetch_text(&remote).await {
            Ok(text) => Ok(text),
            Err(remote_error) => self.bundled_text(app, relative).map_err(|local_error| format!("{remote_error}；内置快照也不可用: {local_error}")),
        }
    }

    async fn schools<R: Runtime>(&self, app: &AppHandle<R>) -> Result<Vec<ShiguangSchool>, String> {
        if let Some(cached) = self.schools_cache.lock().map_err(|_| "shiguang school cache poisoned".to_string())?.clone() { return Ok(cached); }
        let raw = self.warehouse_text(app, "index/root_index.yaml").await?;
        let mut index: RootIndex = serde_yaml::from_str(&raw).map_err(|error| format!("拾光根索引解析失败: {error}"))?;
        for school in &mut index.schools {
            if school.resource_folder.trim().is_empty() {
                school.resource_folder = school.id.clone();
            }
        }
        index.schools.sort_by(|a, b| a.initial.cmp(&b.initial).then_with(|| a.name.cmp(&b.name)));
        *self.schools_cache.lock().map_err(|_| "shiguang school cache poisoned".to_string())? = Some(index.schools.clone());
        Ok(index.schools)
    }

    async fn adapters_for<R: Runtime>(&self, app: &AppHandle<R>, school_id: &str) -> Result<Vec<ShiguangAdapter>, String> {
        let schools = self.schools(app).await?;
        let school = schools.into_iter().find(|school| school.id.eq_ignore_ascii_case(school_id)).ok_or_else(|| format!("拾光仓库中找不到学校: {school_id}"))?;
        let relative = format!("resources/{}/adapters.yaml", school.resource_folder);
        let raw = self.warehouse_text(app, &relative).await?;
        let index: AdapterIndex = serde_yaml::from_str(&raw).map_err(|error| format!("适配器索引解析失败: {error}"))?;
        Ok(index.adapters.into_iter().map(|adapter| ShiguangAdapter {
            school_id: school.id.clone(), school_name: school.name.clone(), resource_folder: school.resource_folder.clone(),
            adapter_id: adapter.adapter_id, adapter_name: adapter.adapter_name, category: adapter.category,
            asset_js_path: adapter.asset_js_path, import_url: adapter.import_url, maintainer: adapter.maintainer, description: adapter.description,
        }).collect())
    }

    async fn resolve_adapter<R: Runtime>(&self, app: &AppHandle<R>, school_id: &str, adapter_id: &str) -> Result<ShiguangAdapter, String> {
        self.adapters_for(app, school_id).await?.into_iter().find(|adapter| adapter.adapter_id == adapter_id)
            .ok_or_else(|| format!("学校 {school_id} 中找不到适配器 {adapter_id}"))
    }
}

#[tauri::command]
pub async fn shiguang_list_schools<R: Runtime>(app: AppHandle<R>, state: State<'_, ShiguangRuntime>, query: Option<String>) -> Result<Vec<ShiguangSchool>, String> {
    let schools = state.schools(&app).await?;
    let Some(query) = query.map(|value| value.trim().to_ascii_lowercase()).filter(|value| !value.is_empty()) else { return Ok(schools); };
    Ok(schools.into_iter().filter(|school| {
        school.name.to_ascii_lowercase().contains(&query) || school.id.to_ascii_lowercase().contains(&query) || school.initial.to_ascii_lowercase().contains(&query)
    }).collect())
}

#[tauri::command]
pub async fn shiguang_list_adapters<R: Runtime>(app: AppHandle<R>, state: State<'_, ShiguangRuntime>, school_id: String) -> Result<Vec<ShiguangAdapter>, String> {
    state.adapters_for(&app, &school_id).await
}

#[tauri::command]
pub async fn shiguang_start_import<R: Runtime>(app: AppHandle<R>, state: State<'_, ShiguangRuntime>, school_id: String, adapter_id: String) -> Result<ShiguangImportStart, String> {
    let adapter = state.resolve_adapter(&app, &school_id, &adapter_id).await?;
    if adapter.import_url.trim().is_empty() { return Err("该拾光条目不是网页登录型教务适配器".to_string()); }
    let script_path = format!("resources/{}/{}", adapter.resource_folder, adapter.asset_js_path);
    let adapter_script = state.warehouse_text(&app, &script_path).await?;
    let source_sha256 = hex::encode(Sha256::digest(adapter_script.as_bytes()));
    let allowed_hosts = collect_allowed_hosts(&adapter.import_url, &adapter_script)?;
    if allowed_hosts.is_empty() { return Err("适配器没有可用的 HTTP/HTTPS 教务域名".to_string()); }
    let insecure_transport = adapter_uses_insecure_http(&adapter.import_url, &adapter_script);
    let session_id = Uuid::new_v4().to_string();
    let window_label = format!("adapter-{}", session_id.replace('-', ""));

    #[cfg(target_os = "android")]
    {
        widget_plugin::start_shiguang_import(&app, widget_plugin::NativeShiguangStart {
            session_id: session_id.clone(), import_url: adapter.import_url.clone(), adapter_script: adapter_script.clone(),
            allowed_hosts_json: serde_json::to_string(&allowed_hosts).map_err(|e| e.to_string())?, adapter_name: adapter.adapter_name.clone(),
            school_name: adapter.school_name.clone(), insecure_transport,
        })?;
    }

    #[cfg(all(not(target_os = "android"), not(target_os = "ios")))]
    {
        let url = Url::parse(&adapter.import_url).map_err(|e| e.to_string())?;
        let script = desktop_injection_script(&session_id, &adapter_script)?;
        let allowed = allowed_hosts.clone();
        let allow_http = insecure_transport;
        tauri::WebviewWindowBuilder::new(&app, &window_label, tauri::WebviewUrl::External(url))
            .title(format!("{} · {}", adapter.school_name, adapter.adapter_name))
            .inner_size(1120.0, 760.0)
            .initialization_script(&script)
            .on_navigation(move |url| {
                let scheme = url.scheme();
                let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
                (scheme == "https" || (allow_http && scheme == "http")) && allowed.iter().any(|value| host == *value || host.ends_with(&format!(".{value}")))
            })
            .build().map_err(|e| e.to_string())?;
        state.sessions.lock().map_err(|_| "session lock poisoned".to_string())?.insert(session_id.clone(), DesktopSession {
            adapter: adapter.clone(), window_label, status: "running".into(), message: Some("请在教务系统中完成登录。".into()), courses: None, time_slots: None, config: None,
        });
    }

    Ok(ShiguangImportStart { session_id, adapter_name: adapter.adapter_name, school_name: adapter.school_name, source_sha256, allowed_hosts, insecure_transport, status: "running".into() })
}

#[tauri::command]
pub async fn shiguang_bridge(state: State<'_, ShiguangRuntime>, request: ShiguangBridgeRequest) -> Result<Value, String> {
    let mut sessions = state.sessions.lock().map_err(|_| "session lock poisoned".to_string())?;
    let session = sessions.get_mut(&request.session_id).ok_or_else(|| "无效或已结束的适配器会话".to_string())?;
    match request.op.as_str() {
        "save_imported_courses" => {
            let payload = request.payload.as_deref().unwrap_or("[]");
            session.courses = Some(serde_json::from_str(payload).map_err(|e| format!("课程解析失败: {e}"))?);
            session.status = "collecting".into(); session.message = Some("课程已经读取，正在整理学期与作息信息。".into()); Ok(json!({"ok":true}))
        }
        "save_preset_time_slots" => { session.time_slots = request.payload.as_deref().and_then(|raw| serde_json::from_str(raw).ok()); Ok(json!({"ok":true})) }
        "save_course_config" => { session.config = request.payload.as_deref().and_then(|raw| serde_json::from_str(raw).ok()); Ok(json!({"ok":true})) }
        "complete" => { session.status = "complete".into(); session.message = Some("课程读取完成。".into()); Ok(json!({"ok":true})) }
        "report_error" => { session.status = "error".into(); session.message = request.payload; Ok(json!({"ok":true})) }
        _ => Err("未知的拾光 Bridge 操作".into()),
    }
}

#[tauri::command]
pub async fn shiguang_get_session<R: Runtime>(app: AppHandle<R>, state: State<'_, ShiguangRuntime>, session_id: String) -> Result<ShiguangSessionSnapshot, String> {
    #[cfg(target_os = "android")]
    {
        let native = widget_plugin::get_shiguang_result(&app, widget_plugin::NativeShiguangResultRequest { session_id: session_id.clone() })?;
        let courses = native.courses_json.as_deref().and_then(|raw| serde_json::from_str::<Vec<ShiguangCourse>>(raw).ok());
        let time_slots = native.time_slots_json.as_deref().and_then(|raw| serde_json::from_str::<Value>(raw).ok());
        let config = native.config_json.as_deref().and_then(|raw| serde_json::from_str::<Value>(raw).ok());
        let bundle = if native.status == "complete" { courses.as_ref().map(|courses| build_bundle(&native.adapter_name, &native.school_name, courses, time_slots.as_ref(), config.as_ref())) } else { None };
        return Ok(ShiguangSessionSnapshot { session_id, adapter_name: native.adapter_name, school_name: native.school_name, status: native.status, message: native.message, bundle, time_slots, config });
    }

    #[cfg(all(not(target_os = "android"), not(target_os = "ios")))]
    {
        let sessions = state.sessions.lock().map_err(|_| "session lock poisoned".to_string())?;
        let session = sessions.get(&session_id).ok_or_else(|| "适配器会话不存在".to_string())?;
        let bundle = if session.status == "complete" { session.courses.as_ref().map(|courses| build_bundle(&session.adapter.adapter_name, &session.adapter.school_name, courses, session.time_slots.as_ref(), session.config.as_ref())) } else { None };
        return Ok(ShiguangSessionSnapshot { session_id, adapter_name: session.adapter.adapter_name.clone(), school_name: session.adapter.school_name.clone(), status: session.status.clone(), message: session.message.clone(), bundle, time_slots: session.time_slots.clone(), config: session.config.clone() });
    }

    #[cfg(target_os = "ios")]
    Err("iOS 拾光原生导入运行时尚未实现".to_string())
}

#[tauri::command]
pub async fn shiguang_close_session<R: Runtime>(app: AppHandle<R>, state: State<'_, ShiguangRuntime>, session_id: String) -> Result<(), String> {
    #[cfg(all(not(target_os = "android"), not(target_os = "ios")))]
    if let Some(session) = state.sessions.lock().map_err(|_| "session lock poisoned".to_string())?.remove(&session_id) { if let Some(window) = app.get_webview_window(&session.window_label) { let _ = window.close(); } }
    #[cfg(target_os = "android")]
    { let _ = state; let _ = widget_plugin::clear_shiguang_session(&app, widget_plugin::NativeShiguangResultRequest { session_id })?; }
    Ok(())
}

fn build_bundle(adapter_name: &str, school_name: &str, courses: &[ShiguangCourse], time_slots: Option<&Value>, config: Option<&Value>) -> ImportBundle {
    let mut grouped: BTreeMap<String, ImportedCourse> = BTreeMap::new();
    for course in courses {
        let key = format!("{}\u{1f}{}\u{1f}{}\u{1f}{}\u{1f}{}\u{1f}{}", course.name, course.teacher.as_deref().unwrap_or(""), course.position.as_deref().unwrap_or(""), course.day, course.start_section, course.end_section);
        let entry = grouped.entry(key).or_insert_with(|| ImportedCourse {
            name: course.name.clone(), teacher: course.teacher.clone().filter(|v| !v.trim().is_empty()), location: course.position.clone().filter(|v| !v.trim().is_empty()),
            weekday: course.day, start_section: course.start_section, end_section: course.end_section, weeks: Vec::new(), start_time: course.start_time.clone(), end_time: course.end_time.clone(),
        });
        entry.weeks.extend(course.weeks.iter().copied().filter(|week| (1..=64).contains(week))); entry.weeks.sort_unstable(); entry.weeks.dedup();
    }
    let mut metadata = HashMap::from([("adapterRuntime".into(), "shiguang-compat-v1".into()), ("adapterName".into(), adapter_name.into()), ("schoolName".into(), school_name.into())]);
    if let Some(value) = time_slots { metadata.insert("timeScheme".into(), value.to_string()); }
    if let Some(value) = config { metadata.insert("courseConfig".into(), value.to_string()); }
    let term_start = config.and_then(|value| value.get("semesterStartDate")).and_then(Value::as_str).map(str::to_string);
    ImportBundle { source: format!("拾光 · {school_name}"), term_name: None, term_start, courses: grouped.into_values().collect(), metadata }
}

fn adapter_uses_insecure_http(import_url: &str, script: &str) -> bool {
    Url::parse(import_url).is_ok_and(|url| url.scheme() == "http") || script.contains("http://")
}

fn collect_allowed_hosts(import_url: &str, script: &str) -> Result<Vec<String>, String> {
    let mut seed_hosts = HashSet::new(); collect_url_hosts(import_url, &mut seed_hosts)?; let mut hosts = seed_hosts.clone();
    let url_re = Regex::new(r#"https?://[^\s\"'`<>\\]+"#).map_err(|e| e.to_string())?;
    for found in url_re.find_iter(script) {
        if let Ok(url) = Url::parse(found.as_str()) {
            if !matches!(url.scheme(), "https" | "http") { continue; }
            if let Some(host) = url.host_str().map(|value| value.to_ascii_lowercase()) {
                if seed_hosts.iter().any(|seed| hosts_share_scope(seed, &host)) { hosts.insert(host); }
            }
        }
    }
    let mut hosts = hosts.into_iter().collect::<Vec<_>>(); hosts.sort(); Ok(hosts)
}

fn hosts_share_scope(seed: &str, candidate: &str) -> bool {
    if seed.eq_ignore_ascii_case(candidate) { return true; }
    let Some(scope) = institutional_scope(seed) else { return false };
    candidate.eq_ignore_ascii_case(&scope) || candidate.to_ascii_lowercase().ends_with(&format!(".{scope}"))
}

fn institutional_scope(host: &str) -> Option<String> {
    let host = host.trim().trim_end_matches('.').to_ascii_lowercase();
    if host.parse::<std::net::IpAddr>().is_ok() || !host.contains('.') { return Some(host); }
    let labels = host.split('.').filter(|part| !part.is_empty()).collect::<Vec<_>>();
    if labels.len() < 2 { return Some(host); }
    let second_level_cn = matches!(labels.get(labels.len().saturating_sub(2)).copied(), Some("edu" | "com" | "net" | "org" | "gov" | "ac")) && labels.last().copied() == Some("cn");
    let keep = if second_level_cn && labels.len() >= 3 { 3 } else { 2 };
    Some(labels[labels.len() - keep..].join("."))
}

fn collect_url_hosts(raw: &str, hosts: &mut HashSet<String>) -> Result<(), String> {
    let url = Url::parse(raw).map_err(|error| format!("无效的适配器登录 URL: {error}"))?;
    if !matches!(url.scheme(), "https" | "http") { return Err("教务适配器只允许 HTTP/HTTPS 登录地址".into()); }
    if let Some(host) = url.host_str() { hosts.insert(host.to_ascii_lowercase()); }
    for (key, value) in url.query_pairs() {
        if matches!(key.as_ref(), "service" | "redirect" | "redirect_uri" | "target" | "url" | "goto") {
            if let Ok(nested) = Url::parse(value.as_ref()) { if matches!(nested.scheme(), "https" | "http") { if let Some(host) = nested.host_str() { hosts.insert(host.to_ascii_lowercase()); } } }
        }
    }
    Ok(())
}

#[cfg(all(not(target_os = "android"), not(target_os = "ios")))]
fn desktop_injection_script(session_id: &str, adapter_script: &str) -> Result<String, String> {
    let session = serde_json::to_string(session_id).map_err(|e| e.to_string())?;
    Ok(format!(r#"
(() => {{
  if (window.top !== window.self || window.__LUMA_SHIGUANG_BOOTSTRAPPED__) return;
  window.__LUMA_SHIGUANG_BOOTSTRAPPED__ = true;
  const sessionId = {session};
  const invokeBridge = async (op, payload = null) => {{
    if (!window.__TAURI_INTERNALS__?.invoke) throw new Error('LumaSchedule IPC bridge unavailable');
    return window.__TAURI_INTERNALS__.invoke('shiguang_bridge', {{ request: {{ sessionId, op, payload }} }});
  }};
  const toast = (message) => {{
    let node = document.getElementById('__luma_shiguang_toast');
    if (!node) {{
      node = document.createElement('div'); node.id = '__luma_shiguang_toast';
      Object.assign(node.style, {{ position:'fixed', left:'50%', bottom:'28px', transform:'translateX(-50%)', zIndex:'2147483647', padding:'10px 14px', borderRadius:'12px', background:'rgba(24,26,34,.90)', color:'#fff', font:'13px system-ui', boxShadow:'0 10px 30px rgba(0,0,0,.25)', maxWidth:'80vw' }});
      document.documentElement.appendChild(node);
    }}
    node.textContent = String(message); node.style.display = 'block'; clearTimeout(node.__timer); node.__timer = setTimeout(() => node.style.display = 'none', 2600);
  }};
  window.shiguangBridge = {{ showToast: toast, notifyTaskCompletion: () => invokeBridge('complete').then(() => {{ toast('课程读取完成，可以返回 LumaSchedule'); setTimeout(() => window.close(), 900); }}) }};
  window.shiguangBridgePromise = {{
    showAlert: async (title, message) => window.confirm(`${{title}}\n\n${{message}}`),
    showPrompt: async (title, tip, defaultText = '', validatorJsFunction = '') => {{
      let current = String(defaultText || '');
      while (true) {{
        const value = window.prompt(`${{title}}\n\n${{tip}}`, current); if (value === null) return null; if (!validatorJsFunction) return value;
        try {{
          const validationResult = (0, eval)(String(validatorJsFunction) + '(' + JSON.stringify(value) + ')');
          const resolved = validationResult && typeof validationResult.then === 'function' ? await validationResult : validationResult;
          if (resolved === false || resolved === null || resolved === undefined || String(resolved).length === 0) return value;
          toast(String(resolved)); current = value;
        }} catch (error) {{ toast('输入校验器执行失败：' + String(error)); return value; }}
      }}
    }},
    showSingleSelection: async (title, optionsJson, defaultIndex = -1) => {{
      const options = JSON.parse(optionsJson || '[]'); const body = options.map((item, index) => `${{index + 1}}. ${{item}}`).join('\n');
      const initial = Number(defaultIndex) >= 0 ? String(Number(defaultIndex) + 1) : ''; const raw = window.prompt(`${{title}}\n\n${{body}}`, initial);
      if (raw === null) return -1; const selected = Number.parseInt(raw, 10) - 1; return Number.isInteger(selected) && selected >= 0 && selected < options.length ? selected : -1;
    }},
    saveImportedCourses: (payload) => invokeBridge('save_imported_courses', payload),
    savePresetTimeSlots: (payload) => invokeBridge('save_preset_time_slots', payload),
    saveCourseConfig: (payload) => invokeBridge('save_course_config', payload),
  }};
  window.AndroidBridgePromise = window.shiguangBridgePromise; window.AndroidBridge = window.shiguangBridge;
  window.addEventListener('unhandledrejection', event => invokeBridge('report_error', String(event.reason || 'adapter rejection')).catch(() => {{}}));
  window.addEventListener('error', event => invokeBridge('report_error', String(event.error || event.message || 'adapter error')).catch(() => {{}}));

{adapter_script}
}})();
"#))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn school_index_accepts_snake_case_resource_folder() {
        let raw = r#"
schools:
  - id: "GDUT"
    name: "广东工业大学"
    initial: "G"
    resource_folder: "GDUT"
"#;
        let index: RootIndex = serde_yaml::from_str(raw).expect("snake_case Shiguang index should parse");
        assert_eq!(index.schools[0].resource_folder, "GDUT");
    }

    #[test]
    fn institutional_subdomains_share_scope() {
        assert!(hosts_share_scope("jxfw.gdut.edu.cn", "authserver.gdut.edu.cn"));
        assert!(!hosts_share_scope("gdut.edu.cn", "example.com"));
    }
}
