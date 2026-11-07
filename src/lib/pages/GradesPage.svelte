<script lang="ts">
  import { ChartColumn, ExternalLink, GraduationCap, RefreshCw, Search, Trash2 } from 'lucide-svelte';
  import { onMount } from 'svelte';
  import { importSession } from '../import/session.svelte';
  import { api, recentAcademicSource, type RecentAcademicSource } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { confirmAction } from '../state/confirm.svelte';
  import { toast } from '../state/toast.svelte';
  import type { GradeImportBundle, GradeRecord, GradeSnapshot } from '../types';
  import EmptyState from '../ui/EmptyState.svelte';
  import Sheet from '../ui/Sheet.svelte';

  let snapshot = $state.raw<GradeSnapshot>({ records: [], terms: [], institutions: [] });
  let loading = $state(true);
  let term = $state('all');
  let query = $state('');
  let includeElective = $state(true);
  let recent = $state.raw<RecentAcademicSource | null>(null);
  let institution = $state('');
  let url = $state('');
  let manualOpen = $state(false);
  let preview = $state.raw<GradeImportBundle | null>(null);
  let saving = $state(false);

  const session = $derived(importSession.active?.kind === 'grades' ? importSession.active : null);

  $effect(() => {
    const result = importSession.gradeResult;
    if (!result) return;
    preview = result;
    importSession.gradeResult = null;
  });

  onMount(() => {
    recent = recentAcademicSource();
    if (recent) {
      url = recent.url;
      institution = recent.schoolName;
    }
    void refresh();
  });

  async function refresh() {
    if (!app.native) {
      loading = false;
      return;
    }
    loading = true;
    try {
      snapshot = await api.grades();
      if (term !== 'all' && !snapshot.terms.includes(term)) term = 'all';
      if (!institution && snapshot.institutions.length === 1) institution = snapshot.institutions[0];
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      loading = false;
    }
  }

  function termKey(value: string) {
    const year = Number(value.match(/(20\d{2})/)?.[1] ?? 0);
    let index = 0;
    if (/第?[一1]学期|[-_]1$|秋/.test(value)) index = 1;
    if (/第?[二2]学期|[-_]2$|春/.test(value)) index = 2;
    if (/第?[三3]学期|[-_]3$|夏/.test(value)) index = 3;
    if (/季/.test(value)) index = /春/.test(value) ? 1 : /夏/.test(value) ? 2 : 3;
    return year * 10 + index;
  }

  const sortedTerms = $derived([...snapshot.terms].sort((a, b) => termKey(b) - termKey(a) || b.localeCompare(a)));
  const needle = $derived(query.trim().toLowerCase());
  const filtered = $derived(
    snapshot.records.filter(
      (record) =>
        (term === 'all' || record.term === term) &&
        (includeElective || !record.elective) &&
        (!needle || `${record.courseName} ${record.courseCode} ${record.courseType}`.toLowerCase().includes(needle))
    )
  );

  type Aggregate = { value: number | null; weighted: boolean; count: number };

  function aggregate(records: GradeRecord[], pick: (record: GradeRecord) => number | null): Aggregate {
    const valid = records.map((record) => ({ record, value: pick(record) })).filter((item): item is { record: GradeRecord; value: number } => item.value !== null && Number.isFinite(item.value));
    if (!valid.length) return { value: null, weighted: false, count: 0 };
    const weighted = valid.filter((item) => (item.record.credit ?? 0) > 0);
    if (weighted.length) {
      const credits = weighted.reduce((sum, item) => sum + (item.record.credit ?? 0), 0);
      return { value: weighted.reduce((sum, item) => sum + item.value * (item.record.credit ?? 0), 0) / credits, weighted: true, count: weighted.length };
    }
    return { value: valid.reduce((sum, item) => sum + item.value, 0) / valid.length, weighted: false, count: valid.length };
  }

  const gpa = $derived(aggregate(filtered, (record) => record.gradePoint));
  const score = $derived(aggregate(filtered, (record) => record.numericScore));
  const credits = $derived(filtered.reduce((sum, record) => sum + (record.credit ?? 0), 0));

  const trend = $derived.by(() => {
    const scoped = snapshot.records.filter((record) => includeElective || !record.elective);
    const terms = [...new Set(scoped.map((record) => record.term))].sort((a, b) => termKey(a) - termKey(b) || a.localeCompare(b));
    const gpaPoints = terms.map((item) => ({ term: item, value: aggregate(scoped.filter((record) => record.term === item), (record) => record.gradePoint).value })).filter((point): point is { term: string; value: number } => point.value !== null);
    if (gpaPoints.length >= 2) return { label: '绩点', points: gpaPoints, max: Math.max(4, ...gpaPoints.map((point) => point.value)), digits: 2 };
    const scorePoints = terms.map((item) => ({ term: item, value: aggregate(scoped.filter((record) => record.term === item), (record) => record.numericScore).value })).filter((point): point is { term: string; value: number } => point.value !== null);
    return { label: '平均分', points: scorePoints, max: 100, digits: 1 };
  });

  const chart = $derived.by(() => {
    const points = trend.points;
    const width = 320;
    const height = 120;
    const min = trend.label === '绩点' ? 0 : Math.max(0, Math.floor(Math.min(...points.map((point) => point.value), 60) / 10) * 10);
    const x = (index: number) => (points.length === 1 ? width / 2 : 16 + index * ((width - 32) / (points.length - 1)));
    const yOf = (value: number) => height - 14 - ((value - min) / Math.max(1e-6, trend.max - min)) * (height - 34);
    return { width, height, coords: points.map((point, index) => ({ ...point, x: x(index), y: yOf(point.value) })) };
  });

  const distribution = $derived.by(() => {
    const scored = filtered.filter((record) => record.numericScore !== null);
    const buckets = [
      { label: '90+', test: (value: number) => value >= 90 },
      { label: '80–89', test: (value: number) => value >= 80 && value < 90 },
      { label: '70–79', test: (value: number) => value >= 70 && value < 80 },
      { label: '60–69', test: (value: number) => value >= 60 && value < 70 },
      { label: '<60', test: (value: number) => value < 60 }
    ].map((bucket) => ({ label: bucket.label, count: scored.filter((record) => bucket.test(record.numericScore ?? 0)).length }));
    return { buckets, max: Math.max(1, ...buckets.map((bucket) => bucket.count)) };
  });

  const grouped = $derived.by(() => {
    const map = new Map<string, GradeRecord[]>();
    for (const record of filtered) map.set(record.term, [...(map.get(record.term) ?? []), record]);
    return [...map.entries()].sort((a, b) => termKey(b[0]) - termKey(a[0]) || b[0].localeCompare(a[0]));
  });

  // The remembered SSO URL may contain a very long, percent-encoded service query.
  // Show only its origin/path; retain the exact URL for the actual capture action.
  function sourceLabel(value: string): string {
    try {
      const parsed = new URL(value);
      return `${parsed.host}${parsed.pathname === '/' ? '' : parsed.pathname}`;
    } catch {
      return value.split('?')[0];
    }
  }

  function format(value: number | null, digits: number) {
    return value === null || !Number.isFinite(value) ? '—' : value.toFixed(digits);
  }

  function scoreTone(record: GradeRecord) {
    const text = record.scoreText;
    const value = record.numericScore ?? (/优/.test(text) ? 95 : /良/.test(text) ? 85 : /中/.test(text) ? 75 : /不及格|不合格/.test(text) ? 50 : /及格|合格|通过/.test(text) ? 65 : null);
    if (value === null) return '';
    if (value >= 90) return 'success';
    if (value >= 80) return 'accent';
    if (value >= 60) return value < 70 ? 'warning' : '';
    return 'danger';
  }

  async function capture(source: RecentAcademicSource | null) {
    const target = (source?.url ?? url).trim();
    const school = (source?.schoolName ?? institution).trim();
    if (!target) {
      manualOpen = true;
      toast.show('先填写学校教务系统的网址。');
      return;
    }
    try {
      const started = await api.startGradeCapture(target, school);
      importSession.begin(started, 'grades', target);
    } catch (error) {
      toast.error(errorMessage(error));
    }
  }

  async function savePreview() {
    if (!preview || saving) return;
    saving = true;
    try {
      const result = await api.commitGrades(preview);
      toast.success(`已保存 ${result.recordCount} 条成绩${result.replacedCount ? `，替换旧记录 ${result.replacedCount} 条` : ''}`);
      preview = null;
      await refresh();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      saving = false;
    }
  }

  async function removeRecords() {
    const scope = term === 'all' ? null : term;
    const ok = await confirmAction({
      title: scope ? `删除「${scope}」的成绩？` : '清空全部成绩？',
      message: scope ? '只删除这个学期在本机保存的成绩记录。' : '删除本机保存的所有成绩记录，不影响课表。',
      confirmText: '删除',
      danger: true
    });
    if (!ok) return;
    try {
      const removed = await api.deleteGrades(scope);
      toast.success(`已删除 ${removed.value} 条成绩`);
      term = 'all';
      await refresh();
    } catch (error) {
      toast.error(errorMessage(error));
    }
  }
