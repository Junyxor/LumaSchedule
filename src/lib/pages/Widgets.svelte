<script lang="ts">
  import { Smartphone, Sparkles } from 'lucide-svelte';
</script>

<section class="page page-widgets">
  <header class="topbar"><div><span class="eyebrow">小组件</span><h1>桌面小组件</h1><p>v0.1 只展示已经接通的 Android 原生能力。</p></div></header>

  <article class="widget-focus content-surface">
    <div class="widget-preview tiny">
      <span>下一节</span>
      <b>课程名称</b>
      <small>时间 · 教室</small>
    </div>
    <div class="widget-focus-copy">
      <span class="soft-badge"><Sparkles size={13}/> Android 原生</span>
      <h2>下一节课</h2>
      <p>App 会把本地下节课快照同步给系统桌面小组件。实际内容来自你的本地课表，不使用演示课程。</p>
      <small>在系统桌面长按空白处 → 小组件 → LumaSchedule 添加。</small>
    </div>
  </article>

  <article class="platform-card content-surface widget-status-row">
    <span><Smartphone size={21}/></span>
    <div><b>更多尺寸</b><p>今日列表、双日和完整周课表小组件尚未完成，因此 v0.1 不再显示成可用入口。</p></div>
    <em>后续</em>
  </article>
</section>
