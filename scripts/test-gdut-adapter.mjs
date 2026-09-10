import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const root = process.cwd();
const warehouse = process.env.SHIGUANG_SNAPSHOT
  ? path.resolve(root, process.env.SHIGUANG_SNAPSHOT)
  : path.join(root, 'vendor', 'shiguang_warehouse');
const upstreamScriptPath = path.join(warehouse, 'resources', 'GDUT', 'gdut.js');
const overrideScriptPath = path.join(root, 'native', 'android', 'adapter_overrides', 'shiguang_overrides', 'GDUT', 'gdut.js');
const scriptPath = fs.existsSync(overrideScriptPath) ? overrideScriptPath : upstreamScriptPath;

if (!fs.existsSync(scriptPath)) {
  throw new Error(`GDUT adapter script missing: ${scriptPath}`);
}
if (!fs.existsSync(upstreamScriptPath)) {
  throw new Error(`Upstream GDUT adapter script missing: ${upstreamScriptPath}`);
}

async function runScenario({ label, hostname, probeBody, coursePayload, expectError }) {
  const captured = {
    alerts: [],
    selections: [],
    courses: null,
    slots: null,
    config: null,
    completed: false,
    errors: [],
    toasts: []
  };

  function decodeHtml(value) {
    return String(value)
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&#39;', "'")
      .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)));
  }

  const sandbox = {
    console,
    URL,
    URLSearchParams,
    Date,
    Promise,
    JSON,
    Number,
    String,
    Array,
    Object,
    Error,
    Math,
    setTimeout,
    clearTimeout,
    document: {
      readyState: 'complete',
      createElement() {
        return {
          textContent: '',
          innerText: '',
          set innerHTML(value) {
            this.textContent = decodeHtml(value);
            this.innerText = this.textContent;
          }
        };
      },
      addEventListener() {}
    },
    location: { hostname, pathname: '/' },
    fetch: async (input, init = {}) => {
      const url = String(input);
      if (url.includes('xsgrkbcx!getXsgrbkList.action') || url.includes('xsgrkbcx!getXsgrbkList')) {
        return {
          ok: true,
          status: 200,
          async text() { return probeBody; }
        };
      }
      if (url.includes('xsgrkbcx!getKbRq.action')) {
        return {
          ok: true,
          status: 200,
          async text() {
            return JSON.stringify([
              {},
              [
                { xqmc: '1', rq: '2026-09-07' },
                { xqmc: '2', rq: '2026-09-08' }
              ]
            ]);
          }
        };
      }

      if (url.includes('xsgrkbcx!getDataList.action')) {
        if (String(init.method || 'GET').toUpperCase() !== 'POST') {
          throw new Error('GDUT course API must be requested with POST');
        }
        const body = new URLSearchParams(String(init.body || ''));
        if (!body.get('xnxqdm')) throw new Error('GDUT course API request missed xnxqdm');
        if (body.get('rows') !== '1000') throw new Error('GDUT course API page size changed unexpectedly');
        if (typeof coursePayload === 'string') {
          return {
            ok: true,
            status: 200,
            async text() { return coursePayload; },
            async json() { throw new Error('should parse text first'); }
          };
        }
        return {
          ok: true,
          status: 200,
          async text() { return JSON.stringify(coursePayload); },
          async json() { return coursePayload; }
        };
      }

      if (url.includes('xsgrkbcx!xsAllKbList.action')) {
        return {
          ok: true,
          status: 200,
          async text() { return '<html>opened</html>'; }
        };
      }

      throw new Error(`Unexpected GDUT adapter request: ${url}`);
    }
  };

  sandbox.window = sandbox;
  sandbox.window.location = sandbox.location;
  sandbox.window.shiguangBridge = {
    showToast(message) { captured.toasts.push(String(message)); },
    notifyTaskCompletion() { captured.completed = true; },
    reportError(message) { captured.errors.push(String(message)); }
  };
  sandbox.window.shiguangBridgePromise = {
    async showAlert(title, message, confirmText) {
      captured.alerts.push({ title, message, confirmText });
      return true;
    },
    async showSingleSelection(title, optionsJson, defaultIndex) {
      const options = JSON.parse(optionsJson);
      captured.selections.push({ title, options, defaultIndex });
      return defaultIndex >= 0 && defaultIndex < options.length ? defaultIndex : 0;
    },
    async saveImportedCourses(payload) {
      captured.courses = JSON.parse(payload);
      return true;
    },
    async savePresetTimeSlots(payload) {
      captured.slots = JSON.parse(payload);
      return true;
    },
    async saveCourseConfig(payload) {
      captured.config = JSON.parse(payload);
      return true;
    }
  };

  const context = vm.createContext(sandbox);
  const source = fs.readFileSync(scriptPath, 'utf8');
  const result = vm.runInContext(source, context, { filename: scriptPath });
  if (result && typeof result.then === 'function') await result;
  // runImportFlow is async fire-and-forget at script bottom.
  for (let i = 0; i < 50; i += 1) {
    if (captured.completed || captured.errors.length) break;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }

  if (expectError === 'no-complete' || expectError === 'toast-only') {
    if (captured.completed) throw new Error(`${label}: adapter must not complete on this host`);
    if (expectError === 'toast-only' && !captured.toasts.length) {
      throw new Error(`${label}: expected a toast`);
    }
    return captured;
  }

  if (expectError) {
    if (captured.completed) throw new Error(`${label}: expected failure but adapter completed`);
    if (!captured.errors.length && !captured.toasts.length) {
      throw new Error(`${label}: expected an error toast/report`);
    }
    return captured;
  }

  if (!captured.completed) {
    throw new Error(`${label}: GDUT adapter did not emit notifyTaskCompletion() (errors=${JSON.stringify(captured.errors)}, toasts=${JSON.stringify(captured.toasts)})`);
  }
  if (!Array.isArray(captured.courses) || captured.courses.length !== 3) {
    throw new Error(`${label}: GDUT adapter expected 3 raw course meetings, got ${captured.courses?.length ?? 'none'}`);
  }
  if (captured.courses[0].name !== '高等数学&A') {
    throw new Error(`${label}: GDUT HTML entity decoding regressed: ${captured.courses[0].name}`);
  }
  if (captured.courses[0].startSection !== 1 || captured.courses[0].endSection !== 2) {
    throw new Error(`${label}: GDUT section parsing regressed for jcdm=0102`);
  }
  if (!Array.isArray(captured.slots) || captured.slots.length !== 14) {
    throw new Error(`${label}: GDUT adapter expected 14 time slots, got ${captured.slots?.length ?? 'none'}`);
  }
  if (captured.slots[0]?.startTime !== '08:30' || captured.slots[13]?.endTime !== '22:35') {
    throw new Error(`${label}: GDUT preset time slots changed unexpectedly`);
  }
  if (captured.config?.semesterStartDate !== '2026-09-07') {
    throw new Error(`${label}: GDUT semester start parsing regressed: ${captured.config?.semesterStartDate}`);
  }
  if (captured.config?.semesterTotalWeeks !== 20 || captured.config?.firstDayOfWeek !== 1) {
    throw new Error(`${label}: GDUT course config contract changed unexpectedly`);
  }
  if (captured.alerts.length !== 1 || captured.selections.length !== 1) {
    throw new Error(`${label}: GDUT confirmation/semester selection flow changed unexpectedly`);
  }
  return captured;
}

