// GDUT adapter override for LumaSchedule.
// Hardened against SSO landing / session-cookie races that leave the classic
// jxfw actions returning the HTML login page instead of JSON.

if (typeof url_strings === 'undefined') {
    var url_strings = {
        BASE_URL: "https://jxfw.gdut.edu.cn",
        GET_WEEK_COURSES_API_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!getKbRq.action",
        GET_ALL_COURSES_API_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!getDataList.action",
        GET_ALL_COURSES_HTML_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!xsAllKbList.action",
        GET_ALL_COURSES_HTML_URL_REFERRER: "https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action",
        SESSION_PROBE_URL: "https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action",
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

async function selectSemesterSelection(){
    // 教务系统识别学期的规则为：学年年份 + 学期编号。
    // 2025-2026 学年秋季学期对应 202501，春季学期对应 202602。
    const now = new Date();
    const currentYear = now.getFullYear();
    const currentMonth = now.getMonth() + 1;

    const currentSemester = currentMonth >= 7 || currentMonth <= 1 ? 1 : 2;
    let currentSemesterYear = currentSemester === 1 ? currentYear : currentYear - 1;
    currentSemesterYear = currentMonth <= 1 ? currentSemesterYear - 1 : currentSemesterYear;
    const nextSemester = currentSemester === 1 ? 2 : 1;
    const nextSemesterYear = currentSemester === 1 ? currentSemesterYear : currentSemesterYear + 1;

    const presetSemestersIds = [];
    const presetSemestersNames = [];

    for (let semesterYear = nextSemesterYear; semesterYear >= nextSemesterYear - 6; semesterYear--){
        for (let semester = semesterYear === nextSemesterYear ? nextSemester : 2; semester >= 1; semester--){
            presetSemestersIds.push(`${semesterYear}0${semester}`);
            const semesterName = `${semester === 1 ? semesterYear : semesterYear + 1}年${semester === 1 ? "秋季" : "春季"} (${semesterYear}-${semesterYear + 1} 学年第${semester}学期)`;
            presetSemestersNames.push(semesterName);
        }
    }

    try {
        const selectedIndex = await window.shiguangBridgePromise.showSingleSelection(
            "选择要导入的学期",
            JSON.stringify(presetSemestersNames),
            1
        );
        if (selectedIndex !== null && selectedIndex >= 0 && selectedIndex < presetSemestersIds.length) {
            console.log("用户选择了: " + presetSemestersNames[selectedIndex] + " (索引: " + selectedIndex + ")");
            return presetSemestersIds[selectedIndex];
        } else {
            console.log("用户取消了选择。");
            return null;
        }
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
    const url = `${url_strings.GET_WEEK_COURSES_API_URL}?xnxqdm=${semesterId}&zc=1`;
    try {
        console.log(`正在获取学期开始日期。学期代码：${semesterId}`);
        const response = await fetch(url, {
            method: 'GET',
            headers: { 'Referer': url },
            credentials: 'include'
        });
        const data = await response.text();
        if (looksLikeLoginHtml(data)) {
            console.warn('学期日期接口返回登录页');
            return new Date();
        }
        const startDateString = extractFirstDay(data);
        if (startDateString === null) {
            return new Date();
        }
        const date = new Date(startDateString);
        if (isNaN(date.getTime())) {
            console.warn(`日期解析失败: ${startDateString}，使用当前日期`);
            return new Date();
        }
        console.log(`成功获取学期开始日期: ${date.toISOString().split('T')[0]}`);
        return date;
    } catch (error) {
        console.error('获取学期开始日期失败，使用当前日期。错误信息:', error);
        return new Date();
    }
}

async function fetchCourses(semesterId){
    try {
        console.log(`正在获取学期 ${semesterId} 的课程数据...`);
        // 教务分页接口会返回重复/缺失数据，固定一页拉全量。
        const pageSize = 1000;
        const formData = new URLSearchParams();
        formData.append('zc', '');
        formData.append('xnxqdm', semesterId);
        formData.append('page', '1');
        formData.append('rows', String(pageSize));
        formData.append('sort', 'kxh');
        formData.append('order', 'asc');

        const response = await fetch(url_strings.GET_ALL_COURSES_API_URL, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'Referer': url_strings.BASE_URL
            },
            body: formData.toString(),
            credentials: 'include'
        });

        if (!response.ok) {
            throw new Error(`请求失败: ${response.status}`);
        }

        const rawText = await response.text();
        let rawData = null;
        try {
            rawData = JSON.parse(rawText);
        } catch (parseError) {
            if (looksLikeLoginHtml(rawText)) {
                throw new Error('教务接口返回登录页，统一认证会话未建立。请点右上角「读取课表」重试。');
            }
            throw new Error('教务接口返回了非 JSON 数据，可能页面结构已变更。');
        }

        if (!rawData || !Array.isArray(rawData.rows)) {
            throw new Error('教务接口返回结构异常，缺少课程列表。');
        }

        if (rawData.total === 0 || rawData.rows.length === 0) {
            console.log(`学期 ${semesterId} 没有找到课程数据。`);
            if (await checkSemesterIsOpened(semesterId)) {
                throw new Error('该学期没有找到课程！请确认选择了正确的学期。');
            }
            throw new Error('学期未开放课表查询！');
        }

        const rawCourses = rawData.rows;
        console.log(`成功获取学期 ${semesterId} 的课程数据，共 ${rawCourses.length} 条记录。`);
        return parseCourses(rawCourses);
    } catch (error) {
        console.error('添加课程表失败:', error);
        fail(`添加课程失败: ${error.message}`);
        return null;
    }
}

async function checkSemesterIsOpened(semesterId) {
    console.log(`正在检查学期 ${semesterId} 是否已开放课表查询...`);
    const url = `${url_strings.GET_ALL_COURSES_HTML_URL}?xnxqdm=${semesterId}`;
    const response = await fetch(url, {
        method: 'GET',
        headers: { 'Referer': url_strings.GET_ALL_COURSES_HTML_URL_REFERRER },
        credentials: 'include'
    });
    const html = await response.text();
    if (looksLikeLoginHtml(html)) return false;
    return !html.includes("本学期课表还未开放，请稍后查询！");
}

function parseCourses(rawCourses){
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
        const course = {
            name: decodeHtmlEntities(raw.kcmc).trim(),
            teacher: decodeHtmlEntities(raw.teaxms || "").trim(),
            position: decodeHtmlEntities(raw.jxcdmc || "").trim(),
            day: Number(raw.xq),
            startSection: startSection,
            endSection: endSection,
            weeks: [week],
            isCustomTime: false
        };
        courses.push(course);
    }
    return courses;
}

