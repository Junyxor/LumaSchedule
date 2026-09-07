package com.lumaschedule.app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lumaschedule.app.MainActivity
import com.lumaschedule.app.R

class NextCourseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    private fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("luma_widget", Context.MODE_PRIVATE)
        val course = prefs.getString("next_course_name", "下一节课程") ?: "下一节课程"
        val meta = prefs.getString("next_course_meta", "打开 LumaSchedule 查看课表") ?: "打开 LumaSchedule 查看课表"
        val countdown = prefs.getString("next_course_countdown", "--") ?: "--"

        val launch = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val views = RemoteViews(context.packageName, R.layout.widget_next_course).apply {
            setTextViewText(R.id.widgetCourseName, course)
            setTextViewText(R.id.widgetCourseMeta, meta)
            setTextViewText(R.id.widgetCountdown, countdown)
            setOnClickPendingIntent(R.id.widgetRoot, pending)
        }
        manager.updateAppWidget(appWidgetId, views)
    }
}
