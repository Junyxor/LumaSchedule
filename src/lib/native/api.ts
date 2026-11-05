import type {
  Appearance,
  BackupSummary,
  Bootstrap,
  CompatibilityFamily,
  CourseMutation,
  DisplayPreferences,
  ExactAlarmStatus,
  GradeImportBundle,
  GradeImportResult,
  GradeSnapshot,
  ImportBundle,
  ImportCommitResult,
  ImportDiff,
  ImportMode,
  ReminderSettings,
  ReminderSyncReport,
  ScheduleSummary,
  Semester,
  ShiguangAdapter,
  ShiguangImportStart,
  ShiguangSchool,
  ShiguangSessionSnapshot,
  TermSettings,
  TimeSlot,
  UpdateCheckResult,
  WebDavCredentials,
  WebDavProfile,
  WebDavResult
} from '../types';
import { invoke, unwrap } from './bridge';

const LONG = 300_000;

export const api = {
  bootstrap: () => invoke<Bootstrap>('get_bootstrap'),
  checkUpdate: () => invoke<UpdateCheckResult>('check_update', {}, 30_000),

  // Schedule
  semester: () => invoke<Semester>('get_semester'),
  displayPreferences: () => invoke<DisplayPreferences>('get_display_preferences'),
  saveDisplayPreferences: (preferences: DisplayPreferences) =>
    invoke<DisplayPreferences>('save_display_preferences', { preferences }),
  saveTermSettings: (term: TermSettings) => invoke<Semester>('save_term_settings', { term }),
  saveTimeSlots: (slots: TimeSlot[]) => invoke<Semester>('save_time_slots', { slots }),
  listSchedules: () => invoke<ScheduleSummary[]>('list_schedules'),
  activateSchedule: (id: string) => invoke<void>('activate_schedule', { id }),
  createSchedule: (name: string) => invoke<void>('create_schedule', { name }),
  renameSchedule: (id: string, name: string) => invoke<void>('rename_schedule', { id, name }),
  deleteSchedule: (id: string) => invoke<void>('delete_schedule', { id }),
  saveCourse: async (course: CourseMutation) => unwrap(await invoke<{ value: string }>('save_course', { course })),
  deleteCourse: (id: string) => invoke<void>('delete_course', { id }),
  saveAppearance: (appearance: Appearance) => invoke<void>('save_appearance', { appearance }),
  migrateLegacyCredits: (byName: Record<string, number>) => invoke<{ value: number }>('migrate_legacy_credits', { byName }),

  // Import
  previewImport: (bundle: ImportBundle) => invoke<ImportDiff>('preview_import_bundle', { bundle }),
  commitImport: (bundle: ImportBundle, mode: ImportMode) =>
    invoke<ImportCommitResult>('commit_import_bundle', { bundle, mode }, 120_000),

  // Grades
  grades: () => invoke<GradeSnapshot>('get_grade_snapshot'),
  commitGrades: (bundle: GradeImportBundle) => invoke<GradeImportResult>('commit_grade_bundle', { bundle }),
  deleteGrades: (term: string | null) => invoke<{ value: number }>('delete_grade_records', { term }),

  // Reminders & widgets
  reminderSettings: () => invoke<ReminderSettings>('get_reminder_settings'),
  saveReminderSettings: (settings: ReminderSettings) => invoke<ReminderSettings>('save_reminder_settings', { settings }),
  syncReminders: () => invoke<ReminderSyncReport>('sync_reminders', {}, 120_000),
  requestNotificationPermission: async () =>
    unwrap(await invoke<{ value: boolean }>('request_notification_permission', {}, 60_000)),
  testNotification: async () => unwrap(await invoke<{ value: boolean }>('test_notification', {}, 60_000)),
  scheduleTestReminder: async (delayMs = 60_000) =>
    unwrap(await invoke<{ value: boolean }>('schedule_test_reminder', { delayMs }, 60_000)),
  exactAlarmStatus: () => invoke<ExactAlarmStatus>('get_exact_alarm_status'),
  requestExactAlarmAccess: async () => unwrap(await invoke<{ value: boolean }>('request_exact_alarm_access')),
  refreshWidgets: () => invoke<void>('refresh_widgets'),

  // University adapters
  listSchools: () => invoke<ShiguangSchool[]>('shiguang_list_schools', { query: '' }),
  listAdapters: (schoolId: string) => invoke<ShiguangAdapter[]>('shiguang_list_adapters', { schoolId }),
  startAdapterImport: (schoolId: string, adapterId: string) =>
    invoke<ShiguangImportStart>('shiguang_start_import', { schoolId, adapterId }),
  startSmartImport: (url: string, schoolName: string) =>
    invoke<ShiguangImportStart>('shiguang_start_smart_import', { url, schoolName }),
  startCompatibilityImport: (url: string, family: CompatibilityFamily, schoolName: string) =>
    invoke<ShiguangImportStart>('shiguang_start_custom_import', { url, family, schoolName }),
  startGradeCapture: (url: string, institution: string) =>
    invoke<ShiguangImportStart>('shiguang_start_grade_capture', { url, institution }),
  session: (sessionId: string) => invoke<ShiguangSessionSnapshot>('shiguang_get_session', { sessionId }),
  closeSession: (sessionId: string) => invoke<void>('shiguang_close_session', { sessionId }),

  // Files
  exportScheduleJson: () => invoke<boolean>('export_schedule_json', {}, LONG),
  exportScheduleIcs: () => invoke<boolean>('export_schedule_ics', {}, LONG),
  exportFullBackup: () => invoke<boolean>('export_full_backup', {}, LONG),
  exportDiagnostics: () => invoke<boolean>('export_diagnostics', {}, LONG),
  restoreFullBackup: () => invoke<BackupSummary | null>('restore_full_backup', {}, LONG),

  // WebDAV
  webDavProfile: () => invoke<WebDavProfile>('get_webdav_profile'),
  saveWebDavProfile: (profile: WebDavProfile) => invoke<void>('save_webdav_profile', { profile }),
  webDavTest: (credentials: WebDavCredentials) => invoke<WebDavResult>('webdav_test', { credentials }, 60_000),
  webDavUpload: (credentials: WebDavCredentials) => invoke<WebDavResult>('webdav_upload', { credentials }, 120_000),
  webDavRestore: (credentials: WebDavCredentials) => invoke<WebDavResult>('webdav_restore', { credentials }, 120_000)
};

