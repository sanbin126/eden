package com.example.eden.feature.music


import com.example.eden.R
import com.example.eden.feature.home.MainActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 音乐入口底部弹窗（粉色卡片式，样式对齐小游戏 / 环境音选择面板）。
 *
 * 由 [MainActivity] 的 btnMusic 点击触发，列出 3 个选项：
 * - "播放内置环境音"：按当前天气启动 AmbientSynth（或 res/raw mp3）
 * - "选择本地音乐"：触发 MainActivity 的 OpenDocument launcher
 * - "停止"：仅当正在播放时显示（由外部通过 [showStopItem] 控制）
 *
 * 通过 [Listener] 回调把选择结果回传给宿主，弹窗自行 dismiss。
 */
class MusicPickerDialog : BottomSheetDialogFragment() {

    interface Listener {
        fun onPickAmbient()
        fun onPickLocalFile()
        fun onPickStop()
    }

    private var listener: Listener? = null
    private var showStopItem: Boolean = false

    fun setListener(l: Listener) { listener = l }
    fun showStopItem(show: Boolean) { showStopItem = show }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_music_picker, container, false)

        view.findViewById<View>(R.id.item_ambient).setOnClickListener {
            listener?.onPickAmbient()
            dismiss()
        }
        view.findViewById<View>(R.id.item_local).setOnClickListener {
            listener?.onPickLocalFile()
            dismiss()
        }

        val stop = view.findViewById<View>(R.id.item_stop)
        stop.visibility = if (showStopItem) View.VISIBLE else View.GONE
        stop.setOnClickListener {
            listener?.onPickStop()
            dismiss()
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        // 去掉 BottomSheet 默认白底，让自定义粉色圆角面板生效
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
}
