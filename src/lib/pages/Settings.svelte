<script lang="ts">
  import { Bell, CalendarRange, Cloud, Database, Download, ExternalLink, Github, Info, MessageCircle, Palette, RefreshCw, Shield, Upload } from 'lucide-svelte';
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import type { CourseReminderSettings, GlassSettings, SchedulePreferences, UpdateCheckResult, WebDavCredentials, WebDavProfile, WeekendMode } from '../types';
  import {
    checkForUpdates,
    ensureNotificationPermission,
    getBootstrap,
    getCourseReminderSettings,
    getWebDavProfile,
    restoreFullBackupFromFile,
    restoreWebDavBackup,
    saveCourseReminderSettings,
    saveFullBackup,
    saveGlassSettings,
    saveLatestScheduleIcs,
    saveLatestScheduleJson,
    saveSchedulePreferences,
    saveWebDavProfile,
    scheduleTestReminder,
    syncCourseReminders,
    testNotification,
    testWebDav,
    uploadWebDavBackup
  } from '../tauri';

  export let glass: GlassSettings;
  export let preferences: SchedulePreferences;
  const dispatch = createEventDispatcher<{ changed: void }>();

  type NumericGlassKey = Exclude<keyof GlassSettings, 'motion'>;
  type ConfirmAction = 'restore-local' | 'restore-webdav' | null;
  type SettingsSection = 'schedule' | 'reminders' | 'appearance' | 'data' | 'about';

  const sections: { id: SettingsSection; label: string; hint: string; icon: typeof CalendarRange }[] = [
    { id: 'schedule', label: '学期与课表', hint: '开学日期、周数与显示', icon: CalendarRange },
    { id: 'reminders', label: '提醒', hint: '上课前通知', icon: Bell },
    { id: 'appearance', label: '外观', hint: 'Liquid Glass', icon: Palette },
    { id: 'data', label: '数据与备份', hint: 'WebDAV、导入导出', icon: Database },
    { id: 'about', label: '关于与反馈', hint: '更新、Issue、隐私', icon: Info }
  ];

  let activeSection: SettingsSection = 'schedule';
  let glassSaveTimer: ReturnType<typeof setTimeout> | null = null;
  let webdav: WebDavProfile = { baseUrl: '', username: '', remotePath: 'LumaSchedule/lumaschedule-latest.luma.json' };
  let webdavPassword = '';
  let webdavBusy = false;
  let webdavStatus = '';
  let dataStatus = '';
  let dataBusy = false;
  let reminder: CourseReminderSettings = { enabled: false, offsetMinutes: 15 };
  let reminderBusy = false;
  let reminderStatus = '';
  let scheduleBusy = false;
  let scheduleStatus = '';
  let scheduleDraft: SchedulePreferences = { ...preferences };
  let confirmAction: ConfirmAction = null;
  let appVersion = '—';
  let updateBusy = false;
  let updateStatus = '';
  let updateResult: UpdateCheckResult | null = null;

  const reminderOffsets = [5, 10, 15, 20, 30, 60];
  const weekendModes: { value: WeekendMode; label: string; hint: string }[] = [
    { value: 'auto', label: '自动', hint: '只显示当前周实际有课的周末列' },
    { value: 'weekdays', label: '工作日', hint: '始终只显示周一至周五' },
    { value: 'sat', label: '仅周六', hint: '固定显示周六，不显示周日' },
    { value: 'sun', label: '仅周日', hint: '固定显示周日，不显示周六' },
    { value: 'both', label: '周六+周日', hint: '始终显示完整七天' }
  ];

  const bugIssueUrl = 'https://github.com/Junyxor/LumaSchedule/issues/new?template=bug_report.yml';
  const featureIssueUrl = 'https://github.com/Junyxor/LumaSchedule/issues/new?template=feature_request.yml';

  onMount(async () => {
    scheduleDraft = { ...preferences, weekendMode: preferences.weekendMode ?? (preferences.showWeekend === false ? 'weekdays' : 'auto') };
    const [profileResult, reminderResult, bootstrapResult] = await Promise.allSettled([
      getWebDavProfile(),
      getCourseReminderSettings(),
      getBootstrap()
    ]);
    if (profileResult.status === 'fulfilled') webdav = profileResult.value;
    if (reminderResult.status === 'fulfilled') reminder = reminderResult.value;
    if (bootstrapResult.status === 'fulfilled') appVersion = bootstrapResult.value.appVersion;
  });

  onDestroy(() => {
    if (glassSaveTimer) clearTimeout(glassSaveTimer);
    void saveGlassSettings(glass);
  });

  function queueGlassSave() {
    localStorage.setItem('luma.glass', JSON.stringify(glass));
    if (glassSaveTimer) clearTimeout(glassSaveTimer);
    glassSaveTimer = setTimeout(() => void saveGlassSettings(glass), 180);
  }

  function updateGlass(key: NumericGlassKey, value: number) {
    glass = { ...glass, [key]: value };
    queueGlassSave();
  }

  function toggleMotion() {
    glass = { ...glass, motion: !glass.motion };
    queueGlassSave();
  }

  function toggleSchedule(key: 'showTeacher' | 'showRoom' | 'showTime' | 'compactMode') {
    scheduleDraft = { ...scheduleDraft, [key]: !scheduleDraft[key] };
  }

  function setWeekendMode(mode: WeekendMode) {
    scheduleDraft = { ...scheduleDraft, weekendMode: mode };
  }

  async function saveSchedule() {
    if (scheduleBusy) return;
    scheduleBusy = true;
    scheduleStatus = '';
    try {
      scheduleDraft = await saveSchedulePreferences({
        ...scheduleDraft,
        termName: scheduleDraft.termName.trim(),
        termStart: scheduleDraft.termStart.trim(),
        timezone: scheduleDraft.timezone.trim(),
        weekCount: Number(scheduleDraft.weekCount),
        weekStartsOn: Number(scheduleDraft.weekStartsOn),
        defaultSections: Number(scheduleDraft.defaultSections)
      });
      scheduleStatus = '学期与课表设置已保存。';
      dispatch('changed');
      window.dispatchEvent(new CustomEvent('luma-data-changed'));
    } catch (error) {
      scheduleStatus = error instanceof Error ? error.message : String(error);
    } finally {
      scheduleBusy = false;
    }
  }

  function credentials(): WebDavCredentials { return { ...webdav, password: webdavPassword }; }

  function reminderSummary(futureCount: number, scheduledCount = 0, cancelledCount = 0) {
    const changes = [scheduledCount ? `新增 ${scheduledCount}` : '', cancelledCount ? `取消 ${cancelledCount}` : ''].filter(Boolean).join('，');
    return `已安排 ${futureCount} 个未来课程提醒${changes ? `（${changes}）` : ''}。`;
  }

  async function setReminderEnabled() {
    if (reminderBusy) return;
    reminderStatus = '';
    reminderBusy = true;
    try {
      const enabled = !reminder.enabled;
      if (enabled && !(await ensureNotificationPermission())) {
        reminderStatus = '没有获得系统通知权限，课程提醒未开启。';
        return;
      }
      reminder = await saveCourseReminderSettings({ ...reminder, enabled });
      const report = await syncCourseReminders();
      reminderStatus = enabled
        ? reminderSummary(report.futureCount, report.scheduledCount, report.cancelledCount)
        : `课程提醒已关闭，取消 ${report.cancelledCount} 个未来提醒。`;
    } catch (error) {
      reminderStatus = error instanceof Error ? error.message : String(error);
    } finally {
      reminderBusy = false;
    }
  }

  async function setReminderOffset(offsetMinutes: number) {
    if (reminderBusy || reminder.offsetMinutes === offsetMinutes) return;
    reminderStatus = '';
    reminderBusy = true;
    try {
      reminder = await saveCourseReminderSettings({ ...reminder, offsetMinutes });
      if (reminder.enabled) {
        const report = await syncCourseReminders();
        reminderStatus = reminderSummary(report.futureCount, report.scheduledCount, report.cancelledCount);
      } else {
        reminderStatus = `默认提前 ${offsetMinutes} 分钟，开启课程提醒后生效。`;
      }
    } catch (error) {
      reminderStatus = error instanceof Error ? error.message : String(error);
    } finally {
      reminderBusy = false;
    }
  }

  async function resyncReminders() {
    if (reminderBusy) return;
    reminderStatus = '';
    reminderBusy = true;
    try {
      if (reminder.enabled && !(await ensureNotificationPermission())) {
        reminderStatus = '没有获得系统通知权限。';
        return;
      }
      const report = await syncCourseReminders();
      reminderStatus = report.enabled
        ? reminderSummary(report.futureCount, report.scheduledCount, report.cancelledCount)
        : '课程提醒当前处于关闭状态。';
    } catch (error) {
      reminderStatus = error instanceof Error ? error.message : String(error);
    } finally {
      reminderBusy = false;
    }
  }

  async function withWebDav(action: 'test' | 'upload' | 'restore') {
    if (action === 'restore') {
      confirmAction = 'restore-webdav';
      return;
    }
    webdavStatus = ''; webdavBusy = true;
    try {
      if (!webdav.baseUrl.trim()) throw new Error('先填写 WebDAV 地址。');
      if (!webdav.remotePath.trim()) throw new Error('先填写远程备份路径。');
      await saveWebDavProfile(webdav);
      const result = action === 'test' ? await testWebDav(credentials()) : await uploadWebDavBackup(credentials());
      webdavStatus = result.message;
    } catch (error) { webdavStatus = error instanceof Error ? error.message : String(error); }
    finally { webdavBusy = false; }
  }

  async function restoreWebDavConfirmed() {
    confirmAction = null;
    webdavStatus = ''; webdavBusy = true;
    try {
      if (!webdav.baseUrl.trim()) throw new Error('先填写 WebDAV 地址。');
      if (!webdav.remotePath.trim()) throw new Error('先填写远程备份路径。');
      await saveWebDavProfile(webdav);
      const result = await restoreWebDavBackup(credentials());
      webdavStatus = result.message;
      window.dispatchEvent(new CustomEvent('luma-data-changed'));
    } catch (error) { webdavStatus = error instanceof Error ? error.message : String(error); }
    finally { webdavBusy = false; }
  }

  async function exportData(kind: 'json' | 'ics' | 'backup') {
    dataStatus = ''; dataBusy = true;
    try {
      const saved = kind === 'json' ? await saveLatestScheduleJson() : kind === 'ics' ? await saveLatestScheduleIcs() : await saveFullBackup();
      dataStatus = saved ? '已保存到你选择的位置。' : '已取消保存。';
    } catch (error) { dataStatus = error instanceof Error ? error.message : String(error); }
    finally { dataBusy = false; }
  }

  function restoreData() { confirmAction = 'restore-local'; }

  async function restoreDataConfirmed() {
    confirmAction = null;
    dataStatus = '';
    dataBusy = true;
    try {
      const result = await restoreFullBackupFromFile();
      if (!result) dataStatus = '已取消恢复。';
      else {
        dataStatus = `恢复完成：${result.courseCount} 门课程 / ${result.meetingCount} 个时段。`;
        window.dispatchEvent(new CustomEvent('luma-data-changed'));
      }
    } catch (error) { dataStatus = error instanceof Error ? error.message : String(error); }
    finally { dataBusy = false; }
  }

  async function checkUpdate() {
    if (updateBusy) return;
    updateBusy = true;
    updateStatus = '正在检查 GitHub Release…';
    try {
      updateResult = await checkForUpdates();
      updateStatus = updateResult.updateAvailable
        ? `发现新版本 v${updateResult.latestVersion}`
        : `已是最新版本 v${updateResult.currentVersion}`;
    } catch (error) {
      updateResult = null;
      updateStatus = error instanceof Error ? error.message : String(error);
    } finally {
      updateBusy = false;
    }
  }
