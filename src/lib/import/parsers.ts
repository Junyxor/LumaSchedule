import type { ImportBundle, ImportedCourse } from '../types';
import { addDays, atNoon, daysBetween, formatClock, normalizeClock, parseClock, parseIsoDate, parseWeeksText, startOfWeek, toIsoDate, weekdayOf } from '../schedule/calendar';

export type ImportFormat = 'json' | 'wakeup' | 'csv' | 'tsv' | 'ics' | 'cses';

export const IMPORT_ACCEPT = '.wakeup_schedule,.json,.txt,.ics,.ical,.csv,.tsv,.yaml,.yml,.cses';
const MAX_FILE_BYTES = 8 * 1024 * 1024;

const REFERENCE_TIMES: Array<[string, string]> = [
  ['08:00', '08:45'], ['08:55', '09:40'], ['10:00', '10:45'], ['10:55', '11:40'],
  ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'], ['16:55', '17:40'],
  ['19:00', '19:45'], ['19:55', '20:40'], ['20:50', '21:35'], ['21:45', '22:30']
];

/** Typical section times, used to guess sections for files that only carry clock times (ICS, CSES). */
const REFERENCE_SECTIONS: Array<[number, number]> = REFERENCE_TIMES.map(([start, end]) => [parseClock(start) ?? 0, parseClock(end) ?? 0]);

function nearestSection(minute: number, edge: 0 | 1) {
  let best = 1;
  let bestDistance = Number.POSITIVE_INFINITY;
  REFERENCE_SECTIONS.forEach((slot, index) => {
    const distance = Math.abs(slot[edge] - minute);
    if (distance < bestDistance) {
      best = index + 1;
      bestDistance = distance;
    }
  });
  return best;
}

function sectionsFromTimes(start: number, end: number) {
  const startSection = nearestSection(start, 0);
  return { startSection, endSection: Math.max(startSection, nearestSection(end, 1)) };
}

// ---------------------------------------------------------------------------
// Entry points

export async function readImportFile(file: File): Promise<ImportBundle> {
  if (file.size > MAX_FILE_BYTES) throw new Error('文件超过 8 MB，不像是课表文件。');
  const text = decodeText(await file.arrayBuffer());
  const format = detectFormat(file.name, text);
  if (!format) throw new Error('无法识别这个文件。支持 WakeUp 备份、JSON、ICS、CSV/TSV 和 CSES。');
  return parseImport(format, text, file.name);
}

/** UTF-8 first; Excel on Chinese Windows often saves CSV as GB18030. */
export function decodeText(buffer: ArrayBuffer) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer).replace(/^\uFEFF/, '');
  } catch {
    try {
      return new TextDecoder('gb18030').decode(buffer).replace(/^\uFEFF/, '');
    } catch {
      return new TextDecoder('utf-8').decode(buffer).replace(/^\uFEFF/, '');
    }
  }
}

export function detectFormat(fileName: string, text: string): ImportFormat | null {
  const ext = fileName.toLowerCase().split('.').pop() ?? '';
  const head = text.trimStart();
  if (ext === 'wakeup_schedule' || looksLikeWakeUp(text)) return 'wakeup';
  if (ext === 'ics' || ext === 'ical' || head.startsWith('BEGIN:VCALENDAR')) return 'ics';
  if (ext === 'csv') return 'csv';
  if (ext === 'tsv') return 'tsv';
  if (ext === 'yaml' || ext === 'yml' || ext === 'cses') return 'cses';
  if (head.startsWith('{') || head.startsWith('[')) return 'json';
  if (/^version:\s*\d/m.test(head) && /schedules:/m.test(head)) return 'cses';
  const firstLine = head.split(/\r?\n/, 1)[0] ?? '';
  if (firstLine.includes('\t')) return 'tsv';
  if (firstLine.includes(',')) return 'csv';
  return null;
}

