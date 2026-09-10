// GDUT adapter override for LumaSchedule.
// Hardened against SSO landing races, HTML login-page responses, and
// semester-code mismatches on the classic jxfw actions.

if (typeof url_strings === 'undefined') {
    var url_strings = {
        BASE_URL: "https://jxfw.gdut.edu.cn",
        GET_WEEK_COURSES_API_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!getKbRq.action",
        GET_ALL_COURSES_API_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!getDataList.action",
        GET_ALL_COURSES_HTML_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!xsAllKbList.action",
        GET_ALL_COURSES_HTML_URL_REFERRER: "https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action",
        SESSION_PROBE_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action",
        SEMESTER_SOURCE_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action",
        SSO_LOGIN_URL: "https://jxfw.gdut.edu.cn/new/ssoLogin"
    };
}

var __lumaGdutFlowRunning = false;

function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

function currentHost() {
    try { return String(window.location.hostname || '').toLowerCase(); } catch (e) { return ''; }
}

function currentPath() {
    try { return String(window.location.pathname || ''); } catch (e) { return ''; }
}

function isAuthHost(hostname) {
    var host = String(hostname || currentHost()).toLowerCase();
    return host === 'authserver.gdut.edu.cn' || host.endsWith('.authserver.gdut.edu.cn');
}

function isJxfwHost(hostname) {
    var host = String(hostname || currentHost()).toLowerCase();
    return host === 'jxfw.gdut.edu.cn';
}

function looksLikeLoginHtml(text) {
    var html = String(text || '');
    if (!html) return false;
    return html.indexOf('使用统一认证中心登录') >= 0
        || html.indexOf('请输入学号或工号') >= 0
        || html.indexOf('login_form') >= 0
        || html.indexOf('Unified identity authentication') >= 0;
}

function toastOnly(message) {
    try { window.shiguangBridge.showToast(String(message || '')); } catch (e) { }
}

function fail(message) {
    var text = String(message || '导入失败');
    toastOnly(text);
    try {
        if (window.shiguangBridge && typeof window.shiguangBridge.reportError === 'function') {
            window.shiguangBridge.reportError(text);
        }
    } catch (e) { }
}

async function waitForDocumentReady() {
    for (var i = 0; i < 20; i += 1) {
        try {
            if (document.readyState === 'complete' || document.readyState === 'interactive') return;
        } catch (e) { }
        await sleep(100);
    }
}

