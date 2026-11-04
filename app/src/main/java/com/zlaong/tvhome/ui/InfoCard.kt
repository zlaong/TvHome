package com.zlaong.tvhome.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.zlaong.tvhome.data.PrinterRepo
import com.zlaong.tvhome.data.isPrinting
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val CARD_W = 640
private const val CARD_H = 272
private const val TILE_GAP = 24
private const val TILE_MIN_W = 520
/** 平铺时能用的宽度：1920 减左右边距 96×2 */
private const val TILE_SPAN = 1728

/**
 * 左上角的多功能卡：天气 / 打印机 / 米家。
 * 平时只露当前那一页；焦点进卡片区时所有页平铺成一排，左右换卡片，确认键打开。
 * 平铺时卡片内容按原尺寸排版再整体缩小，和单张时长得一样。
 */
@Composable
fun InfoCard(data: HomeData, page: Int, tiled: Boolean, onTap: (Int) -> Unit) {
    val ui = LocalUi.current
    val n = CARD_PAGES.size
    val tileW = ((TILE_SPAN - TILE_GAP * (n - 1)) / n).coerceIn(TILE_MIN_W, CARD_W)
    // 页数多到一排放不下时，整排跟着焦点往左挪
    val overflow = (tileW * n + TILE_GAP * (n - 1) - TILE_SPAN).coerceAtLeast(0)
    val shift = if (n <= 1) 0 else overflow * page / (n - 1)
    val trackX by animateDpAsState(ui.d(if (tiled) -shift else 0), Tv.move(), label = "track")

    Box(Modifier.offset(x = trackX)) {
        CARD_PAGES.forEachIndexed { i, p -> CardTile(data, p, i, page, tiled, tileW, onTap) }
    }
}

@Composable
private fun CardTile(data: HomeData, p: CardPage, i: Int, page: Int, tiled: Boolean, tileW: Int, onTap: (Int) -> Unit) {
    val ui = LocalUi.current
    val current = i == page
    val focused = tiled && current
    val k by animateFloatAsState(if (tiled) tileW / CARD_W.toFloat() else 1f, Tv.move(), label = "tileK")
    val x by animateDpAsState(ui.d(if (tiled) i * (tileW + TILE_GAP) else 0), Tv.move(), label = "tileX")
    val shown by animateFloatAsState(if (tiled || current) 1f else 0f, Tv.focus(), label = "tileShown")
    val lift by animateFloatAsState(if (focused) 1.04f else 1f, Tv.focus(), label = "tileLift")
    val ring by animateFloatAsState(if (focused) 1f else 0f, Tv.focus(), label = "tileRing")
    val sheen by rememberSheen(focused)
    if (shown < 0.01f) return
    val shape = RoundedCornerShape(ui.d(28) * k)

    Box(
        Modifier
            .offset(x = x)
            .graphicsLayer {
                alpha = shown
                scaleX = lift
                scaleY = lift
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .focusRing(ring, ui.d(28) * k, ui.d(5))
            .shadow(ui.d(if (focused) 24 else 16), shape, clip = false)
            .size(ui.d(CARD_W) * k, ui.d(CARD_H) * k)
            .frosted(shape, Tv.glassTint.copy(alpha = 0.46f))
            .border(ui.d(1), Color.White.copy(alpha = 0.12f), shape)
            .sheen(sheen)
            .tap { onTap(i) }
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(ui.d(CARD_W), ui.d(CARD_H))
                .graphicsLayer {
                    scaleX = k
                    scaleY = k
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .padding(start = ui.d(36), end = ui.d(36), top = ui.d(28), bottom = ui.d(30))
        ) {
            when (p) {
                CardPage.Weather -> WeatherPage(data)
                CardPage.Printer -> PrinterPage(data)
                CardPage.Home -> HomePage(data)
            }
        }
    }
}

@Composable
private fun PageFrame(
    icon: TvIcon,
    title: String,
    meta: @Composable () -> Unit,
    body: @Composable () -> Unit,
    foot: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(ui.d(32)), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, ui.d(22), Tv.text2)
            Spacer(Modifier.width(ui.d(10)))
            Text(title, fontSize = ui.t(20), color = Tv.text2, maxLines = 1)
            Spacer(Modifier.weight(1f))
            meta()
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) { body() }
        foot()
    }
}

@Composable
private fun Meta(text: String) {
    Text(text, fontSize = LocalUi.current.t(20), color = Tv.text3, maxLines = 1)
}

@Composable
private fun Big(value: String, unit: String? = null) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = ui.t(80), lineHeight = ui.t(80), fontWeight = FontWeight.Light, color = Tv.text)
        if (unit != null) {
            Text(unit, fontSize = ui.t(36), color = Tv.text2, modifier = Modifier.padding(start = ui.d(4), bottom = ui.d(8)))
        }
    }
}

