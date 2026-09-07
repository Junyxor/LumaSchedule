import type { BackupSummary, Course, CourseMutation, CourseReminderSettings, GlassSettings, ImportBundle, ImportedCourse, ReminderSyncReport, ScheduleSnapshot, ShiguangAdapter, ShiguangImportStart, ShiguangSchool, ShiguangSessionSnapshot, WebDavCredentials, WebDavProfile, WebDavResult } from './types';

declare global {
  interface Window {
    LumaNative?: { request(id: string, command: string, payload: string): void };
    __lumaNativeResolve?: (id: string, ok: boolean, payload: string) => void;
  }
}

type Pending = { resolve: (value: unknown) => void; reject: (reason?: unknown) => void; timer: ReturnType<typeof setTimeout> };
const pending = new Map<string, Pending>();
let requestSeq = 0;

window.__lumaNativeResolve = (id, ok, payload) => {
  const item = pending.get(id);
  if (!item) return;
  pending.delete(id);
  clearTimeout(item.timer);
  let value: unknown = null;
  try { value = JSON.parse(payload); } catch { value = payload; }
  if (ok) item.resolve(value);
  else {
    const message = typeof value === 'object' && value && 'message' in value ? String((value as { message: unknown }).message) : String(value);
    item.reject(new Error(message));
  }
};

function invokeNative<T>(command: string, args: Record<string, unknown> = {}, timeoutMs = 45_000): Promise<T> {
  const native = window.LumaNative;
  if (!native) return Promise.reject(new Error('当前环境没有 LumaSchedule Android Native Bridge。'));
  const id = `${Date.now().toString(36)}-${(++requestSeq).toString(36)}`;
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`原生操作超时：${command}`));
    }, timeoutMs);
    pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
    native.request(id, command, JSON.stringify(args));
  });
}

function unwrap<T>(value: { value: T } | T): T {
  return typeof value === 'object' && value !== null && 'value' in value ? (value as { value: T }).value : value as T;
}

export async function getBootstrap() { return invokeNative<{ appVersion: string; glassSettings?: GlassSettings | null; dbReady: boolean; nativeCore?: boolean; startupMs?: number }>('get_bootstrap'); }
export async function listScheduleCourses() { return (await getScheduleSnapshot()).courses as Course[]; }
export async function getScheduleSnapshot() { return invokeNative<ScheduleSnapshot>('get_schedule_snapshot'); }
export async function saveScheduleCourse(course: CourseMutation) { return unwrap(await invokeNative<{ value: string }>('save_schedule_course', { course })); }
export async function deleteScheduleCourse(id: string) { return invokeNative<void>('delete_schedule_course', { id }); }
export async function saveGlassSettings(settings: GlassSettings) {
  localStorage.setItem('luma.glass', JSON.stringify(settings));
  return invokeNative<void>('save_glass_settings', { settings }).catch(() => undefined);
}

function parseWeeks(value: unknown, startWeek?: number, endWeek?: number, oddEven?: unknown): number[] {
  let weeks: number[] = [];
  if (Array.isArray(value)) weeks = value.map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 64);
  else if (typeof value === 'string') {
    for (const token of value.replace(/，/g, ',').split(/[\s,]+/).filter(Boolean)) {
      const range = token.match(/^(\d{1,2})\s*[-~至]\s*(\d{1,2})$/);
      if (range) for (let n = Number(range[1]); n <= Number(range[2]); n++) if (n >= 1 && n <= 64) weeks.push(n);
      else { const n = Number(token); if (Number.isInteger(n) && n >= 1 && n <= 64) weeks.push(n); }
    }
  }
  if (!weeks.length && startWeek && endWeek) for (let n = startWeek; n <= endWeek; n++) weeks.push(n);
  if (!weeks.length) weeks = Array.from({ length: 20 }, (_, i) => i + 1);
  const marker = String(oddEven ?? '').toLowerCase();
  if (marker.includes('单') || marker === 'odd' || marker === '1') weeks = weeks.filter((n) => n % 2 === 1);
  if (marker.includes('双') || marker === 'even' || marker === '2') weeks = weeks.filter((n) => n % 2 === 0);
  return [...new Set(weeks)].sort((a, b) => a - b);
}

