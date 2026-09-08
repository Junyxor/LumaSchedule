#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'luma-wakeup-'));
const input = path.join(temp, 'input.json');
const output = path.join(temp, 'output.json');
fs.writeFileSync(input, JSON.stringify({ data: [
  { sortKey: 'G', name: '广东示例大学本科', url: 'https://jw.example.edu.cn', type: '本科', importType: 'zf_new', mode: '', customConf: { enableHTTPS: true } },
  { sortKey: 'G', name: '广东示例大学研究生', url: 'https://yjs.example.edu.cn', type: '研究生', importType: 'cb_postgraduate', mode: 'urp_new_ajax', customConf: { androidDocumentStartJs: 'window.test = true' } },
  { sortKey: 'C', name: '超星示例', url: 'https://example.edu.cn/cas', importType: 'login_chaoxing' },
  { sortKey: 'Q', name: '青果示例', url: 'jw.example.edu.cn', importType: 'qingguo-v1' }
] }));

const script = path.resolve('scripts/convert-wakeup-school-info.mjs');
const run = spawnSync(process.execPath, [script, input, output], { encoding: 'utf8' });
assert.equal(run.status, 0, run.stderr || run.stdout);
const result = JSON.parse(fs.readFileSync(output, 'utf8'));
assert.equal(result.format, 'lumaschedule-wakeup-compat');
assert.equal(result.profiles.length, 4);
assert.equal(result.profiles[0].inferredFamily, 'zhengfang_jiaowu');
assert.equal(result.profiles[0].degree, 'undergraduate');
assert.equal(result.profiles[1].inferredFamily, 'urp_jiaowu');
assert.equal(result.profiles[1].degree, 'postgraduate');
assert.equal(result.profiles[2].inferredFamily, 'chaoxing_jiaowu');
assert.equal(result.profiles[3].inferredFamily, 'qingguo_jiaowu');
assert.ok(result.profiles[3].url.startsWith('https://'));
assert.equal(result.summary.familyMapped, 4);
assert.equal(result.summary.postgraduate, 1);
console.log('WakeUp SchoolInfo converter smoke test: OK');
