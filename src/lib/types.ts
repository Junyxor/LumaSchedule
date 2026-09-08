export type PageId = 'today' | 'week' | 'grades' | 'import' | 'widgets' | 'settings';

export interface Course { id: string; name: string; teacher: string; room: string; start: string; end: string; day: number; color: string; startSection: number; endSection: number; weeks: number[]; }
export interface CourseMutation { id?: string | null; name: string; teacher: string; room: string; day: number; startSection: number; endSection: number; start: string; end: string; weeks: number[]; }
export interface ScheduleSnapshot { courses: Course[]; hasSchedule: boolean; termName?: string | null; termStart?: string | null; weekCount?: number | null; currentWeek?: number | null; }
export interface GlassSettings { blur: number; opacity: number; saturation: number; highlight: number; refraction: number; noise: number; motion: boolean; }
export interface CourseReminderSettings { enabled: boolean; offsetMinutes: number; }
export interface ReminderSyncReport { enabled: boolean; futureCount: number; scheduledCount: number; cancelledCount: number; skippedCount: number; }
export type WeekendMode = 'auto' | 'weekdays' | 'sat' | 'sun' | 'both';
export interface SchedulePreferences {
  hasSchedule: boolean;
  termName: string;
  termStart: string;
  weekCount: number;
  timezone: string;
  weekStartsOn: number;
  weekendMode: WeekendMode;
  /** Legacy migration hint for builds that only had a boolean weekend switch. */
  showWeekend?: boolean;
  showTeacher: boolean;
  showRoom: boolean;
  showTime: boolean;
  compactMode: boolean;
  defaultSections: number;
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

export interface ImportedCourse { name: string; teacher?: string | null; location?: string | null; weekday: number; startSection: number; endSection: number; weeks: number[]; startTime?: string | null; endTime?: string | null; }
export interface ImportBundle { source: string; termName?: string | null; termStart?: string | null; courses: ImportedCourse[]; metadata: Record<string, string>; }
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
export interface ShiguangSchool { id: string; name: string; initial: string; resourceFolder: string; }
export interface ShiguangAdapter { schoolId: string; schoolName: string; resourceFolder: string; adapterId: string; adapterName: string; category: string; assetJsPath: string; importUrl: string; maintainer: string; description: string; }
export interface ShiguangImportStart { sessionId: string; adapterName: string; schoolName: string; sourceSha256: string; allowedHosts: string[]; insecureTransport: boolean; status: string; captureKind?: 'schedule' | 'grades' | string; }
export interface ShiguangSessionSnapshot { sessionId: string; adapterName: string; schoolName: string; status: 'running' | 'collecting' | 'complete' | 'error' | string; message?: string | null; bundle?: ImportBundle | null; gradeBundle?: GradeImportBundle | null; captureKind?: 'schedule' | 'grades' | string; timeSlots?: unknown; config?: unknown; }
export type CompatibilityFamily = 'zhengfang_jiaowu' | 'qingguo_jiaowu' | 'urp_jiaowu' | 'chaoxing_jiaowu';
export interface BackupSummary { schemaVersion: number; generatedAtUnixMs: number; tableCount: number; rowCount: number; scheduleCount: number; courseCount: number; meetingCount: number; }
export interface WebDavProfile { baseUrl: string; username: string; remotePath: string; }
export interface WebDavCredentials extends WebDavProfile { password: string; }
export interface WebDavResult { ok: boolean; status: number; message: string; remoteUrl?: string | null; etag?: string | null; backup?: BackupSummary | null; }
export interface UpdateCheckResult {
  currentVersion: string;
  latestVersion: string;
  updateAvailable: boolean;
  releaseUrl: string;
  name?: string | null;
  publishedAt?: string | null;
}
