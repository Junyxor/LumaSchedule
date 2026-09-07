<script lang="ts">
  import { Bell, MapPin, ShieldCheck, Sparkles } from 'lucide-svelte';
  import { onDestroy, onMount } from 'svelte';
  import CoursePill from '../components/CoursePill.svelte';
  import type { Course } from '../types';
  export let courses: Course[];

  let now = new Date();
  let timer: ReturnType<typeof setInterval> | null = null;

  const minutes = (value: string) => {
    const [h, m] = value.split(':').map(Number);
    return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : Number.NaN;
  };

  onMount(() => {
    timer = setInterval(() => (now = new Date()), 60_000);
  });
  onDestroy(() => { if (timer) clearInterval(timer); });

  $: weekday = ((now.getDay() + 6) % 7) + 1;
  $: nowMinutes = now.getHours() * 60 + now.getMinutes();
  $: todayCourses = courses
    .filter((course) => course.day === weekday)
    .sort((a, b) => (minutes(a.start) || a.startSection * 60) - (minutes(b.start) || b.startSection * 60));
  $: remainingCourses = todayCourses.filter((course) => !course.end || Number.isNaN(minutes(course.end)) || minutes(course.end) >= nowMinutes);
  $: nextCourse = remainingCourses[0] ?? null;
  $: dateLabel = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(now);
  $: greeting = now.getHours() < 6 ? '夜深了' : now.getHours() < 11 ? '早上好' : now.getHours() < 14 ? '中午好' : now.getHours() < 18 ? '下午好' : '晚上好';
  $: nextProgress = (() => {
    if (!nextCourse?.start || !nextCourse?.end) return 0;
    const start = minutes(nextCourse.start); const end = minutes(nextCourse.end);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return 0;
    return Math.max(0, Math.min(100, ((nowMinutes - start) / (end - start)) * 100));
  })();
</script>

<section class="page page-today">
  <header class="topbar today-topbar">
    <div>
      <span class="eyebrow">{dateLabel}</span>
      <h1>{greeting}</h1>
      <p>{remainingCourses.length ? `今天还有 ${remainingCourses.length} 个课程时段` : '今天没有剩余课程'}</p>
    </div>
    <div class="top-actions"><button class="icon-button glass-panel" aria-label="通知"><Bell size={19} /></button></div>
  </header>

  {#if nextCourse}
    <article class="next-card glass-panel refract apple-hero">
      <div class="next-card-top"><span class="soft-badge"><Sparkles size={14} /> {nextProgress > 0 ? '正在上课' : '下一节课程'}</span></div>
      <div class="next-main"><div><h2>{nextCourse.name}</h2><p><MapPin size={16} /> {nextCourse.room || '教室待定'}{nextCourse.teacher ? ` · ${nextCourse.teacher}` : ''}</p></div><div class="time-block"><b>{nextCourse.start || '--:--'}</b><span>— {nextCourse.end || '--:--'}</span></div></div>
      <div class="progress"><i style={`width:${nextProgress}%`}></i></div>
      <div class="next-bottom"><span>本地课表 · 离线可用</span></div>
    </article>
  {:else}
    <article class="today-empty glass-panel">
      <span><ShieldCheck size={22} /></span>
      <div><h2>{courses.length ? '今天没有课程' : '还没有课表'}</h2><p>{courses.length ? '今天可以自由安排。' : '前往「导入」添加学校教务课表或课表文件。'}</p></div>
    </article>
  {/if}

  <div class="section-heading"><div><h2>今天</h2><span>{todayCourses.length ? '按时间排列' : '暂无课程'}</span></div></div>
  <div class="today-content">
    <div class="timeline-card content-surface">
      <div class="timeline-courses">{#each todayCourses as course}<CoursePill name={course.name} room={course.room || '教室待定'} time={course.start || `第${course.startSection}节`} color={course.color} />{/each}{#if !todayCourses.length}<div class="empty-day">{courses.length ? '今天没有课程。' : '导入课表后，这里会显示当天课程。'}</div>{/if}</div>
    </div>
    <aside class="quick-stack">
      <div class="quick-card content-surface"><span class="quick-orb orb-a"></span><div><b>{courses.length ? `${courses.length} 个课程时段` : '本地数据库为空'}</b><small>所有课程数据默认保存在设备本地</small></div></div>
      <div class="quick-card content-surface"><span class="quick-orb orb-b"></span><div><b>隐私优先</b><small>不登录云端也可以完整使用课表</small></div></div>
    </aside>
  </div>
</section>