function normalizeCourse(raw: Record<string, unknown>): ImportedCourse | null {
  const name = String(raw.name ?? raw.courseName ?? raw.kcmc ?? raw.title ?? '').trim();
  if (!name) return null;
  const weekday = Number(raw.weekday ?? raw.day ?? raw.week ?? raw.xqj ?? 1);
  const startSection = Number(raw.startSection ?? raw.startNode ?? raw.start_section ?? raw.startUnit ?? 1);
  const step = Number(raw.step ?? raw.sectionCount ?? 1);
  const endSection = Number(raw.endSection ?? raw.endNode ?? raw.end_section ?? (startSection + Math.max(1, step) - 1));
  return {
    name,
    teacher: String(raw.teacher ?? raw.teacherName ?? raw.jsxm ?? '').trim() || null,
    location: String(raw.location ?? raw.position ?? raw.room ?? raw.classroom ?? raw.jxcdmc ?? '').trim() || null,
    weekday: Math.min(7, Math.max(1, weekday || 1)),
    startSection: Math.max(1, startSection || 1),
    endSection: Math.max(startSection || 1, endSection || startSection || 1),
    weeks: parseWeeks(raw.weeks ?? raw.weekList, Number(raw.startWeek ?? raw.start_week), Number(raw.endWeek ?? raw.end_week), raw.type ?? raw.weekType ?? raw.oddEven),
    startTime: String(raw.startTime ?? raw.start_time ?? '').trim() || null,
    endTime: String(raw.endTime ?? raw.end_time ?? '').trim() || null
  };
}

function parseJsonImport(payload: string): ImportBundle {
  const root = JSON.parse(payload) as unknown;
  const object = (!Array.isArray(root) && root && typeof root === 'object' ? root : {}) as Record<string, unknown>;
  const candidates = Array.isArray(root) ? root : (object.courses ?? object.courseList ?? object.data ?? object.items ?? []);
  if (!Array.isArray(candidates)) throw new Error('JSON 中没有可识别的课程数组。');
  const courses = candidates.map((item) => normalizeCourse((item ?? {}) as Record<string, unknown>)).filter((item): item is ImportedCourse => !!item);
  if (!courses.length) throw new Error('JSON 中没有识别到有效课程。');
  const metadata: Record<string, string> = {};
  if (object.metadata && typeof object.metadata === 'object') Object.entries(object.metadata as Record<string, unknown>).forEach(([k, v]) => metadata[k] = typeof v === 'string' ? v : JSON.stringify(v));
  return {
    source: String(object.source ?? 'JSON'),
    termName: String(object.termName ?? object.term_name ?? '').trim() || null,
    termStart: String(object.termStart ?? object.semesterStartDate ?? object.term_start ?? '').trim() || null,
    courses,
    metadata
  };
}

