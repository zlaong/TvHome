package com.zlaong.tvhome.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
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
import com.zlaong.tvhome.data.InstalledApp
import kotlin.math.roundToInt

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
    val topPad = ui.px(136, density)
    val bottomPad = ui.px(96, density)

    LaunchedEffect(focusRow, expanded) {
        if (!expanded) {
            scroll.scrollTo(0)
            return@LaunchedEffect
        }
        val r = rows.getOrNull(focusRow) ?: return@LaunchedEffect
        val (ry, rh) = rowY[focusRow] ?: return@LaunchedEffect
        val gy = groupY[r.group] ?: return@LaunchedEffect
        val top = if (r.first) gy else gy + ry
        val bottom = gy + ry + rh
        val viewTop = scroll.value + topPad
        val viewBottom = scroll.value + scroll.viewportSize - bottomPad
        val target = when {
            focusRow == 0 -> 0f
            top < viewTop -> top - topPad
            bottom > viewBottom -> bottom - scroll.viewportSize + bottomPad
            else -> return@LaunchedEffect
        }
        val dest = target.roundToInt().coerceAtLeast(0)
        // 跨屏的移动直接跳到位，一行一行挪才用短动画，连按遥控器时列表才跟得上焦点
        if (kotlin.math.abs(dest - scroll.value) > scroll.viewportSize) {
            scroll.scrollTo(dest)
        } else {
            scroll.animateScrollTo(dest, Tv.move())
        }
    }

    val shape = RoundedCornerShape(topStart = ui.d(32), topEnd = ui.d(32))
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
                val r = ui.px(32, density)
                drawRoundRect(
                    Color.White.copy(alpha = 0.12f),
                    size = Size(size.width, size.height + r),
                    cornerRadius = CornerRadius(r),
                    style = Stroke(1.dp.toPx()),
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll, enabled = false)
                .padding(start = ui.d(96), end = ui.d(96), top = ui.d(136), bottom = ui.d(96))
        ) {
            groups.forEachIndexed { gi, g ->
                val groupRows = rows.withIndex().filter { it.value.group == gi }
                val show by animateFloatAsState(
                    if (expanded) 1f else 0f,
                    if (expanded) tween(Tv.MOVE, delayMillis = 60 * (gi + 1), easing = Tv.easeOut) else snap(),
                    label = "group",
                )
                Column(
                    Modifier
                        .padding(bottom = ui.d(28))
                        .onGloballyPositioned { groupY[gi] = it.positionInParent().y }
                        .graphicsLayer {
                            alpha = show
                            translationY = (1f - show) * ui.px(24, density)
                        }
                ) {
                    Text(
                        g.title,
                        fontSize = ui.t(24),
                        color = Tv.text2,
                        modifier = Modifier.height(ui.d(32)),
                    )
                    Spacer(Modifier.height(ui.d(12)))
                    groupRows.forEach { (ri, row) ->
                        Row(
                            Modifier
                                .padding(bottom = ui.d(8))
                                .onGloballyPositioned { rowY[ri] = it.positionInParent().y to it.size.height.toFloat() },
                            horizontalArrangement = Arrangement.spacedBy(ui.d(28)),
                        ) {
                            row.tiles.forEachIndexed { ci, tile ->
                                TileView(tile, expanded && ri == focusRow && ci == focusCol) { onTap(ri, ci) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileView(tile: Tile, focused: Boolean, onTap: () -> Unit) {
    val ui = LocalUi.current
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, Tv.focus(), label = "tile")
    val ring by animateFloatAsState(if (focused) 1f else 0f, Tv.focus(), label = "tileRing")
    val sheen by rememberSheen(focused)
    val shape = RoundedCornerShape(ui.d(14))
    val isAdd = tile is Tile.Add

    Column(verticalArrangement = Arrangement.spacedBy(ui.d(8))) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRing(ring, ui.d(14), ui.d(5))
                .then(if (!isAdd) Modifier.shadow(ui.d(if (focused) 24 else 10), shape, clip = false) else Modifier)
                .size(ui.d(240), ui.d(135))
                .clip(shape)
                .then(tileBackground(tile))
                .sheen(sheen)
                .tap(onTap),
            contentAlignment = Alignment.Center,
        ) {
            when (tile) {
                is Tile.App -> AppThumbContent(tile.app, small = true)
                is Tile.Source -> SourceContent(tile)
                Tile.Add -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TvIcon.Plus, ui.d(28), Tv.text2)
                    Spacer(Modifier.width(ui.d(8)))
                    Text("添加", fontSize = ui.t(24), color = Tv.text2)
                }
            }
        }
        Text(
            if (isAdd) "" else tile.name,
            fontSize = ui.t(20),
            color = Tv.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(ui.d(240))
                .height(ui.d(30))
                .graphicsLayer { alpha = ring },
        )
    }
}

@Composable
private fun tileBackground(tile: Tile): Modifier {
    val ui = LocalUi.current
    val density = LocalDensity.current.density
    return when (tile) {
        is Tile.App -> Modifier.background(BrandColor.of(tile.app))
        is Tile.Source -> Modifier.background(
            if (tile.source.isPlayStation) Brush.linearGradient(listOf(Color(0xFF0070D1), Color(0xFF003791)))
            else Brush.linearGradient(listOf(Color(0xFF3A4252), Color(0xFF1C212B)))
        )
        Tile.Add -> Modifier
            .background(Color.White.copy(alpha = 0.06f))
            .drawBehind {
                val w = ui.px(2, density)
                val r = ui.px(14, density)
                drawRoundRect(
                    Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(r - w / 2),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 3))),
                )
            }
    }
}

