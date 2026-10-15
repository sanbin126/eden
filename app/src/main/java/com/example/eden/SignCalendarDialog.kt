package com.example.eden

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.GridView
import android.widget.ImageView
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
    private lateinit var tvBtnSign: TextView
    private lateinit var btnClose: TextView
    private lateinit var ivPrevMonth: ImageView
    private lateinit var ivNextMonth: ImageView

    /** 相对本月的偏移：0=本月，负数=往前的月份。不允许翻到未来月份。 */
    private var monthOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_sign_calendar)

        // 圆角背景自带，去掉系统窗口默认背景，避免圆角外露出白底
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.let {
            val lp = it.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }

        tvMonthTitle = findViewById(R.id.tv_month_title)
        gvCalendar = findViewById(R.id.gv_calendar)
        tvSlogan = findViewById(R.id.tv_slogan)
        tvContinuousDays = findViewById(R.id.tv_continuous_days)
        tvTotalDays = findViewById(R.id.tv_total_days)
        tvBtnSign = findViewById(R.id.tv_btn_sign)
        btnClose = findViewById(R.id.sc_btn_close)
        ivPrevMonth = findViewById(R.id.sc_prev_month)
        ivNextMonth = findViewById(R.id.sc_next_month)

        btnClose.setOnClickListener { dismiss() }
        ivPrevMonth.setOnClickListener {
            monthOffset--
            loadCalendar()
        }
        ivNextMonth.setOnClickListener {
            if (monthOffset < 0) {
                monthOffset++
                loadCalendar()
            }
        }

        loadCalendar()
    }

    private fun loadCalendar() {
        val today = Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(today.time)

        // 目标月份（按偏移平移），仅用于渲染网格
        val calendar = Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset) }

        // 月份标题：非本年时带上年份，避免跨年浏览有歧义
        val sameYear = calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val monthPattern = if (sameYear) "MM月" else "yyyy年MM月"
        tvMonthTitle.text = SimpleDateFormat(monthPattern, Locale.getDefault()).format(calendar.time)

        // 右箭头：翻到本月后禁用（不允许查看未来月份）
        ivNextMonth.isEnabled = monthOffset < 0
        ivNextMonth.alpha = if (monthOffset < 0) 1f else 0.3f

        // 统计信息
        val continuousDays = SignInManager.getContinuousDays(context)
        val signedDates = SignInManager.getSignedDates(context)
        val totalDays = signedDates.size

        val nickname = NicknameManager.getCallRaw(context)
        val call = if (nickname.isEmpty()) "" else "$nickname，"
        tvSlogan.text = "${call}一共连续打卡${continuousDays}天~"
        tvContinuousDays.text = "$continuousDays"
        tvTotalDays.text = "$totalDays"

        bindSignButton()

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
            onDateClicked = { dateStr -> handleSign(dateStr, todayStr) }
        )
        gvCalendar.adapter = adapter
        resizeCalendar(days.size)
    }

    /** 签到主入口：点格子与点「立即签到」共用，严格按日期判断，只有今天且未签到才执行。 */
    private fun handleSign(dateStr: String, todayStr: String) {
        if (dateStr == todayStr && !SignInManager.isTodaySigned(context)) {
            val newDays = SignInManager.signToday(context)
            Toast.makeText(context, "签到成功！连续打卡 ${newDays} 天", Toast.LENGTH_SHORT).show()
            dismiss()
        } else {
            Toast.makeText(context, "只能签到今天哦", Toast.LENGTH_SHORT).show()
        }
    }

    /** 底部主按钮：本月→签到/已签到；翻到历史月份→一键回到本月。 */
    private fun bindSignButton() {
        if (monthOffset != 0) {
            tvBtnSign.text = "回到本月"
            tvBtnSign.setTextColor(context.edenColor(R.attr.edenColorAccent, R.color.eden_accent))
            tvBtnSign.setBackgroundResource(R.drawable.bg_game_card)
            tvBtnSign.isClickable = true
            tvBtnSign.setOnClickListener {
                monthOffset = 0
                loadCalendar()
            }
            return
        }

        if (SignInManager.isTodaySigned(context)) {
            tvBtnSign.text = "今日已签到"
            tvBtnSign.setTextColor(context.edenColor(R.attr.edenColorTextTertiary, R.color.eden_text_tertiary))
            tvBtnSign.setBackgroundResource(R.drawable.bg_game_card)
            tvBtnSign.isClickable = false
            tvBtnSign.setOnClickListener(null)
        } else {
            tvBtnSign.text = context.getString(R.string.sign_calendar_action)
            tvBtnSign.setTextColor(context.edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary))
            tvBtnSign.setBackgroundResource(R.drawable.bg_btn_primary)
            tvBtnSign.isClickable = true
            tvBtnSign.setOnClickListener {
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                handleSign(todayStr, todayStr)
            }
        }
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
}
