import fs from 'node:fs';

const core = fs.readFileSync('src/lib/pages/WeekTimelineCore.svelte', 'utf8');
const week = fs.readFileSync('src/lib/pages/Week.svelte', 'utf8');
const runtime = fs.readFileSync('src/styles/mobile-runtime.css', 'utf8');
const readable = fs.readFileSync('src/styles/week-readable-layout.css', 'utf8');
const importCenter = fs.readFileSync('src/lib/pages/ImportCenter.svelte', 'utf8');
const appCss = fs.readFileSync('src/app.css', 'utf8');

const requiredCore = [
  'const TIME_STEP_MINUTES = 5',
  'const DEFAULT_DAY_START = 8 * 60',
  'const DEFAULT_DAY_END = 22 * 60',
  'export let timelineCourses: Course[] = []',
  'class="week-time-grid"',
  'class="time-guide major"',
  'class="time-label"',
  'courseGridPlacement(course)',
  'parseClock(course.start)',
  'parseClock(course.end)',
  'grid-template-rows: repeat(var(--time-row-count), var(--time-step-height))',
  'align-self: stretch',
  'justify-self: stretch',
  'class="course-credit"',
  'export let addRequest = 0'
];

for (const marker of requiredCore) {
  if (!core.includes(marker)) {
    console.error(`Week timeline regression: missing ${marker}`);
    process.exit(1);
  }
}

const stableRangeMarkers = [
  'timeReferenceCourses = timelineCourses.length ? timelineCourses : courses',
  'referenceStartMinutes = timeReferenceCourses.map((course) => courseStartMinute(course))',
  'referenceEndMinutes = timeReferenceCourses.map((course) => courseEndMinute(course))',
  'Math.min(DEFAULT_DAY_START, earliestReference)',
  'Math.max(DEFAULT_DAY_END, latestReference)',
  'minute >= timelineStart && minute < timelineEnd'
];

for (const marker of stableRangeMarkers) {
  if (!core.includes(marker)) {
    console.error(`Week stable-day regression: missing ${marker}`);
    process.exit(1);
  }
}

if (core.includes('MIN_TIMELINE_SPAN') || core.includes('paddedTimelineRange(')) {
  console.error('Week stable-day regression: selected-week-only trimming must not collapse the day.');
  process.exit(1);
}
if (core.includes('visibleStartMinutes = visibleCourses') || core.includes('visibleEndMinutes = visibleCourses')) {
  console.error('Week stable-day regression: active-week courses must not define the global time axis.');
  process.exit(1);
}
if (core.includes('left:calc(') || core.includes('left: calc(')) {
  console.error('Week timeline regression: course columns must stay in CSS grid.');
  process.exit(1);
}

if (!week.includes("import WeekTimelineCore from './WeekTimelineCore.svelte';") || !week.includes('timelineCourses={allCourses}')) {
  console.error('Week timeline regression: full-term courses must feed time inference.');
  process.exit(1);
}
if (!week.includes('class="week-add-button"') || !week.includes('{addRequest}')) {
  console.error('Week toolbar regression: add action must remain in the toolbar.');
  process.exit(1);
}
if (core.includes('floating-week-add')) {
  console.error('Week toolbar regression: floating add button must not cover headers.');
  process.exit(1);
}

const viewportMarkers = [
  '.page-week.week-shell',
  'height: 100dvh !important',
  'overflow: hidden !important',
  '.page-week .week-board-swipe',
  'flex: 1 1 auto !important',
  '.page-week .page-week-core',
  '.page-week .week-board .week-scroll',
  'overflow-y: auto !important',
  '.page-week .week-time-grid',
  'repeat(var(--time-row-count), var(--time-step-height))',
  'grid-auto-rows: 0 !important',
  '.page-week .time-label',
  'transform: translateY(5px) !important',
  '.page-week .week-time-grid .week-course',
  'align-self: stretch !important',
  'justify-self: stretch !important',
  '.page-week .week-time-grid .week-course span,',
  '--axis-width: 44px !important'
];
for (const marker of viewportMarkers) {
  if (!readable.includes(marker)) {
    console.error(`Week mobile viewport regression: missing ${marker}`);
    process.exit(1);
  }
}

const alignmentMarkers = [
  'grid-template-columns: var(--axis-width) repeat(var(--day-count), minmax(0, 1fr)) !important',
  'width: 100% !important',
  'padding-left: 0 !important',
  'padding-right: 0 !important',
  'justify-self: stretch !important',
  'text-align: center !important'
];
for (const marker of alignmentMarkers) {
  if (!readable.includes(marker)) {
    console.error(`Week axis alignment regression: missing ${marker}`);
    process.exit(1);
  }
}

const weekScrollBlock = readable.match(/\.page-week \.week-board \.week-scroll\s*\{([^}]*)\}/s)?.[1] ?? '';
const leftPaddingValues = [...weekScrollBlock.matchAll(/padding-left:\s*([^;]+);/g)].map((match) => match[1].trim());
if (leftPaddingValues.some((value) => !/^0(?:\s*!important)?$/.test(value))) {
  console.error('Week axis alignment regression: scrolling body must not carry a left gutter separate from the header.');
  process.exit(1);
}
if (/\.page-week \.week-board-swipe\s*\{[^}]*margin-inline:\s*-\d/s.test(readable)) {
  console.error('Week mobile regression: timetable must not bleed beyond page edges.');
  process.exit(1);
}
if (readable.includes('position: sticky !important')) {
  console.error('Week mobile regression: weekday header must stay inside the bounded board, not stick to the page.');
  process.exit(1);
}
if (runtime.includes('.week-scroll::after')) {
  console.error('Week scroll regression: obsolete spacer creates a blank tail.');
  process.exit(1);
}

const mobileNavMarkers = [
  'left: 10px;',
  'right: 10px;',
  'bottom: calc(7px + env(safe-area-inset-bottom));',
  'width: auto;',
  'height: 66px;',
  'border-radius: 28px;'
];
for (const marker of mobileNavMarkers) {
  if (!runtime.includes(marker)) {
    console.error(`Mobile navigation regression: missing ${marker}`);
    process.exit(1);
  }
}
if (/\.mobile-nav\s*\{[^}]*width:\s*100%/s.test(runtime)) {
  console.error('Mobile navigation regression: tab bar must stay floating and compact.');
  process.exit(1);
}

const importSelectionMarkers = [
  'let selectedCourseIndexes: number[] = []',
  'function selectedBundle()',
  'function toggleCourse(index: number)',
  'function toggleAllCourses()',
  'selectedCourseIndexes = bundle.courses.map((_, index) => index)',
  'await previewImport(bundle)',
  'preview.courses as course, index',
  '已选 {selectedCount} / {preview.courses.length}',
  'class="preview-course selectable-course"',
  'disabled={loading||diffLoading||!selectedCount}'
];
for (const marker of importSelectionMarkers) {
  if (!importCenter.includes(marker)) {
    console.error(`Import selection regression: missing ${marker}`);
    process.exit(1);
  }
}
if (importCenter.includes('preview.courses.slice(0,6)') || importCenter.includes('还有 {preview.courses.length-6} 条记录')) {
  console.error('Import selection regression: preview must expose all records, not hide records after six rows.');
  process.exit(1);
}

if (!appCss.includes("@import './styles/week-readable-layout.css';")) {
  console.error('Week readability regression: final mobile timetable overrides are not loaded.');
  process.exit(1);
}

console.log('Week viewport, axis alignment, and selectable import contracts OK');