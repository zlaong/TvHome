package com.zlaong.tvhome.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.zlaong.tvhome.data.InstalledApp
import com.zlaong.tvhome.data.PrinterUi

/**
 * 假影视 APP：只在 debug 包 + 手动打开开关时出现，用来在模拟器上验证排版。
 * 正式包永远不会显示（HomeScreen 里有 BuildConfig.DEBUG 判断）。
 *
 * 故意做一半「自带海报」、一半「只有图标」，模拟真实电视：
 * 腾讯 / 酷喵有 320x180 海报，芒果 / B站 / 奇异果只有图标。
 */
object DemoData {

    private data class Demo(
        val label: String,
        val from: Long,
        val to: Long,
        val withBanner: Boolean = true,
    )

    private val demos = listOf(
        Demo("云视听极光", 0xFF2E7BE0, 0xFF0B2E5C),
        Demo("银河奇异果", 0xFF00B84D, 0xFF05351C, withBanner = false),
        Demo("CIBN 酷喵", 0xFFE23A34, 0xFF3B0B0B),
        Demo("芒果 TV", 0xFFFF8A00, 0xFF3B2200, withBanner = false),
        Demo("云视听小电视", 0xFF00A6BF, 0xFF062C33, withBanner = false),
        Demo("Netflix", 0xFFC1121F, 0xFF1A0505),
    )

    fun videoApps(context: Context): List<InstalledApp> = demos.map { demo ->
        InstalledApp(
            label = demo.label,
            packageName = "demo.${demo.label}",
            icon = icon(context, demo),
            banner = if (demo.withBanner) banner(context, demo) else null,
            intent = Intent(),
            isVideo = true,
        )
    }

    /** 演示用的「打印中」状态，用来在模拟器上看打印机卡片的样子 */
    fun printer(): PrinterUi = PrinterUi(
        reachable = true,
        online = true,
        name = "Bambu Lab X2D",
        state = "RUNNING",
        stateText = "打印中",
        jobName = "Benchy_0.2mm_PLA_X2D",
        progress = 63,
        remainingSec = 2 * 3600 + 13 * 60,
        nozzle = 214.0,
        nozzleTarget = 215.0,
        bed = 60.0,
        bedTarget = 60.0,
        chamber = 38.0,
        lastUpdate = System.currentTimeMillis(),
    )

    /** 640x360 的假海报：渐变底 + 名字 */
    private fun banner(context: Context, demo: Demo): Drawable {
        val w = 640
        val h = 360
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            demo.from.toInt(), demo.to.toInt(), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 58f
            isFakeBoldText = true
        }
        canvas.drawText(demo.label, 42f, h - 52f, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /** 160x160 的假图标：圆角方块 + 品牌色 + 首字 */
    private fun icon(context: Context, demo: Demo): Drawable {
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            demo.from.toInt(), demo.to.toInt(), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 36f, 36f, paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 70f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val offset = -(textPaint.ascent() + textPaint.descent()) / 2
        canvas.drawText(demo.label.take(1), size / 2f, size / 2f + offset, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }
}