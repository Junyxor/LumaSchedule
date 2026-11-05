<script lang="ts">
  import { FileText, Lock, ShieldCheck, WifiOff } from 'lucide-svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { toast } from '../state/toast.svelte';
  import Row from '../ui/Row.svelte';

  let busy = $state(false);

  async function exportLog() {
    if (busy) return;
    busy = true;
    try {
      const ok = await api.exportDiagnostics();
      ok ? toast.success('诊断日志已保存，可以附加到 GitHub Issue') : toast.show('已取消');
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = false;
    }
  }
</script>

<div class="list">
  <Row icon={WifiOff} title="不上传使用数据" sub="没有统计、广告或账号系统。课表、成绩和设置只保存在本机。" />
  <Row icon={Lock} title="教务登录隔离" sub="学校页面运行在独立窗口，只能访问该学校的域名，不能读取应用数据。" />
  <Row icon={ShieldCheck} title="导入先预览" sub="教务适配脚本返回的数据先进入预览，确认后才写入课表。" />
</div>

<div class="list-caption">诊断</div>
<div class="list">
  <Row icon={FileText} title="导出诊断日志" sub="包含应用版本、系统版本和运行事件，不包含密码、Cookie 或课程内容" onclick={exportLog} busy={busy} />
</div>
<p class="list-footer">导出前会去掉网址中的查询参数和常见凭据字段。遇到导入失败或提醒不准时，可以把日志附加到 GitHub Issue。</p>
