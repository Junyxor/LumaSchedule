import type { Course, Semester, WeekendMode } from '../types';

/*
 * Calendar rules shared by every screen. Kotlin mirrors the same rules in
 * `ScheduleMath.kt` for reminders, the widget and ICS export:
 *
 * - Week 1 is the calendar week (Mon–Sun, or Sun–Sat when weekStartsOn = 7)
 *   that contains the term start date. A term may therefore start mid-week.
 * - `day` is always 1 = Monday … 7 = Sunday, independent of the week start.
 * - An empty `weeks` array means "every week".
 */

const DAY_MS = 86_400_000;
export const WEEKDAY_SHORT = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
export const WEEKDAY_NARROW = ['一', '二', '三', '四', '五', '六', '日'];

export const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
export const pad2 = (value: number) => String(value).padStart(2, '0');

export function parseIsoDate(raw: string | null | undefined): Date | null {
  const match = String(raw ?? '').trim().match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/);
  if (!match) return null;
  const month = Number(match[2]) - 1;
  const date = new Date(Number(match[1]), month, Number(match[3]), 12, 0, 0, 0);
  if (Number.isNaN(date.getTime()) || date.getMonth() !== month) return null;
  return date;
}

export function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

export function atNoon(date: Date) {
  const copy = new Date(date);
  copy.setHours(12, 0, 0, 0);
  return copy;
}

export function addDays(date: Date, days: number) {
  const copy = new Date(date);
  copy.setDate(copy.getDate() + days);
  return copy;
}

/** 1 = Monday … 7 = Sunday. */
export function weekdayOf(date: Date) {
  return ((date.getDay() + 6) % 7) + 1;
}

/** Position of `day` inside a week that starts on `weekStartsOn`. */
export function dayOffset(day: number, weekStartsOn: number) {
  return weekStartsOn === 7 ? day % 7 : day - 1;
}

export function dayOrder(weekStartsOn: number) {
  return weekStartsOn === 7 ? [7, 1, 2, 3, 4, 5, 6] : [1, 2, 3, 4, 5, 6, 7];
}

export function startOfWeek(date: Date, weekStartsOn: number) {
  const noon = atNoon(date);
  return addDays(noon, -dayOffset(weekdayOf(noon), weekStartsOn));
}

export function daysBetween(from: Date, to: Date) {
  return Math.round((atNoon(to).getTime() - atNoon(from).getTime()) / DAY_MS);
}

export function sameDay(left: Date, right: Date) {
  return left.getFullYear() === right.getFullYear() && left.getMonth() === right.getMonth() && left.getDate() === right.getDate();
}

export function teachingWeek(date: Date, termStart: Date, weekStartsOn: number) {
  return Math.floor(daysBetween(startOfWeek(termStart, weekStartsOn), date) / 7) + 1;
}

export type TermStatus = 'none' | 'unset' | 'before' | 'active' | 'after';

export interface TermContext {
  termStart: Date | null;
  weekStartsOn: number;
  weekCount: number;
  /** Teaching week of "now" without clamping; null when the start date is unknown. */
  rawWeek: number | null;
  status: TermStatus;
  /** Week shown by default in the week view, always within 1…weekCount. */
  currentWeek: number;
}

export function termContext(semester: Semester, now: Date): TermContext {
  const weekStartsOn = semester.weekStartsOn === 7 ? 7 : 1;
  const weekCount = clamp(Math.trunc(semester.weekCount || 20), 1, 64);
  const termStart = parseIsoDate(semester.termStart);
  if (!semester.hasSchedule) {
    return { termStart, weekStartsOn, weekCount, rawWeek: null, status: 'none', currentWeek: 1 };
  }
  if (!termStart) {
    return { termStart: null, weekStartsOn, weekCount, rawWeek: null, status: 'unset', currentWeek: 1 };
  }
  const rawWeek = teachingWeek(now, termStart, weekStartsOn);
  const status: TermStatus = rawWeek < 1 ? 'before' : rawWeek > weekCount ? 'after' : 'active';
  return { termStart, weekStartsOn, weekCount, rawWeek, status, currentWeek: clamp(rawWeek, 1, weekCount) };
}

