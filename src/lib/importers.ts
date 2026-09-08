import type { ImportBundle, ImportedCourse } from './types';

function parseWeekday(value: unknown): number {
  const text = String(value ?? '').trim().replace(/星期|周/g, '');
  const chinese: Record<string, number> = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 7, 天: 7 };
  const number = Number(text);
  if (Number.isInteger(number) && number >= 1 && number <= 7) return number;
  return chinese[text] ?? 1;
}

function parseWeeks(value: unknown, startWeek?: number, endWeek?: number, oddEven?: unknown, defaultEnd = 20): number[] {
  let weeks: number[] = [];
  if (Array.isArray(value)) {
    weeks = value.map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 64);
  } else if (typeof value === 'string') {
    const marker = value.trim().toLowerCase();
    if (marker === 'odd' || marker === '单' || marker === '单周') {
      weeks = Array.from({ length: Math.ceil(defaultEnd / 2) }, (_, index) => index * 2 + 1).filter((n) => n <= defaultEnd);
    } else if (marker === 'even' || marker === '双' || marker === '双周') {
      weeks = Array.from({ length: Math.floor(defaultEnd / 2) }, (_, index) => (index + 1) * 2).filter((n) => n <= defaultEnd);
    } else if (marker === 'all' || marker === '全部' || marker === '每周') {
      weeks = Array.from({ length: defaultEnd }, (_, index) => index + 1);
    } else {
      for (const token of value.replace(/，/g, ',').split(/[\s,]+/).filter(Boolean)) {
        const range = token.match(/^(\d{1,2})\s*[-~至]\s*(\d{1,2})$/);
        if (range) {
          const from = Math.max(1, Math.min(64, Number(range[1])));
          const to = Math.max(1, Math.min(64, Number(range[2])));
          for (let n = Math.min(from, to); n <= Math.max(from, to); n++) weeks.push(n);
        } else {
          const n = Number(token);
          if (Number.isInteger(n) && n >= 1 && n <= 64) weeks.push(n);
        }
      }
    }
  }
  if (!weeks.length && startWeek && endWeek) {
    for (let n = Math.max(1, startWeek); n <= Math.min(64, endWeek); n++) weeks.push(n);
  }
  if (!weeks.length) weeks = Array.from({ length: defaultEnd }, (_, i) => i + 1);
  const marker = String(oddEven ?? '').toLowerCase();
  if (marker.includes('单') || marker === 'odd' || marker === '1') weeks = weeks.filter((n) => n % 2 === 1);
  if (marker.includes('双') || marker === 'even' || marker === '2') weeks = weeks.filter((n) => n % 2 === 0);
  return [...new Set(weeks)].sort((a, b) => a - b);
}

function normalizeCourse(raw: Record<string, unknown>): ImportedCourse | null {
  const name = String(raw.name ?? raw.courseName ?? raw.kcmc ?? raw.title ?? '').trim();
  if (!name) return null;
  const weekday = parseWeekday(raw.weekday ?? raw.day ?? raw.week ?? raw.xqj ?? 1);
  const startSection = Number(raw.startSection ?? raw.startNode ?? raw.start_section ?? raw.startUnit ?? 1);
  const step = Number(raw.step ?? raw.sectionCount ?? 1);
  const endSection = Number(raw.endSection ?? raw.endNode ?? raw.end_section ?? (startSection + Math.max(1, step) - 1));
  return {
    name,
    teacher: String(raw.teacher ?? raw.teacherName ?? raw.jsxm ?? '').trim() || null,
    location: String(raw.location ?? raw.position ?? raw.room ?? raw.classroom ?? raw.jxcdmc ?? '').trim() || null,
    weekday,
    startSection: Math.max(1, startSection || 1),
    endSection: Math.max(startSection || 1, endSection || startSection || 1),
    weeks: parseWeeks(
      raw.weeks ?? raw.weekList,
      Number(raw.startWeek ?? raw.start_week),
      Number(raw.endWeek ?? raw.end_week),
      raw.type ?? raw.weekType ?? raw.oddEven
    ),
    startTime: String(raw.startTime ?? raw.start_time ?? '').trim() || null,
    endTime: String(raw.endTime ?? raw.end_time ?? '').trim() || null
  };
}

