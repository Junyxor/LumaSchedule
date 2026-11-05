<script lang="ts">
  import { ArrowLeft, Building2, ChevronRight, ExternalLink, Globe, Search, ShieldCheck, TriangleAlert } from 'lucide-svelte';
  import { tick, untrack } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { toast } from '../state/toast.svelte';
  import type { CompatibilityFamily, ShiguangAdapter, ShiguangSchool } from '../types';
  import Sheet from '../ui/Sheet.svelte';
  import { importSession } from './session.svelte';

  interface Props {
    open: boolean;
    startInCompat?: boolean;
    onclose: () => void;
  }

  let { open, startInCompat = false, onclose }: Props = $props();

  const HIDDEN_SCHOOLS = new Set(['GLOBAL_TOOLS', 'zhengfang_jiaowu', 'chaoxing_jiaowu', 'qingguo_jiaowu', 'urp_jiaowu']);
  const FAMILIES: { id: CompatibilityFamily; label: string }[] = [
    { id: 'zhengfang_jiaowu', label: '正方' },
    { id: 'qingguo_jiaowu', label: '青果' },
    { id: 'urp_jiaowu', label: 'URP' },
    { id: 'chaoxing_jiaowu', label: '超星' }
  ];

  let schools = $state.raw<ShiguangSchool[]>([]);
  let loadingSchools = $state(false);
  let loadError = $state('');
  let query = $state('');
  let view = $state<'schools' | 'adapters' | 'compat'>('schools');
  let school = $state.raw<ShiguangSchool | null>(null);
  let adapters = $state.raw<ShiguangAdapter[]>([]);
  let loadingAdapters = $state(false);
  let starting = $state(false);
  let compatUrl = $state('');
  let family = $state<CompatibilityFamily>('zhengfang_jiaowu');
  let showFamilies = $state(false);
  let searchInput = $state<HTMLInputElement>();

  const needle = $derived(query.trim().toLowerCase());
  const visibleSchools = $derived(
    schools
      .filter((item) => !HIDDEN_SCHOOLS.has(item.id))
      .filter((item) => !needle || item.name.toLowerCase().includes(needle) || item.id.toLowerCase().includes(needle) || item.initial.toLowerCase() === needle)
      .sort((a, b) => a.initial.localeCompare(b.initial) || a.name.localeCompare(b.name, 'zh-CN'))
  );
  const groups = $derived.by(() => {
    const map = new Map<string, ShiguangSchool[]>();
    for (const item of visibleSchools) {
      const key = (item.initial || '#').slice(0, 1).toUpperCase();
      map.set(key, [...(map.get(key) ?? []), item]);
    }
    return [...map.entries()];
  });
  const searchUrl = $derived(`https://www.bing.com/search?q=${encodeURIComponent(`${query.trim() || '学校'} 教务系统`)}`);

  // Reset only when the sheet opens; the async school load must not re-trigger this.
  $effect(() => {
    if (!open) return;
    const compat = startInCompat;
    untrack(() => {
      view = compat ? 'compat' : 'schools';
      school = null;
      adapters = [];
      void loadSchools();
    });
    void tick().then(() => {
      if (!compat) searchInput?.focus({ preventScroll: true });
    });
  });

  async function loadSchools() {
    if (schools.length || loadingSchools) return;
    loadingSchools = true;
    loadError = '';
    try {
      schools = await api.listSchools();
    } catch (error) {
      loadError = errorMessage(error);
    } finally {
      loadingSchools = false;
    }
  }

  async function choose(item: ShiguangSchool) {
    school = item;
    view = 'adapters';
    adapters = [];
    loadingAdapters = true;
    try {
      adapters = await api.listAdapters(item.id);
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      loadingAdapters = false;
    }
  }

  async function startAdapter(adapter: ShiguangAdapter) {
    if (!school || starting) return;
    starting = true;
    try {
      const started = await api.startAdapterImport(school.id, adapter.adapterId);
      importSession.begin(started, 'schedule', adapter.importUrl);
      onclose();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      starting = false;
    }
  }

  async function startCompat(manual: boolean) {
    if (starting) return;
    const url = compatUrl.trim();
    if (!url) {
      toast.error('先粘贴学校教务系统的网址。');
      return;
    }
    starting = true;
    try {
      const started = manual ? await api.startCompatibilityImport(url, family, query.trim()) : await api.startSmartImport(url, query.trim());
      importSession.begin(started, 'schedule', url);
      onclose();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      starting = false;
    }
  }

  function back() {
    view = 'schools';
    school = null;
  }
</script>