</script>

<section class="page page-settings">
  <header class="topbar"><div><span class="eyebrow">设置</span><h1>设置</h1><p>按栏目管理学期、提醒、外观、数据与应用信息。</p></div></header>

  <nav class="settings-sections glass-panel" aria-label="设置栏目">
    {#each sections as section}
      <button class:active={activeSection === section.id} on:click={() => (activeSection = section.id)}>
        <span><svelte:component this={section.icon} size={18}/></span>
        <div><b>{section.label}</b><small>{section.hint}</small></div>
      </button>
    {/each}
  </nav>

  {#if activeSection === 'schedule'}
    <div class="settings-section-stack">
      <article class="settings-card glass-panel">
        <div class="settings-title"><span><CalendarRange size={19}/></span><div><b>学期设置</b><p>开学日期用于自动计算当前教学周和每周日期。</p></div></div>
        <div class="schedule-form">
          <label class="wide"><span>学期名称</span><input bind:value={scheduleDraft.termName} placeholder="例如 2026-2027 学年第一学期" /></label>
          <label><span>开学日期</span><input type="date" bind:value={scheduleDraft.termStart} /></label>
          <label><span>总周数</span><input type="number" min="1" max="64" bind:value={scheduleDraft.weekCount} /></label>
          <label><span>每周起始</span><select bind:value={scheduleDraft.weekStartsOn}><option value={1}>周一</option><option value={7}>周日</option></select></label>
          <label><span>时区</span><input bind:value={scheduleDraft.timezone} placeholder="Asia/Shanghai" /></label>
        </div>
      </article>

      <article class="settings-card glass-panel">
        <div class="settings-title"><span><CalendarRange size={19}/></span><div><b>课表显示</b><p>只调整你真正会看到的周视图信息。</p></div></div>
        <label class="inline-number"><span>默认显示节数</span><input type="number" min="8" max="30" bind:value={scheduleDraft.defaultSections}/></label>
        <div class="weekend-setting"><div><b>周末列</b><span>{weekendModes.find((item) => item.value === scheduleDraft.weekendMode)?.hint}</span></div><div class="weekend-segments">{#each weekendModes as mode}<button class:active={scheduleDraft.weekendMode===mode.value} on:click={()=>setWeekendMode(mode.value)}>{mode.label}</button>{/each}</div></div>
        <div class="display-toggles">
          <button class="toggle-row" on:click={()=>toggleSchedule('showTeacher')}><span><b>显示教师</b><small>今日页和周课表显示教师信息</small></span><i class:on={scheduleDraft.showTeacher}></i></button>
          <button class="toggle-row" on:click={()=>toggleSchedule('showRoom')}><span><b>显示教室</b><small>关闭后隐藏教室信息</small></span><i class:on={scheduleDraft.showRoom}></i></button>
          <button class="toggle-row" on:click={()=>toggleSchedule('showTime')}><span><b>显示具体时间</b><small>关闭后优先显示节次</small></span><i class:on={scheduleDraft.showTime}></i></button>
          <button class="toggle-row" on:click={()=>toggleSchedule('compactMode')}><span><b>紧凑模式</b><small>缩短周课表行高</small></span><i class:on={scheduleDraft.compactMode}></i></button>
        </div>
        <div class="settings-actions schedule-save"><button on:click={saveSchedule} disabled={scheduleBusy}>{scheduleBusy?'正在保存…':'保存学期与课表设置'}</button></div>
        {#if scheduleStatus}<div class="settings-status">{scheduleStatus}</div>{/if}
      </article>
    </div>

  {:else if activeSection === 'reminders'}
    <div class="settings-section-stack">
      <article class="settings-card glass-panel">
        <div class="settings-title"><span><Bell size={19}/></span><div><b>课程提醒</b><p>由 Android 原生 AlarmManager 调度。</p></div></div>
        <button class="toggle-row" on:click={setReminderEnabled} disabled={reminderBusy}><span><b>上课前提醒</b><small>{reminder.enabled?`每节课提前 ${reminder.offsetMinutes} 分钟`:'当前关闭'}</small></span><i class:on={reminder.enabled}></i></button>
        <div class="reminder-offsets">{#each reminderOffsets as offset}<button class:active={reminder.offsetMinutes===offset} on:click={()=>setReminderOffset(offset)} disabled={reminderBusy}>{offset===60?'1 小时':`${offset} 分钟`}</button>{/each}</div>
        <button class="setting-row" on:click={resyncReminders} disabled={reminderBusy}><div><b>重新同步未来提醒</b><span>课表变化后重新计算</span></div><em>同步</em></button>
        <button class="setting-row" on:click={testNotification}><div><b>即时测试通知</b><span>验证通知权限与通知渠道</span></div><em>立即</em></button>
        <button class="setting-row" on:click={()=>scheduleTestReminder(60_000)}><div><b>后台定时测试</b><span>1 分钟后由系统触发</span></div><em>1 分钟</em></button>
        {#if reminderStatus}<div class="settings-status">{reminderStatus}</div>{/if}
      </article>
    </div>

  {:else if activeSection === 'appearance'}
    <div class="settings-section-stack">
      <article class="settings-card glass-panel">
        <div class="settings-title"><span><Palette size={19}/></span><div><b>Liquid Glass</b><p>实时调整模糊、透明度、高光与折射感。</p></div></div>
        <div class="glass-preview-stage"><i class="preview-orb preview-orb-a"></i><i class="preview-orb preview-orb-b"></i><div class="glass-preview-card glass-panel refract"><span>实时预览</span><b>Liquid Glass</b><small>保持轻量 CSS 合成，不引入大型渲染依赖。</small></div></div>
        <div class="sliders">
          <label><span>模糊 <b>{glass.blur}px</b></span><input type="range" min="0" max="48" value={glass.blur} on:input={(e)=>updateGlass('blur',Number(e.currentTarget.value))}/></label>
          <label><span>透明度 <b>{glass.opacity}%</b></span><input type="range" min="25" max="95" value={glass.opacity} on:input={(e)=>updateGlass('opacity',Number(e.currentTarget.value))}/></label>
          <label><span>饱和度 <b>{glass.saturation}%</b></span><input type="range" min="70" max="210" value={glass.saturation} on:input={(e)=>updateGlass('saturation',Number(e.currentTarget.value))}/></label>
          <label><span>高光 <b>{glass.highlight}%</b></span><input type="range" min="0" max="100" value={glass.highlight} on:input={(e)=>updateGlass('highlight',Number(e.currentTarget.value))}/></label>
          <label><span>折射感 <b>{glass.refraction}%</b></span><input type="range" min="0" max="100" value={glass.refraction} on:input={(e)=>updateGlass('refraction',Number(e.currentTarget.value))}/></label>
          <label><span>噪点 <b>{glass.noise}%</b></span><input type="range" min="0" max="8" value={glass.noise} on:input={(e)=>updateGlass('noise',Number(e.currentTarget.value))}/></label>
        </div>
        <button class="toggle-row" on:click={toggleMotion}><span><b>动态玻璃</b><small>开启轻量动态高光</small></span><i class:on={glass.motion}></i></button>
      </article>
    </div>

  {:else if activeSection === 'data'}
    <div class="settings-section-stack">
      <article class="settings-card glass-panel">
        <div class="settings-title"><span><Cloud size={19}/></span><div><b>WebDAV 备份</b><p>密码仅保留在本次运行内。</p></div></div>
        <div class="webdav-form">
          <label><span>服务器地址</span><input bind:value={webdav.baseUrl} placeholder="https://dav.example.com/user/" autocomplete="url"/></label>
          <div class="webdav-pair"><label><span>用户名</span><input bind:value={webdav.username} placeholder="username" autocomplete="username"/></label><label><span>密码 / 应用专用密码</span><input type="password" bind:value={webdavPassword} placeholder="本次会话使用" autocomplete="current-password"/></label></div>
          <label><span>远程备份路径</span><input bind:value={webdav.remotePath} placeholder="LumaSchedule/lumaschedule-latest.luma.json"/></label>
        </div>
        <div class="settings-actions"><button on:click={()=>withWebDav('test')} disabled={webdavBusy}><RefreshCw size={15}/> 测试连接</button><button on:click={()=>withWebDav('upload')} disabled={webdavBusy}><Upload size={15}/> 立即备份</button><button class="danger-soft" on:click={()=>withWebDav('restore')} disabled={webdavBusy}><Download size={15}/> 从云端恢复</button></div>
        {#if webdavStatus}<div class="settings-status">{webdavStatus}</div>{/if}
      </article>

      <article class="settings-card glass-panel">
        <div class="settings-title"><span><Database size={19}/></span><div><b>导入 / 导出与完整备份</b><p>开放格式和 LumaSchedule 完整数据快照。</p></div></div>
        <button class="setting-row" on:click={()=>exportData('json')} disabled={dataBusy}><div><b>导出 LumaSchedule JSON</b><span>保留周次与作息信息</span></div><em>JSON</em></button>
        <button class="setting-row" on:click={()=>exportData('ics')} disabled={dataBusy}><div><b>导出系统日历</b><span>标准 iCalendar 文件</span></div><em>ICS</em></button>
        <button class="setting-row" on:click={()=>exportData('backup')} disabled={dataBusy}><div><b>导出完整备份</b><span>课程、成绩、提醒、设置与同步配置</span></div><em>.luma.json</em></button>
        <button class="setting-row" on:click={restoreData} disabled={dataBusy}><div><b>恢复完整备份</b><span>替换当前本地数据</span></div><em>恢复</em></button>
        {#if dataStatus}<div class="settings-status">{dataStatus}</div>{/if}
      </article>
    </div>

  {:else}
    <div class="settings-section-stack">
      <article class="settings-card glass-panel">
        <div class="settings-title"><span><Info size={19}/></span><div><b>LumaSchedule</b><p>当前版本 v{appVersion}</p></div></div>
        <button class="setting-row" on:click={checkUpdate} disabled={updateBusy}><div><b>检查更新</b><span>{updateStatus || '从 GitHub Release 检查最新正式版本'}</span></div><em>{updateBusy?'检查中':'检查'}</em></button>
        {#if updateResult?.updateAvailable && updateResult.releaseUrl}<a class="setting-row link-row update-link" href={updateResult.releaseUrl}><div><b>下载 v{updateResult.latestVersion}</b><span>{updateResult.name || '打开最新 Release'}</span></div><ExternalLink size={15}/></a>{/if}
      </article>

      <article class="settings-card glass-panel">
        <div class="settings-title"><span><MessageCircle size={19}/></span><div><b>反馈</b><p>直接进入 GitHub Issue，方便跟踪和修复。</p></div></div>
        <a class="setting-row link-row" href={bugIssueUrl}><div><b>报告问题</b><span>崩溃、导入失败、界面异常、学校兼容问题</span></div><ExternalLink size={15}/></a>
        <a class="setting-row link-row" href={featureIssueUrl}><div><b>功能建议</b><span>课程表、成绩、小组件或其他想法</span></div><ExternalLink size={15}/></a>
      </article>

      <article class="settings-card glass-panel">
        <div class="settings-title"><span><Shield size={19}/></span><div><b>隐私与权限</b><p>默认本地优先，不上传使用数据。</p></div></div>
        <div class="setting-row readonly-row"><div><b>遥测</b><span>不上传使用数据</span></div><em>关闭</em></div>
        <div class="setting-row readonly-row"><div><b>教务登录</b><span>限制在允许的学校教务域名与受限 Bridge</span></div><em>沙箱</em></div>
      </article>

      <a class="about-card glass-panel about-link" href="https://github.com/Junyxor/LumaSchedule"><Github size={19}/><div><b>LumaSchedule · GitHub</b><span>源码、Release 与 Issue</span></div><ExternalLink size={15}/></a>
    </div>
  {/if}
</section>

<ConfirmSheet
  open={confirmAction !== null}
  title={confirmAction === 'restore-webdav' ? '从 WebDAV 恢复' : '恢复完整备份'}
  message={confirmAction === 'restore-webdav' ? '将用 WebDAV 备份替换当前本地课表、成绩、提醒与设置。此操作不可自动撤销。' : '完整备份会替换当前本地课程、成绩、作息、提醒、设置和同步配置。此操作不可自动撤销。'}
  confirmText="继续恢复"
  danger
  busy={webdavBusy || dataBusy}
  on:cancel={() => (confirmAction = null)}
  on:confirm={() => confirmAction === 'restore-webdav' ? restoreWebDavConfirmed() : restoreDataConfirmed()}
/>

<style>
  .settings-sections { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:6px; padding:7px; border-radius:22px; margin-bottom:16px; }
  .settings-sections button { min-height:60px; border:0; border-radius:16px; background:transparent; color:inherit; display:grid; grid-template-columns:auto 1fr; align-items:center; gap:9px; padding:8px 10px; text-align:left; }
  .settings-sections button > span { width:34px; height:34px; border-radius:12px; display:grid; place-items:center; background:rgba(91,86,214,.08); color:#5b56d6; }
  .settings-sections button div { display:grid; gap:2px; min-width:0; }
  .settings-sections button b { font-size:10px; white-space:nowrap; }
  .settings-sections button small { color:rgba(60,60,67,.5); font-size:8px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .settings-sections button.active { background:rgba(91,86,214,.11); color:#514bd0; box-shadow:inset 0 0 0 1px rgba(91,86,214,.10); }
  .settings-section-stack { display:grid; gap:16px; max-width:880px; }
  .schedule-form { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  .schedule-form label,.webdav-form label { display:grid; gap:6px; }
  .schedule-form label.wide { grid-column:1/-1; }
  .schedule-form span,.webdav-form span,.inline-number span { font-size:10px; color:rgba(60,60,67,.58); padding-left:3px; }
  .schedule-form input,.schedule-form select,.webdav-form input,.inline-number input { min-height:43px; border:1px solid rgba(60,60,67,.08); border-radius:13px; padding:0 12px; background:rgba(118,118,128,.08); color:inherit; outline:none; }
  .inline-number { display:grid; grid-template-columns:1fr 120px; align-items:center; gap:12px; margin:6px 0 12px; }
  .weekend-setting { margin:10px 0 4px; padding:14px; border:1px solid rgba(60,60,67,.07); border-radius:16px; background:rgba(118,118,128,.045); display:grid; gap:11px; }
  .weekend-setting > div:first-child { display:flex; justify-content:space-between; align-items:baseline; gap:12px; }
  .weekend-setting b { font-size:11px; }
  .weekend-setting span { color:rgba(60,60,67,.55); font-size:9px; text-align:right; }
  .weekend-segments { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:6px; }
  .weekend-segments button { min-height:36px; border:1px solid rgba(118,118,128,.13); border-radius:11px; background:rgba(255,255,255,.38); color:inherit; font-size:10px; }
  .weekend-segments button.active { border-color:rgba(91,86,214,.30); background:rgba(91,86,214,.11); color:#5751c9; font-weight:700; }
  .display-toggles { display:grid; grid-template-columns:1fr 1fr; gap:0 16px; }
  .display-toggles .toggle-row { min-height:54px; margin-top:0; }
  .toggle-row span { display:flex; flex-direction:column; align-items:flex-start; gap:2px; }
  .toggle-row small { font-size:11px; font-weight:400; opacity:.58; }
  .schedule-save { justify-content:flex-end; margin-top:12px; }
  .schedule-save button { background:#625cd0; color:#fff; }
  .reminder-offsets { display:grid; grid-template-columns:repeat(3,1fr); gap:8px; margin:12px 0 6px; }
  .reminder-offsets button { min-height:38px; border:1px solid rgba(118,118,128,.14); border-radius:12px; background:rgba(118,118,128,.07); font-size:12px; color:inherit; }
  .reminder-offsets button.active { color:#5751c9; border-color:rgba(91,86,214,.28); background:rgba(91,86,214,.10); font-weight:650; }
  .glass-preview-stage { position:relative; min-height:150px; margin:16px 0 18px; border-radius:24px; overflow:hidden; display:grid; place-items:center; background:linear-gradient(135deg,#d9e9ff,#eee6ff 48%,#ffe7ef); isolation:isolate; }
  .preview-orb { position:absolute; border-radius:999px; filter:blur(4px); opacity:.78; }
  .preview-orb-a { width:118px; height:118px; left:-18px; top:-22px; background:#8ecbff; }
  .preview-orb-b { width:135px; height:135px; right:-18px; bottom:-42px; background:#ff9fc6; }
  .glass-preview-card { width:min(82%,330px); min-height:96px; border-radius:24px; padding:18px 20px; position:relative; z-index:1; display:flex; flex-direction:column; justify-content:center; }
  .glass-preview-card span { font-size:11px; opacity:.62; }
  .glass-preview-card b { font-size:20px; margin:3px 0 4px; }
  .glass-preview-card small { font-size:11px; line-height:1.45; opacity:.66; }
  .webdav-form { display:grid; gap:10px; margin-bottom:12px; }
  .webdav-pair { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  .link-row { color:inherit; text-decoration:none; }
  .link-row > svg { color:rgba(60,60,67,.42); }
  .update-link { background:rgba(91,86,214,.055); border-radius:14px; }
  .about-link { color:inherit; text-decoration:none; }
  .about-link > svg:last-child { margin-left:auto; opacity:.45; }
  @media (max-width:760px) {
    .settings-sections { display:flex; overflow-x:auto; gap:5px; padding:6px; margin:0 -2px 14px; scrollbar-width:none; }
    .settings-sections::-webkit-scrollbar { display:none; }
    .settings-sections button { flex:0 0 auto; min-width:118px; min-height:54px; }
    .settings-sections button > span { width:30px; height:30px; }
    .settings-section-stack { gap:12px; }
    .schedule-form { grid-template-columns:1fr; }
    .schedule-form label.wide { grid-column:auto; }
    .display-toggles { grid-template-columns:1fr; }
    .weekend-segments { grid-template-columns:repeat(3,minmax(0,1fr)); }
    .weekend-setting > div:first-child { display:grid; }
    .weekend-setting span { text-align:left; }
    .webdav-pair { grid-template-columns:1fr; }
    .inline-number { grid-template-columns:1fr 110px; }
  }
</style>