function parseJsonImport(payload: string): ImportBundle {
  const root = JSON.parse(payload) as unknown;
  const object = (!Array.isArray(root) && root && typeof root === 'object' ? root : {}) as Record<string, unknown>;
  const candidates = Array.isArray(root) ? root : (object.courses ?? object.courseList ?? object.data ?? object.items ?? []);
  if (!Array.isArray(candidates)) throw new Error('JSON 中没有可识别的课程数组。');
  const courses = candidates
    .map((item) => normalizeCourse((item ?? {}) as Record<string, unknown>))
    .filter((item): item is ImportedCourse => !!item);
  if (!courses.length) throw new Error('JSON 中没有识别到有效课程。');
  const metadata: Record<string, string> = {};
  if (object.metadata && typeof object.metadata === 'object') {
    Object.entries(object.metadata as Record<string, unknown>).forEach(([key, value]) => {
      metadata[key] = typeof value === 'string' ? value : JSON.stringify(value);
    });
  }
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
  let row: string[] = [];
  let field = '';
  let quoted = false;
  for (let index = 0; index < payload.length; index++) {
    const char = payload[index];
    if (char === '"') {
      if (quoted && payload[index + 1] === '"') {
        field += '"';
        index++;
      } else quoted = !quoted;
    } else if (char === delimiter && !quoted) {
      row.push(field);
      field = '';
    } else if ((char === '\n' || char === '\r') && !quoted) {
      if (char === '\r' && payload[index + 1] === '\n') index++;
      row.push(field);
      field = '';
      if (row.some((value) => value.trim())) rows.push(row);
      row = [];
    } else field += char;
  }
  row.push(field);
  if (row.some((value) => value.trim())) rows.push(row);
  if (rows.length < 2) throw new Error('表格中没有课程数据。');

  const headers = rows[0].map((value) => value.trim().toLowerCase());
  const indexOf = (...names: string[]) => headers.findIndex((header) => names.some((name) => header === name.toLowerCase()));
  const indexes = {
    name: indexOf('name', 'course', '课程', '课程名称'),
    teacher: indexOf('teacher', '教师', '老师'),
    room: indexOf('room', 'location', '教室', '地点'),
    day: indexOf('day', 'weekday', '星期', '周几'),
    start: indexOf('startsection', 'startnode', '开始节次', '开始节'),
    end: indexOf('endsection', '结束节次', '结束节'),
    weeks: indexOf('weeks', '周次'),
    startTime: indexOf('starttime', '开始时间'),
    endTime: indexOf('endtime', '结束时间')
  };
  if (indexes.name < 0 || indexes.day < 0 || indexes.start < 0) {
    throw new Error('表格至少需要课程名称、星期和开始节次列。');
  }
  const value = (r: string[], i: number) => i >= 0 ? (r[i] ?? '').trim() : '';
  const courses = rows.slice(1)
    .map((r) => normalizeCourse({
      name: value(r, indexes.name),
      teacher: value(r, indexes.teacher),
      room: value(r, indexes.room),
      day: value(r, indexes.day),
      startSection: value(r, indexes.start),
      endSection: value(r, indexes.end) || value(r, indexes.start),
      weeks: value(r, indexes.weeks),
      startTime: value(r, indexes.startTime),
      endTime: value(r, indexes.endTime)
    }))
    .filter((item): item is ImportedCourse => !!item);
  if (!courses.length) throw new Error('表格中没有识别到有效课程。');
  return { source: delimiter === '\t' ? 'TSV' : 'CSV', courses, metadata: {} };
}

