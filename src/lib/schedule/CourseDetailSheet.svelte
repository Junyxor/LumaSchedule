<script lang="ts">
  import { CalendarDays, Clock3, GraduationCap, MapPin, Pencil, Repeat, Trash2, User } from 'lucide-svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { confirmAction } from '../state/confirm.svelte';
  import { toast } from '../state/toast.svelte';
  import Sheet from '../ui/Sheet.svelte';
  import { courseRange, creditLabel, monthDayLabel, rangeLabel, sectionLabel, WEEKDAY_SHORT, weekOfDate, weeksSummary } from './calendar';
  import { courseSheets, toneClass } from './courseSheets.svelte';

  const detail = $derived(courseSheets.detail);
  const course = $derived(detail?.course ?? null);
  const range = $derived(course ? courseRange(course, app.sections) : null);
  const dateWeek = $derived(detail?.date ? weekOfDate(app.ctx, detail.date) : null);
  let busy = $state(false);

  async function remove() {
    if (!course || busy) return;
    const ok = await confirmAction({
      title: '删除这个上课时段？',
      message: `只删除「${course.name}」在${WEEKDAY_SHORT[course.day - 1]}${sectionLabel(course)}的时段，这门课的其他时段会保留。`,
      confirmText: '删除',
      danger: true
    });
    if (!ok) return;
    busy = true;
    try {
      await api.deleteCourse(course.id);
      courseSheets.closeDetail();
      toast.success('已删除');
      await app.reload();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = false;
    }
  }
</script>

<Sheet open={!!course} title={course?.name ?? ''} subtitle={course ? `${WEEKDAY_SHORT[course.day - 1]} · ${sectionLabel(course)}` : ''} onclose={() => courseSheets.closeDetail()} dismissible={!busy}>
  {#if course && range}
    <div class="hero {toneClass(course, app.appearance.colorfulCourses)}">
      <span class="bar"></span>
      <div>
        <b class="num">{rangeLabel(range)}</b>
        <span>{detail?.date ? `${monthDayLabel(detail.date)}${dateWeek ? ` · 第 ${dateWeek} 周` : ''}` : weeksSummary(course.weeks)}</span>
      </div>
    </div>
    <div class="list">
      <div class="list-row has-icon">
        <span class="row-icon"><Clock3 size={17} /></span>
        <span class="row-body"><span class="row-title">时间</span><span class="row-sub">{WEEKDAY_SHORT[course.day - 1]} {sectionLabel(course)} · {rangeLabel(range)}{course.startTime ? '' : '（按作息时间）'}</span></span>
      </div>
      <div class="list-row has-icon">
        <span class="row-icon"><Repeat size={17} /></span>
        <span class="row-body"><span class="row-title">周次</span><span class="row-sub">{weeksSummary(course.weeks)}</span></span>
      </div>
      <div class="list-row has-icon">
        <span class="row-icon"><MapPin size={17} /></span>
        <span class="row-body"><span class="row-title">教室</span><span class="row-sub">{course.room || '未填写'}</span></span>
      </div>
      <div class="list-row has-icon">
        <span class="row-icon"><User size={17} /></span>
        <span class="row-body"><span class="row-title">教师</span><span class="row-sub">{course.teacher || '未填写'}</span></span>
      </div>
      {#if course.credit !== null}
        <div class="list-row has-icon">
          <span class="row-icon"><GraduationCap size={17} /></span>
          <span class="row-body"><span class="row-title">学分</span><span class="row-sub">{creditLabel(course.credit)}</span></span>
        </div>
      {/if}
      {#if app.ctx.status === 'unset'}
        <div class="list-row has-icon">
          <span class="row-icon"><CalendarDays size={17} /></span>
          <span class="row-body"><span class="row-title">提示</span><span class="row-sub">还没有设置开学日期，课程按每周显示。</span></span>
        </div>
      {/if}
    </div>
  {/if}
  {#snippet footer()}
    <button class="btn btn-lg btn-danger" type="button" onclick={remove} disabled={busy}><Trash2 size={17} /> 删除</button>
    <button class="btn btn-lg btn-primary" style="flex:1" type="button" onclick={() => course && courseSheets.edit(course)} disabled={busy}>
      <Pencil size={17} /> 编辑
    </button>
  {/snippet}
</Sheet>

<style>
  .hero {
    display: flex;
    align-items: stretch;
    gap: 12px;
    margin-bottom: 14px;
    padding: 14px 16px;
    border-radius: var(--radius-md);
    background: var(--tone-bg);
    color: var(--tone-fg);
  }

  .bar {
    width: 4px;
    flex: none;
    border-radius: 2px;
    background: var(--tone-bar);
  }

  .hero b {
    display: block;
    font-size: var(--text-xl);
    font-weight: 700;
    line-height: 1.25;
  }

  .hero span:not(.bar) {
    display: block;
    margin-top: 2px;
    opacity: 0.78;
    font-size: var(--text-sm);
  }
</style>
