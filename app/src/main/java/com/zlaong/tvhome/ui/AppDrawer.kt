package com.zlaong.tvhome.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** 顶栏下沿（屏幕坐标）：抽屉内容在这里裁掉，滚动时不会从顶栏图标底下穿过 */
private const val CLIP_TOP = 112
/** 裁切线到第一个分组标题的距离，也是焦点放大、白圈离裁切线的最小余量 */
private const val CONTENT_TOP = 24
private const val TILE_W = 264
private const val TILE_H = 148

/**
 * 全部应用抽屉：首页按「下」从底部升起，盖满全屏（顶栏留在上面）。
 * 毛玻璃用的是预先模糊好的壁纸，跟着面板滑动时保持不动。
 */
@Composable
fun AppDrawer(
    groups: List<TileGroup>,
    rows: List<DrawerRow>,
    expanded: Boolean,
    focusRow: Int,
    focusCol: Int,
    moving: Boolean,
    onTap: (row: Int, col: Int) -> Unit,
) {
    val ui = LocalUi.current
    val density = LocalDensity.current.density
    val bd = LocalBackdrop.current
    val open by animateFloatAsState(if (expanded) 1f else 0f, tween(Tv.MOVE, easing = Tv.easeOut), label = "drawer")
    if (open <= 0.001f) return

    val scroll = rememberScrollState()
    val groupY = remember { mutableStateMapOf<Int, Float>() }
    val rowY = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val topPad = ui.px(CONTENT_TOP, density)
    val bottomPad = ui.px(96, density)

    LaunchedEffect(focusRow, expanded) {
        if (!expanded) {
            scroll.scrollTo(0)
            return@LaunchedEffect
        }
        val r = rows.getOrNull(focusRow) ?: return@LaunchedEffect
        val (ry, rh) = rowY[focusRow] ?: return@LaunchedEffect
        val gy = groupY[r.group] ?: return@LaunchedEffect
        // 换算成滚动内容里的坐标；分组第一行连标题一起露出来
        val top = topPad + if (r.first) gy else gy + ry
        val bottom = topPad + gy + ry + rh
        val target = when {
            focusRow == 0 -> 0f
            top < scroll.value + topPad -> top - topPad
            bottom > scroll.value + scroll.viewportSize - bottomPad -> bottom + bottomPad - scroll.viewportSize
            else -> return@LaunchedEffect
        }
        val dest = target.roundToInt().coerceIn(0, scroll.maxValue)
        // 跨屏的移动直接跳到位，一行一行挪才用短动画，连按遥控器时列表才跟得上焦点
        if (kotlin.math.abs(dest - scroll.value) > scroll.viewportSize) {
            scroll.scrollTo(dest)
        } else {
            scroll.animateScrollTo(dest, Tv.move())
        }
    }

    // 升起过程中保留圆角，快到顶时再收成直角，展开后顶部不留圆角和描边
    val corner = ((1f - open) / 0.15f).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(topStart = ui.d(32) * corner, topEnd = ui.d(32) * corner)
    Box(
        Modifier
            .zIndex(4f)
            .fillMaxSize()
            // 用布局偏移而不是 graphicsLayer：整屏离屏图层在电视 GPU 上每帧都要重新合成，代价明显
            .offset { IntOffset(0, (bd.rootSize.height * (1f - open)).roundToInt()) }
            .clip(shape)
            .drawBehind {
                val img = bd.blurred
                if (img != null && bd.rootSize.width > 0) {
                    drawImage(
                        img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(0, -(size.height * (1f - open)).roundToInt()),
                        dstSize = bd.rootSize,
                        filterQuality = FilterQuality.Low,
                    )
                }
                drawRect(Color(0xFF0C1018).copy(alpha = 0.6f))
                if (corner > 0.01f) {
                    val r = ui.px(32, density) * corner
                    drawRoundRect(
                        Color.White.copy(alpha = 0.12f * corner),
                        size = Size(size.width, size.height + r),
                        cornerRadius = CornerRadius(r),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = ui.d(CLIP_TOP))
                .clipToBounds()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = false)
                    .padding(start = ui.d(96), end = ui.d(96), top = ui.d(CONTENT_TOP), bottom = ui.d(96))
            ) {
                groups.forEachIndexed { gi, g ->
                    key(g.title) {
                        val groupRows = rows.withIndex().filter { it.value.group == gi }
                        // 展开时分组依次浮现；收起时跟着面板一起淡出，不再瞬间消失
                        val show = remember { Animatable(0f) }
                        LaunchedEffect(expanded) {
                            if (expanded) show.animateTo(1f, tween(Tv.MOVE, delayMillis = 50 * gi, easing = Tv.easeOut))
                            else show.animateTo(0f, tween(Tv.MOVE / 2, easing = Tv.easeOut))
                        }
                        Column(
                            Modifier
                                .padding(bottom = ui.d(28))
                                .onGloballyPositioned { groupY[gi] = it.positionInParent().y }
                                .graphicsLayer {
                                    // 默认的半透明会先画进一张和分组一样大的离屏图，
                                    // 选中格子放大后超出分组的那圈（白框、阴影）被裁掉，淡入结束才露出来。
                                    // 逐个元素乘透明度：不裁切，也省一张离屏图
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                    alpha = show.value
                                    translationY = (1f - show.value) * ui.px(24, density)
                                }
                        ) {
                            Text(
                                g.title,
                                fontSize = ui.t(24),
                                color = Tv.text2,
                                modifier = Modifier.height(ui.d(32)),
                            )
                            Spacer(Modifier.height(ui.d(14)))
                            groupRows.forEach { (ri, row) ->
                                Row(
                                    Modifier
                                        .padding(bottom = ui.d(14))
                                        .onGloballyPositioned { rowY[ri] = it.positionInParent().y to it.size.height.toFloat() },
                                    horizontalArrangement = Arrangement.spacedBy(ui.d(28)),
                                ) {
                                    row.tiles.forEachIndexed { ci, tile ->
                                        val focused = expanded && ri == focusRow && ci == focusCol
                                        TileView(tile, focused, lifted = focused && moving) { onTap(ri, ci) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (moving) MoveHint("方向键挪位置 · 确认键放下 · 返回键取消", Modifier.align(Alignment.BottomCenter).padding(bottom = ui.d(32)))
    }
}

/** lifted：正在挪位置，放得更大、换黄色描边，和普通选中区分开 */
@Composable
private fun TileView(tile: Tile, focused: Boolean, lifted: Boolean, onTap: () -> Unit) {
    val ui = LocalUi.current
    val scale by animateFloatAsState(
        when {
            lifted -> 1.1f
            focused -> 1.08f
            else -> 1f
        },
        Tv.focus(),
        label = "tile",
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, Tv.focus(), label = "tileRing")
    val sheen by rememberSheen(focused && !lifted)
    val shape = RoundedCornerShape(ui.d(14))

    Column(verticalArrangement = Arrangement.spacedBy(ui.d(10))) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRing(ring, ui.d(14), ui.d(5), if (lifted) Tv.busy else Color.White)
                .shadow(ui.d(if (focused) 24 else 10), shape, clip = false)
                .size(ui.d(TILE_W), ui.d(TILE_H))
                .clip(shape)
                .background(BrandColor.of(tile.app))
                .sheen(sheen)
                .tap(onTap),
            contentAlignment = Alignment.Center,
        ) {
            AppThumbContent(tile.app, small = true)
        }
        // 名字一直显示（不然只有图标的应用认不出来），选中时更亮
        Text(
            tile.name,
            fontSize = ui.t(20),
            color = Tv.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(ui.d(TILE_W))
                .height(ui.d(30))
                .graphicsLayer { alpha = 0.6f + 0.4f * ring },
        )
    }
}

// ------------------------------------------------------------------ 弹层

/** 挪位置时的黄色提示条，首页影视行和抽屉共用 */
@Composable
fun MoveHint(text: String, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    Text(
        text,
        fontSize = ui.t(22),
        color = Tv.dark,
        modifier = modifier
            .zIndex(6f)
            .background(Tv.busy, RoundedCornerShape(50))
            .padding(horizontal = ui.d(28), vertical = ui.d(12)),
    )
}

@Composable
internal fun Scrim(content: @Composable () -> Unit) {
    Box(
        Modifier
            .zIndex(7f)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun Panel(width: Int, content: @Composable () -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(ui.d(28))
    Column(
        Modifier
            .width(ui.d(width))
            .background(Color(0xFF161B26), shape)
            .border(ui.d(1), Color.White.copy(alpha = 0.12f), shape)
            .padding(ui.d(40)),
        verticalArrangement = Arrangement.spacedBy(ui.d(12)),
    ) { content() }
}

@Composable
private fun SheetButton(text: String, focused: Boolean, onTap: () -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(ui.d(16))
    Box(
        Modifier
            .fillMaxWidth()
            .height(ui.d(72))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), shape)
            .tap(onTap)
            .padding(horizontal = ui.d(28)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, fontSize = ui.t(26), color = if (focused) Tv.dark else Tv.text)
    }
}

/** 长按应用弹出的菜单 */
@Composable
fun SheetOverlay(sheet: Sheet, index: Int) {
    val ui = LocalUi.current
    Scrim {
        Panel(640) {
            Text(sheet.title, fontSize = ui.t(30), fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 1)
            Spacer(Modifier.height(ui.d(8)))
            sheet.actions.forEachIndexed { i, (label, action) -> SheetButton(label, i == index, action) }
        }
    }
}

/** 顶部居中的提示条 */
@Composable
fun ToastView(text: String?) {
    val ui = LocalUi.current
    // 淡出时 text 已经是 null，继续显示上一条，免得提示条先变空再消失
    var last by remember { mutableStateOf(text) }
    if (text != null) last = text
    val shown by animateFloatAsState(if (text != null) 1f else 0f, tween(260, easing = Tv.easeOut), label = "toast")
    if (shown <= 0.01f) return
    Box(Modifier.zIndex(8f).fillMaxWidth().padding(top = ui.d(72)), contentAlignment = Alignment.TopCenter) {
        Text(
            last.orEmpty(),
            fontSize = ui.t(24),
            color = Color.White,
            modifier = Modifier
                .graphicsLayer {
                    alpha = shown
                    translationY = (shown - 1f) * 12f * ui.px(1, density)
                }
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                .padding(horizontal = ui.d(32), vertical = ui.d(16)),
        )
    }
}
