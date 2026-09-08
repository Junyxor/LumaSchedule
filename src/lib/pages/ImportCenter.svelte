<script lang="ts">
  import { ArrowLeft, BookOpenCheck, Building2, CalendarSync, CheckCircle2, ChevronRight, ExternalLink, FileJson2, FileSpreadsheet, LoaderCircle, Search, ShieldCheck, TriangleAlert, UploadCloud, X } from 'lucide-svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import {
    closeShiguangSession,
    commitImport,
    getShiguangSession,
    importText,
    listShiguangAdapters,
    listShiguangSchools,
    previewImport,
    startCompatibilityImport,
    startShiguangImport,
    startSmartCompatibilityImport
  } from '../tauri';
  import type { CompatibilityFamily, ImportBundle, ImportDiff, ImportMode, ShiguangAdapter, ShiguangImportStart, ShiguangSchool } from '../types';
  import { createEventDispatcher, onDestroy, tick } from 'svelte';

  const dispatch = createEventDispatcher<{ imported: void }>();
  const ALL_IMPORT_ACCEPT = '.json,.ics,.ical,.csv,.tsv,.cses,.yaml,.yml';
  const sources = [
    { icon: FileJson2, title: 'WakeUp / JSON / CSES', subtitle: '导入分享文本、备份 JSON 与 CSES YAML', tag: '兼容', accent: 'amber', accept: '.json,.cses,.yaml,.yml' },
    { icon: FileSpreadsheet, title: 'CSV / TSV', subtitle: '导入结构化表格课表', tag: '表格', accent: 'green', accept: '.csv,.tsv' },
    { icon: BookOpenCheck, title: 'ICS / iCalendar', subtitle: '导入标准 iCalendar 课表文件', tag: '跨平台', accent: 'blue', accept: '.ics,.ical' }
  ];
  const genericSchoolIds = new Set(['zhengfang_jiaowu', 'chaoxing_jiaowu', 'qingguo_jiaowu', 'urp_jiaowu']);
  const compatibilityFamilies: { id: CompatibilityFamily; label: string }[] = [
    { id: 'zhengfang_jiaowu', label: '正方' },
    { id: 'qingguo_jiaowu', label: '青果' },
    { id: 'urp_jiaowu', label: 'URP' },
    { id: 'chaoxing_jiaowu', label: '超星' }
  ];

  let fileInput: HTMLInputElement;
  let schoolSearchInput: HTMLInputElement;
  let loading = false;
  let dragOver = false;
  let preview: ImportBundle | null = null;
  let importDiff: ImportDiff | null = null;
  let diffLoading = false;
  let importMode: ImportMode = 'new';
  let overwriteConfirmOpen = false;
  let selectedFile = '';
  let error = '';
  let committed = '';

  let shiguangOpen = false;
  let shiguangLoading = false;
  let shiguangError = '';
  let schoolQuery = '';
  let schools: ShiguangSchool[] = [];
  let selectedSchool: ShiguangSchool | null = null;
  let adapters: ShiguangAdapter[] = [];
  let activeSession: ShiguangImportStart | null = null;
  let sessionMessage = '';
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let compatibilityUrl = '';
  let compatibilityFamily: CompatibilityFamily = 'zhengfang_jiaowu';
  let advancedCompatibility = false;

  let sheetOffset = 0;
  let sheetDragging = false;
  let sheetDragStartY = 0;
  let sheetPointerId: number | null = null;

  $: normalizedSchoolQuery = schoolQuery.trim().toLowerCase();
  $: filteredSchools = schools
    .filter((school) => school.id !== 'GLOBAL_TOOLS' && !genericSchoolIds.has(school.id))
    .filter((school) => !normalizedSchoolQuery || school.name.toLowerCase().includes(normalizedSchoolQuery) || school.id.toLowerCase().includes(normalizedSchoolQuery) || school.initial.toLowerCase().includes(normalizedSchoolQuery))
    .sort((a,b) => a.initial.localeCompare(b.initial, 'zh-CN') || a.name.localeCompare(b.name, 'zh-CN'))
    .slice(0,100);
  $: showCompatibility = normalizedSchoolQuery.length > 0 && filteredSchools.length === 0;
  $: bingUrl = `https://www.bing.com/search?q=${encodeURIComponent(`${schoolQuery.trim() || '学校'} 教务系统 官网`)}`;

  function formatFor(name: string) {
    const ext = name.toLowerCase().split('.').pop() ?? '';
    if (ext === 'json') return 'json';
    if (ext === 'ics' || ext === 'ical') return 'ics';
    if (ext === 'csv' || ext === 'tsv') return ext;
    if (ext === 'cses' || ext === 'yaml' || ext === 'yml') return 'cses';
    return ext;
  }

  function friendlyError(value: unknown) {
    const text = value instanceof Error ? value.message : String(value);
    if (text.includes('not allowed by ACL')) return '应用内部权限配置异常，请更新到修复版本。';
    return text;
  }

  function openFilePicker(accept = ALL_IMPORT_ACCEPT) {
    if (loading || !fileInput) return;
    fileInput.value = '';
    fileInput.accept = accept;
    fileInput.click();
  }

  async function preparePreview(bundle: ImportBundle, sourceLabel: string) {
    preview = bundle;
    selectedFile = sourceLabel;
    importDiff = null;
    error = '';
    committed = '';
    diffLoading = true;
    try {
      importDiff = await previewImport(bundle);
      importMode = importDiff.hasExistingSchedule ? 'merge' : 'new';
    } catch (e) {
      error = `导入内容已解析，但差异分析失败：${friendlyError(e)}`;
      importMode = 'new';
    } finally {
      diffLoading = false;
    }
  }

  async function loadFile(file?: File) {
    if (!file) return;
    error=''; committed=''; preview=null; importDiff=null; selectedFile=file.name;
    const format=formatFor(file.name);
    if (!['json','ics','csv','tsv','cses'].includes(format)) {
      error = `暂不识别 .${format || 'unknown'} 文件。`;
      return;
    }
    loading=true;
    try {
      const bundle = await importText(format, await file.text());
      await preparePreview(bundle, file.name);
    } catch(e) { error=friendlyError(e); }
    finally { loading=false; }
  }

  async function commitWithMode(mode: ImportMode) {
    if (!preview) return;
    loading=true; error='';
    try {
      const result=await commitImport(preview, mode);
      committed = mode === 'merge'
        ? `已合并到当前课表：新增 ${result.addedCount} 个时段，跳过 ${result.skippedCount} 个重复项。`
        : mode === 'overwrite'
          ? `已覆盖当前课表：写入 ${result.meetingCount} 个时段，替换 ${result.removedCount} 个旧时段。`
          : `已创建新课表：${result.courseCount} 门课程 / ${result.meetingCount} 个上课时段。`;
      preview=null;
      importDiff=null;
      overwriteConfirmOpen=false;
      dispatch('imported');
    } catch(e){ error=friendlyError(e); }
    finally { loading=false; }
  }

  function commitPreview() {
    if (!preview || loading || diffLoading) return;
    if (importMode === 'overwrite' && importDiff?.hasExistingSchedule) overwriteConfirmOpen = true;
    else void commitWithMode(importMode);
  }

  function closePreview() {
    preview = null;
    importDiff = null;
    error = '';
  }

  function onDrop(event: DragEvent){ event.preventDefault(); dragOver=false; void loadFile(event.dataTransfer?.files?.[0]); }

  async function openSchoolPicker(){
    shiguangOpen=true;
    shiguangError='';
    sheetOffset=0;
    await tick();
    schoolSearchInput?.focus({ preventScroll: true });
    if(schools.length)return;
    shiguangLoading=true;
    try{ schools=await listShiguangSchools(); }
    catch(e){ shiguangError=friendlyError(e); }
    finally{ shiguangLoading=false; }
  }

  function dismissSchoolPicker() {
    shiguangOpen = false;
    sheetOffset = 0;
    sheetDragging = false;
    sheetPointerId = null;
    if (!activeSession) {
      selectedSchool = null;
      adapters = [];
      shiguangError = '';
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && shiguangOpen) dismissSchoolPicker();
  }

  function beginSheetDrag(event: PointerEvent) {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    sheetDragging = true;
    sheetPointerId = event.pointerId;
    sheetDragStartY = event.clientY - sheetOffset;
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  }

  function moveSheetDrag(event: PointerEvent) {
    if (!sheetDragging || event.pointerId !== sheetPointerId) return;
    sheetOffset = Math.max(0, event.clientY - sheetDragStartY);
  }

  function endSheetDrag(event: PointerEvent) {
    if (!sheetDragging || event.pointerId !== sheetPointerId) return;
    const shouldDismiss = sheetOffset > Math.min(140, window.innerHeight * 0.16);
    sheetDragging = false;
    sheetPointerId = null;
    if (shouldDismiss) dismissSchoolPicker();
    else sheetOffset = 0;
  }

  function cancelSheetDrag(event: PointerEvent) {
    if (event.pointerId !== sheetPointerId) return;
    sheetDragging = false;
    sheetPointerId = null;
    sheetOffset = 0;
  }

  async function chooseSchool(school: ShiguangSchool){
    selectedSchool=school; adapters=[]; shiguangError=''; shiguangLoading=true;
    try{ adapters=await listShiguangAdapters(school.id); }
    catch(e){ shiguangError=friendlyError(e); }
    finally{ shiguangLoading=false; }
  }

  function backToSchools(){ selectedSchool=null; adapters=[]; shiguangError=''; void tick().then(() => schoolSearchInput?.focus({ preventScroll: true })); }

  async function beginSchoolImport(adapter: ShiguangAdapter){
    if(!selectedSchool||shiguangLoading)return;
    shiguangError=''; shiguangLoading=true; sessionMessage='正在准备隔离的教务登录环境…';
    try{
      activeSession=await startShiguangImport(selectedSchool.id,adapter.adapterId);
      sessionMessage='登录窗口已打开。完成登录后会自动读取课程。';
      void pollSession(activeSession.sessionId);
    } catch(e){ shiguangError=friendlyError(e); sessionMessage=''; }
    finally{ shiguangLoading=false; }
  }

  async function beginSmartCompatibility(){
    if(shiguangLoading || activeSession)return;
    if(!compatibilityUrl.trim()){
      shiguangError='先粘贴学校官方教务系统地址。';
      return;
    }
    shiguangError=''; shiguangLoading=true; sessionMessage='正在准备智能兼容登录环境…';
    try{
      activeSession=await startSmartCompatibilityImport(compatibilityUrl, schoolQuery);
      sessionMessage='登录后进入“我的课表 / 个人课表”，完成查询后点顶部「尝试抓取课表」。Luma 会先识别常见教务，再使用通用页面解析。';
      void pollSession(activeSession.sessionId);
    }catch(e){ shiguangError=friendlyError(e); sessionMessage=''; }
    finally{ shiguangLoading=false; }
  }

  async function beginManualCompatibility(){
    if(shiguangLoading || activeSession)return;
    if(!compatibilityUrl.trim()){
      shiguangError='先粘贴学校官方教务系统地址。';
      return;
    }
    shiguangError=''; shiguangLoading=true; sessionMessage='正在准备指定教务类型的登录环境…';
    try{
      activeSession=await startCompatibilityImport(compatibilityUrl,compatibilityFamily,schoolQuery);
      sessionMessage='登录并进入个人课表查询页面，确认课表已显示后点顶部「尝试抓取课表」。';
      void pollSession(activeSession.sessionId);
    }catch(e){ shiguangError=friendlyError(e); sessionMessage=''; }
    finally{ shiguangLoading=false; }
  }

  async function pollSession(sessionId:string){
    if(pollTimer)clearTimeout(pollTimer);
    try{
      const state=await getShiguangSession(sessionId);
      sessionMessage=state.message||(state.status==='collecting'?'正在整理课程与作息…':'等待教务登录完成…');
      if(state.status==='complete'&&state.bundle){
        const bundle = state.bundle;
        const label = `${state.schoolName} · ${state.adapterName}`;
        activeSession=null; shiguangOpen=false; selectedSchool=null; adapters=[];
        await closeShiguangSession(sessionId).catch(()=>undefined);
        await preparePreview(bundle, label);
        return;
      }
      if(state.status==='error'){
        shiguangError=state.message||'教务导入失败。';
        activeSession=null;
        await closeShiguangSession(sessionId).catch(()=>undefined);
        return;
      }
      pollTimer=setTimeout(()=>void pollSession(sessionId),900);
    }catch{
      sessionMessage='正在重新连接导入会话…';
      pollTimer=setTimeout(()=>void pollSession(sessionId),1500);
    }
  }

  onDestroy(()=>{if(pollTimer)clearTimeout(pollTimer);});