</script>

<section class="page">
  <header class="page-header">
    <div>
      <h1>成绩</h1>
      <p>按教务系统的原始成绩统计</p>
    </div>
    <button class="icon-btn filled" type="button" onclick={refresh} disabled={loading} aria-label="刷新"><RefreshCw size={19} /></button>
  </header>

  {#if session}
    <div class="card session">
      <span class="spinner"></span>
      <div><b>{session.schoolName || '教务系统'}</b><span>{importSession.message}</span></div>
      <button class="btn btn-sm" type="button" onclick={() => importSession.cancel()}>取消</button>
    </div>
  {/if}

  {#if loading}
    <div class="card loading"><span class="spinner"></span> 正在读取成绩…</div>
  {:else if !snapshot.records.length}
    <div class="card">
      <EmptyState icon={GraduationCap} title="还没有成绩" message="登录学校教务系统，在成绩查询页面一键读取。成绩只保存在本机。" />
    </div>
  {:else}
    <div class="metrics">
      <div class="metric primary">
        <span>平均绩点</span>
        <b class="num">{format(gpa.value, 2)}</b>
        <small>{gpa.count ? (gpa.weighted ? `按学分加权 · ${gpa.count} 门` : `算术平均 · ${gpa.count} 门`) : '教务未提供绩点'}</small>
      </div>
      <div class="metric">
        <span>平均分</span>
        <b class="num">{format(score.value, 1)}</b>
        <small>{score.count ? (score.weighted ? '按学分加权' : '算术平均') : '没有数值成绩'}</small>
      </div>
      <div class="metric">
        <span>总学分</span>
        <b class="num">{Number(credits.toFixed(2))}</b>
        <small>{filtered.length} 门课程</small>
      </div>
    </div>

    <div class="filters">
      <div class="chip-row">
        <button class="chip" class:active={term === 'all'} type="button" onclick={() => (term = 'all')}>全部学期</button>
        {#each sortedTerms as item (item)}
          <button class="chip" class:active={term === item} type="button" onclick={() => (term = item)}>{item}</button>
        {/each}
      </div>
      <div class="filter-row">
        <label class="search">
          <Search size={16} />
          <input bind:value={query} placeholder="搜索课程名称或代码" />
        </label>
        <button class="chip" class:active={includeElective} type="button" onclick={() => (includeElective = !includeElective)}>{includeElective ? '含选修' : '不含选修'}</button>
      </div>
    </div>

    {#if trend.points.length >= 2}
      <div class="card chart-card">
        <div class="card-title"><ChartColumn size={18} /> 各学期{trend.label}<span class="hint">{trend.points.length} 个学期</span></div>
        <svg viewBox="0 0 {chart.width} {chart.height}" role="img" aria-label="各学期{trend.label}变化">
          <polyline points={chart.coords.map((point) => `${point.x},${point.y}`).join(' ')} />
          {#each chart.coords as point (point.term)}
            <circle cx={point.x} cy={point.y} r="4" />
            <text x={point.x} y={Math.max(12, point.y - 9)} text-anchor="middle">{point.value.toFixed(trend.digits)}</text>
          {/each}
        </svg>
        <div class="chart-labels">
          {#each chart.coords as point (point.term)}<span title={point.term}>{point.term}</span>{/each}
        </div>
      </div>
    {/if}

    {#if distribution.buckets.some((bucket) => bucket.count)}
      <div class="card dist">
        <div class="card-title">成绩分布<span class="hint">按百分制成绩</span></div>
        {#each distribution.buckets as bucket (bucket.label)}
          <div class="dist-row">
            <span>{bucket.label}</span>
            <i><em style:width="{(bucket.count / distribution.max) * 100}%"></em></i>
            <b class="num">{bucket.count}</b>
          </div>
        {/each}
      </div>
    {/if}

    {#each grouped as [groupTerm, records] (groupTerm)}
      <div class="section-title"><h2>{groupTerm}</h2><span>{records.length} 门</span></div>
      <div class="list">
        {#each records as record (record.id)}
          <div class="list-row grade">
            <span class="row-body">
              <span class="row-title">{record.courseName}</span>
              <span class="row-sub">{[record.courseType, record.credit !== null ? `${record.credit} 学分` : '', record.attempt > 1 ? `第 ${record.attempt} 次修读` : ''].filter(Boolean).join(' · ') || record.courseCode || '—'}</span>
            </span>
            <span class="score">
              <b class="num {scoreTone(record)}">{record.scoreText || format(record.numericScore, 1)}</b>
              <small class="num">{record.gradePoint === null ? '' : `绩点 ${record.gradePoint.toFixed(2)}`}</small>
            </span>
          </div>
        {/each}
      </div>
    {:else}
      <div class="card quiet">没有符合筛选条件的成绩。</div>
    {/each}

    <button class="btn btn-sm btn-ghost danger-link" type="button" onclick={removeRecords}><Trash2 size={15} /> {term === 'all' ? '清空全部成绩' : '删除本学期成绩'}</button>
  {/if}

  <div class="list-caption">从教务读取成绩</div>
  <div class="card capture">
    {#if recent}
      <div class="recent">
        <div>
          <b>{recent.schoolName || '最近使用的教务系统'}</b>
          <span title={recent.url}>{sourceLabel(recent.url)}</span>
        </div>
        <button class="btn btn-primary" type="button" onclick={() => capture(recent)} disabled={!!session}>读取成绩</button>
      </div>
      <p class="hint">会沿用上次登录的教务窗口 Cookie；登录过期时需要重新登录。</p>
      <button class="link" type="button" onclick={() => (manualOpen = !manualOpen)}>{manualOpen ? '收起' : '使用其他学校或网址'}</button>
    {:else}
      <p class="hint">先在「导入」页登录一次学校教务，这里会记住入口；也可以直接填写教务网址。</p>
    {/if}
    {#if manualOpen || !recent}
      <div class="stack manual">
        <label class="field"><span class="label">学校名称</span><input class="input" bind:value={institution} placeholder="例如 广东工业大学" /></label>
        <label class="field"><span class="label">教务系统网址</span><input class="input" bind:value={url} inputmode="url" placeholder="https://jwxt.example.edu.cn/" /></label>
        <div class="row">
          <a class="btn btn-outline btn-sm" href={`https://www.bing.com/search?q=${encodeURIComponent(`${institution.trim() || '学校'} 教务系统`)}`}><ExternalLink size={15} /> 搜索网址</a>
          <span class="spacer"></span>
          <button class="btn btn-tonal btn-sm" type="button" onclick={() => capture(null)} disabled={!!session}>打开并读取</button>
        </div>
      </div>
    {/if}
  </div>
</section>

<Sheet open={!!preview} title="成绩预览" subtitle={preview ? `${preview.institution || '教务系统'} · ${preview.records.length} 条` : ''} size="tall" dismissible={!saving} onclose={() => (preview = null)}>
  {#if preview}
    <div class="list">
      {#each preview.records as record, index (index)}
        <div class="list-row grade">
          <span class="row-body">
            <span class="row-title">{record.courseName}</span>
            <span class="row-sub">{[record.term || preview.termName, record.credit != null ? `${record.credit} 学分` : ''].filter(Boolean).join(' · ')}</span>
          </span>
          <span class="score">
            <b class="num">{record.scoreText || record.numericScore || '—'}</b>
            <small class="num">{record.gradePoint == null ? '' : `绩点 ${record.gradePoint}`}</small>
          </span>
        </div>
      {/each}
    </div>
    <p class="list-footer">保存时会替换同一来源、同一学期的旧成绩。缺少绩点的课程不会按统一公式自动换算。</p>
  {/if}
  {#snippet footer()}
    <button class="btn btn-lg" type="button" onclick={() => (preview = null)} disabled={saving}>取消</button>
    <button class="btn btn-lg btn-primary" style="flex:1" type="button" onclick={savePreview} disabled={saving}>
      {#if saving}<span class="spinner"></span>{/if} 保存 {preview?.records.length ?? 0} 条成绩
    </button>
  {/snippet}
</Sheet>

<style>
  .session {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
    border-color: var(--accent-line);
  }

  .session div {
    flex: 1;
    min-width: 0;
  }

  .session b {
    display: block;
    font-size: var(--text-md);
  }

  .session span:not(.spinner) {
    color: var(--text-2);
    font-size: var(--text-sm);
  }

  .loading {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 32px;
    color: var(--text-2);
  }

  .metrics {
    display: grid;
    grid-template-columns: 1.2fr 1fr 1fr;
    gap: 8px;
  }

  .metric {
    display: grid;
    gap: 2px;
    min-width: 0;
    padding: 14px;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
  }

  .metric.primary {
    border-color: transparent;
    background: var(--accent);
    color: var(--on-accent);
  }

  .metric span {
    font-size: var(--text-sm);
    opacity: 0.8;
  }

  .metric b {
    font-size: 26px;
    font-weight: 700;
    line-height: 1.2;
  }

  .metric small {
    overflow: hidden;
    font-size: var(--text-xs);
    opacity: 0.72;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .filters {
    display: grid;
    gap: 10px;
    margin: 16px 0 12px;
  }

  .filter-row {
    display: flex;
    gap: 8px;
  }

  .search {
    display: flex;
    flex: 1;
    align-items: center;
    gap: 8px;
    min-width: 0;
    padding: 0 12px;
    border: 1px solid var(--border);
    border-radius: var(--radius-pill);
    background: var(--surface);
    color: var(--text-3);
  }

  .search input {
    flex: 1;
    min-width: 0;
    height: 34px;
    border: 0;
    background: transparent;
    color: var(--text);
    font-size: var(--text-md);
    outline: none;
  }

  .chart-card svg {
    width: 100%;
    height: auto;
    overflow: visible;
  }

  polyline {
    fill: none;
    stroke: var(--accent);
    stroke-width: 2.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  circle {
    fill: var(--surface);
    stroke: var(--accent);
    stroke-width: 2.5;
  }

  text {
    fill: var(--text-2);
    font-size: 11px;
    font-variant-numeric: tabular-nums;
  }

  .chart-labels {
    display: flex;
    justify-content: space-between;
    gap: 6px;
    margin-top: 4px;
  }

  .chart-labels span {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    color: var(--text-3);
    font-size: var(--text-xs);
    text-align: center;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .dist {
    display: grid;
    gap: 10px;
  }

  .dist .card-title {
    margin-bottom: 2px;
  }

  .dist-row {
    display: grid;
    grid-template-columns: 48px 1fr 28px;
    align-items: center;
    gap: 10px;
    font-size: var(--text-sm);
  }

  .dist-row span {
    color: var(--text-2);
  }

  .dist-row i {
    height: 8px;
    overflow: hidden;
    border-radius: 4px;
    background: var(--fill);
  }

  .dist-row em {
    display: block;
    height: 100%;
    min-width: 3px;
    border-radius: inherit;
    background: var(--accent);
  }

  .dist-row b {
    text-align: right;
  }

  .grade {
    align-items: center;
  }

  .score {
    display: grid;
    justify-items: end;
    flex: none;
  }

  .score b {
    font-size: var(--text-lg);
    font-weight: 700;
  }

  .score b.success {
    color: var(--success);
  }

  .score b.accent {
    color: var(--accent);
  }

  .score b.warning {
    color: var(--warning);
  }

  .score b.danger {
    color: var(--danger);
  }

  .score small {
    color: var(--text-3);
    font-size: var(--text-xs);
  }

  .quiet {
    margin-top: 12px;
    color: var(--text-2);
    font-size: var(--text-md);
  }

  .danger-link {
    margin-top: 10px;
    color: var(--danger);
  }

  .capture {
    display: grid;
    /* Keep min-content from long school login URLs from widening the CSS grid. */
    grid-template-columns: minmax(0, 1fr);
    min-width: 0;
    max-width: 100%;
    gap: 10px;
  }

  .recent {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
    max-width: 100%;
  }

  .recent div {
    flex: 1;
    min-width: 0;
  }

  .recent b {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .recent span {
    display: block;
    width: 100%;
    max-width: 100%;
    overflow: hidden;
    color: var(--text-3);
    font-size: var(--text-xs);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .hint {
    color: var(--text-2);
    font-size: var(--text-sm);
    line-height: 1.55;
  }

  .link {
    justify-self: start;
    padding: 0;
    border: 0;
    background: none;
    color: var(--accent);
    font-size: var(--text-sm);
    font-weight: 550;
  }

  .manual {
    grid-template-columns: minmax(0, 1fr);
    min-width: 0;
    max-width: 100%;
    margin-top: 4px;
  }

  .manual .row {
    min-width: 0;
    flex-wrap: wrap;
  }

  .manual .input {
    max-width: 100%;
  }

  .spacer {
    flex: 1;
  }

  @media (max-width: 420px) {
    .metric {
      padding: 12px;
    }

    .metric b {
      font-size: 22px;
    }
  }
</style>
