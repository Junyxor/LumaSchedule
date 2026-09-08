# Android release signing

LumaSchedule Android uses one long-lived signing key for all public releases. Once the first public APK is shipped, keep this key permanently: future APK updates with a different key cannot update the installed app in place.

## 1. Generate the release key locally

Run this on a trusted machine with JDK 17+ installed:

```bash
keytool -genkeypair -v \
  -keystore lumaschedule-release.jks \
  -storetype JKS \
  -alias lumaschedule \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=LumaSchedule, OU=Release, O=LumaSchedule, C=CN"
```

Choose strong, unique store/key passwords. Do not commit the `.jks` file or passwords. Keep at least two offline backups of the keystore and record the alias/passwords in a password manager.

## 2. Add GitHub Actions secrets

Repository → Settings → Secrets and variables → Actions → New repository secret.

Required secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64 of `lumaschedule-release.jks`
- `ANDROID_KEY_ALIAS`: normally `lumaschedule`
- `ANDROID_KEY_PASSWORD`: key password
- `ANDROID_STORE_PASSWORD`: keystore password

PowerShell can copy the Base64 directly:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("lumaschedule-release.jks")) | Set-Clipboard
```

Linux:

```bash
base64 -w0 lumaschedule-release.jks
```

macOS:

```bash
base64 < lumaschedule-release.jks | tr -d '\n'
```

## 3. Test a signed build before tagging

Actions → Android Native → Run workflow → enable `release` → version `0.1.0`.

The workflow validates the signing key, runs frontend checks, WakeUp profile smoke tests, Shiguang/GDUT tests, builds signed APK/AAB files, verifies their signatures, rejects unexpected native `.so` libraries, and writes SHA-256 hashes.

Install that signed APK on a clean Android device and validate the real GDUT login/import path before creating the public tag.

## 4. Publish v0.1.0

After `main` is ready:

```bash
git checkout main
git pull --ff-only
git tag -a v0.1.0 -m "LumaSchedule v0.1.0"
git push origin v0.1.0
```

A `vMAJOR.MINOR.PATCH` tag triggers the signed-release job. For `v0.1.0`, Android metadata becomes:

- `versionName = 0.1.0`
- `versionCode = 1000`

Version code formula: `major * 1,000,000 + minor * 1,000 + patch`.

The tag workflow creates:

- `LumaSchedule-v0.1.0.apk`
- `LumaSchedule-v0.1.0.aab`
- `SHA256SUMS.txt`
- `SIGNING-CERTIFICATE.txt`

and publishes them to the matching GitHub Release.

## 5. Never rotate the key casually

The official release key is part of the app's update identity. Do not regenerate it for later releases. CI test builds deliberately use a throwaway key and cannot be installed over an official signed build.
