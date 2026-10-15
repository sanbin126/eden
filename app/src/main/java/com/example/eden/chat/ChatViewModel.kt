package com.example.eden.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.example.eden.ChatMessage

/**
 * 一次性消费的事件包装：避免 LiveData 在配置变更/重新订阅时重复弹出同一条 Toast。
 */
class Event<out T>(private val content: T) {
    private var handled = false

    fun getIfNotHandled(): T? =
        if (handled) null else {
            handled = true
            content
        }
}

/**
 * 聊天页 ViewModel。
 *
 * 真正的状态（消息列表、长期记忆）与 AI 生成逻辑放在进程级的 [ChatSession] 里，
 * 这样退出聊天页（左滑/返回）后 AI 仍能继续把这句话回答完，而不是被 `viewModelScope` 取消。
 * ViewModel 只做一层转发，Activity 的调用方式保持不变。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    init {
        ChatSession.init(application)
    }

    val messages: LiveData<List<ChatMessage>> = ChatSession.messages
    val toast: LiveData<Event<String>> = ChatSession.toast
    val isReplying: LiveData<Boolean> = ChatSession.isReplying

    fun loadMessages() = ChatSession.loadMessages()

    fun send(text: String) = ChatSession.send(text)

    fun appendReceived(content: String) = ChatSession.appendReceived(content)

    fun retry() = ChatSession.retry()

    fun deleteMessage(message: ChatMessage) = ChatSession.deleteMessage(message)

    fun regenerateReply(message: ChatMessage) = ChatSession.regenerateReply(message)

    fun clearHistory() = ChatSession.clearHistory()
}