/** First day of a teaching week. Without a term start, the current calendar week counts as week 1. */
export function weekStartDate(ctx: TermContext, week: number, now: Date) {
  const base = ctx.termStart ? startOfWeek(ctx.termStart, ctx.weekStartsOn) : startOfWeek(now, ctx.weekStartsOn);
  return addDays(base, (week - 1) * 7);
}

export function dateOfWeekDay(ctx: TermContext, week: number, day: number, now: Date) {
  return addDays(weekStartDate(ctx, week, now), dayOffset(day, ctx.weekStartsOn));
}

export function courseInWeek(course: Course, week: number) {
  return course.weeks.length === 0 || course.weeks.includes(week);
}

/** The teaching week a calendar date belongs to, or null when the date is outside the term. */
export function weekOfDate(ctx: TermContext, date: Date): number | null {
  if (!ctx.termStart) return null;
  const week = teachingWeek(date, ctx.termStart, ctx.weekStartsOn);
  return week >= 1 && week <= ctx.weekCount ? week : null;
}

/** Courses on a calendar date. Without a start date every week is assumed to be active. */
export function coursesOnDate(semester: Semester, ctx: TermContext, date: Date): Course[] {
  const day = weekdayOf(date);
  if (ctx.status === 'none') return [];
  if (ctx.status === 'unset') return semester.courses.filter((course) => course.day === day);
  const week = weekOfDate(ctx, date);
  if (week === null) return [];
  return semester.courses.filter((course) => course.day === day && courseInWeek(course, week));
}

// ---------------------------------------------------------------------------
// Clock times

export function parseClock(raw: string | null | undefined): number | null {
  const match = String(raw ?? '').trim().match(/^(\d{1,2})[:：](\d{2})/);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return hour * 60 + minute;
}

export function formatClock(minutes: number) {
  const safe = clamp(Math.round(minutes), 0, 24 * 60 - 1);
  return `${pad2(Math.floor(safe / 60))}:${pad2(safe % 60)}`;
}

export function normalizeClock(raw: string | null | undefined) {
  const value = parseClock(raw);
  return value === null ? '' : formatClock(value);
}

export function minutesOfDay(date: Date) {
  return date.getHours() * 60 + date.getMinutes();
}

const zoneFormatters = new Map<string, Intl.DateTimeFormat>();

/**
 * The wall-clock time in `timeZone` at `instant` (default: now), as a Date whose
 * local fields (getHours, getDate, …) read that wall clock. Every helper here works
 * on local fields, so this keeps "today", the teaching week and the current-time
 * line in the term's zone, which is what reminders and the widget use.
 */
export function wallClock(timeZone: string, instant = new Date()): Date {
  try {
    let format = zoneFormatters.get(timeZone);
    if (!format) {
      format = new Intl.DateTimeFormat('en-US', {
        timeZone,
        hourCycle: 'h23',
        year: 'numeric',
        month: 'numeric',
        day: 'numeric',
        hour: 'numeric',
        minute: 'numeric',
        second: 'numeric'
      });
      zoneFormatters.set(timeZone, format);
    }
    const parts: Record<string, number> = {};
    for (const part of format.formatToParts(instant)) if (part.type !== 'literal') parts[part.type] = Number(part.value);
    const date = new Date(parts.year, parts.month - 1, parts.day, parts.hour % 24, parts.minute, parts.second, instant.getMilliseconds());
    return Number.isNaN(date.getTime()) ? instant : date;
  } catch {
    // Unknown zone: fall back to the device clock.
    return instant;
  }
}