function unfoldIcs(payload: string) {
  return payload.replace(/\r?\n[ \t]/g, '');
}

function icsValue(block: string, key: string) {
  const line = block.split(/\r?\n/).find((item) => {
    const upper = item.toUpperCase();
    return upper.startsWith(`${key}:`) || upper.startsWith(`${key};`);
  });
  return line?.slice(line.indexOf(':') + 1).trim() ?? '';
}

function parseIcsDate(raw: string) {
  const compact = raw.replace(/[^0-9T]/g, '');
  const match = compact.match(/^(\d{4})(\d{2})(\d{2})T?(\d{2})?(\d{2})?/);
  if (!match) return null;
  return new Date(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
    Number(match[4] ?? 0),
    Number(match[5] ?? 0)
  );
}

function parseIcs(payload: string): ImportBundle {
  const text = unfoldIcs(payload);
  const blocks = text.split('BEGIN:VEVENT').slice(1).map((part) => part.split('END:VEVENT')[0]);
  const starts = blocks.map((block) => parseIcsDate(icsValue(block, 'DTSTART'))).filter((date): date is Date => !!date);
  if (!starts.length) throw new Error('ICS 中没有可识别的 VEVENT。');
  const earliest = new Date(Math.min(...starts.map((date) => date.getTime())));
  const monday = new Date(earliest);
  monday.setDate(earliest.getDate() - ((earliest.getDay() + 6) % 7));
  const termStart = `${monday.getFullYear()}-${String(monday.getMonth() + 1).padStart(2, '0')}-${String(monday.getDate()).padStart(2, '0')}`;
  const courses: ImportedCourse[] = [];
  blocks.forEach((block) => {
    const start = parseIcsDate(icsValue(block, 'DTSTART'));
    const end = parseIcsDate(icsValue(block, 'DTEND'));
    if (!start) return;
    const summary = icsValue(block, 'SUMMARY') || '课程';
    const rrule = icsValue(block, 'RRULE');
    const count = Number(rrule.match(/COUNT=(\d+)/i)?.[1] ?? 20);
    courses.push({
      name: summary.replace(/\\,/g, ','),
      teacher: null,
      location: icsValue(block, 'LOCATION').replace(/\\,/g, ',') || null,
      weekday: ((start.getDay() + 6) % 7) + 1,
      startSection: 1,
      endSection: 1,
      weeks: Array.from({ length: Math.max(1, Math.min(64, count)) }, (_, index) => index + 1),
      startTime: `${String(start.getHours()).padStart(2, '0')}:${String(start.getMinutes()).padStart(2, '0')}`,
      endTime: end ? `${String(end.getHours()).padStart(2, '0')}:${String(end.getMinutes()).padStart(2, '0')}` : null
    });
  });
  return { source: 'ICS', termStart, courses, metadata: {} };
}

type CsesSubject = { name: string; teacher?: string; room?: string };
type CsesClass = { subject: string; start_time: string; end_time: string };
type CsesSchedule = { enable_day: number; weeks: string; classes: CsesClass[] };

function stripYamlComment(raw: string) {
  let single = false;
  let double = false;
  let escaped = false;
  for (let index = 0; index < raw.length; index++) {
    const char = raw[index];
    if (escaped) { escaped = false; continue; }
    if (char === '\\' && double) { escaped = true; continue; }
    if (char === "'" && !double) single = !single;
    else if (char === '"' && !single) double = !double;
    else if (char === '#' && !single && !double && (index === 0 || /\s/.test(raw[index - 1]))) return raw.slice(0, index);
  }
  return raw;
}

