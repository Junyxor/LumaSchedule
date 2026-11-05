package com.lumaschedule.app.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.lumaschedule.app.DiagnosticLog
import com.lumaschedule.app.MainActivity
import com.lumaschedule.app.R
import com.lumaschedule.app.data.LumaDatabase
import java.time.ZonedDateTime

/**
 * "Next course" home-screen widget. Content is computed natively from SQLite,
 * so it stays correct without opening the app; an inexact alarm refreshes it
 * when the current course ends or the next one starts.
 */
class NextCourseWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, ACTION_REFRESH -> {
                val pending = goAsync()
                Thread {
                    try {
                        updateAll(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDisabled(context: Context) {
        refreshIntent(context, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.lumaschedule.app.action.WIDGET_REFRESH"

        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, NextCourseWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val state = runCatching { NextCourseResolver.resolve(LumaDatabase.get(app).calendarSource(), ZonedDateTime.now()) }
                .getOrElse { error ->
                    DiagnosticLog.record(app, "WARN", "widget.resolve_failed", error.message.orEmpty())
                    NextCourseResolver.State("LumaSchedule", "打开应用查看课表", "", "", null)
                }
            val launch = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(app.packageName, R.layout.widget_next_course).apply {
                setTextViewText(R.id.widgetLabel, state.label)
                setTextViewText(R.id.widgetTitle, state.title)
                setTextViewText(R.id.widgetMeta, state.meta)
                setViewVisibility(R.id.widgetMeta, if (state.meta.isBlank()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widgetBadge, state.badge)
                setViewVisibility(R.id.widgetBadge, if (state.badge.isBlank()) View.GONE else View.VISIBLE)
                setOnClickPendingIntent(R.id.widgetRoot, launch)
            }
            ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
            scheduleRefresh(app, state.refreshAtMs)
        }

        private fun scheduleRefresh(context: Context, atMs: Long?) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = refreshIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
            if (atMs == null) {
                alarmManager.cancel(pending)
                return
            }
            val at = maxOf(atMs + 1_000L, System.currentTimeMillis() + 30_000L)
            runCatching { alarmManager.setWindow(AlarmManager.RTC, at, 60_000L, pending) }
        }

        private fun refreshIntent(context: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST,
            Intent(context, NextCourseWidgetProvider::class.java).setAction(ACTION_REFRESH),
            flag or PendingIntent.FLAG_IMMUTABLE
        )

        private const val REFRESH_REQUEST = 4200
    }
}