const happy = await runScenario({
  label: 'happy-path',
  hostname: 'jxfw.gdut.edu.cn',
  probeBody: '<html>schedule</html>',
  coursePayload: {
    total: 3,
    rows: [
      {
        kcmc: '高等数学&amp;A',
        teaxms: '张老师',
        jxcdmc: '教1-101',
        xq: '1',
        jcdm: '0102',
        zc: '1'
      },
      {
        kcmc: '高等数学&amp;A',
        teaxms: '张老师',
        jxcdmc: '教1-101',
        xq: '1',
        jcdm: '0102',
        zc: '2'
      },
      {
        kcmc: '大学英语',
        teaxms: '',
        jxcdmc: '教3-202',
        xq: '3',
        jcdm: '0506',
        zc: '1'
      }
    ]
  }
});

// Auth host should not start the import dialogs.
const auth = await runScenario({
  label: 'auth-host',
  hostname: 'authserver.gdut.edu.cn',
  probeBody: '<html>login</html>',
  coursePayload: { total: 0, rows: [] },
  expectError: 'no-complete'
});
if (auth.alerts.length || auth.selections.length || auth.completed || auth.courses) {
  throw new Error('auth-host: adapter must not start import flow on CAS host');
}

// Course API returning the HTML login page must surface a clear error.
const htmlLogin = await runScenario({
  label: 'html-login-page',
  hostname: 'jxfw.gdut.edu.cn',
  probeBody: '<html>使用统一认证中心登录</html>',
  coursePayload: '<html>使用统一认证中心登录 login_form 请输入学号或工号</html>',
  expectError: 'toast-only'
});
if (!htmlLogin.toasts.some((t) => t.includes('会话') || t.includes('登录页') || t.includes('读取课表'))) {
  throw new Error(`html-login-page: expected a session/login guidance toast, got ${JSON.stringify(htmlLogin.toasts)}`);
}
if (htmlLogin.completed) {
  throw new Error('html-login-page: adapter must not complete when the session probe still returns a login page');
}

console.log(`GDUT adapter smoke test passed (${path.relative(root, scriptPath)}): ${happy.courses.length} raw meetings, ${happy.slots.length} slots, start ${happy.config.semesterStartDate}.`);
