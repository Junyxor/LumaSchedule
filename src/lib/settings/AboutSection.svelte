<script lang="ts">
  import { Bug, Github, Lightbulb, RefreshCw, Scale } from 'lucide-svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { toast } from '../state/toast.svelte';
  import type { UpdateCheckResult } from '../types';
  import Row from '../ui/Row.svelte';

  const REPO = 'https://github.com/Junyxor/LumaSchedule';
  let checking = $state(false);
  let update = $state.raw<UpdateCheckResult | null>(null);

  async function check() {
    if (checking) return;
    checking = true;
    try {
      update = await api.checkUpdate();
      if (!update.updateAvailable) toast.success(`已是最新版本 v${update.currentVersion}`);
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      checking = false;
    }
  }
</script>

<div class="brand">
  <img src="/app-icon.svg" alt="" width="64" height="64" />
  <b>LumaSchedule · 流光课表</b>
  <span>版本 {app.appVersion || '—'}</span>
</div>

<div class="list">
  <Row icon={RefreshCw} title="检查更新" sub="从 GitHub Releases 获取最新正式版" onclick={check} busy={checking} />
  {#if update?.updateAvailable}
    <Row title={`下载 v${update.latestVersion}`} sub={update.name || '打开 Release 页面'} href={update.releaseUrl} />
  {/if}
</div>

<div class="list-caption">反馈</div>
<div class="list">
  <Row icon={Bug} title="报告问题" sub="崩溃、导入失败、学校兼容问题" href={`${REPO}/issues/new?template=bug_report.yml`} />
  <Row icon={Lightbulb} title="功能建议" href={`${REPO}/issues/new?template=feature_request.yml`} />
  <Row icon={Github} title="源代码" sub="Junyxor/LumaSchedule" href={REPO} />
</div>

<div class="list-caption">开源许可</div>
<div class="list">
  <Row icon={Scale} title="Apache License 2.0" sub="LumaSchedule 以 Apache-2.0 协议开源" href={`${REPO}/blob/main/LICENSE`} />
  <Row title="拾光课程表适配仓库" sub="高校适配脚本来自 XingHeYuZhuan/shiguang_warehouse（MIT）" href="https://github.com/XingHeYuZhuan/shiguang_warehouse" />
</div>

<style>
  .brand {
    display: grid;
    justify-items: center;
    gap: 4px;
    padding: 8px 0 22px;
  }

  .brand img {
    width: 64px;
    height: 64px;
    margin-bottom: 8px;
    border-radius: 16px;
  }

  .brand b {
    font-size: var(--text-lg);
  }

  .brand span {
    color: var(--text-2);
    font-size: var(--text-sm);
  }
</style>
