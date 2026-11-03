package com.zlaong.tvhome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zlaong.tvhome.R
import com.zlaong.tvhome.data.WallpaperRepo
import com.zlaong.tvhome.data.WallpaperRepo.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class WallItem(
    val key: String,
    val label: String,
    val selected: Boolean,
    val thumb: suspend (Context) -> Bitmap?,
    /** 缩略图跟着它变：「每日一图」要等必应列表回来才有图 */
    val thumbKey: String = key,
    val onClick: suspend () -> String?,
)

@Composable
internal fun WallpaperPanel() {
    val scope = rememberCoroutineScope()
    val choice by WallpaperRepo.choice.collectAsState()
    val busy by WallpaperRepo.busy.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val bing by produceState<List<WallpaperRepo.BingImage>?>(null) {
        value = runCatching { WallpaperRepo.bingList() }.getOrDefault(emptyList())
    }
    val (uploadLink, _) = rememberSetupLink("wallpaper")

    val items = buildList {
        add(WallItem("builtin", "星空下的雪山", choice.mode == Mode.Builtin, ::builtinThumb) { WallpaperRepo.useBuiltin() })
        val today = bing?.firstOrNull()
        add(WallItem("daily", "必应每日一图", choice.mode == Mode.Daily, { today?.let { WallpaperRepo.bingThumb(it) } }, "daily:" + today?.id) {
            WallpaperRepo.useDaily()
        })
        if (choice.mode == Mode.Custom) {
            add(WallItem("custom", "手机上传的照片", true, { currentThumb() }) { null })
        }
        bing.orEmpty().forEach { img ->
            add(WallItem(img.id, img.title.ifBlank { img.date }, choice.mode == Mode.Bing && choice.bingId == img.id,
                { WallpaperRepo.bingThumb(img) }) { WallpaperRepo.useBing(img.id) })
        }
    }

    Text(
        when {
            busy -> "正在更换…"
            error != null -> error!!
            else -> "当前：" + choice.title.ifBlank { "星空下的雪山" }
        },
        fontSize = 14.sp,
        color = if (error != null && !busy) Palette.error else Palette.textSecondary,
    )
    TvGroup("选择壁纸")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { item ->
                    WallTile(item, Modifier.weight(1f)) {
                        if (busy) return@WallTile
                        error = null
                        scope.launch { error = item.onClick() }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (bing?.isEmpty() == true) {
        Text("必应壁纸加载失败，检查一下电视网络", fontSize = 13.sp, color = Palette.textDim, modifier = Modifier.padding(top = 10.dp))
    }
    TvGroup("用自己的照片")
    QrCard(uploadLink, "手机扫码上传", listOf("扫码后在网页里点「上传照片当壁纸」", "横版照片效果最好，会自动裁成 16:9"))
    Spacer(Modifier.height(8.dp))
    Text("必应壁纸来自 cn.bing.com，「每日一图」每天自动换", fontSize = 12.sp, color = Palette.textDim)
}

@Composable
private fun WallTile(item: WallItem, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(Tv.FAST, easing = Tv.easeOut), label = "wall")
    val ver by WallpaperRepo.version.collectAsState()
    val thumb by produceState<Bitmap?>(null, item.thumbKey, ver) { value = withContext(Dispatchers.IO) { item.thumb(context) } }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape),
        ) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (item.key == "daily") {
                Text(
                    "每天自动换",
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (item.selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(Palette.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        Text(
            item.label,
            fontSize = 13.sp,
            color = if (focused || item.selected) Palette.textPrimary else Palette.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}

private fun builtinThumb(context: Context): Bitmap? = runCatching {
    BitmapFactory.decodeResource(context.resources, R.drawable.wallpaper_mountain_night, BitmapFactory.Options().apply { inSampleSize = 4 })
}.getOrNull()

private fun currentThumb(): Bitmap? = runCatching {
    WallpaperRepo.file()?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
}.getOrNull()
