package com.zlaong.tvhome.ui

import androidx.compose.ui.graphics.Color

/**
 * 影院感深色配色：背景近黑，内容靠图标主色 + 焦点光圈出彩。
 * 卡片一律半透明白（surface），不做成实心蓝块，避免和海报抢注意力。
 */
object Palette {
    /** 近黑底色，电视上看不会发灰 */
    val bg = Color(0xFF06070A)
    val bgTop = bg
    val bgBottom = Color(0xFF0A0D14)

    /** 右上角的冷光晕，给纯黑底一点纵深 */
    val glow = Color(0xFF2E62FF)

    /** 半透明卡片底（6% 白），叠在纯黑上像磨砂玻璃 */
    val surface = Color(0x0FFFFFFF)
    val surfaceFocused = Color(0x1FFFFFFF)

    /** 兼容旧调用：设置页仍用这两个名字 */
    val card = Color(0xFF11141B)
    val cardFocused = Color(0xFF1B2029)

    val stroke = Color(0x14FFFFFF)
    val ring = Color(0xFFD6E6FF)
    val accent = Color(0xFF6FA8FF)

    val textPrimary = Color(0xFFF5F7FA)
    val textSecondary = Color(0xFF9AA4B2)
    val textDim = Color(0xFF5D6675)

    val ok = Color(0xFF52D98A)
    val warn = Color(0xFFF5BF4F)
    val error = Color(0xFFF0736E)
}