function yamlScalar(raw: string) {
  let value = stripYamlComment(raw).trim();
  if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) {
    value = value.slice(1, -1);
  }
  return value.replace(/\\n/g, '\n').replace(/\\"/g, '"');
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
  let clazz: CsesClass | null = null;
  let inClasses = false;

  const flushSubject = () => {
    if (subject?.name) subjects.push(subject);
    subject = null;
  };
  const flushClass = () => {
    if (clazz?.subject) schedule?.classes.push(clazz);
    clazz = null;
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
      if (line.startsWith('version:')) version = Number(yamlScalar(line.slice(line.indexOf(':') + 1)));
      else if (line === 'subjects:') { flushSubject(); flushSchedule(); section = 'subjects'; inClasses = false; }
      else if (line === 'schedules:') { flushSubject(); flushSchedule(); section = 'schedules'; inClasses = false; }
      continue;
    }

    if (section === 'subjects') {
      if (line.startsWith('- ')) {
        flushSubject();
        subject = { name: '' };
        const pair = yamlPair(line.slice(2));
        if (pair) subject[pair[0] as keyof CsesSubject] = pair[1];
      } else if (subject) {
        const pair = yamlPair(line);
        if (pair && ['name', 'teacher', 'room'].includes(pair[0])) subject[pair[0] as keyof CsesSubject] = pair[1];
      }
      continue;
    }

    if (section === 'schedules') {
      if (indent <= 2 && line.startsWith('- ')) {
        flushSchedule();
        schedule = { enable_day: 1, weeks: 'all', classes: [] };
        inClasses = false;
        const pair = yamlPair(line.slice(2));
        if (pair) {
          if (pair[0] === 'enable_day') schedule.enable_day = parseWeekday(pair[1]);
          if (pair[0] === 'weeks') schedule.weeks = pair[1];
        }
        continue;
      }
      if (!schedule) continue;
      if (line === 'classes:') { flushClass(); inClasses = true; continue; }
      if (!inClasses) {
        const pair = yamlPair(line);
        if (pair?.[0] === 'enable_day') schedule.enable_day = parseWeekday(pair[1]);
        if (pair?.[0] === 'weeks') schedule.weeks = pair[1];
        continue;
      }
      if (line.startsWith('- ')) {
        flushClass();
        clazz = { subject: '', start_time: '', end_time: '' };
        const pair = yamlPair(line.slice(2));
        if (pair && ['subject', 'start_time', 'end_time'].includes(pair[0])) clazz[pair[0] as keyof CsesClass] = pair[1];
      } else if (clazz) {
        const pair = yamlPair(line);
        if (pair && ['subject', 'start_time', 'end_time'].includes(pair[0])) clazz[pair[0] as keyof CsesClass] = pair[1];
      }
    }
  }
  flushSubject();
  flushSchedule();

  if (version !== 1) throw new Error(`仅支持 CSES v1，当前文件版本为 ${version || '未知'}。`);
  const subjectMap = new Map(subjects.map((item) => [item.name, item]));
  const courses: ImportedCourse[] = [];
  for (const item of schedules) {
    const weeks = parseWeeks(item.weeks, undefined, undefined, undefined, 24);
    item.classes.forEach((entry, index) => {
      const meta = subjectMap.get(entry.subject);
      courses.push({
        name: entry.subject,
        teacher: meta?.teacher?.trim() || null,
        location: meta?.room?.trim() || null,
        weekday: item.enable_day,
        startSection: index + 1,
        endSection: index + 1,
        weeks,
        startTime: entry.start_time.trim() || null,
        endTime: entry.end_time.trim() || null
      });
    });
  }
  if (!courses.length) throw new Error('CSES 文件中没有识别到课程。');
  return { source: 'CSES v1', courses, metadata: {} };
}

export function importText(format: string, payload: string): ImportBundle {
  if (format === 'json') return parseJsonImport(payload);
  if (format === 'csv') return parseDelimited(payload, ',');
  if (format === 'tsv') return parseDelimited(payload, '\t');
  if (format === 'ics') return parseIcs(payload);
  if (format === 'cses') return parseCses(payload);
  throw new Error(`暂不支持的导入格式：${format}`);
}
