package com.zlaong.tvhome.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壁纸来源：
 * - builtin：自带的《星空下的雪山》
 * - daily：必应每日壁纸，每天自动换（cn.bing.com，免费、国内能访问）
 * - bing：固定用必应最近 8 天里的某一张
 * - custom：手机上传的照片
 * 选好的图统一存成 files/wallpaper/current.jpg，首页只读这一个文件。
 */
object WallpaperRepo {

    private const val TAG = "WallpaperRepo"
    private const val PREFS = "tvhome_wallpaper"
    private const val BING = "https://cn.bing.com"
    private const val MAX_UPLOAD = 40 * 1024 * 1024

    enum class Mode(val key: String) { Builtin("builtin"), Daily("daily"), Bing("bing"), Custom("custom") }

    data class Choice(
        val mode: Mode = Mode.Builtin,
        /** bing 模式下选的哪一张（必应图片编号） */
        val bingId: String = "",
        /** 当前壁纸的说明，例如必应图片的标题 */
        val title: String = "",
        /** 每天模式上次更新的日期 yyyyMMdd */
        val day: String = "",
    )

    data class BingImage(val id: String, val title: String, val copyright: String, val date: String) {
        val full get() = "$BING/th?id=$id" + "_1920x1080.jpg"
        val thumb get() = "$BING/th?id=$id" + "_400x240.jpg"
    }

    private lateinit var appContext: Context
    private val lock = Mutex()

    private val _choice = MutableStateFlow(Choice())
    val choice: StateFlow<Choice> = _choice.asStateFlow()

    /** 每换一次壁纸 +1，首页据此重新加载 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** 正在下载 / 处理时给设置页显示「正在更换…」 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var bingCache: Pair<Long, List<BingImage>>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = prefs()
        _choice.value = Choice(
            mode = Mode.entries.firstOrNull { it.key == p.getString("mode", null) } ?: Mode.Builtin,
            bingId = p.getString("bing_id", null).orEmpty(),
            title = p.getString("title", null).orEmpty(),
            day = p.getString("day", null).orEmpty(),
        )
        // 文件丢了（比如清了数据）就退回自带壁纸
        if (_choice.value.mode != Mode.Builtin && !current().exists()) save(Choice())
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir() = File(appContext.filesDir, "wallpaper").apply { mkdirs() }
    private fun current() = File(dir(), "current.jpg")

    /** 首页要画的壁纸文件；null = 用自带的 */
    fun file(): File? = current().takeIf { _choice.value.mode != Mode.Builtin && it.exists() }

    private fun save(c: Choice) {
        prefs().edit()
            .putString("mode", c.mode.key)
            .putString("bing_id", c.bingId)
            .putString("title", c.title)
            .putString("day", c.day)
            .apply()
        _choice.value = c
        _version.value++
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())

    // ------------------------------------------------------------ 必应

    suspend fun bingList(): List<BingImage> {
        bingCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) return list }
        val o = JSONObject(Http.get("$BING/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN", timeoutMs = 8000))
        val arr = o.getJSONArray("images")
        val list = (0 until arr.length()).mapNotNull { i ->
            val im = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = im.optString("urlbase").substringAfter("id=", "")
            if (!Regex("^[A-Za-z0-9._\\-]+$").matches(id)) return@mapNotNull null
            BingImage(id, im.optString("title"), im.optString("copyright"), im.optString("enddate"))
        }
        bingCache = System.currentTimeMillis() to list
        return list
    }

    /** 缩略图，设置页的格子用；内存里留一份，来回切换不重复下载 */
    private val thumbs = HashMap<String, Bitmap>()

    suspend fun bingThumb(img: BingImage): Bitmap? {
        synchronized(thumbs) { thumbs[img.id] }?.let { return it }
        val bmp = runCatching {
            val bytes = Http.bytes(img.thumb, maxBytes = 2 * 1024 * 1024)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null
        synchronized(thumbs) { thumbs[img.id] = bmp }
        return bmp
    }

    // ------------------------------------------------------------ 切换

    /** 返回 null 表示成功，否则是出错原因 */
    suspend fun useBuiltin(): String? = lock.withLock {
        save(Choice())
        null
    }

    suspend fun useDaily(): String? = lock.withLock {
        work {
            val img = bingList().firstOrNull() ?: return@work "没拿到必应今天的壁纸"
            download(img.full)
            save(Choice(Mode.Daily, img.id, img.title, today()))
            null
        }
    }

    suspend fun useBing(id: String): String? = lock.withLock {
        work {
            val img = bingList().firstOrNull { it.id == id } ?: return@work "这张壁纸已经找不到了，刷新一下再选"
            download(img.full)
            save(Choice(Mode.Bing, img.id, img.title))
            null
        }
    }

    /** 手机上传的照片：缩到 1920 宽、按 16:9 居中裁好再存 */
    suspend fun useUpload(length: Long, input: InputStream): String? = lock.withLock {
        if (length <= 0 || length > MAX_UPLOAD) return@withLock "照片太大了（最多 40 MB）"
        work {
            val tmp = File(dir(), "upload.part")
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                if (left > 0) { tmp.delete(); return@work "传输中断了，请重试" }
            }
            val ok = runCatching { writeFitted(tmp) }.getOrDefault(false)
            tmp.delete()
            if (!ok) return@work "这张照片打不开，换一张试试"
            save(Choice(Mode.Custom, title = "手机上传的照片"))
            null
        }
    }

    /** 每天模式：过了零点就换今天的图。首页每次回到前台调一次，失败了下次再试 */
    suspend fun refreshDaily() {
        val c = _choice.value
        if (c.mode != Mode.Daily || c.day == today()) return
        lock.withLock {
            if (_choice.value.day == today()) return
            runCatching {
                bingCache = null
                val img = bingList().firstOrNull() ?: return
                if (img.id != c.bingId) download(img.full)
                save(Choice(Mode.Daily, img.id, img.title, today()))
            }.onFailure { Log.w(TAG, "每日壁纸更新失败", it) }
        }
    }

    private suspend fun work(block: suspend () -> String?): String? {
        _busy.value = true
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            Log.w(TAG, "更换壁纸失败", e)
            "网络不通，换壁纸失败"
        } finally {
            _busy.value = false
        }
    }

    private suspend fun download(url: String) {
        val bytes = Http.bytes(url, maxBytes = 15 * 1024 * 1024)
        val tmp = File(dir(), "dl.part")
        tmp.writeBytes(bytes)
        // 先确认是一张能解开的图，再替换，坏图不能顶掉现在的壁纸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, bounds)
        if (bounds.outWidth <= 0) {
            tmp.delete()
            error("不是图片")
        }
        if (!tmp.renameTo(current())) { tmp.delete(); error("保存失败") }
    }

    private fun writeFitted(src: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val rotated = rotateByExif(raw, src)
        // 居中裁成 16:9，再缩放到 1920x1080
        val w = rotated.width
        val h = rotated.height
        val (cw, ch) = if (w * 9 > h * 16) (h * 16 / 9) to h else w to (w * 9 / 16)
        val cropped = Bitmap.createBitmap(rotated, (w - cw) / 2, (h - ch) / 2, cw, ch)
        val out = Bitmap.createScaledBitmap(cropped, 1920, 1080, true)
        val tmp = File(dir(), "fit.part")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return tmp.renameTo(current())
    }

    private fun rotateByExif(bmp: Bitmap, file: File): Bitmap {
        @Suppress("DEPRECATION")
        val deg = when (runCatching { android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
    }
}
