package com.example.eden.feature.game


import com.example.eden.domain.economy.EconomyManager
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.daily.LoopStep
import com.example.eden.feature.story.MilestoneRepository
import com.example.eden.domain.world.WorldEvent
import com.example.eden.domain.world.WorldStateStore
import android.content.Context

/**
 * 小游戏的统一结算。
 *
 * 三个小游戏（扫雷 / 五子棋 / 象棋）结束时的奖励口径集中在这里，避免各写一份跑偏：
 * - **円**：胜 +10 / 负（含平局）+5，每日上限 50（走 [EconomyManager.earn] 封顶）。
 * - **好感**：每天首次结算 +3（输赢都一样），每日上限 3。
 * - **中途退出 / 放弃**：调用方不调用 [settle] 即视为不结算。
 *
 * 此外，一局真正结算即视为完成每日任务里的「玩游戏」一项，在这里统一打点。
 */
object GameRewards {
    private const val GAME_YEN_WIN = 10
    private const val GAME_YEN_LOSE = 5
    private const val GAME_YEN_DAILY_CAP = 50

    private const val GAME_AFFECTION = 3

    private const val COUNTER_GAME_YEN = "game_yen"
    private const val COUNTER_GAME_AFFECTION = "game_aff"

    /**
     * 结算一局小游戏。
     *
     * @param win 是否获胜（平局按未获胜处理，同样给 +5 円）
     * @return 本次实际入账的「円」（今日封顶后可能为 0）
     */
    fun settle(context: Context, win: Boolean): Int {
        // 一局真正结算 = 每日任务「和三玖玩一局小游戏」达成（中途退出不走这里）
        DailyLoopManager.markDone(context, LoopStep.GAME)
        val yen = EconomyManager.earn(
            context,
            COUNTER_GAME_YEN,
            if (win) GAME_YEN_WIN else GAME_YEN_LOSE,
            GAME_YEN_DAILY_CAP
        )
        // 好感每天只结算一次（输赢都是 +3），用 cap=1 的「每日一次」额度去重
        if (EconomyManager.tryDailyGrant(context, COUNTER_GAME_AFFECTION, 1) == 1) {
            val (newAffection, crossedMilestone) = EconomyManager.increase(context, GAME_AFFECTION)
            if (crossedMilestone) MilestoneRepository.onCrossed(context, newAffection)
        }
        // 情绪结算：赢/输的兴奋度不同，写在奖励之后，保证数值结算失败不影响情绪
        WorldStateStore.applyEvent(context, event = WorldEvent.PlayedGame(win))
        return yen
    }
}
