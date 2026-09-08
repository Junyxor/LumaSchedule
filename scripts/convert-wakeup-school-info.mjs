#!/usr/bin/env node
import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';

const args = process.argv.slice(2);
const stripJs = args.includes('--strip-js');
const positional = args.filter((arg) => !arg.startsWith('--'));
const [inputPath, outputPath] = positional;

if (!inputPath || !outputPath) {
  console.error('Usage: node scripts/convert-wakeup-school-info.mjs <plaintext.json> <output.json> [--strip-js]');
  process.exit(2);
}

function text(value) {
  return value == null ? '' : String(value).trim();
}

function stableId(item, index) {
  const seed = [text(item.name), text(item.url), text(item.importType), text(item.type), text(item.mode), index].join('\u001f');
  return `wakeup-${crypto.createHash('sha256').update(seed).digest('hex').slice(0, 16)}`;
}

function normalizeUrl(raw) {
  const value = text(raw);
  if (!value) return '';
  let candidate = value;
  if (!/^https?:\/\//i.test(candidate)) candidate = `https://${candidate}`;
  try {
    const url = new URL(candidate);
    if (!['http:', 'https:'].includes(url.protocol)) return '';
    if (url.username || url.password) return '';
    return url.toString();
  } catch {
    return '';
  }
}

function inferFamily(item) {
  const haystack = [item.importType, item.type, item.mode, item.name, item.url].map(text).join(' ').toLowerCase();
  if (/\b(zf|zhengfang)[_-]|正方|zf_new|zf_cls|zf_1/.test(haystack)) return 'zhengfang_jiaowu';
  if (/\burp[_-]|urp_new|urp_new_ajax|\burp\b/.test(haystack)) return 'urp_jiaowu';
  if (/qingguo|青果/.test(haystack)) return 'qingguo_jiaowu';
  if (/chaoxing|超星|login_chaoxing/.test(haystack)) return 'chaoxing_jiaowu';
  return null;
}

function inferDegree(item) {
  const haystack = [item.importType, item.type, item.mode, item.name].map(text).join(' ').toLowerCase();
  if (/cb_postgraduate|postgraduate|研究生|硕士|博士/.test(haystack)) return 'postgraduate';
  if (/undergraduate|本科|学士/.test(haystack)) return 'undergraduate';
  return 'unspecified';
}

function customConf(raw) {
  const conf = raw && typeof raw === 'object' ? raw : {};
  const result = {
    enableHTTPS: conf.enableHTTPS ?? null,
    noProxy: conf.noProxy ?? null,
    landscapeMode: conf.landscapeMode ?? null,
    androidZiyanType: conf.androidZiyanType ?? null,
    mockSSUtterance: conf.mockSSUtterance ?? null
  };
  if (!stripJs && text(conf.androidDocumentStartJs)) {
    // Preserve for manual review only. LumaSchedule must never execute this on the main app origin.
    result.androidDocumentStartJs = text(conf.androidDocumentStartJs);
  }
  return result;
}

function extractItems(root) {
  if (Array.isArray(root)) return root;
  if (Array.isArray(root?.data)) return root.data;
  if (Array.isArray(root?.schools)) return root.schools;
  throw new Error('Unsupported plaintext dump shape. Expected an array, AdapterInfo.data, or schools[].');
}

let root;
try {
  root = JSON.parse(fs.readFileSync(inputPath, 'utf8'));
} catch (error) {
  console.error('WakeUp school list conversion requires a plaintext JSON dump of AdapterInfo/SchoolInfo. The bundled school_info_android_new.txt is encrypted and cannot be converted directly.');
  console.error(String(error?.message || error));
  process.exit(1);
}

const sourceItems = extractItems(root);
const profiles = sourceItems.map((item, index) => {
  const url = normalizeUrl(item?.url);
  return {
    id: stableId(item ?? {}, index),
    name: text(item?.name) || `WakeUp school ${index + 1}`,
    sortKey: text(item?.sortKey),
    url,
    degree: inferDegree(item ?? {}),
    inferredFamily: inferFamily(item ?? {}),
    wakeup: {
      type: text(item?.type),
      importType: text(item?.importType),
      mode: text(item?.mode),
      minVersion: Number.isFinite(Number(item?.minVersion)) ? Number(item.minVersion) : null,
      applyCompat: text(item?.applyCompat)
    },
    customConf: customConf(item?.customConf),
    reviewRequired: !url || !inferFamily(item ?? {}) || Boolean(text(item?.customConf?.androidDocumentStartJs))
  };
});

const summary = {
  total: profiles.length,
  familyMapped: profiles.filter((item) => item.inferredFamily).length,
  unmapped: profiles.filter((item) => !item.inferredFamily).length,
  undergraduate: profiles.filter((item) => item.degree === 'undergraduate').length,
  postgraduate: profiles.filter((item) => item.degree === 'postgraduate').length,
  unspecifiedDegree: profiles.filter((item) => item.degree === 'unspecified').length,
  customDocumentStartJs: profiles.filter((item) => item.customConf.androidDocumentStartJs).length
};

const output = {
  format: 'lumaschedule-wakeup-compat',
  version: 1,
  generatedAt: new Date().toISOString(),
  source: `WakeUp SchoolInfo plaintext dump (${path.basename(inputPath)})`,
  parserScope: 'profile-and-login-metadata-only',
  warning: 'WakeUp server-side schedule parsers are not included. Unknown importType values remain unmapped and require a reviewed local adapter.',
  profiles,
  summary
};

fs.writeFileSync(outputPath, `${JSON.stringify(output, null, 2)}\n`, 'utf8');
console.log(`Converted ${summary.total} profiles; mapped ${summary.familyMapped}, unmapped ${summary.unmapped}, postgraduate ${summary.postgraduate}.`);
