# JavaScriptInterface methods are reached from WebView, not from Java call sites.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep broadcast receivers and the import Activity names referenced from AndroidManifest.
-keep class com.lumaschedule.app.MainActivity { *; }
-keep class com.lumaschedule.app.shiguang.ShiguangImportActivity { *; }
-keep class com.lumaschedule.app.widgets.NextCourseWidgetProvider { *; }
-keep class com.lumaschedule.app.widgets.ReminderReceiver { *; }
-keep class com.lumaschedule.app.widgets.BootReceiver { *; }
