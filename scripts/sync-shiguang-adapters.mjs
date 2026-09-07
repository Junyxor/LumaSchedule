import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const source = process.env.SHIGUANG_SOURCE
  ? path.resolve(root, process.env.SHIGUANG_SOURCE)
  : path.join(root, '.cache', 'shiguang_warehouse');
const vendor = path.join(root, 'vendor', 'shiguang_warehouse');

function run(cmd, args, cwd = root) {
  execFileSync(cmd, args, { cwd, stdio: 'inherit' });
}

if (!fs.existsSync(path.join(source, '.git'))) {
  fs.rmSync(source, { recursive: true, force: true });
  fs.mkdirSync(path.dirname(source), { recursive: true });
  run('git', ['clone', '--depth', '1', 'https://github.com/XingHeYuZhuan/shiguang_warehouse.git', source]);
} else if (!process.env.SHIGUANG_SOURCE) {
  run('git', ['fetch', '--depth', '1', 'origin', 'main'], source);
  run('git', ['reset', '--hard', 'origin/main'], source);
}

for (const required of ['index', 'resources', 'LICENSE']) {
  if (!fs.existsSync(path.join(source, required))) {
    throw new Error(`Upstream snapshot is missing ${required}`);
  }
}

fs.mkdirSync(vendor, { recursive: true });
for (const name of ['index', 'resources', 'LICENSE']) {
  fs.rmSync(path.join(vendor, name), { recursive: true, force: true });
  fs.cpSync(path.join(source, name), path.join(vendor, name), { recursive: true });
}

const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: source, encoding: 'utf8' }).trim();
const resourceDirs = fs.readdirSync(path.join(vendor, 'resources'), { withFileTypes: true }).filter((entry) => entry.isDirectory());
let adapterScriptCount = 0;
let adapterManifestCount = 0;
for (const dir of resourceDirs) {
  const base = path.join(vendor, 'resources', dir.name);
  for (const entry of fs.readdirSync(base, { withFileTypes: true })) {
    if (!entry.isFile()) continue;
    if (entry.name.endsWith('.js')) adapterScriptCount += 1;
    if (entry.name === 'adapters.yaml') adapterManifestCount += 1;
  }
}

const snapshot = {
  source: 'https://github.com/XingHeYuZhuan/shiguang_warehouse',
  revision,
  schoolCount: resourceDirs.length,
  adapterScriptCount,
  adapterManifestCount,
  generatedAt: new Date().toISOString(),
  bootstrap: false
};
fs.writeFileSync(path.join(vendor, 'SNAPSHOT.json'), `${JSON.stringify(snapshot, null, 2)}\n`);
console.log(`Synced shiguang_warehouse @ ${revision}: ${snapshot.schoolCount} resource folders, ${adapterScriptCount} JS adapters.`);
