package com.zlaong.tvhome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.zlaong.tvhome.R
import com.zlaong.tvhome.data.WallpaperRepo

/**
 * 首页壁纸。另外准备两张图：
 * - full：原图，先和上下两条压暗渐变「烤」成一张，首页只画一次，省掉两层全屏混合；
 * - blurred：给卡片和抽屉当毛玻璃底的小图。
 * 电视 GPU 弱，不做实时模糊，开机算一次就够。
 */
class WallpaperImages(val full: ImageBitmap, val blurred: ImageBitmap)

object Wallpaper {

    @Volatile private var cached: Pair<Int, WallpaperImages>? = null

    /** 按 [WallpaperRepo.version] 缓存：换了壁纸才重新解码 */
    fun load(context: Context): WallpaperImages? {
        val ver = runCatching { WallpaperRepo.version.value }.getOrDefault(0)
        cached?.let { (v, img) -> if (v == ver) return img }
        val res = context.resources
        val raw = runCatching { WallpaperRepo.file()?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
            ?: runCatching { BitmapFactory.decodeResource(res, R.drawable.wallpaper_mountain_night) }.getOrNull()
            ?: return null
        val full = bakeShade(raw)
        val small = Bitmap.createScaledBitmap(raw, 160, 90, true)
        val blurred = boxBlur(small, radius = 3, passes = 3)
        return WallpaperImages(full.asImageBitmap(), blurred.asImageBitmap()).also { cached = ver to it }
    }

    /**
     * 把原图 + 顶部 24% 压暗 + 底部 66% 压暗合成一张位图。
     * 原来首页每帧要画「壁纸 + 两条全屏渐变」，电视 GPU 上约等于三次全屏填充；
     * 合成后只剩一次。
     */
    private fun bakeShade(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val c = Canvas(out)
        val top = h * 0.24f
        c.drawRect(
            0f, 0f, w.toFloat(), top,
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, top, 0x59000000, 0x00000000, Shader.TileMode.CLAMP)
            },
        )
        val bottom = h * 0.66f
        c.drawRect(
            0f, h - bottom, w.toFloat(), h.toFloat(),
            Paint().apply {
                // 必须显式转成 Int：十六进制字面量 0x85000000 会被编译成 Long，
                // 命中 ColorLong 版重载，渐变会被当成非法颜色而完全画不出来（API 29 以下还会崩）
                shader = LinearGradient(
                    0f, h - bottom, 0f, h.toFloat(),
                    0, android.graphics.Color.argb(0x85, 0, 0, 0),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        return out
    }

    /** 三遍盒式模糊约等于高斯模糊，160x90 的图几毫秒就算完 */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        var a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        var b = IntArray(w * h)
        repeat(passes) {
            blurLine(a, b, w, h, radius, horizontal = true)
            blurLine(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val n = 2 * r + 1
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var rs = 0
                var gs = 0
                var bs = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) src[l * w + j] else src[j * w + l]
                    rs += (p shr 16) and 0xFF
                    gs += (p shr 8) and 0xFF
                    bs += p and 0xFF
                }
                val out = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                if (horizontal) dst[l * w + i] = out else dst[i * w + l] = out
            }
        }
    }
}
