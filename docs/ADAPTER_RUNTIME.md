# Adapter Runtime / 拾光兼容层

LumaSchedule 的高校 Adapter 层与本地 SQLite 分离。第三方脚本只负责在学校教务页面中读取课程数据并通过受限 Bridge 暂存结果；LumaSchedule 再把结果规范化成 `ImportBundle`，进入导入预览，用户确认后才写入数据库。

## 全量拾光生态同步

上游：`XingHeYuZhuan/shiguang_warehouse`（MIT）。

构建时完整同步：

```text
vendor/shiguang_warehouse/
├─ index/
├─ resources/       # 学校 adapters.yaml + JavaScript Adapter
├─ LICENSE
└─ SNAPSHOT.json    # upstream revision / 数量 / 生成时间
```

Android Release 构建会拉取上游并重新生成 snapshot；定时 workflow 也会更新仓库中的快照。因此：

1. 新学校进入拾光仓库后通常不需要修改 LumaSchedule 主程序；
2. 学校搜索直接读取 APK 内本地 Assets，并在 Kotlin 内存中缓存，不依赖运行时 GitHub 网络；
3. 离线仍可使用当前安装包内的完整目录；
4. 上游 source、MIT license 与 maintainer 信息继续保留。

本地同步：

```bash
npm run adapters:sync
```

## Bridge 兼容目标

对照拾光 WebBridge 协议，隔离 WebView 提供：

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

并兼容旧脚本别名：

```text
window.AndroidBridgePromise
window.AndroidBridge
```

`showPrompt` 支持拾光约定的 JS validator：返回 `false/null/undefined/空字符串` 视为通过；返回文本则作为校验错误并允许重新输入。

## Android Native 运行链

Android 主应用不在自己的 UI WebView 中打开学校网站，而是使用独立 `ShiguangImportActivity`：

```text
Svelte Import Center
  ↓ async LumaNative Bridge
Kotlin ShiguangRepository
  ↓ resolve bundled catalog / Adapter / SHA-256 / allowed hosts
Kotlin ShiguangImportActivity
  ↓
isolated WebView 登录学校官方系统
  ↓
restricted Shiguang Bridge
  ↓
SharedPreferences staged result
  ↓
Kotlin ShiguangRepository → ImportBundle
  ↓
Svelte Preview
  ↓
Kotlin SQLite transaction
```

主线不需要 Rust/Tauri 参与这条链路。

## 权限边界

- 主 App WebView 使用 `https://app.luma.local/` 虚拟本地 Origin；未知网络请求直接返回 403；
- 教务 WebView 与主 UI WebView 分离；
- HTTPS 是默认；只有 Adapter 明确使用旧 HTTP 登录时才进入兼容模式并显示风险提示；
- `allowFileAccess=false`、`allowContentAccess=false`；
- 导航、子资源、XHR / fetch 受 host allowlist 限制；
- Adapter 源码里的任意 URL **不能自行授予新域名权限**；允许域名来自登录 URL / SSO redirect target 及受控同机构范围；
- Bridge 不提供 SQLite、文件系统、Shell 或任意 Native command；
- Adapter 只能提交 courses / time slots / course config 等导入数据；
- 最终数据仍必须经过 LumaSchedule 导入预览。

## GDUT 首个端到端目标

`GDUT_01`：广东工业大学统一身份认证 → `jxfw.gdut.edu.cn`。

当前 Adapter 可提供：

- 学期开始日期；
- 所选学期课程；
- 课程名称 / 教师 / 地点 / 星期 / 节次 / 周次；
- 1–14 节学校作息；
- 学期总周数、周起始日等配置。

LumaSchedule 不读取或保存用户的 GDUT 密码；登录凭据只在学校官方登录 WebView / Cookie 会话中使用。

## 其他平台

当前 v0.1 将 Android 作为唯一正式 Adapter 运行平台。Rust/Tauri 历史实现保存在 `archive/rust-tauri-v0.1` 分支，用于回溯和架构参考；未来若重新做 Desktop/iOS，会重新设计对应的隔离运行时，不会因为历史代码存在就宣称已经具备同等级安全边界。
