package com.zlaong.tvhome.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import android.graphics.Color as AColor

/**
 * 从 APP 图标里取「主色」，用来给卡片做底色。
 *
 * 电视端拿不到片单海报，只能拿到图标，所以图标本身就是卡片的视觉主体：
 * 用它的主色打一层暗渐变当背景，比统一色块更像一张「海报」。
 */
object IconColor {

    private val cache = HashMap<String, Color>()

    fun of(key: String, bitmap: Bitmap?): Color {
        cache[key]?.let { return it }
        val color = bitmap?.let { runCatching { compute(it) }.getOrNull() } ?: fallback(key)
        cache[key] = color
        return color
    }

    /** 取图标中有彩色的像素平均值；太亮（白底）、太暗、太灰的像素丢掉 */
    private fun compute(bmp: Bitmap): Color? {
        if (bmp.width <= 0 || bmp.height <= 0) return null
        val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val p = small.getPixel(x, y)
                if (AColor.alpha(p) < 200) continue
                AColor.colorToHSV(p, hsv)
                if (hsv[1] < 0.20f || hsv[2] < 0.12f || hsv[2] > 0.96f) continue
                r += AColor.red(p)
                g += AColor.green(p)
                b += AColor.blue(p)
                n++
            }
        }
        if (n < 24) return null
        AColor.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        // 压暗 + 提饱和：作背景色用，太亮会盖住图标
        hsv[1] = hsv[1].coerceAtLeast(0.62f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.92f)
        return Color(AColor.HSVToColor(hsv))
    }

    /** 取不到主色时按包名散列一个稳定色，同一个 APP 每次都一样 */
    private fun fallback(key: String): Color {
        val hue = (((key.hashCode() % 360) + 360) % 360).toFloat()
        return Color(AColor.HSVToColor(floatArrayOf(hue, 0.45f, 0.78f)))
    }
}