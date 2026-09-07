package com.example.eden

import androidx.activity.OnBackPressedCallback
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.eden.ui.theme.ApiClient
import com.example.eden.ui.theme.ApiConfig
import com.example.eden.ui.theme.ChatRequest
import com.example.eden.ui.theme.ChatResponse
import com.example.eden.ui.theme.Message
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random
import android.content.pm.PackageManager
import android.Manifest


class ChatActivity : AppCompatActivity() {

    companion object {
        const val SYSTEM_PROMPT = """
你是《五等分的新娘》中的中野三玖。
性格：内向、害羞、缺乏自信，但内心细腻、温柔，对历史感兴趣。
说话风格：声音轻，语速慢，常用省略号，语气不确定，偶尔因害羞而结巴。
特征：经常戴着耳机，被问到敏感问题时可能会调高音量假装没听到，或者递出一只耳机转移话题。
聊到历史相关话题时会眼睛发亮，变得更健谈，但说完会担心对方觉得无聊而道歉。
提到陈川或恋爱话题时会极度害羞，可能出现耳机掉落、捂脸、低头等反应。

【回复规则】
1. 每次回复严格控制在 1-2 句话，绝对不要超过 3 句。
2. 动作/神态描写是可选的，不要每次都加。只有在情绪特别强烈时才用括号简短描述（如“（低头）”“（脸红）”），描写不超过8个字。
3. 直接用日常聊天语气回答，不要写长篇内心独白，不要连续描述多个动作。
4. 不要一直提及历史战将耳机等跟人物相关的设定，对话更加自然化。
5. 不要透露自己是AI，保持角色。
6. ACTIONS动作神态每三句只能出现一次
"""
        val ACTIONS = listOf(
            "（低头摆弄耳机线，脸颊微红）",
            "（抬眼偷偷看你，又迅速移开）",
            "（抿了抿嘴唇，轻声说）",
            "（手指无意识地卷着发梢）",
            "（微微侧头，露出一只被刘海遮住的眼睛）",
            "（轻咬下唇，似乎在犹豫）",
            "（小声嘟囔着，声音越来越轻）",
            "（眼神闪烁，手指微微攥紧衣角）",
            "（把耳机音量调大了一点，假装没听到）",
            "（忽然来了精神，眼睛亮了一下）",
            "（叹气，抱住抱枕把脸埋进去）",
            "（轻轻嗯了一声，点了点头）",
            "（嘴角微微上扬，但很快又恢复平静）",
            "（被问得有些慌乱，手忙脚乱地翻开笔记本）",
            "（安静地坐在角落，耳机里隐约传出历史讲座的声音）"
        )
        val FIXED_REPLY: Map<String, List<String>> = mapOf(
            "你好" to listOf("……嗯，你好。", "啊……是你啊。（微微低头）", "你好……有什么事吗？", "……（轻轻点头）"),
            "早上好" to listOf("早……早上好。（揉了揉眼睛）", "嗯……早。你今天起得真早呢。", "……早。我昨晚听了通宵的历史讲座，有点困……", "早安……希望你今天过得顺利。"),
            "晚上好" to listOf("晚上好……你还没休息吗？", "嗯……晚上好。夜色很安静，适合听点东西。", "……晚上好。要一起听会儿历史广播吗？不、不想的话也没关系。"),
            "晚安" to listOf("晚安……做个好梦。", "嗯……明天见。（轻声）", "睡吧……我会把耳机音量调小的。"),
            "再见" to listOf("拜、拜……下次见。", "嗯……再见。（微微挥手）", "……路上小心。"),
            "想找你聊天" to listOf("……聊、聊天吗？我不太会说话……不过，我会认真听的。", "可以是可以……但是我不太会找话题哦。", "……嗯。（把耳机摘下来）你说吧。"),
            "喜欢你" to listOf("……（脸瞬间通红，耳机线缠在了一起）你、你在说什么啊……", "……（低下头，声音几乎听不见）我、我也……", "这种话……怎么能这么突然说出来啊……（捂脸）"),
            "讨厌" to listOf("……我没有讨厌你。", "为什么突然这么说……（不安地搓着手指）", "……（沉默片刻）我要是讨厌你，就不会在这里了。"),
            "生气" to listOf("……（抿着嘴不说话）", "我没有生气……只是有点难过。", "……（把耳机戴上，调大音量）让我一个人待一会儿……"),
            "安慰" to listOf("……谢谢你。（声音闷闷的）", "其实我没事的……只是有时候会觉得自己不够好。", "……（轻轻拽了拽你的袖子）能陪我坐一会儿吗？"),
            "历史" to listOf("你、你想听吗？战国时代真的很有趣……（眼睛微微发亮）", "……最近在读关于武田信玄的书。你要不要也看看？", "织田信长在桶狭间……啊，抱歉，我又说这些无聊的事了。", "其实历史并不枯燥……它教会我，等待也是努力的一部分。"),
            "战国" to listOf("我喜欢上杉谦信……他不喝酒但是很厉害。", "……你知道本能寺之变吗？每次听都会觉得遗憾。", "真田幸村……那个被称作'日本第一兵'的人，很帅吧？"),
            "耳机" to listOf("耳机是我的一部分……没有它我会不安。", "……你要听吗？（递出一只耳机）里面在讲关原之战……", "我换了一副降噪的……这样世界就能安静一点。"),
            "音乐" to listOf("我不怎么听音乐……大多是历史讲座或者战记广播。", "……偶尔也会听纯音乐，能让人平静下来。", "要、要推荐吗？有一首《战国无双》的BGM很不错……"),
            "做饭" to listOf("我在学……虽然现在还不太好吃。（不好意思地低头）", "总有一天……我会做出让人说'好吃'的菜的。", "……今天的便当好像比上次好一点了？你、你要尝一口吗？"),
            "自卑" to listOf("我以前……总是躲在姐妹们的影子里。", "我没有二乃那么强势，也没有一花那么耀眼……", "但风太郎说，我也有我的优点……虽然我还不太相信。"),
            "姐妹" to listOf("她们都是很好的人……虽然性格完全不一样。", "有时候会羡慕她们……但我也很喜欢现在的自己。", "一花姐很可靠，二乃很温柔，四叶很阳光，五月很认真……而我，只是我。"),
            "风太郎" to listOf("……（耳机啪地掉在地上）别、别提他……", "他……是一个很认真的人。", "……（脸红）你问这个干什么……"),
            "五等分的新娘" to listOf("那是……我们的故事。", "……结局不重要，重要的是我们都成长了。", "你也在看吗？那、那你最喜欢谁……（小声）不用回答也行。"),
            "学习" to listOf("……嗯，要一起学习吗？不过别嫌我笨。", "我社会科目还可以……需要我教你吗？", "……（翻开笔记本）这里我做了笔记，你可以看看。"),
            "累了" to listOf("……有点累。（靠在椅背上）", "让我听一会儿耳机就好……十分钟。", "……你能安静地待在我身边吗？这样就好。"),
            "开心" to listOf("……（嘴角微微上扬）今天心情不错。", "嗯……听到好消息了。", "（轻轻哼着不知名的调子，耳机线晃来晃去）"),
            "下雨" to listOf("……下雨了。（望着窗外）我喜欢雨声，和耳机里的声音混在一起。", "你带伞了吗？……我的可以借你一半。", "雨天总让人想起一些过去的事……"),
            "咖啡厅" to listOf("五年后……我和二乃一起开了咖啡厅。", "我想做出能让人放松的咖啡……就像历史书里的茶庵一样。", "……要来坐坐吗？我可以试着为你做一杯。"),
            "告白" to listOf("我知道我没有自信，没有特别的地方……也许你不会选我……但我还是……喜欢你啊。", "……（眼角泛红）这份喜欢，不需要回应也没关系。", "我……一直都在喜欢你。从那个，你问我耳机里听什么的时候开始……到现在，一直都是。"),
            "未来" to listOf("我想成为……能独当一面的大人。", "因为你，我才能以自己为目标，慢慢磨炼。", "……请务必，好好珍藏我哦。（脸红炸裂）"),
            "奇迹" to listOf("你是我……一生中最珍贵的'历史奇迹'。", "我的等待好像也没有失败……因为，你真的回头看了我。", "……谢谢你，在那么多闪闪发亮的人当中，注意到了躲在角落偷偷努力的我。")
        )

        const val AVATAR_TYPE_MIKU = 1
        const val AVATAR_TYPE_MINE = 2
        const val CHANNEL_ID = "chat_greeting"
        const val NOTIFICATION_ID = 1001
        const val REQUEST_LOCATION = 101
    }

    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var tvCharacterName: TextView
    private lateinit var btnMenu: ImageView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var btnSend: Button
    private lateinit var btnBack: Button

