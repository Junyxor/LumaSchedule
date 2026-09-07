<script lang="ts">
  import { Bell, Cloud, Database, Download, Github, Palette, RefreshCw, Shield, Upload } from 'lucide-svelte';
  import { confirm } from '@tauri-apps/plugin-dialog';
  import { onDestroy, onMount } from 'svelte';
  import type { GlassSettings, WebDavCredentials, WebDavProfile } from '../types';
  import { getWebDavProfile, restoreFullBackupFromFile, restoreWebDavBackup, saveFullBackup, saveGlassSettings, saveLatestScheduleIcs, saveLatestScheduleJson, saveWebDavProfile, scheduleTestReminder, testNotification, testWebDav, uploadWebDavBackup } from '../tauri';

  export let glass: GlassSettings;
  type NumericGlassKey = Exclude<keyof GlassSettings, 'motion'>;

  let glassSaveTimer: ReturnType<typeof setTimeout> | null = null;
  let webdav: WebDavProfile = { baseUrl: '', username: '', remotePath: 'LumaSchedule/lumaschedule-latest.luma.json' };
  let webdavPassword = '';
  let webdavBusy = false;
  let webdavStatus = '';
  let dataStatus = '';
  let dataBusy = false;

  onMount(async () => { try { webdav = await getWebDavProfile(); } catch {} });
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
      <div class="settings-title"><span><Bell size={19} /></span><div><b>提醒测试</b><p>只保留当前已经接通的系统能力。</p></div></div>
      <button class="setting-row" on:click={testNotification}><div><b>即时测试通知</b><span>验证 Android 通知权限与展示</span></div><em>立即</em></button>
      <button class="setting-row" on:click={() => scheduleTestReminder(60_000)}><div><b>后台定时提醒</b><span>由 Android AlarmManager 在 1 分钟后触发</span></div><em>1 分钟</em></button>
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

    <article class="about-card glass-panel"><Github size={19} /><div><b>100% 开源 · 无广告</b><span>Apache-2.0 · Core powered by Rust</span></div></article>
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
</style>
