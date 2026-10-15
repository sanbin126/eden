# 聊天体验优化方案

## Context

当前聊天页有三处体验短板，均由用户在体验后指出：

1. **固定回复命中过粗**：[MikuPersona.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/character/MikuPersona.kt#L147-L159) 用 `userInput.contains(keyword)`，导致 "你好烦" 命中 "你好"、"我喜欢你家历史书" 同时命中 "喜欢你" 与 "历史"，给出风马牛不相及的固定台词。
2. **消息层缺失交互**：长按消息无菜单（复制 / 删除 / 重新生成）；回复失败只有点击错误气泡重试，无法对已成功的角色消息"重摇"。
3. **未走流式输出**：后端 [main.py](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/../../server/main.py) 已实现 SSE（`stream=true`），但客户端 [ChatRequest](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/ChatModel.kt) 只发非流式，首字延迟 1~2s。

目标：在不破坏现有持久化/记忆机制的前提下，把这三处改掉，让匹配更精确、消息可操作、首字延迟降到 200ms 级。

---

## 实施步骤

### 一、整句归一化 + 边界匹配（任务 1）

**文件**：[MikuPersona.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/character/MikuPersona.kt)

重写 `fixedReplyFor(userInput)`，新增私有 `normalize` / `hasBoundaryMatch` / `isCJK`，规则：

1. **归一化**：`trim()` 后循环剥离句末/句首的标点（。，！？~ ，全角空格）与句末语气词（啊呀吧哦呢哈啦呐哟哇）。让 "你好啊" / "早安哦" 仍能命中。
2. **边界匹配**：关键词在归一化句中每次出现时，其前后字符必须是非汉字（标点/空格/字母/数字）或字符串首尾，才算命中。
   - "你好烦" → "你好" 后接汉字"烦" → 不命中 → 走大模型 ✓
   - "我喜欢你家历史书" → "喜欢你" 前贴"我"、"历史" 前后贴"家/书" → 都不命中 → 走大模型 ✓
   - "你好，三玖" → "你好" 前为首、后为标点"，" → 命中 ✓
3. **冲突择优**：多个关键词同时命中时取更长的一条（更具体意图优先，平手取插入序靠前）。

`ACTIONS` 40% 追加逻辑保持不变。漏匹配（如 "我喜欢你"、"聊聊历史"）会优雅回落到大模型——这正是用户期望的"宁缺毋滥"。

### 二、流式输出（任务 3，先做，是任务 2 的基础）

#### 2.1 后端 SSE 转义修复
**文件**：[server/main.py](file:///c:/Users/suwei/Desktop/eden/server/main.py#L600-L638) `_stream_upstream`

当前 `yield f"data: {delta}\n\n"` 未转义，模型输出含换行时换行后的内容会被当成新 SSE 事件丢弃。改为：

```python
yield f"data: {json.dumps(delta, ensure_ascii=False)}\n\n"
```

错误行与 `[DONE]` 保持原样（非 JSON），客户端按前缀识别。

#### 2.2 客户端请求模型
**文件**：[ChatModel.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/ChatModel.kt)

`data class ChatRequest(val messages: List<Message>)` → 增加字段 `val stream: Boolean = false`。记忆压缩仍用默认 `false`（无需流式）。

#### 2.3 流式接口
**文件**：[ApiClient.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/ApiClient.kt)

`EdenChatApi` 新增方法（保留原 `chat` 给记忆压缩用）：

```kotlin
@Streaming
@POST("api/chat")
suspend fun chatStream(@Body request: ChatRequest): ResponseBody
```

#### 2.4 仓库流式解析
**文件**：[ChatRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/ChatRepository.kt)

新增 `suspend fun requestReplyStream(messages: List<Message>): Flow<String>`：用 `flow { }.flowOn(Dispatchers.IO)`，`body.use` 内 `source.readUtf8Line()` 逐行读；非 `data:` 前缀跳过；`[DONE]` 终止；`[ERROR]` 抛 `IOException`；其余用 `gson.fromJson(payload, String::class.java)` 解码增量后 `emit`。

#### 2.5 ViewModel 流式
**文件**：[ChatViewModel.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/chat/ChatViewModel.kt)

- 新增 `_isReplying: MutableLiveData<Boolean>` + `val isReplying`，流式期间置 true，结束时（`finally`）置 false。`send()` 在 replying 时直接 return，避免流式期间并发请求把列表搞乱。
- `requestReply()` 重写为 `streamReply()`：先 `buildPayload()`（此时列表最后是刚发的用户消息，**占位符尚未插入**，避免空 assistant 消息污染上下文），再调 `streamReplyWith(payload)`。
- `streamReplyWith(payload)`：
  1. `append` 一条空内容 `TYPE_RECEIVED` 占位符；
  2. `repository.requestReplyStream(payload).collect { delta -> accum.append(delta); updateLastReceived(accum) }`；
  3. 完成后 `accum.isBlank()` 视为异常；否则 `repository.append` 落库拿 `rowId`，`replaceLast(final.copy(id=rowId))`，再 `refreshMemory()`；
  4. 异常（非取消）：`removeLast()` 抹掉占位符 → 弹 toast → append `TYPE_ERROR` 重试气泡。
- 新增私有 `updateLastReceived` / `replaceLast` / `removeLast` / `replaceMessage(old,new)`（按对象身份替换，给 send/appendReceived 回填 DB id 用）。
- `buildPayload(source = _messages.value.orEmpty())` 加可选参数，供"重新生成"传子列表。
- `send()` / `appendReceived()` 改为：`append(msg)` → `launch { id = repository.append(...); replaceMessage(msg, msg.copy(id=id)) }`，让内存消息带上 DB 主键（删除/重新生成要用）。
- `retry()`：去掉错误气泡后调 `streamReply()`。

### 三、消息交互（任务 2）

#### 3.1 DAO
**文件**：[ChatMessageDao.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/ChatMessageDao.kt)

新增 `@Query("DELETE FROM chat_message WHERE id = :id") suspend fun deleteById(id: Long)`。

#### 3.2 Repository
**文件**：[ChatRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/ChatRepository.kt)

新增 `suspend fun deleteMessage(characterId: String, id: Long)` 调 `dao.deleteById`。

#### 3.3 ViewModel 删除 / 重新生成
**文件**：[ChatViewModel.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/chat/ChatViewModel.kt)

- `fun deleteMessage(message: ChatMessage)`：从 `_messages` 按身份剔除；`id != 0L` 时 `launch { repository.deleteMessage(...) }`。
- `fun regenerateReply(message: ChatMessage)`：replaying 时 return；按身份定位 idx，校验为 `TYPE_RECEIVED`；`payload = buildPayload(subList(0, idx))`；从列表剔除该消息并按 id 删库；`launch { streamReplyWith(payload) }`（新回复流式追加到末尾）。

#### 3.4 适配器
**文件**：[ChatAdapter.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ChatAdapter.kt)

- 构造追加参数 `private val onMessageLongClick: (ChatMessage, Int) -> Unit = { _, _ -> }`。
- `SentMessageViewHolder.bind` / `ReceivedMessageViewHolder.bind` 内设 `itemView.setOnLongClickListener { onMessageLongClick(message, bindingAdapterPosition); true }`。
- `submit(newMessages)` 增加流式分支：列表等长且"除末项外身份全同"时只 `notifyItemChanged(lastIndex)`，避免每个增量都 `notifyDataSetChanged` 卡顿。原 append-only / fallback 分支保留。

#### 3.5 长按菜单
**文件**：[ChatActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ChatActivity.kt)

- `setupRecyclerView()` 构造 `ChatAdapter` 时传 `onMessageLongClick = { msg, _ -> showMessageOptions(msg) }`。
- `showMessageOptions(message)`：`BottomSheetDialog` 载入新布局；复制用 `ClipboardManager.setPrimaryClip` + toast；删除调 `viewModel.deleteMessage`；"重新生成"项仅在 `type == TYPE_RECEIVED` 时显示并接 `viewModel.regenerateReply`，否则 `View.GONE`。
- `observeViewModel()` 增加对 `viewModel.isReplying` 的观察，联动 `btnSend.isEnabled`；`etInput` 的 `TextWatcher.afterTextChanged` 也并入 `isReplying` 判断。

#### 3.6 新布局
**新文件**：`app/src/main/res/layout/dialog_message_options.xml`

结构镜像 [dialog_chat_settings.xml](file:///c:/Users/suwei/Desktop/eden/app/src/main/res/layout/dialog_chat_settings.xml)：纵向 LinearLayout，标题"消息操作"+ 三行（`item_copy` 复制 / `item_delete` 删除 / `item_regenerate` 重新生成），每行用 `?attr/selectableItemBackground`。图标复用 `ic_chat` / `ic_delete` / `ic_more`，删除行红色。

---

## 验证

无现成单测，按手动端到端验证：

1. **构建**：`./gradlew assembleDebug` 通过编译。
2. **后端**：`cd server; .venv\Scripts\Activate.ps1; uvicorn main:app --reload`，用 `curl -N -X POST http://127.0.0.1:8001/api/chat -H "Content-Type: application/json" -d "{\"messages\":[{\"role\":\"user\",\"content\":\"用三行话自我介绍，每行带换行\"}],\"stream\":true}"`，确认每行 `data:` 都带 JSON 字符串、换行不丢、末尾 `data: [DONE]`。
3. **固定回复**：
   - 输入"你好" → 三玖固定回复；输入"你好烦" → 走大模型（非固定台词）；
   - 输入"我喜欢你家历史书" → 走大模型；
   - 输入"早上好呀" → 命中"早上好"池。
4. **流式**：发任意消息，观察角色气泡逐字增长，首字延迟明显低于改前。
5. **长按菜单**：长按角色消息 → 复制（粘贴板含全文）/ 删除（列表与重启后均消失）/ 重新生成（旧回复删、新回复流式追加到末尾）；长按自己发的消息 → 不显示"重新生成"。
6. **失败重试**：断网后发消息 → 出现"回复失败，点击重试"气泡；点击 → 重新流式请求。
7. **记忆**：连发 40+ 条后触发摘要压缩（日志无异常，重启后角色仍记得早期事实）。