export function parseImport(format: ImportFormat, text: string, fileName = ''): ImportBundle {
  if (/WakeUp课程表/.test(text) && /分享口令/.test(text)) {
    throw new Error('WakeUp 分享口令需要 WakeUp 服务器解析。请在 WakeUp 中选择「导出为文件」，再导入 .wakeup_schedule 文件。');
  }
  const bundle =
    format === 'wakeup' ? parseWakeUp(text)
    : format === 'json' ? parseJson(text)
    : format === 'csv' ? parseDelimited(text, ',')
    : format === 'tsv' ? parseDelimited(text, '\t')
    : format === 'ics' ? parseIcs(text)
    : parseCses(text);
  if (!bundle.courses.length) throw new Error('文件中没有识别到课程。');
  if (fileName && !bundle.metadata.fileName) bundle.metadata.fileName = fileName;
  return bundle;
}

// ---------------------------------------------------------------------------
// Shared normalisation

const WEEKDAY_WORDS: Record<string, number> = {
  一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 7, 天: 7, 七: 7,
  mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6, sun: 7
};

export function parseWeekday(value: unknown): number | null {
  if (typeof value === 'number') return Number.isInteger(value) && value >= 1 && value <= 7 ? value : null;
  const text = String(value ?? '').trim().toLowerCase().replace(/^(星期|周|礼拜)/, '');
  if (!text) return null;
  const number = Number(text);
  if (Number.isInteger(number) && number >= 1 && number <= 7) return number;
  return WEEKDAY_WORDS[text.slice(0, 3)] ?? WEEKDAY_WORDS[text.slice(0, 1)] ?? null;
}

function toNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const number = typeof value === 'number' ? value : Number(String(value).trim());
  return Number.isFinite(number) ? number : null;
}

function text(value: unknown) {
  if (Array.isArray(value)) return value.map((item) => String(item ?? '').trim()).filter(Boolean).join('、');
  return String(value ?? '').trim();
}

function sectionRange(value: unknown): [number, number] | null {
  const match = String(value ?? '').match(/(\d{1,2})\s*(?:[-~—–至到]\s*(\d{1,2}))?/);
  if (!match) return null;
  const from = Number(match[1]);
  const to = Number(match[2] ?? match[1]);
  return from >= 1 ? [Math.min(from, to), Math.max(from, to)] : null;
}

function parity(value: unknown): 1 | 0 | -1 {
  const marker = String(value ?? '').trim().toLowerCase();
  if (marker === '1' || marker === 'odd' || marker.includes('单')) return 1;
  if (marker === '2' || marker === 'even' || marker.includes('双')) return 0;
  return -1;
}

function weeksFrom(raw: Record<string, unknown>, fallbackEnd = 20): number[] {
  const value = raw.weeks ?? raw.weekList ?? raw.week_list ?? raw.zcd ?? raw.weekText;
  let weeks: number[] = [];
  if (Array.isArray(value)) {
    weeks = value.map(Number).filter((week) => Number.isInteger(week) && week >= 1 && week <= 64);
  } else if (typeof value === 'string' && value.trim()) {
    weeks = parseWeeksText(value, fallbackEnd);
  }
  const startWeek = toNumber(raw.startWeek ?? raw.start_week);
  const endWeek = toNumber(raw.endWeek ?? raw.end_week);
  if (!weeks.length && startWeek && endWeek) {
    for (let week = Math.max(1, startWeek); week <= Math.min(64, endWeek); week += 1) weeks.push(week);
  }
  if (!weeks.length) weeks = Array.from({ length: fallbackEnd }, (_, index) => index + 1);
  const oddEven = parity(raw.oddEven ?? raw.weekType ?? raw.type);
  if (oddEven === 1) weeks = weeks.filter((week) => week % 2 === 1);
  if (oddEven === 0) weeks = weeks.filter((week) => week % 2 === 0);
  return [...new Set(weeks)].sort((a, b) => a - b);
}