function parseDelimited(payload: string, delimiter: string): ImportBundle {
  const rows: string[][] = [];
  let row: string[] = [], field = '', quoted = false;
  for (let i = 0; i < payload.length; i++) {
    const ch = payload[i];
    if (ch === '"') {
      if (quoted && payload[i + 1] === '"') { field += '"'; i++; }
      else quoted = !quoted;
    } else if (ch === delimiter && !quoted) { row.push(field); field = ''; }
    else if ((ch === '\n' || ch === '\r') && !quoted) {
      if (ch === '\r' && payload[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.some((v) => v.trim())) rows.push(row);
      row = [];
    } else field += ch;
  }
  row.push(field); if (row.some((v) => v.trim())) rows.push(row);
  if (rows.length < 2) throw new Error('表格中没有课程数据。');
  const headers = rows[0].map((v) => v.trim().toLowerCase());
  const indexOf = (...names: string[]) => headers.findIndex((header) => names.some((name) => header === name.toLowerCase()));
  const idx = {
    name: indexOf('name','course','课程','课程名称'), teacher: indexOf('teacher','教师','老师'), room: indexOf('room','location','教室','地点'),
    day: indexOf('day','weekday','星期','周几'), start: indexOf('startsection','startnode','开始节次','开始节'), end: indexOf('endsection','结束节次','结束节'), weeks: indexOf('weeks','周次'), startTime: indexOf('starttime','开始时间'), endTime: indexOf('endtime','结束时间')
  };
  if (idx.name < 0 || idx.day < 0 || idx.start < 0) throw new Error('表格至少需要课程名称、星期和开始节次列。');
  const value = (r: string[], i: number) => i >= 0 ? (r[i] ?? '').trim() : '';
  const courses = rows.slice(1).map((r) => normalizeCourse({
    name: value(r, idx.name), teacher: value(r, idx.teacher), room: value(r, idx.room), day: value(r, idx.day).replace(/周|星期/g,''),
    startSection: value(r, idx.start), endSection: value(r, idx.end) || value(r, idx.start), weeks: value(r, idx.weeks), startTime: value(r, idx.startTime), endTime: value(r, idx.endTime)
  })).filter((item): item is ImportedCourse => !!item);
  if (!courses.length) throw new Error('表格中没有识别到有效课程。');
  return { source: delimiter === '\t' ? 'TSV' : 'CSV', courses, metadata: {} };
}

function unfoldIcs(payload: string) { return payload.replace(/\r?\n[ \t]/g, ''); }
function icsValue(block: string, key: string) {
  const line = block.split(/\r?\n/).find((item) => item.toUpperCase().startsWith(`${key}:`) || item.toUpperCase().startsWith(`${key};`));
  return line?.slice(line.indexOf(':') + 1).trim() ?? '';
}
function parseIcsDate(raw: string) {
  const compact = raw.replace(/[^0-9T]/g, '');
  const m = compact.match(/^(\d{4})(\d{2})(\d{2})T?(\d{2})?(\d{2})?/);
  if (!m) return null;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4] ?? 0), Number(m[5] ?? 0));
}
function parseIcs(payload: string): ImportBundle {
  const text = unfoldIcs(payload);
  const blocks = text.split('BEGIN:VEVENT').slice(1).map((part) => part.split('END:VEVENT')[0]);
  const starts = blocks.map((block) => parseIcsDate(icsValue(block, 'DTSTART'))).filter((d): d is Date => !!d);
  if (!starts.length) throw new Error('ICS 中没有可识别的 VEVENT。');
  const earliest = new Date(Math.min(...starts.map((d) => d.getTime())));
  const monday = new Date(earliest); monday.setDate(earliest.getDate() - ((earliest.getDay() + 6) % 7));
  const termStart = `${monday.getFullYear()}-${String(monday.getMonth()+1).padStart(2,'0')}-${String(monday.getDate()).padStart(2,'0')}`;
  const courses: ImportedCourse[] = [];
  blocks.forEach((block) => {
    const start = parseIcsDate(icsValue(block, 'DTSTART')); const end = parseIcsDate(icsValue(block, 'DTEND'));
    if (!start) return;
    const summary = icsValue(block, 'SUMMARY') || '课程';
    const rrule = icsValue(block, 'RRULE');
    const count = Number(rrule.match(/COUNT=(\d+)/i)?.[1] ?? 20);
    courses.push({
      name: summary.replace(/\\,/g, ','), teacher: null, location: icsValue(block, 'LOCATION').replace(/\\,/g, ',') || null,
      weekday: ((start.getDay() + 6) % 7) + 1, startSection: 1, endSection: 1,
      weeks: Array.from({ length: Math.max(1, Math.min(64, count)) }, (_, i) => i + 1),
      startTime: `${String(start.getHours()).padStart(2,'0')}:${String(start.getMinutes()).padStart(2,'0')}`,
      endTime: end ? `${String(end.getHours()).padStart(2,'0')}:${String(end.getMinutes()).padStart(2,'0')}` : null
    });
  });
  return { source: 'ICS', termStart, courses, metadata: {} };
}

