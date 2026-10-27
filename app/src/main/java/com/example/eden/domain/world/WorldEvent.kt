package com.example.eden.domain.world

sealed interface WorldEvent {
    data object SessionActive : WorldEvent                       // 冷启动 / 回到前台
    data object UserMessage : WorldEvent                         // 用户发来一条消息
    data object Praised : WorldEvent                             // 明显夸奖
    data object AskedIfAi : WorldEvent                           // 问"你是不是 AI"
    data object SignedIn : WorldEvent                            // 签到成功
    data class PlayedGame(val win: Boolean) : WorldEvent         // 一局游戏结束
    data class FedGift(val favorite: Boolean) : WorldEvent       // 投喂成功
    data class ChapterFinished(val index: Int) : WorldEvent      // 章节首次通关
    data class FocusFinished(val minutes: Int) : WorldEvent      // 专注结束
    data class MilestoneCrossed(val value: Int) : WorldEvent     // 好感里程碑
    data class AbsenceSettled(val days: Int) : WorldEvent        // 久别结算（内部派生）
}

/** 事件对 (valence, arousal) 的增量。所有数值只在这里维护，调整情绪敏感度改这一处。 */
fun WorldEvent.delta(): Pair<Float, Float> = when (this) {
    WorldEvent.SessionActive -> 0f to 0f
    WorldEvent.UserMessage -> 0.02f to 0.05f
    WorldEvent.Praised -> 0.30f to 0.20f
    WorldEvent.AskedIfAi -> -0.30f to 0.30f
    WorldEvent.SignedIn -> 0.10f to 0.05f
    is WorldEvent.PlayedGame -> if (win) 0.20f to 0.40f else 0.05f to 0.15f
    is WorldEvent.FedGift -> if (favorite) 0.25f to 0.15f else 0.15f to 0.10f
    is WorldEvent.ChapterFinished -> 0.25f to 0.20f
    is WorldEvent.FocusFinished -> 0.15f to 0.10f
    is WorldEvent.MilestoneCrossed -> 0.40f to 0.30f
    is WorldEvent.AbsenceSettled -> -0.50f to -0.20f
}