function normalizeCourse(raw: Record<string, unknown>): ImportedCourse | null {
  const name = text(raw.name ?? raw.courseName ?? raw.course_name ?? raw.kcmc ?? raw.title ?? raw.course);
  if (!name) return null;
  const weekday = parseWeekday(raw.weekday ?? raw.day ?? raw.dayOfWeek ?? raw.day_of_week ?? raw.xqj);
  if (!weekday) return null;

  const sectionList = Array.isArray(raw.sections) ? raw.sections.map((item) => toNumber(typeof item === 'object' && item ? (item as Record<string, unknown>).section : item)).filter((item): item is number => item !== null) : [];
  const range = sectionRange(raw.section ?? raw.sectionText ?? raw.jc ?? raw.jcs);
  let startSection = toNumber(raw.startSection ?? raw.startNode ?? raw.start_section ?? raw.startUnit) ?? (sectionList.length ? Math.min(...sectionList) : range?.[0] ?? null);
  const step = toNumber(raw.step ?? raw.sectionCount);
  let endSection =
    toNumber(raw.endSection ?? raw.endNode ?? raw.end_section ?? raw.endUnit) ??
    (sectionList.length ? Math.max(...sectionList) : null) ??
    (startSection !== null && step ? startSection + Math.max(1, step) - 1 : range?.[1] ?? null);

  const startTime = normalizeClock(text(raw.startTime ?? raw.start_time ?? raw.start));
  const endTime = normalizeClock(text(raw.endTime ?? raw.end_time ?? raw.end));
  if (startSection === null && startTime) {
    const guess = sectionsFromTimes(parseClock(startTime)!, parseClock(endTime) ?? parseClock(startTime)! + 45);
    startSection = guess.startSection;
    endSection = endSection ?? guess.endSection;
  }
  startSection = Math.min(30, Math.max(1, Math.trunc(startSection ?? 1)));
  endSection = Math.min(30, Math.max(startSection, Math.trunc(endSection ?? startSection)));
  const credit = toNumber(raw.credit ?? raw.xf ?? raw.credits);

  return {
    name,
    teacher: text(raw.teacher ?? raw.teacherName ?? raw.teachers ?? raw.jsxm) || null,
    location: text(raw.location ?? raw.position ?? raw.room ?? raw.classroom ?? raw.place ?? raw.jxcdmc) || null,
    weekday,
    startSection,
    endSection,
    weeks: weeksFrom(raw),
    startTime: startTime || null,
    endTime: endTime || null,
    credit: credit !== null && credit >= 0 && credit <= 30 ? credit : null
  };
}

function stringMetadata(value: unknown) {
  const metadata: Record<string, string> = {};
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      if (item === null || item === undefined) continue;
      metadata[key] = typeof item === 'string' ? item : JSON.stringify(item);
    }
  }
  return metadata;
}

// ---------------------------------------------------------------------------
// JSON (LumaSchedule export, Shiguang/WakeUp-like arrays, generic objects)

function parseJson(payload: string): ImportBundle {
  let root: unknown;
  try {
    root = JSON.parse(payload);
  } catch {
    throw new Error('JSON 格式无效。');
  }
  const object = (!Array.isArray(root) && root && typeof root === 'object' ? root : {}) as Record<string, unknown>;
  const data = object.data && typeof object.data === 'object' && !Array.isArray(object.data) ? (object.data as Record<string, unknown>) : {};
  const candidates = Array.isArray(root)
    ? root
    : [object.courses, object.courseList, object.course_list, object.data, data.courses, data.courseList, object.items, object.list].find(Array.isArray);
  if (!Array.isArray(candidates)) throw new Error('JSON 中没有找到课程数组。');
  const courses = candidates
    .map((item) => (item && typeof item === 'object' ? normalizeCourse(item as Record<string, unknown>) : null))
    .filter((item): item is ImportedCourse => item !== null);
  const termStart = text(object.termStart ?? object.semesterStartDate ?? object.term_start ?? object.startDate);
  return {
    source: text(object.source) || 'JSON',
    termName: text(object.termName ?? object.term_name ?? object.semesterName ?? object.tableName) || null,
    termStart: parseIsoDate(termStart) ? toIsoDate(parseIsoDate(termStart)!) : null,
    courses,
    metadata: stringMetadata(object.metadata)
  };
}

// ---------------------------------------------------------------------------
// WakeUp `.wakeup_schedule`: five JSON documents, one per line
// (time table, time nodes, table settings, course bases, course details).

function jsonLines(payload: string): unknown[] {
  return payload
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line) as unknown;
      } catch {
        return undefined;
      }
    })
    .filter((value) => value !== undefined);
}

