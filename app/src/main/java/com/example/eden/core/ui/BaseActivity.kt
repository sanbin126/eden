package com.example.eden.core.ui


import com.example.eden.ui.theme.SkinManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 所有界面的基类：在 setContentView 之前套用用户选择的皮肤。
 *
 * onResume 时若发现皮肤已被切换（例如从设置页返回），自动 recreate 让当前界面换肤。
 */
open class BaseActivity : AppCompatActivity() {

    private var appliedSkin: String = SkinManager.SKIN_PINK

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedSkin = SkinManager.currentSkin(this)
        setTheme(SkinManager.themeRes(appliedSkin))
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (appliedSkin != SkinManager.currentSkin(this)) recreate()
    }
}
