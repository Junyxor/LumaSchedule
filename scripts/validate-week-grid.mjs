import fs from 'node:fs';

const core = fs.readFileSync('src/lib/pages/WeekTimelineCore.svelte', 'utf8');
const week = fs.readFileSync('src/lib/pages/Week.svelte', 'utf8');
const runtime = fs.readFileSync('src/styles/mobile-runtime.css', 'utf8');
const readable = fs.readFileSync('src/styles/week-readable-layout.css', 'utf8');
const appCss = fs.readFileSync('src/app.css', 'utf8');

const requiredCore = [
  'const TIME_STEP_MINUTES = 5',
  'export let timelineCourses: Course[] = []',
  'class="week-time-grid"',
  'class="time-guide"',
  'class="time-label"',
  'courseGridPlacement(course)',
  'parseClock(course.start)',
  'parseClock(course.end)',
  'grid-template-rows: repeat(var(--time-row-count), var(--time-step-height))',
  'class="course-credit"',
  'export let addRequest = 0'
];

for (const marker of requiredCore) {
  if (!core.includes(marker)) {
    console.error(`Week timeline regression: missing ${marker}`);
    process.exit(1);
  }
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

const readableMarkers = [
  '.page-week.week-shell',
  'overflow: visible !important',
  '.page-week .week-board .week-header',
  'position: sticky !important',
  '.page-week .week-time-grid',
  'repeat(var(--time-row-count), var(--time-step-height))',
  '.page-week .time-guide.major',
  '.page-week .time-label',
  '.page-week .week-time-grid .week-course span',
  'text-overflow: clip !important',
  'white-space: normal !important',
  '--axis-width: 46px !important'
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
  '.page-week .week-toolbar .week-title b',
  'border-top-color: transparent !important'
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

console.log('Week time-proportional layout contract OK');
