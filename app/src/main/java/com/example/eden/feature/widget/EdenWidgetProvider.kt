package com.example.eden.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.eden.R
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.world.TimeOfDay
import com.example.eden.domain.world.WorldSnapshot
import com.example.eden.domain.world.WorldState
import com.example.eden.feature.privacy.PrivacyConsentActivity
import java.util.Calendar

/**
 * 桌面小组件：立绘 + 今日心情 + 一句话，整块点击进入聊天。
 *
 * 所有内容都来自本地（[WorldState.snapshot] 只读 prefs + 纯计算），不发起网络请求，
 * 因此断网时仍可正常显示。
 */
class EdenWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_eden)

        // 隐私未同意时不做任何业务数据读取，只保留一个入口文案。
        if (!PrivacyConsentActivity.hasAgreed(context)) {
            views.setViewVisibility(R.id.widget_mood, View.GONE)
            views.setTextViewText(R.id.widget_line, context.getString(R.string.widget_need_open))
        } else {
            val snapshot = WorldState.snapshot(context)
            views.setTextViewText(R.id.widget_mood, snapshot.moodLabel)
            views.setTextViewText(R.id.widget_line, pickLine(snapshot))
        }

        views.setOnClickPendingIntent(R.id.widget_root, routerPendingIntent(context))
        return views
    }

    /**
     * 按「日期 + 时段」确定性地取一句：同一天内多次刷新不会换句，
     * 避免小组件在系统重绘时文字乱跳。
     */
    private fun pickLine(snapshot: WorldSnapshot): String {
        val pool = when (snapshot.timeOfDay) {
            TimeOfDay.LATE_NIGHT, TimeOfDay.NIGHT -> MikuPersona.GOODNIGHT_LINES
            TimeOfDay.MORNING -> MikuPersona.MORNING_CARE_NORMAL_LINES
            else -> MikuPersona.THOUGHT_OF_YOU_LINES
        }
        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return pool[dayOfYear % pool.size]
    }

    private fun routerPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRouterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
