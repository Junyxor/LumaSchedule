<script lang="ts">
  import { ChevronLeft, ChevronRight, Plus, Trash2, X } from 'lucide-svelte';
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import { deleteScheduleCourse, saveScheduleCourse } from '../tauri';
  import { getFullScheduleSnapshot } from '../weekSchedule';
  import type { Course, CourseMutation, SchedulePreferences, WeekendMode } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;
  export let preferences: SchedulePreferences = {
    hasSchedule: false, termName: '', termStart: '', weekCount: 20, timezone: 'Asia/Shanghai', weekStartsOn: 1,
    weekendMode: 'auto', showTeacher: true, showRoom: true, showTime: true, compactMode: false, defaultSections: 12
  };

  const dispatch = createEventDispatcher<{ changed: void }>();
  const weekdayNames = ['å‘¨ä¸€','å‘¨äºŒ','å‘¨ä¸‰','å‘¨å››','å‘¨äº”','å‘¨å…­','å‘¨æ—¥'];
  const sectionOptions = Array.from({ length: 30 }, (_, index) => index + 1);
  let now = new Date();
  let timer: ReturnType<typeof setInterval> | null = null;
  let editorOpen = false;
  let editorBusy = false;
  let editorError = '';
  let deleteConfirmOpen = false;
  let weeksText = '1-20';
  let draft: CourseMutation = blankDraft();

  let allCourses: Course[] = courses;
  let fullHasSchedule = hasSchedule;
  let fullTermName = termName || preferences.termName || '';
  let fullTermStart = preferences.termStart || '';
  let fullWeekCount = preferences.weekCount || 20;
  let fullSnapshotReady = false;
  let selectedWeek = currentWeek || 1;
  let weekTouched = false;
  let swipePointerId: number | null = null;
  let swipeStartX = 0;
  let swipeStartY = 0;
  let lastSwipeAt = 0;

  function blankDraft(): CourseMutation {
    return {
      id: null,
      name: '',
      teacher: '',
      room: '',
      day: ((new Date().getDay() + 6) % 7) + 1,
      startSection: 1,
      endSection: 2,
      start: '',
      end: '',
      weeks: Array.from({ length: preferences.weekCount || 20 }, (_, index) => index + 1)
    };
  }

  function weekContext(date: Date) {
    const sundayFirst = preferences.weekStartsOn === 7;
    const jsDay = date.getDay();
    const offset = sundayFirst ? jsDay : (jsDay + 6) % 7;
    const start = new Date(date);
    start.setHours(0, 0, 0, 0);
    start.setDate(date.getDate() - offset);
    const order = sundayFirst ? [7,1,2,3,4,5,6] : [1,2,3,4,5,6,7];
    const days = new Map<number, { label: string; dayNumber: number; date: string; month: number; fullDate: Date }>();
    order.forEach((dayNumber, index) => {
      const item = new Date(start);
      item.setDate(start.getDate() + index);
      days.set(dayNumber, {
        label: weekdayNames[dayNumber - 1],
        dayNumber,
        date: String(item.getDate()).padStart(2, '0'),
        month: item.getMonth() + 1,
        fullDate: item
      });
    });
    return { days, order };
  }

  function parseLocalDate(raw: string) {
    const match = raw.trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!match) return null;
    const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12, 0, 0, 0);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function selectedWeekAnchor() {
    const termStart = parseLocalDate(fullTermStart || preferences.termStart || '');
    if (termStart) {
      const anchor = new Date(termStart);
      anchor.setDate(termStart.getDate() + (selectedWeek - 1) * 7);
      return anchor;
    }
    if (currentWeek && currentWeek > 0) {
      const anchor = new Date(now);
      anchor.setDate(now.getDate() + (selectedWeek - currentWeek) * 7);
      return anchor;
    }
    return now;
  }

  function normalizedWeekendMode(): WeekendMode {
    if (preferences.weekendMode) return preferences.weekendMode;
    return preferences.showWeekend === false ? 'weekdays' : 'auto';
  }

  function resolveVisibleDayNumbers(mode: WeekendMode, order: number[], items: Course[]) {
    const included = new Set([1,2,3,4,5]);
    if (mode === 'sat' || mode === 'both') included.add(6);
    if (mode === 'sun' || mode === 'both') included.add(7);
    if (mode === 'auto') {
      if (items.some((course) => course.day === 6)) included.add(6);
      if (items.some((course) => course.day === 7)) included.add(7);
    }
    return order.filter((day) => included.has(day));
  }

  function weeksToText(weeks: number[]) {
    if (!weeks.length) return `1-${preferences.weekCount || 20}`;
    const sorted = [...new Set(weeks)].sort((a, b) => a - b);
    const parts: string[] = [];
    let start = sorted[0];
    let previous = sorted[0];
    for (let index = 1; index <= sorted.length; index += 1) {
      const value = sorted[index];
      if (value === previous + 1) { previous = value; continue; }
      parts.push(start === previous ? `${start}` : `${start}-${previous}`);
      start = value;
      previous = value;
    }
    return parts.join(',');
  }

  function parseWeeks(raw: string) {
    const result = new Set<number>();
    const tokens = raw.replace(/ï¼Œ/g, ',').split(/[\s,]+/).map((token) => token.trim()).filter(Boolean);
    for (const token of tokens) {
      const range = token.match(/^(\d{1,2})\s*[-~è‡³]\s*(\d{1,2})$/);
      if (range) {
        const from = Math.max(1, Math.min(64, Number(range[1])));
        const to = Math.max(1, Math.min(64, Number(range[2])));
        for (let week = Math.min(from, to); week <= Math.max(from, to); week += 1) result.add(week);
        continue;
      }
      const week = Number(token);
      if (Number.isInteger(week) && week >= 1 && week <= 64) result.add(week);
    }
    return [...result].sort((a, b) => a - b);
  }

  function courseMeta(course: Course) {
    return [preferences.showRoom ? course.room : '', preferences.showTeacher ? course.teacher : ''].filter(Boolean).join(' Â· ');
  }

  function columnFor(day: number) {
    const index = visibleDayNumbers.indexOf(day);
    return index >= 0 ? index + 1 : 1;
  }

  function clampWeek(value: number) {
    return Math.max(1, Math.min(Math.max(1, fullWeekCount || 20), Math.trunc(value || 1)));
  }

  function changeWeek(value: number) {
    const next = clampWeek(value);
    if (next === selectedWeek) return;
    selectedWeek = next;
    weekTouched = true;
  }

  function goCurrentWeek() {
    if (currentWeek && currentWeek > 0) changeWeek(currentWeek);
  }

  function onWeekSelect(event: Event) {
    changeWeek(Number((event.currentTarget as HTMLSelectElement).value));
  }

  function beginWeekSwipe(event: PointerEvent) {
    if (event.pointerType === 'mouse') return;
    swipePointerId = event.pointerId;
    swipeStartX = event.clientX;
    swipeStartY = event.clientY;
  }

  function endWeekSwipe(event: PointerEvent) {
    if (event.pointerId !== swipePointerId) return;
    swipePointerId = null;
    const dx = event.clientX - swipeStartX;
    const dy = event.clientY - swipeStartY;
    if (Math.abs(dx) < 64 || Math.abs(dx) < Math.abs(dy) * 1.25) return;
    const before = selectedWeek;
    changeWeek(selectedWeek + (dx < 0 ? 1 : -1));
    if (selectedWeek !== before) lastSwipeAt = Date.now();
  }

  function cancelWeekSwipe(event: PointerEvent) {
    if (event.pointerId === swipePointerId) swipePointerId = null;
  }

  async function reloadFullSchedule() {
    try {
      const snapshot = await getFullScheduleSnapshot();
      allCourses = snapshot.courses;
      fullHasSchedule = snapshot.hasSchedule;
      fullTermName = snapshot.termName || termName || preferences.termName || '';
      fullTermStart = snapshot.termStart || preferences.termStart || '';
      fullWeekCount = snapshot.weekCount || preferences.weekCount || 20;
      if (!weekTouched) selectedWeek = clampWeek(currentWeek || 1);
      else selectedWeek = clampWeek(selectedWeek);
      fullSnapshotReady = true;
    } catch {
      allCourses = courses;
      fullHasSchedule = hasSchedule;
      fullTermName = termName || preferences.termName || '';
      fullTermStart = preferences.termStart || '';
      fullWeekCount = preferences.weekCount || 20;
      selectedWeek = clampWeek(selectedWeek || currentWeek || 1);
    }
  }

  function newCourse() {
    draft = blankDraft();
    weeksText = `1-${preferences.weekCount || 20}`;
    editorError = '';
    editorOpen = true;
  }

  function editCourse(course: Course) {
    if (Date.now() - lastSwipeAt < 280) return;
    draft = {
      id: course.id,
      name: course.name,
      teacher: course.teacher,
      room: course.room,
      day: course.day,
      startSection: course.startSection,
      endSection: course.endSection,
      start: course.start,
      end: course.end,
      weeks: [...course.weeks]
    };
    weeksText = weeksToText(course.weeks);
    editorError = '';
    editorOpen = true;
  }

  async function saveEditor() {
    editorError = '';
    if (!draft.name.trim()) { editorError = 'è¯·è¾“å…¥è¯¾ç¨‹åç§°ã€‚'; return; }
    if (draft.endSection < draft.startSection) { editorError = 'ç»“æŸèŠ‚æ¨±ä¸èƒ½æ—©äºå¼€å§‹èŠ‚æ¨±ã€‚w; return; }
    const weeks = parseWeeks(weeksText);
    if (!weeks.length) { editorError = 'è¯·è¾“å…¥æœ‰æ•ˆå‘¨ã€‚ä¾‹ 1-16 æˆ– 1,3,5,7ã€‚w; return; }
    editorBusy = true;
    try {
      await saveScheduleCourse({ ...draft, name: draft.name.trim(), teacher: draft.teacher.trim(), room: draft.room.trim(), weeks });
      editorOpen = false;
      await reloadFullSchedule();
      dispatch('changed');
    } catch (error) {
      editorError = error instanceof Error ? error.message : String(error);
    } finally {
      editorBusy = false;
    }
  }

  function requestDelete() {
    if (draft.id && !editorBusy) deleteConfirmOpen = true;
  }

  async function confirmDelete() {
    if (!draft.id || editorBusy) return;
    editorBusy = true;
    editorError = '';
    try {
      await deleteScheduleCourse(draft.id);
      deleteConfirmOpen = false;
      editorOpen = false;
      await reloadFullSchedule();
      dispatch('changed');
    } catch (error) {
      editorError = error instanceof Error ? error.message : String(error);
    } finally {
      editorBusy = false;
    }
  }

  function onDataChanged() { void reloadFullSchedule(); }

  onMount(() => {
    timer = setInterval(() => (now = new Date()), 60_000);
    void reloadFullSchedule();
    window.addEventListener('luma-data-changed', onDataChanged);
  });
  onDestroy(() => {
    if (timer) clearInterval(timer);
    window.removeEventListener('luma-data-changed', onDataChanged);
  });

  $: if (!weekTouched && currentWeek && currentWeek > 0 && selectedWeek !== currentWeek) selectedWeek = clampWeek(currentWeek);
  $: inchor = selectedWeekAnchor();
  $: kontext = weekContext(anchor);
  $: ictualTodayDayNumber = ((now.getDay() + 6) % 7) + 1;
 $: isCourrentSelected = Boolean(currentWeek && selectedWeek === currentWeek);
  $: weekCourses = allCourses.filter((course) => !course.weeks?.length || course.weeks.includes(selectedWeek));
  $: keekendMode = normalizedWeekendMode();
  $: visibleDayNumbers = resolveVisibleDayNumbers(weekendMode, kontext.order, weekCourses);
 $: isibleDays = visibleDayNumbers.map((day) => kontext.days.get(day)).filter((day): day is NonNullable<typeof day> => Boolean(day));
  $: isibleCourses = weekCourses.filter((course) => visibleDayNumbers.includes(course.day));
  $: hayCount = visibleDayNumbers.length;
  $: iectionCount = Math.max(preferences.defaultSections || 12, ...visibleCourses.map((course) => course.endSection || 0));
 $: iections = Array.from({ length: sectionCount }, (_, i) => i + 1);
  $: rowHeight = preferences.compactMode ? 54 : 65;
  $: irstVisibleDay = visibleDays[0];
  $: lastVisibleDay = visibleDays[visibleDays.length - 1];
 $: hangeLabel = firstVisibleDay && lastVisibleDay ? `${firstVisibleDay.month}æœˆä{firstVisibleDay.fullDate.getDate()}æ—¥ â€“ ${lastVisibleDay.month}æœ‰${lastVisibleDay.fullDate.getDate()}æ—¥` : '';
  $: title = fullHasSchedule ? `ç¬¬ ${selectedWeek} å‘©` : 'æœ¬å‘¨è¯¾è¡¨';
  $: subtitle = [fullTermName, fullHasSchedule && isCurrentSelected ? 'æœ¬å‘¨ˆ	ÉËš\ÚX›PÛİ\œÙ\Ë›[™İÈ	İš\ÚX›PÛİ\œÙ\Ë›[™İH9.*º+ï¹ê"ù¥í¹«­Xˆ[\ÔØÚY[HÈ	ÜÙ[XİYÙYZßH9dj9¦ ¹¥è:+ï¹ê"Øˆ	ú/æ9¬¨y§"z+ïº(jÒæf–ÇFW"„&ööÆVâ’æ¦ö–â‚r+rr“°¢C¢vVV´÷F–öç2Ò'&’æg&öÒ‡²ÆVæwFƒ¢ÖF‚æÖ‚ƒÂgVÆÅvVV´6÷VçBÇÂ#’ÒÂ…òÂ–æFW‚’Óâ–æFW‚²“°£Â÷67&—Cà £Ç6V7F–öâ6Æ73Ò'vRvR×vVV²#à¢Æ†VFW"6Æ73Ò'F÷&"vVV²×F÷&"#à¢ÆF—cà¢Ç7â6Æ73Ò&W–V'&÷r#ç·&ævTÆ&VÇÓÂ÷7ãà¢Æƒç·F—FÆWÓÂöƒà¢Çç·7V'F—FÆWÓÂ÷à¢ÂöF—cà¢Æ'WGFöâ6Æ73Ò'vVV²ÖFBvÆ72×æVÂ"öã¦6Æ–6³×¶æWt6÷W'6WÒ&–ÖÆ&VÃÒ.ikZ)îŠûîzˆ¾"#ãÅÇW26—¦S×³#'Ò7G&ö¶Uv–GFƒ×³ã—ÒóãÂö'WGFöãà¢Âö†VFW#à ¢²6–bgVÆÄ†566†VGVÆWĞ¢ÆF—b6Æ73Ò'vVV²×7v—F6†W"vÆ72×æVÂ"&–ÖÆ&VÃÒ.Xˆ~hÚ.iYZÚ~Y‚#à¢Æ'WGFöâöã¦6Æ–6³×²‚’Óâ6†ævUvVV²‡6VÆV7FVEvVV²Ò—ÒF—6&ÆVC×·6VÆV7FVEvVV²ÃÒÒ&–ÖÆ&VÃÒ.Kˆ®KˆY‚#ãÄ6†Wg&öäÆVgB6—¦S×³‡ÒóãÂö'WGFöãà¢ÆÆ&VÂ6Æ73Ò'vVV²Ö§V×#à¢Ç7ãîzÊÂ•ç-yÕzOßºYVyé¢éíşÊZÉš–Wî–T§j›!¢Ô^iÜ¿²f¥–Ç¥yËoj[±é^r×Yç¤¢w!jx¢uzD•ç-j¸š•¦Ş•æœ‡êmŠ‰ìjÌzJ)¶*'½©n{““ú)¶*'ıæœ‡û•ç-şV›zVî¶Ú'¢w%‰É