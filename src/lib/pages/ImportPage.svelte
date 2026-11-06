<script lang="ts">
  import { CalendarPlus, FileUp, Globe, Search, ShieldCheck, TriangleAlert, X } from 'lucide-svelte';
  import { errorMessage } from '../native/bridge';
  import { IMPORT_ACCEPT, readImportFile } from '../import/parsers';
  import PreviewSheet from '../import/PreviewSheet.svelte';
  import SchoolSheet from '../import/SchoolSheet.svelte';
  import { importSession } from '../import/session.svelte';
  import { courseSheets } from '../schedule/courseSheets.svelte';
  import { toast } from '../state/toast.svelte';
  import type { ImportBundle } from '../types';
  import Row from '../ui/Row.svelte';

  let fileInput = $state<HTMLInputElement>();
  let parsing = $state(false);
  let schoolOpen = $state(false);
  let compatMode = $state(false);
  let preview = $state.raw<{ bundle: ImportBundle; label: string } | null>(null);

  const session = $derived(importSession.active?.kind === 'schedule' ? importSession.active : null);

  // A finished login session hands its result to this page.
  $effect(() => {
    const result = importSession.scheduleResult;
    if (!result) return;
    preview = result;
    importSession.scheduleResult = null;
  });

  function openSchools(compat = false) {
    compatMode = compat;
    schoolOpen = true;
  }

  function pickFile() {
    if (parsing || !fileInput) return;
    fileInput.value = '';
    fileInput.click();
  }

  async function loadFile(file: File | undefined) {
    if (!file) return;
    parsing = true;
    try {
      const bundle = await readImportFile(file);
      preview = { bundle, label: `${file.name} · ${bundle.source}` };
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      parsing = false;
    }
  }
</script>

<section class="page">
  <header class="page-header">
    <div>
      <h1>导入课表</h1>
      <p>从学校教务系统或课表文件导入，写入前会先预览。</p>
    </div>
  </header>

  <button class="search" type="button" onclick={() => openSchools(false)}>
    <Search size={19} />
    <span>搜索学校，登录教务系统导入</span>
  </button>

  {#if session}
    <div class="card session">
      <div class="session-head">
        <span class="spinner"></span>
        <div>
          <b>{session.schoolName} · {session.adapterName}</b>
          <span>{importSession.message}</span>
        </div>
        <button class="icon-btn" type="button" aria-label="取消导入" onclick={() => importSession.cancel()}><X size={18} /></button>
      </div>
      {#if session.insecureTransport}
        <div class="notice warning"><TriangleAlert size={16} /><span>这个教务系统使用不加密的 HTTP，请只在可信网络中登录。</span></div>
      {/if}
      <p class="hosts">允许访问：{session.allowedHosts.join('、')}</p>
    </div>
  {/if}

  <div class="list-caption">从文件导入</div>
  <div class="card file-card">
    <div class="file-head">
      <span class="file-icon"><FileUp size={22} /></span>
      <div>
        <b>选择课表文件</b>
        <span>自动识别格式，支持 GBK 编码的表格</span>
      </div>
    </div>
    <div class="formats">
      <span class="badge">WakeUp 备份</span>
      <span class="badge">JSON</span>
      <span class="badge">ICS 日历</span>
      <span class="badge">CSV / TSV</span>
      <span class="badge">CSES</span>
    </div>
    <button class="btn btn-primary btn-block" type="button" onclick={pickFile} disabled={parsing}>
      {#if parsing}<span class="spinner"></span> 正在解析…{:else}选择文件{/if}
    </button>
    <input bind:this={fileInput} class="visually-hidden" type="file" accept={IMPORT_ACCEPT} onchange={(event) => loadFile(event.currentTarget.files?.[0])} />
  </div>
  <p class="list-footer">WakeUp 课程表：在 WakeUp 中选择「导出为文件」，再导入得到的 .wakeup_schedule 文件。表格至少需要课程名称、星期和节次（或开始时间）列。</p>

  <div class="list-caption">其他方式</div>
  <div class="list">
    <Row icon={Globe} title="学校未收录" sub="粘贴教务系统网址，尝试智能兼容导入" onclick={() => openSchools(true)} />
    <Row icon={CalendarPlus} title="手动添加课程" sub="逐门添加，也可以在课表里点空白格子" onclick={() => courseSheets.create()} />
  </div>

  <div class="notice privacy">
    <ShieldCheck size={18} />
    <span>教务登录在独立窗口中完成，只能访问该学校的域名。LumaSchedule 不保存账号密码，课表只存放在本机。</span>
  </div>
</section>

<SchoolSheet open={schoolOpen} startInCompat={compatMode} onclose={() => (schoolOpen = false)} />
<PreviewSheet bundle={preview?.bundle ?? null} label={preview?.label ?? ''} onclose={() => (preview = null)} />

<style>
  .search {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    min-height: 50px;
    padding: 0 16px;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
    color: var(--text-3);
    font-size: var(--text-base);
    text-align: left;
  }

  .search:active {
    background: var(--surface-2);
  }

  .session {
    display: grid;
    gap: 10px;
    margin-top: 12px;
    border-color: var(--accent-line);
  }

  .session-head {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .session-head > div {
    flex: 1;
    min-width: 0;
  }

  .session-head b {
    display: block;
    font-size: var(--text-md);
  }

  .session-head span {
    display: block;
    color: var(--text-2);
    font-size: var(--text-sm);
    line-height: 1.5;
  }

  .hosts {
    color: var(--text-3);
    font-size: var(--text-xs);
    word-break: break-all;
  }

  .file-card {
    display: grid;
    gap: 14px;
  }

  .file-head {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .file-icon {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: var(--radius-md);
    background: var(--accent-soft);
    color: var(--accent);
  }

  .file-head b {
    display: block;
    font-size: var(--text-base);
  }

  .file-head span {
    color: var(--text-2);
    font-size: var(--text-sm);
  }

  .formats {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .privacy {
    margin-top: 20px;
  }
</style>