@Composable
private fun Sub(text: String, color: Color = Tv.text2) {
    Text(text, fontSize = LocalUi.current.t(24), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// ------------------------------------------------------------------ 天气

@Composable
private fun WeatherPage(data: HomeData) {
    val ui = LocalUi.current
    val w = data.weather
    val meta = listOfNotNull(
        w.humidity?.let { "湿度 $it%" },
        if (w.windDir != null && w.windLevel != null) "${w.windDir} ${w.windLevel} 级" else null,
    ).joinToString(" · ")
    PageFrame(
        icon = TvIcon.Pin,
        title = if (w.city.isBlank()) "天气" else "${w.city} · 天气",
        meta = { Meta(meta) },
        body = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ui.d(6))) {
                    when {
                        w.temp != null -> {
                            Big(w.temp.degree())
                            Sub("${w.text} · ${w.low.degree()} / ${w.high.degree()}")
                        }
                        w.error != null -> { Big("—"); Sub(w.error, Tv.text3) }
                        else -> { Big("—"); Sub("正在获取天气…", Tv.text3) }
                    }
                }
                if (w.temp != null) WeatherArt(skyOf(w.code, w.isDay), ui.d(136), Modifier.offset(x = ui.d(8)))
            }
        },
        foot = {
            if (w.hours.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(Color.White.copy(alpha = 0.1f), Offset.Zero, Offset(size.width, 0f), 1f) }
                        .padding(top = ui.d(16)),
                ) {
                    w.hours.forEach { h ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("${h.hour}时", fontSize = ui.t(20), color = Tv.text2)
                            Spacer(Modifier.width(ui.d(8)))
                            WeatherMini(skyOf(h.code, h.isDay), ui.d(26))
                            Spacer(Modifier.width(ui.d(8)))
                            Text("${h.temp.roundToInt()}°", fontSize = ui.t(20), fontWeight = FontWeight.SemiBold, color = Tv.text)
                        }
                    }
                }
            }
        },
    )
}

// ------------------------------------------------------------------ 打印机

private val etaFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

private fun eta(sec: Int, now: Long): String {
    val end = now + sec * 1000L
    val a = Calendar.getInstance().apply { timeInMillis = now }
    val b = Calendar.getInstance().apply { timeInMillis = end }
    val nextDay = a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)
    return "预计${if (nextDay) "明天 " else " "}${etaFmt.format(Date(end))} 完成"
}

@Composable
private fun Badge(text: String, color: Color) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(ui.d(10)).background(color, CircleShape))
        Spacer(Modifier.width(ui.d(8)))
        Text(text, fontSize = ui.t(20), color = Tv.text2)
    }
}

@Composable
private fun PrinterPage(data: HomeData) {
    val ui = LocalUi.current
    val p = data.printer
    val active = p.isPrinting() && p.state.uppercase() != "FAILED"
    val (badge, badgeColor) = when {
        !p.reachable -> "未连接" to Tv.danger
        p.state.uppercase() == "FAILED" -> "出错" to Tv.danger
        active -> p.stateText to Tv.busy
        p.state.uppercase() == "FINISH" -> "已完成" to Tv.ok
        else -> "空闲" to Color.White.copy(alpha = 0.4f)
    }
    PageFrame(
        icon = TvIcon.Printer,
        title = "3D 打印机",
        meta = { Badge(badge, badgeColor) },
        body = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (active) Big("${p.progress}", "%") else Big("—")
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(ui.d(6))) {
                    when {
                        !p.reachable -> { Sub("连不上 Mac mini"); Meta(data.printerHost) }
                        p.state.uppercase() == "FAILED" -> { Sub(p.hms ?: "打印失败"); Meta("请到打印机前查看") }
                        active -> {
                            Sub("剩余 ${PrinterRepo.humanRemaining(p.remainingSec)}")
                            if (p.remainingSec > 0) Meta(eta(p.remainingSec, data.now))
                        }
                        else -> Sub("没有进行中的任务")
                    }
                }
            }
        },
        foot = {
            Column(verticalArrangement = Arrangement.spacedBy(ui.d(12))) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ui.d(8))
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                ) {
                    if (active) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(p.progress / 100f)
                                .background(Tv.busy, RoundedCornerShape(50))
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    val file = when {
                        p.jobName.isBlank() -> ""
                        active -> p.jobName
                        else -> "上次：${p.jobName}"
                    }
                    Text(file, fontSize = ui.t(20), color = Tv.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (active && p.layerNum != null && p.totalLayers != null) {
                        Text("第 ${p.layerNum} / ${p.totalLayers} 层", fontSize = ui.t(20), color = Tv.text3, modifier = Modifier.padding(start = ui.d(16)))
                    }
                }
            }
        },
    )
}

// ------------------------------------------------------------------ 米家

/**
 * 米家卡片，从上到下回答三个问题：
 * 1. 家里现在怎么样：客厅温湿度（大字），空气质量（小字）；
 * 2. 有没有要处理的事：窗没关、滤芯该换、传感器没电……红字，最紧急的排前面；
 * 3. 什么开着：只列运行中的设备，关着的不占位置。
 */
