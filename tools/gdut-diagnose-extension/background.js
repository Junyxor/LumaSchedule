// Manual click diagnostic for GDUT. Click-only. One semester. No term scanning.

chrome.action.onClicked.addListener(async (tab) => {
  if (!tab || !tab.id) return;
  if (!/^https:\/\/jxfw\.gdut\.edu\.cn\//i.test(tab.url || '')) {
    await notify(tab.id, '请先打开并登录 https://jxfw.gdut.edu.cn ，再点本扩展。');
    return;
  }
  try {
    const [{ result }] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      world: 'MAIN',
      func: runDiagnose
    });
    if (!result) {
      await notify(tab.id, '诊断脚本没有返回结果。');
      return;
    }
    const dataUrl = 'data:application/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(result, null, 2));
    await chrome.downloads.download({
      url: dataUrl,
      filename: `gdut-diagnose-${new Date().toISOString().replace(/[:.]/g, '-')}.json`,
      saveAs: true
    });
  } catch (error) {
    await notify(tab.id, '诊断失败：' + String(error && error.message || error));
  }
});

async function notify(tabId, message) {
  try {
    await chrome.scripting.executeScript({
      target: { tabId },
      world: 'MAIN',
      func: (text) => { alert(text); },
      args: [message]
    });
  } catch (_) {
    console.warn(message);
  }
}

async function runDiagnose() {
  const log = [];

  function extractSemesters(html) {
    const m = String(html || '').match(/<select[^>]*id=["']xnxqdm["'][^>]*>([\s\S]*?)<\/select>/i);
    if (!m) return [];
    const out = [];
    const re = /<option[^>]*value=["']([^"']+)["']([^>]*)>([\s\S]*?)<\/option>/gi;
    let x;
    while ((x = re.exec(m[1])) !== null) {
      out.push({
        value: x[1].trim(),
        selected: /selected/i.test(x[2] || ''),
        label: String(x[3] || '').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
      });
    }
    return out;
  }

  function parseKbxx(html) {
    const m = String(html || '').match(/(?:var\s+)?kbxx\s*=\s*(\[[\s\S]*?\])\s*;/i);
    if (!m) return { present: false, len: null, sample: null };
    try {
      const arr = JSON.parse(m[1]);
      return { present: true, len: Array.isArray(arr) ? arr.length : null, sample: Array.isArray(arr) ? arr[0] || null : null };
    } catch (e) {
      return { present: true, len: null, parseError: String(e && e.message || e) };
    }
  }

  function extractAjax(html) {
    const text = String(html || '');
    const hits = [];
    const patterns = [
      /url\s*:\s*["']([^"']+)["']/gi,
      /href\s*=\s*["']([^"']+\.action[^"']*)["']/gi,
      /["']([^"']*\.action[^"']*)["']/gi
    ];
    for (const re of patterns) {
      let m;
      while ((m = re.exec(text)) !== null) {
        const u = m[1];
        if (/action|getData|kb|skxx|xskb|xsgr/i.test(u)) hits.push(u);
      }
    }
    // queryParams blocks
    const qps = [];
    const qre = /queryParams\s*:\s*\{([\s\S]*?)\}/gi;
    let q;
    while ((q = qre.exec(text)) !== null) qps.push(q[1].replace(/\s+/g, ' ').trim().slice(0, 300));
    return { urls: Array.from(new Set(hits)).slice(0, 30), queryParams: qps };
  }

  function summarize(html, label) {
    const text = String(html || '');
    const ajax = extractAjax(text);
    return {
      label,
      length: text.length,
      title: (text.match(/<title>([\s\S]*?)<\/title>/i) || [])[1] || '',
      kbxx: parseKbxx(text),
      tables: (text.match(/<table[\s\S]*?<\/table>/gi) || []).length,
      hasCourseWord: /课程名称|节次|上课地点|任课|教师|课程代码|课序号/.test(text),
      looksDenied: /非法访问|没有该权限/.test(text),
      looksError: /系统出错/.test(text),
      ajax,
      html: text
    };
  }

  async function get(url) {
    const res = await fetch(url, { credentials: 'include' });
    const text = await res.text();
    log.push({ kind: 'GET', url, status: res.status, length: text.length });
    return { status: res.status, length: text.length, text };
  }

  const shell = await get('https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action');
  const semesters = extractSemesters(shell.text);
  const selected = semesters.find((s) => s.selected) || null;
  const term = (selected && selected.value) || '202601';

  const paths = [
    'xsgrkbcx!xsAllKbList.action?xnxqdm=',
    'xsgrkbcx!xskbList.action?xnxqdm=',
    'xsgrkbcx!xskbList2.action?xnxqdm=',
    'xsgrkbcx!xsgrkbMain.action?xnxqdm='
  ];
  const views = [];
  for (const path of paths) {
    const url = 'https://jxfw.gdut.edu.cn/' + path + encodeURIComponent(term);
    const res = await get(url);
    views.push(summarize(res.text, path));
  }

  // One follow-up: first datagrid/ajax URL from xskbList2 that we have not already fetched.
  let followUp = null;
  const list2 = views.find((v) => v.label.indexOf('xskbList2') >= 0);
  const already = new Set(paths.map((p) => p.split('?')[0].split('!')[1]));
  const cand = ((list2 && list2.ajax.urls) || []).find((u) => {
    const name = (u.split('!')[1] || u).split('?')[0];
    return name && !already.has(name) && !/getSkxxDataList|getDataList/i.test(u);
  });
  if (cand) {
    const abs = cand.startsWith('http') ? cand : (cand.startsWith('/') ? 'https://jxfw.gdut.edu.cn' + cand : 'https://jxfw.gdut.edu.cn/' + cand);
    const withTerm = abs.includes('xnxqdm=') ? abs : (abs + (abs.includes('?') ? '&' : '?') + 'xnxqdm=' + encodeURIComponent(term));
    try {
      const res = await get(withTerm);
      followUp = summarize(res.text, withTerm);
      followUp.requestedUrl = withTerm;
    } catch (e) {
      followUp = { requestedUrl: withTerm, error: String(e && e.message || e) };
    }
  }

  return {
    generatedAt: new Date().toISOString(),
    pageUrl: location.href,
    targetSemester: term,
    selectedSemester: selected,
    semesterSample: semesters.filter((s) => s.selected || /^202[5-7]/.test(s.value)).slice(0, 12),
    requestLog: log,
    shell: summarize(shell.text, 'getXsgrbkList'),
    views,
    followUp
  };
}
