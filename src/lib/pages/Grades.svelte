<script lang="ts">
  import { BarChart3, BookOpenCheck, CheckCircle2, ExternalLink, GraduationCap, LoaderCircle, RefreshCw, Search, ShieldCheck, TrendingUp, X } from 'lucide-svelte';
  import { onDestroy, onMount } from 'svelte';
  import { closeShiguangSession, commitGradeBundle, getGradeSnapshot, getShiguangSession, startGradeCapture } from '../tauri';
  import type { GradeImportBundle, GradeRecord, GradeSnapshot, ShiguangImportStart } from '../types';

  let snapshot: GradeSnapshot = { records: [], terms: [], institutions: [] };
  let loading = true;
  let error = '';
  let status = '';
  let query = '';
  let selectedTerm = 'all';
  let includeElective = true;
  let institution = '';
  let academicUrl = '';
  let activeSession: ShiguangImportStart | null = null;
  let captureMessage = '';
  let gradePreview: GradeImportBundle | null = null;
  let saving = false;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;

  type Aggregate = { value: number | null; weighted: boolean; count: number; total: number };
  type TrendPoint = { term: string; value: number };

  function friendlyError(value: unknown) {
    return value instanceof Error ? value.message : String(value);
  }

  async function refresh() {
    loading = true;
    error = '';
    try {
      snapshot = await getGradeSnapshot();
      if (!institution && snapshot.institutions.length === 1) institution = snapshot.institutions[0];
    } catch (e) {
      error = friendlyError(e);
    } finally {
      loading = false;
    }
  }

  function aggregate(records: GradeRecord[], getter: (record: GradeRecord) => number | null): Aggregate {
    const valid = records.map((record) => ({ record, value: getter(record) })).filter((item): item is { record: GradeRecord; value: number } => item.value !== null && Number.isFinite(item.value));
    if (!valid.length) return { value: null, weighted: false, count: 0, total: records.length };
    const weighted = valid.filter((item) => item.record.credit !== null && item.record.credit > 0);
    if (weighted.length) {
      const credits = weighted.reduce((sum, item) => sum + (item.record.credit ?? 0), 0);
      const value = credits > 0 ? weighted.reduce((sum, item) => sum + item.value * (item.record.credit ?? 0), 0) / credits : null;
      return { value, weighted: true, count: weighted.length, total: records.length };
    }
    return { value: valid.reduce((sum, item) => sum + item.value, 0) / valid.length, weighted: false, count: valid.length, total: records.length };
  }

  function formatMetric(value: number | null, digits = 2) {
    return value === null || !Number.isFinite(value) ? '—' : value.toFixed(digits);
  }

  function trendFor(records: GradeRecord[]) {
    const terms = [...new Set(records.map((record) => record.term).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN'));
    const gpaPoints: TrendPoint[] = [];
    const scorePoints: TrendPoint[] = [];
    for (const term of terms) {
      const termRows = records.filter((record) => record.term === term);
      const gpa = aggregate(termRows, (record) => record.gradePoint);
      const score = aggregate(termRows, (record) => record.numericScore);
      if (gpa.value !== null) gpaPoints.push({ term, value: gpa.value });
      if (score.value !== null) scorePoints.push({ term, value: score.value });
    }
    return gpaPoints.length >= 2
      ? { label: '来源绩点', max: Math.max(5, ...gpaPoints.map((item) => item.value)), points: gpaPoints }
      : { label: '平均成绩', max: 100, points: scorePoints };
  }

  function polyline(points: TrendPoint[], max: number) {
    if (!points.length) return '';
    const width = 300;
    const height = 116;
    const x = (index: number) => points.length === 1 ? width / 2 : 10 + index * ((width - 20) / (points.length - 1));
    const y = (value: number) => height - 8 - Math.max(0, Math.min(1, value / max)) * (height - 20);
    return points.map((point, index) => `${x(index)},${y(point.value)}`).join(' ');
  }

  function trendDotX(index: number, count: number) {
    return count === 1 ? 150 : 10 + index * (280 / (count - 1));
  }

  function trendDotY(value: number, max: number) {
    return 116 - 8 - Math.max(0, Math.min(1, value / max)) * 96;
  }

  function distribution(records: GradeRecord[]) {
    const gpaRows = records.filter((record) => record.gradePoint !== null && Number.isFinite(record.gradePoint));
    if (gpaRows.length) {
      return {
        label: '绩点分布',
        note: '按教务来源提供的绩点统计',
        buckets: [
          { label: '4–5', count: gpaRows.filter((r) => (r.gradePoint ?? -1) >= 4).length },
          { label: '3–4', count: gpaRows.filter((r) => (r.gradePoint ?? -1) >= 3 && (r.gradePoint ?? 99) < 4).length },
          { label: '2–3', count: gpaRows.filter((r) => (r.gradePoint ?? -1) >= 2 && (r.gradePoint ?? 99) < 3).length },
          { label: '1–2', count: gpaRows.filter((r) => (r.gradePoint ?? -1) >= 1 && (r.gradePoint ?? 99) < 2).length },
          { label: '0–1', count: gpaRows.filter((r) => (r.gradePoint ?? -1) >= 0 && (r.gradePoint ?? 99) < 1).length }
        ]
      };
    }
    const scoreRows = records.filter((record) => record.numericScore !== null && Number.isFinite(record.numericScore));
    return {
      label: '成绩分布',
      note: '暂无来源绩点，暂按原始百分制成绩分组',
      buckets: [
        { label: '90+', count: scoreRows.filter((r) => (r.numericScore ?? -1) >= 90).length },
        { label: '80–89', count: scoreRows.filter((r) => (r.numericScore ?? -1) >= 80 && (r.numericScore ?? 999) < 90).length },
        { label: '70–79', count: scoreRows.filter((r) => (r.numericScore ?? -1) >= 70 && (r.numericScore ?? 999) < 80).length },
        { label: '60–69', count: scoreRows.filter((r) => (r.numericScore ?? -1) >= 60 && (r.numericScore ?? 999) < 70).length },
        { label: '<60', count: scoreRows.filter((r) => (r.numericScore ?? 999) < 60).length }
      ]
    };
  }

  async function beginCapture() {
    if (!academicUrl.trim() || activeSession) {
      if (!academicUrl.trim()) error = '请先粘贴学校官方教务系统地址。';
      return;
    }
    error = '';
    status = '';
    gradePreview = null;
    try {
      activeSession = await startGradeCapture(academicUrl, institution);
      captureMessage = '登录窗口已打开。进入成绩查询页面后，点击顶部「抓取成绩」。';
      pollCapture(activeSession.sessionId);
    } catch (e) {
      error = friendlyError(e);
    }
  }

  async function pollCapture(sessionId: string) {
    if (pollTimer) clearTimeout(pollTimer);
    try {
      const state = await getShiguangSession(sessionId);
      captureMessage = state.message || '等待成绩页面抓取…';
      if (state.status === 'complete' && state.gradeBundle) {
        gradePreview = state.gradeBundle;
        activeSession = null;
        await closeShiguangSession(sessionId).catch(() => undefined);
        return;
      }
      if (state.status === 'error') {
        error = state.message || '成绩抓取失败。';
        activeSession = null;
        await closeShiguangSession(sessionId).catch(() => undefined);
        return;
      }
      pollTimer = setTimeout(() => void pollCapture(sessionId), 900);
    } catch {
      captureMessage = '正在重新连接成绩抓取会话…';
      pollTimer = setTimeout(() => void pollCapture(sessionId), 1500);
    }
  }

  async function savePreview() {
    if (!gradePreview || saving) return;
    saving = true;
    error = '';
    try {
      const result = await commitGradeBundle(gradePreview);
      status = `已保存 ${result.recordCount} 条成绩，覆盖同来源旧记录 ${result.replacedCount} 条。`;
      gradePreview = null;
      await refresh();
    } catch (e) {
      error = friendlyError(e);
    } finally {
      saving = false;
    }
  }

  onMount(() => void refresh());
  onDestroy(() => { if (pollTimer) clearTimeout(pollTimer); });

  $: normalizedQuery = query.trim().toLowerCase();
  $: filtered = snapshot.records.filter((record) =>
    (selectedTerm === 'all' || record.term === selectedTerm) &&
    (includeElective || !record.elective) &&
    (!normalizedQuery || `${record.courseName} ${record.courseCode} ${record.courseType}`.toLowerCase().includes(normalizedQuery))
  );
  $: averageScore = aggregate(filtered, (record) => record.numericScore);
  $: averageGpa = aggregate(filtered, (record) => record.gradePoint);
  $: trend = trendFor(snapshot.records.filter((record) => includeElective || !record.elective));
  $: trendLine = polyline(trend.points, trend.max);
  $: dist = distribution(filtered);
  $: distMax = Math.max(1, ...dist.buckets.map((item) => item.count));
  $: bingUrl = `https://www.bing.com/search?q=${encodeURIComponent(`${institution.trim() || '学校'} 教务系统 官网`)}`;
</script>

<section class="page page-grades">
  <header class="topbar grades-topbar">
    <div><span class="eyebrow">成绩</span><h1>成绩与绩点</h1><p>原始成绩本地保存，统计按学校提供的数据计算。</p></div>
    <button class="refresh-grade glass-panel" on:click={refresh} disabled={loading} aria-label="刷新成绩"><RefreshCw size={19} class:spin={loading}/></button>
  </header>

  <div class="grade-filters content-surface">
    <label><span>学期</span><select bind:value={selectedTerm}><option value="all">所有学期</option>{#each snapshot.terms as term}<option value={term}>{term}</option>{/each}</select></label>
    <label class="grade-search"><span>课程搜索</span><div><Search size={16}/><input bind:value={query} placeholder="课程名称 / 代码" /></div></label>
    <button class:active={includeElective} on:click={() => (includeElective = !includeElective)}>{includeElective ? '包含选修' : '不含选修'}</button>
  </div>

  {#if error}<div class="grade-message error"><X size={16}/>{error}</div>{/if}
  {#if status}<div class="grade-message success"><CheckCircle2 size={16}/>{status}</div>{/if}

  <div class="grade-summary-grid">
    <article class="metric-card glass-panel refract">
      <span><GraduationCap size={18}/> 平均绩点</span>
      <b>{formatMetric(averageGpa.value)}</b>
      <small>{averageGpa.count ? `${averageGpa.weighted ? '按学分加权' : '无学分时算术平均'} · 覆盖 ${averageGpa.count}/${filtered.length} 条` : '当前来源没有可用绩点，不进行统一换算'}</small>
    </article>
    <article class="metric-card content-surface">
      <span><BookOpenCheck size={18}/> 平均成绩</span>
      <b>{formatMetric(averageScore.value, 1)}</b>
      <small>{averageScore.count ? `${averageScore.weighted ? '按学分加权' : '无学分时算术平均'} · ${averageScore.count} 条可计算` : '暂无可计算的数值成绩'}</small>
    </article>
  </div>

  <div class="grade-dashboard">
    <article class="grade-card content-surface trend-card">
      <div class="grade-card-head"><div><span class="eyebrow">变化情况</span><h2>各学期{trend.label}</h2></div><TrendingUp size={20}/></div>
      {#if trend.points.length}
        <svg viewBox="0 0 300 136" role="img" aria-label={`各学期${trend.label}变化`}>
          <line x1="10" y1="108" x2="290" y2="108" class="axis"/>
          <line x1="10" y1="60" x2="290" y2="60" class="guide"/>
          <polyline points={trendLine} class="trend-line"/>
          {#each trend.points as point, index}
            <circle cx={trendDotX(index, trend.points.length)} cy={trendDotY(point.value, trend.max)} r="4.2"/>
            <text x={trendDotX(index, trend.points.length)} y={Math.max(13, trendDotY(point.value, trend.max) - 9)} text-anchor="middle">{point.value.toFixed(2)}</text>
          {/each}
        </svg>
        <div class="trend-labels">{#each trend.points as point}<span title={point.term}>{point.term}</span>{/each}</div>
      {:else}<div class="chart-empty">至少需要一个可计算学期后才会显示趋势。</div>{/if}
    </article>

    <article class="grade-card content-surface distribution-card">
      <div class="grade-card-head"><div><span class="eyebrow">分布</span><h2>{dist.label}</h2><p>{dist.note}</p></div><BarChart3 size={20}/></div>
      <div class="dist-list">{#each dist.buckets as bucket}<div><span>{bucket.label}</span><i><em style={`width:${bucket.count / distMax * 100}%`}></em></i><b>{bucket.count}</b></div>{/each}</div>
    </article>
  </div>

  <article class="grade-card content-surface course-grade-card">
    <div class="grade-card-head"><div><span class="eyebrow">课程成绩</span><h2>{filtered.length} 条记录</h2></div></div>
    {#if loading}<div class="chart-empty"><LoaderCircle class="spin" size={18}/> 正在读取本地成绩…</div>
    {:else if filtered.length}
      <div class="grade-table">{#each filtered.slice(0,120) as record}<div class="grade-row"><div><b>{record.courseName}</b><small>{[record.term, record.courseType, record.credit !== null ? `${record.credit} 学分` : '', record.attempt > 1 ? `第 ${record.attempt} 次修读` : ''].filter(Boolean).join(' · ')}</small></div><span>{record.scoreText || formatMetric(record.numericScore, 1)}</span><em>{record.gradePoint === null ? '绩点 —' : `绩点 ${record.gradePoint.toFixed(2)}`}</em></div>{/each}</div>
    {:else}<div class="chart-empty">没有符合当前筛选条件的课程成绩。</div>{/if}
  </article>

  <article class="grade-capture glass-panel refract">
    <div class="grade-card-head"><div><span class="eyebrow">教务抓取 · 兼容模式</span><h2>从学校官方教务读取成绩</h2><p>学校未专门适配时，可粘贴官方教务地址，在隔离 WebView 登录后抓取标准成绩表。</p></div><ShieldCheck size={20}/></div>
    <div class="capture-form">
      <label><span>学校名称</span><input bind:value={institution} placeholder="例如 广东工业大学" /></label>
      <label class="wide"><span>官方教务地址</span><input bind:value={academicUrl} inputmode="url" placeholder="https://jxfw.example.edu.cn/" /></label>
    </div>
    <div class="capture-actions">
      <a href={bingUrl}><ExternalLink size={15}/> Bing 搜索教务官网</a>
      <button class="primary-button" on:click={beginCapture} disabled={!!activeSession}>{activeSession ? '等待教务页面…' : '打开登录并抓取成绩'}</button>
    </div>
    <small class="capture-security">搜索仅用于寻找官网；请确认域名属于学校后再粘贴。Luma 不会自动把搜索结果当成登录页，也不会把账号密码传回主页面。</small>
    {#if activeSession}<div class="capture-session"><LoaderCircle class="spin" size={17}/><span>{captureMessage}</span></div>{/if}
  </article>

  {#if gradePreview}
    <div class="grade-preview-backdrop">
      <article class="grade-preview glass-panel refract">
        <header><div><span class="eyebrow">成绩预览</span><h2>识别到 {gradePreview.records.length} 条成绩</h2><p>{gradePreview.institution || institution || '教务系统'} · {gradePreview.termName || '未分组'}</p></div><button on:click={() => (gradePreview = null)} aria-label="关闭"><X size={18}/></button></header>
        <div class="preview-grade-list">{#each gradePreview.records.slice(0,8) as record}<div><b>{record.courseName}</b><span>{record.scoreText || (record.numericScore ?? '—')}</span><small>{record.gradePoint == null ? '绩点未提供' : `绩点 ${record.gradePoint}`}</small></div>{/each}{#if gradePreview.records.length > 8}<p>还有 {gradePreview.records.length - 8} 条记录将在确认后一起保存。</p>{/if}</div>
        <div class="preview-note">不会把缺失绩点按统一公式自动补算；教务来源给多少就保存多少。</div>
        <footer><button class="secondary-button" on:click={() => (gradePreview = null)}>取消</button><button class="primary-button" on:click={savePreview} disabled={saving}>{saving ? '正在保存…' : '确认保存'}</button></footer>
      </article>
    </div>
  {/if}
</section>

<style>
  .grades-topbar { align-items: center; }
  .refresh-grade { width: 44px; height: 44px; border: 0; border-radius: 50%; display: grid; place-items: center; color: #5b56d6; }
  .grade-filters { display: grid; grid-template-columns: minmax(130px,.7fr) minmax(220px,1.3fr) auto; gap: 10px; align-items: end; padding: 14px; margin-bottom: 14px; }
  .grade-filters label, .capture-form label { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
  .grade-filters label > span, .capture-form label > span { padding-left: 3px; font-size: 10px; color: rgba(60,60,67,.55); }
  .grade-filters select, .grade-filters input, .capture-form input { min-height: 44px; border: 1px solid rgba(60,60,67,.08); border-radius: 14px; padding: 0 12px; background: rgba(118,118,128,.08); color: inherit; outline: none; }
  .grade-search > div { position: relative; }
  .grade-search svg { position: absolute; left: 12px; top: 14px; opacity: .5; }
  .grade-search input { width: 100%; padding-left: 36px; }
  .grade-filters > button { min-height: 44px; border: 1px solid rgba(91,86,214,.16); border-radius: 14px; padding: 0 14px; background: rgba(91,86,214,.06); color: inherit; }
  .grade-filters > button.active { background: rgba(91,86,214,.14); color: #514bd0; }
  .grade-message { display: flex; align-items: center; gap: 8px; border-radius: 14px; padding: 10px 13px; margin: 10px 0; font-size: 12px; }
  .grade-message.error { background: rgba(215,60,76,.08); color: #b64d59; }
  .grade-message.success { background: rgba(38,155,101,.09); color: #277e58; }
  .grade-summary-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
  .metric-card { min-height: 150px; padding: 18px; display: flex; flex-direction: column; }
  .metric-card > span { display: flex; align-items: center; gap: 7px; font-size: 12px; color: rgba(60,60,67,.62); }
  .metric-card > b { margin-top: 18px; font-size: clamp(34px,6vw,52px); line-height: 1; letter-spacing: -.055em; }
  .metric-card > small { margin-top: auto; padding-top: 12px; color: rgba(60,60,67,.5); font-size: 10px; line-height: 1.5; }
  .grade-dashboard { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; margin-top: 14px; }
  .grade-card { padding: 18px; }
  .grade-card-head { display: flex; gap: 14px; align-items: flex-start; }
  .grade-card-head > div { flex: 1; min-width: 0; }
  .grade-card-head h2 { margin: 2px 0 0; font-size: 20px; letter-spacing: -.03em; }
  .grade-card-head p { margin: 5px 0 0; font-size: 11px; line-height: 1.45; color: rgba(60,60,67,.52); }
  .trend-card svg { width: 100%; height: 160px; overflow: visible; margin-top: 8px; }
  .trend-card .axis { stroke: rgba(60,60,67,.12); stroke-width: 1; }
  .trend-card .guide { stroke: rgba(60,60,67,.07); stroke-width: 1; stroke-dasharray: 4 5; }
  .trend-card .trend-line { fill: none; stroke: currentColor; color: #5b56d6; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; }
  .trend-card circle { fill: #5b56d6; stroke: rgba(255,255,255,.9); stroke-width: 2; }
  .trend-card text { fill: rgba(35,35,42,.7); font-size: 8px; }
  .trend-labels { display: flex; justify-content: space-between; gap: 5px; }
  .trend-labels span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: center; font-size: 8px; color: rgba(60,60,67,.5); }
  .chart-empty { min-height: 110px; display: flex; align-items: center; justify-content: center; gap: 8px; text-align: center; color: rgba(60,60,67,.48); font-size: 12px; }
  .dist-list { display: grid; gap: 13px; margin-top: 22px; }
  .dist-list > div { display: grid; grid-template-columns: 46px 1fr 24px; gap: 8px; align-items: center; font-size: 10px; }
  .dist-list i { height: 9px; background: rgba(118,118,128,.1); border-radius: 999px; overflow: hidden; }
  .dist-list em { display: block; height: 100%; min-width: 2px; border-radius: inherit; background: #5b56d6; }
  .dist-list b { text-align: right; }
  .course-grade-card { margin-top: 14px; }
  .grade-table { display: grid; margin-top: 12px; }
  .grade-row { min-height: 58px; display: grid; grid-template-columns: 1fr auto auto; align-items: center; gap: 12px; border-top: 1px solid rgba(60,60,67,.07); }
  .grade-row:first-child { border-top: 0; }
  .grade-row > div { min-width: 0; }
  .grade-row b { display: block; font-size: 12px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .grade-row small { display: block; margin-top: 3px; font-size: 9px; color: rgba(60,60,67,.46); overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .grade-row > span { font-size: 16px; font-weight: 700; }
  .grade-row > em { min-width: 64px; text-align: right; font-style: normal; font-size: 10px; color: rgba(60,60,67,.58); }
  .grade-capture { margin-top: 14px; padding: 18px; }
  .capture-form { display: grid; grid-template-columns: .8fr 1.2fr; gap: 10px; margin-top: 16px; }
  .capture-form .wide { min-width: 0; }
  .capture-actions { display: flex; justify-content: flex-end; align-items: center; gap: 10px; margin-top: 12px; }
  .capture-actions a { min-height: 42px; display: inline-flex; align-items: center; gap: 6px; padding: 0 13px; border-radius: 13px; text-decoration: none; color: inherit; background: rgba(118,118,128,.08); font-size: 11px; }
  .capture-security { display: block; margin-top: 10px; color: rgba(60,60,67,.48); font-size: 9px; line-height: 1.55; }
  .capture-session { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding: 10px 12px; border-radius: 13px; background: rgba(91,86,214,.08); font-size: 11px; }
  .grade-preview-backdrop { position: fixed; inset: 0; z-index: 140; display: flex; align-items: flex-end; justify-content: center; padding-top: 48px; background: rgba(18,18,24,.25); backdrop-filter: blur(5px); -webkit-backdrop-filter: blur(5px); }
  .grade-preview { width: min(620px,100%); max-height: 84dvh; overflow: auto; padding: 18px 18px calc(20px + env(safe-area-inset-bottom)); border-radius: 28px 28px 0 0; }
  .grade-preview header { display: flex; gap: 12px; }
  .grade-preview header > div { flex: 1; min-width: 0; }
  .grade-preview header h2 { margin: 3px 0 0; font-size: 22px; }
  .grade-preview header p { margin: 4px 0 0; font-size: 10px; color: rgba(60,60,67,.5); }
  .grade-preview header button { width: 38px; height: 38px; border: 0; border-radius: 50%; background: rgba(118,118,128,.1); display: grid; place-items: center; }
  .preview-grade-list { display: grid; margin-top: 14px; }
  .preview-grade-list > div { display: grid; grid-template-columns: 1fr auto auto; gap: 10px; align-items: center; min-height: 48px; border-top: 1px solid rgba(60,60,67,.07); }
  .preview-grade-list b { font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .preview-grade-list span { font-weight: 700; }
  .preview-grade-list small, .preview-grade-list p { font-size: 9px; color: rgba(60,60,67,.5); }
  .preview-note { margin-top: 12px; padding: 10px 12px; border-radius: 12px; background: rgba(91,86,214,.07); color: rgba(60,60,67,.62); font-size: 10px; }
  .grade-preview footer { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; }
  @media (max-width: 760px) {
    .grade-filters { grid-template-columns: 1fr 1fr; }
    .grade-filters > button { grid-column: 1 / -1; }
    .grade-dashboard, .grade-summary-grid { grid-template-columns: 1fr; }
    .capture-form { grid-template-columns: 1fr; }
    .capture-actions { align-items: stretch; flex-direction: column; }
    .capture-actions a, .capture-actions button { justify-content: center; width: 100%; }
    .grade-row { grid-template-columns: minmax(0,1fr) auto; }
    .grade-row > em { grid-column: 1 / -1; text-align: left; margin-top: -8px; padding-bottom: 7px; }
  }
</style>
