package com.example.eden

import android.content.Context
import android.content.SharedPreferences

object AffectionManager {
    private const val PREFS_NAME = "affection_prefs"
    private const val KEY_AFFECTION = "affection_value"
    private const val MAX_AFFECTION = 100

    /**
     * 获取当前好感度
     */
    fun getAffection(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_AFFECTION, 0)
    }

    /**
     * 增加好感度，返回 Pair(新值, 是否刚突破10的倍数)
     * 如果已达到上限则不再增加
     */
    fun increase(context: Context, amount: Int = 1): Pair<Int, Boolean> {
        val current = getAffection(context)
        if (current >= MAX_AFFECTION) return Pair(current, false)

        val newValue = (current + amount).coerceAtMost(MAX_AFFECTION)
        getPrefs(context).edit().putInt(KEY_AFFECTION, newValue).apply()

        val crossedMilestone = (current / 10) < (newValue / 10)
        return Pair(newValue, crossedMilestone)
    }

    /**
     * 减少好感度（保留备用）
     */
    fun decrease(context: Context, amount: Int = 1): Int {
        val current = getAffection(context)
        val newValue = (current - amount).coerceAtLeast(0)
        getPrefs(context).edit().putInt(KEY_AFFECTION, newValue).apply()
        return newValue
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}