// ---------------------------------------------------------------------------
// Small pieces of UI state that only live in WebView storage.

export interface RecentAcademicSource {
  url: string;
  schoolName: string;
  adapterName?: string;
  updatedAt: number;
}

const RECENT_SOURCE_KEY = 'luma.recentAcademicSource';

export function rememberAcademicSource(url: string, schoolName = '', adapterName = '') {
  const normalized = url.trim();
  if (!/^https?:\/\//i.test(normalized)) return;
  const source: RecentAcademicSource = {
    url: normalized,
    schoolName: schoolName.trim(),
    adapterName: adapterName.trim() || undefined,
    updatedAt: Date.now()
  };
  try {
    localStorage.setItem(RECENT_SOURCE_KEY, JSON.stringify(source));
  } catch {
    // Storage can be unavailable; the import itself still works.
  }
}

export function recentAcademicSource(): RecentAcademicSource | null {
  try {
    const parsed = JSON.parse(localStorage.getItem(RECENT_SOURCE_KEY) || 'null') as Partial<RecentAcademicSource> | null;
    const url = typeof parsed?.url === 'string' ? parsed.url.trim() : '';
    if (!parsed || !/^https?:\/\//i.test(url)) return null;
    return {
      url,
      schoolName: typeof parsed.schoolName === 'string' ? parsed.schoolName.trim() : '',
      adapterName: typeof parsed.adapterName === 'string' && parsed.adapterName.trim() ? parsed.adapterName.trim() : undefined,
      updatedAt: typeof parsed.updatedAt === 'number' ? parsed.updatedAt : 0
    };
  } catch {
    return null;
  }
}

const LEGACY_CREDIT_KEY = 'luma.courseCredits.v1';

/**
 * Older builds kept course credits in WebView localStorage. Move them into
 * SQLite once so that backups and the grade page see the same values.
 */
export async function migrateLegacyCredits() {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(LEGACY_CREDIT_KEY);
  } catch {
    return;
  }
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw) as { byName?: Record<string, unknown> };
    const byName: Record<string, number> = {};
    for (const [name, value] of Object.entries(parsed.byName ?? {})) {
      const credit = Number(value);
      if (name.trim() && Number.isFinite(credit) && credit >= 0 && credit <= 30) byName[name] = credit;
    }
    if (Object.keys(byName).length) await api.migrateLegacyCredits(byName);
    localStorage.removeItem(LEGACY_CREDIT_KEY);
  } catch {
    // Keep the legacy value so the next start can retry.
  }
}
