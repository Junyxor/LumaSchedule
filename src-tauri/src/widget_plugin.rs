use serde::{Deserialize, Deserializer, Serialize};
use std::marker::PhantomData;
use tauri::{
    plugin::{Builder, PluginHandle, TauriPlugin},
    AppHandle, Manager, Runtime,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetSnapshot {
    pub course_name: String,
    pub course_meta: String,
    pub countdown: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeReminder {
    pub id: i32,
    pub trigger_at_epoch_ms: i64,
    pub title: String,
    pub body: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CancelReminder {
    pub id: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeShiguangStart {
    pub session_id: String,
    pub import_url: String,
    pub adapter_script: String,
    pub allowed_hosts_json: String,
    pub adapter_name: String,
    pub school_name: String,
    pub insecure_transport: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeShiguangResultRequest {
    pub session_id: String,
}

fn nullable_string<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(Option::<String>::deserialize(deserializer)?.unwrap_or_default())
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeShiguangResult {
    pub status: String,
    pub message: Option<String>,
    pub courses_json: Option<String>,
    pub time_slots_json: Option<String>,
    pub config_json: Option<String>,
    #[serde(default, deserialize_with = "nullable_string")]
    pub adapter_name: String,
    #[serde(default, deserialize_with = "nullable_string")]
    pub school_name: String,
}

#[derive(Debug, Deserialize)]
struct WidgetUpdateResponse { updated: bool }
#[derive(Debug, Deserialize)]
struct ReminderResponse { scheduled: bool }
#[derive(Debug, Deserialize)]
struct CancelReminderResponse { cancelled: bool }
#[derive(Debug, Deserialize)]
struct NativeShiguangStartResponse { started: bool }
#[derive(Debug, Deserialize)]
struct NativeShiguangClearResponse { cleared: bool }

pub struct ScheduleNativeBridge<R: Runtime> {
    #[cfg(target_os = "android")]
    handle: PluginHandle<R>,
    #[cfg(not(target_os = "android"))]
    marker: PhantomData<fn() -> R>,
}

impl<R: Runtime> ScheduleNativeBridge<R> {
    pub fn update_widget(&self, snapshot: WidgetSnapshot) -> Result<bool, String> {
        #[cfg(target_os = "android")]
        {
            let response: WidgetUpdateResponse = self.handle.run_mobile_plugin("updateWidget", snapshot).map_err(|error| error.to_string())?;
            return Ok(response.updated);
        }
        #[cfg(not(target_os = "android"))]
        { let _ = snapshot; Ok(false) }
    }

    pub fn schedule_reminder(&self, reminder: NativeReminder) -> Result<bool, String> {
        #[cfg(target_os = "android")]
        {
            let response: ReminderResponse = self.handle.run_mobile_plugin("scheduleReminder", reminder).map_err(|error| error.to_string())?;
            return Ok(response.scheduled);
        }
        #[cfg(not(target_os = "android"))]
        { let _ = reminder; Ok(false) }
    }

    pub fn cancel_reminder(&self, reminder: CancelReminder) -> Result<bool, String> {
        #[cfg(target_os = "android")]
        {
            let response: CancelReminderResponse = self.handle.run_mobile_plugin("cancelReminder", reminder).map_err(|error| error.to_string())?;
            return Ok(response.cancelled);
        }
        #[cfg(not(target_os = "android"))]
        { let _ = reminder; Ok(false) }
    }

    pub fn start_shiguang_import(&self, request: NativeShiguangStart) -> Result<bool, String> {
        #[cfg(target_os = "android")]
        {
            let response: NativeShiguangStartResponse = self.handle.run_mobile_plugin("startShiguangImport", request).map_err(|error| error.to_string())?;
            return Ok(response.started);
        }
        #[cfg(not(target_os = "android"))]
        { let _ = request; Ok(false) }
    }

    pub fn clear_shiguang_session(&self, request: NativeShiguangResultRequest) -> Result<bool, String> {
        #[cfg(target_os = "android")]
        {
            let response: NativeShiguangClearResponse = self.handle.run_mobile_plugin("clearShiguangSession", request).map_err(|error| error.to_string())?;
            return Ok(response.cleared);
        }
        #[cfg(not(target_os = "android"))]
        { let _ = request; Ok(false) }
    }

    pub fn get_shiguang_result(&self, request: NativeShiguangResultRequest) -> Result<NativeShiguangResult, String> {
        #[cfg(target_os = "android")]
        { return self.handle.run_mobile_plugin("getShiguangResult", request).map_err(|error| error.to_string()); }
        #[cfg(not(target_os = "android"))]
        { let _ = request; Err("native Shiguang bridge is Android-only".into()) }
    }
}

pub fn update_snapshot<R: Runtime>(app: &AppHandle<R>, snapshot: WidgetSnapshot) -> Result<bool, String> {
    app.state::<ScheduleNativeBridge<R>>().update_widget(snapshot)
}
pub fn schedule_reminder<R: Runtime>(app: &AppHandle<R>, reminder: NativeReminder) -> Result<bool, String> {
    app.state::<ScheduleNativeBridge<R>>().schedule_reminder(reminder)
}
pub fn cancel_reminder<R: Runtime>(app: &AppHandle<R>, reminder: CancelReminder) -> Result<bool, String> {
    app.state::<ScheduleNativeBridge<R>>().cancel_reminder(reminder)
}
pub fn start_shiguang_import<R: Runtime>(app: &AppHandle<R>, request: NativeShiguangStart) -> Result<bool, String> {
    app.state::<ScheduleNativeBridge<R>>().start_shiguang_import(request)
}
pub fn clear_shiguang_session<R: Runtime>(app: &AppHandle<R>, request: NativeShiguangResultRequest) -> Result<bool, String> {
    app.state::<ScheduleNativeBridge<R>>().clear_shiguang_session(request)
}
pub fn get_shiguang_result<R: Runtime>(app: &AppHandle<R>, request: NativeShiguangResultRequest) -> Result<NativeShiguangResult, String> {
    app.state::<ScheduleNativeBridge<R>>().get_shiguang_result(request)
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("schedule-native")
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            {
                let handle = api.register_android_plugin("com.lumaschedule.app", "ScheduleNativePlugin")?;
                app.manage(ScheduleNativeBridge::<R> { handle });
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = api;
                app.manage(ScheduleNativeBridge::<R> { marker: PhantomData });
            }
            Ok(())
        })
        .build()
}
