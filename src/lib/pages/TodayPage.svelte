<script lang="ts">
  import { CalendarPlus, CalendarX2, ChevronRight, Clock3, Coffee, Download, MapPin, Sparkles, User } from 'lucide-svelte';
  import { app } from '../state/app.svelte';
  import { addDays, durationLabel, findUpcoming, formatClock, minutesOfDay, monthDayLabel, occurrencesOn, rangeLabel, relativeDayLabel, sectionLabel, WEEKDAY_SHORT, weekdayOf, type Occurrence } from '../schedule/calendar';
  import { courseSheets, toneClass } from '../schedule/courseSheets.svelte';
  import EmptyState from '../ui/EmptyState.svelte';

  const now = $derived(app.now);
  const ctx = $derived(app.ctx);
  const nowMinute = $derived(minutesOfDay(now));
  const today = $derived(occurrencesOn(app.semester, ctx, app.sections, now));
  const tomorrow = $derived(occurrencesOn(app.semester, ctx, app.sections, addDays(now, 1)));
  const upcoming = $derived(findUpcoming(app.semester, ctx, app.sections, now));
  const remaining = $derived(today.filter((item) => item.end > nowMinute));
  const colorful = $derived(app.appearance.colorfulCourses);

  const greeting = $derived.by(() => {
    const hour = now.getHours();
    if (hour < 5) return '夜深了';
    if (hour < 11) return '早上好';
    if (hour < 13) return '中午好';
    if (hour < 18) return '下午好';
    return '晚上好';
  });

  const weekLabel = $derived.by(() => {
    switch (ctx.status) {
      case 'active':
        return `第 ${ctx.rawWeek} 周`;
      case 'before':
        return '尚未开学';
      case 'after':
        return '学期已结束';
      case 'unset':
        return '未设置开学日期';
      default:
        return '';
    }
  });

  const subtitle = $derived([app.semester.termName, weekLabel].filter(Boolean).join(' · '));

  const progress = $derived.by(() => {
    const current = upcoming.current;
    if (!current) return 0;
    return Math.min(100, Math.max(0, ((nowMinute - current.start) / (current.end - current.start)) * 100));
  });

  function meta(item: Occurrence) {
    return [app.display.showRoom ? item.course.room : '', app.display.showTeacher ? item.course.teacher : ''].filter(Boolean).join(' · ');
  }

  function timeText(item: Occurrence) {
    return app.display.showTime ? rangeLabel(item) : sectionLabel(item.course);
  }

  function status(item: Occurrence) {
    if (item.end <= nowMinute) return 'past';
    if (item.start <= nowMinute) return 'live';
    return 'next';
  }

  function openTermSettings() {
    app.openSettings('term');
  }
</script>

