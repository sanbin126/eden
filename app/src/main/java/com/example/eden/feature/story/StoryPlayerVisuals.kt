package com.example.eden.feature.story

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.example.eden.domain.character.StoryLibrary
import kotlin.math.roundToInt

/**
 * 剧情播放器的背景 / 立绘演出。
 *
 * 从 StoryPlayerActivity 原样拆出：仅以扩展函数承载，视图与缓存仍保存在 Activity 上，
 * 判定规则、回调与拆出前一致。
 */

// ==================== 背景 / 立绘 ====================

internal fun StoryPlayerActivity.applyBackground(backgroundId: String) {
    val bg = chapter?.backgrounds?.get(backgroundId) ?: return
    bgIsCg = bg.image?.startsWith(StoryPlayerActivity.CG_ROOT) == true
    // 优先用真实背景图；缺失或解码失败时回退到上下渐变，保证不崩
    val bitmap = StoryLibrary.loadBackground(this, bg.image, resources.displayMetrics.heightPixels)
    if (bitmap != null) {
        ivStoryBg.setImageBitmap(bitmap)
    } else {
        val top = parseColor(bg.topColor, Color.parseColor("#FFEEF4"))
        val bottom = parseColor(bg.bottomColor, Color.parseColor("#CBB8DE"))
        ivStoryBg.setImageDrawable(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        )
    }
    tvSceneLabel.text = bg.label
}

private fun StoryPlayerActivity.parseColor(hex: String, fallback: Int): Int =
    runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

/**
 * 玩家（"you"）台词时的立绘演出。
 *
 * 目前玩家还没有立绘资源，所以直接清空舞台上的角色立绘。
 * 后续接入「自定义玩家立绘」时，只需在这里改成按玩家配置的图片展示
 * （例如从自定义立绘目录加载，再走 [applyPortraitHeight] 之类的高度规则），
 * 台词 / 旁白 / 分支流程都不需要再改。
 */
internal fun StoryPlayerActivity.showPlayerPortrait() {
    ivStoryPortrait.visibility = View.GONE
}

/**
 * 展示说话人立绘：固定靠左（气泡左上角），底边与对话框底边对齐，
 * 下半身被不透明浅色气泡遮住 → 呈「露出半身」的 galgame 演出。
 * 高度按角色覆写（见 [StoryPlayerActivity.PORTRAIT_HEIGHT_OVERRIDE_DP]），宽度由 adjustViewBounds 等比得出。
 */
internal fun StoryPlayerActivity.showPortrait(castId: String, face: String?) {
    // CG 背景期间隐藏立绘：CG 里已经有角色全身像，叠加会变成「两个三玖」
    if (bgIsCg) {
        ivStoryPortrait.visibility = View.GONE
        return
    }
    val key = "$castId|${face.orEmpty()}"
    val bitmap = portraitCache[key]
        ?: StoryLibrary.loadPortrait(this, castId, face, StoryPlayerActivity.PORTRAIT_TARGET_PX)?.also { portraitCache[key] = it }
    if (bitmap == null) {
        ivStoryPortrait.visibility = View.GONE
        return
    }
    applyPortraitHeight(castId)
    ivStoryPortrait.setImageBitmap(bitmap)
    ivStoryPortrait.visibility = View.VISIBLE
}

private fun StoryPlayerActivity.applyPortraitHeight(castId: String) {
    val dp = StoryPlayerActivity.PORTRAIT_HEIGHT_OVERRIDE_DP[castId] ?: StoryPlayerActivity.PORTRAIT_HEIGHT_DEFAULT_DP
    val px = (dp * resources.displayMetrics.density).roundToInt()
    val lp = ivStoryPortrait.layoutParams
    if (lp.height != px) {
        lp.height = px
        ivStoryPortrait.layoutParams = lp
    }
}