@Composable
private fun HomePage(data: HomeData) {
    val ui = LocalUi.current
    val h = data.home
    val running = h.running
    PageFrame(
        icon = TvIcon.House,
        title = "米家",
        meta = {
            when {
                !h.reachable -> Badge("未连接", Tv.danger)
                h.alerts.isNotEmpty() -> Badge("${h.alerts.size} 件事要注意", Tv.danger)
                else -> Badge("一切正常", Tv.ok)
            }
        },
        body = {
            if (!h.reachable) {
                Column(verticalArrangement = Arrangement.spacedBy(ui.d(6))) {
                    Sub(h.error ?: "家里设备桥没有响应")
                    Meta(data.homeHost)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    EnvBlock(h.env)
                    Spacer(Modifier.width(ui.d(28)))
                    AlertBlock(h.alerts, Modifier.weight(1f))
                }
            }
        },
        foot = {
            if (h.reachable) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(Color.White.copy(alpha = 0.1f), Offset.Zero, Offset(size.width, 0f), 1f) }
                        .padding(top = ui.d(14)),
                    horizontalArrangement = Arrangement.spacedBy(ui.d(12)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        running.isNotEmpty() -> {
                            // 一行放 3 个，多出来的用「+N」，名字不会被挤成省略号
                            running.take(3).forEach { RunningChip(it, Modifier.weight(1f)) }
                            repeat(3 - running.take(3).size) { Spacer(Modifier.weight(1f)) }
                            if (running.size > 3) Meta("+${running.size - 3}")
                        }
                        h.devices.isEmpty() && data.homeUsesHa ->
                            Meta(data.webSetupUrl?.let { "电脑浏览器打开 $it 选择要显示的设备" } ?: "还没选要显示的设备")
                        else -> Meta("设备都关着")
                    }
                }
            }
        },
    )
}

/** 左边：客厅温度大字，湿度、PM2.5、CO₂ 用小图标 + 数字 */
@Composable
private fun EnvBlock(env: com.zlaong.tvhome.data.HomeEnv) {
    val ui = LocalUi.current
    Column(verticalArrangement = Arrangement.spacedBy(ui.d(4))) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                env.temp?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "—",
                fontSize = ui.t(64), lineHeight = ui.t(64), fontWeight = FontWeight.Light, color = Tv.text,
            )
            if (env.temp != null) Text("°", fontSize = ui.t(36), color = Tv.text2, modifier = Modifier.padding(bottom = ui.d(10)))
        }
        val small = listOfNotNull(
            env.humidity?.let { TvIcon.Droplet to "${it.roundToInt()}%" },
            env.pm25?.let { TvIcon.Particles to "${it.roundToInt()}" },
            env.co2?.let { TvIcon.Molecule to "${it.roundToInt()}" },
        )
        if (small.isEmpty()) {
            Text("客厅", fontSize = ui.t(20), color = Tv.text2, maxLines = 1)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ui.d(16)), verticalAlignment = Alignment.CenterVertically) {
                small.forEach { (icon, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, ui.d(22), Tv.text2)
                        Spacer(Modifier.width(ui.d(6)))
                        Text(value, fontSize = ui.t(20), color = Tv.text2, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 右边：要注意的事，最多 3 条；没事时给一句安心话，而不是空着 */
@Composable
private fun AlertBlock(alerts: List<com.zlaong.tvhome.data.HomeAlert>, modifier: Modifier) {
    val ui = LocalUi.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ui.d(8))) {
        if (alerts.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(TvIcon.Check, ui.d(24), Tv.ok)
                Spacer(Modifier.width(ui.d(10)))
                Text("没有要处理的事", fontSize = ui.t(20), color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            val shown = if (alerts.size > 3) alerts.take(2) else alerts
            shown.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TvIcon.Alert, ui.d(22), Tv.danger)
                    Spacer(Modifier.width(ui.d(10)))
                    Text(a.text, fontSize = ui.t(20), color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (alerts.size > 3) Text("还有 ${alerts.size - 2} 件", fontSize = ui.t(18), color = Tv.text3, modifier = Modifier.padding(start = ui.d(32)))
        }
    }
}

/** 底部一个运行中的设备：图标 + 名字 + 状态（「制冷 26°」「自动」「70%」） */
@Composable
private fun RunningChip(d: com.zlaong.tvhome.data.HomeDevice, modifier: Modifier) {
    val ui = LocalUi.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(ui.d(40)).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(deviceIcon(d.kind), ui.d(22), Tv.dark)
        }
        Spacer(Modifier.width(ui.d(10)))
        Column {
            Text(d.name, fontSize = ui.t(20), color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.stateText ?: "开", fontSize = ui.t(16), color = Tv.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun deviceIcon(kind: String): TvIcon = when (kind) {
    "light" -> TvIcon.Bulb
    "fan", "humidifier" -> TvIcon.Fan
    "climate" -> TvIcon.Snow
    "vacuum" -> TvIcon.Robot
    "cover" -> TvIcon.Hanger
    "switch" -> TvIcon.Plug
    else -> TvIcon.House
}