@Composable
private fun SourceContent(tile: Tile.Source) {
    val ui = LocalUi.current
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (tile.source.isPlayStation) TvIcon.Gamepad else TvIcon.Hdmi, ui.d(48), Color.White)
            Spacer(Modifier.width(ui.d(14)))
            Text(tile.source.name, fontSize = ui.t(34), fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        }
        Text(
            tile.source.port,
            fontSize = ui.t(16),
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(ui.d(10))
                .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                .padding(horizontal = ui.d(10), vertical = ui.d(2)),
        )
    }
}

// ------------------------------------------------------------------ 弹层

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

/** 「常用」里点「添加」：列出还没加进来的应用 */
@Composable
fun PickerOverlay(apps: List<InstalledApp>, index: Int, onPick: (Int) -> Unit) {
    val ui = LocalUi.current
    val list = rememberLazyListState()
    LaunchedEffect(index) {
        val vis = list.layoutInfo.visibleItemsInfo
        if (vis.isEmpty()) return@LaunchedEffect
        if (index <= vis.first().index || index >= vis.last().index) {
            list.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
    }
    Scrim {
        Panel(900) {
            Text("加到「常用」", fontSize = ui.t(30), fontWeight = FontWeight.SemiBold, color = Tv.text)
            Text("确认键加入 · 返回键关闭", fontSize = ui.t(20), color = Tv.text3)
            Spacer(Modifier.height(ui.d(8)))
            if (apps.isEmpty()) {
                Text("所有应用都已经在「常用」里了", fontSize = ui.t(24), color = Tv.text2)
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.heightIn(max = ui.d(600)),
                    verticalArrangement = Arrangement.spacedBy(ui.d(8)),
                    userScrollEnabled = false,
                ) {
                    itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                        PickerRow(app, i == index) { onPick(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(app: InstalledApp, focused: Boolean, onTap: () -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(ui.d(16))
    val icon = remember(app.packageName) { app.iconBitmap?.asImageBitmap() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ui.d(88))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.04f), shape)
            .tap(onTap)
            .padding(horizontal = ui.d(24)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Image(icon, null, Modifier.size(ui.d(48))) else Spacer(Modifier.size(ui.d(48)))
        Spacer(Modifier.width(ui.d(20)))
        Text(app.label, fontSize = ui.t(26), color = if (focused) Tv.dark else Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
