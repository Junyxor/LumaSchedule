import type { Course, GradeRecord, ImportBundle } from './types';

const STORAGE_KEY = 'luma.courseCredits.v1';

type CreditState = {
  byId: Record<string, number>;
  byName: Record<string, number>;
};

function normalizeName(value: string) {
  return value.trim().toLocaleLowerCase('zh-CN').replace(/\s+/g, ' ');
}

function validCredit(value: unknown): number | null {
  const number = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(number) && number >= 0 && number <= 30 ? number : null;
}

function loadState(): CreditState {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as Partial<CreditState>;
    return {
      byId: parsed.byId && typeof parsed.byId === 'object' ? parsed.byId : {},
      byName: parsed.byName && typeof parsed.byName === 'object' ? parsed.byName : {}
    };
  } catch {
    return { byId: {}, byName: {} };
  }
}

function saveState(state: CreditState) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

export function rememberCourseCredit(id: string, name: string, credit: number | null | undefined) {
  const state = loadState();
  const normalized = normalizeName(name);
  const value = validCredit(credit);

  if (value === null) {
    delete state.byId[id];
    if (normalized) delete state.byName[normalized];
  } else {
    state.byId[id] = value;
    if (normalized) state.byName[normalized] = value;
  }
  saveState(state);
}

export function forgetCourseCredit(id: string, name = '') {
  const state = loadState();
  delete state.byId[id];
  const normalized = normalizeName(name);
  if (normalized) delete state.byName[normalized];
  saveState(state);
}

export function rememberImportCredits(bundle: ImportBundle) {
  const state = loadState();
  let changed = false;
  for (const course of bundle.courses) {
    const value = validCredit(course.credit);
    const normalized = normalizeName(course.name);
    if (value === null || !normalized) continue;
    state.byName[normalized] = value;
    changed = true;
  }
  if (changed) saveState(state);
}

export function hydrateCourseCredits(courses: Course[]): Course[] {
  const state = loadState();
  return courses.map((course) => {
    const nativeCredit = validCredit(course.credit);
    const stored = validCredit(state.byId[course.id]) ?? validCredit(state.byName[normalizeName(course.name)]);
    return stored === null && nativeCredit === null ? course : { ...course, credit: nativeCredit ?? stored };
  });
}

export function hydrateGradeCredits(records: GradeRecord[]): GradeRecord[] {
  const state = loadState();
  return records.map((record) => {
    if (validCredit(record.credit) !== null) return record;
    const stored = validCredit(state.byName[normalizeName(record.courseName)]);
    return stored === null ? record : { ...record, credit: stored };
  });
}
