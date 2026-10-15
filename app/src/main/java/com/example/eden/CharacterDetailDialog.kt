package com.example.eden

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.eden.character.MikuPersona
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 角色详情页（居中弹窗）。
 *
 * 由 [MainActivity] 点击角色信息卡弹出。承载「立绘 / 好感 / 记忆」之外的角色主页信息：
 * 顶部身份区 + 今日心情与语录 + 好感度 + 陪伴与羁绊 + 底部操作。
 *
 * 窗口宽度由代码设为屏幕 90%，高度自适应，避免遮挡整屏。
 * 所有展示数据都复用现有 Manager（好感度 / 陪伴时长 / 签到 / 纪念日 / 头像），
 * 唯一新增的是「今日心情 + 一句语录」——按当天日期做种子确定性生成，点「换一句」循环。
 *
 * @param firstChatTime 首次聊天时间戳（由调用方异步从 Room 取出），为空表示还没开始。
 * @param onOpenChat    点「去聊天」回调（弹窗已先 dismiss）。
 * @param onOpenDiary   点「去日记」回调（弹窗已先 dismiss）。
 */
class CharacterDetailDialog(
    context: Context,
    private val firstChatTime: Long?,
    private val onOpenChat: () -> Unit,
    private val onOpenDiary: () -> Unit
) : Dialog(context) {

    private lateinit var tvName: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvTogether: TextView
    private lateinit var tvMoodEmoji: TextView
    private lateinit var tvMoodLabel: TextView
    private lateinit var tvMoodLine: TextView
    private lateinit var tvMoodReroll: TextView
    private lateinit var tvAffection: TextView
    // 注意：布局里是普通 <View>，类型必须声明为 View，
    // 否则 findViewById<LinearLayout> 会在运行时强转失败（ClassCastException）导致闪退
    private lateinit var viewAffectionFill: View
    private lateinit var viewAffectionRest: View
    private lateinit var tvAffectionHint: TextView
    private lateinit var cdMilestoneEntry: LinearLayout
    private lateinit var cdMilestoneDot: View
    private lateinit var tvStatCompany: TextView
    private lateinit var tvStatSign: TextView
    private lateinit var tvStatMeet: TextView
    private lateinit var btnChat: TextView
    private lateinit var btnDiary: TextView
    private lateinit var btnClose: TextView

    /** 本次展示的心情词条与其语录索引（点「换一句」时在词条内循环）。 */
    private lateinit var mood: MikuPersona.MoodLine
    private var moodLineIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_character_detail)

        // 圆角背景自带，去掉系统窗口默认背景，避免圆角外露出白底
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.let {
            val lp = it.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }

        bindViews()
        bindIdentity()
        bindMood()
        bindAffection()
        bindMilestoneEntry()
        bindCompanionship()
        bindActions()
    }

    private fun bindViews() {
        tvName = findViewById(R.id.cd_name)
        tvTitle = findViewById(R.id.cd_title)
        tvTogether = findViewById(R.id.cd_together)
        tvMoodEmoji = findViewById(R.id.cd_mood_emoji)
        tvMoodLabel = findViewById(R.id.cd_mood_label)
        tvMoodLine = findViewById(R.id.cd_mood_line)
        tvMoodReroll = findViewById(R.id.cd_mood_reroll)
        tvAffection = findViewById(R.id.cd_affection)
        viewAffectionFill = findViewById(R.id.cd_affection_fill)
        viewAffectionRest = findViewById(R.id.cd_affection_rest)
        tvAffectionHint = findViewById(R.id.cd_affection_hint)
        cdMilestoneEntry = findViewById(R.id.cd_milestone_entry)
        cdMilestoneDot = findViewById(R.id.cd_milestone_dot)
        tvStatCompany = findViewById(R.id.cd_stat_company)
        tvStatSign = findViewById(R.id.cd_stat_sign)
        tvStatMeet = findViewById(R.id.cd_stat_meet)
        btnChat = findViewById(R.id.cd_btn_chat)
        btnDiary = findViewById(R.id.cd_btn_diary)
        btnClose = findViewById(R.id.cd_btn_close)
    }

    // ==================== 顶部身份区 ====================
    private fun bindIdentity() {
        // 头像固定为系统图（见布局 ?attr/edenCharChibi），不再读取自定义头像
        tvName.text = MikuPersona.NAME
        tvTitle.text = context.getString(R.string.character_detail_nickname)

        val days = AnniversaryManager.daysSinceStart(context, firstChatTime)
        tvTogether.text = if (days == null) "还没开始的故事" else "在一起 $days 天"
    }

    // ==================== 今日心情 + 一句语录 ====================
    private fun bindMood() {
        mood = pickMood()
        moodLineIndex = daySeed() % mood.lines.size
        renderMood()

        tvMoodReroll.setOnClickListener {
            moodLineIndex = (moodLineIndex + 1) % mood.lines.size
            renderMood()
        }
    }

    private fun renderMood() {
        tvMoodEmoji.text = mood.emoji
        tvMoodLabel.text = "今日心情 · ${mood.label}"
        tvMoodLine.text = mood.lines[moodLineIndex]
    }

    /**
     * 按「生日 > 节日 > 雨雪天气 > 默认心情池」优先级挑选今日心情。
     * 雨雪只读 [WeatherManager] 写入的本地缓存，不发起新网络请求。
     */
    private fun pickMood(): MikuPersona.MoodLine {
        val cal = Calendar.getInstance()

        if (AnniversaryManager.isBirthdayToday(context, cal)) {
            return MikuPersona.MoodLine("🎂", "生日快乐", MikuPersona.BIRTHDAY_LINES)
        }

        FestivalCalendar.todayFestival(cal)?.let { festival ->
            MikuPersona.FESTIVAL_LINES[festival]?.let {
                return MikuPersona.MoodLine("🎉", festival, it)
            }
        }

        val weather = context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
            .getString("weather_text", "") ?: ""
        if (weather.contains("雪")) {
            return MikuPersona.MoodLine("❄️", "下雪了", MikuPersona.SNOW_MOOD_LINES)
        }
        if (weather.contains("雨")) {
            return MikuPersona.MoodLine("🌧️", "在下雨", MikuPersona.RAIN_MOOD_LINES)
        }

        return MikuPersona.MOODS[daySeed() % MikuPersona.MOODS.size]
    }

    /** 用当天日期（yyyyMMdd）做种子，保证同一天内心情与语录稳定。 */
    private fun daySeed(): Int =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()).toIntOrNull() ?: 0

    // ==================== 好感度 ====================
    private fun bindAffection() {
        val affection = AffectionManager.getAffection(context)
        tvAffection.text = "$affection"

        // 好感度不设上限，进度条改为表达「距离下一个里程碑」的进度：
        // 在当前 10 点区间内前进，填满即解锁下一段小剧场。
        val inBlock = affection % 10
        (viewAffectionFill.layoutParams as LinearLayout.LayoutParams).weight = inBlock.toFloat()
        (viewAffectionRest.layoutParams as LinearLayout.LayoutParams).weight =
            (10 - inBlock).toFloat()

        val nextMilestone = (affection / 10 + 1) * 10
        tvAffectionHint.text = "距离下一个里程碑还差 ${nextMilestone - affection} 点"
    }

    // ==================== 好感小剧场入口 ====================
    /**
     * 入口常显；有未看剧情时点亮红点提示。点击进入新剧情（有未看）或列表页（回看）。
     * 未解锁的剧情不会出现在列表里（见 MilestoneStoryListActivity）。
     */
    private fun bindMilestoneEntry() {
        cdMilestoneDot.visibility =
            if (MilestoneManager.hasUnviewed(context)) View.VISIBLE else View.GONE
        cdMilestoneEntry.setOnClickListener {
            dismiss()
            MilestoneManager.openEntry(context)
        }
    }

    // ==================== 陪伴与羁绊 ====================
    private fun bindCompanionship() {
        val seconds = CompanyTimeManager.getTodaySeconds(context)
        tvStatCompany.text = CompanyTimeManager.format(seconds)

        tvStatSign.text = "${SignInManager.getContinuousDays(context)} 天"

        tvStatMeet.text = firstChatTime?.let {
            SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(it))
        } ?: context.getString(R.string.common_dash)
    }

    // ==================== 底部操作 ====================
    private fun bindActions() {
        btnChat.setOnClickListener {
            dismiss()
            onOpenChat()
        }
        btnDiary.setOnClickListener {
            dismiss()
            onOpenDiary()
        }
        btnClose.setOnClickListener { dismiss() }
    }
}