function looksLikeWakeUp(payload: string) {
  if (!/"startNode"/.test(payload) || !/"courseName"/.test(payload)) return false;
  return jsonLines(payload).length >= 4;
}

type Row = Record<string, unknown>;
const rows = (value: unknown): Row[] => (Array.isArray(value) ? value.filter((item): item is Row => !!item && typeof item === 'object') : []);

function parseWakeUp(payload: string): ImportBundle {
  const documents = jsonLines(payload);
  const arrays = documents.filter(Array.isArray).map(rows);
  const objects = documents.filter((item): item is Row => !!item && typeof item === 'object' && !Array.isArray(item));
  const nodes = arrays.find((list) => list.some((item) => 'node' in item && 'startTime' in item)) ?? [];
  const details = arrays.find((list) => list.some((item) => 'startNode' in item && 'day' in item)) ?? [];
  const bases = arrays.find((list) => list.some((item) => 'courseName' in item && !('startNode' in item))) ?? [];
  const table = objects.find((item) => 'maxWeek' in item || 'startDate' in item || 'tableName' in item) ?? {};
  if (!details.length) throw new Error('WakeUp 文件中没有课程时间信息。');

  const nodeTimes = new Map<number, { start: string; end: string }>();
  for (const node of nodes) {
    const number = toNumber(node.node);
    const start = normalizeClock(text(node.startTime));
    const end = normalizeClock(text(node.endTime));
    if (number && start && end) nodeTimes.set(number, { start, end });
  }
  const baseById = new Map(bases.map((base) => [`${base.id}`, base]));
  const maxWeek = Math.min(64, Math.max(1, toNumber(table.maxWeek) ?? 20));
  const courses: ImportedCourse[] = [];
  for (const detail of details) {
    const base = baseById.get(`${detail.id}`);
    const name = text(base?.courseName ?? detail.courseName);
    const weekday = parseWeekday(detail.day);
    const startSection = toNumber(detail.startNode);
    if (!name || !weekday || !startSection) continue;
    const endSection = startSection + Math.max(1, toNumber(detail.step) ?? 1) - 1;
    const startWeek = Math.max(1, toNumber(detail.startWeek) ?? 1);
    const endWeek = Math.min(64, toNumber(detail.endWeek) ?? maxWeek);
    const type = toNumber(detail.type) ?? 0;
    const weeks: number[] = [];
    for (let week = startWeek; week <= endWeek; week += 1) {
      if (type === 1 && week % 2 === 0) continue;
      if (type === 2 && week % 2 === 1) continue;
      weeks.push(week);
    }
    const ownTime = detail.ownTime === true;
    const credit = toNumber(base?.credit);
    courses.push({
      name,
      teacher: text(detail.teacher) || null,
      location: text(detail.room) || null,
      weekday,
      startSection,
      endSection,
      weeks,
      startTime: (ownTime ? normalizeClock(text(detail.startTime)) : nodeTimes.get(startSection)?.start) || null,
      endTime: (ownTime ? normalizeClock(text(detail.endTime)) : nodeTimes.get(endSection)?.end) || null,
      credit: credit !== null && credit > 0 && credit <= 30 ? credit : null
    });
  }

  const start = parseIsoDate(text(table.startDate));
  const metadata: Record<string, string> = {
    courseConfig: JSON.stringify({
      semesterTotalWeeks: maxWeek,
      firstDayOfWeek: table.sundayFirst === true ? 7 : 1,
      semesterStartDate: start ? toIsoDate(start) : ''
    })
  };
  if (nodeTimes.size) {
    metadata.timeScheme = JSON.stringify(
      [...nodeTimes.entries()].sort((a, b) => a[0] - b[0]).map(([number, time]) => ({ number, startTime: time.start, endTime: time.end }))
    );
  }
  return {
    source: 'WakeUp',
    termName: text(table.tableName) || null,
    termStart: start ? toIsoDate(start) : null,
    courses,
    metadata
  };
}

// ---------------------------------------------------------------------------
// CSV / TSV

