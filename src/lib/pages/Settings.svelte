<script lang="ts">
  import { Bell, Cloud, Database, Download, ExternalLink, Github, Palette, RefreshCw, Shield, Upload } from 'lucide-svelte';
  import { confirm } from '@tauri-apps/plugin-dialog';
  import { onDestroy, onMount } from 'svelte';
  import type { CourseReminderSettings, GlassSettings, WebDavCredentials, WebDavProfile } from '../types';
  import {
    ensureNotificationPermission,
    getCourseReminderSettings,
    getWebDavProfile,
    restoreFullBackupFromFile,
    restoreWebDavBackup,
    saveCourseReminderSettings,
    saveFullBackup,
    saveGlassSettings,
    saveLatestScheduleIcs,
    saveLatestScheduleJson,
    saveWebDavProfile,
    scheduleTestReminder,
    syncCourseReminders,
    testNotification,
    testWebDav,
    uploadWebDavBackup
  } from '../tauri';

  export let glass: GlassSettings;
  type NumericGlassKey = Exclude<keyof GlassSettings, 'motion'>;

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
  const reminderOffsets = [5, 10, 15, 20, 30, 60];

  onMount(async () => {
    const [profileResult, reminderResult] = await Promise.allSettled([getWebDavProfile(), getCourseReminderSettings()]);
    if (profileResult.status === 'fulfilled') webdav = profileResult.value;
    if (reminderResult.status === 'fulfilled') reminder = reminderResult.value;
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
    webdavStatus = ''; webdavBusy = true;
    try {
      if (!webdav.baseUrl.trim()) throw new Error('先填写 WebDAV 地址。');
      if (!webdav.remotePath.trim()) throw new Error('先填写远程备份路径。');
      await saveWebDavProfile(webdav);
      if (action === 'restore') {
        const approved = await confirm('将用 WebDAV 备份替换当前本地课表、提醒与设置。确定继续吗？', { title: '恢复 LumaSchedule 备份', kind: 'warning' });
        if (!approved) return;
      }
      const result = action === 'test' ? await testWebDav(credentials()) : action === 'upload' ? await uploadWebDavBackup(credentials()) : await restoreWebDavBackup(credentials());
      webdavStatus = result.message;
      if (action === 'restore') window.dispatchEvent(new CustomEvent('luma-data-changed'));
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

  async function restoreData() {
    dataStatus = '';
    const approved = await confirm('完整备份会替换当前本地课程、作息、提醒、设置和同步配置。确定继续吗？', { title: '恢复完整备份', kind: 'warning' });
    if (!approved) return;
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
  <header class="topbar"><div><span class="eyebrow">设置</span><h1>设置</h1><p>外观、提醒、备份与隐私。</p></div></header>
  <div class="settings-layout"><div class="settings-main">
    <article class="settings-card glass-panel">
      <div class="settings-title"><span><Palette size={19} /></span><div><b>Liquid Glass</b><p>滑动时实时更新导航、搜索、浮层和课程主卡。</p></div></div>

      <div class="glass-preview-stage" aria-label="Liquid Glass 实时预览">
        <i class="preview-orb preview-orb-a"></i><i class="preview-orb preview-orb-b"></i>
        <div class="glass-preview-card glass-panel refract">
          <span>实时预览</span>
          <b>Liquid Glass</b>
          <small>模糊、透明度、饱和度、高光、折射和噪点会立即反映在这里。</small>
        </div>
      </div>

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
      <div class="settings-title"><span><Bell size={19} /></span><div><b>课程提醒</b><p>Android 原生 AlarmManager 调度，应用被回收或重启后仍可恢复。</p></div></div>
      <button class="toggle-row" on:click={setReminderEnabled} disabled={reminderBusy}><span><b>上课前提醒</b><small>{reminder.enabled ? `每节课提前 ${reminder.offsetMinutes} 分钟` : '当前关闭'}</small></span><i class:on={reminder.enabled}></i></button>
      <div class="reminder-offsets" aria-label="课程提醒提前时间">
        {#each reminderOffsets as offset}
          <button class:active={reminder.offsetMinutes === offset} on:click={() => setReminderOffset(offset)} disabled={reminderBusy}>{offset === 60 ? '1 小时' : `${offset} 分钟`}</button>
        {/each}
      </div>
      <button class="setting-row" on:click={resyncReminders} disabled={reminderBusy}><div><b>重新同步未来提醒</b><span>导入、编辑课表后会按当前课程重新计算</span></div><em>同步</em></button>
      <button class="setting-row" on:click={testNotification}><div><b>即时测试通知</b><span>验证系统通知权限与通知渠道</span></div><em>立即</em></button>
      <button class="setting-row" on:click={() => scheduleTestReminder(60_000)}><div><b>后台定时测试</b><span>1 分钟后由 Android 原生闹钟触发</span></div><em>1 分钟</em></button>
      {#if reminderStatus}<div class="settings-status">{reminderStatus}</div>{/if}
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

    <a class="about-card glass-panel about-link" href="https://github.com/Junyxor/LumaSchedule" target="_blank" rel="noreferrer">
      <Github size={19} />
      <div><b>LumaSchedule · GitHub</b><span>github.com/Junyxor/LumaSchedule · Apache-2.0</span></div>
      <ExternalLink size={15} />
    </a>
    <div class="project-sources">
      <b>参考与数据来源</b>
      <span>拾光课表 / shiguang_warehouse · WakeUp Schedule · CSES / ClassIsland · BetterUntis · AntAlmanac</span>
      <small>第三方项目仅用于协议兼容、产品设计与架构参考；代码和数据继续遵循各自许可证。</small>
    </div>
  </aside></div>
</section>

<style>
  .glass-preview-stage {
    position: relative;
    min-height: 150px;
    margin: 16px 0 18px;
    border-radius: 24px;
    overflow: hidden;
    display: grid;
    place-items: center;
    background: linear-gradient(135deg, #d9e9ff, #eee6ff 48%, #ffe7ef);
    isolation: isolate;
  }
  .preview-orb { position: absolute; border-radius: 999px; filter: blur(4px); opacity: .78; }
  .preview-orb-a { width: 118px; height: 118px; left: -18px; top: -22px; background: #8ecbff; }
  .preview-orb-b { width: 135px; height: 135px; right: -18px; bottom: -42px; background: #ff9fc6; }
  .glass-preview-card {
    width: min(82%, 330px);
    min-height: 96px;
    border-radius: 24px;
    padding: 18px 20px;
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    justify-content: center;
  }
  .glass-preview-card span { font-size: 11px; opacity: .62; }
  .glass-preview-card b { font-size: 20px; margin: 3px 0 4px; }
  .glass-preview-card small { font-size: 11px; line-height: 1.45; opacity: .66; }
  .reminder-offsets { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 12px 0 6px; }
  .reminder-offsets button { min-height: 38px; border: 1px solid rgba(118,118,128,.14); border-radius: 12px; background: rgba(118,118,128,.07); font-size: 12px; color: inherit; }
  .reminder-offsets button.active { color: #5751c9; border-color: rgba(91,86,214,.28); background: rgba(91,86,214,.10); font-weight: 650; }
  .toggle-row span { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; }
  .toggle-row small { font-size: 11px; font-weight: 400; opacity: .58; }
  .about-link { color: inherit; text-decoration: none; }
  .about-link > svg:last-child { margin-left: auto; opacity: .45; }
  .project-sources { padding: 4px 5px 0; display: grid; gap: 5px; color: rgba(60,60,67,.68); }
  .project-sources b { font-size: 12px; color: inherit; }
  .project-sources span { font-size: 11px; line-height: 1.5; }
  .project-sources small { font-size: 10px; line-height: 1.45; opacity: .75; }
  @media (prefers-color-scheme: dark) {
    .project-sources { color: rgba(235,235,245,.62); }
  }
</style>