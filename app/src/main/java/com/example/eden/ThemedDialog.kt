package com.example.eden

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主题化确认弹窗：统一使用角色详情同款粉色卡片，替代系统 AlertDialog。
 */
object ThemedDialog {

    /**
     * 弹出一个粉色主题的确认框。
     *
     * @param iconRes 顶部圆形图标
     * @param iconTint 图标着色，不传则用图标原色
     * @param negativeText 传 null 表示只有一个主按钮（占满整行）
     */
    fun show(
        context: Context,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String? = context.getString(R.string.common_cancel),
        iconRes: Int = R.drawable.ic_info,
        iconTint: Int? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val dialog = Dialog(context)
        val view = View.inflate(context, R.layout.dialog_confirm, null)
        dialog.setContentView(view)

        view.findViewById<ImageView>(R.id.confirm_icon).apply {
            setImageResource(iconRes)
            if (iconTint != null) setColorFilter(iconTint)
        }
        view.findViewById<TextView>(R.id.confirm_title).text = title
        view.findViewById<TextView>(R.id.confirm_message).text = message

        val positive = view.findViewById<TextView>(R.id.confirm_positive)
        positive.text = positiveText
        positive.setOnClickListener {
            dialog.dismiss()
            onPositive?.invoke()
        }

        val negative = view.findViewById<TextView>(R.id.confirm_negative)
        if (negativeText != null) {
            negative.text = negativeText
            negative.setOnClickListener { dialog.dismiss() }
        } else {
            negative.visibility = View.GONE
            (positive.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        }

        dialog.show()
        dialog.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val lp = it.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.86).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }
    }
}
