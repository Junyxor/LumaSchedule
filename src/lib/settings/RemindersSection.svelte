<script lang="ts">
  import { AlarmClock, BellRing, CalendarX2, Clock3, RefreshCw, Send, Timer } from 'lucide-svelte';
  import { onMount } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { toast } from '../state/toast.svelte';
  import type { ExactAlarmStatus, ReminderSettings, ReminderSyncReport } from '../types';
  import Row from '../ui/Row.svelte';

  const OFFSETS = [5, 10, 15, 20, 30, 60];
  let settings = $state<ReminderSettings>({ enabled: false, offsetMinutes: 15 });
  let exact = $state<ExactAlarmStatus>({ required: false, granted: true });
  let report = $state.raw<ReminderSyncReport | null>(null);
  let busy = $state(false);

  onMount(() => {
    void load();
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refreshExact();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  });

  async function load() {
    const [settingsResult, exactResult] = await Promise.allSettled([api.reminderSettings(), api.exactAlarmStatus()]);
    if (settingsResult.status === 'fulfilled') settings = settingsResult.value;
    if (exactResult.status === 'fulfilled') exact = exactResult.value;
  }

  async function refreshExact() {
    try {
      exact = await api.exactAlarmStatus();
    } catch {
      // Keep the last known state.
    }
  }

  async function run(task: () => Promise<void>) {
    if (busy) return;
    busy = true;
    try {
      await task();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = false;
    }
  }

  function summary(value: ReminderSyncReport) {
    if (!value.enabled) return '提醒已关闭';
    if (value.problem === 'noSchedule') return '还没有课表，暂时没有需要提醒的课程';
    if (value.problem === 'missingTermStart') return '还没有设置开学日期，无法计算上课日期';
    return `已安排未来 ${value.horizonDays ?? 14} 天内的 ${value.futureCount} 个提醒`;
  }

  const toggle = () =>
    run(async () => {
      const enabled = !settings.enabled;
      if (enabled && !(await api.requestNotificationPermission())) {
        toast.error('没有通知权限，无法开启上课提醒。请在系统设置中允许 LumaSchedule 发送通知。');
        return;
      }
      settings = await api.saveReminderSettings({ ...settings, enabled });
      report = await api.syncReminders();
      exact = { ...exact, granted: report.exactAlarmGranted };
      toast.success(enabled ? summary(report) : '上课提醒已关闭');
    });

  const setOffset = (offsetMinutes: number) =>
    run(async () => {
      settings = await api.saveReminderSettings({ ...settings, offsetMinutes });
      if (settings.enabled) report = await api.syncReminders();
    });

  const resync = () =>
    run(async () => {
      report = await api.syncReminders();
      exact = { ...exact, granted: report.exactAlarmGranted };
      toast.success(summary(report));
    });

  const testNow = () =>
    run(async () => {
      const ok = await api.testNotification();
      if (ok) toast.success('测试通知已发送');
      else toast.error('没有通知权限，测试通知未发送。');
    });

  const testLater = () =>
    run(async () => {
      const ok = await api.scheduleTestReminder(60_000);
      if (ok) toast.success('1 分钟后会收到一条测试提醒，可以先退出应用');
      else toast.error('没有通知权限，无法安排测试提醒。');
    });

  const grantExact = () =>
    run(async () => {
      await api.requestExactAlarmAccess();
    });
</script>

<div class="list">
  <Row toggle icon={BellRing} title="上课提醒" sub={settings.enabled ? `每节课开始前 ${settings.offsetMinutes} 分钟通知` : '关闭'} checked={settings.enabled} busy={busy} onclick={toggle} />
</div>

<div class="list-caption">提前时间</div>
<div class="chips">
  {#each OFFSETS as offset (offset)}
    <button class="chip" class:active={settings.offsetMinutes === offset} type="button" disabled={busy} onclick={() => setOffset(offset)}>
      {offset === 60 ? '1 小时' : `${offset} 分钟`}
    </button>
  {/each}
</div>

{#if settings.enabled && (report?.problem === 'missingTermStart' || app.ctx.status === 'unset')}
  <button class="notice warning action" type="button" onclick={() => app.openSettings('term')}>
    <CalendarX2 size={18} />
    <span><b>需要开学日期。</b>设置后才能知道每节课具体在哪一天，点这里去设置。</span>
  </button>
{:else if settings.enabled && app.estimatedCount > 0}
  <button class="notice warning action" type="button" onclick={() => app.openSettings('times')}>
    <Clock3 size={18} />
    <span><b>{app.estimatedCount} 个上课时段没有具体时间，</b>提醒会按常见作息估算的时间发出。在「作息时间」里填好每节课的时间即可。</span>
  </button>
{/if}

{#if exact.required}
  <div class="list-caption">准时提醒</div>
  <div class="list">
    <Row
      icon={AlarmClock}
      title="闹钟和提醒权限"
      sub={exact.granted ? '已允许，提醒会准时触发' : '未允许时，系统可能为省电延后提醒'}
      value={exact.granted ? '已允许' : '去设置'}
      onclick={exact.granted ? undefined : grantExact}
    />
  </div>
{/if}

<div class="list-caption">维护</div>
<div class="list">
  <Row icon={RefreshCw} title="重新安排提醒" sub={report ? summary(report) : '课表变化后会自动更新，这里可手动刷新'} onclick={resync} disabled={busy} />
  <Row icon={Send} title="发送测试通知" onclick={testNow} disabled={busy} />
  <Row icon={Timer} title="1 分钟后测试提醒" sub="用于检查后台提醒是否会被系统拦截" onclick={testLater} disabled={busy} />
</div>
<p class="list-footer">提醒由 Android 系统闹钟触发，不需要应用常驻后台。部分品牌手机需要在系统设置中允许 LumaSchedule 自启动或关闭省电限制。</p>

<style>
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .action {
    width: 100%;
    margin-top: 12px;
    border: 0;
    text-align: left;
  }
</style>
