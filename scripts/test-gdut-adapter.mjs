import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const root = process.cwd();
const warehouse = process.env.SHIGUANG_SNAPSHOT
  ? path.resolve(root, process.env.SHIGUANG_SNAPSHOT)
  : path.join(root, 'vendor', 'shiguang_warehouse');
const scriptPath = path.join(warehouse, 'resources', 'GDUT', 'gdut.js');

if (!fs.existsSync(scriptPath)) {
  throw new Error(`GDUT adapter script missing: ${scriptPath}`);
}

const captured = {
  alerts: [],
  selections: [],
  courses: null,
  slots: null,
  config: null,
  completed: false,
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
    createElement() {
      return {
        textContent: '',
        innerText: '',
        set innerHTML(value) {
          this.textContent = decodeHtml(value);
          this.innerText = this.textContent;
        }
      };
    }
  },
  location: { hostname: 'jxfw.gdut.edu.cn' },
  fetch: async (input, init = {}) => {
    const url = String(input);
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
      return {
        ok: true,
        status: 200,
        async json() {
          return {
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
          };
        }
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
  notifyTaskCompletion() { captured.completed = true; }
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

if (!captured.completed) throw new Error('GDUT adapter did not emit notifyTaskCompletion()');
if (!Array.isArray(captured.courses) || captured.courses.length !== 3) {
  throw new Error(`GDUT adapter expected 3 raw course meetings, got ${captured.courses?.length ?? 'none'}`);
}
if (captured.courses[0].name !== '高等数学&A') {
  throw new Error(`GDUT HTML entity decoding regressed: ${captured.courses[0].name}`);
}
if (captured.courses[0].startSection !== 1 || captured.courses[0].endSection !== 2) {
  throw new Error('GDUT section parsing regressed for jcdm=0102');
}
if (!Array.isArray(captured.slots) || captured.slots.length !== 14) {
  throw new Error(`GDUT adapter expected 14 time slots, got ${captured.slots?.length ?? 'none'}`);
}
if (captured.slots[0]?.startTime !== '08:30' || captured.slots[13]?.endTime !== '22:35') {
  throw new Error('GDUT preset time slots changed unexpectedly');
}
if (captured.config?.semesterStartDate !== '2026-09-07') {
  throw new Error(`GDUT semester start parsing regressed: ${captured.config?.semesterStartDate}`);
}
if (captured.config?.semesterTotalWeeks !== 20 || captured.config?.firstDayOfWeek !== 1) {
  throw new Error('GDUT course config contract changed unexpectedly');
}
if (captured.alerts.length !== 1 || captured.selections.length !== 1) {
  throw new Error('GDUT confirmation/semester selection flow changed unexpectedly');
}

console.log(`GDUT adapter smoke test passed: ${captured.courses.length} raw meetings, ${captured.slots.length} slots, start ${captured.config.semesterStartDate}.`);
