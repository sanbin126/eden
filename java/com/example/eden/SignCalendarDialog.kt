package com.example.eden

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

class SignCalendarDialog(context: Context) : Dialog(context) {

    private lateinit var gvCalendar: GridView
    private lateinit var tvMonthTitle: TextView
    private lateinit var tvSlogan: TextView
    private lateinit var tvContinuousDays: TextView
    private lateinit var tvTotalDays: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_sign_calendar)

        // 设置弹窗宽度为屏幕宽度的90%，高度自适应
        window?.let {
            val lp = it.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }

        tvMonthTitle = findViewById(R.id.tv_month_title)
        gvCalendar = findViewById(R.id.gv_calendar)
        tvSlogan = findViewById(R.id.tv_slogan)
        tvContinuousDays = findViewById(R.id.tv_continuous_days)
        tvTotalDays = findViewById(R.id.tv_total_days)

        loadCalendar()
    }

    private fun loadCalendar() {
        val calendar = Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)

        // 月份标题
        val monthFormat = SimpleDateFormat("MM月", Locale.getDefault())
        tvMonthTitle.text = monthFormat.format(calendar.time)

        // 统计信息
        val continuousDays = SignInManager.getContinuousDays(context)
        val signedDates = SignInManager.getSignedDates(context)
        val totalDays = signedDates.size

        tvSlogan.text = "一共连续打卡${continuousDays}天~"
        tvContinuousDays.text = "$continuousDays"
        tvTotalDays.text = "$totalDays"

        // 日历数据
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val maxDays = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        val days = mutableListOf<Date?>()
        for (i in 0 until firstDayOfWeek) {
            days.add(null)
        }
        for (day in 1..maxDays) {
            calendar.set(Calendar.DAY_OF_MONTH, day)
            days.add(calendar.time)
        }

        val adapter = CalendarAdapter(
            context,
            days,
            signedDates,
            todayStr,
            onDateClicked = { dateStr ->
                val newDays = SignInManager.signToday(context)
                Toast.makeText(context, "签到成功！连续打卡 ${newDays} 天", Toast.LENGTH_SHORT).show()
                loadCalendar() // 刷新界面
                dismiss()
            }
        )
        gvCalendar.adapter = adapter
    }
}