const HEADER_ALIASES: Record<string, string[]> = {
  name: ['name', 'course', 'coursename', '课程', '课程名', '课程名称', '科目', '科目名称'],
  teacher: ['teacher', 'teachers', '教师', '老师', '任课教师', '授课教师'],
  room: ['room', 'location', 'classroom', 'place', 'position', '教室', '地点', '上课地点', '上课教室'],
  day: ['day', 'weekday', 'dayofweek', '星期', '周几', '上课星期', '星期几'],
  start: ['startsection', 'startnode', 'start', '开始节次', '开始节', '起始节', '起始节次', '节次', '上课节次'],
  end: ['endsection', 'endnode', 'end', '结束节次', '结束节'],
  weeks: ['weeks', 'week', '周次', '上课周次', '周数', '教学周'],
  startTime: ['starttime', '开始时间', '上课时间'],
  endTime: ['endtime', '结束时间', '下课时间'],
  credit: ['credit', 'credits', '学分']
};

function splitDelimited(payload: string, delimiter: string) {
  const result: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  for (let index = 0; index < payload.length; index += 1) {
    const char = payload[index];
    if (quoted) {
      if (char === '"' && payload[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
      continue;
    }
    if (char === '"' && field.trim() === '') {
      quoted = true;
      field = '';
    } else if (char === delimiter) {
      row.push(field);
      field = '';
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && payload[index + 1] === '\n') index += 1;
      row.push(field);
      field = '';
      if (row.some((value) => value.trim())) result.push(row);
      row = [];
    } else {
      field += char;
    }
  }
  row.push(field);
  if (row.some((value) => value.trim())) result.push(row);
  return result;
}

function parseDelimited(payload: string, delimiter: string): ImportBundle {
  const table = splitDelimited(payload, delimiter);
  if (table.length < 2) throw new Error('表格中没有课程数据。');
  const headers = table[0].map((value) => value.trim().toLowerCase().replace(/[\s_\-（）()]/g, ''));
  const column = (key: string) => headers.findIndex((header) => HEADER_ALIASES[key].includes(header));
  const index = Object.fromEntries(Object.keys(HEADER_ALIASES).map((key) => [key, column(key)])) as Record<string, number>;
  if (index.name < 0 || index.day < 0 || (index.start < 0 && index.startTime < 0)) {
    throw new Error('表格至少需要「课程名称」「星期」以及「开始节次」或「开始时间」列。');
  }
  const cell = (row: string[], key: string) => (index[key] >= 0 ? (row[index[key]] ?? '').trim() : '');
  const courses = table
    .slice(1)
    .map((row) => {
      const range = sectionRange(cell(row, 'start'));
      return normalizeCourse({
        name: cell(row, 'name'),
        teacher: cell(row, 'teacher'),
        room: cell(row, 'room'),
        day: cell(row, 'day'),
        startSection: range?.[0] ?? null,
        endSection: toNumber(cell(row, 'end')) ?? range?.[1] ?? null,
        weeks: cell(row, 'weeks'),
        startTime: cell(row, 'startTime'),
        endTime: cell(row, 'endTime'),
        credit: cell(row, 'credit')
      });
    })
    .filter((item): item is ImportedCourse => item !== null);
  return { source: delimiter === '\t' ? 'TSV' : 'CSV', courses, metadata: {} };
}

// ---------------------------------------------------------------------------
// iCalendar

interface IcsProperty {
  name: string;
  params: Record<string, string>;
  value: string;
}

function parseIcsProperty(line: string): IcsProperty | null {
  let quoted = false;
  let colon = -1;
  for (let index = 0; index < line.length; index += 1) {
    if (line[index] === '"') quoted = !quoted;
    if (line[index] === ':' && !quoted) {
      colon = index;
      break;
    }
  }
  if (colon <= 0) return null;
  const [name, ...paramParts] = line.slice(0, colon).split(';');
  const params: Record<string, string> = {};
  for (const part of paramParts) {
    const equals = part.indexOf('=');
    if (equals > 0) params[part.slice(0, equals).toUpperCase()] = part.slice(equals + 1).replace(/^"|"$/g, '');
  }
  return { name: name.toUpperCase(), params, value: line.slice(colon + 1) };
}

function unescapeIcs(value: string) {
  return value.replace(/\\n/gi, '\n').replace(/\\([,;\\])/g, '$1').trim();
}

function parseIcsDateTime(value: string, params: Record<string, string>): Date | null {
  if (params.VALUE === 'DATE') return null;
  const match = value.trim().match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})?(Z)?$/);
  if (!match) return null;
  const [, y, mo, d, h, mi, s, utc] = match;
  const parts = [Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s ?? 0)] as const;
  return utc ? new Date(Date.UTC(...parts)) : new Date(...parts);
}

