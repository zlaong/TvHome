package com.zlaong.tvhome.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.zlaong.tvhome.data.InstalledApp

/** 卡片底色：横幅图四角的颜色（让留白和图融为一体），没有横幅就取图标主色 */
object BrandColor {
    private val cache = HashMap<String, Color>()

    fun of(app: InstalledApp): Color = cache.getOrPut(app.packageName) {
        app.bannerBitmap?.let(::corners) ?: IconColor.of(app.packageName, app.iconBitmap)
    }

    private fun corners(b: Bitmap): Color? = runCatching {
        val pts = listOf(2 to 2, b.width - 3 to 2, 2 to b.height - 3, b.width - 3 to b.height - 3)
        var r = 0
        var g = 0
        var bl = 0
        pts.forEach { (x, y) ->
            val p = b.getPixel(x, y)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            bl += p and 0xFF
        }
        Color(r / 4, g / 4, bl / 4)
    }.getOrNull()
}

/**
 * 应用缩略图的内容：有横幅用横幅；没有就用图标主色做底，图标 + 名字（系统读出来的）。
 * 首页影视行和抽屉共用。
 */
@Composable
fun AppThumbContent(app: InstalledApp, small: Boolean) {
    val ui = LocalUi.current
    val banner = remember(app.packageName) { app.bannerBitmap?.asImageBitmap() }
    if (banner != null) {
        Image(banner, app.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        return
    }
    val tint = BrandColor.of(app)
    val icon = remember(app.packageName) { app.iconBitmap?.asImageBitmap() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.2f), Color(0xFF0B0E14))))
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = ui.d(16)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ui.d(if (small) 8 else 12), Alignment.CenterVertically),
    ) {
        if (icon != null) Image(icon, null, Modifier.size(ui.d(if (small) 56 else 72)))
        Text(
            app.label,
            fontSize = ui.t(if (small) 22 else 24),
            fontWeight = FontWeight.SemiBold,
            color = Tv.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

val nameShadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1f), 8f)

/**
 * 影视行一行固定 5 个、铺满整行：
 * 1920 - 左右边距 96×2 - 间隔 32×4 = 1600，单个 320 宽，16:9 即 320×180。
 */
private const val MEDIA_W = 320
private const val MEDIA_H = 180
private const val MEDIA_GAP = 32
private const val MEDIA_EDGE = 96

@Composable
fun MediaRow(
    apps: List<InstalledApp>,
    lastVideo: String?,
    loaded: Boolean,
    focusedIndex: Int,
    rowFocused: Boolean,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    val state = rememberLazyListState()

    LaunchedEffect(focusedIndex, rowFocused) {
        if (!rowFocused) return@LaunchedEffect
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
        if (item == null) {
            state.animateScrollToItem(focusedIndex)
        } else {
            val end = info.viewportEndOffset - info.afterContentPadding
            val over = item.offset + item.size - end
            val under = info.viewportStartOffset + info.beforeContentPadding - item.offset
            if (over > 0) state.animateScrollBy(over.toFloat(), Tv.move())
            else if (under > 0) state.animateScrollBy(-under.toFloat(), Tv.move())
        }
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = state,
        userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = ui.d(MEDIA_EDGE), vertical = ui.d(40)),
        horizontalArrangement = Arrangement.spacedBy(ui.d(MEDIA_GAP)),
    ) {
        if (apps.isEmpty()) {
            if (loaded) item { EmptyMedia(rowFocused) }
        } else {
            itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                MediaItem(app, app.packageName == lastVideo, rowFocused && i == focusedIndex) { onTap(i) }
            }
        }
    }
}

@Composable
private fun MediaItem(app: InstalledApp, last: Boolean, focused: Boolean, onTap: () -> Unit) {
    val ui = LocalUi.current
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, Tv.focus(), label = "media")
    val show by animateFloatAsState(if (focused) 1f else 0f, Tv.focus(), label = "mediaName")
    val sheen by rememberSheen(focused)
    val brand = BrandColor.of(app)
    val shape = RoundedCornerShape(ui.d(16))

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ui.d(16))) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRing(show, ui.d(16), ui.d(5))
                .shadow(
                    ui.d(if (focused) 40 else 16),
                    shape,
                    ambientColor = if (focused) brand else Color.Black,
                    spotColor = if (focused) brand else Color.Black,
                    clip = false,
                )
                .size(ui.d(MEDIA_W), ui.d(MEDIA_H))
                .background(brand, shape)
                .sheen(sheen)
                .tap(onTap)
        ) {
            AppThumbContent(app, small = false)
            if (last) {
                Text(
                    "上次观看",
                    fontSize = ui.t(18),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(ui.d(12))
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = ui.d(12), vertical = ui.d(2)),
                )
            }
        }
        Text(
            app.label,
            style = TextStyle(fontSize = ui.t(24), color = Tv.text, shadow = nameShadow),
            maxLines = 1,
            modifier = Modifier.graphicsLayer {
                alpha = show
                translationY = (-6f + 14f * show) * ui.px(1, density)
            },
        )
    }
}

@Composable
private fun EmptyMedia(focused: Boolean) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(ui.d(16))
    Box(
        Modifier
            .focusRing(if (focused) 1f else 0f, ui.d(16), ui.d(5))
            .size(ui.d(1728), ui.d(MEDIA_H))
            .background(Color.White.copy(alpha = 0.06f), shape)
            .border(ui.d(2), Color.White.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("还没装影视应用 · 按确认键打开全部应用", fontSize = ui.t(24), color = Tv.text2)
    }
}
