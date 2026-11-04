package com.example.eden.feature.chat

import com.example.eden.R
import com.example.eden.core.ui.ThemedDialog
import com.example.eden.ui.theme.edenColor
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog

internal fun ChatActivity.showSettingsDialog() {
    val dialog = BottomSheetDialog(this)
    val view = layoutInflater.inflate(R.layout.dialog_chat_settings, null)
    view.findViewById<LinearLayout>(R.id.item_clear_chat).setOnClickListener {
        ThemedDialog.show(
            context = this,
            title = getString(R.string.chat_settings_clear),
            message = "确定要清空所有聊天记录吗？记忆也会被清除。",
            positiveText = getString(R.string.common_confirm),
            iconRes = R.drawable.ic_delete,
            iconTint = edenColor(R.attr.edenColorPrimaryPressed, R.color.eden_primary_pressed)
        ) { viewModel.clearHistory() }
        dialog.dismiss()
    }
    dialog.setContentView(view)
    // 去掉 BottomSheet 默认白底，让自定义粉色圆角面板生效
    (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    dialog.show()
}
