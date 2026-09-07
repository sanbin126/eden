package com.example.eden

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(private var messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private const val VIEW_TYPE_DIVIDER = 3
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun getMessages(): List<ChatMessage> = messages

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].type) {
            MessageType.TYPE_SENT -> VIEW_TYPE_SENT
            MessageType.TYPE_RECEIVED -> VIEW_TYPE_RECEIVED
            MessageType.TYPE_DIVIDER -> VIEW_TYPE_DIVIDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_right, parent, false)
                SentMessageViewHolder(view)
            }
            VIEW_TYPE_RECEIVED -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_left, parent, false)
                ReceivedMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_divider, parent, false)
                DividerViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        when (holder) {
            is SentMessageViewHolder -> holder.bind(message, timeFormat)
            is ReceivedMessageViewHolder -> holder.bind(message, timeFormat)
            is DividerViewHolder -> holder.bind(message)
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

    // ========== 原有 ViewHolder 保持不变 ==========
    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message_right)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time_right)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_avatar_right)

        fun bind(message: ChatMessage, timeFormat: SimpleDateFormat) {
            tvMessage.text = message.content
            tvTime.text = timeFormat.format(Date(message.timestamp))
            val myAvatar = AvatarManager.loadAvatar(itemView.context, false)
            if (myAvatar != null) {
                val roundedDrawable = RoundedBitmapDrawableFactory.create(itemView.resources, myAvatar)
                roundedDrawable.cornerRadius = myAvatar.width.coerceAtMost(myAvatar.height) / 2f
                ivAvatar.setImageDrawable(roundedDrawable)
            } else {
                ivAvatar.setImageResource(R.drawable.ic_user_avatar)
            }
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message_left)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time_left)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_avatar_left)

        fun bind(message: ChatMessage, timeFormat: SimpleDateFormat) {
            tvMessage.text = message.content
            tvTime.text = timeFormat.format(Date(message.timestamp))
            val mikuAvatar = AvatarManager.loadAvatar(itemView.context, true)
            if (mikuAvatar != null) {
                val roundedDrawable = RoundedBitmapDrawableFactory.create(itemView.resources, mikuAvatar)
                roundedDrawable.cornerRadius = mikuAvatar.width.coerceAtMost(mikuAvatar.height) / 2f
                ivAvatar.setImageDrawable(roundedDrawable)
            } else {
                ivAvatar.setImageResource(R.drawable.miku)
            }
        }
    }
}