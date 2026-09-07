# Android signing for LumaSchedule

LumaSchedule 的 Native Android workflow 只需要 Android 签名密钥；当前主线不依赖 Tauri signing、NDK 或 Firebase SDK。

在 GitHub 仓库的 **Settings -> Secrets and variables -> Actions -> Repository secrets** 中配置正式发布所需的四个 Secret：

- `ANDROID_KEYSTORE_BASE64` - Upload Key `.jks` / `.keystore` 文件的 Base64。
- `ANDROID_KEY_ALIAS` - key alias。
- `ANDROID_KEY_PASSWORD` - key password。
- `ANDROID_STORE_PASSWORD` - keystore password。

Tag / 手动 Release job 会在 Gradle 开始签名前检查四项 Secret。解码后的 keystore 只写到 GitHub Runner 的 `$RUNNER_TEMP`，不会提交到仓库。

## Base64 helper

PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\lumaschedule-upload.jks")) | Set-Clipboard
```

Linux：

```bash
base64 -w 0 ./lumaschedule-upload.jks
```

macOS：

```bash
base64 -i ./lumaschedule-upload.jks | tr -d '\n'
```

## Current workflow behavior

- 普通 PR：构建 **R8/Resource Shrinking 的 Release APK**，但使用 GitHub Actions 临时生成的测试 key 签名，方便直接真机安装；这个 key 不用于正式发行。
- Tag `v*` 或 `workflow_dispatch release=true`：使用四项 Repository Secrets 构建正式签名的 Release APK + AAB。
- Native Android build 直接运行 Gradle，不需要 Rust/Cargo/Tauri CLI/Android NDK。
- 仓库忽略 `*.jks`、`*.keystore`、`keystore.properties` 和本地 credential 文件。

## Recommended release key model

正式上线时推荐启用 **Google Play App Signing**：

1. Google Play 保管最终 App Signing Key；
2. 开发者自己保管 Upload Key；
3. GitHub Actions Secrets 只放 Upload Key；
4. 本地至少保留两份离线备份；
5. 不在 Issue、PR、聊天记录或源码中发送 key/password。

直接分发 APK 时，用户后续升级要求签名一致，因此用于官网/GitHub Release 的正式签名 key 同样必须长期保存。

## Firebase

当前 v0.1 Native 主线**没有接入 Firebase SDK**，也不需要 `google-services.json` 才能构建或运行核心功能。之前测试阶段使用过的 Firebase 配置恢复逻辑已经从 Native Android workflow 移除。

如果以后确实加入 Firebase Crashlytics、FCM 等功能，应单独评估：

- 是否符合 Local-first / 默认无遥测原则；
- SDK 对 APK、启动时间和隐私的成本；
- 客户端配置与服务端私钥的边界。

绝不要把 Firebase Admin SDK / service-account 私钥放进 APK。
