import type {
  BackupSummary,
  CompatibilityFamily,
  Course,
  CourseMutation,
  CourseReminderSettings,
  GlassSettings,
  GradeImportBundle,
  GradeImportResult,
  GradeSnapshot,
  ImportBundle,
  ImportCommitResult,
  ImportDiff,
  ImportMode,
  ReminderSyncReport,
  SchedulePreferences,
  ScheduleSnapshot,
  ShiguangAdapter,
  ShiguangImportStart,
  ShiguangSchool,
  ShiguangSessionSnapshot,
  UpdateCheckResult,
  WebDavCredentials,
  WebDavProfile,
  WebDavResult
} from './types';
import { importText } from './importers';
import { invokeNative, unwrap } from './nativeBridge';
import {
  forgetCourseCredit,
  hydrateCourseCredits,
  hydrateGradeCredits,
  rememberCourseCredit,
  rememberImportCredits
} from './courseCredits';

export { importText };

export interface RecentAcademicSource {
  url: string;
  schoolName: string;
  adapterName?: string;
  updatedAt: number;
}

const RECENT_ACADEMIC_SOURCE_KEY = 'luma.recentAcademicSource';
const shiguangAdapterCache = new Map<string, ShiguangAdapter[]>();

function rememberAcademicSource(url: string, schoolName = '', adapterName = '') {
  if (typeof localStorage === 'undefined') return;
  const normalizedUrl = url.trim();
  if (!/^https?:\/\//i.test(normalizedUrl)) return;
  const source: RecentAcademicSource = {
    url: normalizedUrl,
    schoolName: schoolName.trim(),
    adapterName: adapterName.trim() || undefined,
    updatedAt: Date.now()
  };
  try {
    localStorage.setItem(RECENT_ACADEMIC_SOURCE_KEY, JSON.stringify(source));
  } catch {
    // The native flow still works if WebView storage is unavailable.
  }
}

export function getRecentAcademicSource(): RecentAcademicSource | null {
  if (typeof localStorage === 'undefined') return null;
  try {
    const raw = localStorage.getItem(RECENT_ACADEMIC_SOURCE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<RecentAcademicSource>;
    const url = typeof parsed.url === 'string' ? parsed.url.trim() : '';
    if (!/^https?:\/\//i.test(url)) return null;
    return {
      url,
      schoolName: typeof parsed.schoolName === 'string' ? parsed.schoolName.trim() : '',
      adapterName: typeof parsed.adapterName === 'string' && parsed.adapterName.trim() ? parsed.adapterName.trim() : undefined,
      updatedAt: typeof parsed.updatedAt === 'number' && Number.isFinite(parsed.updatedAt) ? parsed.updatedAt : 0
    };
  } catch {
    return null;
  }
}

export function getBootstrap() {
  return invokeNative<{
    appVersion: string;
    glassSettings?: GlassSettings | null;
    dbReady: boolean;
    nativeCore?: boolean;
    startupMs?: number;
  }>('get_bootstrap');
}

export function checkForUpdates() {
  return invokeNative<UpdateCheckResult>('check_update', {}, 30_000);
}

export async function listScheduleCourses(): Promise<Course[]> {
  return (await getScheduleSnapshot()).courses;
}

export async function getScheduleSnapshot() {
  const snapshot = await invokeNative<ScheduleSnapshot>('get_schedule_snapshot');
  return { ...snapshot, courses: hydrateCourseCredits(snapshot.courses || []) };
}

export function getSchedulePreferences() {
  return invokeNative<SchedulePreferences>('get_schedule_preferences');
}

export function saveSchedulePreferences(preferences: SchedulePreferences) {
  return invokeNative<SchedulePreferences>('save_schedule_preferences', { preferences });
}

export async function saveScheduleCourse(course: CourseMutation) {
  const id = unwrap(await invokeNative<{ value: string }>('save_schedule_course', { course }));
  rememberCourseCredit(id, course.name, course.credit);
  return id;
}

export async function deleteScheduleCourse(id: string, courseName = '') {
  await invokeNative<void>('delete_schedule_course', { id });
  forgetCourseCredit(id, courseName);
}

export async function saveGlassSettings(settings: GlassSettings) {
  localStorage.setItem('luma.glass', JSON.stringify(settings));
  await invokeNative<void>('save_glass_settings', { settings }).catch(() => undefined);
}

export async function getGradeSnapshot() {
  const snapshot = await invokeNative<GradeSnapshot>('get_grade_snapshot');
  return { ...snapshot, records: hydrateGradeCredits(snapshot.records || []) };
}

export function commitGradeBundle(bundle: GradeImportBundle) {
  return invokeNative<GradeImportResult>('commit_grade_bundle', { bundle });
}

export function previewImport(bundle: ImportBundle) {
  return invokeNative<ImportDiff>('preview_import_bundle', { bundle });
}

export async function commitImport(bundle: ImportBundle, mode: ImportMode = 'new') {
  const result = await invokeNative<ImportCommitResult>('commit_import_bundle', { bundle, mode });
  rememberImportCredits(bundle);
  return result;
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

export async function listShiguangAdapters(schoolId: string) {
  const adapters = await invokeNative<ShiguangAdapter[]>('shiguang_list_adapters', { schoolId });
  shiguangAdapterCache.set(schoolId, adapters);
  return adapters;
}

export async function startShiguangImport(schoolId: string, adapterId: string) {
  const started = await invokeNative<ShiguangImportStart>('shiguang_start_import', {
    schoolId,
    adapterId
  });
  const adapter = shiguangAdapterCache.get(schoolId)?.find((item) => item.adapterId === adapterId);
  if (adapter?.importUrl) {
    rememberAcademicSource(adapter.importUrl, adapter.schoolName || started.schoolName, adapter.adapterName);
  }
  return started;
}

export async function startSmartCompatibilityImport(url: string, schoolName = '') {
  const normalizedUrl = url.trim();
  const started = await invokeNative<ShiguangImportStart>('shiguang_start_smart_import', {
    url: normalizedUrl,
    schoolName: schoolName.trim()
  });
  rememberAcademicSource(normalizedUrl, started.schoolName || schoolName, started.adapterName);
  return started;
}

export async function startCompatibilityImport(url: string, family: CompatibilityFamily, schoolName = '') {
  const normalizedUrl = url.trim();
  const started = await invokeNative<ShiguangImportStart>('shiguang_start_custom_import', {
    url: normalizedUrl,
    family,
    schoolName: schoolName.trim()
  });
  rememberAcademicSource(normalizedUrl, started.schoolName || schoolName, started.adapterName);
  return started;
}

export async function startGradeCapture(url: string, institution = '') {
  const normalizedUrl = url.trim();
  const started = await invokeNative<ShiguangImportStart>('shiguang_start_grade_capture', {
    url: normalizedUrl,
    institution: institution.trim()
  });
  rememberAcademicSource(normalizedUrl, started.schoolName || institution, started.adapterName);
  return started;
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

export function saveDiagnosticLog() {
  return invokeNative<boolean>('export_diagnostic_log_to_file', {}, 300_000);
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
