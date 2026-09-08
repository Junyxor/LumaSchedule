<script lang="ts">
  import { MapPin, ShieldCheck, Sparkles } from 'lucide-svelte';
  import { onDestroy, onMount } from 'svelte';
  import CoursePill from '../components/CoursePill.svelte';
  import type { Course, SchedulePreferences } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;
  export let preferences: SchedulePreferences = {
    hasSchedule: false, termName: '', termStart: '', weekCount: 20, timezone: 'Asia/Shanghai', weekStartsOn: 1,
    showWeekend: true, showTeacher: true, showRoom: true, showTime: true, compactMode: false, defaultSections: 12
  };

  let now = new Date();
  let timer: ReturnType<typeof setInterval> | null = null;

  const minutes = (value: string) => {
    const [h, m] = value.split(':').map(Number);
    return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : Number.NaN;
  };

  const courseSortKey = (course: Course) => {
    const parsed = minutes(course.start);
    return Number.isFinite(parsed) ? parsed : course.startSection * 60;
  };

  function locationTeacher(course: Course) {
    return [preferences.showRoom ? (course.room || '教室待定') : '', preferences.showTeacher ? course.teacher : ''].filter(Boolean).join(' · ');
  }

  function displayTime(course: Course) {
    if (!preferences.showTime) return `第${course.startSection}–${course.endSection}节`;
    return course.start || `第${course.startSection}节`;
  }

  onMount(() => { timer = setInterval(() => (now = new Date()), 60_000); });
  onDestroy(() => { if (timer) clearInterval(timer); });

  $: weekday = ((now.getDay() + 6) % 7) + 1;
  $: nowMinutes = now.getHours() * 60 + now.getMinutes();
  $: todayCourses = courses.filter((course) => course.day === weekday).sort((a, b) => courseSortKey(a) - courseSortKey(b));
  $: remainingCourses = todayCourses.filter((course) => !course.end || Number.isNaN(minutes(course.end)) || minutes(course.end) >= nowMinutes);
  $: nextCourse = remainingCourses[0] ?? null;
  $: dateLabel = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(now);
  $: greeting = now.getHours() < 6 ? '夜深了' : now.getHours() < 11 ? '早上好' : now.getHours() < 14 ? '中午好' : now.getHours() < 18 ? '下午好' : '晚上好';
  $: academicLabel = [termName, currentWeek ? `第 ${currentWeek} 周` : null].filter(Boolean).join(' · ');
  $: nextProgress = (() => {
    if (!nextCourse?.start || !nextCourse?.end) return 0;
    const start = minutes(nextCourse.start);
    const end = minutes(nextCourse.end);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return 0;
    return Math.max(0, Math.min(100, ((nowMinutes - start) / (end - start)) * 100));
  })();
  $: emptyTitle = hasSchedule ? '当前没有课程' : '还没有课表';
  $: emptyMessage = hasSchedule ? '课表已经保存在本地；当前周可能暂无课程，或不在教学周内。' : '前往「导入」添加学校教务课表或课表文件。';
</script>

<section class="page page-today" class:compact-today={preferences.compactMode}>
  <header class="topbar today-topbar">
    <div>
      <span class="eyebrow">{dateLabel}</span>
      <h1>{greeting}</h1>
      <p>{academicLabel ? `${academicLabel} · ` : ''}{remainingCourses.length ? `今天还有 ${remainingCourses.length} 个课程时段` : hasSchedule ? '今天没有剩余课程' : '等待导入课表'}</p>
    </div>
  </header>

  {#if nextCourse}
    <article class="next-card glass-panel refract apple-hero">
      <div class="next-card-top"><span class="soft-badge"><Sparkles size={14} /> {nextProgress > 0 ? '正在上课' : '下一节课程'}</span></div>
      <div class="next-main">
        <div><h2>{nextCourse.name}</h2>{#if locationTeacher(nextCourse)}<p><MapPin size={16} /> {locationTeacher(nextCourse)}</p>{/if}</div>
        <div class="time-block"><b>{displayTime(nextCourse)}</b>{#if preferences.showTime}<span>— {nextCourse.end || `第${nextCourse.endSection}节`}</span>{/if}</div>
      </div>
      <div class="progress"><i style={`width:${nextProgress}%`}></i></div>
      <div class="next-bottom"><span>本地课表 · 离线可用</span>{#if currentWeek}<span>第 {currentWeek} 周</span>{/if}</div>
    </article>
  {:else}
    <article class="today-empty content-surface">
      <span><ShieldCheck size={22} /></span>
      <div><h2>{todayCourses.length ? '今天的课程已结束' : emptyTitle}</h2><p>{todayCourses.length ? '今天剩下的时间可以自由安排。' : emptyMessage}</p></div>
    </article>
  {/if}

  <div class="section-heading"><div><h2>今天</h2><span>{todayCourses.length ? '按时间排列' : '暂无课程'}</span></div></div>
  <div class="today-content">
    <div class="timeline-card content-surface">
      <div class="timeline-courses">
        {#each todayCourses as course}
          <CoursePill name={course.name} room={locationTeacher(course)} time={displayTime(course)} color={course.color} compact={preferences.compactMode} />
        {/each}
        {#if !todayCourses.length}<div class="empty-day">{hasSchedule ? '当前没有今天的课程。' : '导入课表后，这里会显示当天课程。'}</div>{/if}
      </div>
    </div>
    <aside class="quick-stack">
      <div class="quick-card content-surface"><span class="quick-orb orb-a"></span><div><b>{courses.length ? `${courses.length} 个本周课程时段` : hasSchedule ? '课表已保存在本地' : '本地数据库为空'}</b><small>{currentWeek ? `已按第 ${currentWeek} 周过滤单双周` : hasSchedule ? '进入教学周后会自动显示对应课程' : '所有课程数据默认保存在设备本地'}</small></div></div>
      <div class="quick-card content-surface"><span class="quick-orb orb-b"></span><div><b>隐私优先</b><small>不登录云端也可以完整使用课表</small></div></div>
    </aside>
  </div>
</section>

<style>
  .compact-today .timeline-card { padding-top: 4px; padding-bottom: 4px; }
  .compact-today .quick-card { min-height: 84px; }
</style>
