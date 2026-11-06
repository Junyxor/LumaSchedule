<script module lang="ts">
  // Kept in memory for the lifetime of the app process only, never written to disk.
  let sessionPassword = '';
</script>

<script lang="ts">
  import { CloudDownload, CloudUpload, PlugZap } from 'lucide-svelte';
  import { onMount } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { confirmAction } from '../state/confirm.svelte';
  import { toast } from '../state/toast.svelte';
  import type { WebDavProfile } from '../types';
  import Row from '../ui/Row.svelte';

  let profile = $state<WebDavProfile>({ baseUrl: '', username: '', remotePath: 'LumaSchedule/lumaschedule-latest.luma.json' });
  let password = $state(sessionPassword);
  let busy = $state<string | null>(null);

  $effect(() => {
    sessionPassword = password;
  });

  onMount(async () => {
    try {
      profile = await api.webDavProfile();
    } catch {
      // Defaults are fine.
    }
  });

  function validate() {
    if (!profile.baseUrl.trim()) throw new Error('请填写 WebDAV 服务器地址。');
    if (!profile.remotePath.trim()) throw new Error('请填写远程备份路径。');
  }

  async function run(key: string, task: () => Promise<void>) {
    if (busy) return;
    busy = key;
    try {
      validate();
      await api.saveWebDavProfile(profile);
      await task();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = null;
    }
  }

  const credentials = () => ({ ...profile, password });

  const test = () =>
    run('test', async () => {
      const result = await api.webDavTest(credentials());
      result.ok ? toast.success(result.message) : toast.error(result.message);
    });

  const upload = () =>
    run('upload', async () => {
      const result = await api.webDavUpload(credentials());
      result.ok ? toast.success(result.message) : toast.error(result.message);
    });

  const restore = () =>
    run('restore', async () => {
      const ok = await confirmAction({
        title: '从 WebDAV 恢复？',
        message: '会下载云端的完整备份并替换本机全部课表、成绩和设置，无法撤销。',
        confirmText: '恢复',
        danger: true
      });
      if (!ok) return;
      const result = await api.webDavRestore(credentials());
      if (!result.ok) {
        toast.error(result.message);
        return;
      }
      toast.success(result.message);
      await app.start();
    });
</script>

<div class="card stack">
  <label class="field">
    <span class="label">服务器地址</span>
    <input class="input" bind:value={profile.baseUrl} inputmode="url" placeholder="https://dav.example.com/remote.php/dav/files/me/" autocomplete="off" />
  </label>
  <div class="form-grid">
    <label class="field">
      <span class="label">用户名</span>
      <input class="input" bind:value={profile.username} autocomplete="username" />
    </label>
    <label class="field">
      <span class="label">密码 / 应用密码</span>
      <input class="input" type="password" bind:value={password} autocomplete="current-password" />
    </label>
  </div>
  <label class="field">
    <span class="label">远程备份路径</span>
    <input class="input" bind:value={profile.remotePath} placeholder="LumaSchedule/lumaschedule-latest.luma.json" autocomplete="off" />
    <span class="hint">公网地址必须使用 HTTPS；HTTP 只允许局域网地址。密码只在本次打开应用期间保留。</span>
  </label>
</div>

<div class="list-caption">操作</div>
<div class="list">
  <Row icon={PlugZap} title="测试连接" onclick={test} busy={busy === 'test'} />
  <Row icon={CloudUpload} title="立即备份到云端" sub="上传一份完整备份，覆盖远程同名文件" onclick={upload} busy={busy === 'upload'} />
  <Row icon={CloudDownload} title="从云端恢复" sub="替换本机全部数据" onclick={restore} busy={busy === 'restore'} danger />
</div>
