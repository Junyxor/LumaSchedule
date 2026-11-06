import { api, migrateLegacyCredits } from '../native/api';
import { errorMessage, hasNative } from '../native/bridge';
import { buildSectionTimes, courseTimeEstimated, sectionCountFor, termContext, wallClock } from '../schedule/calendar';
import type { AccentId, Appearance, DisplayPreferences, PageId, Semester, ThemeMode } from '../types';

export const emptySemester = (): Semester => ({
  hasSchedule: false,
  scheduleId: null,
  scheduleName: '',
  termName: '',
  termStart: '',
  weekCount: 20,
  weekStartsOn: 1,
  timezone: 'Asia/Shanghai',
  timeSlots: [],
  courses: []
});

export const defaultDisplay = (): DisplayPreferences => ({
  weekendMode: 'auto',
  showTeacher: true,
  showRoom: true,
  showTime: true,
  compactMode: false,
  defaultSections: 12
});

export const defaultAppearance = (): Appearance => ({ theme: 'system', accent: 'luma', colorfulCourses: false });

const THEMES: ThemeMode[] = ['system', 'light', 'dark'];
const ACCENTS: AccentId[] = ['luma', 'blue', 'teal', 'green', 'orange', 'pink', 'graphite'];
const APPEARANCE_KEY = 'luma.appearance.v2';

export function sanitizeAppearance(raw: unknown): Appearance {
  const value = (raw && typeof raw === 'object' ? raw : {}) as Partial<Appearance>;
  const fallback = defaultAppearance();
  return {
    theme: THEMES.includes(value.theme as ThemeMode) ? (value.theme as ThemeMode) : fallback.theme,
    accent: ACCENTS.includes(value.accent as AccentId) ? (value.accent as AccentId) : fallback.accent,
    colorfulCourses: typeof value.colorfulCourses === 'boolean' ? value.colorfulCourses : fallback.colorfulCourses
  };
}

function cachedAppearance(): Appearance {
  try {
    const stored = localStorage.getItem(APPEARANCE_KEY);
    if (stored) return sanitizeAppearance(JSON.parse(stored));
    // Older builds only remembered the colourful-course switch.
    return { ...defaultAppearance(), colorfulCourses: localStorage.getItem('luma.colorfulCourses') === 'true' };
  } catch {
    return defaultAppearance();
  }
}

function cacheAppearance(appearance: Appearance) {
  try {
    localStorage.setItem(APPEARANCE_KEY, JSON.stringify(appearance));
  } catch {
    // Not fatal: the native copy is the source of truth.
  }
}

class AppState {
  ready = $state(false);
  native = $state(hasNative());
  appVersion = $state('');
  loadError = $state('');
  page = $state<PageId>('today');
  semester = $state.raw<Semester>(emptySemester());
  display = $state.raw<DisplayPreferences>(defaultDisplay());
  appearance = $state.raw<Appearance>(cachedAppearance());
  /** Device clock, refreshed by tick(). */
  #instant = $state.raw(new Date());
  /** Wall-clock time in the term's time zone (see `wallClock`), the same clock reminders and the widget use. */
  now = $derived(wallClock(this.semester.timezone, this.#instant));
  /** Settings sub-page to open next time the settings tab is shown. */
  settingsTarget = $state<string | null>(null);

  ctx = $derived(termContext(this.semester, this.now));
  sectionCount = $derived(sectionCountFor(this.semester, this.display.defaultSections));
  sections = $derived(buildSectionTimes(this.semester, this.sectionCount));
  /** Meetings shown at typical-day times because neither the course nor the timetable has a time for them. */
  estimatedCount = $derived(this.semester.courses.filter((course) => courseTimeEstimated(course, this.sections)).length);

  #loading: Promise<void> | null = null;

  tick() {
    this.#instant = new Date();
  }

  async start() {
    this.native = hasNative();
    await this.reload();
    if (!this.native) return;
    try {
      const bootstrap = await api.bootstrap();
      this.appVersion = bootstrap.appVersion;
      if (bootstrap.appearance) {
        this.appearance = sanitizeAppearance(bootstrap.appearance);
        cacheAppearance(this.appearance);
      } else {
        // First launch of this build: persist the cached choice natively so backups include it.
        void api.saveAppearance(this.appearance).catch(() => undefined);
      }
    } catch {
      // Version and appearance are optional for the first paint.
    }
    void migrateLegacyCredits().then(() => this.reload());
  }

  /** Reloads schedule data and display preferences from SQLite. */
  reload() {
    if (this.#loading) return this.#loading;
    this.#loading = (async () => {
      if (!this.native) {
        this.ready = true;
        this.loadError = '当前环境不是 LumaSchedule 应用，无法读取本地课表。';
        return;
      }
      try {
        const [semester, display] = await Promise.all([api.semester(), api.displayPreferences()]);
        this.semester = semester;
        this.display = { ...defaultDisplay(), ...display };
        this.loadError = '';
      } catch (error) {
        this.loadError = errorMessage(error);
      } finally {
        this.ready = true;
        this.tick();
      }
    })().finally(() => {
      this.#loading = null;
    });
    return this.#loading;
  }

  async setDisplay(next: DisplayPreferences) {
    const previous = this.display;
    this.display = next;
    try {
      this.display = { ...defaultDisplay(), ...(await api.saveDisplayPreferences(next)) };
    } catch (error) {
      this.display = previous;
      throw error;
    }
  }

  setAppearance(next: Appearance) {
    this.appearance = sanitizeAppearance(next);
    cacheAppearance(this.appearance);
    if (this.native) void api.saveAppearance(this.appearance).catch(() => undefined);
  }

  go(page: PageId) {
    this.page = page;
    window.scrollTo(0, 0);
  }

  openSettings(section: string) {
    this.settingsTarget = section;
    this.go('settings');
  }
}

export const app = new AppState();
