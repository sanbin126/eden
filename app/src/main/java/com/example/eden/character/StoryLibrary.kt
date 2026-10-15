package com.example.eden.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * 剧本与立绘的资源读取入口。
 *
 * 目录约定（线路隔离见 [StoryScript] 注释）：
 * - 剧本：`assets/story/<lineId>/chapter_01.json`
 * - 立绘：`assets/portrait/<castId>/<castId>_<face>.png`（face 如 `school_normal`），
 *   差分缺失时回退到 `<castId>_school_normal.png`
 *
 * 所有读取都做了容错：文件缺失 / JSON 破损时返回 null，不抛异常，
 * 避免一条坏剧本把整个剧情板块打崩。
 */
object StoryLibrary {

    private const val STORY_ROOT = "story"
    private const val PORTRAIT_ROOT = "portrait"

    /** 立绘差分的兜底文件名后缀。 */
    const val FALLBACK_FACE = "school_normal"

    fun chapterFileName(index: Int): String = "chapter_%02d.json".format(index)

    /** 扫描线路目录下已存在的章节序号（升序）。 */
    fun availableChapterIndices(context: Context, lineId: String): List<Int> {
        val names = runCatching { context.assets.list("$STORY_ROOT/$lineId") }.getOrNull() ?: return emptyList()
        return names.mapNotNull { name ->
            Regex("""chapter_(\d+)\.json""").find(name)?.groupValues?.get(1)?.toIntOrNull()
        }.sorted()
    }

    fun loadChapter(context: Context, lineId: String, index: Int): StoryChapter? {
        val path = "$STORY_ROOT/$lineId/${chapterFileName(index)}"
        val raw = runCatching {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        return runCatching { parseChapter(raw) }.getOrNull()
    }

    /** 线路下的全部章节（按序号升序）。 */
    fun loadLine(context: Context, lineId: String): List<StoryChapter> =
        availableChapterIndices(context, lineId).mapNotNull { loadChapter(context, lineId, it) }

    // ==================== JSON 解析 ====================

    private fun parseChapter(raw: String): StoryChapter {
        val root = JSONObject(raw)

        val casts = mutableMapOf<String, StoryCast>()
        root.optJSONObject("casts")?.let { obj ->
            for (key in obj.keys()) {
                val c = obj.getJSONObject(key)
                casts[key] = StoryCast(
                    id = key,
                    name = c.optString("name", key),
                    defaultSlot = c.optString("slot", "center")
                )
            }
        }

        val backgrounds = mutableMapOf<String, StoryBackground>()
        root.optJSONObject("backgrounds")?.let { obj ->
            for (key in obj.keys()) {
                val b = obj.getJSONObject(key)
                backgrounds[key] = StoryBackground(
                    id = key,
                    label = b.optString("label", ""),
                    topColor = b.optString("top", "#FFEEF4"),
                    bottomColor = b.optString("bottom", "#CBB8DE")
                )
            }
        }

        val scenesJson = root.optJSONArray("scenes") ?: JSONArray()
        val scenes = (0 until scenesJson.length()).map { parseScene(scenesJson.getJSONObject(it)) }

        val unlock = root.optJSONObject("unlock")
        val reward = root.optJSONObject("reward")

        return StoryChapter(
            id = root.optString("id"),
            index = root.optInt("index", 1),
            chapterLabel = root.optString("chapterLabel", "第 ${root.optInt("index", 1)} 章"),
            title = root.optString("title"),
            synopsis = root.optString("synopsis"),
            prevChapter = unlock?.optInt("prevChapter", 0) ?: 0,
            requiredAffection = unlock?.optInt("affection", 0) ?: 0,
            rewardAffection = reward?.optInt("affection", 0) ?: 0,
            bgm = root.optString("bgm", ""),
            casts = casts,
            backgrounds = backgrounds,
            scenes = scenes
        )
    }

    private fun parseScene(obj: JSONObject): StoryScene {
        val stepsJson = obj.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsJson.length()).map { parseStep(stepsJson.getJSONObject(it)) }
        return StoryScene(
            id = obj.optString("id"),
            backgroundId = obj.optString("background", ""),
            next = obj.optString("next").takeIf { it.isNotBlank() },
            steps = steps
        )
    }

    private fun parseStep(obj: JSONObject): StoryStep = when (obj.optString("type")) {
        "say" -> StoryStep.Say(
            who = obj.optString("who"),
            text = obj.optString("text"),
            slot = obj.optString("slot").takeIf { it.isNotBlank() },
            face = obj.optString("face").takeIf { it.isNotBlank() }
        )
        "bg" -> StoryStep.ChangeBg(obj.optString("id"))
        "choice" -> {
            val arr = obj.optJSONArray("options") ?: JSONArray()
            val options = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoryChoice(
                    text = o.optString("text"),
                    affection = o.optInt("affection", 0),
                    goto = o.optString("goto").takeIf { it.isNotBlank() }
                )
            }
            StoryStep.Choice(options)
        }
        "end" -> StoryStep.End
        // 旁白：focus 指向某个 castId 时展示该角色立绘，缺省（环境描写 / 多人动作）则不展示。
        else -> StoryStep.Narrate(
            text = obj.optString("text"),
            focus = obj.optString("focus").takeIf { it.isNotBlank() }
        )
    }

    // ==================== 立绘 ====================

    fun portraitPath(castId: String, face: String?): String {
        val f = face?.takeIf { it.isNotBlank() } ?: FALLBACK_FACE
        return "$PORTRAIT_ROOT/$castId/${castId}_$f.png"
    }

    /**
     * 读取立绘并按下采样解码到接近 [targetHeightPx] 的高度，控制内存占用。
     * 差分缺失时自动回退到 [FALLBACK_FACE]。
     */
    fun loadPortrait(context: Context, castId: String, face: String?, targetHeightPx: Int): Bitmap? =
        decodeAsset(context, portraitPath(castId, face), targetHeightPx)
            ?: decodeAsset(context, portraitPath(castId, FALLBACK_FACE), targetHeightPx)

    private fun decodeAsset(context: Context, path: String, targetHeightPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds 模式下 decodeStream 只填充 bounds 并返回 null，
        // 所以这里只能用「是否抛出异常」判断成败，不能拿它的返回值当位图。
        val read = runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            true
        }.getOrDefault(false)
        if (!read || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        if (targetHeightPx > 0) {
            while (bounds.outHeight / (sample * 2) >= targetHeightPx) sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
}
