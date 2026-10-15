package com.example.eden

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.eden.character.Characters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 聊天列表适配器。
 *
 * 列表数据由 ChatViewModel 持有，这里通过 [submit] 接收新列表：
 * 「在旧列表末尾追加」这种最常见的情形只触发 notifyItemRangeInserted，保留插入动画；
 * 其余情况（清空、删除错误气泡等）退化为整体刷新。
 */
class ChatAdapter(
    private var messages: List<ChatMessage> = emptyList(),
    private val onErrorClick: () -> Unit = {},
    private val onMessageLongClick: (ChatMessage, Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private const val VIEW_TYPE_DIVIDER = 3
        private const val VIEW_TYPE_ERROR = 4
        private const val VIEW_TYPE_STORY_END = 5

        /** 两条聊天消息间隔超过该值（10 分钟）时，在它们之间插入一条时间戳分隔线 */
        private const val GAP_MS = 10 * 60 * 1000L
    }

    /**
     * 用新的原始消息列表刷新界面。
     *
     * 先把它展开成「界面展示列表」（在需要的位置插入日期/时间分隔线），
     * 再与当前展示列表做 diff：
     * - 末尾追加（最常见）：走 [notifyItemRangeInserted]，保留插入动画。
     * - 流式更新（长度相同、除末项外身份全同）：只 [notifyItemChanged] 末项，
     *   避免每个增量都触发整体重绘。
     * - 其余（跨天、清空、删除、错误气泡替换等）：整体刷新。
     */
    fun submit(source: List<ChatMessage>) {
        val display = reconcileDividers(messages, buildDisplayItems(source, System.currentTimeMillis()))
        val old = messages
        if (display.size >= old.size && samePrefix(old, display)) {
            messages = display
            if (display.size > old.size) {
                notifyItemRangeInserted(old.size, display.size - old.size)
            }
            return
        }
        if (display.size == old.size && old.size >= 1 &&
            samePrefix(
                old.subList(0, old.size - 1),
                display.subList(0, display.size - 1)
            )
        ) {
            messages = display
            notifyItemChanged(old.size - 1)
            return
        }
        messages = display
        notifyDataSetChanged()
    }

    /**
     * 把「原始消息列表」展开为「界面展示列表」，在需要的位置动态插入日期/时间分隔线。
     *
     * 插入规则（顶部不显示分隔线）：
     * - 跨天：与上一条聊天消息不在同一天；
     * - 间隔超过 [GAP_MS]（10 分钟）：同一天但间隔过久，补一个时间戳；
     * - 末尾补一条「今天」分隔线：仅当最后一条聊天消息不是今天发的，
     *   连续多天未聊也只补一条（不重复）。
     *
     * 分隔线文案按「相对当前时间」实时计算（今天 / 昨天 / 月日 / 年月日），
     * 因此第二天再看时会自动显示成「昨天 xx:xx」。
     */
    private fun buildDisplayItems(source: List<ChatMessage>, now: Long): List<ChatMessage> {
        val result = ArrayList<ChatMessage>(source.size + 4)
        var lastChatTs: Long? = null
        for (msg in source) {
            val isChat = msg.type == MessageType.TYPE_SENT || msg.type == MessageType.TYPE_RECEIVED
            if (isChat) {
                val prev = lastChatTs
                val needDivider = prev != null &&
                    (!sameDay(prev, msg.timestamp) || msg.timestamp - prev > GAP_MS)
                if (needDivider) {
                    result.add(divider(formatDivider(msg.timestamp, now), msg.timestamp))
                }
                lastChatTs = msg.timestamp
            }
            result.add(msg)
        }
        // 末尾「新一天」分隔线：最后一条聊天消息不是今天时补一条，只补最新的这一天
        lastChatTs?.let { if (!sameDay(it, now)) result.add(divider(hourMinute(now), now)) }
        return result
    }

    /** 复用旧的分隔线对象：内容相同的分隔线沿用引用，[samePrefix] 的引用相等判定才成立，流式增量刷新不会被降级为整体刷新 */
    private fun reconcileDividers(old: List<ChatMessage>, new: List<ChatMessage>): List<ChatMessage> {
        val spare = old.filter { it.type == MessageType.TYPE_DIVIDER }.toMutableList()
        return new.map { item ->
            if (item.type != MessageType.TYPE_DIVIDER) {
                item
            } else {
                val idx = spare.indexOfFirst { it.content == item.content }
                if (idx >= 0) spare.removeAt(idx) else item
            }
        }
    }

    private fun divider(content: String, timestamp: Long) =
        ChatMessage(content = content, type = MessageType.TYPE_DIVIDER, timestamp = timestamp)

    private fun formatDivider(ts: Long, now: Long): String {
        val time = hourMinute(ts)
        return when {
            sameDay(ts, now) -> time
            isYesterday(ts, now) -> "昨天 $time"
            sameYear(ts, now) -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ts)) + " $time"
            else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(ts)) + " $time"
        }
    }

    private fun hourMinute(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    private fun sameDay(a: Long, b: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = a }
        val c2 = Calendar.getInstance().apply { timeInMillis = b }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(ts: Long, now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return sameDay(ts, cal.timeInMillis)
    }

    private fun sameYear(a: Long, b: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = a }
        val c2 = Calendar.getInstance().apply { timeInMillis = b }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
    }

    /** 引用相等即认为是同一条（ViewModel 只做追加，旧元素对象会被复用） */
    private fun samePrefix(old: List<ChatMessage>, new: List<ChatMessage>): Boolean {
        for (i in old.indices) {
            if (old[i] !== new[i]) return false
        }
        return true
    }

    /** 强制重建所有可见项：用于头像变更等与消息内容无关的刷新 */
    fun refresh() = notifyDataSetChanged()

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].type) {
            MessageType.TYPE_SENT -> VIEW_TYPE_SENT
            MessageType.TYPE_RECEIVED -> VIEW_TYPE_RECEIVED
            MessageType.TYPE_DIVIDER -> VIEW_TYPE_DIVIDER
            MessageType.TYPE_STORY_END -> VIEW_TYPE_STORY_END
            MessageType.TYPE_ERROR -> VIEW_TYPE_ERROR
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SENT -> SentMessageViewHolder(
                inflater.inflate(R.layout.item_message_right, parent, false)
            )
            VIEW_TYPE_RECEIVED -> ReceivedMessageViewHolder(
                inflater.inflate(R.layout.item_message_left, parent, false)
            )
            VIEW_TYPE_ERROR -> ErrorViewHolder(
                inflater.inflate(R.layout.item_message_error, parent, false)
            )
            VIEW_TYPE_STORY_END -> StoryEndViewHolder(
                inflater.inflate(R.layout.item_story_end, parent, false)
            )
            else -> DividerViewHolder(
                inflater.inflate(R.layout.item_chat_divider, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        when (holder) {
            is SentMessageViewHolder -> holder.bind(message, timeFormat)
            is ReceivedMessageViewHolder -> holder.bind(message, timeFormat)
            is DividerViewHolder -> holder.bind(message)
            is StoryEndViewHolder -> holder.bind(message)
            is ErrorViewHolder -> holder.bind(message, onErrorClick)
        }
    }

    override fun getItemCount(): Int = messages.size

    // ========== 分隔线 ViewHolder ==========
    inner class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDivider: TextView = itemView.findViewById(R.id.tv_divider_text)

        fun bind(message: ChatMessage) {
            tvDivider.text = message.content
        }
    }

    // ========== 小剧场结束横线 ViewHolder ==========
    inner class StoryEndViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(message: ChatMessage) {
            // 无需绑定内容，仅展示一条横线表示剧情结束
        }
    }

    // ========== 失败重试 ViewHolder ==========
    inner class ErrorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvError: TextView = itemView.findViewById(R.id.tv_error_message)

        fun bind(message: ChatMessage, onClick: () -> Unit) {
            tvError.text = message.content
            itemView.setOnClickListener { onClick() }
        }
    }

    // ========== 原有 ViewHolder 保持不变 ==========
    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message_right)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time_right)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_avatar_right)

        fun bind(message: ChatMessage, timeFormat: SimpleDateFormat) {
            itemView.setOnLongClickListener {
                val pos = adapterPosition ?: return@setOnLongClickListener true
                onMessageLongClick(message, pos)
                true
            }
            tvMessage.text = message.content
            tvTime.text = timeFormat.format(Date(message.timestamp))
            // 用户头像固定为系统默认图，不支持自定义
            ivAvatar.setImageResource(R.drawable.ic_user_avatar)
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message_left)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time_left)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_avatar_left)

        fun bind(message: ChatMessage, timeFormat: SimpleDateFormat) {
            itemView.setOnLongClickListener {
                val pos = adapterPosition ?: return@setOnLongClickListener true
                onMessageLongClick(message, pos)
                true
            }
            tvMessage.text = message.content
            tvTime.text = timeFormat.format(Date(message.timestamp))
            // 角色头像固定为系统图，不支持自定义
            ivAvatar.setImageResource(Characters.current.portraitRes)
        }
    }
}
