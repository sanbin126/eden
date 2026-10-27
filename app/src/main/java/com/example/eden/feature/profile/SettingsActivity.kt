package com.example.eden.feature.profile


import com.example.eden.BuildConfig
import com.example.eden.R
import com.example.eden.core.session.SessionManager
import com.example.eden.core.ui.BaseActivity
import com.example.eden.core.ui.CircleImageView
import com.example.eden.core.ui.EdenDatePickerDialog
import com.example.eden.core.ui.ThemedDialog
import com.example.eden.data.repository.ProfileRepository
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.auth.LoginActivity
import com.example.eden.feature.home.GrowthGuideActivity
import com.example.eden.feature.memory.MemoryActivity
import com.example.eden.ui.theme.SkinManager
import com.example.eden.ui.theme.edenColor
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.eden.core.network.LlmModeManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置界面。
 *
 * 包含：账号信息、修改密码、通用开关（消息通知 / 主动问候）、
 * 清除缓存、关于、退出登录。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var tvNickname: TextView
    private lateinit var tvSignature: TextView
    private lateinit var ivProfileAvatar: CircleImageView
    private lateinit var tvNicknameValue: TextView
    private lateinit var tvIntimateValue: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvCacheSize: TextView
    private lateinit var tvVersion: TextView
    private lateinit var switchNotification: SwitchCompat
    private lateinit var switchGreeting: SwitchCompat
    private lateinit var tvAnniversaryValue: TextView
    private lateinit var tvBirthdayValue: TextView
    private lateinit var tvLlmMode: TextView
    private lateinit var tvThemeValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadSwitches()
        loadLlmMode()
        setupListeners()
    }

    /** 从「编辑资料」返回后需要刷新头像 / 昵称 / 签名，故放在 onResume。 */
    override fun onResume() {
        super.onResume()
        loadAccountInfo()
    }

    private fun initViews() {
        tvNickname = findViewById(R.id.tv_nickname)
        tvSignature = findViewById(R.id.tv_signature)
        ivProfileAvatar = findViewById(R.id.iv_profile_avatar)
        tvNicknameValue = findViewById(R.id.tv_nickname_value)
        tvIntimateValue = findViewById(R.id.tv_intimate_value)
        tvEmail = findViewById(R.id.tv_email)
        tvCacheSize = findViewById(R.id.tv_cache_size)
        tvVersion = findViewById(R.id.tv_version)
        switchNotification = findViewById(R.id.switch_notification)
        switchGreeting = findViewById(R.id.switch_greeting)
        tvAnniversaryValue = findViewById(R.id.tv_anniversary_value)
        tvBirthdayValue = findViewById(R.id.tv_birthday_value)
        tvLlmMode = findViewById(R.id.tv_llm_mode)
        tvThemeValue = findViewById(R.id.tv_theme_value)
    }

    private fun loadAccountInfo() {
        tvNickname.text = ProfileRepository.getDisplayNickname(this)
        val email = SessionManager.getEmail(this)
        tvEmail.text = if (email.isEmpty()) getString(R.string.common_dash) else email
        val signature = ProfileRepository.getSignature(this)
        // 未填写签名时整行隐藏，不显示占位文案
        tvSignature.text = signature
        tvSignature.visibility = if (signature.isEmpty()) View.GONE else View.VISIBLE
        refreshAvatar()
        refreshNicknameValue()
        refreshIntimateValue()
        refreshAnniversaryValues()
    }

    /** 头像按 256px 采样解码后圆形显示，无头像时回落到默认图标。 */
    private fun refreshAvatar() {
        val bitmap = ProfileRepository.loadAvatarBitmap(this, 256)
        if (bitmap != null) ivProfileAvatar.setImageBitmap(bitmap)
        else ivProfileAvatar.setImageResource(R.drawable.ic_user_avatar)
    }

    private fun refreshAnniversaryValues() {
        val startDate = DailyLoopManager.getStartDate(this)
        tvAnniversaryValue.text = startDate ?: getString(R.string.common_not_set)
        val birthday = DailyLoopManager.getUserBirthday(this)
        tvBirthdayValue.text = birthday ?: getString(R.string.common_not_set)
    }

    private fun refreshNicknameValue() {
        val formal = ProfileRepository.getFormal(this)
        tvNicknameValue.text = if (formal.isEmpty()) getString(R.string.common_not_set) else formal
    }

    private fun refreshIntimateValue() {
        val call = ProfileRepository.getIntimateRaw(this)
        tvIntimateValue.text = if (call.isEmpty()) getString(R.string.common_not_set) else call
    }

    private fun refreshThemeValue() {
        tvThemeValue.text = SkinManager.displayName(this, SkinManager.currentSkin(this))
    }

    private fun loadSwitches() {
        switchNotification.isChecked = ProfileRepository.isNotificationEnabled(this)
        switchGreeting.isChecked = ProfileRepository.isGreetingEnabled(this)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        refreshThemeValue()
        refreshCacheSize()
    }

    /**
     * 只读展示当前 LLM 运行模式（后端 / 直连）。
     *
     * 模式由 [LlmModeManager] 自动判定：默认后端，后端探活不通时回退直连，
     * 用户不可手动切换，这里仅作展示。
     */
    private fun loadLlmMode() {
        tvLlmMode.text = getString(R.string.settings_detecting)
        lifecycleScope.launch {
            tvLlmMode.text = LlmModeManager.currentModeLabel()
        }
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<View>(R.id.item_profile).setOnClickListener {
            startActivity(Intent(this, ProfileEditActivity::class.java))
        }

        findViewById<View>(R.id.item_nickname).setOnClickListener { showCustomNicknameDialog() }
        findViewById<View>(R.id.item_intimate).setOnClickListener { showNicknameDialog() }
        findViewById<View>(R.id.item_theme).setOnClickListener { showThemeDialog() }

        findViewById<View>(R.id.item_anniversary).setOnClickListener { showAnniversaryDatePicker() }
        findViewById<View>(R.id.item_birthday).setOnClickListener { showBirthdayDatePicker() }

        findViewById<View>(R.id.item_change_password).setOnClickListener {
            startActivity(Intent(this, ChangePasswordActivity::class.java))
        }

        switchNotification.setOnCheckedChangeListener { _, checked ->
            ProfileRepository.setNotificationEnabled(this, checked)
        }

        switchGreeting.setOnCheckedChangeListener { _, checked ->
            ProfileRepository.setGreetingEnabled(this, checked)
        }

        findViewById<View>(R.id.item_clear_cache).setOnClickListener { confirmClearCache() }
        findViewById<View>(R.id.item_about).setOnClickListener { showAboutDialog() }

        findViewById<View>(R.id.item_growth).setOnClickListener {
            startActivity(Intent(this, GrowthGuideActivity::class.java))
        }

        findViewById<View>(R.id.item_memory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        findViewById<Button>(R.id.btn_logout).setOnClickListener { confirmLogout() }
    }

    // ==================== 称呼设置 ====================
    /** 亲密称呼：从预设（+ 剧情解锁）里单选，粉色底部面板。 */
    private fun showNicknameDialog() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_intimate_picker, null)
        sheet.setContentView(view)
        // 去掉 BottomSheet 默认白底，让自定义粉色圆角面板生效
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val container = view.findViewById<LinearLayout>(R.id.llIntimateOptions)
        val options = ProfileRepository.allOptions(this)
        val current = ProfileRepository.getIntimateRaw(this)
        options.forEachIndexed { index, option ->
            val row = makeOptionRow(option, option == current)
            if (index > 0) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            }
            row.setOnClickListener {
                ProfileRepository.select(this, option)
                refreshIntimateValue()
                sheet.dismiss()
                Toast.makeText(this, "已设置：$option", Toast.LENGTH_SHORT).show()
            }
            container.addView(row)
        }

        view.findViewById<Button>(R.id.btnIntimateCancel).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    /** 亲密称呼选项行：选中态粉色填充白字，未选中浅粉底深字。 */
    private fun makeOptionRow(text: String, selected: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_nickname_option)
            isSelected = selected
            setTextColor(
                if (selected) edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary)
                else edenColor(R.attr.edenColorTextPrimary, R.color.eden_text_primary)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    // ==================== 主题 ====================
    /** 主题选择：粉色（默认）/ 蓝色绣球，选中后持久化并重建当前界面。 */
    private fun showThemeDialog() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_theme_picker, null)
        sheet.setContentView(view)
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val container = view.findViewById<LinearLayout>(R.id.llThemeOptions)
        val current = SkinManager.currentSkin(this)
        SkinManager.allSkins.forEachIndexed { index, skin ->
            val row = makeOptionRow(SkinManager.displayName(this, skin), skin == current)
            if (index > 0) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            }
            row.setOnClickListener {
                sheet.dismiss()
                if (skin == current) return@setOnClickListener
                SkinManager.setSkin(this, skin)
                recreate()
            }
            container.addView(row)
        }

        view.findViewById<Button>(R.id.btnThemeCancel).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    /** 「我的名字」自定义面板：全名（郑重场合）+ 小名（日常）双槽，全名在上。 */
    private fun showCustomNicknameDialog() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_my_name, null)
        sheet.setContentView(view)
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val etFormal = view.findViewById<EditText>(R.id.etMyNameFormal)
        val etCall = view.findViewById<EditText>(R.id.etMyNameCall)
        etFormal.setText(ProfileRepository.getFormal(this))
        etCall.setText(ProfileRepository.getCallRaw(this))

        view.findViewById<Button>(R.id.btnMyNameCancel).setOnClickListener { sheet.dismiss() }
        view.findViewById<Button>(R.id.btnMyNameSave).setOnClickListener {
            val call = etCall.text?.toString().orEmpty().trim()
            val formal = etFormal.text?.toString().orEmpty().trim()
            // 只填了全名时，小名沿用全名，避免日常没有称呼可用
            ProfileRepository.setCustom(this, call.ifEmpty { formal }, formal)
            refreshNicknameValue()
            refreshIntimateValue()
            sheet.dismiss()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        sheet.show()
    }

    // ==================== 纪念日 / 生日 ====================
    private fun showAnniversaryDatePicker() {
        showDatePicker(
            initialDate = DailyLoopManager.getStartDate(this),
            title = getString(R.string.anniversary_start_label),
            subtitle = getString(R.string.date_picker_anniversary_sub)
        ) { picked ->
            DailyLoopManager.setStartDate(this, picked)
            refreshAnniversaryValues()
            Toast.makeText(this, "已保存：$picked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBirthdayDatePicker() {
        showDatePicker(
            initialDate = DailyLoopManager.getUserBirthday(this),
            title = getString(R.string.anniversary_birthday_label),
            subtitle = getString(R.string.date_picker_birthday_sub)
        ) { picked ->
            DailyLoopManager.setUserBirthday(this, picked)
            refreshAnniversaryValues()
            Toast.makeText(this, "已保存：$picked", Toast.LENGTH_SHORT).show()
        }
    }

    /** 主题化日历弹窗，样式对齐主界面的「我的日签卡」。 */
    private fun showDatePicker(
        initialDate: String?,
        title: String,
        subtitle: String,
        onPicked: (String) -> Unit
    ) {
        EdenDatePickerDialog(this, title, subtitle, initialDate, onPicked).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ==================== 清除缓存 ====================
    private fun refreshCacheSize() {
        tvCacheSize.text = formatSize(cacheSize())
    }

    private fun cacheSize(): Long {
        var total = 0L
        cacheDir?.let { total += dirSize(it) }
        externalCacheDir?.let { total += dirSize(it) }
        return total
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) dirSize(file) else file.length()
        }
        return size
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    private fun confirmClearCache() {
        ThemedDialog.show(
            context = this,
            title = getString(R.string.settings_clear_cache),
            message = "确定要清除本地缓存吗？",
            positiveText = "清除",
            iconRes = R.drawable.ic_delete,
            iconTint = edenColor(R.attr.edenColorPrimaryPressed, R.color.eden_primary_pressed)
        ) {
            clearCaches()
            refreshCacheSize()
            Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearCaches() {
        cacheDir?.let { deleteChildren(it) }
        externalCacheDir?.let { deleteChildren(it) }
    }

    private fun deleteChildren(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                deleteChildren(file)
                file.delete()
            } else {
                file.delete()
            }
        }
    }

    // ==================== 关于 ====================
    private fun showAboutDialog() {
        ThemedDialog.show(
            context = this,
            title = getString(R.string.settings_about),
            message = "Eden 陪伴助手\n版本 v${BuildConfig.VERSION_NAME}\n\n一个懂你的专属陪伴。",
            positiveText = getString(R.string.common_confirm),
            negativeText = null,
            iconRes = R.drawable.ic_info,
            iconTint = edenColor(R.attr.edenColorAccent, R.color.eden_accent)
        )
    }

    // ==================== 退出登录 ====================
    private fun confirmLogout() {
        ThemedDialog.show(
            context = this,
            title = getString(R.string.settings_logout),
            message = "确定要退出当前账号吗？",
            positiveText = "退出",
            iconRes = R.drawable.ic_lock,
            iconTint = edenColor(R.attr.edenColorPrimaryPressed, R.color.eden_primary_pressed)
        ) {
            // 清除登录态后退出；本地数据按账号分区保存，重新登录该账号即可恢复
            SessionManager.logoutAndResetSession(this) {
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
