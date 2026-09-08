<script lang="ts">
  import { Bell, Building2, CalendarRange, Cloud, Database, Download, ExternalLink, Github, Palette, RefreshCw, Shield, Upload } from 'lucide-svelte';
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import type { CourseReminderSettings, GlassSettings, SchedulePreferences, WebDavCredentials, WebDavProfile, WeekendMode } from '../types';
  import {
    ensureNotificationPermission,
    getCourseReminderSettings,
    getWebDavProfile,
    listShiguangAdapters,
    listShiguangSchools,
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
  let adapterStatus = '正在读取适配器索引…';
  let confirmAction: ConfirmAction = null;
  const reminderOffsets = [5, 10, 15, 20, 30, 60];
  const weekendModes: { value: WeekendMode; label: string; hint: string }[] = [
    { value: 'auto', label: '自动', hint: '只显示本周有课的周末列' },
    { value: 'weekdays', label: '工作日', hint: '始终只显示周一至周五' },
    { value: 'sat', label: '仅周六', hint: '固定显示周六，不显示周日' },
    { value: 'sun', label: '仅周日', hint: '固定显示周日，不显示周六' },
    { value: 'both', label: '周六+周日', hint: '始终显示完整七天' }
  ];

  onMount(async () => {
    scheduleDraft = { ...preferences, weekendMode: preferences.weekendMode ?? (preferences.showWeekend === false ? 'weekdays' : 'auto') };
    const [profileResult, reminderResult, schoolResult] = await Promise.allSettled([
      getWebDavProfile(),
      getCourseReminderSettings(),
      listShiguangSchools()
    ]);
    if (profileResult.status === 'fulfilled') webdav = profileResult.value;
    if (reminderResult.status === 'fulfilled') reminder = reminderResult.value;
    if (schoolResult.status === 'fulfilled') {
      const schools = schoolResult.value;
      const genericIds = new Set(['zhengfang_jiaowu', 'chaoxing_jiaowu', 'qingguo_jiaowu', 'urp_jiaowu']);
      const direct = schools.filter((school) => school.id !== 'GLOBAL_TOOLS' && !genericIds.has(school.id)).length;
      const generic = schools.filter((school) => genericIds.has(school.id)).length;
      const hasGdut = schools.some((school) => school.id === 'GDUT');
      let gdutAdapters = 0;
      if (hasGdut) gdutAdapters = await listShiguangAdapters('GDUT').then((items) => items.length).catch(() => 0);
      adapterStatus = `${direct} 所高校 · ${generic} 个通用入口${hasGdut ? ` · GDUT ${gdutAdapters} 个适配器` : ''}`;
    } else {
      adapterStatus = '适配器索引读取失败；仍可使用 App 内置离线快照。';
    }
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
      scheduleStatus = scheduleDraft.hasSchedule ? '学期与显示设置已保存。' : '显示设置已保存；导入课表后可继续设置学期信息。';
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
</script>

<section class="page page-settings">
  <header class="topbar"><div><span class="eyebrow">设置</span><h1>设置</h1><p>学期、课表显示、提醒、外观、备份与隐私。</p></div></header>
  <div class="settings-layout"><div class="settings-main">
    <article class="settings-card glass-panel">
      <div class="settings-title"><span><CalendarRange size={19} /></span><div><b>学期与课表</b><p>控制当前周计算、周视图列数、密度与课程信息显示。</p></div></div>
      <div class="schedule-form">
        <label class="wide"><span>学期名称</span><input bind:value={scheduleDraft.termName} placeholder="例如 2026-2027 学年第一学期" disabled={!scheduleDraft.hasSchedule} /></label>
        <label><span>开学日期</span><input type="date" bind:value={scheduleDraft.termStart} disabled={!scheduleDraft.hasSchedule} /></label>
        <label><span>总周数</span><input type="number" min="1" max="64" bind:value={scheduleDraft.weekCount} disabled={!scheduleDraft.hasSchedule} /></label>
        <label><span>每周起始</span><select bind:value={scheduleDraft.weekStartsOn} disabled={!scheduleDraft.hasSchedule}><option value={1}>周一</option><option value={7}>周日</option></select></label>
        <label><span>时区</span><input bind:value={scheduleDraft.timezone} placeholder="Asia/Shanghai" disabled={!scheduleDraft.hasSchedule} /></label>
        <label><span>默认显示节数</span><input type="number" min="8" max="30" bind:value={scheduleDraft.defaultSections} /></label>
      </div>

      <div class="weekend-setting">
        <div><b>周末列</b><span>{weekendModes.find((item) => item.value === scheduleDraft.weekendMode)?.hint}</span></div>
        <div class="weekend-segments" role="group" aria-label="周末课表显示方式">
          {#each weekendModes as mode}<button class:active={scheduleDraft.weekendMode === mode.value} on:click={() => setWeekendMode(mode.value)}>{mode.label}</button>{/each}
        </div>
      </div>

      <div class="display-toggles">
        <button class="toggle-row" on:click={() => toggleSchedule('showTeacher')}><span><b>显示教师</b><small>今日页和周课表显示教师信息</small></span><i class:on={scheduleDraft.showTeacher}></i></button>
        <button class="toggle-row" on:click={() => toggleSchedule('showRoom')}><span><b>显示教室</b><small>隐藏后课程卡只保留课程名与时间</small></span><i class:on={scheduleDraft.showRoom}></i></button>
        <button class="toggle-row" on:click={() => toggleSchedule('showTime')}><span><b>显示具体时间</b><small>关闭后优先显示第几节</small></span><i class:on={scheduleDraft.showTime}></i></button>
        <button class="toggle-row" on:click={() => toggleSchedule('compactMode')}><span><b>紧凑模式</b><small>缩短周课表行高与今日课程间距</small></span><i class:on={scheduleDraft.compactMode}></i></button>
      </div>
      <div class="settings-actions schedule-save"><button on:click={saveSchedule} disabled={scheduleBusy}>{scheduleBusy ? '正在保存…' : '保存课表设置'}</button></div>
      {#if !scheduleDraft.hasSchedule}<div class="settings-status">当前还没有课表；显示偏好可以保存，学期信息会在导入后启用。</div>{/if}
      {#if scheduleStatus}<div class="settings-status">{scheduleStatus}</div>{/if}
    </article>

    <article class="settings-card glass-panel">
      <div class="settings-title"><span><Bell size={19} /></span><div><b>课程提醒</b><p>Android 原生 AlarmManager 调度，应用被回收或重启后仍可恢复。</p></div></div>
      <button class="toggle-row" on:click={setReminderEnabled} disabled={reminderBusy}><span><b>上课前提醒</b><small>{reminder.enabled ? `每节课提前 ${reminder.offsetMinutes} 分钟` : '当前关闭'}</small></span><i class:on={reminder.enabled}></i></button>
      <div class="reminder-offsets" aria-label="课程提醒提前时间">{#each reminderOffsets as offset}<button class:active={reminder.offsetMinutes === offset} on:click={() => setReminderOffset(offset)} disabled={reminderBusy}>{offset === 60 ? '1 小时' : `${offset} 分钟`}</button>{/each}</div>
      <button class="setting-row" on:click={resyncReminders} disabled={reminderBusy}><div><b>重新同步未来提醒</b><span>导入、编辑课表后会按当前课程重新计算</span></div><em>同步</em></button>
      <button class="setting-row" on:click={testNotification}><div><b>即时测试通知</b><span>验证系统通知权限与通知渠道</span></div><em>立即</em></button>
      <button class="setting-row" on:click={() => scheduleTestReminder(60_000)}><div><b>后台定时测试</b><span>1 分钟后由 Android 原生闹钟触发</span></div><em>1 分钟</em></button>
      {#if reminderStatus}<div class="settings-status">{reminderStatus}</div>{/if}
    </article>

    <article class="settings-card glass-panel">
      <div class="settings-title"><span><Palette size={19} /></span><div><b>Liquid Glass</b><p>滑动时实时更新导航、搜索、浮层和课程主卡。</p></div></div>
      <div class="glass-preview-stage" aria-label="Liquid Glass 实时预览"><i class="preview-orb preview-orb-a"></i><i class="preview-orb preview-orb-b"></i><div class="glass-preview-card glass-panel refract"><span>实时预览</span><b>Liquid Glass</b><small>模糊、透明度、饱和度、高光、折射和噪点会立即反映在这里。</small></div></div>
      <div class="sliders">
        <label><span>模糊 <b>{glass.blur}px</b></span><input type="range" min="0" max="48" value={glass.blur} on:input={(event) => updateGlass('blur', Number(event.currentTarget.value))} /></label>
        <label><span>透明度 <b>{glass.opacity}%</b></span><input type="range" min="25" max="95" value={glass.opacity} on:input={(event) => updateGlass('opacity', Number(event.currentTarget.value))} /></label>
        <label><span>饱和度 <b>{glass.saturation}%</b></span><input type="range" min="70" max="210" value={glass.saturation} on:input={(event) => updateGlass('saturation', Number(event.currentTarget.value))} /></label>
        <label><span>高光 <b>{glass.highlight}%</b></span><input type="range" min="0" max="100" value={glass.highlight} on:input={(event) => updateGlass('highlight', Number(event.currentTarget.value))} /></label>
        <label><span>折射感 <b>{glass.refraction}%</b></span><input type="range" min="0" max="100" value={glass.refraction} on:input={(event) => updateGlass('refraction', Number(event.currentTarget.value))} /></label>
        <label><span>噪点 <b>{glass.noise}%</b></span><input type="range" min="0" max="8" value={glass.noise} on:input={(event) => updateGlass('noise', Number(event.currentTarget.value))} /></label>
      </div>
      <button class="toggle-row" on:click={toggleMotion}><span>动态玻璃</span><i class:on={glass.motion}></i></button>
    </article>

    <article class="settings-card glass-panel">
      <div class="settings-title"><span><Cloud size={19} /></span><div><b>WebDAV 备份</b><p>密码仅保留在本次运行内，不写入数据库。</p></div></div>
      <div class="webdav-form">
        <label><span>服务器地址</span><input bind:value={webdav.baseUrl} placeholder="https://dav.example.com/user/" autocomplete="url" /></label>
        <div class="webdav-pair"><label><span>用户名</span><input bind:value={webdav.username} placeholder="username" autocomplete="username" /></label><label><span>密码 / 应用专用密码</span><input type="password" bind:value={webdavPassword} placeholder="本次会话使用" autocomplete="current-password" /></label></div>
        <label><span>远程备份路径</span><input bind:value={webdav.remotePath} placeholder="LumaSchedule/lumaschedule-latest.luma.json" /></label>
      </div>
      <div class="settings-actions"><button on:click={() => withWebDav('test')} disabled={webdavBusy}><RefreshCw size={15} /> 测试连接</button><button on:click={() => withWebDav('upload')} disabled={webdavBusy}><Upload size={15} /> 立即备份</button><button class="danger-soft" on:click={() => withWebDav('restore')} disabled={webdavBusy}><Download size={15} /> 从云端恢复</button></div>
      {#if webdavStatus}<div class="settings-status">{webdavStatus}</div>{/if}
    </article>
  </div>

  <aside class="settings-side">
    <article class="settings-card glass-panel compact-settings">
      <div class="settings-title"><span><Building2 size={18} /></span><div><b>高校适配器</b><p>shiguang_warehouse 当前快照</p></div></div>
      <div class="adapter-health"><b>{adapterStatus}</b><span>在线索引优先，失败时回退到 APK 内置快照。GDUT 仍走受限教务 WebView 沙箱。</span></div>
    </article>

    <article class="settings-card glass-panel compact-settings">
      <div class="settings-title"><span><Database size={18} /></span><div><b>数据导入 / 导出</b><p>开放格式 + 完整备份</p></div></div>
      <button class="setting-row" on:click={() => exportData('json')} disabled={dataBusy}><div><b>导出 LumaSchedule JSON</b><span>可再次导入，保留周次与作息信息</span></div><em>JSON</em></button>
      <button class="setting-row" on:click={() => exportData('ics')} disabled={dataBusy}><div><b>导出系统日历</b><span>标准 iCalendar 文件</span></div><em>ICS</em></button>
      <button class="setting-row" on:click={() => exportData('backup')} disabled={dataBusy}><div><b>导出完整备份</b><span>课程、作息、提醒、设置、同步配置</span></div><em>.luma.json</em></button>
      <button class="setting-row" on:click={restoreData} disabled={dataBusy}><div><b>恢复完整备份</b><span>导入 LumaSchedule 完整数据快照</span></div><em>恢复</em></button>
      {#if dataStatus}<div class="settings-status compact">{dataStatus}</div>{/if}
    </article>

    <article class="settings-card glass-panel compact-settings">
      <div class="settings-title"><span><Shield size={18} /></span><div><b>隐私与权限</b><p>当前 v0.1 的真实状态</p></div></div>
      <div class="setting-row readonly-row"><div><b>遥测</b><span>不上传使用数据</span></div><em>关闭</em></div>
      <div class="setting-row readonly-row"><div><b>教务适配器</b><span>仅允许声明的教务域名与受限 Bridge</span></div><em>沙箱</em></div>
    </article>

    <a class="about-card glass-panel about-link" href="https://github.com/Junyxor/LumaSchedule" target="_blank" rel="noreferrer"><Github size={19} /><div><b>LumaSchedule · GitHub</b><span>github.com/Junyxor/LumaSchedule · Apache-2.0</span></div><ExternalLink size={15} /></a>
    <div class="project-sources"><b>参考与数据来源</b><span>拾光课表 / shiguang_warehouse · TimetableView / 怪兽课表 · ClassIsland · Class Widgets · WakeUp Schedule · CSES / ClassIsland · BetterUntis · AntAlmanac</span><small>第三方项目仅用于协议兼容、产品设计与架构参考；代码和数据继续遵循各自许可证。</small></div>
  </aside></div>
</section>

<ConfirmSheet
  open={confirmAction !== null}
  title={confirmAction === 'restore-webdav' ? '从 WebDAV 恢复' : '恢复完整备份'}
  message={confirmAction === 'restore-webdav' ? '将用 WebDAV 备份替换当前本地课表、提醒与设置。此操作不可自动撤销。' : '完整备份会替换当前本地课程、作息、提醒、设置和同步配置。此操作不可自动撤销。'}
  confirmText="继续恢复"
  danger
  busy={webdavBusy || dataBusy}
  on:cancel={() => (confirmAction = null)}
  on:confirm={() => confirmAction === 'restore-webdav' ? restoreWebDavConfirmed() : restoreDataConfirmed()}
/>

<style>
  .schedule-form { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:8px; }
  .schedule-form label { display:grid; gap:6px; }
  .schedule-form label.wide { grid-column:1 / -1; }
  .schedule-form span,.webdav-form span { font-size:10px; color:rgba(60,60,67,.58); padding-left:3px; }
  .schedule-form input,.schedule-form select { min-height:43px; border:1px solid rgba(60,60,67,.08); border-radius:13px; padding:0 12px; background:rgba(118,118,128,.08); color:inherit; outline:none; }
  .schedule-form input:disabled,.schedule-form select:disabled { opacity:.48; }
  .weekend-setting { margin:14px 0 4px; padding:14px; border:1px solid rgba(60,60,67,.07); border-radius:16px; background:rgba(118,118,128,.045); display:grid; gap:11px; }
  .weekend-setting > div:first-child { display:flex; justify-content:space-between; align-items:baseline; gap:12px; }
  .weekend-setting b { font-size:11px; }
  .weekend-setting span { color:rgba(60,60,67,.55); font-size:9px; text-align:right; }
  .weekend-segments { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:6px; }
  .weekend-segments button { min-height:36px; border:1px solid rgba(118,118,128,.13); border-radius:11px; background:rgba(255,255,255,.38); color:inherit; font-size:10px; }
  .weekend-segments button.active { border-color:rgba(91,86,214,.30); background:rgba(91,86,214,.11); color:#5751c9; font-weight:700; }
  .display-toggles { display:grid; grid-template-columns:1fr 1fr; gap:0 16px; }
  .display-toggles .toggle-row { min-height:54px; margin-top:0; }
  .schedule-save { justify-content:flex-end; margin-top:12px; }
  .schedule-save button { background:#625cd0; color:#fff; }
  .glass-preview-stage { position: relative; min-height: 150px; margin: 16px 0 18px; border-radius: 24px; overflow: hidden; display: grid; place-items: center; background: linear-gradient(135deg, #d9e9ff, #eee6ff 48%, #ffe7ef); isolation: isolate; }
  .preview-orb { position: absolute; border-radius: 999px; filter: blur(4px); opacity: .78; }
  .preview-orb-a { width: 118px; height: 118px; left: -18px; top: -22px; background: #8ecbff; }
  .preview-orb-b { width: 135px; height: 135px; right: -18px; bottom: -42px; background: #ff9fc6; }
  .glass-preview-card { width: min(82%, 330px); min-height: 96px; border-radius: 24px; padding: 18px 20px; position: relative; z-index: 1; display: flex; flex-direction: column; justify-content: center; }
  .glass-preview-card span { font-size: 11px; opacity: .62; }
  .glass-preview-card b { font-size: 20px; margin: 3px 0 4px; }
  .glass-preview-card small { font-size: 11px; line-height: 1.45; opacity: .66; }
  .reminder-offsets { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 12px 0 6px; }
  .reminder-offsets button { min-height: 38px; border: 1px solid rgba(118,118,128,.14); border-radius: 12px; background: rgba(118,118,128,.07); font-size: 12px; color: inherit; }
  .reminder-offsets button.active { color: #5751c9; border-color: rgba(91,86,214,.28); background: rgba(91,86,214,.10); font-weight: 650; }
  .toggle-row span { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; }
  .toggle-row small { font-size: 11px; font-weight: 400; opacity: .58; }
  .adapter-health { display:grid; gap:6px; padding:4px 2px; }
  .adapter-health b { font-size:12px; }
  .adapter-health span { font-size:10px; line-height:1.55; color:rgba(60,60,67,.58); }
  .about-link { color: inherit; text-decoration: none; }
  .about-link > svg:last-child { margin-left: auto; opacity: .45; }
  .project-sources { padding: 4px 5px 0; display: grid; gap: 5px; color: rgba(60,60,67,.68); }
  .project-sources b { font-size: 12px; color: inherit; }
  .project-sources span { font-size: 11px; line-height: 1.5; }
  .project-sources small { font-size: 10px; line-height: 1.45; opacity: .75; }
  @media (max-width:760px) { .display-toggles { grid-template-columns:1fr; } .schedule-form { grid-template-columns:1fr 1fr; } .weekend-segments { grid-template-columns:repeat(3,minmax(0,1fr)); } .weekend-setting > div:first-child { display:grid; } .weekend-setting span { text-align:left; } }
  @media (prefers-color-scheme: dark) { .project-sources { color: rgba(235,235,245,.62); } }
</style>
