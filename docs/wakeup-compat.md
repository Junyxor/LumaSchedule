# WakeUp 教务兼容迁移说明

LumaSchedule 不打包 WakeUp APK，也不依赖 WakeUp 的服务器。兼容层只迁移可验证的教务入口元数据，并将可识别的 `importType` 映射到 LumaSchedule 已有的本地通用适配器。

## 已确认的数据契约

逆向材料中的 `SchoolInfo` 以 `url / type / importType / mode / customConf` 驱动学校入口。`customConf` 包含 `androidDocumentStartJs`、`enableHTTPS`、`noProxy`、`landscapeMode` 等 WebView 行为提示。本科与研究生并非一个本地固定枚举；同一学校可以通过不同 SchoolInfo 条目、URL 和 importType 区分。

WakeUp 的主路径可以把 `SubmitExportBean`（HTML、webData、截图等）提交给远端解析服务，再接收解析结果。该服务端解析器不在当前逆向交付包中，因此 LumaSchedule 不声称复现这部分能力。

## 转换器

```bash
node scripts/convert-wakeup-school-info.mjs plaintext-school-info.json wakeup-compat.json
```

输入必须是已经解密或运行时 dump 出来的 `AdapterInfo/SchoolInfo` JSON。打包在 WakeUp APK 里的 `school_info_android_new.txt` 是密文，转换器不会猜密钥或尝试绕过保护。

当前映射：

- `zf_* / 正方` → `zhengfang_jiaowu`
- `urp_*` → `urp_jiaowu`
- `qingguo / 青果` → `qingguo_jiaowu`
- `chaoxing / login_chaoxing / 超星` → `chaoxing_jiaowu`
- `cb_postgraduate / postgraduate / 研究生 / 硕士 / 博士` → `degree=postgraduate`
- `undergraduate / 本科 / 学士` → `degree=undergraduate`

未知 importType 会保留原始元数据并标记为未映射，等待单校本地适配器。

## 安全边界

`androidDocumentStartJs` 只作为待人工审核的兼容元数据保留；不能在 LumaSchedule 主 UI WebView 中执行。未来如需使用，只能进入声明域名受限的教务 WebView，且要经过脚本审核。用户账号密码仍由学校官方页面处理。

逆向材料中的第三方 OCR、凭据自动化或特定学校密码变换不默认接入。除非某校确实要求且完成独立安全审查，否则 LumaSchedule 不复制这些链路。

## 性能原则

兼容清单是静态 JSON/索引数据，运行时按需加载；不会把 WakeUp APK、反编译源码或服务端协议实现打进 APK。学校页面只有在用户开始导入时才创建隔离 WebView。
