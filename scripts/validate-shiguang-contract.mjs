import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const warehouse = process.env.SHIGUANG_SNAPSHOT
  ? path.resolve(root, process.env.SHIGUANG_SNAPSHOT)
  : path.join(root, 'vendor', 'shiguang_warehouse');
const resources = path.join(warehouse, 'resources');
const rootIndexPath = path.join(warehouse, 'index', 'root_index.yaml');

function fail(message) {
  console.error(`::error::${message}`);
  process.exitCode = 1;
}

function requireFile(file, label = file) {
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    fail(`Missing ${label}: ${path.relative(root, file)}`);
    return false;
  }
  return true;
}

function unquote(value) {
  const text = value.trim();
  if (text.length >= 2 && ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'")))) {
    return text.slice(1, -1);
  }
  return text;
}

function yamlField(block, key) {
  const match = block.match(new RegExp(`^\\s*${key}:\\s*(.+?)\\s*$`, 'm'));
  return match ? unquote(match[1].replace(/\s+#.*$/, '')) : '';
}

function adapterBlock(yaml, adapterId) {
  const blocks = yaml.split(/\n(?=\s*-\s+adapter_id:)/g);
  return blocks.find((block) => yamlField(block.replace(/^\s*-\s+/, ''), 'adapter_id') === adapterId) ?? '';
}

function walk(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

if (!requireFile(rootIndexPath, 'Shiguang root index') || !fs.existsSync(resources)) {
  process.exit(1);
}

const rootIndex = fs.readFileSync(rootIndexPath, 'utf8');
if (!/(?:^|\n)\s*-\s+id:\s*["']?GDUT["']?\s*(?:#.*)?$/m.test(rootIndex)) {
  fail('Upstream Shiguang snapshot no longer exposes school GDUT / 广东工业大学.');
}
if (!/name:\s*["']?广东工业大学["']?/m.test(rootIndex)) {
  fail('GDUT school entry no longer carries the expected 广东工业大学 display name.');
}

let manifestCount = 0;
let referencedScriptCount = 0;
for (const manifestPath of walk(resources).filter((file) => path.basename(file) === 'adapters.yaml')) {
  manifestCount += 1;
  const yaml = fs.readFileSync(manifestPath, 'utf8');
  for (const match of yaml.matchAll(/^\s*asset_js_path:\s*(.+?)\s*$/gm)) {
    const asset = unquote(match[1].replace(/\s+#.*$/, ''));
    if (!asset) continue;
    referencedScriptCount += 1;
    const script = path.resolve(path.dirname(manifestPath), asset);
    const base = path.resolve(path.dirname(manifestPath));
    if (!(script === base || script.startsWith(`${base}${path.sep}`))) {
      fail(`Adapter manifest escapes its resource directory: ${path.relative(root, manifestPath)} -> ${asset}`);
      continue;
    }
    requireFile(script, `adapter script referenced by ${path.relative(root, manifestPath)}`);
  }
}

const gdutDir = path.join(resources, 'GDUT');
const gdutManifestPath = path.join(gdutDir, 'adapters.yaml');
if (requireFile(gdutManifestPath, 'GDUT adapter manifest')) {
  const yaml = fs.readFileSync(gdutManifestPath, 'utf8');
  const block = adapterBlock(yaml, 'GDUT_01');
  if (!block) {
    fail('GDUT adapter manifest no longer contains adapter_id GDUT_01.');
  } else {
    const asset = yamlField(block.replace(/^\s*-\s+/, ''), 'asset_js_path');
    const importUrl = yamlField(block.replace(/^\s*-\s+/, ''), 'import_url');
    const scriptPath = path.join(gdutDir, asset || '__missing__.js');
    if (!asset) fail('GDUT_01 has no asset_js_path.');
    if (!importUrl) fail('GDUT_01 has no import_url.');
    if (importUrl) {
      try {
        const login = new URL(importUrl);
        if (login.protocol !== 'https:') fail(`GDUT_01 login must stay HTTPS, got ${login.protocol}`);
        if (login.hostname !== 'authserver.gdut.edu.cn') {
          fail(`GDUT_01 login host changed unexpectedly: ${login.hostname}`);
        }
        const service = login.searchParams.get('service');
        if (service) {
          const target = new URL(service);
          if (target.hostname !== 'jxfw.gdut.edu.cn') {
            fail(`GDUT_01 SSO service host changed unexpectedly: ${target.hostname}`);
          }
        } else {
          fail('GDUT_01 login URL no longer carries an SSO service target.');
        }
      } catch (error) {
        fail(`GDUT_01 import_url is invalid: ${error instanceof Error ? error.message : String(error)}`);
      }
    }

    if (requireFile(scriptPath, 'GDUT_01 adapter script')) {
      const script = fs.readFileSync(scriptPath, 'utf8');
      const requiredCalls = [
        ['shiguangBridgePromise', 'showAlert'],
        ['shiguangBridgePromise', 'showSingleSelection'],
        ['shiguangBridgePromise', 'saveCourseConfig'],
        ['shiguangBridgePromise', 'saveImportedCourses'],
        ['shiguangBridgePromise', 'savePresetTimeSlots'],
        ['shiguangBridge', 'notifyTaskCompletion']
      ];
      for (const [bridge, method] of requiredCalls) {
        if (!script.includes(`${bridge}.${method}`)) {
          fail(`GDUT_01 no longer calls ${bridge}.${method}; re-check LumaSchedule bridge compatibility.`);
        }
      }
      if (!script.includes('https://jxfw.gdut.edu.cn')) {
        fail('GDUT_01 no longer references the expected jxfw.gdut.edu.cn teaching system.');
      }
    }
  }
}

const supportedPromiseMethods = new Set([
  'showAlert',
  'showPrompt',
  'showSingleSelection',
  'saveImportedCourses',
  'savePresetTimeSlots',
  'saveCourseConfig'
]);
const supportedSyncMethods = new Set(['showToast', 'notifyTaskCompletion']);
const unknown = new Map();
const scripts = walk(resources).filter((file) => file.endsWith('.js'));
for (const file of scripts) {
  const source = fs.readFileSync(file, 'utf8');
  const patterns = [
    { re: /(?:window\.)?(?:shiguangBridgePromise|AndroidBridgePromise)\.([A-Za-z_$][\w$]*)/g, supported: supportedPromiseMethods, kind: 'promise' },
    { re: /(?:window\.)?(?:shiguangBridge|AndroidBridge)\.([A-Za-z_$][\w$]*)/g, supported: supportedSyncMethods, kind: 'sync' }
  ];
  for (const { re, supported, kind } of patterns) {
    for (const match of source.matchAll(re)) {
      const method = match[1];
      if (supported.has(method)) continue;
      const key = `${kind}:${method}`;
      const list = unknown.get(key) ?? [];
      if (list.length < 5) list.push(path.relative(resources, file));
      unknown.set(key, list);
    }
  }
}

for (const [method, files] of [...unknown.entries()].sort()) {
  console.warn(`::warning::Upstream adapters reference unsupported ${method} in ${files.join(', ')}`);
}

console.log(
  `Validated Shiguang snapshot: ${manifestCount} manifests, ${referencedScriptCount} referenced scripts, ${scripts.length} JS files; GDUT_01 contract is compatible.`
);

if (process.exitCode) process.exit(process.exitCode);
