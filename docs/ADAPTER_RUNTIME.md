# Adapter Runtime / 拾光兼容层

LumaSchedule 的 adapter 层与 SQLite 数据库分离。第三方脚本只负责从教务系统读取数据并输出兼容对象，最终统一转换为 `ImportBundle`，经过预览后才允许写入数据库。

## 全量拾光生态同步

上游：`XingHeYuZhuan/shiguang_warehouse`（MIT）。

LumaSchedule 不手工维护“学校名单副本”，而是完整镜像：

```text
vendor/shiguang_warehouse/
├─ index/
├─ resources/       # 所有学校 adapters.yaml + *.js
├─ LICENSE
└─ SNAPSHOT.json    # upstream revision / 数量 / 生成时间
```

`Sync Shiguang adapters` GitHub Actions 每日同步；Desktop/Android release 构建也会在打包前拉取上游并重新生成 snapshot。因此：

1. 新学校进入拾光仓库后不需要改 LumaSchedule 主程序；
2. 离线时仍可使用随 App 打包的最后一次 snapshot；
3. 上游 source、MIT license、maintainer 信息保持原样，不抹除署名。

本地可运行：

```bash
npm run adapters:sync
```

## 官方 Bridge 兼容目标

对照拾光当前 `WebBridgeProtocol`，运行时提供：

```text
window.shiguangBridgePromise.showAlert(...)
window.shiguangBridgePromise.showPrompt(...)
window.shiguangBridgePromise.showSingleSelection(...)
window.shiguangBridgePromise.saveImportedCourses(...)
window.shiguangBridgePromise.saveCourseConfig(...)
window.shiguangBridgePromise.savePresetTimeSlots(...)

window.shiguangBridge.showToast(...)
window.shiguangBridge.notifyTaskCompletion()
```

并提供旧脚本别名：

```text
window.AndroidBridgePromise
window.AndroidBridge
```

`showPrompt` 支持拾光约定的 JS validator：返回 `false/null/undefined/空字符串` 视为通过；返回文本则作为校验错误提示并允许重新输入。

## Android 运行时

Android 不在 Tauri 主 WebView 中打开教务系统，而是使用独立 `ShiguangImportActivity`：

```text
Import Center
  ↓
Rust resolve adapter / SHA-256 / allowed hosts
  ↓
Kotlin ShiguangImportActivity
  ↓
WebView 登录（Cookie 仅留在该 WebView）
  ↓
Shiguang bridge
  ↓
SharedPreferences staged result
  ↓
Rust ImportBundle
  ↓
Preview → SQLite transaction
```

### 权限边界

- HTTPS 是默认；只有适配器明确声明/使用 HTTP 的旧教务会话才允许对应机构域名使用明文 HTTP，并在 UI 显示风险警告；
- `allowFileAccess=false`、`allowContentAccess=false`；
- 主导航被 host allowlist 限制；
- Android `shouldInterceptRequest` 同时限制子资源 / XHR / fetch；
- adapter 源码里的 URL **不能自行授予任意新域名权限**：只有登录 URL、SSO redirect target 以及它们同机构域名下的 host 可进入 allowlist；
- Bridge 不提供文件系统、Shell、任意 Tauri command；
- adapter 只能暂存 courses / time slots / course config；
- 最终仍必须经过 LumaSchedule 导入预览。

## Desktop 说明

Desktop Tauri Webview 已限制 navigation 与可调用 IPC，只开放 `shiguang_bridge`。Tauri 当前 `on_web_resource_request` 对 external URL 不执行，因此 desktop 端暂时无法像 Android 一样在 WebView 层截断所有外部 fetch；浏览器 CORS 仍会生效，但这不等同于完整网络沙箱。

因此 v0.1 将 **Android 作为教务 adapter 的首要安全验收平台**。Desktop 继续保留兼容运行时用于开发/测试，后续应改为 Rust 网络代理 / 本地隔离执行上下文后再宣称同等级沙箱。

## GDUT 首个端到端目标

`GDUT_01` 登录：广东工业大学统一身份认证 → `jxfw.gdut.edu.cn`。

adapter 会读取：

- 学期开始日期；
- 所选学期全部课程；
- 课程名称 / 教师 / 地点 / 星期 / 节次 / 周次；
- 1–14 节学校作息；
- 学期总周数、周起始日等配置。

运行时不会存储 GDUT 密码；真机首次验收需要用户自己在原生 WebView 完成学校登录。
