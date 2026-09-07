use crate::{backup::BackupSummary, db::AppDb};
use reqwest::{Client, Method, StatusCode};
use serde::{Deserialize, Serialize};
use std::net::{IpAddr, Ipv4Addr};
use tauri::State;
use url::Url;

const MAX_BACKUP_BYTES: usize = 32 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WebDavProfile {
    pub base_url: String,
    pub username: String,
    pub remote_path: String,
}

impl Default for WebDavProfile {
    fn default() -> Self {
        Self {
            base_url: String::new(),
            username: String::new(),
            remote_path: "LumaSchedule/lumaschedule-latest.luma.json".into(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WebDavCredentials {
    pub base_url: String,
    pub username: String,
    pub password: String,
    pub remote_path: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebDavResult {
    pub ok: bool,
    pub status: u16,
    pub message: String,
    pub remote_url: Option<String>,
    pub etag: Option<String>,
    pub backup: Option<BackupSummary>,
}

#[tauri::command]
pub fn get_webdav_profile(db: State<'_, AppDb>) -> Result<WebDavProfile, String> {
    Ok(db.get_json("sync.webdav.profile")?.and_then(|value| serde_json::from_value(value).ok()).unwrap_or_default())
}

#[tauri::command]
pub fn save_webdav_profile(db: State<'_, AppDb>, profile: WebDavProfile) -> Result<(), String> {
    validate_profile(&profile.base_url, &profile.remote_path)?;
    let value = serde_json::to_value(profile).map_err(|e| e.to_string())?;
    db.set_json("sync.webdav.profile", &value)
}

#[tauri::command]
pub async fn webdav_test(credentials: WebDavCredentials) -> Result<WebDavResult, String> {
    let base = validate_profile(&credentials.base_url, &credentials.remote_path)?;
    let client = client()?;
    let method = Method::from_bytes(b"PROPFIND").map_err(|e| e.to_string())?;
    let response = client.request(method, base.clone())
        .basic_auth(&credentials.username, Some(&credentials.password))
        .header("Depth", "0")
        .header("Content-Type", "application/xml; charset=utf-8")
        .body("<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><displayname/></prop></propfind>")
        .send().await.map_err(|e| format!("WebDAV 连接失败: {e}"))?;
    let status = response.status();
    if status == StatusCode::UNAUTHORIZED || status == StatusCode::FORBIDDEN {
        return Err("WebDAV 身份验证失败，请检查用户名和密码".into());
    }
    if !(status.is_success() || status.as_u16() == 207) {
        return Err(format!("WebDAV 服务器返回 HTTP {}", status.as_u16()));
    }
    Ok(WebDavResult { ok: true, status: status.as_u16(), message: "WebDAV 连接正常，凭据未写入磁盘。".into(), remote_url: Some(base.to_string()), etag: None, backup: None })
}

#[tauri::command]
pub async fn webdav_upload_backup(db: State<'_, AppDb>, credentials: WebDavCredentials) -> Result<WebDavResult, String> {
    let base = validate_profile(&credentials.base_url, &credentials.remote_path)?;
    let client = client()?;
    ensure_collections(&client, &base, &credentials).await?;
    let target = backup_url(&base, &credentials.remote_path)?;
    let backup = db.export_backup()?;
    let payload = serde_json::to_vec_pretty(&backup).map_err(|e| e.to_string())?;
    let response = client.put(target.clone())
        .basic_auth(&credentials.username, Some(&credentials.password))
        .header("Content-Type", "application/vnd.lumaschedule.backup+json; charset=utf-8")
        .body(payload).send().await.map_err(|e| format!("上传 WebDAV 备份失败: {e}"))?;
    let status = response.status();
    if status == StatusCode::UNAUTHORIZED || status == StatusCode::FORBIDDEN { return Err("WebDAV 身份验证失败，请检查用户名和密码".into()); }
    if !status.is_success() { return Err(format!("WebDAV PUT 返回 HTTP {}", status.as_u16())); }
    let etag = response.headers().get("etag").and_then(|v| v.to_str().ok()).map(str::to_string);
    let summary = backup.summary();
    Ok(WebDavResult { ok: true, status: status.as_u16(), message: format!("已备份 {} 门课程 / {} 个时段。", summary.course_count, summary.meeting_count), remote_url: Some(target.to_string()), etag, backup: Some(summary) })
}

#[tauri::command]
pub async fn webdav_restore_backup(db: State<'_, AppDb>, credentials: WebDavCredentials) -> Result<WebDavResult, String> {
    let base = validate_profile(&credentials.base_url, &credentials.remote_path)?;
    let target = backup_url(&base, &credentials.remote_path)?;
    let client = client()?;
    let response = client.get(target.clone()).basic_auth(&credentials.username, Some(&credentials.password)).send().await.map_err(|e| format!("下载 WebDAV 备份失败: {e}"))?;
    let status = response.status();
    if status == StatusCode::NOT_FOUND { return Err("WebDAV 上没有找到该备份文件".into()); }
    if status == StatusCode::UNAUTHORIZED || status == StatusCode::FORBIDDEN { return Err("WebDAV 身份验证失败，请检查用户名和密码".into()); }
    if !status.is_success() { return Err(format!("WebDAV GET 返回 HTTP {}", status.as_u16())); }
    if let Some(len) = response.content_length() {
        if len as usize > MAX_BACKUP_BYTES { return Err(format!("备份文件过大（{} MiB），拒绝恢复", len / 1024 / 1024)); }
    }
    let bytes = response.bytes().await.map_err(|e| format!("读取备份失败: {e}"))?;
    if bytes.len() > MAX_BACKUP_BYTES { return Err("备份文件超过 32 MiB 安全限制".into()); }
    let raw = std::str::from_utf8(&bytes).map_err(|_| "备份文件不是 UTF-8 JSON".to_string())?;
    let summary = db.restore_backup_json(raw)?;
    Ok(WebDavResult { ok: true, status: status.as_u16(), message: format!("已从 WebDAV 恢复 {} 门课程 / {} 个时段。", summary.course_count, summary.meeting_count), remote_url: Some(target.to_string()), etag: None, backup: Some(summary) })
}

fn client() -> Result<Client, String> {
    Client::builder().user_agent("LumaSchedule/0.1 WebDAV").redirect(reqwest::redirect::Policy::limited(5)).https_only(false).build().map_err(|e| e.to_string())
}

fn validate_profile(base_url: &str, remote_path: &str) -> Result<Url, String> {
    let mut base = Url::parse(base_url.trim()).map_err(|e| format!("WebDAV URL 无效: {e}"))?;
    if base.query().is_some() || base.fragment().is_some() { return Err("WebDAV 基础 URL 不应包含 query 或 fragment".into()); }
    match base.scheme() {
        "https" => {},
        "http" if is_local_http(&base) => {},
        "http" => return Err("公网 WebDAV 必须使用 HTTPS；HTTP 只允许 localhost / 局域网地址".into()),
        _ => return Err("WebDAV 仅支持 HTTPS，或局域网 HTTP".into()),
    }
    let remote = remote_path.trim().trim_start_matches('/');
    if remote.is_empty() || remote.ends_with('/') { return Err("远程路径必须包含备份文件名".into()); }
    if remote.split('/').any(|part| part.is_empty() || part == "." || part == "..") { return Err("WebDAV 远程路径包含非法目录段".into()); }
    if !base.path().ends_with('/') { let path = format!("{}/", base.path()); base.set_path(&path); }
    Ok(base)
}

fn is_local_http(url: &Url) -> bool {
    let Some(host) = url.host_str().map(|x| x.to_ascii_lowercase()) else { return false };
    if host == "localhost" || host.ends_with(".localhost") || host.ends_with(".local") { return true; }
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => is_private_v4(ip),
        Ok(IpAddr::V6(ip)) => ip.is_loopback() || ip.is_unique_local(),
        Err(_) => false,
    }
}

fn is_private_v4(ip: Ipv4Addr) -> bool { ip.is_private() || ip.is_loopback() || ip.is_link_local() }

fn backup_url(base: &Url, remote_path: &str) -> Result<Url, String> {
    base.join(remote_path.trim().trim_start_matches('/')).map_err(|e| format!("无法组合 WebDAV 备份路径: {e}"))
}

async fn ensure_collections(client: &Client, base: &Url, credentials: &WebDavCredentials) -> Result<(), String> {
    let remote = credentials.remote_path.trim().trim_start_matches('/');
    let parts = remote.split('/').collect::<Vec<_>>();
    if parts.len() <= 1 { return Ok(()); }
    let mkcol = Method::from_bytes(b"MKCOL").map_err(|e| e.to_string())?;
    let mut current = base.clone();
    for directory in &parts[..parts.len() - 1] {
        current = current.join(&format!("{directory}/")).map_err(|e| e.to_string())?;
        let response = client.request(mkcol.clone(), current.clone()).basic_auth(&credentials.username, Some(&credentials.password)).send().await.map_err(|e| format!("创建 WebDAV 目录失败: {e}"))?;
        let status = response.status();
        if status.is_success() || status == StatusCode::METHOD_NOT_ALLOWED || status == StatusCode::FOUND || status == StatusCode::MOVED_PERMANENTLY { continue; }
        if status == StatusCode::UNAUTHORIZED || status == StatusCode::FORBIDDEN { return Err("WebDAV 身份验证失败，无法创建备份目录".into()); }
        return Err(format!("创建 WebDAV 目录 {} 失败: HTTP {}", current, status.as_u16()));
    }
    Ok(())
}
