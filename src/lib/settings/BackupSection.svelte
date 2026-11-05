<script lang="ts">
  import { CalendarArrowDown, DatabaseBackup, FileJson2, HardDriveUpload } from 'lucide-svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { confirmAction } from '../state/confirm.svelte';
  import { toast } from '../state/toast.svelte';
  import Row from '../ui/Row.svelte';

  let busy = $state<string | null>(null);

  async function run(key: string, task: () => Promise<void>) {
    if (busy) return;
    busy = key;
    try {
      await task();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = null;
    }
  }

  const saved = (ok: boolean) => (ok ? toast.success('已保存到所选位置') : toast.show('已取消'));

  const exportJson = () => run('json', async () => saved(await api.exportScheduleJson()));
  const exportIcs = () => run('ics', async () => saved(await api.exportScheduleIcs()));
  const exportBackup = () => run('backup', async () => saved(await api.exportFullBackup()));

  const restore = () =>
    run('restore', async () => {
      const ok = await confirmAction({
        title: '从备份文件恢复？',
        message: '恢复会替换本机的全部课表、成绩、作息、提醒和设置，无法撤销。建议先导出一份当前数据的备份。',
        confirmText: '选择备份文件',
        danger: true
      });
      if (!ok) return;
      const summary = await api.restoreFullBackup();
      if (!summary) {
        toast.show('已取消');
        return;
      }
      toast.success(`恢复完成：${summary.courseCount} 门课程，${summary.meetingCount} 个时段`);
      await app.start();
    });
</script>

<div class="list-caption">导出课表</div>
<div class="list">
  <Row icon={FileJson2} title="导出为 JSON" sub="包含周次、作息时间和学分，可再次导入" onclick={exportJson} busy={busy === 'json'} disabled={!app.semester.hasSchedule} />
  <Row icon={CalendarArrowDown} title="导出为日历文件 (ICS)" sub="导入系统日历、Outlook 或 Google 日历" onclick={exportIcs} busy={busy === 'ics'} disabled={!app.semester.hasSchedule} />
</div>

<div class="list-caption">完整备份</div>
<div class="list">
  <Row icon={HardDriveUpload} title="导出完整备份" sub="所有课表、成绩、提醒、外观与同步设置（.luma.json）" onclick={exportBackup} busy={busy === 'backup'} />
  <Row icon={DatabaseBackup} title="从备份恢复" sub="替换本机全部数据" onclick={restore} busy={busy === 'restore'} danger />
</div>
<p class="list-footer">备份文件不包含 WebDAV 密码。换手机时，在旧手机导出完整备份，再在新手机恢复即可。</p>
