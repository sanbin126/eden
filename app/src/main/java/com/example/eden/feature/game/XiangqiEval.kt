package com.example.eden.feature.game

import kotlin.math.abs

/** 中列奖励：越靠近中路越高，鼓励马炮出动 */
internal fun colBonus(col: Int): Int = (2 - abs(col - 4)) * 3
