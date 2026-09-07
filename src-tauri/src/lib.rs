mod backup;
mod db;
mod schedule_view;
mod shiguang;
mod webdav;
mod widget_plugin;

use db::AppDb;
use schedule_core::ImportBundle;
use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Manager, Runtime, State};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Bootstrap {
    app_version: String,
    glass_settings: Option<Value>,
    db_ready: bool,
}

#[tauri::command]
fn get_bootstrap(db: State<'_, AppDb>) -> Result<Bootstrap, String> {
    Ok(Bootstrap {
        app_version: env!("CARGO_PKG_VERSION").to_string(),
        glass_settings: db.get_json("appearance.glass")?,
        db_ready: true,
    })
}

#[tauri::command]
fn save_glass_settings(db: State<'_, AppDb>, settings: Value) -> Result<(), String> {
    db.set_json("appearance.glass", &settings)
}

#[tauri::command]
fn import_schedule_text(format: String, payload: String) -> Result<ImportBundle, String> {
    schedule_import::parse(&format, &payload).map_err(|e| e.to_string())
}

#[tauri::command]
fn commit_import_bundle(db: State<'_, AppDb>, bundle: ImportBundle) -> Result<db::ImportCommitResult, String> {
    db.commit_import(&bundle)
}

#[tauri::command]
fn list_schedule_courses(db: State<'_, AppDb>) -> Result<Vec<db::CourseView>, String> {
    db.list_latest_schedule_courses()
}

#[tauri::command]
fn export_latest_schedule_json(db: State<'_, AppDb>) -> Result<String, String> {
    db.export_latest_json()
}

#[tauri::command]
fn export_latest_schedule_ics(db: State<'_, AppDb>) -> Result<String, String> {
    db.export_latest_ics()
}

#[tauri::command]
fn export_full_backup(db: State<'_, AppDb>) -> Result<String, String> {
    db.export_backup_json()
}

#[tauri::command]
fn restore_full_backup(db: State<'_, AppDb>, payload: String) -> Result<backup::BackupSummary, String> {
    db.restore_backup_json(&payload)
}

#[tauri::command]
fn validate_adapter_url(manifest: schedule_adapter::AdapterManifest, url: String) -> Result<bool, String> {
    manifest.allows_url(&url).map(|_| true).map_err(|e| e.to_string())
}

#[tauri::command]
fn update_widget_snapshot<R: Runtime>(
    app: AppHandle<R>,
    snapshot: widget_plugin::WidgetSnapshot,
) -> Result<bool, String> {
    widget_plugin::update_snapshot(&app, snapshot)
}

#[tauri::command]
fn schedule_native_reminder<R: Runtime>(
    app: AppHandle<R>,
    reminder: widget_plugin::NativeReminder,
) -> Result<bool, String> {
    widget_plugin::schedule_reminder(&app, reminder)
}

#[tauri::command]
fn cancel_native_reminder<R: Runtime>(
    app: AppHandle<R>,
    reminder: widget_plugin::CancelReminder,
) -> Result<bool, String> {
    widget_plugin::cancel_reminder(&app, reminder)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(widget_plugin::init())
        .setup(|app| {
            let config_dir = app.path().app_config_dir()?;
            std::fs::create_dir_all(&config_dir)?;
            let db = AppDb::open(&config_dir.join("lumaschedule.sqlite"))?;
            app.manage(db);
            app.manage(shiguang::ShiguangRuntime::new().map_err(std::io::Error::other)?);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_bootstrap,
            save_glass_settings,
            import_schedule_text,
            commit_import_bundle,
            list_schedule_courses,
            schedule_view::get_schedule_snapshot,
            export_latest_schedule_json,
            export_latest_schedule_ics,
            export_full_backup,
            restore_full_backup,
            validate_adapter_url,
            webdav::get_webdav_profile,
            webdav::save_webdav_profile,
            webdav::webdav_test,
            webdav::webdav_upload_backup,
            webdav::webdav_restore_backup,
            update_widget_snapshot,
            schedule_native_reminder,
            cancel_native_reminder,
            shiguang::shiguang_list_schools,
            shiguang::shiguang_list_adapters,
            shiguang::shiguang_start_import,
            shiguang::shiguang_bridge,
            shiguang::shiguang_get_session,
            shiguang::shiguang_close_session
        ])
        .run(tauri::generate_context!())
        .expect("error while running LumaSchedule");
}
