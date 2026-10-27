package com.example.eden.feature.signin


import com.example.eden.R
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*
import com.example.eden.ui.theme.edenColor

class CalendarAdapter(
    private val context: Context,
    private val days: List<Date?>,
    private val signedDates: Set<String>,
    private val todayStr: String,
    private val onDateClicked: (String) -> Unit,
    /** 选择模式：高亮该日期，今天及以前的日期均可点击 */
    private val selectedDate: String? = null,
    private val selectable: Boolean = false
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
        val isSelected = selectable && dateStr == selectedDate
        // yyyy-MM-dd 定长格式可直接字符串比较；未来日期在选择模式下不可选
        val isFuture = selectable && dateStr > todayStr

        tvDay.text = dayOfMonth

        when {
            isSelected -> {
                // 选择模式下的已选日期：实心粉色圆 + 白字加粗
                tvDay.setBackgroundResource(R.drawable.bg_day_today)
                tvDay.setTextColor(context.edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary))
                tvDay.textSize = 15f
                tvDay.typeface = Typeface.DEFAULT_BOLD
            }
            selectable && isToday -> {
                // 选择模式下的今天：浅粉圆底 + 粉字加粗
                tvDay.setBackgroundResource(R.drawable.bg_day_signed)
                tvDay.setTextColor(context.edenColor(R.attr.edenColorAccent, R.color.eden_accent))
                tvDay.textSize = 14f
                tvDay.typeface = Typeface.DEFAULT_BOLD
            }
            isFuture -> {
                tvDay.setBackgroundResource(0)
                tvDay.setTextColor(context.edenColor(R.attr.edenColorTextHint, R.color.eden_text_hint))
                tvDay.textSize = 14f
                tvDay.typeface = Typeface.DEFAULT
                view.alpha = 0.35f
            }
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

        view.setOnClickListener(
            when {
                // 选择模式：今天及以前都可点
                selectable -> if (isFuture) null else View.OnClickListener { onDateClicked(dateStr) }
                // 签到模式：仅今天且未签到可点击（不支持补签），回调严格按日期判断
                isToday && !isSigned -> View.OnClickListener { onDateClicked(dateStr) }
                else -> null
            }
        )
        return view
    }
}
