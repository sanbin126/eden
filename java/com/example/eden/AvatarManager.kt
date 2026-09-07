package com.example.eden

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object AvatarManager {
    private const val AVATAR_DIR = "avatars"
    private const val MY_AVATAR_FILE = "my_avatar.jpg"
    private const val MIKU_AVATAR_FILE = "miku_avatar.jpg"

    private fun getAvatarDir(context: Context): File {
        val dir = File(context.filesDir, AVATAR_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // 保存头像（自动裁剪为圆形）
    fun saveAvatar(context: Context, uri: Uri, isMiku: Boolean) {
        val fileName = if (isMiku) MIKU_AVATAR_FILE else MY_AVATAR_FILE
        val file = File(getAvatarDir(context), fileName)

        // 从 Uri 解码 Bitmap
        val originalBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return

        // 裁剪为正方形并转为圆形
        val size = minOf(originalBitmap.width, originalBitmap.height)
        val squareBitmap = Bitmap.createScaledBitmap(originalBitmap, size, size, true)
        val circleBitmap = createCircleBitmap(squareBitmap)

        // 保存到文件
        FileOutputStream(file).use { output ->
            circleBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }

        // 回收 Bitmap
        originalBitmap.recycle()
        squareBitmap.recycle()
        circleBitmap.recycle()

        // 保存路径到 SP
        context.getSharedPreferences("avatar_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(if (isMiku) "miku_avatar_path" else "my_avatar_path", file.absolutePath)
            .apply()
    }

    // 生成圆形 Bitmap
    private fun createCircleBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val radius = minOf(width, height) / 2f

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 绘制圆形遮罩
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return output
    }

    // 获取头像路径
    fun getAvatarPath(context: Context, isMiku: Boolean): String? {
        val key = if (isMiku) "miku_avatar_path" else "my_avatar_path"
        return context.getSharedPreferences("avatar_prefs", Context.MODE_PRIVATE)
            .getString(key, null)
    }

    // 加载头像 Bitmap
    fun loadAvatar(context: Context, isMiku: Boolean): Bitmap? {
        val path = getAvatarPath(context, isMiku) ?: return null
        return BitmapFactory.decodeFile(path)
    }
}