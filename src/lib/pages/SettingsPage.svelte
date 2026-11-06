<script lang="ts">
  import { Bell, CalendarRange, Clock3, Cloud, DatabaseBackup, Info, LayoutGrid, Palette, Rows3, Shield, Table2 } from 'lucide-svelte';
  import { onMount } from 'svelte';
  import { api } from '../native/api';
  import AboutSection from '../settings/AboutSection.svelte';
  import AppearanceSection from '../settings/AppearanceSection.svelte';
  import BackupSection from '../settings/BackupSection.svelte';
  import DisplaySection from '../settings/DisplaySection.svelte';
  import PrivacySection from '../settings/PrivacySection.svelte';
  import RemindersSection from '../settings/RemindersSection.svelte';
  import SchedulesSection from '../settings/SchedulesSection.svelte';
  import SubPage from '../settings/SubPage.svelte';
  import TermSection from '../settings/TermSection.svelte';
  import TimesSection from '../settings/TimesSection.svelte';
  import WebDavSection from '../settings/WebDavSection.svelte';
  import WidgetsSection from '../settings/WidgetsSection.svelte';
  import { app } from '../state/app.svelte';
  import { useBack } from '../state/back.svelte';
  import Row from '../ui/Row.svelte';

  type Section = 'schedules' | 'term' | 'times' | 'display' | 'reminders' | 'widgets' | 'appearance' | 'backup' | 'webdav' | 'privacy' | 'about';

  const titles: Record<Section, { title: string; description?: string }> = {
    schedules: { title: '我的课表', description: '切换、重命名或删除课表' },
    term: { title: '学期设置', description: '开学日期决定教学周和提醒时间' },
    times: { title: '作息时间', description: '每一节课的开始和结束时间' },
    display: { title: '课表显示' },
    reminders: { title: '上课提醒' },
    widgets: { title: '桌面小组件' },
    appearance: { title: '外观' },
    backup: { title: '备份与导出' },
    webdav: { title: 'WebDAV 云备份', description: '坚果云、Nextcloud 等支持 WebDAV 的网盘' },
    privacy: { title: '隐私与诊断' },
    about: { title: '关于' }
  };

  let section = $state<Section | null>(null);
  let reminderOn = $state<boolean | null>(null);

  onMount(() => {
    if (app.native) void api.reminderSettings().then((value) => (reminderOn = value.enabled)).catch(() => undefined);
  });

  // Other screens (and settings sub-pages) can deep-link to a section.
  $effect(() => {
    const target = app.settingsTarget;
    if (!target) return;
    if (target in titles) section = target as Section;
    app.settingsTarget = null;
  });

  useBack(() => section !== null, () => close());

  function open(next: Section) {
    section = next;
    window.scrollTo(0, 0);
  }

  function close() {
    section = null;
    window.scrollTo(0, 0);
    if (app.native) void api.reminderSettings().then((value) => (reminderOn = value.enabled)).catch(() => undefined);
  }

  const themeLabel = $derived(app.appearance.theme === 'system' ? '跟随系统' : app.appearance.theme === 'dark' ? '深色' : '浅色');
</script>

{#if section}
  <SubPage title={titles[section].title} description={titles[section].description} onback={close}>
    {#if section === 'schedules'}<SchedulesSection />
    {:else if section === 'term'}<TermSection />
    {:else if section === 'times'}<TimesSection />
    {:else if section === 'display'}<DisplaySection />
    {:else if section === 'reminders'}<RemindersSection />
    {:else if section === 'widgets'}<WidgetsSection />
    {:else if section === 'appearance'}<AppearanceSection />
    {:else if section === 'backup'}<BackupSection />
    {:else if section === 'webdav'}<WebDavSection />
    {:else if section === 'privacy'}<PrivacySection />
    {:else}<AboutSection />
    {/if}
  </SubPage>
{:else}
  <section class="page">
    <header class="page-header">
      <div><h1>设置</h1></div>
    </header>

    <div class="list-caption">课表</div>
    <div class="list">
      <Row icon={Table2} title="我的课表" value={app.semester.scheduleName || (app.semester.hasSchedule ? '当前课表' : '暂无')} onclick={() => open('schedules')} />
      <Row icon={CalendarRange} title="学期设置" value={app.semester.termStart || '未设置开学日期'} onclick={() => open('term')} />
      <Row icon={Clock3} title="作息时间" value={app.semester.timeSlots.length ? `${app.semester.timeSlots.length} 节` : '自动推算'} onclick={() => open('times')} />
      <Row icon={Rows3} title="课表显示" onclick={() => open('display')} />
    </div>

    <div class="list-caption">提醒</div>
    <div class="list">
      <Row icon={Bell} title="上课提醒" value={reminderOn === null ? '' : reminderOn ? '已开启' : '关闭'} onclick={() => open('reminders')} />
      <Row icon={LayoutGrid} title="桌面小组件" onclick={() => open('widgets')} />
    </div>

    <div class="list-caption">外观</div>
    <div class="list">
      <Row icon={Palette} title="主题与配色" value={themeLabel} onclick={() => open('appearance')} />
    </div>

    <div class="list-caption">数据</div>
    <div class="list">
      <Row icon={DatabaseBackup} title="备份与导出" onclick={() => open('backup')} />
      <Row icon={Cloud} title="WebDAV 云备份" onclick={() => open('webdav')} />
    </div>

    <div class="list-caption">其他</div>
    <div class="list">
      <Row icon={Shield} title="隐私与诊断" onclick={() => open('privacy')} />
      <Row icon={Info} title="关于 LumaSchedule" value={app.appVersion ? `v${app.appVersion}` : ''} onclick={() => open('about')} />
    </div>
  </section>
{/if}
