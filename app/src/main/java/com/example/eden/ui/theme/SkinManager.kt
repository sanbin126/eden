package com.example.eden.ui.theme


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import android.content.Context
import androidx.annotation.StyleRes

/**
 * 皮肤（主题）管理。
 *
 * 目前提供两套：
 * · [SKIN_PINK]      —— 原有粉色主题，默认；
 * · [SKIN_HYDRANGEA] —— 蓝色绣球主题（磨砂玻璃聊天按钮 + 动态柔光）。
 *
 * 各 Activity 继承 [BaseActivity]，在 onCreate 里先 setTheme 再 setContentView，
 * 因此切换皮肤后 recreate 即可全局生效。
 */
object SkinManager {

    private const val PREFS = "eden_skin"
    private const val KEY_SKIN = "skin_id"

    const val SKIN_PINK = "pink"
    const val SKIN_HYDRANGEA = "hydrangea"

    /** 设置页展示顺序。 */
    val allSkins = listOf(SKIN_PINK, SKIN_HYDRANGEA)

    fun currentSkin(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIN, SKIN_PINK)
            ?.takeIf { it in allSkins }
            ?: SKIN_PINK

    fun setSkin(context: Context, skin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIN, if (skin in allSkins) skin else SKIN_PINK)
            .apply()
    }

    @StyleRes
    fun themeRes(skin: String): Int = when (skin) {
        SKIN_HYDRANGEA -> R.style.Theme_Eden_Skin_Hydrangea
        else -> R.style.Theme_Eden
    }

    @StyleRes
    fun currentThemeRes(context: Context): Int = themeRes(currentSkin(context))

    fun displayName(context: Context, skin: String): String = context.getString(
        when (skin) {
            SKIN_HYDRANGEA -> R.string.theme_hydrangea
            else -> R.string.theme_pink
        }
    )
}
