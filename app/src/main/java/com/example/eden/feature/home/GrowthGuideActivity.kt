package com.example.eden.feature.home


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import android.os.Bundle
import android.widget.ImageView

/**
 * 成长说明页。
 *
 * 把「円」与「好感度」的获取途径、每日上限集中列在同一页，避免散落在各功能里说不清。
 * 纯展示页，内容写死在 [R.layout.activity_growth_guide] 中，这里只负责退出。
 */
class GrowthGuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_growth_guide)
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
    }
}
