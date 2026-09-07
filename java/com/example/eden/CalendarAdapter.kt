package com.example.eden

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
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
        val ivHeart = view.findViewById<ImageView>(R.id.iv_heart_mark)

        val date = days[position]
        if (date == null) {
            // 空白占位
            tvDay.text = ""
            ivHeart.visibility = View.GONE
            view.setOnClickListener(null)
            view.alpha = 0.3f
        } else {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateStr = sdf.format(date)
            val dayOfMonth = SimpleDateFormat("d", Locale.getDefault()).format(date)
            tvDay.text = dayOfMonth

            val isToday = dateStr == todayStr
            val isSigned = signedDates.contains(dateStr)

            // 默认样式
            tvDay.setTextColor(0xFF666666.toInt())
            tvDay.textSize = 14f
            ivHeart.visibility = View.GONE

            if (isSigned) {
                // 已签到：显示粉色爱心
                ivHeart.visibility = View.VISIBLE
            }

            if (isToday) {
                // 今日：红色加粗数字
                tvDay.setTextColor(0xFFE91E63.toInt())  // 粉色红
                tvDay.textSize = 16f
            }

            // 点击事件：仅今天且未签到可点击
            view.setOnClickListener {
                if (isToday && !isSigned) {
                    onDateClicked(dateStr)
                }
            }
        }
        return view
    }
}