package com.zlaong.tvhome.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 首页按 1920x1080 的设计稿取值，运行时按屏幕宽度等比换算。
 * d(304) 就是设计稿里的 304px，t(24) 是 24px 的字。
 */
class Ui(private val u: Float, private val fontScale: Float) {
    fun d(px: Number): Dp = (px.toFloat() * u).dp
    fun t(px: Number): TextUnit = (px.toFloat() * u / fontScale).sp
    fun px(px: Number, density: Float): Float = px.toFloat() * u * density
}

val LocalUi = compositionLocalOf { Ui(0.5f, 1f) }

object Tv {
    val text = Color.White
    val text2 = Color.White.copy(alpha = 0.78f)
    val text3 = Color.White.copy(alpha = 0.55f)
    val danger = Color(0xFFFF6B6B)
    val ok = Color(0xFF4ADE80)
    val busy = Color(0xFFFBBF24)
    val dark = Color(0xFF11151D)
    val glassTint = Color(0xFF0A0E16)
    /**
     * 只给一次性的动作用（抽屉开合、全屏页淡入、提示条）。
     * 起点速度不能为 0：(0.3, 0, 0.2, 1) 按键后前 3 帧几乎不动，显得「不跟手」；
     * (0.2, 0.8, 0.2, 1) 又太冲，像「跳」过去。这条第 1 帧走约 30%，后半段慢慢收。
     */
    val easeOut = CubicBezierEasing(0.1f, 0.5f, 0.2f, 1f)

    /**
     * 焦点放大、描边、圆点：遥控器连按时动画会被打断，弹簧动画能接着当前速度改目标，
     * 补间动画会从 0 速度重新起步，连按时一顿一顿的。不回弹（阻尼 1）。
     */
    fun <T> focus() = spring<T>(dampingRatio = 1f, stiffness = 2500f)

    /** 卡片翻页、列表跟随焦点滚动：位移大，比焦点稍慢 */
    fun <T> move() = spring<T>(dampingRatio = 1f, stiffness = 800f)
    /** 抽屉开合、全屏页淡入 */
    const val MOVE = 340
}

/** 模糊后的壁纸 + 根布局尺寸，卡片用它假装「毛玻璃」，比实时模糊省得多 */
class Backdrop(val blurred: ImageBitmap?, val rootSize: IntSize)

val LocalBackdrop = compositionLocalOf { Backdrop(null, IntSize.Zero) }

/** 毛玻璃底：画出卡片背后那一块模糊壁纸，再压一层半透明深色 */
fun Modifier.frosted(shape: Shape, tint: Color): Modifier = composed {
    val bd = LocalBackdrop.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            val img = bd.blurred
            if (img != null && bd.rootSize.width > 0) {
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(-pos.x.roundToInt(), -pos.y.roundToInt()),
                    dstSize = bd.rootSize,
                    filterQuality = FilterQuality.Low,
                )
            }
            drawRect(tint)
        }
}

/** 焦点白圈，画在卡片外侧；要放在 clip 之前 */
fun Modifier.focusRing(alpha: Float, radius: Dp, width: Dp): Modifier = drawWithContent {
    drawContent()
    if (alpha > 0.01f) {
        val w = width.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = alpha),
            topLeft = Offset(-w / 2, -w / 2),
            size = Size(size.width + w, size.height + w),
            cornerRadius = CornerRadius(radius.toPx() + w / 2),
            style = Stroke(w),
        )
    }
}

/** 选中时从左到右扫过的一道高光；要放在 clip 之后 */
fun Modifier.sheen(progress: Float): Modifier = drawWithContent {
    drawContent()
    if (progress <= 0f || progress >= 1f) return@drawWithContent
    val w = size.width
    val x0 = (-1.3f + 2.6f * progress) * w + 0.35f * w
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.28f),
            1f to Color.Transparent,
            start = Offset(x0, 0f),
            end = Offset(x0 + 0.3f * w, 0.11f * w),
        )
    )
}

@Composable
fun rememberSheen(focused: Boolean): State<Float> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        a.snapTo(0f)
        if (focused) a.animateTo(1f, tween(700, easing = Tv.easeOut))
    }
    return a.asState()
}

/** 鼠标/触屏点一下（模拟器调试用），不让元素抢走遥控器焦点 */
fun Modifier.tap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    detectTapGestures { onTap() }
}
