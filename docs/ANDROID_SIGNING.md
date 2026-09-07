# Android signing in GitHub Actions

LumaSchedule does not commit signing material. Release APK/AAB builds restore an Android Studio / `keytool` keystore only inside the GitHub-hosted runner.

## Repository secrets

Create these under **Settings → Secrets and variables → Actions → Repository secrets**:

- `ANDROID_KEYSTORE_BASE64` — Base64 of the `.jks` / `.keystore` file.
- `ANDROID_KEY_ALIAS` — key alias selected in Android Studio.
- `ANDROID_KEY_PASSWORD` — password for the key alias.
- `ANDROID_STORE_PASSWORD` — password for the keystore.

PowerShell helper:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\lumaschedule-release.jks")) | Set-Clipboard
```

Linux:

```bash
base64 -w 0 ./lumaschedule-release.jks
```

macOS:

```bash
base64 -i ./lumaschedule-release.jks | tr -d '\n'
```

The `Android` workflow generates `src-tauri/gen/android/keystore.properties` at runtime, patches the generated Gradle release signing config, then creates both APK and AAB artifacts. The keystore file is written to `$RUNNER_TEMP`, never to the repository.

## Build behavior

- Push / PR: unsigned **debug APK** for quick device testing.
- Tag `v*` or manual workflow dispatch: **signed release APK + AAB** and fails early if signing secrets are missing.

Keep the original keystore backed up offline. Losing it can prevent future updates to builds distributed outside Play App Signing.