</script>

<svelte:window on:keydown={handleKeydown} />

<section class="page page-import">
  <header class="topbar"><div><span class="eyebrow">导入</span><h1>添加课表</h1><p>搜索学校教务，或从开放格式导入。</p></div></header>

  <div class="import-toolbar glass-panel apple-search">
    <Search size={18}/>
    <input bind:value={schoolQuery} aria-label="搜索学校" placeholder="搜索学校名称" on:focus={openSchoolPicker} on:input={()=>{ if(!shiguangOpen) void openSchoolPicker(); }}/>
  </div>

  <div class="source-grid apple-source-list">{#each sources as source}<button class="source-card content-surface" on:click={()=>openFilePicker(source.accept)}><span class="source-icon {source.accent}"><svelte:component this={source.icon} size={21}/></span><div><span class="source-title"><b>{source.title}</b><em>{source.tag}</em></span><p>{source.subtitle}</p></div><ChevronRight size={18}/></button>{/each}</div>

  <div class="import-hero content-surface" role="region" aria-label="课表文件导入" class:drag-over={dragOver} on:dragover={(e)=>{e.preventDefault();dragOver=true;}} on:dragleave={()=>dragOver=false} on:drop={onDrop}>
    <input bind:this={fileInput} class="file-input" type="file" accept={ALL_IMPORT_ACCEPT} on:change={(e)=>void loadFile(e.currentTarget.files?.[0])}/>
    <div class="upload-mark">{#if loading}<LoaderCircle class="spin" size={26}/>{:else}<UploadCloud size={26}/>{/if}</div>
    <div><h2>{loading?'正在解析…':'从文件导入'}</h2><p>支持 JSON / ICS / CSV / TSV / CSES。导入前会先做差异预览。</p></div>
    <button class="primary-button" on:click={()=>openFilePicker()} disabled={loading}>选择文件</button>
  </div>

  {#if committed}<div class="import-result import-success content-surface"><CheckCircle2 size={18}/><div><b>导入完成</b><span>{committed}</span></div></div>{/if}
  {#if error}<div class="import-result import-error content-surface"><X size={18}/><div><b>导入提示</b><span>{error}</span></div></div>{/if}

  {#if preview}
    <article class="import-preview content-surface">
      <div class="preview-head"><div class="preview-ok"><CheckCircle2 size={20}/></div><div><span class="eyebrow">导入预览</span><h2>已识别 {preview.courses.length} 条课程记录</h2><p>{selectedFile} · 来源 {preview.source}</p></div><button class="icon-ghost" on:click={closePreview} aria-label="关闭导入预览"><X size={18}/></button></div>
      {#if diffLoading}
        <div class="diff-loading"><LoaderCircle class="spin" size={18}/> 正在与当前课表比较…</div>
      {:else if importDiff}
        <div class="diff-grid" aria-label="导入差异">
          <div><b>{importDiff.newCount}</b><span>新增时段</span></div><div><b>{importDiff.duplicateCount}</b><span>重复跳过</span></div><div class:warn={importDiff.conflictCount>0}><b>{importDiff.conflictCount}</b><span>时间冲突</span></div><div><b>{importDiff.removeCount}</b><span>覆盖会移除</span></div>
        </div>
        <div class="import-mode-picker">
          <div><b>导入方式</b><span>{importDiff.hasExistingSchedule ? `当前已有 ${importDiff.existingMeetingCount} 个课程时段` : '当前没有课表'}</span></div>
          <div class="mode-options">
            <button class:active={importMode==='new'} on:click={()=>importMode='new'}><b>新建</b><small>保留当前课表，创建新课表</small></button>
            <button class:active={importMode==='merge'} disabled={!importDiff.hasExistingSchedule} on:click={()=>importMode='merge'}><b>合并</b><small>新增课程，重复项自动跳过</small></button>
            <button class:active={importMode==='overwrite'} disabled={!importDiff.hasExistingSchedule} on:click={()=>importMode='overwrite'}><b>覆盖</b><small>用本次导入替换当前课表</small></button>
          </div>
          {#if importMode==='merge' && importDiff.conflictCount>0}<div class="conflict-note"><TriangleAlert size={14}/> 检测到 {importDiff.conflictCount} 个时间冲突；合并后会保留两边课程。</div>{/if}
          {#if importMode==='overwrite' && importDiff.removeCount>0}<div class="overwrite-note">覆盖将移除当前课表中 {importDiff.removeCount} 个本次导入不存在的时段。</div>{/if}
        </div>
      {/if}
      <div class="preview-courses">{#each preview.courses.slice(0,6) as course}<div class="preview-course"><span>周{course.weekday}</span><div><b>{course.name}</b><small>第 {course.startSection}–{course.endSection} 节 · {course.location||'教室未提供'}</small></div><em>{course.weeks.length} 周</em></div>{/each}{#if preview.courses.length>6}<div class="preview-more">还有 {preview.courses.length-6} 条记录</div>{/if}</div>
      <div class="preview-actions"><button class="secondary-button" on:click={closePreview}>取消</button><button class="primary-button" on:click={commitPreview} disabled={loading||diffLoading}>{loading?'正在导入…':importMode==='merge'?'确认合并':importMode==='overwrite'?'确认覆盖':'创建课表'}</button></div>
    </article>
  {/if}

  <div class="security-note"><ShieldCheck size={18}/><div><b>本地优先</b><span>学校登录在隔离 WebView 中完成，课程先进入预览与差异分析，再写入本地数据库。</span></div></div>
</section>

{#if shiguangOpen}
  <div class="adapter-sheet-backdrop" role="presentation" on:click={(event)=>{if(event.currentTarget===event.target)dismissSchoolPicker();}}>
    <div class="adapter-browser glass-panel refract" class:sheet-dragging={sheetDragging} style:transform={`translate3d(0, ${sheetOffset}px, 0)`} role="dialog" aria-modal="true" aria-label="高校教务导入">
      <div class="sheet-grabber" role="button" tabindex="0" aria-label="向下拖动关闭高校教务搜索" on:pointerdown={beginSheetDrag} on:pointermove={moveSheetDrag} on:pointerup={endSheetDrag} on:pointercancel={cancelSheetDrag} on:keydown={(event)=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();dismissSchoolPicker();}}}></div>
      <div class="adapter-browser-head"><div class="adapter-brand"><span><Building2 size={20}/></span><div><span class="eyebrow">高校教务导入</span><h2>{selectedSchool?selectedSchool.name:'选择学校'}</h2><p>{selectedSchool?'选择可用的登录方式':'搜索学校；未收录也可以用官方教务网址智能兼容'}</p></div></div><button class="icon-ghost" on:click={dismissSchoolPicker} aria-label="关闭高校教务搜索"><X size={18}/></button></div>
      {#if activeSession}
        <div class="adapter-session"><div class="session-orbit"><LoaderCircle class="spin" size={22}/></div><div><b>{activeSession.schoolName} · {activeSession.adapterName}</b><span>{sessionMessage}</span><small>允许访问：{activeSession.allowedHosts.join(' · ')}</small>{#if activeSession.insecureTransport}<small class="adapter-http-warning"><TriangleAlert size={13}/> 该教务仍使用 HTTP，请仅在可信网络登录。</small>{/if}</div></div>
      {:else if selectedSchool}
        <div class="adapter-subbar"><button on:click={backToSchools}><ArrowLeft size={15}/> 返回</button><span>选择登录方式</span></div>
        <div class="adapter-list">{#if shiguangLoading}<div class="adapter-loading"><LoaderCircle class="spin" size={20}/> 正在读取登录方式…</div>{:else}{#each adapters as adapter}<button class="adapter-row" on:click={()=>void beginSchoolImport(adapter)} disabled={!adapter.importUrl}><span class="adapter-row-icon"><CalendarSync size={18}/></span><div><b>{adapter.adapterName}</b><p>{adapter.description}</p></div><ChevronRight size={17}/></button>{/each}{#if !adapters.length}<div class="adapter-empty">这个学校暂时没有可用的专用网页登录方式。返回后可以粘贴官方教务网址使用智能兼容。</div>{/if}{/if}</div>
      {:else}
        <div class="school-search"><Search size={17}/><input bind:this={schoolSearchInput} bind:value={schoolQuery} aria-label="学校名称" placeholder="输入学校名称"/></div>
        {#if shiguangLoading}
          <div class="adapter-loading"><LoaderCircle class="spin" size={20}/> 正在读取学校索引…</div>
        {:else if shiguangError && schools.length===0}
          <div class="adapter-empty adapter-index-error"><TriangleAlert size={16}/> 学校索引读取失败。仍可使用文件导入，或稍后重试。</div>
        {:else}
          {#if showCompatibility}
            <div class="compatibility-panel">
              <div class="compatibility-head"><div><b>没找到学校？智能兼容</b><span>不需要知道正方、青果还是 URP。粘贴学校官方教务网址即可。</span></div><a href={bingUrl}><ExternalLink size={13}/> 搜索官网</a></div>
              <div class="compatibility-url"><input bind:value={compatibilityUrl} inputmode="url" placeholder="https://学校官方教务域名/"/><button on:click={()=>void beginSmartCompatibility()} disabled={shiguangLoading}>智能识别并登录</button></div>
              <button class="advanced-toggle" on:click={()=>advancedCompatibility=!advancedCompatibility}>{advancedCompatibility?'收起高级选项':'高级选项：手动选择教务类型'}</button>
              {#if advancedCompatibility}
                <div class="compatibility-families">{#each compatibilityFamilies as family}<button class:active={compatibilityFamily===family.id} on:click={()=>compatibilityFamily=family.id}>{family.label}</button>{/each}</div>
                <button class="manual-open" on:click={()=>void beginManualCompatibility()} disabled={shiguangLoading}>按所选类型打开</button>
              {/if}
              <small><ShieldCheck size={12}/> 只粘贴你确认过的学校官方域名。搜索结果不会自动获得登录权限。</small>
            </div>
          {/if}
          <div class="school-list">{#each filteredSchools as school}<button on:click={()=>void chooseSchool(school)}><span>{school.initial.slice(0,1)||'校'}</span><div><b>{school.name}</b></div><ChevronRight size={16}/></button>{/each}{#if normalizedSchoolQuery && !filteredSchools.length && !showCompatibility}<div class="adapter-empty">没有找到匹配学校，可以换关键词重试。</div>{/if}</div>
        {/if}
      {/if}
      {#if shiguangError && schools.length>0}<div class="adapter-error"><X size={15}/> {shiguangError}</div>{/if}
      <div class="adapter-sandbox-note"><ShieldCheck size={16}/><span>已收录学校优先使用专用登录适配；未收录时使用智能兼容。登录始终在受限教务 WebView 中完成。</span></div>
    </div>
  </div>
{/if}

<ConfirmSheet
  open={overwriteConfirmOpen}
  title="覆盖当前课表"
  message={`将用本次导入替换当前课表${importDiff?.removeCount ? `，预计移除 ${importDiff.removeCount} 个旧时段` : ''}。建议重要课表先导出备份。`}
  confirmText="确认覆盖"
  danger
  busy={loading}
  on:cancel={()=>overwriteConfirmOpen=false}
  on:confirm={()=>void commitWithMode('overwrite')}
/>

<style>
  .diff-loading { margin:12px 0; min-height:44px; border-radius:14px; display:flex; align-items:center; justify-content:center; gap:8px; color:rgba(60,60,67,.62); background:rgba(118,118,128,.06); font-size:11px; }
  .diff-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin:14px 0; }
  .diff-grid > div { min-height:66px; border-radius:15px; padding:11px; background:rgba(118,118,128,.06); display:flex; flex-direction:column; justify-content:center; }
  .diff-grid b { font-size:20px; letter-spacing:-.04em; }
  .diff-grid span { margin-top:3px; color:rgba(60,60,67,.55); font-size:9px; }
  .diff-grid .warn { background:rgba(221,153,58,.10); color:#9d6518; }
  .import-mode-picker { margin:0 0 14px; padding:14px; border:1px solid rgba(60,60,67,.07); border-radius:18px; background:rgba(118,118,128,.035); display:grid; gap:10px; }
  .import-mode-picker > div:first-child { display:flex; align-items:baseline; justify-content:space-between; gap:12px; }
  .import-mode-picker > div:first-child b { font-size:11px; }
  .import-mode-picker > div:first-child span { color:rgba(60,60,67,.52); font-size:9px; }
  .mode-options { display:grid; grid-template-columns:repeat(3,1fr); gap:7px; }
  .mode-options button { min-height:62px; border:1px solid rgba(118,118,128,.12); border-radius:14px; background:rgba(255,255,255,.38); color:inherit; text-align:left; padding:10px; }
  .mode-options button b,.mode-options button small { display:block; }
  .mode-options button b { font-size:11px; }
  .mode-options button small { margin-top:3px; font-size:8px; line-height:1.4; color:rgba(60,60,67,.53); }
  .mode-options button.active { border-color:rgba(91,86,214,.30); background:rgba(91,86,214,.09); }
  .mode-options button.active b { color:#5751c9; }
  .mode-options button:disabled { opacity:.42; }
  .conflict-note,.overwrite-note { border-radius:12px; padding:9px 10px; font-size:9px; line-height:1.5; }
  .conflict-note { display:flex; align-items:flex-start; gap:6px; color:#93631d; background:rgba(221,153,58,.09); }
  .overwrite-note { color:#a24857; background:rgba(220,70,84,.07); }
  .compatibility-panel { flex:0 0 auto; display:grid; gap:9px; padding:12px; border:1px solid rgba(91,86,214,.13); border-radius:17px; background:rgba(91,86,214,.055); }
  .compatibility-head { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; }
  .compatibility-head > div { min-width:0; }
  .compatibility-head b,.compatibility-head span { display:block; }
  .compatibility-head b { font-size:11px; }
  .compatibility-head span { margin-top:2px; color:rgba(60,60,67,.56); font-size:9px; line-height:1.4; }
  .compatibility-head a { flex:none; min-height:29px; display:inline-flex; align-items:center; gap:5px; padding:0 9px; border-radius:999px; background:rgba(255,255,255,.48); color:#5751c9; text-decoration:none; font-size:9px; }
  .compatibility-families { display:flex; flex-wrap:wrap; gap:6px; }
  .compatibility-families button { min-height:30px; padding:0 11px; border:1px solid rgba(91,86,214,.15); border-radius:999px; background:rgba(255,255,255,.45); color:inherit; font-size:10px; }
  .compatibility-families button.active { color:#514bd0; border-color:rgba(91,86,214,.32); background:rgba(91,86,214,.13); }
  .compatibility-url { display:grid; grid-template-columns:1fr auto; gap:7px; }
  .compatibility-url input { min-width:0; min-height:42px; border:1px solid rgba(60,60,67,.08); border-radius:12px; background:rgba(255,255,255,.58); padding:0 11px; color:inherit; outline:none; font-size:10px; }
  .compatibility-url button,.manual-open { min-height:42px; border:0; border-radius:12px; padding:0 13px; background:#5b56d6; color:white; font-size:10px; font-weight:650; }
  .advanced-toggle { justify-self:start; border:0; background:transparent; padding:2px 0; color:#5b56d6; font-size:9px; }
  .manual-open { justify-self:start; min-height:34px; background:rgba(91,86,214,.12); color:#514bd0; }
  .compatibility-panel > small { display:flex; align-items:flex-start; gap:5px; color:rgba(60,60,67,.5); font-size:8px; line-height:1.45; }
  .adapter-index-error { display:flex; align-items:center; justify-content:center; gap:7px; }
  .adapter-browser { transition: transform .22s cubic-bezier(.2,.75,.25,1); will-change: transform; }
  .adapter-browser.sheet-dragging { transition:none; }
  .sheet-grabber { touch-action:none; cursor:grab; padding:9px 10px; margin:-9px auto -7px; background-clip:content-box; box-sizing:content-box; }
  .sheet-grabber:active { cursor:grabbing; }
  @media (max-width:760px) {
    .diff-grid { grid-template-columns:repeat(2,1fr); }
    .mode-options { grid-template-columns:1fr; }
    .mode-options button { min-height:52px; }
    .compatibility-head { align-items:center; }
    .compatibility-url { grid-template-columns:1fr; }
  }
</style>
