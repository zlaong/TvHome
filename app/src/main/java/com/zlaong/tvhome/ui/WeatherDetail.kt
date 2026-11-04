package com.zlaong.tvhome.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.zlaong.tvhome.data.DayForecast
import com.zlaong.tvhome.data.HourForecast
import com.zlaong.tvhome.data.WeatherRepo
import com.zlaong.tvhome.data.WeatherUi
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 天气全屏页（在天气卡上按确认键打开）。只看不操作，返回键关闭。
 * 上：现在的天气 + 8 项指标；中：24 小时温度曲线；下：7 天预报。
 */
@Composable
fun WeatherDetail(w: WeatherUi) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize().padding(start = ui.d(96), end = ui.d(96), top = ui.d(64), bottom = ui.d(48))) {
        // 标题行
        Row(Modifier.fillMaxWidth().height(ui.d(44)), verticalAlignment = Alignment.CenterVertically) {
            Icon(TvIcon.Pin, ui.d(28), Tv.text2)
            Spacer(Modifier.width(ui.d(12)))
            Text(w.city.ifBlank { "天气" }, fontSize = ui.t(30), fontWeight = FontWeight.SemiBold, color = Tv.text)
            Spacer(Modifier.weight(1f))
            if (w.updatedAt > 0) Text("${formatTime(w.updatedAt)} 更新 · 返回键关闭", fontSize = ui.t(20), color = Tv.text3)
        }
        Spacer(Modifier.height(ui.d(20)))

        if (w.temp == null) {
            Text(w.error ?: "正在获取天气…", fontSize = ui.t(30), color = Tv.text2)
            return@Column
        }

        // 现在 + 指标
        Row(Modifier.fillMaxWidth().height(ui.d(300)), verticalAlignment = Alignment.CenterVertically) {
            NowBlock(w, Modifier.width(ui.d(640)))
            Spacer(Modifier.width(ui.d(40)))
            MetricGrid(w, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(ui.d(28)))

        SectionTitle("24 小时")
        HourlyChart(w.hourly, Modifier.fillMaxWidth().height(ui.d(250)))
        Spacer(Modifier.height(ui.d(28)))

        SectionTitle("7 天")
        DailyRow(w.daily, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun SectionTitle(text: String) {
    val ui = LocalUi.current
    Text(text, fontSize = ui.t(22), color = Tv.text2, modifier = Modifier.padding(start = ui.d(4), bottom = ui.d(12)))
}

/** 半透明底的块，三个区域共用 */
@Composable
private fun Glass(modifier: Modifier, content: @Composable () -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(ui.d(24))
    Box(
        modifier
            .background(Color.White.copy(alpha = 0.06f), shape)
            .border(ui.d(1), Color.White.copy(alpha = 0.08f), shape),
    ) { content() }
}

// ------------------------------------------------------------------ 现在

@Composable
private fun NowBlock(w: WeatherUi, modifier: Modifier) {
    val ui = LocalUi.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WeatherArt(skyOf(w.code, w.isDay), ui.d(220))
        Spacer(Modifier.width(ui.d(24)))
        Column(verticalArrangement = Arrangement.spacedBy(ui.d(6))) {
            Text(w.temp.degree(), fontSize = ui.t(128), lineHeight = ui.t(128), fontWeight = FontWeight.Light, color = Tv.text)
            Text(w.text, fontSize = ui.t(34), color = Tv.text)
            Text("最低 ${w.low.degree()} · 最高 ${w.high.degree()}", fontSize = ui.t(24), color = Tv.text2)
        }
    }
}

private data class Metric(val label: String, val value: String, val sub: String?, val subColor: Color = Tv.text3)

@Composable
private fun MetricGrid(w: WeatherUi, modifier: Modifier) {
    val ui = LocalUi.current
    val items = listOf(
        Metric("体感", w.feelsLike.degree(), w.feelsLike?.let { feelsText(it, w.temp) }),
        Metric("湿度", w.humidity?.let { "$it%" } ?: "—", w.humidity?.let(::humidityText)),
        Metric("风", w.windLevel?.let { "$it 级" } ?: "—", w.windDir),
        w.aqi?.let { Metric("空气质量", "$it", WeatherRepo.aqiLevel(it), aqiColor(it)) }
            ?: Metric("空气质量", "—", "暂无数据"),
        Metric("紫外线", w.uv?.let { "${it.roundToInt()}" } ?: "—", w.uv?.let(::uvText)),
        Metric("PM2.5", w.pm25?.let { "${it.roundToInt()}" } ?: "—", w.pm10?.let { "PM10 ${it.roundToInt()}" }),
        Metric("能见度", w.visibilityKm?.let { if (it >= 10) "${it.roundToInt()} km" else String.format(Locale.CHINA, "%.1f km", it) } ?: "—",
            w.visibilityKm?.let { if (it >= 10) "很好" else if (it >= 3) "一般" else "较差" }),
        Metric("日出 / 日落", w.sunrise ?: "—", w.sunset?.let { "日落 $it" }),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ui.d(16))) {
        items.chunked(4).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ui.d(16))) {
                row.forEach { m -> MetricTile(m, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun MetricTile(m: Metric, modifier: Modifier) {
    val ui = LocalUi.current
    Glass(modifier) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = ui.d(24), vertical = ui.d(14)),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(m.label, fontSize = ui.t(20), color = Tv.text3, maxLines = 1)
            Text(m.value, fontSize = ui.t(36), lineHeight = ui.t(40), fontWeight = FontWeight.Medium, color = Tv.text, maxLines = 1)
            if (m.sub != null) Text(m.sub, fontSize = ui.t(20), color = m.subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun feelsText(feels: Double, temp: Double?): String {
    val d = feels - (temp ?: feels)
    return when {
        d <= -3 -> "比实际冷"
        d >= 3 -> "比实际热"
        else -> "和实际差不多"
    }
}

private fun humidityText(h: Int) = when {
    h < 30 -> "干燥"
    h > 70 -> "潮湿"
    else -> "舒适"
}

private fun uvText(uv: Double) = when {
    uv < 3 -> "弱"
    uv < 6 -> "中等"
    uv < 8 -> "强，注意防晒"
    else -> "很强，避免暴晒"
}

private fun aqiColor(aqi: Int) = when {
    aqi <= 50 -> Tv.ok
    aqi <= 100 -> Tv.busy
    aqi <= 150 -> Color(0xFFFF9F43)
    else -> Tv.danger
}

// ------------------------------------------------------------------ 24 小时

/**
 * 一整块画布：每小时一列，上面是温度曲线和数字，下面是天气小图、降水概率、时刻。
 * 曲线和文字的横坐标要严格对齐，所以全部在同一张画布里画。
 */
@Composable
private fun HourlyChart(hours: List<HourForecast>, modifier: Modifier) {
    val ui = LocalUi.current
    val measurer = rememberTextMeasurer()
    val tempStyle = TextStyle(fontSize = ui.t(22), fontWeight = FontWeight.SemiBold, color = Tv.text)
    val hourStyle = TextStyle(fontSize = ui.t(20), color = Tv.text2)
    val popStyle = TextStyle(fontSize = ui.t(18), color = Color(0xFF7CC4FF))
    val lineColor = Color.White.copy(alpha = 0.85f)

    Glass(modifier) {
        if (hours.isEmpty()) {
            Text("暂无逐小时预报", fontSize = ui.t(22), color = Tv.text3, modifier = Modifier.padding(ui.d(28)))
            return@Glass
        }
        Canvas(Modifier.fillMaxSize().padding(horizontal = ui.d(20), vertical = ui.d(20))) {
            val n = hours.size
            val col = size.width / n
            val lo = hours.minOf { it.temp }
            val hi = hours.maxOf { it.temp }
            val span = (hi - lo).coerceAtLeast(1.0)
            // 竖向分区：温度数字 + 曲线占上 55%，下面依次是小图、降水、时刻
            val curveTop = ui.px(36, density)
            val curveBottom = size.height * 0.48f
            val iconPx = ui.px(36, density)
            val iconTop = size.height * 0.56f
            fun x(i: Int) = col * (i + 0.5f)
            fun y(t: Double) = (curveBottom - ((t - lo) / span).toFloat() * (curveBottom - curveTop))

            // 曲线下方的渐变填充 + 曲线
            val path = Path()
            val fill = Path()
            hours.forEachIndexed { i, h ->
                val px = x(i); val py = y(h.temp)
                if (i == 0) { path.moveTo(px, py); fill.moveTo(px, curveBottom + ui.px(10, density)); fill.lineTo(px, py) }
                else {
                    // 相邻两点之间用三次贝塞尔，看起来比折线顺
                    val p0x = x(i - 1); val p0y = y(hours[i - 1].temp)
                    val cx = (p0x + px) / 2
                    path.cubicTo(cx, p0y, cx, py, px, py)
                    fill.cubicTo(cx, p0y, cx, py, px, py)
                }
            }
            fill.lineTo(x(n - 1), curveBottom + ui.px(10, density))
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), curveTop, curveBottom + ui.px(10, density)))
            drawPath(path, lineColor, style = Stroke(ui.px(3, density), cap = StrokeCap.Round))

            hours.forEachIndexed { i, h ->
                val cx = x(i)
                val cy = y(h.temp)
                drawCircle(Color.White, ui.px(5, density), Offset(cx, cy))

                // 温度：隔一个标一个，24 个全标太挤；最高、最低点总是标
                val mark = i % 2 == 0 || h.temp == hi || h.temp == lo
                if (mark) {
                    val t = measurer.measure("${h.temp.roundToInt()}°", tempStyle)
                    drawText(t, topLeft = Offset(cx - t.size.width / 2f, cy - t.size.height - ui.px(8, density)))
                }

                translate(cx - iconPx / 2, iconTop) { drawWeatherMini(skyOf(h.code, h.isDay), iconPx) }

                val pop = h.pop
                val popTop = iconTop + iconPx + ui.px(6, density)
                if (pop != null && pop >= 20) {
                    val p = measurer.measure("$pop%", popStyle)
                    drawText(p, topLeft = Offset(cx - p.size.width / 2f, popTop))
                }

                val label = if (i == 0) "${h.hour}时" else if (h.hour == 0) "明天" else "${h.hour}时"
                val l = measurer.measure(label, hourStyle)
                drawText(l, topLeft = Offset(cx - l.size.width / 2f, size.height - l.size.height))
            }
        }
    }
}

// ------------------------------------------------------------------ 7 天

@Composable
private fun DailyRow(days: List<DayForecast>, modifier: Modifier) {
    val ui = LocalUi.current
    if (days.isEmpty()) {
        Glass(modifier) { Text("暂无 7 天预报", fontSize = ui.t(22), color = Tv.text3, modifier = Modifier.padding(ui.d(28))) }
        return
    }
    // 温度条的刻度按这一周的最低、最高算，各天的条才能横向比较
    val weekLo = days.minOf { it.low }
    val weekHi = days.maxOf { it.high }
    val labels = remember(days) { days.mapIndexed { i, d -> dayLabel(i, d.date) } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ui.d(16))) {
        days.forEachIndexed { i, d ->
            Glass(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().padding(vertical = ui.d(18), horizontal = ui.d(18)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(labels[i], fontSize = ui.t(24), fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, color = Tv.text)
                    WeatherMini(skyOf(d.code), ui.d(44))
                    Text(
                        WeatherRepo.describe(d.code).first + (d.pop?.takeIf { it >= 20 }?.let { " $it%" } ?: ""),
                        fontSize = ui.t(20), color = if ((d.pop ?: 0) >= 50) Color(0xFF7CC4FF) else Tv.text2, maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.low.roundToInt()}°", fontSize = ui.t(22), color = Tv.text2)
                        Spacer(Modifier.width(ui.d(10)))
                        RangeBar(d.low, d.high, weekLo, weekHi, Modifier.width(ui.d(80)).height(ui.d(8)))
                        Spacer(Modifier.width(ui.d(10)))
                        Text("${d.high.roundToInt()}°", fontSize = ui.t(22), fontWeight = FontWeight.SemiBold, color = Tv.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeBar(lo: Double, hi: Double, min: Double, max: Double, modifier: Modifier) {
    val span = (max - min).coerceAtLeast(1.0)
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = r)
        val a = ((lo - min) / span).toFloat() * size.width
        val b = ((hi - min) / span).toFloat() * size.width
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF7CC4FF), Color(0xFFFFC857)), a, b.coerceAtLeast(a + 1f)),
            topLeft = Offset(a, 0f),
            size = Size((b - a).coerceAtLeast(size.height), size.height),
            cornerRadius = r,
        )
    }
}

private val WEEK = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

private fun dayLabel(i: Int, date: String): String = when (i) {
    0 -> "今天"
    1 -> "明天"
    else -> runCatching {
        val (y, m, d) = date.split('-').map { it.toInt() }
        val c = Calendar.getInstance().apply { set(y, m - 1, d) }
        WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]
    }.getOrDefault(date.takeLast(5))
}
