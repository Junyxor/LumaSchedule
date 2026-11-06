/**
 * In-memory stand-in for the Android bridge, used only by `npm run dev` and
 * `npm run build:mock` so the UI can be developed in a desktop browser.
 * It is excluded from production builds.
 */
import type {
  Appearance,
  Course,
  CourseColor,
  DisplayPreferences,
  GradeRecord,
  ImportBundle,
  ReminderSettings,
  Semester,
  TimeSlot,
  WebDavProfile
} from '../types';
import { addDays, startOfWeek, toIsoDate } from '../schedule/calendar';

interface MockSchedule {
  id: string;
  name: string;
  termName: string;
  termStart: string;
  weekCount: number;
  weekStartsOn: number;
  timezone: string;
  timeSlots: TimeSlot[];
  courses: Course[];
}

interface MockDb {
  schedules: MockSchedule[];
  activeId: string | null;
  display: DisplayPreferences;
  appearance: Appearance | null;
  reminder: ReminderSettings;
  grades: GradeRecord[];
  webdav: WebDavProfile;
}

const KEY = 'luma.mock.db.v2';
const COLORS: CourseColor[] = ['violet', 'blue', 'cyan', 'teal', 'green', 'lime', 'amber', 'orange', 'red', 'pink'];
const uid = () => Math.random().toString(36).slice(2, 10);

function colorFor(name: string) {
  let hash = 0;
  for (const char of name) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return COLORS[Math.abs(hash) % COLORS.length];
}

const SLOTS: TimeSlot[] = [
  ['08:00', '08:45'], ['08:55', '09:40'], ['10:00', '10:45'], ['10:55', '11:40'],
  ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'], ['16:55', '17:40'],
  ['19:00', '19:45'], ['19:55', '20:40'], ['20:50', '21:35'], ['21:45', '22:30']
].map(([start, end], index) => ({ section: index + 1, start, end }));

function course(name: string, teacher: string, room: string, day: number, startSection: number, endSection: number, weeks: number[], credit: number | null = null): Course {
  return { id: uid(), courseId: uid(), name, teacher, room, day, startSection, endSection, startTime: '', endTime: '', weeks, color: colorFor(name), customColor: false, credit };
}

function seed(): MockDb {
  const all = Array.from({ length: 18 }, (_, index) => index + 1);
  const odd = all.filter((week) => week % 2 === 1);
  const termStart = toIsoDate(addDays(startOfWeek(new Date(), 1), -8 * 7));
  const courses = [
    course('高等数学（上）', '陈老师', '教 6-204', 1, 1, 2, all, 5),
    course('大学英语', '周老师', '教 3-106', 1, 5, 6, all, 3),
    course('C 语言程序设计', '林老师', '实验 4-302', 2, 3, 4, all, 3.5),
    course('大学物理', '许老师', '教 6-401', 3, 1, 2, all, 4),
    course('线性代数', '王老师', '教 2-108', 3, 5, 6, all, 3),
    course('思想道德与法治', '赵老师', '教 1-301', 4, 3, 4, all.slice(0, 12), 2),
    course('量子信息导论', '李老师', '理学馆 A302', 5, 1, 2, odd, 2),
    course('体育', '梁老师', '东区体育场', 5, 7, 8, all, 1),
    course('大学生职业规划', '王老师', '教 2-205', 4, 9, 10, all.slice(0, 8), 1)
  ];
  const today = ((new Date().getDay() + 6) % 7) + 1;
  courses.push(course('数据结构', '孙老师', '教 5-110', today, 3, 4, all, 4));
  courses.push(course('电路分析', '钱老师', '教 4-212', today, 7, 8, all, 3));
  const schedule: MockSchedule = { id: uid(), name: '2026 秋季学期', termName: '2026–2027 学年第一学期', termStart, weekCount: 18, weekStartsOn: 1, timezone: 'Asia/Shanghai', timeSlots: SLOTS, courses };
  const grade = (term: string, courseName: string, credit: number, score: number, point: number, courseType = '必修'): GradeRecord => ({
    id: uid(), source: 'Mock', institution: '示例大学', term, courseCode: '', courseName, courseType, credit, scoreText: String(score), numericScore: score, gradePoint: point, elective: courseType !== '必修', attempt: 1, importedAt: new Date().toISOString()
  });
  return {
    schedules: [schedule],
    activeId: schedule.id,
    display: { weekendMode: 'auto', showTeacher: true, showRoom: true, showTime: true, compactMode: false, defaultSections: 12 },
    appearance: null,
    reminder: { enabled: false, offsetMinutes: 15 },
    grades: [
      grade('2025-2026学年第一学期', '高等数学（下）', 5, 92, 4.2),
      grade('2025-2026学年第一学期', '大学英语 II', 3, 85, 3.5),
      grade('2025-2026学年第一学期', '程序设计基础', 3, 78, 2.8),
      grade('2025-2026学年第二学期', '离散数学', 3, 88, 3.8),
      grade('2025-2026学年第二学期', '概率论', 3, 66, 1.6),
      grade('2025-2026学年第二学期', '音乐鉴赏', 1, 95, 4.5, '公选')
    ],
    webdav: { baseUrl: '', username: '', remotePath: 'LumaSchedule/lumaschedule-latest.luma.json' }
  };
}

