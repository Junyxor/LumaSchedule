export type PageId = 'today' | 'week' | 'grades' | 'import' | 'settings';
export type WeekendMode = 'auto' | 'weekdays' | 'sat' | 'sun' | 'both';
export type ThemeMode = 'system' | 'light' | 'dark';
export type AccentId = 'luma' | 'blue' | 'teal' | 'green' | 'orange' | 'pink' | 'graphite';
export type CourseColor = 'violet' | 'blue' | 'cyan' | 'teal' | 'green' | 'lime' | 'amber' | 'orange' | 'red' | 'pink';

/** One row of a school's daily timetable ("作息时间"). Times are HH:mm. */
export interface TimeSlot {
  section: number;
  start: string;
  end: string;
}

/** A single meeting (上课时段) of a course in the active schedule. */
export interface Course {
  /** Meeting id. Editing and deleting operate on a single meeting. */
  id: string;
  courseId: string;
  name: string;
  teacher: string;
  room: string;
  /** 1 = Monday … 7 = Sunday. */
  day: number;
  startSection: number;
  endSection: number;
  /** Explicit clock time of this meeting, or '' when it follows the time slots. */
  startTime: string;
  endTime: string;
  weeks: number[];
  color: CourseColor;
  customColor: boolean;
  credit: number | null;
}

export interface CourseMutation {
  id?: string | null;
  name: string;
  teacher: string;
  room: string;
  day: number;
  startSection: number;
  endSection: number;
  startTime: string;
  endTime: string;
  weeks: number[];
  credit: number | null;
  color: CourseColor | 'auto';
}

export interface Semester {
  hasSchedule: boolean;
  scheduleId: string | null;
  scheduleName: string;
  termName: string;
  /** YYYY-MM-DD, or '' when the start date is unknown. */
  termStart: string;
  weekCount: number;
  /** 1 = weeks start on Monday, 7 = weeks start on Sunday. */
  weekStartsOn: number;
  timezone: string;
  timeSlots: TimeSlot[];
  courses: Course[];
}

export interface TermSettings {
  termName: string;
  termStart: string;
  weekCount: number;
  weekStartsOn: number;
  timezone: string;
}

export interface DisplayPreferences {
  weekendMode: WeekendMode;
  showTeacher: boolean;
  showRoom: boolean;
  showTime: boolean;
  compactMode: boolean;
  defaultSections: number;
}

export interface Appearance {
  theme: ThemeMode;
  accent: AccentId;
  colorfulCourses: boolean;
}

export interface ScheduleSummary {
  id: string;
  name: string;
  termName: string;
  termStart: string;
  courseCount: number;
  meetingCount: number;
  active: boolean;
}

export interface Bootstrap {
  appVersion: string;
  appearance?: Partial<Appearance> | null;
  dbReady: boolean;
}

export interface ReminderSettings {
  enabled: boolean;
  offsetMinutes: number;
}

export interface ExactAlarmStatus {
  required: boolean;
  granted: boolean;
}

export interface ReminderSyncReport {
  enabled: boolean;
  futureCount: number;
  scheduledCount: number;
  cancelledCount: number;
  skippedCount: number;
  exactAlarmGranted: boolean;
  horizonDays?: number;
  /** Meetings whose clock time is estimated from a typical day; their reminders use that estimate. */
  estimatedCount?: number;
  problem?: 'missingTermStart' | 'noSchedule' | null;
}

export interface GradeRecord {
  id: string;
  source: string;
  institution: string;
  term: string;
  courseCode: string;
  courseName: string;
  courseType: string;
  credit: number | null;
  scoreText: string;
  numericScore: number | null;
  gradePoint: number | null;
  elective: boolean;
  attempt: number;
  importedAt: string;
}

export interface GradeSnapshot {
  records: GradeRecord[];
  terms: string[];
  institutions: string[];
  updatedAt?: string | null;
}

export interface GradeImportRecord {
  courseCode?: string | null;
  courseName: string;
  courseType?: string | null;
  credit?: number | null;
  scoreText?: string | null;
  numericScore?: number | null;
  gradePoint?: number | null;
  elective?: boolean | null;
  attempt?: number | null;
  term?: string | null;
}

export interface GradeImportBundle {
  source: string;
  institution?: string | null;
  termName?: string | null;
  records: GradeImportRecord[];
  metadata?: Record<string, string>;
}

export interface GradeImportResult {
  recordCount: number;
  termCount: number;
  replacedCount: number;
}

export interface ImportedCourse {
  name: string;
  teacher?: string | null;
  location?: string | null;
  weekday: number;
  startSection: number;
  endSection: number;
  weeks: number[];
  startTime?: string | null;
  endTime?: string | null;
  credit?: number | null;
}

export interface ImportBundle {
  source: string;
  termName?: string | null;
  termStart?: string | null;
  courses: ImportedCourse[];
  metadata: Record<string, string>;
}

export type ImportMode = 'new' | 'merge' | 'overwrite';

export interface ImportDiff {
  hasExistingSchedule: boolean;
  existingCourseCount: number;
  existingMeetingCount: number;
  incomingCourseCount: number;
  incomingMeetingCount: number;
  newCount: number;
  duplicateCount: number;
  conflictCount: number;
  removeCount: number;
}

export interface ImportCommitResult {
  mode: ImportMode;
  termId: string;
  scheduleId: string;
  courseCount: number;
  meetingCount: number;
  addedCount: number;
  skippedCount: number;
  removedCount: number;
}

export interface ShiguangSchool {
  id: string;
  name: string;
  initial: string;
  resourceFolder: string;
}

export interface ShiguangAdapter {
  schoolId: string;
  schoolName: string;
  resourceFolder: string;
  adapterId: string;
  adapterName: string;
  category: string;
  assetJsPath: string;
  importUrl: string;
  maintainer: string;
  description: string;
}

export type CaptureKind = 'schedule' | 'grades';

export interface ShiguangImportStart {
  sessionId: string;
  adapterName: string;
  schoolName: string;
  sourceSha256: string;
  allowedHosts: string[];
  insecureTransport: boolean;
  status: string;
  captureKind?: CaptureKind | string;
}

export interface ShiguangSessionSnapshot {
  sessionId: string;
  adapterName: string;
  schoolName: string;
  status: 'running' | 'collecting' | 'complete' | 'error' | string;
  message?: string | null;
  bundle?: ImportBundle | null;
  gradeBundle?: GradeImportBundle | null;
  captureKind?: CaptureKind | string;
}

export type CompatibilityFamily = 'zhengfang_jiaowu' | 'qingguo_jiaowu' | 'urp_jiaowu' | 'chaoxing_jiaowu';

export interface BackupSummary {
  schemaVersion: number;
  generatedAtUnixMs: number;
  tableCount: number;
  rowCount: number;
  scheduleCount: number;
  courseCount: number;
  meetingCount: number;
  /** The backup had reminders on, but notification permission was refused, so they were turned off. */
  remindersDisabled?: boolean;
}

export interface WebDavProfile {
  baseUrl: string;
  username: string;
  remotePath: string;
}

export interface WebDavCredentials extends WebDavProfile {
  password: string;
}

export interface WebDavResult {
  ok: boolean;
  status: number;
  message: string;
  remoteUrl?: string | null;
  etag?: string | null;
  backup?: BackupSummary | null;
}

export interface UpdateCheckResult {
  currentVersion: string;
  latestVersion: string;
  updateAvailable: boolean;
  releaseUrl: string;
  name?: string | null;
  publishedAt?: string | null;
}
