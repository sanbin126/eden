package com.example.eden.feature.anniversary


import com.example.eden.R
import com.example.eden.core.util.FestivalCalendar
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.home.MainActivity
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 纪念日详情对话框（居中弹窗，风格对齐角色详情）。
 *
 * 展示「在一起第 X 天」大字 + 今日命中提示（milestone / 生日 / 节日），
 * 并提供「纪念日起点」「我的生日」两个 DatePicker 入口，保存后原地刷新。
 *
 * @param days 当前"在一起"天数；null 表示尚未开始（无起点 + 无聊天记录）
 * @param onUpdate 用户改了起点或生日后回调，由 [MainActivity] 用来刷新卡片文本
 */
class AnniversaryDialog(
    context: Context,
    private var days: Int?,
    private val onUpdate: () -> Unit
) : Dialog(context) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var tvTogether: TextView
    private lateinit var tvDays: TextView
    private lateinit var tvDaysUnit: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvHit: TextView
    private lateinit var tvNext: TextView
    private lateinit var tvStartValue: TextView
    private lateinit var tvBirthdayValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_anniversary)

        // 圆角背景自带，去掉系统窗口默认背景，避免圆角外露出白底
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.let {
            val lp = it.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }

        tvTogether = findViewById(R.id.an_together)
        tvDays = findViewById(R.id.an_days)
        tvDaysUnit = findViewById(R.id.an_days_unit)
        tvSub = findViewById(R.id.an_sub)
        tvHit = findViewById(R.id.an_hit)
        tvNext = findViewById(R.id.an_next)
        tvStartValue = findViewById(R.id.an_start_value)
        tvBirthdayValue = findViewById(R.id.an_birthday_value)

        findViewById<View>(R.id.an_btn_close).setOnClickListener { dismiss() }
        findViewById<View>(R.id.an_row_start).setOnClickListener { editStartDate() }
        findViewById<View>(R.id.an_row_birthday).setOnClickListener { editBirthday() }
        findViewById<View>(R.id.an_btn_edit_start).setOnClickListener { editStartDate() }
        findViewById<View>(R.id.an_btn_edit_birthday).setOnClickListener { editBirthday() }

        render()
    }

    private fun render() {
        val cal = Calendar.getInstance()

        if (days == null) {
            tvDays.text = context.getString(R.string.common_dash)
            tvDaysUnit.visibility = View.GONE
            tvSub.text = "还没有开始的故事"
            tvNext.text = "和三玖聊一次天，或设置你们的纪念日吧。"
            tvHit.visibility = View.GONE
            tvTogether.text = "还没开始的故事"
        } else {
            val d = days!!
            tvDays.text = "$d"
            tvDaysUnit.visibility = View.VISIBLE
            tvSub.text = context.getString(R.string.anniversary_caption)
            tvTogether.text = "在一起 $d 天"
            tvNext.text = "距离下个里程碑还有 ${DailyLoopManager.daysToNextMilestone(d)} 天"

            val hits = mutableListOf<String>()
            if (DailyLoopManager.isMilestoneDay(d)) hits.add(context.getString(R.string.anniversary_current))
            if (DailyLoopManager.isBirthdayToday(context, cal)) hits.add("♡ 今天是你的生日 ♡")
            FestivalCalendar.todayFestival(cal)?.let { hits.add("♡ 今天是 $it ♡") }

            if (hits.isEmpty()) {
                tvHit.visibility = View.GONE
            } else {
                tvHit.text = hits.joinToString("\n")
                tvHit.visibility = View.VISIBLE
            }
        }

        // 当前配置
        val start = DailyLoopManager.getStartDate(context)
        tvStartValue.text = when {
            start != null -> start
            days != null -> "首次聊天日期"
            else -> context.getString(R.string.common_not_set)
        }
        tvBirthdayValue.text = DailyLoopManager.getUserBirthday(context) ?: context.getString(R.string.common_not_set)
    }

    private fun editStartDate() {
        showDatePicker(DailyLoopManager.getStartDate(context)) { picked ->
            DailyLoopManager.setStartDate(context, picked)
            // 刚写入起点，daysSinceStart 用起点计算即可（不再依赖 firstChatTime）
            days = DailyLoopManager.daysSinceStart(context, null) ?: days
            Toast.makeText(context, "已保存：$picked", Toast.LENGTH_SHORT).show()
            onUpdate()
            render()
        }
    }

    private fun editBirthday() {
        showDatePicker(DailyLoopManager.getUserBirthday(context)) { picked ->
            DailyLoopManager.setUserBirthday(context, picked)
            Toast.makeText(context, "已保存：$picked", Toast.LENGTH_SHORT).show()
            onUpdate()
            render()
        }
    }

    private fun showDatePicker(initialDate: String?, onPicked: (String) -> Unit) {
        val cal = Calendar.getInstance()
        if (!initialDate.isNullOrBlank()) {
            runCatching { dateFmt.parse(initialDate) }
                .getOrNull()?.let { cal.time = it }
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val m = (month + 1).toString().padStart(2, '0')
                val d = day.toString().padStart(2, '0')
                onPicked("$year-$m-$d")
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
