package com.example.eden.feature.signin

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.example.eden.R
import java.util.Locale

/**
 * 每日签到弹窗：当天未签到时进入主页自动弹出，代替此前直接跳日历页的做法。
 *
 * 只做「今天签不签」这一个决定，日历 / 补看历史记录仍由 [SignCalendarDialog] 承担。
 * 签到成功后回调 [onSigned]，由调用方刷新主页卡片与每日任务进度。
 */
class DailySignDialog(
    context: Context,
    private val onSigned: ((Int) -> Unit)? = null
) : Dialog(context) {

    private lateinit var tvDays: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_daily_sign)

        // 圆角背景自带，去掉系统窗口默认背景，避免圆角外露出白底
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.let {
            val lp = it.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }

        tvDays = findViewById(R.id.ds_days)
        val tvLine = findViewById<TextView>(R.id.ds_line)
        val btnCancel = findViewById<TextView>(R.id.ds_btn_cancel)
        val btnSign = findViewById<TextView>(R.id.ds_btn_sign)

        // 今天还没签到，连续天数展示的是截至昨天的连续记录
        tvDays.text = String.format(Locale.getDefault(), "连续签到 %d 天", SignInRepository.getContinuousDays(context))
        // 暖心话取自轮转队列，保证池子走完一轮前不重复
        tvLine.text = SignInRepository.nextSignInLine(context)

        btnCancel.setOnClickListener { dismiss() }
        btnSign.setOnClickListener {
            if (SignInRepository.isTodaySigned(context)) {
                dismiss()
                return@setOnClickListener
            }
            val days = SignInRepository.signToday(context)
            Toast.makeText(context, "签到成功！连续打卡 $days 天", Toast.LENGTH_SHORT).show()
            dismiss()
            onSigned?.invoke(days)
        }
    }
}
