import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const androidRoot = path.join(root, 'src-tauri', 'gen', 'android');
const appRoot = path.join(androidRoot, 'app');
if (!fs.existsSync(appRoot)) throw new Error('Tauri Android project not initialized. Run `tauri android init` first.');

const copy = (from, to) => {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(path.join(root, from), path.join(root, to));
};

copy('native/android/widget/NextCourseWidgetProvider.kt', 'src-tauri/gen/android/app/src/main/java/com/lumaschedule/app/widgets/NextCourseWidgetProvider.kt');
copy('native/android/widget/ScheduleNativePlugin.kt', 'src-tauri/gen/android/app/src/main/java/com/lumaschedule/app/ScheduleNativePlugin.kt');
copy('native/android/widget/ReminderReceiver.kt', 'src-tauri/gen/android/app/src/main/java/com/lumaschedule/app/widgets/ReminderReceiver.kt');
copy('native/android/widget/BootReceiver.kt', 'src-tauri/gen/android/app/src/main/java/com/lumaschedule/app/widgets/BootReceiver.kt');
copy('native/android/shiguang/ShiguangImportActivity.kt', 'src-tauri/gen/android/app/src/main/java/com/lumaschedule/app/shiguang/ShiguangImportActivity.kt');
copy('native/android/res/xml/next_course_widget_info.xml', 'src-tauri/gen/android/app/src/main/res/xml/next_course_widget_info.xml');
copy('native/android/res/layout/widget_next_course.xml', 'src-tauri/gen/android/app/src/main/res/layout/widget_next_course.xml');
copy('native/android/res/drawable/widget_background.xml', 'src-tauri/gen/android/app/src/main/res/drawable/widget_background.xml');

const manifestPath = path.join(appRoot, 'src', 'main', 'AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8');
if (!manifest.includes('android:usesCleartextTraffic')) {
  manifest = manifest.replace('<application', '<application android:usesCleartextTraffic="true"');
}
const bootPermission = '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />';
if (!manifest.includes('android.permission.RECEIVE_BOOT_COMPLETED')) {
  manifest = manifest.replace('<application', `${bootPermission}\n    <application`);
}
const widgetReceiver = `
        <receiver
            android:name=".widgets.NextCourseWidgetProvider"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/next_course_widget_info" />
        </receiver>`;
const shiguangActivity = `

        <activity
            android:name=".shiguang.ShiguangImportActivity"
            android:exported="false"
            android:excludeFromRecents="true" />
`;
const reminderReceiver = `
        <receiver
            android:name=".widgets.ReminderReceiver"
            android:exported="false" />
        <receiver
            android:name=".widgets.BootReceiver"
            android:enabled="true"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>`;

if (!manifest.includes('NextCourseWidgetProvider')) {
  manifest = manifest.replace('</application>', `${widgetReceiver}\n    </application>`);
}
if (!manifest.includes('ReminderReceiver') || !manifest.includes('BootReceiver')) {
  manifest = manifest.replace('</application>', `${reminderReceiver}\n    </application>`);
}
if (!manifest.includes('ShiguangImportActivity')) {
  manifest = manifest.replace('</application>', `${shiguangActivity}\n    </application>`);
}
fs.writeFileSync(manifestPath, manifest);

if (process.argv.includes('--signing')) {
  const gradlePath = path.join(appRoot, 'build.gradle.kts');
  let gradle = fs.readFileSync(gradlePath, 'utf8');
  if (!gradle.includes('java.util.Properties')) {
    gradle = `import java.util.Properties\nimport java.io.FileInputStream\n${gradle}`;
  }
  if (!gradle.includes('lumaRelease')) {
    const block = `
    signingConfigs {
        create("lumaRelease") {
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties()
            if (propsFile.exists()) props.load(FileInputStream(propsFile))
            keyAlias = props["keyAlias"] as String
            keyPassword = props["keyPassword"] as String
            storeFile = file(props["storeFile"] as String)
            storePassword = props["storePassword"] as String
        }
    }
`;
    gradle = gradle.replace(/android\s*\{/, (m) => `${m}${block}`);
    gradle = gradle.replace(/getByName\("release"\)\s*\{/, (m) => `${m}\n            signingConfig = signingConfigs.getByName("lumaRelease")\n`);
    fs.writeFileSync(gradlePath, gradle);
  }
}

console.log('Android project patched: widget + reboot-safe reminders + native bridge' + (process.argv.includes('--signing') ? ' + release signing' : ''));
