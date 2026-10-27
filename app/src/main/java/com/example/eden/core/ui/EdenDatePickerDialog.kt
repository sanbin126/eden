package com.example.eden.core.ui


import com.example.eden.R
import com.example.eden.feature.signin.CalendarAdapter
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 主题化日期选择弹窗，视觉对齐「我的日签卡」：渐变圆角底 + 头像标题 + 日历卡片。
 *
 * 用于设置页的「纪念日起点」「我的生日」，替代系统 [android.app.DatePickerDialog]。
 * 只能选择今天及以前的日期，支持按年 / 按月翻页。
 *
 * @param initialDate 已设置的日期（yyyy-MM-dd），为空则默认选中今天
 * @param onPicked 点击「确定」后回调选中的日期（yyyy-MM-dd）
 */
class EdenDatePickerDialog(
    context: Context,
    private val title: String,
    private val subtitle: String,
    initialDate: String?,
    private val onPicked: (String) -> Unit
) : Dialog(context) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val todayStr = dateFmt.format(Date())

    /** 当前选中的日期，默认沿用已设置日期，否则今天。 */
    private val selected: Calendar = Calendar.getInstance().apply {
        initialDate?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { dateFmt.parse(raw) }.getOrNull()?.let { time = it }
        }
    }

    /** 日历当前展示的年月，默认跟随已选日期。 */
    private val shown: Calendar = (selected.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1)
    }

    private lateinit var tvMonthTitle: TextView
    private lateinit var tvPickedDate: TextView
    private lateinit var tvPickedWeek: TextView
    private lateinit var gvCalendar: GridView
    private lateinit var ivPrevYear: ImageView
    private lateinit var ivPrevMonth: ImageView
    private lateinit var ivNextMonth: ImageView
    private lateinit var ivNextYear: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_date_picker)

        // 圆角背景自带，去掉系统窗口默认背景，避免圆角外露出白底
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.let {
            val lp = it.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }

        findViewById<TextView>(R.id.dp_title).text = title
        findViewById<TextView>(R.id.dp_sub).text = subtitle
        tvMonthTitle = findViewById(R.id.dp_month_title)
        tvPickedDate = findViewById(R.id.dp_picked_date)
        tvPickedWeek = findViewById(R.id.dp_picked_week)
        gvCalendar = findViewById(R.id.gv_date_calendar)
        ivPrevYear = findViewById(R.id.dp_prev_year)
        ivPrevMonth = findViewById(R.id.dp_prev_month)
        ivNextMonth = findViewById(R.id.dp_next_month)
        ivNextYear = findViewById(R.id.dp_next_year)

        findViewById<View>(R.id.dp_btn_close).setOnClickListener { dismiss() }
        findViewById<View>(R.id.dp_btn_cancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.dp_btn_confirm).setOnClickListener {
            onPicked(dateFmt.format(selected.time))
            dismiss()
        }

        ivPrevYear.setOnClickListener { shown.add(Calendar.YEAR, -1); render() }
        ivPrevMonth.setOnClickListener { shown.add(Calendar.MONTH, -1); render() }
        ivNextMonth.setOnClickListener { shown.add(Calendar.MONTH, 1); render() }
        ivNextYear.setOnClickListener { shown.add(Calendar.YEAR, 1); render() }

        render()
    }

    private fun render() {
        val year = shown.get(Calendar.YEAR)
        val month = shown.get(Calendar.MONTH)
        tvMonthTitle.text = String.format(Locale.getDefault(), "%d年%02d月", year, month + 1)

        // 不允许翻到未来：下个月 / 下一年在当前年月处禁用
        val now = Calendar.getInstance()
        val atCurrentMonth =
            year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH)
        val atCurrentYear = year == now.get(Calendar.YEAR)
        setNavEnabled(ivNextMonth, !atCurrentMonth)
        setNavEnabled(ivNextYear, !atCurrentYear)
        setNavEnabled(ivPrevMonth, year > MIN_YEAR || month > 0)
        setNavEnabled(ivPrevYear, year > MIN_YEAR)

        renderPicked()

        // 日历数据：首日前的空格 + 当月日期
        shown.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOffset = shown.get(Calendar.DAY_OF_WEEK) - 1
        val maxDays = shown.getActualMaximum(Calendar.DAY_OF_MONTH)
        val days = mutableListOf<Date?>()
        repeat(firstDayOffset) { days.add(null) }
        for (day in 1..maxDays) {
            shown.set(Calendar.DAY_OF_MONTH, day)
            days.add(shown.time)
        }

        gvCalendar.adapter = CalendarAdapter(
            context,
            days,
            signedDates = emptySet(),
            todayStr = todayStr,
            onDateClicked = { picked ->
                runCatching { dateFmt.parse(picked) }.getOrNull()?.let {
                    selected.time = it
                    render()
                }
            },
            selectedDate = dateFmt.format(selected.time),
            selectable = true
        )
        resizeCalendar(days.size)
    }

    /** 顶部「已选日期」卡片：日期大字 + 星期。 */
    private fun renderPicked() {
        tvPickedDate.text = dateFmt.format(selected.time)
        tvPickedWeek.text = WEEKDAYS[selected.get(Calendar.DAY_OF_WEEK) - 1]
    }

    private fun setNavEnabled(view: ImageView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.3f
    }

    /** GridView 嵌在 ScrollView 里无法 wrap_content 自适应行高，按行数算出固定高度。 */
    private fun resizeCalendar(cellCount: Int) {
        val rows = (cellCount + 6) / 7
        val density = context.resources.displayMetrics.density
        val cellHeight = (44 * density).toInt()
        val spacing = (4 * density).toInt()
        gvCalendar.layoutParams = gvCalendar.layoutParams.apply {
            height = rows * cellHeight + (rows - 1) * spacing
        }
    }

    private companion object {
        const val MIN_YEAR = 1900
        val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
    }
}
