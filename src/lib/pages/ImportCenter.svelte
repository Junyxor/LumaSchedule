<script lang="ts">
  import { ArrowLeft, BookOpenCheck, Building2, CalendarSync, CheckCircle2, ChevronRight, FileJson2, FileSpreadsheet, Globe2, LoaderCircle, Search, ShieldCheck, TriangleAlert, UploadCloud, X } from 'lucide-svelte';
  import { closeShiguangSession, commitImport, getShiguangSession, importText, listShiguangAdapters, listShiguangSchools, startShiguangImport } from '../tauri';
  import type { ImportBundle, ShiguangAdapter, ShiguangImportStart, ShiguangSchool } from '../types';
  import { createEventDispatcher, onDestroy, tick } from 'svelte';

  const dispatch = createEventDispatcher<{ imported: void }>();
  const sources = [
    { icon: Globe2, title: '高校教务系统', subtitle: '搜索学校并登录官方教务系统自动导入', tag: '推荐', accent: 'violet', action: 'shiguang' },
    { icon: FileJson2, title: 'WakeUp / JSON / CSES', subtitle: '导入分享文本、备份 JSON 与 CSES YAML', tag: '兼容', accent: 'amber', action: 'file' },
    { icon: FileSpreadsheet, title: 'CSV / TSV', subtitle: '导入结构化表格课表', tag: '表格', accent: 'green', action: 'file' },
    { icon: BookOpenCheck, title: 'ICS / iCalendar', subtitle: '导入标准 iCalendar 课表文件', tag: '跨平台', accent: 'blue', action: 'file' }
  ];
  const genericSchoolIds = new Set(['zhengfang_jiaowu', 'chaoxing_jiaowu', 'qingguo_jiaowu', 'urp_jiaowu']);

  let fileInput: HTMLInputElement;
  let schoolSearchInput: HTMLInputElement;
  let loading = false;
  let dragOver = false;
  let preview: ImportBundle | null = null;
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

  $: normalizedSchoolQuery = schoolQuery.trim().toLowerCase();
  $: genericSchools = schools.filter((school) => genericSchoolIds.has(school.id));
  $: directSchoolCount = schools.filter((school) => school.id !== 'GLOBAL_TOOLS' && !genericSchoolIds.has(school.id)).length;
  $: filteredSchools = schools
    .filter((school) => school.id !== 'GLOBAL_TOOLS' && !genericSchoolIds.has(school.id))
    .filter((school) => !normalizedSchoolQuery || school.name.toLowerCase().includes(normalizedSchoolQuery) || school.id.toLowerCase().includes(normalizedSchoolQuery) || school.initial.toLowerCase().includes(normalizedSchoolQuery))
    .sort((a,b) => Number(b.id === 'GDUT') - Number(a.id === 'GDUT') || a.initial.localeCompare(b.initial, 'zh-CN'))
    .slice(0,100);

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

  async function loadFile(file?: File) {
    if (!file) return;
    error=''; committed=''; preview=null; selectedFile=file.name;
    const format=formatFor(file.name);
    if (!['json','ics','csv','tsv','cses'].includes(format)) {
      error = `暂不识别 .${format || 'unknown'} 文件。`;
      return;
    }
    loading=true;
    try { preview = await importText(format, await file.text()); }
    catch(e) { error=friendlyError(e); }
    finally { loading=false; }
  }

  async function commitPreview() {
    if(!preview)return;
    loading=true; error='';
    try {
      const result=await commitImport(preview);
      committed=`已创建新课表：${result.courseCount} 门课程 / ${result.meetingCount} 个上课时段。`;
      preview=null;
      dispatch('imported');
    } catch(e){ error=friendlyError(e); }
    finally { loading=false; }
  }

  function onDrop(event: DragEvent){ event.preventDefault(); dragOver=false; loadFile(event.dataTransfer?.files?.[0]); }

  async function openShiguang(){
    shiguangOpen=true;
    shiguangError='';
    await tick();
    schoolSearchInput?.focus({ preventScroll: true });
    if(schools.length)return;
    shiguangLoading=true;
    try{ schools=await listShiguangSchools(); }
    catch(e){ shiguangError=friendlyError(e); }
    finally{ shiguangLoading=false; }
  }

  function dismissShiguang() {
    shiguangOpen = false;
    if (!activeSession) {
      selectedSchool = null;
      adapters = [];
      shiguangError = '';
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && shiguangOpen) dismissShiguang();
  }

  async function chooseSchool(school: ShiguangSchool){
    selectedSchool=school; adapters=[]; shiguangError=''; shiguangLoading=true;
    try{ adapters=await listShiguangAdapters(school.id); }
    catch(e){ shiguangError=friendlyError(e); }
    finally{ shiguangLoading=false; }
  }

  function backToSchools(){ selectedSchool=null; adapters=[]; shiguangError=''; tick().then(() => schoolSearchInput?.focus({ preventScroll: true })); }

  async function beginShiguang(adapter: ShiguangAdapter){
    if(!selectedSchool||shiguangLoading)return;
    shiguangError=''; shiguangLoading=true; sessionMessage='正在准备隔离的教务登录环境…';
    try{
      activeSession=await startShiguangImport(selectedSchool.id,adapter.adapterId);
      sessionMessage='登录窗口已打开。完成登录后会自动读取课程。';
      pollShiguang(activeSession.sessionId);
    } catch(e){ shiguangError=friendlyError(e); sessionMessage=''; }
    finally{ shiguangLoading=false; }
  }

  async function pollShiguang(sessionId:string){
    if(pollTimer)clearTimeout(pollTimer);
    try{
      const state=await getShiguangSession(sessionId);
      sessionMessage=state.message||(state.status==='collecting'?'正在整理课程与作息…':'等待教务登录完成…');
      if(state.status==='complete'&&state.bundle){
        preview=state.bundle;
        selectedFile=`${state.schoolName} · ${state.adapterName}`;
        activeSession=null; shiguangOpen=false; selectedSchool=null; adapters=[];
        await closeShiguangSession(sessionId).catch(()=>undefined);
        return;
      }
      if(state.status==='error'){
        shiguangError=state.message||'拾光适配器执行失败。';
        activeSession=null;
        await closeShiguangSession(sessionId).catch(()=>undefined);
        return;
      }
      pollTimer=setTimeout(()=>pollShiguang(sessionId),900);
    }catch{
      sessionMessage='正在重新连接导入会话…';
      pollTimer=setTimeout(()=>pollShiguang(sessionId),1500);
    }
  }

  function sourceAction(action:string){ if(action==='shiguang')openShiguang(); else if(action==='file')fileInput.click(); }
  onDestroy(()=>{if(pollTimer)clearTimeout(pollTimer);});
