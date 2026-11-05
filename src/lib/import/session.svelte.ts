import { api, rememberAcademicSource } from '../native/api';
import { errorMessage } from '../native/bridge';
import { app } from '../state/app.svelte';
import { toast } from '../state/toast.svelte';
import type { GradeImportBundle, ImportBundle, ShiguangImportStart } from '../types';

/**
 * Tracks the single login WebView session that may be open at a time (course
 * import or grade capture) and polls Kotlin for its staged result. The session
 * id is persisted so the result is not lost if the main WebView reloads while
 * the login Activity is in front.
 */

const STORAGE_KEY = 'luma.activeSession';

export interface ActiveSession extends ShiguangImportStart {
  kind: 'schedule' | 'grades';
  sourceUrl: string;
}

export interface ScheduleResult {
  bundle: ImportBundle;
  label: string;
}

class SessionManager {
  active = $state.raw<ActiveSession | null>(null);
  message = $state('');
  scheduleResult = $state.raw<ScheduleResult | null>(null);
  gradeResult = $state.raw<GradeImportBundle | null>(null);
  #timer: ReturnType<typeof setTimeout> | null = null;
  #failures = 0;

  resume() {
    if (this.active) return;
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null') as ActiveSession | null;
      if (saved?.sessionId) this.#track(saved, '正在恢复导入会话…');
    } catch {
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  begin(started: ShiguangImportStart, kind: 'schedule' | 'grades', sourceUrl: string) {
    if (this.active) void this.cancel();
    const session: ActiveSession = { ...started, kind, sourceUrl };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } catch {
      // Polling still works without persistence.
    }
    if (sourceUrl) rememberAcademicSource(sourceUrl, started.schoolName, started.adapterName);
    this.#track(session, kind === 'grades' ? '登录后进入成绩页面，再点底部「读取成绩」。' : '请在打开的窗口中登录教务系统。');
  }

  async cancel() {
    const session = this.active;
    this.#stop();
    if (session) await api.closeSession(session.sessionId).catch(() => undefined);
  }

  #track(session: ActiveSession, message: string) {
    this.active = session;
    this.message = message;
    this.#failures = 0;
    this.#schedule(300);
  }

  #schedule(delay: number) {
    if (this.#timer) clearTimeout(this.#timer);
    this.#timer = setTimeout(() => void this.#poll(), delay);
  }

  #stop() {
    if (this.#timer) clearTimeout(this.#timer);
    this.#timer = null;
    this.active = null;
    this.message = '';
    localStorage.removeItem(STORAGE_KEY);
  }

  async #poll() {
    const session = this.active;
    if (!session) return;
    try {
      const state = await api.session(session.sessionId);
      if (this.active?.sessionId !== session.sessionId) return;
      this.#failures = 0;
      this.message = state.message || (state.status === 'collecting' ? '正在整理数据…' : '等待登录完成…');
      if (state.status === 'complete') {
        this.#stop();
        await api.closeSession(session.sessionId).catch(() => undefined);
        if (session.kind === 'grades' && state.gradeBundle) {
          this.gradeResult = state.gradeBundle;
          app.go('grades');
        } else if (state.bundle) {
          this.scheduleResult = { bundle: state.bundle, label: [state.schoolName, state.adapterName].filter(Boolean).join(' · ') };
          app.go('import');
        } else {
          toast.error('教务页面没有返回可用数据。');
        }
        return;
      }
      if (state.status === 'error') {
        this.#stop();
        await api.closeSession(session.sessionId).catch(() => undefined);
        toast.show(state.message || '导入已取消。', state.message?.includes('退出') ? 'info' : 'error');
        return;
      }
      this.#schedule(900);
    } catch (error) {
      this.#failures += 1;
      if (this.#failures > 40) {
        this.#stop();
        toast.error(`导入会话已失效：${errorMessage(error)}`);
        return;
      }
      this.message = '正在重新连接导入会话…';
      this.#schedule(1500);
    }
  }
}

export const importSession = new SessionManager();
