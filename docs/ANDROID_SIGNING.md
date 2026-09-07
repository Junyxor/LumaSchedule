# Android / Firebase secrets for v0.1

For the first working LumaSchedule build, keep the secret setup deliberately small. Create these under **Settings -> Secrets and variables -> Actions -> Repository secrets**.

## Required only for signed Release APK/AAB

- `ANDROID_KEYSTORE_BASE64` - Base64 of the Android Studio / `keytool` `.jks` or `.keystore` file.
- `ANDROID_KEY_ALIAS` - alias of the signing key.
- `ANDROID_KEY_PASSWORD` - password of the signing key alias.
- `ANDROID_STORE_PASSWORD` - password of the keystore.

The Release job validates all four values before Gradle signing begins. The decoded keystore is written only to `$RUNNER_TEMP` and is never committed.

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

## Optional for v0.1 Firebase Android configuration

- `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` - Base64 of the complete Firebase `google-services.json` file.

This is optional for the first Debug build. If it is present, GitHub Actions restores it to the generated Android app directory. If it is absent, the build continues without Firebase configuration.

`google-services.json` is an Android client configuration, not a Firebase service-account private key. LumaSchedule still keeps it out of the public repository. Never put a Firebase Admin SDK / service-account private key into the APK; those belong on a trusted server only.

PowerShell helper:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\google-services.json")) | Set-Clipboard
```

Linux:

```bash
base64 -w 0 ./google-services.json
```

macOS:

```bash
base64 -i ./google-services.json | tr -d '\n'
```

## v0.1 build behavior

- Push / PR: unsigned Debug APK. Firebase config is optional.
- Tag `v*` or manual workflow dispatch with `release=true`: signed Release APK + AAB. All four Android signing secrets are mandatory.
- The repository ignores `*.jks`, `*.keystore`, `keystore.properties`, `google-services.json`, and Apple `GoogleService-Info.plist` files.

Keep the original Android keystore backed up offline in at least two safe locations. Losing it can prevent future updates to builds distributed outside Google Play App Signing.
