import { invoke } from '@tauri-apps/api/core';
import { isPermissionGranted, requestPermission, sendNotification } from '@tauri-apps/plugin-notification';
import { open, save } from '@tauri-apps/plugin-dialog';
import { readTextFile, writeTextFile } from '@tauri-apps/plugin-fs';
import type { BackupSummary, Course, CourseMutation, GlassSettings, ImportBundle, ScheduleSnapshot, ShiguangAdapter, ShiguangImportStart, ShiguangSchool, ShiguangSessionSnapshot, WebDavCredentials, WebDavProfile, WebDavResult } from './types';

export async function getBootstrap() { return invoke<{ appVersion: string; glassSettings?: GlassSettings | null; dbReady: boolean }>('get_bootstrap'); }
export async function listScheduleCourses() { return invoke<Course[]>('list_schedule_courses'); }
export async function getScheduleSnapshot() { return invoke<ScheduleSnapshot>('get_schedule_snapshot'); }
export async function saveScheduleCourse(course: CourseMutation) { return invoke<string>('save_schedule_course', { course }); }
export async function deleteScheduleCourse(id: string) { return invoke<void>('delete_schedule_course', { id }); }
export async function saveGlassSettings(settings: GlassSettings) { try { return await invoke('save_glass_settings', { settings }); } catch { localStorage.setItem('luma.glass', JSON.stringify(settings)); } }
export async function importText(format: string, payload: string) { return invoke<ImportBundle>('import_schedule_text', { format, payload }); }
export async function commitImport(bundle: ImportBundle) { return invoke<{ termId: string; scheduleId: string; courseCount: number; meetingCount: number }>('commit_import_bundle', { bundle }); }
export async function testNotification() {
  let granted = await isPermissionGranted(); if (!granted) granted = (await requestPermission()) === 'granted';
  if (granted) sendNotification({ title: 'LumaSchedule', body: '测试通知发送成功。课程提醒可以正常显示。' });
}
export async function publishWidgetSnapshot(snapshot: { courseName: string; courseMeta: string; countdown: string; }) {
  try { return await invoke<boolean>('update_widget_snapshot', { snapshot }); } catch { return false; }
}
export async function scheduleTestReminder(delayMs = 60_000) {
  let granted = await isPermissionGranted(); if (!granted) granted = (await requestPermission()) === 'granted'; if (!granted) return false;
  const id = Math.floor(Date.now() % 2_000_000_000);
  return invoke<boolean>('schedule_native_reminder', { reminder: { id, triggerAtEpochMs: Date.now() + delayMs, title: 'LumaSchedule · 测试提醒', body: '后台定时提醒触发成功。' } });
}
export async function listShiguangSchools(query = '') { return invoke<ShiguangSchool[]>('shiguang_list_schools', { query: query || null }); }
export async function listShiguangAdapters(schoolId: string) { return invoke<ShiguangAdapter[]>('shiguang_list_adapters', { schoolId }); }
export async function startShiguangImport(schoolId: string, adapterId: string) { return invoke<ShiguangImportStart>('shiguang_start_import', { schoolId, adapterId }); }
export async function getShiguangSession(sessionId: string) { return invoke<ShiguangSessionSnapshot>('shiguang_get_session', { sessionId }); }
export async function closeShiguangSession(sessionId: string) { return invoke<void>('shiguang_close_session', { sessionId }); }
export async function saveLatestScheduleJson() {
  const payload = await invoke<string>('export_latest_schedule_json'); const path = await save({ defaultPath: 'LumaSchedule-schedule.json', filters: [{ name: 'LumaSchedule JSON', extensions: ['json'] }] });
  if (!path) return false; await writeTextFile(path, payload); return true;
}
export async function saveLatestScheduleIcs() {
  const payload = await invoke<string>('export_latest_schedule_ics'); const path = await save({ defaultPath: 'LumaSchedule-calendar.ics', filters: [{ name: 'iCalendar', extensions: ['ics'] }] });
  if (!path) return false; await writeTextFile(path, payload); return true;
}
export async function saveFullBackup() {
  const payload = await invoke<string>('export_full_backup'); const path = await save({ defaultPath: 'LumaSchedule-backup.luma.json', filters: [{ name: 'LumaSchedule Backup', extensions: ['json'] }] });
  if (!path) return false; await writeTextFile(path, payload); return true;
}
export async function restoreFullBackupFromFile() {
  const path = await open({ multiple: false, directory: false, filters: [{ name: 'LumaSchedule Backup', extensions: ['json'] }] });
  if (!path || Array.isArray(path)) return null; const payload = await readTextFile(path); return invoke<BackupSummary>('restore_full_backup', { payload });
}
export async function getWebDavProfile() { return invoke<WebDavProfile>('get_webdav_profile'); }
export async function saveWebDavProfile(profile: WebDavProfile) { return invoke<void>('save_webdav_profile', { profile }); }
export async function testWebDav(credentials: WebDavCredentials) { return invoke<WebDavResult>('webdav_test', { credentials }); }
export async function uploadWebDavBackup(credentials: WebDavCredentials) { return invoke<WebDavResult>('webdav_upload_backup', { credentials }); }
export async function restoreWebDavBackup(credentials: WebDavCredentials) { return invoke<WebDavResult>('webdav_restore_backup', { credentials }); }