function parseIcsDuration(value: string) {
  const match = value.match(/^P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/);
  if (!match) return 0;
  const [, w, d, h, m] = match.map((part) => Number(part ?? 0));
  return ((w * 7 + d) * 24 * 60 + h * 60 + m) * 60_000;
}

const BYDAY: Record<string, number> = { MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6, SU: 7 };

function expandRule(start: Date, rule: string): Date[] {
  const options = Object.fromEntries(
    rule.split(';').map((part) => {
      const [key, value = ''] = part.split('=');
      return [key.toUpperCase(), value.toUpperCase()];
    })
  );
  const interval = Math.max(1, Number(options.INTERVAL) || 1);
  const count = options.COUNT ? Math.min(500, Number(options.COUNT)) : Number.POSITIVE_INFINITY;
  const untilValue = options.UNTIL ? parseIcsDateTime(options.UNTIL.length === 8 ? `${options.UNTIL}T235959` : options.UNTIL, {}) : null;
  const until = untilValue ?? new Date(start.getTime() + 64 * 7 * 86_400_000);
  const result: Date[] = [];
  if (options.FREQ === 'WEEKLY') {
    const days = (options.BYDAY ? options.BYDAY.split(',').map((day) => BYDAY[day.slice(-2)]).filter(Boolean) : [weekdayOf(start)]).sort((a, b) => a - b);
    const weekBase = addDays(start, -(weekdayOf(start) - 1));
    for (let week = 0; week < 64 * interval && result.length < count; week += interval) {
      for (const day of days) {
        const candidate = addDays(weekBase, week * 7 + day - 1);
        candidate.setHours(start.getHours(), start.getMinutes(), 0, 0);
        if (candidate < start) continue;
        if (candidate > until || result.length >= count) break;
        result.push(candidate);
      }
      if (addDays(weekBase, week * 7) > until) break;
    }
  } else if (options.FREQ === 'DAILY') {
    for (let day = 0; result.length < count && day < 450; day += interval) {
      const candidate = addDays(start, day);
      candidate.setHours(start.getHours(), start.getMinutes(), 0, 0);
      if (candidate > until) break;
      result.push(candidate);
    }
  } else {
    result.push(start);
  }
  return result;
}

