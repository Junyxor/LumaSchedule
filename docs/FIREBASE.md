# Firebase configuration

正式 Android application id：`com.lumaschedule.app`。

Firebase 的 `google-services.json` 属于构建环境配置，不提交到本公开仓库。`.gitignore` 已覆盖任意目录下的 `google-services.json` / `GoogleService-Info.plist`。

当前 v0.1 **没有强依赖 Firebase SDK，也没有默认遥测**。Local-first / 无账号模式不依赖 Firebase。未来如果接入用户主动选择的崩溃诊断、跨设备通知或其他 Firebase 功能，再通过本地文件或 GitHub Actions Secret 注入配置，而不是把个人环境文件写进源码。
