<script lang="ts">
  import type { Course } from '../types';
  export let courses: Course[];

  const weekdayNames = ['周一','周二','周三','周四','周五','周六','周日'];
  const now = new Date();
  const monday = new Date(now);
  monday.setHours(0, 0, 0, 0);
  monday.setDate(now.getDate() - ((now.getDay() + 6) % 7));
  const days = weekdayNames.map((label, index) => {
    const date = new Date(monday);
    date.setDate(monday.getDate() + index);
    return { label, date: String(date.getDate()).padStart(2, '0') };
  });
  const todayIndex = ((now.getDay() + 6) % 7);
  const sunday = new Date(monday); sunday.setDate(monday.getDate() + 6);
  const rangeLabel = `${monday.getMonth() + 1}月${monday.getDate()}日 – ${sunday.getMonth() + 1}月${sunday.getDate()}日`;
  const sections = Array.from({ length: 12 }, (_, i) => i + 1);
</script>

<section class="page page-week">
  <header class="topbar week-topbar">
    <div>
      <span class="eyebrow">{rangeLabel}</span>
      <h1>本周课表</h1>
      <p>{courses.length ? `${courses.length} 个课程时段` : '还没有导入课表'}</p>
    </div>
  </header>

  <div class="week-board content-surface">
    <div class="week-header"><div class="corner">节</div>{#each days as day, i}<div class:today={i === todayIndex}><span>{day.label}</span><b>{day.date}</b></div>{/each}</div>
    <div class="week-scroll">
      <div class="section-column">{#each sections as n}<div><b>{n}</b></div>{/each}</div>
      <div class="week-gridlines">{#each Array(7) as _}<div></div>{/each}</div>
      {#each courses as course}<button class="week-course {course.color}" style={`--day:${course.day};--start:${course.startSection};--span:${course.endSection - course.startSection + 1}`} aria-label={`${course.name} ${course.room || ''}`}><b>{course.name}</b><span>{course.room || ''}</span>{#if course.start}<small>{course.start}</small>{/if}</button>{/each}
      {#if !courses.length}<div class="week-empty">导入课表后，这里会显示一整周的课程。</div>{/if}
    </div>
  </div>
</section>
