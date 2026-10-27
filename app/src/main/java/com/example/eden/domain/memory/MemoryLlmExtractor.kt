package com.example.eden.domain.memory

import android.util.Log
import com.example.eden.core.network.ChatRequest
import com.example.eden.core.network.LlmClient
import com.example.eden.core.network.Message
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException

/**
 * 阶段二：用大模型补充规则抽取覆盖不到的长尾事实（职业变化、养宠物、人际称呼等）。
 *
 * 只在用户消息落库后异步调用，不阻塞回复；任何失败（网络 / 解析 / 越权）都静默降级为空结果，
 * 由规则抽取兜底。提示词刻意要求「只记稳定事实、忽略临时情绪」，避免把「今天好累」写进长期记忆。
 */
object MemoryLlmExtractor {

    /** 太短的消息（「嗯」「好的」）没有抽取价值，直接跳过以省一次请求。 */
    private const val MIN_TEXT_LENGTH = 5

    /** 单条记忆内容上限，防止模型把整段对话塞进来污染检索。 */
    private const val MAX_CONTENT_LENGTH = 40

    private const val SYSTEM_PROMPT = """你是一个信息抽取器，只负责从用户的一句话里提取「值得长期记住」的事实。

只提取满足以下条件的内容：
1. 未来一段时间仍然成立（姓名、年龄、城市、职业、长期喜好、养宠物、重要人际关系、长期目标）；
2. 是用户亲口说出的关于自己的信息。

绝对不要提取：
- 临时情绪与状态（累、困、烦、饿、心情不好）；
- 一次性的动作与安排（今天吃了什么、待会要出门、刚刚在做什么）；
- 疑问句、假设、玩笑与反讽；
- 对方（AI 角色）的信息。

输出要求：
- 只输出一个 JSON 数组，不要任何解释、前后缀或 Markdown 代码块；
- 没有可提取内容时输出 []；
- 每个元素包含 4 个字段：
  - category：只能是 identity / preference / event / relationship 之一；
  - key：稳定的英文去重键，小写字母加下划线，例如 name、city、job、fav_drink、pet；
  - content：一句中文事实，第三人称描述用户，例如「用户养了一只叫团子的猫」，不超过 30 字；
  - importance：1 到 5 的整数，姓名/年龄这类身份信息给 5，长期喜好给 3，一般经历给 4。"""

    /**
     * 抽取一条用户消息里的稳定事实。
     * 网络或解析失败返回空列表（不影响聊天），取消异常照常透传。
     */
    suspend fun extract(text: String): List<ExtractedFact> {
        val trimmed = text.trim()
        if (trimmed.length < MIN_TEXT_LENGTH) return emptyList()
        return try {
            val response = LlmClient.chat(
                ChatRequest(
                    messages = listOf(
                        Message(role = "system", content = SYSTEM_PROMPT),
                        Message(role = "user", content = trimmed)
                    ),
                    // 抽取要稳定可解析，尽量压低随机性
                    temperature = 0.1
                )
            )
            val reply = response.data?.reply
            if (response.code != 0 || reply.isNullOrBlank()) {
                Log.w(TAG, "抽取响应异常：code=${response.code} message=${response.message}")
                return emptyList()
            }
            parse(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LLM 抽取失败：${e.message}")
            emptyList()
        }
    }

    /** 解析模型返回的 JSON 数组；兼容被 Markdown 代码块包裹或前后带解释的情况。 */
    fun parse(raw: String): List<ExtractedFact> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()

        val facts = LinkedHashMap<String, ExtractedFact>()
        val array = try {
            JsonParser.parseString(raw.substring(start, end + 1)).asJsonArray
        } catch (e: Exception) {
            Log.w(TAG, "抽取结果不是合法 JSON：${e.message}")
            return emptyList()
        }

        for (element in array) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val key = obj.get("key")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val content = obj.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (key.isEmpty() || content.isEmpty()) continue
            if (facts.containsKey(key)) continue

            val category = MemoryCategory.of(
                obj.get("category")?.takeIf { it.isJsonPrimitive }?.asString
            )
            val importance = obj.get("importance")
                ?.takeIf { it.isJsonPrimitive }
                ?.asInt
                ?.coerceIn(1, 5)
                ?: defaultImportance(category)

            facts[key] = ExtractedFact(
                category = category,
                subject = MemorySubject.USER,
                key = key.take(32),
                content = content.take(MAX_CONTENT_LENGTH),
                importance = importance
            )
        }
        return facts.values.toList()
    }

    private fun defaultImportance(category: MemoryCategory): Int = when (category) {
        MemoryCategory.IDENTITY -> 5
        MemoryCategory.EVENT -> 4
        else -> 3
    }

    private const val TAG = "MemoryLlmExtractor"
}
