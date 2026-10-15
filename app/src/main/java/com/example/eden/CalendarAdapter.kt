package com.example.eden

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

class CalendarAdapter(
    private val context: Context,
    private val days: List<Date?>,
    private val signedDates: Set<String>,
    private val todayStr: String,
    private val onDateClicked: (String) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = days.size
    override fun getItem(position: Int): Any? = days[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_calendar_day, parent, false)

        val tvDay = view.findViewById<TextView>(R.id.tv_day)

        val date = days[position]
        if (date == null) {
            // 空白占位
            tvDay.text = ""
            tvDay.setBackgroundResource(0)
            tvDay.setTextColor(context.edenColor(R.attr.edenColorTextHint, R.color.eden_text_hint))
            tvDay.textSize = 14f
            tvDay.typeface = Typeface.DEFAULT
            view.setOnClickListener(null)
            view.alpha = 0.3f
            return view
        }

        view.alpha = 1f  // convertView 复用：重置空白单元格留下的透明度
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        val dayOfMonth = SimpleDateFormat("d", Locale.getDefault()).format(date)
        val isToday = dateStr == todayStr
        val isSigned = signedDates.contains(dateStr)

        tvDay.text = dayOfMonth

        when {
            isToday -> {
                // 今天：实心粉色圆 + 白字加粗
                tvDay.setBackgroundResource(R.drawable.bg_day_today)
                tvDay.setTextColor(context.edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary))
                tvDay.textSize = 15f
                tvDay.typeface = Typeface.DEFAULT_BOLD
            }
            isSigned -> {
                // 已签到：浅粉圆底 + 粉字加粗
                tvDay.setBackgroundResource(R.drawable.bg_day_signed)
                tvDay.setTextColor(context.edenColor(R.attr.edenColorAccent, R.color.eden_accent))
                tvDay.textSize = 14f
                tvDay.typeface = Typeface.DEFAULT_BOLD
            }
            else -> {
                // 普通日期：灰字
                tvDay.setBackgroundResource(0)
                tvDay.setTextColor(context.edenColor(R.attr.edenColorTextHint, R.color.eden_text_hint))
                tvDay.textSize = 14f
                tvDay.typeface = Typeface.DEFAULT
            }
        }

        // 点击事件：仅今天且未签到可点击（不支持补签），回调严格按日期判断
        view.setOnClickListener(if (isToday && !isSigned) {
            View.OnClickListener { onDateClicked(dateStr) }
        } else {
            null
        })
        return view
    }
}