export async function importText(format: string, payload: string): Promise<ImportBundle> {
  if (format === 'json') return parseJsonImport(payload);
  if (format === 'csv') return parseDelimited(payload, ',');
  if (format === 'tsv') return parseDelimited(payload, '\t');
  if (format === 'ics') return parseIcs(payload);
  if (format === 'cses') throw new Error('CSES YAML 正在迁移到无 Rust 解析器；当前请先使用 JSON / CSV / TSV / ICS。');
  throw new Error(`暂不支持的导入格式：${format}`);
}

export async function commitImport(bundle: ImportBundle) { return invokeNative<{ termId: string; scheduleId: string; courseCount: number; meetingCount: number }>('commit_import_bundle', { bundle }); }
export async function ensureNotificationPermission() { return true; }
export async function testNotification() { return unwrap(await invokeNative<{ value: boolean }>('test_notification')); }
export async function getCourseReminderSettings() { return invokeNative<CourseReminderSettings>('get_course_reminder_settings'); }
export async function saveCourseReminderSettings(settings: CourseReminderSettings) { return invokeNative<CourseReminderSettings>('save_course_reminder_settings', { settings }); }
export async function syncCourseReminders() { return invokeNative<ReminderSyncReport>('sync_course_reminders'); }
export async function publishWidgetSnapshot(snapshot: { courseName: string; courseMeta: string; countdown: string }) { return unwrap(await invokeNative<{ value: boolean }>('update_widget_snapshot', { snapshot })); }
export async function scheduleTestReminder(delayMs = 60_000) {
  const id = Math.floor(Date.now() % 2_000_000_000);
  return unwrap(await invokeNative<{ value: boolean }>('schedule_native_reminder', { reminder: { id, triggerAtEpochMs: Date.now() + delayMs, title: 'LumaSchedule · 测试提醒', body: '后台定时提醒触发成功。' } }));
}

export async function listShiguangSchools(query = '') { return invokeNative<ShiguangSchool[]>('shiguang_list_schools', { query: query || null }); }
export async function listShiguangAdapters(schoolId: string) { return invokeNative<ShiguangAdapter[]>('shiguang_list_adapters', { schoolId }); }
export async function startShiguangImport(schoolId: string, adapterId: string) { return invokeNative<ShiguangImportStart>('shiguang_start_import', { schoolId, adapterId }); }
export async function getShiguangSession(sessionId: string) { return invokeNative<ShiguangSessionSnapshot>('shiguang_get_session', { sessionId }); }
export async function closeShiguangSession(sessionId: string) { return invokeNative<void>('shiguang_close_session', { sessionId }); }

export async function saveLatestScheduleJson() { return invokeNative<boolean>('export_latest_schedule_json_to_file'); }
export async function saveLatestScheduleIcs() { return invokeNative<boolean>('export_latest_schedule_ics_to_file'); }
export async function saveFullBackup() { return invokeNative<boolean>('export_full_backup_to_file'); }
export async function restoreFullBackupFromFile() { return invokeNative<BackupSummary | null>('restore_full_backup_from_file'); }
export async function getWebDavProfile() { return invokeNative<WebDavProfile>('get_webdav_profile'); }
export async function saveWebDavProfile(profile: WebDavProfile) { return invokeNative<void>('save_webdav_profile', { profile }); }
export async function testWebDav(credentials: WebDavCredentials) { return invokeNative<WebDavResult>('webdav_test', { credentials }, 60_000); }
export async function uploadWebDavBackup(credentials: WebDavCredentials) { return invokeNative<WebDavResult>('webdav_upload_backup', { credentials }, 60_000); }
export async function restoreWebDavBackup(credentials: WebDavCredentials) { return invokeNative<WebDavResult>('webdav_restore_backup', { credentials }, 60_000); }