function parseIcs(payload: string): ImportBundle {
  const lines = payload.replace(/\r?\n[ \t]/g, '').split(/\r?\n/);
  type IcsEvent = Map<string, IcsProperty[]>;
  const events: IcsEvent[] = [];
  let current: IcsEvent | null = null;
  let calendarName = '';
  for (const line of lines) {
    if (/^BEGIN:VEVENT$/i.test(line.trim())) {
      current = new Map();
      continue;
    }
    if (/^END:VEVENT$/i.test(line.trim())) {
      if (current) events.push(current);
      current = null;
      continue;
    }
    const property = parseIcsProperty(line);
    if (!property) continue;
    if (!current) {
      if (property.name === 'X-WR-CALNAME') calendarName = unescapeIcs(property.value);
      continue;
    }
    current.set(property.name, [...(current.get(property.name) ?? []), property]);
  }

  interface Item { name: string; location: string; teacher: string; date: Date; start: number; end: number }
  const items: Item[] = [];
  for (const event of events) {
    const first = (name: string) => event.get(name)?.[0];
    if (first('STATUS')?.value.toUpperCase() === 'CANCELLED' || first('RECURRENCE-ID')) continue;
    const dtStart = first('DTSTART');
    const start = dtStart ? parseIcsDateTime(dtStart.value, dtStart.params) : null;
    if (!start) continue;
    const dtEnd = first('DTEND');
    const durationMs = dtEnd
      ? (parseIcsDateTime(dtEnd.value, dtEnd.params)?.getTime() ?? start.getTime()) - start.getTime()
      : parseIcsDuration(first('DURATION')?.value ?? '');
    const excluded = new Set(
      (event.get('EXDATE') ?? []).flatMap((property) =>
        property.value.split(',').map((value) => parseIcsDateTime(value, property.params)?.getTime()).filter((value): value is number => value !== undefined)
      )
    );
    const rule = first('RRULE')?.value;
    const occurrences = (rule ? expandRule(start, rule) : [start]).filter((date) => !excluded.has(date.getTime()));
    const description = unescapeIcs(first('DESCRIPTION')?.value ?? '');
    const teacher = description.match(/(?:教师|老师|任课教师|teacher)\s*[：:]\s*([^\n·;；,，]+)/i)?.[1]?.trim() ?? '';
    for (const date of occurrences) {
      const end = new Date(date.getTime() + Math.max(durationMs, 5 * 60_000));
      items.push({
        name: unescapeIcs(first('SUMMARY')?.value ?? '') || '未命名课程',
        location: unescapeIcs(first('LOCATION')?.value ?? ''),
        teacher,
        date,
        start: date.getHours() * 60 + date.getMinutes(),
        end: end.getHours() * 60 + end.getMinutes() || 24 * 60 - 1
      });
    }
  }
  if (!items.length) throw new Error('ICS 中没有可识别的上课时间。');

  const earliest = items.reduce((min, item) => (item.date < min ? item.date : min), items[0].date);
  const termStart = startOfWeek(earliest, 1);
  const groups = new Map<string, { item: Item; weeks: Set<number> }>();
  for (const item of items) {
    const week = Math.floor(daysBetween(termStart, atNoon(item.date)) / 7) + 1;
    if (week < 1 || week > 64) continue;
    const key = [item.name, item.location, item.teacher, weekdayOf(item.date), item.start, item.end].join('\u001f');
    const group = groups.get(key) ?? { item, weeks: new Set<number>() };
    group.weeks.add(week);
    groups.set(key, group);
  }
  const courses: ImportedCourse[] = [...groups.values()].map(({ item, weeks }) => ({
    name: item.name,
    teacher: item.teacher || null,
    location: item.location || null,
    weekday: weekdayOf(item.date),
    ...sectionsFromTimes(item.start, item.end),
    weeks: [...weeks].sort((a, b) => a - b),
    startTime: formatClock(item.start),
    endTime: formatClock(item.end)
  }));
  return {
    source: 'ICS',
    termName: calendarName || null,
    termStart: toIsoDate(termStart),
    courses,
    metadata: {}
  };
}

// ---------------------------------------------------------------------------
// CSES v1 (https://github.com/CSES-org/CSES)

type CsesSubject = { name: string; teacher?: string; room?: string };
type CsesClass = { subject: string; start_time: string; end_time: string };
type CsesSchedule = { enable_day: number; weeks: string; classes: CsesClass[] };

function stripYamlComment(raw: string) {
  let single = false;
  let double = false;
  for (let index = 0; index < raw.length; index += 1) {
    const char = raw[index];
    if (char === "'" && !double) single = !single;
    else if (char === '"' && !single && raw[index - 1] !== '\\') double = !double;
    else if (char === '#' && !single && !double && (index === 0 || /\s/.test(raw[index - 1]))) return raw.slice(0, index);
  }
  return raw;
}

function yamlScalar(raw: string) {
  let value = stripYamlComment(raw).trim();
  if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) {
    value = value.slice(1, -1);
  }
  return value.replace(/\\"/g, '"');
}

function yamlPair(line: string): [string, string] | null {
  const colon = line.indexOf(':');
  if (colon <= 0) return null;
  return [line.slice(0, colon).trim(), yamlScalar(line.slice(colon + 1))];
}