<Sheet {open} size="full" title={view === 'adapters' ? (school?.name ?? '') : view === 'compat' ? '智能兼容导入' : '选择学校'} subtitle={view === 'adapters' ? '选择登录方式' : view === 'compat' ? '学校未收录时使用' : `${visibleSchools.length} 所学校已适配`} {onclose}>
  {#snippet leading()}
    {#if view !== 'schools'}
      <button class="icon-btn" type="button" aria-label="返回学校列表" onclick={back}><ArrowLeft size={20} /></button>
    {/if}
  {/snippet}

  {#if view === 'schools'}
    <label class="search">
      <Search size={18} />
      <input bind:this={searchInput} bind:value={query} placeholder="输入学校名称或拼音首字母" autocomplete="off" enterkeyhint="search" />
    </label>

    {#if loadingSchools}
      <div class="loading"><span class="spinner"></span> 正在读取学校列表…</div>
    {:else if loadError}
      <div class="notice danger"><TriangleAlert size={18} /><span>学校列表读取失败：{loadError}</span></div>
    {:else}
      {#if needle && !visibleSchools.length}
        <div class="notice"><Building2 size={18} /><span>没有找到「{query.trim()}」。可以用学校的教务网址尝试智能兼容。</span></div>
      {/if}
      <button class="compat-entry" type="button" onclick={() => (view = 'compat')}>
        <Globe size={18} />
        <span><b>学校不在列表中？</b>粘贴教务系统网址，自动识别正方、青果、URP、超星等系统</span>
        <ChevronRight size={18} />
      </button>
      {#each groups as [initial, items] (initial)}
        <div class="group-title">{initial}</div>
        <div class="list">
          {#each items as item (item.id)}
            <button class="list-row" type="button" onclick={() => choose(item)}>
              <span class="row-body"><span class="row-title">{item.name}</span></span>
              <ChevronRight class="row-chevron" size={18} />
            </button>
          {/each}
        </div>
      {/each}
    {/if}
  {:else if view === 'adapters'}
    {#if loadingAdapters}
      <div class="loading"><span class="spinner"></span> 正在读取登录方式…</div>
    {:else if !adapters.length}
      <div class="notice"><Building2 size={18} /><span>这所学校暂时没有可用的登录方式，可以返回后使用智能兼容。</span></div>
    {:else}
      <div class="list">
        {#each adapters as adapter (adapter.adapterId)}
          <button class="list-row adapter" type="button" disabled={starting || !adapter.importUrl} onclick={() => startAdapter(adapter)}>
            <span class="row-body">
              <span class="row-title">{adapter.adapterName}</span>
              {#if adapter.description}<span class="row-sub">{adapter.description}</span>{/if}
              {#if adapter.maintainer}<span class="row-sub faint">适配维护：{adapter.maintainer}</span>{/if}
            </span>
            {#if starting}<span class="spinner"></span>{:else}<ChevronRight class="row-chevron" size={18} />{/if}
          </button>
        {/each}
      </div>
      <p class="list-footer">登录在独立的教务窗口中完成，账号密码只交给学校官方页面。读取完成后会先显示预览，确认后才写入课表。</p>
    {/if}
  {:else}
    <div class="stack">
      <label class="field">
        <span class="label">学校名称（可选）</span>
        <input class="input" bind:value={query} placeholder="例如 广东工业大学" autocomplete="off" />
      </label>
      <label class="field">
        <span class="label">教务系统网址</span>
        <input class="input" bind:value={compatUrl} inputmode="url" placeholder="https://jwxt.example.edu.cn/" autocomplete="off" />
        <span class="hint">只填写你确认过的学校官方地址。登录窗口只允许访问这个地址所属学校的域名。</span>
      </label>
      <button class="btn btn-primary btn-lg btn-block" type="button" disabled={starting} onclick={() => startCompat(false)}>
        {#if starting}<span class="spinner"></span>{/if} 智能识别并登录
      </button>
      <a class="btn btn-outline btn-block" href={searchUrl}><ExternalLink size={16} /> 在 Bing 搜索教务网址</a>
      <button class="link" type="button" onclick={() => (showFamilies = !showFamilies)}>{showFamilies ? '收起高级选项' : '高级：手动指定教务系统类型'}</button>
      {#if showFamilies}
        <div class="chip-row">
          {#each FAMILIES as item (item.id)}
            <button class="chip" class:active={family === item.id} type="button" onclick={() => (family = item.id)}>{item.label}</button>
          {/each}
        </div>
        <button class="btn btn-tonal btn-block" type="button" disabled={starting} onclick={() => startCompat(true)}>按所选类型打开</button>
      {/if}
      <div class="notice"><ShieldCheck size={18} /><span>登录后进入「个人课表 / 我的课表」页面并完成查询，再点底部的「读取课表」。</span></div>
    </div>
  {/if}
</Sheet>

<style>
  .search {
    position: sticky;
    top: 0;
    z-index: 2;
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 0 12px;
    padding: 0 14px;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--surface-2);
    color: var(--text-3);
  }

  .search input {
    flex: 1;
    min-width: 0;
    height: 44px;
    border: 0;
    background: transparent;
    color: var(--text);
    font-size: var(--text-base);
    outline: none;
  }

  .loading {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 40px 0;
    color: var(--text-2);
    font-size: var(--text-md);
  }

  .compat-entry {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    margin: 8px 0 4px;
    padding: 12px 14px;
    border: 1px dashed var(--accent-line);
    border-radius: var(--radius-md);
    background: var(--accent-softer);
    color: var(--accent);
    text-align: left;
  }

  .compat-entry span {
    flex: 1;
    color: var(--text-2);
    font-size: var(--text-sm);
    line-height: 1.5;
  }

  .compat-entry b {
    display: block;
    color: var(--text);
    font-size: var(--text-md);
  }

  .group-title {
    margin: 16px 6px 6px;
    color: var(--text-3);
    font-size: var(--text-sm);
    font-weight: 650;
  }

  .notice {
    margin-bottom: 8px;
  }

  .adapter .row-sub {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
    line-clamp: 3;
  }

  .hint {
    color: var(--text-3);
    font-size: var(--text-xs);
    line-height: 1.5;
  }

  .link {
    justify-self: start;
    padding: 4px 2px;
    border: 0;
    background: none;
    color: var(--accent);
    font-size: var(--text-sm);
    font-weight: 550;
  }
</style>
