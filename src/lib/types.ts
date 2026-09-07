export type PageId = 'today' | 'week' | 'import' | 'widgets' | 'settings';

export interface Course { id: string; name: string; teacher: string; room: string; start: string; end: string; day: number; color: string; startSection: number; endSection: number; weeks: number[]; }
export interface CourseMutation { id?: string | null; name: string; teacher: string; room: string; day: number; startSection: number; endSection: number; start: string; end: string; weeks: number[]; }
export interface ScheduleSnapshot { courses: Course[]; hasSchedule: boolean; termName?: string | null; termStart?: string | null; weekCount?: number | null; currentWeek?: number | null; }
export interface GlassSettings { blur: number; opacity: number; saturation: number; highlight: number; refraction: number; noise: number; motion: boolean; }
export interface ImportedCourse { name: string; teacher?: string | null; location?: string | null; weekday: number; startSection: number; endSection: number; weeks: number[]; startTime?: string | null; endTime?: string | null; }
export interface ImportBundle { source: string; termName?: string | null; termStart?: string | null; courses: ImportedCourse[]; metadata: Record<string, string>; }
export interface ShiguangSchool { id: string; name: string; initial: string; resourceFolder: string; }
export interface ShiguangAdapter { schoolId: string; schoolName: string; resourceFolder: string; adapterId: string; adapterName: string; category: string; assetJsPath: string; importUrl: string; maintainer: string; description: string; }
export interface ShiguangImportStart { sessionId: string; adapterName: string; schoolName: string; sourceSha256: string; allowedHosts: string[]; insecureTransport: boolean; status: string; }
export interface ShiguangSessionSnapshot { sessionId: string; adapterName: string; schoolName: string; status: 'running' | 'collecting' | 'complete' | 'error' | string; message?: string | null; bundle?: ImportBundle | null; timeSlots?: unknown; config?: unknown; }
export interface BackupSummary { schemaVersion: number; generatedAtUnixMs: number; tableCount: number; rowCount: number; scheduleCount: number; courseCount: number; meetingCount: number; }
export interface WebDavProfile { baseUrl: string; username: string; remotePath: string; }
export interface WebDavCredentials extends WebDavProfile { password: string; }
export interface WebDavResult { ok: boolean; status: number; message: string; remoteUrl?: string | null; etag?: string | null; backup?: BackupSummary | null; }