export interface SectionTime {
  section: number;
  start: number;
  end: number;
  /** True when the time comes from the saved timetable instead of being inferred. */
  saved: boolean;
  /** True when start or end falls back to the built-in typical day (neither saved nor used by any course). */
  estimated: boolean;
}

const DEFAULT_SLOTS: Array<[string, string]> = [
  ['08:00', '08:45'], ['08:55', '09:40'], ['10:00', '10:45'], ['10:55', '11:40'],
  ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'], ['16:55', '17:40'],
  ['19:00', '19:45'], ['19:55', '20:40'], ['20:50', '21:35'], ['21:45', '22:30']
];

function mostCommon(values: Array<number | null>) {
  const counts = new Map<number, number>();
  for (const value of values) if (value !== null) counts.set(value, (counts.get(value) ?? 0) + 1);
  let best: number | null = null;
  let bestCount = 0;
  for (const [value, count] of counts) {
    if (count > bestCount || (count === bestCount && best !== null && value < best)) {
      best = value;
      bestCount = count;
    }
  }
  return best;
}

export function sectionCountFor(semester: Semester, defaultSections: number) {
  const fromCourses = semester.courses.reduce((max, course) => Math.max(max, course.endSection || course.startSection || 1), 1);
  const fromSlots = semester.timeSlots.reduce((max, slot) => Math.max(max, slot.section), 0);
  return clamp(Math.max(defaultSections || 12, fromCourses, fromSlots), 1, 30);
}

/**
 * Start/end minute of every section: saved time slots first, then the most common
 * explicit time used by imported courses, then a typical Chinese university day.
 * `ScheduleMath.TimeResolver` in Kotlin applies the same rule, so reminders, the
 * widget and ICS export use the times shown here.
 */
export function buildSectionTimes(semester: Semester, count: number): SectionTime[] {
  const slots = new Map(semester.timeSlots.map((slot) => [slot.section, slot]));
  const result: SectionTime[] = [];
  for (let section = 1; section <= count; section += 1) {
    const previous = result[result.length - 1];
    const slot = slots.get(section);
    const savedStart = parseClock(slot?.start);
    const savedEnd = parseClock(slot?.end);
    let start =
      savedStart ??
      mostCommon(semester.courses.filter((course) => course.startSection === section).map((course) => parseClock(course.startTime)));
    let end =
      savedEnd ??
      mostCommon(semester.courses.filter((course) => course.endSection === section).map((course) => parseClock(course.endTime)));
    let estimated = false;
    if (start === null) {
      estimated = true;
      const fallback = DEFAULT_SLOTS[section - 1] ? parseClock(DEFAULT_SLOTS[section - 1][0]) : null;
      const afterPrevious = previous ? previous.end + 10 : 8 * 60;
      start = end !== null ? end - 45 : fallback !== null && fallback >= afterPrevious ? fallback : afterPrevious;
    }
    if (end === null || end <= start) {
      estimated = true;
      end = start + 45;
    }
    result.push({
      section,
      start: clamp(start, 0, 24 * 60 - 1),
      end: clamp(end, 1, 24 * 60),
      saved: savedStart !== null && savedEnd !== null,
      estimated
    });
  }
  return result;
}

/** True when the course's start or end time is a typical-day estimate rather than a known time. */
export function courseTimeEstimated(course: Course, sections: SectionTime[]) {
  const first = Math.max(1, course.startSection || 1);
  const last = Math.max(first, course.endSection || first);
  return (
    (parseClock(course.startTime) === null && sections[first - 1]?.estimated !== false) ||
    (parseClock(course.endTime) === null && sections[last - 1]?.estimated !== false)
  );
}

export interface TimeRange {
  start: number;
  end: number;
}

export function courseRange(course: Course, sections: SectionTime[]): TimeRange {
  const first = Math.max(1, course.startSection || 1);
  const last = Math.max(first, course.endSection || first);
  const start = parseClock(course.startTime) ?? sections[first - 1]?.start ?? 8 * 60 + (first - 1) * 55;
  let end = parseClock(course.endTime) ?? sections[last - 1]?.end ?? start + 45 * (last - first + 1);
  if (end <= start) end = start + 45;
  return { start, end };
}

