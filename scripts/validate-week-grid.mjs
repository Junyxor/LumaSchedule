import fs from 'node:fs';

const core = fs.readFileSync('src/lib/pages/WeekTimelineCore.svelte', 'utf8');
const week = fs.readFileSync('src/lib/pages/Week.svelte', 'utf8');
const runtime = fs.readFileSync('src/styles/mobile-runtime.css', 'utf8');
const readable = fs.readFileSync('src/styles/week-readable-layout.css', 'utf8');
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
  'minute >= timelineStart && minute < timelineEnd',
  '{#each timelineSections as section}'
];

for (const marker of stableRangeMarkers) {
  if (!core.includes(marker)) {
    console.error(`Week stable-day regression: missing ${marker}`);
    process.exit(1);
  }
}

if (core.includes('MIN_TIMELINE_SPAN') || core.includes('paddedTimelineRange(')) {
  console.error('Week stable-day regression: selected-week-only range trimming must not collapse a school day into a morning-only view.');
  process.exit(1);
}

if (core.includes('visibleStartMinutes = visibleCourses') || core.includes('visibleEndMinutes = visibleCourses')) {
  console.error('Week stable-day regression: active-week courses must not define the global time axis.');
  process.exit(1);
}

if (core.includes('left:calc(') || core.includes('left: calc(')) {
  console.error('Week timeline regression: course columns must not use calculated absolute left positioning.');
  process.exit(1);
}

if (!week.includes("import WeekTimelineCore from './WeekTimelineCore.svelte';") || !week.includes('timelineCourses={allCourses}')) {
  console.error('Week timeline regression: full-term courses must feed the time-scale inference.');
  process.exit(1);
}

if (!week.includes('class="week-add-button"') || !week.includes('{addRequest}')) {
  console.error('Week toolbar regression: add action must live in the top toolbar.');
  process.exit(1);
}

if (core.includes('floating-week-add')) {
  console.error('Week toolbar regression: floating add button must not cover weekday headers.');
  process.exit(1);
}

if (runtime.includes('.week-scroll::after')) {
  console.error('Week scroll regression: obsolete spacer creates a blank tail after the timetable.');
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
  console.error('Mobile navigation regression: tab bar must stay compact instead of becoming a full-width bottom sheet.');
  process.exit(1);
}

if (!runtime.includes('.page-week .week-time-grid') || !runtime.includes('grid-auto-rows: 0;')) {
  console.error('Week scroll regression: implicit grid rows must not add a blank tail below the timetable.');
  process.exit(1);
}

const readableMarkers = [
  '.page-week.week-shell',
  'overflow: visible !important',
  '.page-week .week-board .week-header',
  'position: sticky !important',
  '.page-week .week-time-grid',
  'repeat(var(--time-row-count), var(--time-step-height))',
  'padding: 0 !important',
  '.page-week .time-guide.major',
  '.page-week .time-label',
  '.page-week .week-time-grid .week-course',
  'align-self: stretch !important',
  'justify-self: stretch !important',
  'width: auto !important',
  'word-break: normal !important',
  '--axis-width: 42px !important'
];

for (const marker of readableMarkers) {
  if (!readable.includes(marker)) {
    console.error(`Week readability regression: missing ${marker}`);
    process.exit(1);
  }
}

const mobileVisualGuards = [
  '.page-week .week-time-grid:has(.week-empty)',
  'min-height: clamp(300px, 46dvh, 410px) !important',
  'margin-inline: 0 !important',
  '.page-week .week-toolbar .week-selector',
  '.page-week .week-toolbar .week-title b'
];

for (const marker of mobileVisualGuards) {
  if (!readable.includes(marker)) {
    console.error(`Week mobile visual regression: missing ${marker}`);
    process.exit(1);
  }
}

if (/\.page-week \.week-board-swipe\s*\{[^}]*margin-inline:\s*-\d/s.test(readable)) {
  console.error('Week mobile visual regression: timetable must align with the page instead of bleeding to the screen edge.');
  process.exit(1);
}

if (!appCss.includes("@import './styles/week-readable-layout.css';")) {
  console.error('Week readability regression: final mobile timetable overrides are not loaded.');
  process.exit(1);
}

console.log('Week full-day time-proportional layout contract OK');