function parseCses(payload: string): ImportBundle {
  let version = 0;
  const subjects: CsesSubject[] = [];
  const schedules: CsesSchedule[] = [];
  let section: 'none' | 'subjects' | 'schedules' = 'none';
  let subject: CsesSubject | null = null;
  let schedule: CsesSchedule | null = null;
  let entry: CsesClass | null = null;
  let inClasses = false;

  const flushSubject = () => {
    if (subject?.name) subjects.push(subject);
    subject = null;
  };
  const flushClass = () => {
    if (entry?.subject && schedule) schedule.classes.push(entry);
    entry = null;
  };
  const flushSchedule = () => {
    flushClass();
    if (schedule) schedules.push(schedule);
    schedule = null;
  };

  for (const rawLine of payload.split(/\r?\n/)) {
    if (!rawLine.trim() || rawLine.trimStart().startsWith('#')) continue;
    const indent = rawLine.length - rawLine.trimStart().length;
    const line = rawLine.trim();
    if (indent === 0) {
      if (line.startsWith('version:')) version = Number(yamlScalar(line.slice(8)));
      else if (line === 'subjects:') { flushSubject(); flushSchedule(); section = 'subjects'; inClasses = false; }
      else if (line === 'schedules:') { flushSubject(); flushSchedule(); section = 'schedules'; inClasses = false; }
      continue;
    }
    if (section === 'subjects') {
      const body = line.startsWith('- ') ? line.slice(2) : line;
      if (line.startsWith('- ')) {
        flushSubject();
        subject = { name: '' };
      }
      const pair = yamlPair(body);
      if (subject && pair) {
        const [key, value] = pair;
        if (key === 'name' || key === 'teacher' || key === 'room') subject[key] = value;
      }
      continue;
    }
    if (section !== 'schedules') continue;
    if (indent <= 2 && line.startsWith('- ')) {
      flushSchedule();
      schedule = { enable_day: 1, weeks: 'all', classes: [] };
      inClasses = false;
      const pair = yamlPair(line.slice(2));
      if (pair?.[0] === 'enable_day') schedule.enable_day = parseWeekday(pair[1]) ?? 1;
      if (pair?.[0] === 'weeks') schedule.weeks = pair[1];
      continue;
    }
    if (!schedule) continue;
    if (line === 'classes:') {
      flushClass();
      inClasses = true;
      continue;
    }
    if (!inClasses) {
      const pair = yamlPair(line);
      if (pair?.[0] === 'enable_day') schedule.enable_day = parseWeekday(pair[1]) ?? 1;
      if (pair?.[0] === 'weeks') schedule.weeks = pair[1];
      continue;
    }
    const body = line.startsWith('- ') ? line.slice(2) : line;
    if (line.startsWith('- ')) {
      flushClass();
      entry = { subject: '', start_time: '', end_time: '' };
    }
    const pair = yamlPair(body);
    if (entry && pair) {
      const [key, value] = pair;
      if (key === 'subject' || key === 'start_time' || key === 'end_time') entry[key] = value;
    }
  }
  flushSubject();
  flushSchedule();

  if (version !== 1) throw new Error(`仅支持 CSES v1，当前文件版本为 ${version || '未知'}。`);
  const subjectMap = new Map(subjects.map((item) => [item.name, item]));
  const courses: ImportedCourse[] = [];
  for (const item of schedules) {
    const marker = item.weeks.trim().toLowerCase();
    const weeks = parseWeeksText(marker === 'odd' ? '单' : marker === 'even' ? '双' : '全', 20);
    for (const lesson of item.classes) {
      const meta = subjectMap.get(lesson.subject);
      const start = parseClock(lesson.start_time);
      const end = parseClock(lesson.end_time);
      if (start === null) continue;
      courses.push({
        name: lesson.subject,
        teacher: meta?.teacher?.trim() || null,
        location: meta?.room?.trim() || null,
        weekday: item.enable_day,
        ...sectionsFromTimes(start, end ?? start + 45),
        weeks,
        startTime: formatClock(start),
        endTime: end === null ? null : formatClock(end)
      });
    }
  }
  return { source: 'CSES v1', courses, metadata: {} };
}

/** "08:00–09:40" when the file carries clock times, otherwise the section range. */
export function previewTime(course: ImportedCourse) {
  if (course.startTime && course.endTime) return `${course.startTime}–${course.endTime}`;
  return course.startSection === course.endSection ? `第 ${course.startSection} 节` : `第 ${course.startSection}–${course.endSection} 节`;
}
