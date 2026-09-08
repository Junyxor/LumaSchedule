import fs from 'node:fs';

const source = fs.readFileSync('src/lib/pages/WeekCore.svelte', 'utf8');

const required = [
  'class="week-body-grid"',
  'grid-column:${columnFor(course.day) + 1}',
  'class="section-time"',
  '<div class="corner">时间</div>'
];

for (const marker of required) {
  if (!source.includes(marker)) {
    console.error(`Week grid regression: missing ${marker}`);
    process.exit(1);
  }
}

if (source.includes('left:calc(') && source.includes('.week-course')) {
  console.error('Week grid regression: course cards must not use absolute left:calc positioning.');
  process.exit(1);
}

console.log('Week grid layout contract OK');