function load(): MockDb {
  try {
    const stored = JSON.parse(localStorage.getItem(KEY) || 'null') as MockDb | null;
    if (stored?.schedules) return stored;
  } catch {
    // Reseed below.
  }
  return seed();
}

let db = load();
const save = () => localStorage.setItem(KEY, JSON.stringify(db));
const active = () => db.schedules.find((item) => item.id === db.activeId) ?? db.schedules[db.schedules.length - 1] ?? null;
const sessions = new Map<string, { started: number; kind: string; school: string; adapter: string }>();

function semester(): Semester {
  const schedule = active();
  if (!schedule) return { hasSchedule: false, scheduleId: null, scheduleName: '', termName: '', termStart: '', weekCount: 20, weekStartsOn: 1, timezone: 'Asia/Shanghai', timeSlots: [], courses: [] };
  return { hasSchedule: true, scheduleId: schedule.id, scheduleName: schedule.name, termName: schedule.termName, termStart: schedule.termStart, weekCount: schedule.weekCount, weekStartsOn: schedule.weekStartsOn, timezone: schedule.timezone, timeSlots: schedule.timeSlots, courses: schedule.courses };
}

function ensureSchedule() {
  let schedule = active();
  if (!schedule) {
    schedule = { id: uid(), name: '我的课表', termName: '', termStart: '', weekCount: 20, weekStartsOn: 1, timezone: 'Asia/Shanghai', timeSlots: [], courses: [] };
    db.schedules.push(schedule);
    db.activeId = schedule.id;
  }
  return schedule;
}

function fromBundle(bundle: ImportBundle): Course[] {
  return bundle.courses.map((item) => ({
    id: uid(),
    courseId: uid(),
    name: item.name,
    teacher: item.teacher ?? '',
    room: item.location ?? '',
    day: item.weekday,
    startSection: item.startSection,
    endSection: item.endSection,
    startTime: item.startTime ?? '',
    endTime: item.endTime ?? '',
    weeks: item.weeks,
    color: colorFor(item.name),
    customColor: false,
    credit: item.credit ?? null
  }));
}

type Args = Record<string, any>;

