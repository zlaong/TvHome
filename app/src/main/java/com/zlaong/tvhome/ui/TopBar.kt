package com.zlaong.tvhome.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import com.zlaong.tvhome.data.isPrinting
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Dot { Ok, Busy, Down }

data class ChipInfo(val icon: TvIcon, val label: String, val dot: Dot? = null)

private val clockFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dateFmt = SimpleDateFormat("M月d日 EEE", Locale.CHINA)

fun chipInfo(id: ChipId, data: HomeData): ChipInfo = when (id) {
    ChipId.Settings -> ChipInfo(TvIcon.Gear, "设置")
    ChipId.Cast -> ChipInfo(TvIcon.Cast, "手机投屏")
    ChipId.Wifi -> ChipInfo(
        TvIcon.Wifi,
        when (data.net) {
            Net.Wifi -> "Wi-Fi · 已连接"
            Net.Ethernet -> "有线网络 · 已连接"
            Net.None -> "网络未连接"
        },
        if (data.net == Net.None) Dot.Down else null,
    )
    ChipId.Time -> ChipInfo(TvIcon.Clock, "")
    ChipId.Mac -> {
        val down = !data.printer.reachable
        ChipInfo(TvIcon.Mac, if (down) "Mac mini · 已断开" else "Mac mini · 正常", if (down) Dot.Down else Dot.Ok)
    }
    ChipId.Printer -> {
        val p = data.printer
        when {
            !p.reachable -> ChipInfo(TvIcon.Printer, "3D 打印机 · 未连接", Dot.Down)
            p.state.uppercase() == "FAILED" -> ChipInfo(TvIcon.Printer, "3D 打印机 · 出错", Dot.Down)
            p.isPrinting() -> ChipInfo(TvIcon.Printer, "3D 打印机 · ${p.stateText} ${p.progress}%", Dot.Busy)
            else -> ChipInfo(TvIcon.Printer, "3D 打印机 · 空闲", Dot.Ok)
        }
    }
    ChipId.Home -> {
        val h = data.home
        // 顶栏只表示连接状态；门窗、滤芯这类提醒只在米家卡片里显示
        if (!h.reachable) ChipInfo(TvIcon.House, "米家 · 未连接", Dot.Down)
        else ChipInfo(TvIcon.House, "米家 · 已连接", Dot.Ok)
    }
}

/** 顶栏右侧那行小字：只在有事的时候出现 */
fun statusText(data: HomeData): Pair<String, Boolean>? {
    if (!PRINTER_ENABLED) return null
    val p = data.printer
    return when {
        !p.reachable -> "Mac mini 已断开" to true
        p.state.uppercase() == "FAILED" -> "打印出错" to true
        p.isPrinting() -> "${p.stateText} ${p.progress}%" to false
        else -> null
    }
}

@Composable
fun TopBar(data: HomeData, focusedChip: ChipId?, onTap: (ChipId) -> Unit) {
    val ui = LocalUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ui.d(96), end = ui.d(96), top = ui.d(40)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ui.d(16))) {
            LEFT_CHIPS.forEach { Chip(it, data, focusedChip == it, onTap) }
        }
        Spacer(Modifier.weight(1f))
        statusText(data)?.let { (msg, down) ->
            Text(
                msg,
                style = TextStyle(
                    fontSize = ui.t(24),
                    color = if (down) Tv.danger else Tv.text2,
                    shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1f), 8f),
                ),
                modifier = Modifier.padding(end = ui.d(8)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ui.d(16))) {
            TOP_CHIPS.filter { it !in LEFT_CHIPS }.forEach { Chip(it, data, focusedChip == it, onTap) }
        }
    }
}

@Composable
private fun Chip(id: ChipId, data: HomeData, focused: Boolean, onTap: (ChipId) -> Unit) {
    val ui = LocalUi.current
    val info = chipInfo(id, data)
    val isTime = id == ChipId.Time
    // 时间自己走：只有这一个 chip 重画，不牵动整页
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isTime) {
        if (!isTime) return@LaunchedEffect
        while (true) {
            clock = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, Tv.focus(), label = "chip")
    val down = info.dot == Dot.Down
    val fg = when {
        focused && down -> Color(0xFFC62828)
        focused -> Tv.dark
        down -> Tv.danger
        else -> Tv.text
    }
    val shape = RoundedCornerShape(50)
    val (padStart, padEnd) = when {
        focused -> 20 to 28
        isTime -> 24 to 24
        else -> 17 to 17
    }

    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focused) Modifier.shadow(ui.d(16), shape) else Modifier)
            .height(ui.d(64))
            .widthIn(min = ui.d(64))
            .background(if (focused) Color.White else Tv.glassTint.copy(alpha = 0.42f), shape)
            .border(ui.d(1), if (focused) Color.Transparent else Color.White.copy(alpha = 0.12f), shape)
            .tap { onTap(id) }
            .animateContentSize(Tv.focus())
            .padding(start = ui.d(padStart), end = ui.d(padEnd)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ui.d(12), Alignment.CenterHorizontally),
    ) {
        if (!isTime || focused) {
            Box {
                Icon(info.icon, ui.d(30), fg)
                info.dot?.let { dot ->
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = ui.d(7), y = ui.d(5))
                            .size(ui.d(19))
                            .background(if (focused) Color.White else Color(0xE60A0E16), CircleShape)
                            .padding(ui.d(3))
                            .background(
                                when (dot) {
                                    Dot.Ok -> Tv.ok
                                    Dot.Busy -> Tv.busy
                                    Dot.Down -> Tv.danger
                                },
                                CircleShape,
                            )
                    )
                }
            }
        }
        if (isTime) {
            Text(clockFmt.format(Date(clock)), fontSize = ui.t(24), fontWeight = FontWeight.SemiBold, color = fg)
            if (focused) Text(dateFmt.format(Date(clock)), fontSize = ui.t(24), color = fg.copy(alpha = 0.7f))
        } else if (focused) {
            Text(info.label, fontSize = ui.t(24), color = fg, maxLines = 1)
        }
    }
}