export function sectionLabel(course: Pick<Course, 'startSection' | 'endSection'>) {
  const from = Math.max(1, course.startSection || 1);
  const to = Math.max(from, course.endSection || from);
  return from === to ? `第 ${from} 节` : `第 ${from}–${to} 节`;
}

export function rangeLabel(range: TimeRange) {
  return `${formatClock(range.start)}–${formatClock(range.end)}`;
}

// ---------------------------------------------------------------------------
// Occurrences ("this course on that date")

export interface Occurrence extends TimeRange {
  course: Course;
  date: Date;
}

export function occurrencesOn(semester: Semester, ctx: TermContext, sections: SectionTime[], date: Date): Occurrence[] {
  return coursesOnDate(semester, ctx, date)
    .map((course) => ({ course, date: atNoon(date), ...courseRange(course, sections) }))
    .sort((left, right) => left.start - right.start || left.end - right.end || left.course.name.localeCompare(right.course.name, 'zh-CN'));
}

export interface Upcoming {
  current: Occurrence | null;
  next: Occurrence | null;
}

/** The course in progress right now and the next one, looking ahead up to `maxDays`. */
export function findUpcoming(semester: Semester, ctx: TermContext, sections: SectionTime[], now: Date, maxDays = 21): Upcoming {
  const nowMinute = minutesOfDay(now);
  let current: Occurrence | null = null;
  for (let offset = 0; offset <= maxDays; offset += 1) {
    const date = addDays(atNoon(now), offset);
    for (const occurrence of occurrencesOn(semester, ctx, sections, date)) {
      if (offset === 0 && occurrence.end <= nowMinute) continue;
      if (offset === 0 && occurrence.start <= nowMinute) {
        if (!current) current = occurrence;
        continue;
      }
      return { current, next: occurrence };
    }
  }
  return { current, next: null };
}

// ---------------------------------------------------------------------------
// Weeks text ("1-16", "1-15单", "2,4,6")

export function weeksToText(weeks: number[]): string {
  const sorted = [...new Set(weeks.filter((week) => Number.isInteger(week) && week >= 1 && week <= 64))].sort((a, b) => a - b);
  if (!sorted.length) return '';
  const parts: string[] = [];
  let index = 0;
  while (index < sorted.length) {
    let end = index;
    while (end + 1 < sorted.length && sorted[end + 1] === sorted[end] + 1) end += 1;
    if (end > index) {
      parts.push(`${sorted[index]}-${sorted[end]}`);
      index = end + 1;
      continue;
    }
    // Try an odd/even run with step 2 (at least three weeks long).
    let stepEnd = index;
    while (stepEnd + 1 < sorted.length && sorted[stepEnd + 1] === sorted[stepEnd] + 2) stepEnd += 1;
    if (stepEnd - index >= 2) {
      parts.push(`${sorted[index]}-${sorted[stepEnd]}${sorted[index] % 2 === 1 ? '单' : '双'}`);
      index = stepEnd + 1;
      continue;
    }
    parts.push(String(sorted[index]));
    index += 1;
  }
  return parts.join(',');
}

export function weeksSummary(weeks: number[]) {
  if (!weeks.length) return '每周';
  return weeksToText(weeks)
    .split(',')
    .map((part) => {
      const parity = part.endsWith('单') ? '(单)' : part.endsWith('双') ? '(双)' : '';
      return `${part.replace(/[单双]$/, '').replace('-', '–')}周${parity}`;
    })
    .join(', ');
}