function decodeHtmlEntities(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.innerHTML = text;
    return div.textContent || div.innerText || '';
}

async function saveCourses(courses){
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
    // After CAS SSO the classic action endpoints sometimes still see an empty
    // JSESSIONID for a moment. Probe and retry instead of failing on HTML.
    for (var attempt = 0; attempt < 3; attempt += 1) {
        try {
            const response = await fetch(url_strings.SESSION_PROBE_URL, {
                method: 'GET',
                headers: { 'Referer': url_strings.BASE_URL },
                credentials: 'include',
                redirect: 'follow'
            });
            const text = await response.text();
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

        // SSO callback may still be mid-redirect.
        const path = currentPath();
        if (path.indexOf('/new/ssoLogin') === 0 || path.indexOf('/new/sso') === 0) {
            await sleep(800);
        }

        const sessionReady = await ensureJxfwSessionReady();
        if (!sessionReady) {
            // Keep the host session alive so the user can retry via the manual button.
            toastOnly("教务会话未就绪（接口仍返回登录页）。若已登录，请点右上角「读取课表」重试。");
            return;
        }

        const result = await stepDescriptionAlert();
        if (!result) {
            console.log("用户取消了操作，停止后续执行。");
            return;
        }

        const semesterId = await selectSemesterSelection();
        if (!semesterId) {
            console.log("用户取消了学期选择，停止后续执行。");
            return;
        }

        const startDate = await fetchStartDate(semesterId);
        const courses = await fetchCourses(semesterId);
        if (!courses) {
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

        window.shiguangBridge.showToast(`成功导入 ${courses.length} 门课程！`);
        window.shiguangBridge.notifyTaskCompletion();
    } finally {
        __lumaGdutFlowRunning = false;
    }
}

// 入口函数，开始执行导入流程
runImportFlow();
