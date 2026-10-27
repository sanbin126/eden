package com.example.eden.feature.profile


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import com.example.eden.data.repository.ProfileRepository
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * 编辑个人资料：头像 / 昵称 / 个性签名。
 *
 * 头像流程：相册选图 → [CropActivity] 圆形裁剪 → 预览。
 * 所有修改都在点击「保存」时统一落库，中途退出不会产生副作用。
 */
class ProfileEditActivity : BaseActivity() {

    private lateinit var ivAvatar: ImageView
    private lateinit var etNickname: EditText
    private lateinit var etSignature: EditText

    /** 已裁剪但尚未保存的头像文件。 */
    private var pendingAvatar: File? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) startCrop(uri.toString())
    }

    private val cropImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = result.data?.getStringExtra(CropActivity.EXTRA_RESULT_PATH)
        if (result.resultCode == RESULT_OK && !path.isNullOrEmpty()) {
            val file = File(path)
            if (file.exists()) {
                pendingAvatar = file
                showAvatar(file)
                toast(getString(R.string.profile_avatar_updated))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_edit)

        ivAvatar = findViewById(R.id.iv_avatar)
        etNickname = findViewById(R.id.et_nickname)
        etSignature = findViewById(R.id.et_signature)

        etNickname.setText(ProfileRepository.getNicknameRaw(this))
        etSignature.setText(ProfileRepository.getSignature(this))
        showAvatar(ProfileRepository.getAvatarFile(this))

        findViewById<View>(R.id.item_avatar).setOnClickListener { pickAvatar() }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
    }

    // ==================== 头像 ====================

    private fun pickAvatar() {
        pickImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun startCrop(uriString: String) {
        cropImage.launch(
            Intent(this, CropActivity::class.java)
                .putExtra(CropActivity.EXTRA_SOURCE_URI, uriString)
        )
    }

    private fun showAvatar(file: File?) {
        val bitmap = file?.let { runCatching { decodeSampled(it) }.getOrNull() }
        if (bitmap != null) {
            ivAvatar.setImageBitmap(bitmap)
        } else {
            ivAvatar.setImageResource(R.drawable.ic_user_avatar)
        }
    }

    /** 头像预览最多 512px，避免把原图整张读进内存。 */
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= 512) sample *= 2

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    // ==================== 保存 ====================

    private fun save() {
        val nickname = etNickname.text?.toString().orEmpty().trim()
        if (nickname.isEmpty()) {
            toast(getString(R.string.profile_nickname_required))
            return
        }

        ProfileRepository.setNickname(this, nickname)
        ProfileRepository.setSignature(this, etSignature.text?.toString().orEmpty())
        pendingAvatar?.let { ProfileRepository.saveAvatar(this, it) }

        toast(getString(R.string.profile_saved))
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
