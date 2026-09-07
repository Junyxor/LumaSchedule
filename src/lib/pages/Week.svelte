<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import type { Course } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;

  const weekdayNames = ['周一','周二','周三','周四','周五','周六','周日'];
  let now = new Date();
  let timer: ReturnType<typeof setInterval> | null = null;

  function weekContext(date: Date) {
    const monday = new Date(date);
    monday.setHours(0, 0, 0, 0);
    monday.setDate(date.getDate() - ((date.getDay() + 6) % 7));
    const days = weekdayNames.map((label, index) => {
      const item = new Date(monday);
      item.setDate(monday.getDate() + index);
      return { label, date: String(item.getDate()).padStart(2, '0') };
    });
    const sunday = new Date(monday);
    sunday.setDate(monday.getDate() + 6);
    return {
      days,
      todayIndex: ((date.getDay() + 6) % 7),
      rangeLabel: `${monday.getMonth() + 1}月${monday.getDate()}日 – ${sunday.getMonth() + 1}月${sunday.getDate()}日`
    };
  }

  onMount(() => { timer = setInterval(() => (now = new Date()), 60_000); });
  onDestroy(() => { if (timer) clearInterval(timer); });

  $: context = weekContext(now);
  $: sectionCount = Math.max(12, ...courses.map((course) => course.endSection || 0));
  $: sections = Array.from({ length: sectionCount }, (_, i) => i + 1);
  $: title = currentWeek ? `第 ${currentWeek} 周` : '本周课表';
  $: subtitle = [termName, courses.length ? `${courses.length} 个课程时段` : hasSchedule ? '当前周暂无课程' : '还没有导入课表'].filter(Boolean).join(' · ');
</script>

<section class="page page-week">
  <header class="topbar week-topbar">
    <div>
      <span class="eyebrow">{context.rangeLabel}</span>
      <h1>{title}</h1>
      <p>{subtitle}</p>
    </div>
  </header>

  <div class="week-board content-surface" style={`--section-count:${sectionCount}`}>
    <div class="week-header">
      <div class="corner">节</div>
      {#each context.days as day, i}<div class:today={i === context.todayIndex}><span>{day.label}</span><b>{day.date}</b></div>{/each}
    </div>
    <div class="week-scroll">
      <div class="section-column">{#each sections as n}<div><b>{n}</b></div>{/each}</div>
      <div class="week-gridlines">{#each Array(7) as _}<div></div>{/each}</div>
      {#each courses as course}
        <article class="week-course {course.color}" style={`--day:${course.day};--start:${course.startSection};--span:${course.endSection - course.startSection + 1}`} aria-label={`${course.name} ${course.room || ''}`}>
          <b>{course.name}</b>
          <span>{course.room || ''}</span>
          {#if course.start}<small>{course.start}</small>{/if}
        </article>
      {/each}
      {#if !courses.length}<div class="week-empty">{hasSchedule ? '课表已保存在本地，当前周没有需要显示的课程。' : '导入课表后，这里会显示一整周的课程。'}</div>{/if}
    </div>
  </div>
</section>
