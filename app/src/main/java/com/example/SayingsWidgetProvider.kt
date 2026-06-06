package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast

class SayingsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("SayingsWidgetPrefs", Context.MODE_PRIVATE)
        for (appWidgetId in appWidgetIds) {
            val dbId = prefs.getLong("widget_db_id_$appWidgetId", -1L)
            val dbName = prefs.getString("widget_db_name_$appWidgetId", null) ?: "Sayings DB"
            updateWidgetInstance(context, appWidgetManager, appWidgetId, dbName, dbId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_PINNED_CALLBACK) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val dbId = intent.getLongExtra("EXTRA_DATABASE_ID", -1L)
            val dbName = intent.getStringExtra("EXTRA_DATABASE_NAME") ?: "Sayings DB"

            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val prefs = context.getSharedPreferences("SayingsWidgetPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("widget_db_id_$appWidgetId", dbId)
                    .putString("widget_db_name_$appWidgetId", dbName)
                    .apply()

                val appWidgetManager = AppWidgetManager.getInstance(context)
                updateWidgetInstance(context, appWidgetManager, appWidgetId, dbName, dbId)
                Toast.makeText(context, "Widget pinned for display: $dbName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("SayingsWidgetPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (appWidgetId in appWidgetIds) {
            editor.remove("widget_db_id_$appWidgetId")
            editor.remove("widget_db_name_$appWidgetId")
        }
        editor.apply()
    }

    companion object {
        const val ACTION_WIDGET_PINNED_CALLBACK = "com.example.ACTION_WIDGET_PINNED_CALLBACK"

        fun updateWidgetInstance(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            dbName: String,
            dbId: Long
        ) {
            val views = RemoteViews(context.packageName, R.layout.sayings_widget_layout)
            views.setTextViewText(R.id.widget_title, dbName)

            // Dynamic launch MainActivity when clicked
            val clickIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("DATABASE_ID", dbId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Helper method to programmatically request pinning a 1x1 widget for a specific database.
         */
        fun requestPinWidget(context: Context, dbId: Long, dbName: String): Boolean {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val myProvider = ComponentName(context, SayingsWidgetProvider::class.java)

                    if (appWidgetManager.isRequestPinAppWidgetSupported) {
                        val callbackIntent = Intent(context, SayingsWidgetProvider::class.java).apply {
                            action = ACTION_WIDGET_PINNED_CALLBACK
                            putExtra("EXTRA_DATABASE_ID", dbId)
                            putExtra("EXTRA_DATABASE_NAME", dbName)
                        }

                        val successCallback = PendingIntent.getBroadcast(
                            context,
                            dbId.toInt(),
                            callbackIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )

                        return appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }
    }
}
