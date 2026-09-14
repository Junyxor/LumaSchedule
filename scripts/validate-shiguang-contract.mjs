import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const warehouse = process.env.SHIGUANG_SNAPSHOT
  ? path.resolve(root, process.env.SHIGUANG_SNAPSHOT)
  : path.join(root, 'vendor', 'shiguang_warehouse');

function requireFile(file, label) {
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    throw new Error(`${label} missing: ${path.relative(root, file)}`);
  }
}

function unquote(value) {
  const text = value.trim();
  if (text.length >= 2 && ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'")))) {
    return text.slice(1, -1);
  }
  return text;
}

function field(text, key) {
  const match = text.match(new RegExp(`^\\s*${key}:\\s*(.+?)\\s*$`, 'm'));
  return match ? unquote(match[1].replace(/\s+#.*$/, '')) : '';
}

const rootIndexPath = path.join(warehouse, 'index', 'root_index.yaml');
const gdutDir = path.join(warehouse, 'resources', 'GDUT');
const manifestPath = path.join(gdutDir, 'adapters.yaml');
requireFile(rootIndexPath, 'Shiguang root index');
requireFile(manifestPath, 'GDUT adapter manifest');

const rootIndex = fs.readFileSync(rootIndexPath, 'utf8');
if (!/^\s*-\s+id:\s*["']?GDUT["']?\s*$/m.test(rootIndex)) {
  throw new Error('Upstream Shiguang snapshot no longer exposes school GDUT.');
}
if (!/^\s*name:\s*["']?广东工业大学["']?\s*$/m.test(rootIndex)) {
  throw new Error('Upstream Shiguang snapshot no longer exposes 广东工业大学 by that display name.');
}
if (!/^\s*resource_folder:\s*["']?GDUT["']?\s*$/m.test(rootIndex)) {
  throw new Error('GDUT resource_folder changed unexpectedly.');
}

const manifest = fs.readFileSync(manifestPath, 'utf8');
if (!/^\s*-\s+adapter_id:\s*["']?GDUT_01["']?\s*$/m.test(manifest)) {
  throw new Error('GDUT adapter manifest no longer contains GDUT_01.');
}

const asset = field(manifest, 'asset_js_path');
const importUrl = field(manifest, 'import_url');
if (!asset) throw new Error('GDUT_01 has no asset_js_path.');
if (!importUrl) throw new Error('GDUT_01 has no import_url.');

const scriptPath = path.resolve(gdutDir, asset);
const gdutBase = path.resolve(gdutDir);
if (!(scriptPath === gdutBase || scriptPath.startsWith(`${gdutBase}${path.sep}`))) {
  throw new Error(`GDUT_01 asset path escapes its resource directory: ${asset}`);
}
requireFile(scriptPath, 'GDUT_01 adapter script');

const login = new URL(importUrl);
if (login.protocol !== 'https:') throw new Error(`GDUT_01 login must use HTTPS, got ${login.protocol}`);
if (login.hostname !== 'authserver.gdut.edu.cn') {
  throw new Error(`GDUT_01 login host changed unexpectedly: ${login.hostname}`);
}
const service = login.searchParams.get('service');
if (!service) throw new Error('GDUT_01 login URL no longer carries an SSO service target.');
const target = new URL(service);
if (target.hostname !== 'jxfw.gdut.edu.cn') {
  throw new Error(`GDUT_01 SSO service host changed unexpectedly: ${target.hostname}`);
}

const source = fs.readFileSync(scriptPath, 'utf8');
const requiredCalls = [
  'shiguangBridgePromise.showAlert',
  'shiguangBridgePromise.showSingleSelection',
  'shiguangBridgePromise.saveCourseConfig',
  'shiguangBridgePromise.saveImportedCourses',
  'shiguangBridgePromise.savePresetTimeSlots',
  'shiguangBridge.notifyTaskCompletion'
];
for (const call of requiredCalls) {
  if (!source.includes(call)) {
    throw new Error(`GDUT_01 no longer calls ${call}; re-check LumaSchedule bridge compatibility.`);
  }
}
if (!source.includes('https://jxfw.gdut.edu.cn')) {
  throw new Error('GDUT_01 no longer references jxfw.gdut.edu.cn.');
}

console.log(`GDUT contract validated: ${path.relative(warehouse, scriptPath)} -> ${login.hostname} -> ${target.hostname}`);

const overrideScript = path.join(root, 'native', 'android', 'adapter_overrides', 'shiguang_overrides', 'GDUT', 'gdut.js');
if (fs.existsSync(overrideScript)) {
  const overrideSource = fs.readFileSync(overrideScript, 'utf8');
  for (const call of requiredCalls) {
    if (!overrideSource.includes(call)) {
      throw new Error(`GDUT override no longer calls ${call}; re-check LumaSchedule bridge compatibility.`);
    }
  }
  if (!overrideSource.includes('https://jxfw.gdut.edu.cn')) {
    throw new Error('GDUT override no longer references jxfw.gdut.edu.cn.');
  }
  if (!overrideSource.includes('reportError') && !overrideSource.includes('shiguangBridge.reportError')) {
    throw new Error('GDUT override should surface failures via shiguangBridge.reportError.');
  }
  console.log(`GDUT override validated: ${path.relative(root, overrideScript)}`);
}
