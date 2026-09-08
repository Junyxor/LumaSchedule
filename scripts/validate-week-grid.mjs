import fs from 'node:fs';

const source = fs.readFileSync('src/lib/pages/WeekCore.svelte', 'utf8');

const required = [
  'class="week-body-grid"',
  'grid-column:${columnFor(course.day) + 1}',
  'class="section-time"',
  "preferences.showTime ? '时间' : '节次'"
];

for (const marker of required) {
  if (!source.includes(marker)) {
    console.error(`Week grid regression: missing ${marker}`);
    process.exit(1);
  }
}

if (/\.week-board\s+\.week-course\s*\{[^}]*left\s*:\s*calc\(/s.test(source)) {
  console.error('Week grid regression: course cards must not use absolute left:calc positioning.');
  process.exit(1);
}

if (!/\.week-body-grid\s+\.week-course\s*\{[^}]*position\s*:\s*relative/s.test(source)) {
  console.error('Week grid regression: course cards must be grid items, not absolutely positioned.');
  process.exit(1);
}

console.log('Week grid layout contract OK');
