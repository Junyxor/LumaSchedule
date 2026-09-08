import fs from 'node:fs';

const core = fs.readFileSync('src/lib/pages/WeekCore.svelte', 'utf8');
const week = fs.readFileSync('src/lib/pages/Week.svelte', 'utf8');
const runtime = fs.readFileSync('src/styles/mobile-runtime.css', 'utf8');
const readable = fs.readFileSync('src/styles/week-readable-layout.css', 'utf8');
const appCss = fs.readFileSync('src/app.css', 'utf8');

const requiredCore = [
  'class="week-body-grid"',
  'grid-column:${columnFor(course.day) + 1}',
  'class="section-time"',
  "preferences.showTime ? '时间' : '节次'",
  'class="course-credit"',
  'export let addRequest = 0'
];

for (const marker of requiredCore) {
  if (!core.includes(marker)) {
    console.error(`Week grid regression: missing ${marker}`);
    process.exit(1);
  }
}

if (/\.week-board\s+\.week-course\s*\{[^}]*left\s*:\s*calc\(/s.test(core)) {
  console.error('Week grid regression: course cards must not use absolute left:calc positioning.');
  process.exit(1);
}

if (!/\.week-body-grid\s+\.week-course\s*\{[^}]*position\s*:\s*relative/s.test(core)) {
  console.error('Week grid regression: course cards must be grid items, not absolutely positioned.');
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
  '.page-week .week-body-grid .week-course span',
  'text-overflow: clip !important',
  'white-space: normal !important',
  '--axis-width: 44px !important'
];

for (const marker of readableMarkers) {
  if (!readable.includes(marker)) {
    console.error(`Week readability regression: missing ${marker}`);
    process.exit(1);
  }
}

if (!appCss.includes("@import './styles/week-readable-layout.css';")) {
  console.error('Week readability regression: final mobile timetable overrides are not loaded.');
  process.exit(1);
}

console.log('Week grid and readability contract OK');