function handle(command: string, args: Args): unknown {
  const schedule = active();
  switch (command) {
    case 'get_bootstrap':
      return { appVersion: 'preview', appearance: db.appearance, dbReady: true };
    case 'get_semester':
      return semester();
    case 'get_display_preferences':
      return db.display;
    case 'save_display_preferences':
      db.display = { ...db.display, ...args.preferences };
      return db.display;
    case 'save_term_settings': {
      const target = ensureSchedule();
      Object.assign(target, args.term);
      if (!target.name || target.name === '我的课表') target.name = args.term.termName || target.name;
      return semester();
    }
    case 'save_time_slots':
      ensureSchedule().timeSlots = args.slots;
      return semester();
    case 'list_schedules':
      return db.schedules.map((item) => ({ id: item.id, name: item.name, termName: item.termName, termStart: item.termStart, courseCount: new Set(item.courses.map((entry) => entry.name)).size, meetingCount: item.courses.length, active: item.id === active()?.id }));
    case 'activate_schedule':
      db.activeId = args.id;
      return null;
    case 'create_schedule': {
      const created: MockSchedule = { id: uid(), name: args.name, termName: args.name, termStart: '', weekCount: 20, weekStartsOn: 1, timezone: 'Asia/Shanghai', timeSlots: schedule?.timeSlots ?? [], courses: [] };
      db.schedules.push(created);
      db.activeId = created.id;
      return null;
    }
    case 'rename_schedule': {
      const target = db.schedules.find((item) => item.id === args.id);
      if (target) target.name = args.name;
      return null;
    }
    case 'delete_schedule':
      db.schedules = db.schedules.filter((item) => item.id !== args.id);
      if (db.activeId === args.id) db.activeId = db.schedules[db.schedules.length - 1]?.id ?? null;
      return null;
    case 'save_course': {
      const target = ensureSchedule();
      const input = args.course;
      const existing = target.courses.find((item) => item.id === input.id);
      const color = input.color === 'auto' ? colorFor(input.name) : input.color;
      const next: Course = { id: existing?.id ?? uid(), courseId: existing?.courseId ?? uid(), name: input.name, teacher: input.teacher, room: input.room, day: input.day, startSection: input.startSection, endSection: input.endSection, startTime: input.startTime, endTime: input.endTime, weeks: input.weeks, color, customColor: input.color !== 'auto', credit: input.credit };
      target.courses = existing ? target.courses.map((item) => (item.id === next.id ? next : item)) : [...target.courses, next];
      return { value: next.id };
    }
    case 'delete_course':
      if (schedule) schedule.courses = schedule.courses.filter((item) => item.id !== args.id);
      return null;
    case 'save_appearance':
      db.appearance = args.appearance;
      return null;
    case 'migrate_legacy_credits':
      return { value: 0 };
    case 'preview_import_bundle': {
      const incoming = (args.bundle as ImportBundle).courses.length;
      const existing = schedule?.courses.length ?? 0;
      return { hasExistingSchedule: !!schedule, existingCourseCount: existing, existingMeetingCount: existing, incomingCourseCount: incoming, incomingMeetingCount: incoming, newCount: incoming, duplicateCount: 0, conflictCount: Math.min(1, existing), removeCount: existing };
    }
    case 'commit_import_bundle': {
      const bundle = args.bundle as ImportBundle;
      const mode = args.mode as string;
      const courses = fromBundle(bundle);
      if (mode === 'new' || !schedule) {
        const created: MockSchedule = { id: uid(), name: bundle.termName || `${bundle.source} 导入`, termName: bundle.termName ?? '', termStart: bundle.termStart ?? '', weekCount: 20, weekStartsOn: 1, timezone: 'Asia/Shanghai', timeSlots: [], courses };
        db.schedules.push(created);
        db.activeId = created.id;
      } else if (mode === 'merge') {
        schedule.courses = [...schedule.courses, ...courses];
      } else {
        schedule.courses = courses;
        if (bundle.termStart) schedule.termStart = bundle.termStart;
      }
      const target = active()!;
      return { mode, termId: target.id, scheduleId: target.id, courseCount: new Set(target.courses.map((item) => item.name)).size, meetingCount: target.courses.length, addedCount: courses.length, skippedCount: 0, removedCount: 0 };
    }
    case 'get_grade_snapshot':
      return { records: db.grades, terms: [...new Set(db.grades.map((item) => item.term))], institutions: [...new Set(db.grades.map((item) => item.institution))], updatedAt: null };
    case 'commit_grade_bundle':
      return { recordCount: args.bundle.records.length, termCount: 1, replacedCount: 0 };
    case 'delete_grade_records': {
      const before = db.grades.length;
      db.grades = args.term ? db.grades.filter((item) => item.term !== args.term) : [];
      return { value: before - db.grades.length };
    }
    case 'get_reminder_settings':
      return db.reminder;
    case 'save_reminder_settings':
      db.reminder = { ...db.reminder, ...args.settings };
      return db.reminder;
    case 'sync_reminders':
      return { enabled: db.reminder.enabled, futureCount: db.reminder.enabled ? 23 : 0, scheduledCount: 23, cancelledCount: 0, skippedCount: 0, exactAlarmGranted: false, horizonDays: 14 };
    case 'request_notification_permission':
    case 'test_notification':
    case 'schedule_test_reminder':
    case 'request_exact_alarm_access':
      return { value: true };
    case 'get_exact_alarm_status':
      return { required: true, granted: false };
    case 'refresh_widgets':
      return null;
    case 'shiguang_list_schools':
      return [
        { id: 'GDUT', name: '广东工业大学', initial: 'G', resourceFolder: 'GDUT' },
        { id: 'SCUT', name: '华南理工大学', initial: 'H', resourceFolder: 'SCUT' },
        { id: 'BUPT', name: '北京邮电大学', initial: 'B', resourceFolder: 'BUPT' },
        { id: 'BJTU', name: '北京交通大学', initial: 'B', resourceFolder: 'BJTU' },
        { id: 'CCZU', name: '常州大学', initial: 'C', resourceFolder: 'CCZU' }
      ];
    case 'shiguang_list_adapters':
      return [{ schoolId: args.schoolId, schoolName: '示例大学', resourceFolder: args.schoolId, adapterId: `${args.schoolId}_01`, adapterName: '本科教务系统', category: 'BACHELOR', assetJsPath: 'x.js', importUrl: 'https://jwxt.example.edu.cn/', maintainer: 'mock', description: '登录后自动读取本学期课表。' }];
    case 'shiguang_start_import':
    case 'shiguang_start_smart_import':
    case 'shiguang_start_custom_import':
    case 'shiguang_start_grade_capture': {
      const sessionId = uid();
      const kind = command === 'shiguang_start_grade_capture' ? 'grades' : 'schedule';
      sessions.set(sessionId, { started: Date.now(), kind, school: args.schoolName || args.institution || '示例大学', adapter: '本科教务系统' });
      return { sessionId, adapterName: '本科教务系统', schoolName: args.schoolName || args.institution || '示例大学', sourceSha256: 'mock', allowedHosts: ['jwxt.example.edu.cn'], insecureTransport: false, status: 'running', captureKind: kind };
    }
    case 'shiguang_get_session': {
      const session = sessions.get(args.sessionId);
      if (!session) return { sessionId: args.sessionId, adapterName: '', schoolName: '', status: 'error', message: '会话不存在' };
      const elapsed = Date.now() - session.started;
      if (elapsed < 2500) return { sessionId: args.sessionId, adapterName: session.adapter, schoolName: session.school, status: 'running', message: '等待登录完成…' };
      if (session.kind === 'grades') {
        return { sessionId: args.sessionId, adapterName: session.adapter, schoolName: session.school, status: 'complete', gradeBundle: { source: 'Mock', institution: session.school, termName: '2026-2027学年第一学期', records: [{ courseName: '数据结构', credit: 4, scoreText: '91', numericScore: 91, gradePoint: 4.1 }] } };
      }
      return {
        sessionId: args.sessionId,
        adapterName: session.adapter,
        schoolName: session.school,
        status: 'complete',
        bundle: { source: 'Mock 教务', termName: '2026 秋季学期', termStart: active()?.termStart ?? null, metadata: {}, courses: [{ name: '操作系统', teacher: '吴老师', location: '教 7-101', weekday: 2, startSection: 7, endSection: 8, weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16], startTime: null, endTime: null }] }
      };
    }
    case 'shiguang_close_session':
      sessions.delete(args.sessionId);
      return null;
    case 'export_schedule_json':
    case 'export_schedule_ics':
    case 'export_full_backup':
    case 'export_diagnostics':
      return true;
    case 'restore_full_backup':
      return { schemaVersion: 2, generatedAtUnixMs: Date.now(), tableCount: 14, rowCount: 100, scheduleCount: 1, courseCount: 10, meetingCount: 12 };
    case 'get_webdav_profile':
      return db.webdav;
    case 'save_webdav_profile':
      db.webdav = args.profile;
      return null;
    case 'webdav_test':
    case 'webdav_upload':
    case 'webdav_restore':
      return { ok: true, status: 200, message: '模拟环境：操作成功。' };
    case 'check_update':
      return { currentVersion: 'preview', latestVersion: 'preview', updateAvailable: false, releaseUrl: 'https://github.com/Junyxor/LumaSchedule/releases' };
    default:
      throw new Error(`模拟环境未实现：${command}`);
  }
}

export function installMockNative() {
  window.LumaNative = {
    request(id, command, payload) {
      setTimeout(() => {
        try {
          const result = handle(command, JSON.parse(payload || '{}') as Args);
          save();
          window.__lumaNativeResolve?.(id, true, JSON.stringify(result ?? null));
        } catch (error) {
          window.__lumaNativeResolve?.(id, false, JSON.stringify({ message: error instanceof Error ? error.message : String(error) }));
        }
      }, 40 + Math.random() * 60);
    },
    setBackEnabled() {},
    setSystemBarsDark() {}
  };
  (window as unknown as { lumaMockReset: () => void }).lumaMockReset = () => {
    db = seed();
    save();
    location.reload();
  };
}