function stripTags(html) {
    return String(html || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
}

function decodeHtmlEntities(text) {
    if (!text) return '';
    try {
        const div = document.createElement('div');
        div.innerHTML = text;
        return div.textContent || div.innerText || '';
    } catch (e) {
        return String(text)
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&quot;/g, '"')
            .replace(/&#39;/g, "'");
    }
}

function extractSemesterOptions(htmlText) {
    const selectMatch = String(htmlText || '').match(/<select[^>]*id=['"]xnxqdm['"][^>]*>([\s\S]*?)<\/select>/i);
    if (!selectMatch) return null;
    const options = [];
    const optionRe = /<option[^>]*value=['"]([^'"]+)['"]([^>]*)>([\s\S]*?)<\/option>/gi;
    let m;
    while ((m = optionRe.exec(selectMatch[1])) !== null) {
        const value = (m[1] || '').trim();
        const attrs = m[2] || '';
        const label = stripTags(m[3] || '');
        if (!value || !label) continue;
        options.push({ value: value, label: label, selected: /selected/i.test(attrs) });
    }
    return options.length ? options : null;
}

function buildFallbackSemesters() {
    const now = new Date();
    const currentYear = now.getFullYear();
    const currentMonth = now.getMonth() + 1;
    const currentSemester = currentMonth >= 7 || currentMonth <= 1 ? 1 : 2;
    let currentSemesterYear = currentSemester === 1 ? currentYear : currentYear - 1;
    currentSemesterYear = currentMonth <= 1 ? currentSemesterYear - 1 : currentSemesterYear;
    const nextSemester = currentSemester === 1 ? 2 : 1;
    const nextSemesterYear = currentSemester === 1 ? currentSemesterYear : currentSemesterYear + 1;

    const options = [];
    for (let semesterYear = nextSemesterYear; semesterYear >= nextSemesterYear - 6; semesterYear--) {
        for (let semester = semesterYear === nextSemesterYear ? nextSemester : 2; semester >= 1; semester--) {
            // Try the two common GDUT code shapes: 202601 and 20261.
            options.push({
                value: `${semesterYear}0${semester}`,
                label: `${semester === 1 ? semesterYear : semesterYear + 1}年${semester === 1 ? "秋季" : "春季"} (${semesterYear}-${semesterYear + 1} 学年第${semester}学期)`,
                selected: false
            });
            options.push({
                value: `${semesterYear}${semester}`,
                label: `${semester === 1 ? semesterYear : semesterYear + 1}年${semester === 1 ? "秋季" : "春季"} · 短码 ${semesterYear}${semester}`,
                selected: false
            });
        }
    }
    // Default to the current fall/spring semester (index of first current-year fall/spring).
    const defaultLabelNeedle = currentSemester === 1 ? `${currentSemesterYear}年秋季` : `${currentSemesterYear + 1}年春季`;
    const defaultIndex = Math.max(0, options.findIndex((item) => item.label.indexOf(defaultLabelNeedle) === 0));
    options[defaultIndex].selected = true;
    return options;
}

async function fetchText(url, init) {
    const response = await fetch(url, init || { credentials: 'include' });
    return await response.text();
}

async function discoverSemesters() {
    // Prime the classic schedule entry so the session has visited 课表查询.
    try {
        const html = await fetchText(url_strings.SEMESTER_SOURCE_URL, {
            method: 'GET',
            headers: { 'Referer': url_strings.BASE_URL },
            credentials: 'include',
            redirect: 'follow'
        });
        if (looksLikeLoginHtml(html)) {
            console.warn('学期来源页返回登录页');
        } else {
            const fromPage = extractSemesterOptions(html);
            if (fromPage && fromPage.length) {
                console.log(`从教务页解析到 ${fromPage.length} 个学期选项`);
                return fromPage;
            }
        }
    } catch (error) {
        console.warn('解析教务学期下拉框失败', error);
    }

    // Second chance: the all-course list page often carries the same select.
    try {
        const html = await fetchText(url_strings.GET_ALL_COURSES_HTML_URL, {
            method: 'GET',
            headers: { 'Referer': url_strings.SEMESTER_SOURCE_URL },
            credentials: 'include',
            redirect: 'follow'
        });
        if (!looksLikeLoginHtml(html)) {
            const fromPage = extractSemesterOptions(html);
            if (fromPage && fromPage.length) return fromPage;
        }
    } catch (error) {
        console.warn('解析全量课表页学期失败', error);
    }

    console.log('回退到本地推算学期列表');
    return buildFallbackSemesters();
}

async function stepDescriptionAlert() {
    try {
        const confirmed = await window.shiguangBridgePromise.showAlert(
            "提示",
            "即将从广东工业大学教务系统读取课表。请确认已通过统一身份认证登录（无需手动打开课表页）。",
            "确认"
        );
        return confirmed;
    } catch (error) {
        console.error("显示弹窗时发生错误:", error);
        return false;
    }
}

async function selectSemesterSelection(options) {
    const list = options && options.length ? options : buildFallbackSemesters();
    const labels = list.map(function (item) { return item.label; });
    let defaultIndex = list.findIndex(function (item) { return item.selected; });
    if (defaultIndex < 0) defaultIndex = Math.min(1, list.length - 1);

    try {
        const selectedIndex = await window.shiguangBridgePromise.showSingleSelection(
            "选择要导入的学期",
            JSON.stringify(labels),
            defaultIndex
        );
        if (selectedIndex !== null && selectedIndex >= 0 && selectedIndex < list.length) {
            console.log("用户选择了: " + list[selectedIndex].label + " => " + list[selectedIndex].value);
            return list[selectedIndex];
        }
        console.log("用户取消了选择。");
        return null;
    } catch (error) {
        console.error("显示单选列表弹窗时发生错误:", error);
        window.shiguangBridge.showToast("Single Selection：显示列表出错！" + error.message);
        return null;
    }
}

function extractFirstDay(dateInfoJsonData) {
    try {
        const jsonArray = JSON.parse(dateInfoJsonData);
        const dateInfoArray = jsonArray[1];
        for (const dateInfo of dateInfoArray) {
            if (dateInfo.xqmc === "1" && dateInfo.rq) {
                return dateInfo.rq;
            }
        }
        console.error('未找到 xqmc=1 的日期项');
        return null;
    } catch (error) {
        console.error('解析 JSON 失败:', error);
        return null;
    }
}

async function fetchStartDate(semesterId) {
    const candidates = [
        `${url_strings.GET_WEEK_COURSES_API_URL}?xnxqdm=${encodeURIComponent(semesterId)}&zc=1`,
        `${url_strings.GET_WEEK_COURSES_API_URL}?xnxqdm=${encodeURIComponent(semesterId)}`
    ];
    for (const url of candidates) {
        try {
            console.log(`正在获取学期开始日期。学期代码：${semesterId}`);
            const data = await fetchText(url, {
                method: 'GET',
                headers: { 'Referer': url },
                credentials: 'include'
            });
            if (looksLikeLoginHtml(data)) continue;
            const startDateString = extractFirstDay(data);
            if (!startDateString) continue;
            const date = new Date(startDateString);
            if (!isNaN(date.getTime())) {
                console.log(`成功获取学期开始日期: ${date.toISOString().split('T')[0]}`);
                return date;
            }
        } catch (error) {
            console.warn('获取学期开始日期失败', error);
        }
    }
    return new Date();
}

async function postCourseJson(body) {
    const response = await fetch(url_strings.GET_ALL_COURSES_API_URL, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'Referer': url_strings.SEMESTER_SOURCE_URL
        },
        body: body.toString(),
        credentials: 'include'
    });
    if (!response.ok) {
        throw new Error(`请求失败: ${response.status}`);
    }
    const rawText = await response.text();
    if (looksLikeLoginHtml(rawText)) {
        return { loginPage: true };
    }
    try {
        return { data: JSON.parse(rawText) };
    } catch (parseError) {
        return { nonJson: true, text: rawText.slice(0, 200) };
    }
}

async function fetchCoursesFromJson(semesterId) {
    const pageSize = 1000;
    const variants = [];

    const full = new URLSearchParams();
    full.append('zc', '');
    full.append('xnxqdm', semesterId);
    full.append('page', '1');
    full.append('rows', String(pageSize));
    full.append('sort', 'kxh');
    full.append('order', 'asc');
    variants.push(full);

    const lean = new URLSearchParams();
    lean.append('xnxqdm', semesterId);
    lean.append('page', '1');
    lean.append('rows', String(pageSize));
    variants.push(lean);

    const withWeek = new URLSearchParams();
    withWeek.append('zc', '1');
    withWeek.append('xnxqdm', semesterId);
    withWeek.append('page', '1');
    withWeek.append('rows', String(pageSize));
    variants.push(withWeek);

    let sawLogin = false;
    for (let i = 0; i < variants.length; i += 1) {
        try {
            console.log(`尝试课程 JSON 接口变体 #${i + 1}, xnxqdm=${semesterId}`);
            const result = await postCourseJson(variants[i]);
            if (result.loginPage) {
                sawLogin = true;
                continue;
            }
            if (result.nonJson) {
                console.warn('课程接口非 JSON:', result.text);
                continue;
            }
            const rawData = result.data;
            if (!rawData || !Array.isArray(rawData.rows)) continue;
            if (rawData.rows.length > 0) {
                console.log(`JSON 接口命中 ${rawData.rows.length} 条, xnxqdm=${semesterId}`);
                return parseCourses(rawData.rows);
            }
            console.log(`JSON 接口变体 #${i + 1} 返回空列表`);
        } catch (error) {
            console.warn(`课程 JSON 变体 #${i + 1} 失败`, error);
        }
    }
    if (sawLogin) {
        throw new Error('教务接口返回登录页，统一认证会话未建立。请点右上角「读取课表」重试。');
    }
    return null;
}

function parseScheduleTable(htmlText) {
    // Best-effort fallback for classic HTML 课表 tables.
    const docHtml = String(htmlText || '');
    if (looksLikeLoginHtml(docHtml)) return null;
    const tables = docHtml.match(/<table[\s\S]*?<\/table>/gi) || [];
    const courses = [];
    const seen = {};
    for (const table of tables) {
        if (!/课程|节次|星期|周次|上课/.test(table)) continue;
        const rowRe = /<tr[\s\S]*?<\/tr>/gi;
        let rowMatch;
        while ((rowMatch = rowRe.exec(table)) !== null) {
            const row = rowMatch[0];
            if (/<th[\s\S]*?>/.test(row) && !/<td[\s\S]*?>/.test(row)) continue;
            const cells = [];
            const cellRe = /<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi;
            let cellMatch;
            while ((cellMatch = cellRe.exec(row)) !== null) {
                cells.push(stripTags(decodeHtmlEntities(cellMatch[1])));
            }
            if (cells.length < 3) continue;
            const joined = cells.join(' | ');
            const dayMatch = joined.match(/(?:星期|周)\s*([1-7日天])/);
            const sectionMatch = joined.match(/第?\s*(\d{1,2})\s*[-~—–至到]\s*(\d{1,2})\s*节?/) || joined.match(/第\s*(\d{1,2})\s*节/);
            const weekMatch = joined.match(/第?\s*(\d{1,2})\s*[-~—–至到]\s*(\d{1,2})\s*周/) || joined.match(/第?\s*(\d{1,2})\s*周/);
            if (!dayMatch || !sectionMatch) continue;
            const dayMap = { '1': 1, '2': 2, '3': 3, '4': 4, '5': 5, '6': 6, '7': 7, '日': 7, '天': 7 };
            const day = dayMap[String(dayMatch[1])] || 0;
            if (!day) continue;
            const startSection = Number(sectionMatch[1]);
            const endSection = sectionMatch[2] ? Number(sectionMatch[2]) : startSection;
            const name = cells.find(function (cell) {
                return cell && !/^(星期|周)[1-7日天]$/.test(cell)
                    && !/^\d+\s*[-~—–至到]\s*\d+\s*节?$/.test(cell)
                    && !/^\d+\s*[-~—–至到]\s*\d+\s*周$/.test(cell)
                    && !/^\d+$/.test(cell)
                    && !/^(第?\d+.*节|第?\d+.*周)/.test(cell)
                    && cell.length >= 2;
            }) || '';
            if (!name) continue;
            const key = [name, day, startSection, endSection].join('|');
            if (seen[key]) continue;
            seen[key] = true;
            courses.push({
                name: name,
                teacher: '',
                position: '',
                day: day,
                startSection: startSection,
                endSection: endSection,
                weeks: weekMatch ? [Number(weekMatch[1])] : [],
                isCustomTime: false
            });
        }
    }
    return courses.length ? courses : null;
}

async function fetchCoursesFromHtml(semesterId) {
    const urls = [
        `${url_strings.GET_ALL_COURSES_HTML_URL}?xnxqdm=${encodeURIComponent(semesterId)}`,
        `${url_strings.GET_ALL_COURSES_HTML_URL_REFERRER}?xnxqdm=${encodeURIComponent(semesterId)}`
    ];
    for (const url of urls) {
        try {
            const html = await fetchText(url, {
                method: 'GET',
                headers: { 'Referer': url_strings.SEMESTER_SOURCE_URL },
                credentials: 'include',
                redirect: 'follow'
            });
            if (looksLikeLoginHtml(html)) continue;
            if (html.indexOf('本学期课表还未开放') >= 0) continue;
            const parsed = parseScheduleTable(html);
            if (parsed && parsed.length) {
                console.log(`HTML 课表解析到 ${parsed.length} 条: ${url}`);
                return parsed;
            }
        } catch (error) {
            console.warn('HTML 课表回退失败', error);
        }
    }
    return null;
}

async function fetchCourses(semesterId) {
    try {
        console.log(`正在获取学期 ${semesterId} 的课程数据...`);
        const fromJson = await fetchCoursesFromJson(semesterId);
        if (fromJson && fromJson.length) return fromJson;

        const fromHtml = await fetchCoursesFromHtml(semesterId);
        if (fromHtml && fromHtml.length) return fromHtml;

        if (await checkSemesterIsOpened(semesterId)) {
            throw new Error(`学期 ${semesterId} 没有返回课程。请换一个学期重试；若网页课表有课，请把该提示反馈给开发者。`);
        }
        throw new Error('学期未开放课表查询！');
    } catch (error) {
        console.error('添加课程表失败:', error);
        fail(`添加课程失败: ${error.message}`);
        return null;
    }
}

async function checkSemesterIsOpened(semesterId) {
    try {
        const url = `${url_strings.GET_ALL_COURSES_HTML_URL}?xnxqdm=${encodeURIComponent(semesterId)}`;
        const html = await fetchText(url, {
            method: 'GET',
            headers: { 'Referer': url_strings.GET_ALL_COURSES_HTML_URL_REFERRER },
            credentials: 'include'
        });
        if (looksLikeLoginHtml(html)) return false;
        return html.indexOf("本学期课表还未开放，请稍后查询！") < 0;
    } catch (e) {
        return true;
    }
}

function parseCourses(rawCourses) {
    console.log(`正在转换原始课程数据...`);
    const courses = [];
    for (const raw of rawCourses) {
        const sectionMatch = String(raw.jcdm || '').match(/\d{2}/g);
        if (!sectionMatch) {
            console.error(`课程节次解析失败，原始数据：${raw.jcdm}。`);
            throw new Error(`课程 ${raw.kcmc} 节次解析失败，原始数据：${raw.jcdm}。联系开发者解决此问题。`);
        }
        const sections = sectionMatch.map(Number);
        const startSection = sections[0];
        const endSection = sections[sections.length - 1];
        const week = Number(raw.zc);
        if (isNaN(week)) {
            console.error(`课程周次解析失败，原始数据：${raw.zc}。`);
            throw new Error(`课程 ${raw.kcmc} 周次解析失败，原始数据：${raw.zc}。联系开发者解决此问题。`);
        }
        courses.push({
            name: decodeHtmlEntities(raw.kcmc).trim(),
            teacher: decodeHtmlEntities(raw.teaxms || "").trim(),
            position: decodeHtmlEntities(raw.jxcdmc || "").trim(),
            day: Number(raw.xq),
            startSection: startSection,
            endSection: endSection,
            weeks: [week],
            isCustomTime: false
        });
    }
    return courses;
}

async function saveCourses(courses) {
    try {
        console.log("正在尝试导入课程...");
        const result = await window.shiguangBridgePromise.saveImportedCourses(JSON.stringify(courses));
        if (result === true) {
            console.log("课程导入成功！");
        } else {
            console.log("课程导入未成功，结果：" + result);
            fail("课程导入失败，请查看日志。");
        }
    } catch (error) {
        console.error("导入课程时发生错误:", error);
        fail("导入课程失败: " + error.message);
    }
}

async function setPresetTimeSlots() {
    const presetTimeSlots = [
        { "number": 1, "startTime": "08:30", "endTime": "09:15" },
        { "number": 2, "startTime": "09:20", "endTime": "10:05" },
        { "number": 3, "startTime": "10:25", "endTime": "11:10" },
        { "number": 4, "startTime": "11:15", "endTime": "12:00" },
        { "number": 5, "startTime": "13:50", "endTime": "14:35" },
        { "number": 6, "startTime": "14:40", "endTime": "15:25" },
        { "number": 7, "startTime": "15:30", "endTime": "16:15" },
        { "number": 8, "startTime": "16:30", "endTime": "17:15" },
        { "number": 9, "startTime": "17:20", "endTime": "18:05" },
        { "number": 10, "startTime": "18:30", "endTime": "19:15" },
        { "number": 11, "startTime": "19:20", "endTime": "20:05" },
        { "number": 12, "startTime": "20:10", "endTime": "20:55" },
        { "number": 13, "startTime": "21:00", "endTime": "21:45" },
        { "number": 14, "startTime": "21:50", "endTime": "22:35" }
    ];
    try {
        console.log("正在尝试导入预设时间段...");
        const result = await window.shiguangBridgePromise.savePresetTimeSlots(JSON.stringify(presetTimeSlots));
        if (result === true) {
            console.log("预设时间段导入成功！");
        } else {
            console.log("预设时间段导入未成功，结果：" + result);
            fail("预设时间段导入失败，请查看日志。");
        }
    } catch (error) {
        console.error("导入时间段时发生错误:", error);
        fail("导入时间段失败: " + error.message);
    }
}

async function saveConfig(config) {
    try {
        console.log("正在尝试导入课表配置...");
        const configJsonString = JSON.stringify(config);
        const result = await window.shiguangBridgePromise.saveCourseConfig(configJsonString);
        if (result === true) {
            console.log("课表配置导入成功！");
        } else {
            console.log("课表配置导入未成功，结果：" + result);
            fail("课表配置导入失败，请查看日志。");
        }
    } catch (error) {
        console.error("导入课表配置时发生错误:", error);
        fail("导入课表配置失败: " + error.message);
    }
}

async function ensureJxfwSessionReady() {
    for (var attempt = 0; attempt < 3; attempt += 1) {
        try {
            const text = await fetchText(url_strings.SESSION_PROBE_URL, {
                method: 'GET',
                headers: { 'Referer': url_strings.BASE_URL },
                credentials: 'include',
                redirect: 'follow'
            });
            if (!looksLikeLoginHtml(text)) return true;
            console.warn(`教务会话探测 #${attempt + 1} 仍是登录页`);
        } catch (error) {
            console.warn(`教务会话探测 #${attempt + 1} 失败`, error);
        }
        if (attempt < 2) await sleep(250 * (attempt + 1));
    }
    return false;
}

async function runImportFlow() {
    if (__lumaGdutFlowRunning) {
        console.log('GDUT 导入流程已在运行，忽略重复触发。');
        return;
    }
    __lumaGdutFlowRunning = true;
    try {
        await waitForDocumentReady();

        const host = currentHost();
        if (isAuthHost(host)) {
            window.shiguangBridge.showToast("请先完成统一身份认证登录，登录后会自动回到教务系统。");
            return;
        }

        if (!isJxfwHost(host)) {
            fail(`当前页面不是广工教务系统（${host || 'unknown'}）。`);
            return;
        }

        const path = currentPath();
        if (path.indexOf('/new/ssoLogin') === 0 || path.indexOf('/new/sso') === 0) {
            await sleep(800);
        }

        const sessionReady = await ensureJxfwSessionReady();
        if (!sessionReady) {
            toastOnly("教务会话未就绪（接口仍返回登录页）。若已登录，请点右上角「读取课表」重试。");
            return;
        }

        const result = await stepDescriptionAlert();
        if (!result) {
            console.log("用户取消了操作，停止后续执行。");
            return;
        }

        const semesterOptions = await discoverSemesters();
        const selected = await selectSemesterSelection(semesterOptions);
        if (!selected) {
            console.log("用户取消了学期选择，停止后续执行。");
            return;
        }

        toastOnly(`正在读取 ${selected.label} 课表…`);
        const startDate = await fetchStartDate(selected.value);
        const courses = await fetchCourses(selected.value);
        if (!courses || !courses.length) {
            console.log(`未能获取课程数据，停止后续执行。`);
            return;
        }

        const config = {
            semesterStartDate: startDate.toISOString().split('T')[0],
            semesterTotalWeeks: 20,
            defaultClassDuration: 45,
            defaultBreakDuration: 5,
            firstDayOfWeek: 1
        };

        await saveConfig(config);
        await saveCourses(courses);
        await setPresetTimeSlots();

        window.shiguangBridge.showToast(`成功导入 ${courses.length} 条课程记录！`);
        window.shiguangBridge.notifyTaskCompletion();
    } finally {
        __lumaGdutFlowRunning = false;
    }
}

runImportFlow();