</script>

<svelte:window on:keydown={handleKeydown} />

<section class="page page-import">
  <header class="topbar"><div><span class="eyebrow">导入</span><h1>添加课表</h1><p>从学校教务或开放格式导入。</p></div></header>

  <div class="import-toolbar glass-panel apple-search">
    <Search size={18}/>
    <input bind:value={schoolQuery} aria-label="搜索学校" placeholder="搜索学校，例如 广东工业大学" on:focus={openShiguang} on:input={()=>{ if(!shiguangOpen) openShiguang(); }}/>
  </div>

  <div class="source-grid apple-source-list">{#each sources as source}<button class="source-card content-surface" on:click={()=>sourceAction(source.action)}><span class="source-icon {source.accent}"><svelte:component this={source.icon} size={21}/></span><div><span class="source-title"><b>{source.title}</b><em>{source.tag}</em></span><p>{source.subtitle}</p></div><ChevronRight size={18}/></button>{/each}</div>

  <div class="import-hero content-surface" role="region" aria-label="课表文件导入" class:drag-over={dragOver} on:dragover={(e)=>{e.preventDefault();dragOver=true;}} on:dragleave={()=>dragOver=false} on:drop={onDrop}>
    <input bind:this={fileInput} class="file-input" type="file" accept=".json,.ics,.ical,.csv,.tsv,.cses,.yaml,.yml" on:change={(e)=>loadFile(e.currentTarget.files?.[0])}/>
    <div class="upload-mark">{#if loading}<LoaderCircle class="spin" size={26}/>{:else}<UploadCloud size={26}/>{/if}</div>
    <div><h2>{loading?'正在解析…':'从文件导入'}</h2><p>支持 JSON / ICS / CSV / TSV / CSES。导入前会先预览。</p></div>
    <button class="primary-button" on:click={()=>fileInput.click()} disabled={loading}>选择文件</button>
  </div>

  {#if committed}<div class="import-result import-success content-surface"><CheckCircle2 size={18}/><div><b>导入完成</b><span>{committed}</span></div></div>{/if}
  {#if error}<div class="import-result import-error content-surface"><X size={18}/><div><b>暂时无法解析 {selectedFile}</b><span>{error}</span></div></div>{/if}

  {#if preview}<article class="import-preview content-surface"><div class="preview-head"><div class="preview-ok"><CheckCircle2 size={20}/></div><div><span class="eyebrow">导入预览</span><h2>已识别 {preview.courses.length} 条课程记录</h2><p>{selectedFile} · 来源 {preview.source}</p></div><button class="icon-ghost" on:click={()=>preview=null} aria-label="关闭导入预览"><X size={18}/></button></div><div class="preview-courses">{#each preview.courses.slice(0,6) as course}<div class="preview-course"><span>周{course.weekday}</span><div><b>{course.name}</b><small>第 {course.startSection}–{course.endSection} 节 · {course.location||'教室未提供'}</small></div><em>{course.weeks.length} 周</em></div>{/each}{#if preview.courses.length>6}<div class="preview-more">还有 {preview.courses.length-6} 条记录</div>{/if}</div><div class="preview-actions"><button class="secondary-button" on:click={()=>preview=null}>取消</button><button class="primary-button" on:click={commitPreview} disabled={loading}>确认导入</button></div></article>{/if}

  <div class="security-note"><ShieldCheck size={18}/><div><b>本地优先</b><span>学校登录在隔离 WebView 中完成，课程先进入预览再写入本地数据库。</span></div></div>
</section>

{#if shiguangOpen}
  <div class="adapter-sheet-backdrop" role="presentation" on:click={(event)=>{if(event.currentTarget===event.target)dismissShiguang();}}>
    <div class="adapter-browser glass-panel refract" role="dialog" aria-modal="true" aria-label="高校适配仓库">
      <div class="sheet-grabber" aria-hidden="true"></div>
      <div class="adapter-browser-head"><div class="adapter-brand"><span><Building2 size={20}/></span><div><span class="eyebrow">高校教务</span><h2>{selectedSchool?selectedSchool.name:'选择学校'}</h2><p>{selectedSchool?'选择教务适配器':schools.length?`${directSchoolCount} 所高校 · ${genericSchools.length} 个通用教务入口`:'在线索引 + 离线快照'}</p></div></div><button class="icon-ghost" on:click={dismissShiguang} aria-label="关闭高校教务搜索"><X size={18}/></button></div>
      {#if activeSession}
        <div class="adapter-session"><div class="session-orbit"><LoaderCircle class="spin" size={22}/></div><div><b>{activeSession.schoolName} · {activeSession.adapterName}</b><span>{sessionMessage}</span><small>允许访问：{activeSession.allowedHosts.join(' · ')}</small>{#if activeSession.insecureTransport}<small class="adapter-http-warning"><TriangleAlert size={13}/> 该校旧教务仍使用 HTTP，请仅在可信网络登录。</small>{/if}</div></div>
      {:else if selectedSchool}
        <div class="adapter-subbar"><button on:click={backToSchools}><ArrowLeft size={15}/> 返回</button><span>{adapters.length} 个适配器</span></div>
        <div class="adapter-list">{#if shiguangLoading}<div class="adapter-loading"><LoaderCircle class="spin" size={20}/> 正在读取适配器…</div>{:else}{#each adapters as adapter}<button class="adapter-row" on:click={()=>beginShiguang(adapter)} disabled={!adapter.importUrl}><span class="adapter-row-icon"><CalendarSync size={18}/></span><div><b>{adapter.adapterName}</b><p>{adapter.description}</p><small>{adapter.category} · {adapter.maintainer}</small></div><ChevronRight size={17}/></button>{/each}{#if !adapters.length}<div class="adapter-empty">这个学校暂时没有可用的网页登录适配器。</div>{/if}{/if}</div>
      {:else}
        <div class="school-search"><Search size={17}/><input bind:this={schoolSearchInput} bind:value={schoolQuery} aria-label="学校名称" placeholder="输入学校名称，例如 广东工业大学"/></div>
        {#if shiguangLoading}<div class="adapter-loading"><LoaderCircle class="spin" size={20}/> 正在读取学校索引…</div>{:else}
          {#if genericSchools.length}
            <div class="generic-adapters"><span>学校没单独适配？尝试通用教务</span><div>{#each genericSchools as school}<button on:click={()=>chooseSchool(school)}>{school.name.replace('-通用教务','')}</button>{/each}</div></div>
          {/if}
          <div class="school-list">{#each filteredSchools as school}<button class:featured-school={school.id==='GDUT'} on:click={()=>chooseSchool(school)}><span>{school.initial.slice(0,1)||'校'}</span><div><b>{school.name}</b><small>{school.id}</small></div>{#if school.id==='GDUT'}<em>已验证</em>{/if}<ChevronRight size={16}/></button>{/each}{#if !filteredSchools.length}<div class="adapter-empty">没有找到匹配学校。可以尝试上方通用教务，或使用 JSON / ICS / CSV 导入。</div>{/if}</div>
        {/if}
      {/if}
      {#if shiguangError}<div class="adapter-error"><X size={15}/> {shiguangError}</div>{/if}
      <div class="adapter-sandbox-note"><ShieldCheck size={16}/><span>适配数据来自开源 shiguang_warehouse；在线优先，离线回退随 App 打包的最近快照。</span></div>
    </div>
  </div>
{/if}

<style>
  .generic-adapters { display: grid; gap: 7px; padding: 0 2px; }
  .generic-adapters > span { font-size: 11px; opacity: .62; }
  .generic-adapters > div { display: flex; flex-wrap: wrap; gap: 6px; }
  .generic-adapters button { min-height: 31px; padding: 0 10px; border: 1px solid rgba(91,86,214,.16); border-radius: 999px; background: rgba(91,86,214,.08); color: inherit; font-size: 11px; }
</style>