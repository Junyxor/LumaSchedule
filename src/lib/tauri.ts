import type {
  BackupSummary,
  Course,
  CourseMutation,
  CourseReminderSettings,
  GlassSettings,
  ImportBundle,
  ReminderSyncReport,
  ScheduleSnapshot,
  ShiguangAdapter,
  ShiguangImportStart,
  ShiguangSchool,
  ShiguangSessionSnapshot,
  WebDavCredentials,
  WebDavProfile,
  WebDavResult
} from './types';
import { importText } from './importers';
import { invokeNative, unwrap } from './nativeBridge';

export { importText };

export function getBootstrap() {
  return invokeNative<{
    appVersion: string;
    glassSettings?: GlassSettings | null;
    dbReady: boolean;
    nativeCore?: boolean;
    startupMs?: number;
  }>('get_bootstrap');
}

export async function listScheduleCourses(): Promise<Course[]> {
  return (await getScheduleSnapshot()).courses;
}

export function getScheduleSnapshot() {
  return invokeNative<ScheduleSnapshot>('get_schedule_snapshot');
}

export async function saveScheduleCourse(course: CourseMutation) {
  return unwrap(await invokeNative<{ value: string }>('save_schedule_course', { course }));
}

export function deleteScheduleCourse(id: string) {
  return invokeNative<void>('delete_schedule_course', { id });
}

export async function saveGlassSettings(settings: GlassSettings) {
  localStorage.setItem('luma.glass', JSON.stringify(settings));
  await invokeNative<void>('save_glass_settings', { settings }).catch(() => undefined);
}

export function commitImport(bundle: ImportBundle) {
  return invokeNative<{
    termId: string;
    scheduleId: string;
    courseCount: number;
    meetingCount: number;
  }>('commit_import_bundle', { bundle });
}

export async function ensureNotificationPermission() {
  return unwrap(
    await invokeNative<{ value: boolean }>('request_notification_permission')
  );
}

export async function testNotification() {
  return unwrap(await invokeNative<{ value: boolean }>('test_notification'));
}

export function getCourseReminderSettings() {
  return invokeNative<CourseReminderSettings>('get_course_reminder_settings');
}

export function saveCourseReminderSettings(settings: CourseReminderSettings) {
  return invokeNative<CourseReminderSettings>('save_course_reminder_settings', { settings });
}

export function syncCourseReminders() {
  return invokeNative<ReminderSyncReport>('sync_course_reminders', {}, 120_000);
}

export async function publishWidgetSnapshot(snapshot: {
  courseName: string;
  courseMeta: string;
  countdown: string;
}) {
  return unwrap(
    await invokeNative<{ value: boolean }>('update_widget_snapshot', { snapshot })
  );
}

export async function scheduleTestReminder(delayMs = 60_000) {
  const id = Math.floor(Date.now() % 2_000_000_000);
  return unwrap(
    await invokeNative<{ value: boolean }>('schedule_native_reminder', {
      reminder: {
        id,
        triggerAtEpochMs: Date.now() + delayMs,
        title: 'LumaSchedule · 测试提醒',
        body: '后台定时提醒触发成功。'
      }
    })
  );
}

export function listShiguangSchools(query = '') {
  return invokeNative<ShiguangSchool[]>('shiguang_list_schools', {
    query: query.trim()
  });
}

export function listShiguangAdapters(schoolId: string) {
  return invokeNative<ShiguangAdapter[]>('shiguang_list_adapters', { schoolId });
}

export function startShiguangImport(schoolId: string, adapterId: string) {
  return invokeNative<ShiguangImportStart>('shiguang_start_import', {
    schoolId,
    adapterId
  });
}

export function getShiguangSession(sessionId: string) {
  return invokeNative<ShiguangSessionSnapshot>('shiguang_get_session', { sessionId });
}

export function closeShiguangSession(sessionId: string) {
  return invokeNative<void>('shiguang_close_session', { sessionId });
}

export function saveLatestScheduleJson() {
  return invokeNative<boolean>('export_latest_schedule_json_to_file', {}, 300_000);
}

export function saveLatestScheduleIcs() {
  return invokeNative<boolean>('export_latest_schedule_ics_to_file', {}, 300_000);
}

export function saveFullBackup() {
  return invokeNative<boolean>('export_full_backup_to_file', {}, 300_000);
}

export function restoreFullBackupFromFile() {
  return invokeNative<BackupSummary | null>('restore_full_backup_from_file', {}, 300_000);
}

export function getWebDavProfile() {
  return invokeNative<WebDavProfile>('get_webdav_profile');
}

export function saveWebDavProfile(profile: WebDavProfile) {
  return invokeNative<void>('save_webdav_profile', { profile });
}

export function testWebDav(credentials: WebDavCredentials) {
  return invokeNative<WebDavResult>('webdav_test', { credentials }, 60_000);
}

export function uploadWebDavBackup(credentials: WebDavCredentials) {
  return invokeNative<WebDavResult>('webdav_upload_backup', { credentials }, 120_000);
}

export function restoreWebDavBackup(credentials: WebDavCredentials) {
  return invokeNative<WebDavResult>('webdav_restore_backup', { credentials }, 120_000);
}
