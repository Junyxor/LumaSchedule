import type { ScheduleSnapshot } from './types';
import { hydrateCourseCredits } from './courseCredits';
import { getScheduleSnapshot } from './tauri';

declare global {
  interface Window {
    LumaWeek?: { snapshot(): string };
  }
}

/**
 * The week page asks for the whole active semester once and then performs week
 * switching in memory. Old APKs without LumaWeek gracefully fall back to the
 * current-week snapshot.
 */
export async function getFullScheduleSnapshot(): Promise<ScheduleSnapshot> {
  const bridge = window.LumaWeek;
  if (!bridge) return getScheduleSnapshot();
  const raw = bridge.snapshot();
  const parsed = JSON.parse(raw) as ScheduleSnapshot;
  return parsed && Array.isArray(parsed.courses)
    ? { ...parsed, courses: hydrateCourseCredits(parsed.courses) }
    : getScheduleSnapshot();
}
