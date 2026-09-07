<script lang="ts">
  export let name: string; export let room: string; export let time: string; export let color = 'violet'; export let compact = false;
</script>
<div class="course-pill {color}" class:compact><span class="course-accent"></span><div class="course-info"><b>{name}</b><span>{room}</span></div><time>{time}</time></div>