<section class="page">
  <header class="page-header">
    <div>
      <p class="date">{monthDayLabel(now)} {WEEKDAY_SHORT[weekdayOf(now) - 1]}</p>
      <h1>{greeting}</h1>
      {#if subtitle}<p>{subtitle}</p>{/if}
    </div>
  </header>

  {#if !app.ready}
    <div class="card skeleton" aria-hidden="true"></div>
  {:else if ctx.status === 'none'}
    <div class="card welcome">
      <EmptyState icon={Sparkles} title="欢迎使用 LumaSchedule" message="从学校教务系统导入课表，或者手动添加课程。所有数据只保存在这台设备上。">
        {#snippet actions()}
          <button class="btn btn-primary" type="button" onclick={() => app.go('import')}><Download size={17} /> 导入课表</button>
          <button class="btn" type="button" onclick={() => courseSheets.create()}><CalendarPlus size={17} /> 手动添加</button>
        {/snippet}
      </EmptyState>
    </div>
  {:else}
    {#if upcoming.current}
      {@const item = upcoming.current}
      <button class="hero {toneClass(item.course, colorful)}" type="button" onclick={() => courseSheets.show(item.course, item.date)}>
        <span class="hero-label"><i class="live-dot"></i>正在上课 · 还剩 {durationLabel(item.end - nowMinute)}</span>
        <b class="hero-name">{item.course.name}</b>
        <span class="hero-meta num">{rangeLabel(item)}{meta(item) ? ` · ${meta(item)}` : ''}</span>
        <span class="progress"><i style:width="{progress}%"></i></span>
        {#if upcoming.next}
          <span class="hero-next">下一节：{relativeDayLabel(upcoming.next.date, now) === '今天' ? '' : relativeDayLabel(upcoming.next.date, now) + ' '}{formatClock(upcoming.next.start)} {upcoming.next.course.name}</span>
        {/if}
      </button>
    {:else if upcoming.next}
      {@const item = upcoming.next}
      {@const sameDay = relativeDayLabel(item.date, now) === '今天'}
      <button class="hero {toneClass(item.course, colorful)}" type="button" onclick={() => courseSheets.show(item.course, item.date)}>
        <span class="hero-label">
          {#if sameDay}
            下一节 · {item.start - nowMinute <= 90 ? `${durationLabel(item.start - nowMinute)}后` : formatClock(item.start)}
          {:else}
            {remaining.length === 0 && today.length > 0 ? '今天的课已结束 · ' : ''}下一节在{relativeDayLabel(item.date, now)}
          {/if}
        </span>
        <b class="hero-name">{item.course.name}</b>
        <span class="hero-meta num">{sameDay ? '' : `${monthDayLabel(item.date)} `}{rangeLabel(item)}{meta(item) ? ` · ${meta(item)}` : ''}</span>
      </button>
    {:else}
      <div class="card">
        {#if ctx.status === 'before'}
          <EmptyState icon={Coffee} compact title="还没开学" message={ctx.termStart ? `学期从 ${monthDayLabel(ctx.termStart)} 所在的那一周开始。` : ''} />
        {:else if ctx.status === 'after'}
          <EmptyState icon={CalendarX2} compact title="本学期已经结束" message="新学期可以重新导入课表，旧课表会保留在「设置 → 我的课表」里。">
            {#snippet actions()}
              <button class="btn btn-tonal btn-sm" type="button" onclick={() => app.go('import')}>导入新学期</button>
            {/snippet}
          </EmptyState>
        {:else}
          <EmptyState icon={Coffee} compact title="近期没有课程" message="接下来三周内没有安排课程。" />
        {/if}
      </div>
    {/if}

    {#if ctx.status === 'unset'}
      <button class="notice unset" type="button" onclick={openTermSettings}>
        <CalendarX2 size={18} />
        <span><b>还没有设置开学日期。</b>设置后才能区分单双周、计算教学周，并安排上课提醒。</span>
        <ChevronRight size={18} />
      </button>
    {:else if app.estimatedCount > 0}
      <button class="notice unset" type="button" onclick={() => app.openSettings('times')}>
        <Clock3 size={18} />
        <span><b>{app.estimatedCount} 个上课时段没有具体时间，</b>暂按常见作息显示，上课提醒和桌面小组件也用这个时间。点这里设置作息时间。</span>
        <ChevronRight size={18} />
      </button>
    {/if}

    <div class="section-title">
      <h2>今天</h2>
      <span>{today.length ? `${today.length} 节课 · 剩余 ${remaining.length}` : '没有课'}</span>
    </div>
    {#if today.length}
      <div class="list timeline">
        {#each today as item (item.course.id)}
          {@const state = status(item)}
          <button class="list-row lesson {state}" type="button" onclick={() => courseSheets.show(item.course, item.date)}>
            <span class="time num">
              <b>{formatClock(item.start)}</b>
              <span>{formatClock(item.end)}</span>
            </span>
            <span class="lesson-bar {toneClass(item.course, colorful)}"></span>
            <span class="row-body">
              <span class="row-title">{item.course.name}</span>
              <span class="row-sub">
                {#if app.display.showRoom && item.course.room}<span class="meta"><MapPin size={12} />{item.course.room}</span>{/if}
                {#if app.display.showTeacher && item.course.teacher}<span class="meta"><User size={12} />{item.course.teacher}</span>{/if}
                {#if !(app.display.showRoom && item.course.room) && !(app.display.showTeacher && item.course.teacher)}{sectionLabel(item.course)}{/if}
              </span>
            </span>
            {#if state === 'live'}<span class="badge accent">进行中</span>{:else if state === 'past'}<span class="badge">已结束</span>{/if}
          </button>
        {/each}
      </div>
    {:else}
      <div class="card quiet">今天没有课程安排。</div>
    {/if}

    {#if remaining.length === 0 && tomorrow.length}
      <div class="section-title">
        <h2>明天</h2>
        <span>{tomorrow.length} 节课</span>
      </div>
      <div class="list">
        {#each tomorrow as item (item.course.id)}
          <button class="list-row lesson" type="button" onclick={() => courseSheets.show(item.course, item.date)}>
            <span class="time num"><b>{formatClock(item.start)}</b><span>{formatClock(item.end)}</span></span>
            <span class="lesson-bar {toneClass(item.course, colorful)}"></span>
            <span class="row-body">
              <span class="row-title">{item.course.name}</span>
              <span class="row-sub">{[item.course.room, timeText(item)].filter(Boolean).join(' · ')}</span>
            </span>
          </button>
        {/each}
      </div>
    {/if}
  {/if}

  {#if app.loadError && app.ready}
    <div class="notice danger load-error">{app.loadError}</div>
  {/if}
</section>

<style>
  .date {
    margin: 0 0 2px !important;
    color: var(--accent) !important;
    font-size: var(--text-md) !important;
    font-weight: 600;
  }

  .skeleton {
    height: 132px;
    background: linear-gradient(90deg, var(--surface) 0%, var(--surface-2) 50%, var(--surface) 100%);
  }

  .welcome {
    padding: 8px 0;
  }

  .hero {
    display: grid;
    gap: 6px;
    width: 100%;
    padding: 18px 18px 16px;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
    color: var(--text);
    text-align: left;
    position: relative;
    overflow: hidden;
  }

  .hero::before {
    content: '';
    position: absolute;
    inset: 0 auto 0 0;
    width: 5px;
    background: var(--tone-bar);
  }

  .hero:active {
    background: var(--surface-2);
  }

  .hero-label {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--accent);
    font-size: var(--text-sm);
    font-weight: 600;
  }

  .live-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--accent);
    animation: pulse 1.6s ease-in-out infinite;
  }

  .hero-name {
    font-size: 22px;
    font-weight: 700;
    line-height: 1.3;
  }

  .hero-meta {
    color: var(--text-2);
    font-size: var(--text-md);
  }

  .hero .progress {
    margin-top: 8px;
  }

  .hero-next {
    margin-top: 2px;
    color: var(--text-3);
    font-size: var(--text-sm);
  }

  .notice.unset {
    width: 100%;
    margin-top: 12px;
    border: 0;
    text-align: left;
    align-items: center;
  }

  .notice.unset span {
    flex: 1;
  }

  .lesson {
    --row-divider-inset: 76px;
    align-items: stretch;
    gap: 12px;
    padding-top: 12px;
    padding-bottom: 12px;
  }

  .lesson .time {
    display: grid;
    align-content: center;
    width: 46px;
    flex: none;
    text-align: right;
  }

  .lesson .time b {
    font-size: var(--text-md);
    font-weight: 650;
  }

  .lesson .time span {
    color: var(--text-3);
    font-size: var(--text-xs);
  }

  .lesson-bar {
    width: 4px;
    flex: none;
    border-radius: 2px;
    background: var(--tone-bar);
  }

  .lesson .row-body {
    align-self: center;
  }

  .lesson .row-sub {
    display: flex;
    flex-wrap: wrap;
    gap: 2px 10px;
  }

  .meta {
    display: inline-flex;
    align-items: center;
    gap: 3px;
  }

  .lesson .badge {
    align-self: center;
  }

  .lesson.past {
    opacity: 0.55;
  }

  .lesson.live {
    background: var(--accent-softer);
  }

  .quiet {
    color: var(--text-2);
    font-size: var(--text-md);
  }

  .load-error {
    margin-top: 16px;
  }
</style>