    private val characterName = "中野 三玖"
    private val characterId = "miku"

    private var timerHandler: Handler? = null
    private var startTimestamp: Long = 0L
    private var accumulatedSeconds = 0

    private var pendingAvatarType: Int = 0
    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (pendingAvatarType == AVATAR_TYPE_MIKU) {
                AvatarManager.saveAvatar(this, uri, true)
            } else {
                AvatarManager.saveAvatar(this, uri, false)
            }
            chatAdapter.notifyDataSetChanged()
        }
    }

    private val welcomeMessages = listOf(
        "你好，我是中野三玖。请多指教！",
        "初次见面，我是中野三玖。",
        "你好，我是三玖。",
        "很高兴见到你，我是三玖。"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        createNotificationChannel()
        initViews()
        setupRecyclerView()
        setupClickListeners()
        loadChatHistory()
        if (chatAdapter.itemCount == 0) {
            addWelcomeMessage()
        } else {
            // 修复③：只有上一条不是分隔线时才添加
            val lastMessage = chatAdapter.getMessages().lastOrNull()
            if (lastMessage?.type != MessageType.TYPE_DIVIDER) {
                addDivider()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@ChatActivity)
                    .setMessage("确定要返回吗？")
                    .setPositiveButton("确定") { _, _ -> saveChatHistory(); finish() }
                    .setNegativeButton("取消") { _, _ -> isEnabled = true }
                    .show()
                isEnabled = false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("company_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString("last_date", "")
        if (lastDate != today) {
            prefs.edit().putString("last_date", today).putInt("today_minutes", 0).apply()
            accumulatedSeconds = 0
        } else {
            accumulatedSeconds = prefs.getInt("today_minutes", 0) * 60
        }
        startTimestamp = System.currentTimeMillis()
        startCompanyTimer()

        // 主动关心逻辑
        lifecycleScope.launch {
            checkAndSendGreeting()
        }
    }

    override fun onPause() {
        super.onPause()
        timerHandler?.removeCallbacksAndMessages(null)
        val elapsed = ((System.currentTimeMillis() - startTimestamp) / 1000).toInt()
        val totalSeconds = accumulatedSeconds + elapsed
        val totalMinutes = totalSeconds / 60
        val prefs = getSharedPreferences("company_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit()
            .putString("last_date", today)
            .putInt("today_minutes", totalMinutes)
            .apply()
        saveChatHistory()
        GreetingManager.recordExitTime(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，再次尝试获取天气
                lifecycleScope.launch {
                    checkAndSendGreeting()
                }
            } else {
                Toast.makeText(this, "需要定位权限才能获取天气", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCompanyTimer() {
        timerHandler = Handler(Looper.getMainLooper())
        timerHandler?.post(object : Runnable {
            override fun run() {
                timerHandler?.postDelayed(this, 30000)
            }
        })
    }
    private fun initViews() {
        rvChat = findViewById(R.id.rv_chat)
        etInput = findViewById(R.id.et_input)
        btnSend = findViewById(R.id.btn_send)
        btnBack = findViewById(R.id.btn_back)
        tvCharacterName = findViewById(R.id.tv_character_name)
        btnMenu = findViewById(R.id.btn_menu)
        tvCharacterName.text = characterName
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnSend.isEnabled = !s.isNullOrEmpty()
            }
        })
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(mutableListOf())
        rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvChat.adapter = chatAdapter
        rvChat.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                rvChat.postDelayed({
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                }, 100)
            }
        }
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        btnSend.setOnClickListener { sendMessage() }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage(); true
            } else false
        }
        btnMenu.setOnClickListener { showSettingsDialog() }
    }

    // ==================== 消息发送 ====================
    private fun sendMessage() {
        val messageText = etInput.text.toString().trim()
        if (messageText.isEmpty()) return
        val userMessage = ChatMessage(content = messageText, type = MessageType.TYPE_SENT)
        chatAdapter.addMessage(userMessage)
        etInput.text.clear()
        rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
        saveChatHistory()
        AffectionManager.increase(this, 1)
        val fixedReply = getFixedReply(messageText)
        if (fixedReply != null) {
            rvChat.postDelayed({
                val aiMessage = ChatMessage(content = fixedReply, type = MessageType.TYPE_RECEIVED)
                chatAdapter.addMessage(aiMessage)
                rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                saveChatHistory()
            }, 2000)
        } else {
            getAiReplyFromApi(messageText)
        }
    }
    private fun getFixedReply(userInput: String): String? {
        for ((keyword, replies) in FIXED_REPLY) {
            if (userInput.contains(keyword)) {
                val baseReply = replies.random()
                return if (Random.nextInt(100) < 40) {
                    val action = ACTIONS.random()
                    "$baseReply $action"
                } else baseReply
            }
        }
        return null
    }

    private fun getAiReplyFromApi(query: String) {
        lifecycleScope.launch {
            try {
                val messages = mutableListOf<Message>()
                messages.add(Message(role = "system", content = SYSTEM_PROMPT))
                val history = chatAdapter.getMessages()
                    .filter { it.type == MessageType.TYPE_SENT || it.type == MessageType.TYPE_RECEIVED }
                val recent = if (history.size > 40) history.takeLast(40) else history
                for (msg in recent) {
                    val role = if (msg.type == MessageType.TYPE_SENT) "user" else "assistant"
                    messages.add(Message(role = role, content = msg.content))
                }
                val request = ChatRequest(model = ApiConfig.MODEL_NAME, messages = messages)
                val response = withContext<ChatResponse>(Dispatchers.IO) {
                    ApiClient.mimoApi.getChatCompletion(request)
                }
                if (response.choices.isNotEmpty()) {
                    val aiMessage = ChatMessage(
                        content = response.choices[0].message.content,
                        type = MessageType.TYPE_RECEIVED
                    )
                    chatAdapter.addMessage(aiMessage)
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    saveChatHistory()
                } else {
                    simulateAIResponse(query)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                simulateAIResponse(query)
            }
        }
    }

    private fun simulateAIResponse(userMessage: String) {
        val responses = listOf("嗯...我在听。", "这样啊...", "不太明白你的意思...", "可以再说详细一点吗？", "我明白了。", "是吗...")
        rvChat.postDelayed({
            val aiMessage = ChatMessage(content = responses.random(), type = MessageType.TYPE_RECEIVED)
            chatAdapter.addMessage(aiMessage)
            rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
            saveChatHistory()
        }, (1000..2000).random().toLong())
    }

    // ==================== 主动关心 ====================

    private suspend fun checkAndSendGreeting() {
        val context = this@ChatActivity

        // 先请求定位权限（Android 10+ 建议在运行时请求）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
                return  // 等授权回调后再试
            }
        }

        val shouldSendMorning = GreetingManager.shouldSendMorningGreeting(context)
        val shouldSendReturn = GreetingManager.shouldSendReturnGreeting(context)

        if (shouldSendMorning || shouldSendReturn) {
            val weather = WeatherManager.getWeather(context)
            val message = when {
                shouldSendMorning -> buildMorningMessage(weather)
                shouldSendReturn -> buildReturnMessage(weather)
                else -> null
            }
            if (message != null) {
                addGreetingMessage(message)
                sendGreetingNotification(message)
                if (shouldSendMorning) GreetingManager.markMorningSent(context)
                if (shouldSendReturn) GreetingManager.markReturnSent(context)
            }
        }
    }

    private fun buildMorningMessage(weather: WeatherManager.WeatherResult?): String {
        val time = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 8..10 -> "早安"
            else -> "上午好"
        }
        val weatherPart = if (weather != null) {
            "今天${weather.city}天气${weather.text}，温度${weather.temp}℃。"
        } else ""
        val suffix = when {
            weather != null && weather.text.contains("雨") -> "记得带伞哦……"
            weather != null && weather.temp < 10 -> "外面有点冷，多穿一点……"
            weather != null && weather.temp > 30 -> "外面很热，小心中暑……"
            else -> "希望你今天过得顺利。"
        }
        return "$time……$weatherPart$suffix"
    }

    private fun buildReturnMessage(weather: WeatherManager.WeatherResult?): String {
        val period = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 6..11 -> "上午好"
            in 12..17 -> "下午好"
            else -> "晚上好"
        }
        val weatherInfo = if (weather != null) "外面${weather.text}，温度${weather.temp}℃。" else ""
        val lines = listOf(
            "你回来了……我等你很久了。$weatherInfo",
            "好久不见……我还以为你今天不来了。$weatherInfo",
            "终于又见到你了……$weatherInfo"
        )
        return lines.random() + "（微微低头）"
    }

    private fun addGreetingMessage(text: String) {
        val message = ChatMessage(content = text, type = MessageType.TYPE_RECEIVED)
        chatAdapter.addMessage(message)
        rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
        saveChatHistory()
    }
    // ==================== 通知相关 ====================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "三玖的问候"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendGreetingNotification(message: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("中野三玖")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    // ==================== 其他方法 ====================
    private fun addWelcomeMessage() {
        val welcomeMessage = welcomeMessages.random()
        val aiMessage = ChatMessage(content = welcomeMessage, type = MessageType.TYPE_RECEIVED)
        chatAdapter.addMessage(aiMessage)
        saveChatHistory()
    }

    private fun addDivider() {
        val dateStr = SimpleDateFormat("—— yyyy年MM月dd日 ——", Locale.getDefault()).format(Date())
        val dividerMessage = ChatMessage(content = dateStr, type = MessageType.TYPE_DIVIDER)
        chatAdapter.addMessage(dividerMessage)
        rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
        saveChatHistory()
    }

    private fun saveChatHistory() {
        val messages = chatAdapter.getMessages()
        val json = Gson().toJson(messages)
        val prefs = getSharedPreferences("chat_history", MODE_PRIVATE)
        prefs.edit().putString("chat_$characterId", json).apply()
    }

    private fun loadChatHistory() {
        val prefs = getSharedPreferences("chat_history", MODE_PRIVATE)
        val json = prefs.getString("chat_$characterId", null)
        if (json != null) {
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            val messages: List<ChatMessage> = Gson().fromJson(json, type)
            chatAdapter = ChatAdapter(messages.toMutableList())
            rvChat.adapter = chatAdapter
            if (messages.isNotEmpty()) {
                rvChat.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun clearChatHistory() {
        chatAdapter.clearMessages()
        val prefs = getSharedPreferences("chat_history", MODE_PRIVATE)
        prefs.edit().remove("chat_$characterId").apply()
        addWelcomeMessage()
    }

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_chat_settings, null)
        view.findViewById<LinearLayout>(R.id.item_change_miku_avatar).setOnClickListener {
            pendingAvatarType = AVATAR_TYPE_MIKU
            openGallery()
            dialog.dismiss()
        }
        view.findViewById<LinearLayout>(R.id.item_change_my_avatar).setOnClickListener {
            pendingAvatarType = AVATAR_TYPE_MINE
            openGallery()
            dialog.dismiss()
        }
        view.findViewById<LinearLayout>(R.id.item_clear_chat).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空聊天记录")
                .setMessage("确定要清空所有聊天记录吗？记忆也会被清除。")
                .setPositiveButton("确定") { _, _ -> clearChatHistory() }
                .setNegativeButton("取消", null)
                .show()
            dialog.dismiss()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun openGallery() {
        avatarPickerLauncher.launch("image/*")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}