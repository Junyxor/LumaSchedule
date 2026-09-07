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

function field(block, key) {
  const match = block.match(new RegExp(`^\\s*${key}:\\s*(.+?)\\s*$`, 'm'));
  return match ? unquote(match[1].replace(/\s+#.*$/, '')) : '';
}

const rootIndexPath = path.join(warehouse, 'index', 'root_index.yaml');
const gdutDir = path.join(warehouse, 'resources', 'GDUT');
const manifestPath = path.join(gdutDir, 'adapters.yaml');
requireFile(rootIndexPath, 'Shiguang root index');
requireFile(manifestPath, 'GDUT adapter manifest');

const rootIndex = fs.readFileSync(rootIndexPath, 'utf8');
const gdutSchool = rootIndex.match(/(?:^|\n)\s*-\s+id:\s*["']?GDUT["']?[\s\S]*?(?=\n\s*-\s+id:|$)/m)?.[0] ?? '';
if (!gdutSchool) throw new Error('Upstream Shiguang snapshot no longer exposes school GDUT.');
if (!/name:\s*["']?广东工业大学["']?/m.test(gdutSchool)) {
  throw new Error('GDUT school entry no longer carries 广东工业大学 as its display name.');
}
if (!/resource_folder:\s*["']?GDUT["']?/m.test(gdutSchool)) {
  throw new Error('GDUT school entry changed its resource_folder unexpectedly.');
}

const manifest = fs.readFileSync(manifestPath, 'utf8');
const adapter = manifest.match(/(?:^|\n)\s*-\s+adapter_id:\s*["']?GDUT_01["']?[\s\S]*?(?=\n\s*-\s+adapter_id:|$)/m)?.[0] ?? '';
if (!adapter) throw new Error('GDUT adapter manifest no longer contains GDUT_01.');

const asset = field(adapter, 'asset_js_path');
const importUrl = field(adapter, 'import_url');
if (!asset) throw new Error('GDUT_01 has no asset_js_path.');
if (!importUrl) throw new Error('GDUT_01 has no import_url.');

const scriptPath = path.resolve(gdutDir, asset);
if (!scriptPath.startsWith(`${path.resolve(gdutDir)}${path.sep}`)) {
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
  if (!source.includes(call)) throw new Error(`GDUT_01 no longer calls ${call}; re-check LumaSchedule bridge compatibility.`);
}
if (!source.includes('https://jxfw.gdut.edu.cn')) {
  throw new Error('GDUT_01 no longer references jxfw.gdut.edu.cn.');
}

console.log(`GDUT contract validated: ${path.relative(warehouse, scriptPath)} -> ${login.hostname} -> ${target.hostname}`);
