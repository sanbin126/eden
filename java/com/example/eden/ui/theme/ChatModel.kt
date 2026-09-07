package com.example.eden.ui.theme


data class ChatRequest(
    val model: String,
    val messages: List<Message>
)

data class Message(val role: String, val content: String)

data class ChatResponse(
    val id: String,
    val choices: List<Choice>
)

data class Choice(val message: Message)
