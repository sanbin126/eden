package com.example.eden.ui.theme

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

/**
 * 从当前主题解析 Eden 语义颜色属性（?attr/edenColorXxx）。
 *
 * 布局 / drawable 已统一走 ?attr；这里让 Kotlin 里动态设置的色值也跟随主题，
 * 这样「换肤」（切换 Theme.Eden.Skin.xxx）时动态颜色也会一起变。
 * 若当前主题未定义该属性，则回退到 [fallbackColorRes]（即 colors.xml 中的默认皮肤色）。
 */
fun Context.edenColor(attrRes: Int, fallbackColorRes: Int): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(attrRes, value, true) && value.resourceId != 0) {
        ContextCompat.getColor(this, value.resourceId)
    } else {
        ContextCompat.getColor(this, fallbackColorRes)
    }
}

/**
 * 从当前主题解析色组属性（?attr/edenGreetingColors 这类数组引用）。
 * 未定义时回退到 [fallbackArrayRes]。
 */
fun Context.edenColorArray(attrRes: Int, fallbackArrayRes: Int): IntArray {
    val value = TypedValue()
    val arrayRes =
        if (theme.resolveAttribute(attrRes, value, true) && value.resourceId != 0) {
            value.resourceId
        } else {
            fallbackArrayRes
        }
    return resources.obtainTypedArray(arrayRes).let { ta ->
        val arr = IntArray(ta.length()) { ta.getColor(it, 0) }
        ta.recycle()
        arr
    }
}