/** Parses "1-16", "1-15单", "2-16(双)", "第1~8周,10,12", "单" … into week numbers. */
export function parseWeeksText(raw: string, weekCount = 20): number[] {
  const result = new Set<number>();
  const text = raw.replace(/[，、；;]/g, ',').replace(/[（]/g, '(').replace(/[）]/g, ')').trim();
  const tokens = text.split(/[,\s]+/).filter(Boolean);
  for (const token of tokens) {
    const parity = /单/.test(token) ? 1 : /双/.test(token) ? 0 : -1;
    const range = token.match(/(\d{1,2})\s*[-~—–至到]\s*(\d{1,2})/);
    const single = token.match(/(\d{1,2})/);
    let from: number;
    let to: number;
    if (range) {
      from = Number(range[1]);
      to = Number(range[2]);
    } else if (single) {
      from = to = Number(single[1]);
    } else if (/^(全|每周|all)$/i.test(token) || parity >= 0) {
      from = 1;
      to = weekCount;
    } else {
      continue;
    }
    for (let week = Math.min(from, to); week <= Math.max(from, to); week += 1) {
      if (week < 1 || week > 64) continue;
      if (parity === 1 && week % 2 === 0) continue;
      if (parity === 0 && week % 2 === 1) continue;
      result.add(week);
    }
  }
  return [...result].sort((a, b) => a - b);
}

// ---------------------------------------------------------------------------
// Week grid helpers

export function visibleWeekdays(mode: WeekendMode, weekStartsOn: number, courses: Course[]) {
  const included = new Set([1, 2, 3, 4, 5]);
  if (mode === 'sat' || mode === 'both') included.add(6);
  if (mode === 'sun' || mode === 'both') included.add(7);
  if (mode === 'auto') {
    if (courses.some((course) => course.day === 6)) included.add(6);
    if (courses.some((course) => course.day === 7)) included.add(7);
  }
  return dayOrder(weekStartsOn).filter((day) => included.has(day));
}

export interface PlacedItem<T> extends TimeRange {
  item: T;
  lane: number;
  lanes: number;
}

/** Assigns side-by-side lanes to overlapping blocks inside one day column. */
export function layoutLanes<T>(items: Array<TimeRange & { item: T }>): PlacedItem<T>[] {
  const sorted = [...items].sort((a, b) => a.start - b.start || b.end - a.end);
  const placed: PlacedItem<T>[] = [];
  let cluster: PlacedItem<T>[] = [];
  let laneEnds: number[] = [];
  let clusterEnd = -1;
  const flush = () => {
    for (const entry of cluster) entry.lanes = laneEnds.length;
    placed.push(...cluster);
    cluster = [];
    laneEnds = [];
  };
  for (const entry of sorted) {
    if (cluster.length && entry.start >= clusterEnd) flush();
    let lane = laneEnds.findIndex((end) => end <= entry.start);
    if (lane < 0) {
      lane = laneEnds.length;
      laneEnds.push(entry.end);
    } else {
      laneEnds[lane] = entry.end;
    }
    clusterEnd = cluster.length ? Math.max(clusterEnd, entry.end) : entry.end;
    cluster.push({ ...entry, lane, lanes: 1 });
  }
  if (cluster.length) flush();
  return placed;
}

// ---------------------------------------------------------------------------
// Human readable labels

export function durationLabel(minutes: number) {
  const safe = Math.max(0, Math.round(minutes));
  if (safe < 60) return `${safe} 分钟`;
  const hours = Math.floor(safe / 60);
  const rest = safe % 60;
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`;
}

export function relativeDayLabel(date: Date, now: Date) {
  const diff = daysBetween(now, date);
  if (diff === 0) return '今天';
  if (diff === 1) return '明天';
  if (diff === 2) return '后天';
  const weekday = WEEKDAY_SHORT[weekdayOf(date) - 1];
  return diff > 0 && diff < 7 ? weekday : `${date.getMonth() + 1}月${date.getDate()}日 ${weekday}`;
}

export function monthDayLabel(date: Date) {
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

export function creditLabel(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '';
  return `${Number.isInteger(value) ? value : Number(value.toFixed(2))} 学分`;
}
